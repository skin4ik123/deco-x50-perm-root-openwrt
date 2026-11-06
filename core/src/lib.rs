mod crypto;
mod install;
mod native_protocol;
mod private_storage;
mod ram_boot;
pub mod scripts;
mod temperature;
use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
    sys::jstring,
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    net::{Ipv4Addr, SocketAddr, TcpStream},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
pub type Result<T> = std::result::Result<T, String>;
pub trait Platform {
    fn call(&mut self, request: Value) -> Result<Value>;
}
static MUTATION: AtomicBool = AtomicBool::new(false);
pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
pub fn text<'a>(v: &'a Value, key: &str) -> Result<&'a str> {
    v.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("Missing request field: {key}"))
}
pub fn nonce() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}
pub fn hex(data: &[u8]) -> String {
    data.iter().map(|b| format!("{b:02x}")).collect()
}
pub fn sha256(data: &[u8]) -> String {
    hex(&Sha256::digest(data))
}
pub fn sha1(data: &[u8]) -> String {
    use sha1::Sha1;
    hex(&Sha1::digest(data))
}
pub fn b64(data: &[u8]) -> String {
    B64.encode(data)
}
pub fn decode(data: &str) -> Result<Vec<u8>> {
    B64.decode(data)
        .map_err(|_| "Invalid device response".into())
}
pub fn lua(s: &str) -> String {
    let mut out = String::from("\"");
    for b in s.as_bytes() {
        match b {
            b'"' => out.push_str("\\\""),
            b'\\' => out.push_str("\\\\"),
            32..=126 => out.push(*b as char),
            _ => out.push_str(&format!("\\{:03}", b)),
        }
    }
    out.push('"');
    out
}
pub fn shell(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}
pub fn target(s: &str) -> Result<String> {
    let ip: Ipv4Addr = s
        .parse()
        .map_err(|_| "Enter the Deco IPv4 address".to_string())?;
    let o = ip.octets();
    if !ip.is_private() || o[3] == 0 || o[3] == 255 {
        return Err("Enter a local unicast IPv4 address".into());
    }
    Ok(ip.to_string())
}
pub fn save(path: &Path, value: &Value) -> Result<()> {
    if let Some(p) = path.parent() {
        fs::create_dir_all(p).map_err(|_| "Could not create the local directory".to_string())?;
    }
    let temp = path.with_extension(format!("{}.new", nonce()));
    let mut f = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temp)
        .map_err(|_| "Could not save the operation journal".to_string())?;
    use std::os::unix::fs::PermissionsExt;
    f.set_permissions(fs::Permissions::from_mode(0o600))
        .map_err(|_| "Could not set journal permissions".to_string())?;
    f.write_all(serde_json::to_string(value).unwrap().as_bytes())
        .and_then(|_| f.sync_all())
        .map_err(|_| "Could not write the operation journal".to_string())?;
    fs::rename(&temp, path).map_err(|_| "Could not commit the operation journal".into())
}
pub fn load(path: &Path) -> Result<Value> {
    let mut f =
        fs::File::open(path).map_err(|_| "Local operation journal not found".to_string())?;
    let mut bytes = vec![];
    std::io::Read::by_ref(&mut f)
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Could not read the operation journal".to_string())?;
    if bytes.len() > 4 * 1024 * 1024 {
        return Err("Operation journal exceeds the size limit".into());
    }
    serde_json::from_slice(&bytes).map_err(|_| "Local operation journal is damaged".into())
}
pub struct Engine<'a> {
    pub p: &'a mut dyn Platform,
    pub home: PathBuf,
    pub host: String,
}
impl Engine<'_> {
    pub fn event(&mut self, s: &str) -> Result<()> {
        self.p
            .call(json!({"op":"event","text":s,"host":self.host}))?;
        Ok(())
    }
    pub fn exec(&mut self, command: &str, input: &[u8], limit: usize) -> Result<Vec<u8>> {
        let r=self.p.call(json!({"op":"exec","host":self.host,"command":command,"input":b64(input),"limit":limit}))?;
        if r["exit"] != 0 {
            return Err("Command did not complete successfully. Check the operation result".into());
        }
        decode(text(&r, "data")?)
    }
    pub fn run_lua(&mut self, source: &str) -> Result<Value> {
        let d = self.exec("lua -", source.as_bytes(), 1024 * 1024)?;
        serde_json::from_slice(&d).map_err(|_| "Deco returned an invalid Lua response".into())
    }
    pub fn read(&mut self, path: &str, limit: usize) -> Result<Vec<u8>> {
        let s = format!(
            "{}\nlocal f=assert(io.open({},'rb'));local s=f:read({}) or '';f:close();assert(#s<={});io.write(require('luci.json').encode({{data=encode64(s)}}))",
            scripts::ENCODE64,
            lua(path),
            limit + 1,
            limit
        );
        let r = self.run_lua(&s)?;
        decode(text(&r, "data")?)
    }
    pub fn upload(&mut self, path: &str, data: &[u8]) -> Result<()> {
        if !path.starts_with("/tmp/deco-control-")
            || path.contains("..")
            || path
                .chars()
                .any(|c| !(c.is_ascii_alphanumeric() || "/._-".contains(c)))
        {
            return Err("Invalid temporary file path".into());
        }
        self.exec(
            &format!(
                "umask 077; cat > {} && test \"$(sha1sum {} | cut -d ' ' -f 1)\" = {}",
                shell(path),
                shell(path),
                sha1(data)
            ),
            data,
            2048,
        )?;
        Ok(())
    }
    pub fn connect(&mut self) -> Result<()> {
        self.p.call(json!({"op":"connect","host":self.host}))?;
        let uid = self.exec("id -u", &[], 1024)?;
        if uid != b"0\n" {
            return Err("SSH connected but UID 0 was not confirmed".into());
        }
        Ok(())
    }
    pub fn boot(&mut self) -> Result<String> {
        let d = self.read("/proc/sys/kernel/random/boot_id", 100)?;
        let s = String::from_utf8(d).map_err(|_| "Invalid boot identifier".to_string())?;
        let s = s.trim();
        if s.len() != 36 || !s.chars().all(|c| c.is_ascii_hexdigit() || c == '-') {
            return Err("Invalid boot identifier".into());
        }
        Ok(s.into())
    }
    pub fn journal(&self) -> PathBuf {
        self.home
            .join("nodes")
            .join(&self.host)
            .join("operation.json")
    }
    pub fn configure(&mut self) -> Result<()> {
        let node = self.home.join("nodes").join(&self.host);
        fs::create_dir_all(&node).map_err(|_| "Could not create device directory".to_string())?;
        Ok(())
    }
}
fn list_local(home: &Path) -> Value {
    let mut out = vec![];
    if let Ok(entries) = fs::read_dir(home.join("nodes")) {
        for entry in entries.flatten() {
            let n = entry.path();
            if let Ok(mut v) = load(&n.join("device.json")) {
                v["host"] = json!(entry.file_name().to_string_lossy());
                out.push(v)
            }
        }
    }
    json!(out)
}
pub fn parse_snapshot(mut v: Value, old: Option<Value>) -> Value {
    temperature::normalize(&mut v);
    let uptime = v["uptime"].as_f64().unwrap_or(0.);
    let cpu = v["cpu_raw"]
        .as_str()
        .unwrap_or("")
        .split_whitespace()
        .skip(1)
        .filter_map(|n| n.parse::<u64>().ok())
        .collect::<Vec<_>>();
    let total = cpu.iter().take(8).sum::<u64>();
    let idle = cpu.get(3).copied().unwrap_or(0) + cpu.get(4).copied().unwrap_or(0);
    v["cpu_total"] = json!(total);
    v["cpu_idle"] = json!(idle);
    v["observed_at"] = json!(now());
    v["cpu_percent"] = Value::Null;
    if let Some(o) = old {
        if o["boot_id"] == v["boot_id"] {
            let t = total.saturating_sub(o["cpu_total"].as_u64().unwrap_or(total));
            let i = idle.saturating_sub(o["cpu_idle"].as_u64().unwrap_or(idle));
            if t > 0 {
                v["cpu_percent"] = json!(100. * (t - i.min(t)) as f64 / t as f64)
            }
            let elapsed = uptime - o["uptime"].as_f64().unwrap_or(uptime);
            if elapsed > 0. {
                if let Some(net) = v["interfaces"].as_array_mut() {
                    for n in net {
                        if let Some(previous) = o["interfaces"]
                            .as_array()
                            .and_then(|a| a.iter().find(|p| p["name"] == n["name"]))
                        {
                            for (field, rate) in [("rx", "rx_per_second"), ("tx", "tx_per_second")]
                            {
                                let diff = n[field]
                                    .as_u64()
                                    .unwrap_or(0)
                                    .saturating_sub(previous[field].as_u64().unwrap_or(0));
                                n[rate] = json!(diff as f64 / elapsed);
                            }
                        }
                    }
                }
            }
        }
    }
    v
}
fn action(e: &mut Engine<'_>, name: &str, args: &Value) -> Result<Value> {
    e.configure()?;
    match name {
        "ram_status" | "ram_prepare" | "ram_start" | "ram_check_stock"
        | "ram_check_openwrt" | "ram_restore" => ram_boot::dispatch(e, name, args),
        "connect" => {
            e.connect()?;
            let info = e.run_lua(scripts::IDENTITY)?;
            if info["uid"] != 0 {
                return Err("Root access required".into());
            }
            let mut record =
                load(&e.home.join("nodes").join(&e.host).join("device.json")).unwrap_or(json!({}));
            record["identity"] = info.clone();
            record["last_seen"] = json!(now());
            save(
                &e.home.join("nodes").join(&e.host).join("device.json"),
                &record,
            )?;
            Ok(info)
        }
        "owner_check" => install::owner_check(e, args),
        "bootstrap" => install::bootstrap(e, args),
        "install" => install::install(e, args),
        "result" => install::result(e),
        "continue_install" => install::continue_install(e),
        "boot_check" => install::boot_check(e),
        "ssh_health" => {
            let mut v = e.p.call(json!({"op":"ssh_probe","host":e.host}))?;
            if v["tcp_ok"] == true && v["banner_ok"] == true && e.connect().is_ok() {
                match e.run_lua(scripts::SSH_HEALTH) {
                    Ok(h) => v["server"] = h,
                    Err(err) => v["server_error"] = json!(err),
                }
            }
            v["last_connection"] = e.p.call(json!({"op":"connection_timing","host":e.host}))?;
            Ok(v)
        }
        "metrics" => {
            e.connect()?;
            let value = e.run_lua(scripts::MONITOR)?;
            let path = e.home.join("nodes").join(&e.host).join("last-metrics.json");
            let parsed = parse_snapshot(value, load(&path).ok());
            save(&path, &parsed)?;
            Ok(parsed)
        }
        "inventory" => {
            e.connect()?;
            let nodes = e.run_lua(scripts::INVENTORY)?;
            save(&e.home.join("mesh.json"), &nodes)?;
            Ok(nodes)
        }
        "files" => {
            e.connect()?;
            let path = text(args, "path")?;
            if path.len() > 4096 || path.contains('\0') {
                return Err("Invalid path".into());
            }
            e.run_lua(&scripts::files(path))
        }
        "read_file" => {
            e.connect()?;
            let path = text(args, "path")?;
            if !path.starts_with('/') || path.len() > 4096 || path.contains('\0') {
                return Err("Invalid file path".into());
            }
            let metadata = e.run_lua(&scripts::read_file(path))?;
            let bytes = decode(text(&metadata, "data")?)?;
            if bytes.contains(&0) { return Err("This is a binary file. Use Download".into()); }
            let data = String::from_utf8(bytes)
                .map_err(|_| "This is a binary file. Use Download".to_string())?;
            Ok(json!({"text":data,"path":path,"canonical":metadata["canonical"],"writable":metadata["writable"]}))
        }
        "write_file" => {
            e.connect()?;
            let path = text(args, "path")?;
            if !(path.starts_with("/tmp/") || path.starts_with("/tp_data/user_data/"))
                || path.contains("..")
                || path.contains('\0')
            {
                return Err("Editing is allowed in /tmp and /tp_data/user_data".into());
            }
            let data = text(args, "text")?;
            if data.len() > 256 * 1024 {
                return Err("File exceeds 256 KiB".into());
            }
            e.run_lua(&scripts::write_file(path, data, &nonce()))
        }
        "download" => {
            e.connect()?;
            let path = text(args, "path")?;
            if path.contains('\0') || path.len() > 4096 || path.starts_with("/dev/") {
                return Err("Path not allowed. Use the NAND backup tool for flash data".into());
            }
            let dir = e.home.join("exports");
            fs::create_dir_all(&dir)
                .map_err(|_| "Could not create export directory".to_string())?;
            let filename = Path::new(path)
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("download.bin");
            let local = dir.join(format!("{}-{}-{filename}", e.host, nonce()));
            e.p.call(json!({"op":"download","host":e.host,"command":format!("cat {}",shell(path)),"path":local,"max":64*1024*1024}))
        }
        "upload" => {
            e.connect()?;
            let src = PathBuf::from(text(args, "local")?);
            if !src.starts_with(e.home.join("imports")) {
                return Err("Select a local file in the app first".into());
            }
            let dir = text(args, "path")?;
            if !(dir.starts_with("/tmp/") || dir.starts_with("/tp_data/user_data/"))
                || dir.contains("..")
                || dir.contains('\0')
            {
                return Err("Uploads are allowed in /tmp and /tp_data/user_data".into());
            }
            e.p.call(json!({"op":"upload","host":e.host,"path":src,"remote":dir}))
        }
        "logs" => {
            e.connect()?;
            let b = e.exec("logread | tail -n 400", &[], 256 * 1024)?;
            Ok(json!({"text":String::from_utf8_lossy(&b)}))
        }
        "processes" => {
            e.connect()?;
            e.run_lua(scripts::PROCESSES)
        }
        "services" => {
            e.connect()?;
            e.run_lua(scripts::SERVICES)
        }
        "clients" => {
            e.connect()?;
            e.run_lua(scripts::CLIENTS)
        }
        "diagnostics" => {
            e.connect()?;
            e.run_lua(scripts::DIAGNOSTICS)
        }
        "locate" => {
            e.connect()?;
            e.run_lua(scripts::LOCATE)
        }
        "reboot" => {
            if args["confirmed"] != true {
                return Err("Confirm reboot of the selected Deco".into());
            }
            e.connect()?;
            let boot = e.boot()?;
            let path = e.home.join("nodes").join(&e.host).join("reboot.json");
            save(
                &path,
                &json!({"boot_before":boot,"requested_at":now(),"phase":"request_sent"}),
            )?;
            e.exec(
                "/bin/sh -c '(sleep 3; reboot) </dev/null >/dev/null 2>&1 &'",
                &[],
                2048,
            )?;
            Ok(json!({"scheduled":true,"host":e.host}))
        }
        "password" => install::change_password(e, args),
        "backup" => install::backup_config(e),
        "nand_backup" => install::backup_nand(e),
        "export_report" => {
            let report = json!({"application":format!("Deco Control {}",env!("CARGO_PKG_VERSION")),"created_at":now(),"target":e.host,"device":load(&e.home.join("nodes").join(&e.host).join("device.json")).ok(),"operation":load(&e.journal()).ok().map(|v|json!({"phase":v["phase"],"status":v["status"],"action":v["action"],"boot_before":v["boot_before"]})),"ssh_timing":e.p.call(json!({"op":"connection_timing","host":e.host}))?,"temperature_sensors":load(&e.home.join("nodes").join(&e.host).join("last-metrics.json")).ok().map(|v|v["temperatures"].clone()),"owner_ssh_timing":e.p.call(json!({"op":"owner_connection_timing","host":e.host}))?,"bootstrap_phase":load(&e.home.join("nodes").join(&e.host).join("bootstrap.json")).ok().map(|v|v["phase"].clone()),"credentials_included":false,"terminal_or_logs_included":false});
            let path = e
                .home
                .join("exports")
                .join(format!("Deco-{}-{}.json", e.host, now()));
            save(&path, &report)?;
            Ok(json!({"path":path}))
        }
        _ => Err("Unknown operation".into()),
    }
}
fn dispatch(p: &mut dyn Platform, args: &Value) -> Result<Value> {
    let home = PathBuf::from(text(args, "home")?);
    if !home.is_absolute() || home.to_string_lossy().contains("..") {
        return Err("Invalid app storage directory".into());
    }
    let name = text(args, "action")?;
    if name == "local_devices" {
        return Ok(list_local(&home));
    }
    if name == "discover" {
        let mut result = vec![];
        let hosts = args["hosts"]
            .as_array()
            .ok_or("No device addresses supplied")?;
        if hosts.len() > 32 {
            return Err("Check up to 32 devices at once".into());
        }
        for v in hosts {
            let host = target(v.as_str().ok_or("Invalid device address")?)?;
            let ip: Ipv4Addr = host.parse().unwrap();
            let mut ports = vec![];
            for port in [22, 20001] {
                if TcpStream::connect_timeout(
                    &SocketAddr::from((ip, port)),
                    Duration::from_millis(400),
                )
                .is_ok()
                {
                    ports.push(port)
                }
            }
            result.push(json!({"host":host,"ports":ports}));
        }
        return Ok(json!(result));
    }
    let host = target(text(args, "host")?)?;
    let mutation = [
        "install",
        "continue_install",
        "bootstrap",
        "password",
        "reboot",
        "write_file",
        "upload",
        "locate",
        "ram_prepare",
        "ram_start",
        "ram_check_stock",
        "ram_check_openwrt",
        "ram_restore",
    ]
    .contains(&name);
    if mutation
        && MUTATION
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
    {
        return Err("Another operation is still running".into());
    }
    struct Guard(bool);
    impl Drop for Guard {
        fn drop(&mut self) {
            if self.0 {
                MUTATION.store(false, Ordering::SeqCst)
            }
        }
    }
    let _guard = Guard(mutation);
    let mut engine = Engine { p, home, host };
    if mutation {
        ram_boot::guard_pending(&engine, name)?;
        if !matches!(name, "ram_check_stock" | "ram_check_openwrt" | "ram_restore") {
            install::guard_pending(&engine, name)?;
        }
    }
    action(&mut engine, name, args)
}
struct JavaPlatform<'a, 'b> {
    env: &'a mut JNIEnv<'b>,
    object: JObject<'b>,
}
impl Platform for JavaPlatform<'_, '_> {
    fn call(&mut self, request: Value) -> Result<Value> {
        // A root installation can make hundreds of callbacks in one JNI entry.
        // Each callback has its own bounded local-reference frame.
        let object = &self.object;
        let input = request.to_string();
        let output: std::result::Result<String, jni::errors::Error> =
            self.env.with_local_frame(16, |env| {
                let argument = JObject::from(env.new_string(input)?);
                let result = env
                    .call_method(
                        object,
                        "perform",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        &[JValue::Object(&argument)],
                    )?
                    .l()?;
                let output = JString::from(result);
                let value: String = env.get_string(&output)?.into();
                Ok(value)
            });
        let output = output.map_err(|_| "Android bridge call failed".to_string())?;
        let value: Value = serde_json::from_str(&output)
            .map_err(|_| "Invalid Android bridge response".to_string())?;
        if value["ok"] == false {
            return Err(value["error"]
                .as_str()
                .unwrap_or("Connection failed")
                .into());
        }
        Ok(value)
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_internetdeco_decocontrol_Native_run<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
    bridge: JObject<'local>,
) -> jstring {
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let request: String = env
            .get_string(&input)
            .map_err(|_| "Invalid Android request".to_string())?
            .into();
        let value: Value =
            serde_json::from_str(&request).map_err(|_| "Invalid request".to_string())?;
        let mut p = JavaPlatform {
            env: &mut env,
            object: bridge,
        };
        dispatch(&mut p, &value)
    }));
    let response = match outcome {
        Ok(Ok(v)) => json!({"ok":true,"data":v}),
        Ok(Err(e)) => json!({"ok":false,"error":e}),
        Err(_) => {
            json!({"ok":false,"error":"Operation interrupted. Check its result before retrying"})
        }
    };
    env.new_string(response.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_internetdeco_decocontrol_Native_prepareStorage<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, home: JString<'local>, host: JString<'local>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
        let home: String = env.get_string(&home).map_err(|_| "Invalid app storage path".to_string())?.into();
        let host: String = env.get_string(&host).map_err(|_| "Invalid device address".to_string())?.into();
        private_storage::prepare(Path::new(&home), &host)
    }));
    let error = match result { Ok(Ok(())) => String::new(), Ok(Err(e)) => e, Err(_) => "Could not prepare app storage".into() };
    env.new_string(error).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn concurrent_device_downloads_never_share_a_destination() {
        struct DownloadPlatform;
        impl Platform for DownloadPlatform {
            fn call(&mut self, request: Value) -> Result<Value> {
                match request["op"].as_str().unwrap() {
                    "connect" => Ok(json!({})),
                    "exec" => Ok(json!({"exit":0,"data":b64(b"0\n")})),
                    "download" => Ok(json!({"path":request["path"]})),
                    _ => panic!("unexpected operation"),
                }
            }
        }
        let home = std::env::temp_dir().join(nonce());
        let mut platform = DownloadPlatform;
        let mut paths = std::collections::BTreeSet::new();
        for i in 0..64 {
            let mut engine = Engine { p:&mut platform, home:home.clone(), host:if i%2==0 {"10.20.30.40"}else{"10.20.30.41"}.into() };
            let response = action(&mut engine,"download",&json!({"path":"/tmp/same-name.txt"})).unwrap();
            let path = response["path"].as_str().unwrap().to_string();
            assert!(Path::new(&path).starts_with(home.join("exports")));
            assert!(paths.insert(path));
        }
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn guards() {
        for ip in ["127.0.0.1", "8.8.8.8", "192.168.88.255", "10.0.0.0"] {
            assert!(target(ip).is_err(), "{ip}")
        }
        for ip in ["192.168.1.1", "10.20.30.40", "172.16.5.9"] {
            assert!(target(ip).is_ok());
        }
        assert_eq!(lua("a\n\"é"), "\"a\\010\\\"\\195\\169\"");
        assert_eq!(shell("a'b"), "'a'\\''b'")
    }
    #[test]
    fn cpu_delta() {
        let a = json!({"cpu_total":100,"cpu_idle":50,"boot_id":"a","uptime":1.});
        let b = json!({"cpu_raw":"cpu 50 0 50 100 0 0 0 0","boot_id":"a","uptime":2.});
        assert_eq!(parse_snapshot(b, Some(a))["cpu_percent"], 50.)
    }
    #[test]
    fn report_is_local_and_journal_atomic() {
        let p = std::env::temp_dir().join(nonce());
        save(&p, &json!({"phase":"before"})).unwrap();
        save(&p, &json!({"phase":"after"})).unwrap();
        assert_eq!(load(&p).unwrap()["phase"], "after");
        fs::remove_file(p).unwrap();
    }
}
