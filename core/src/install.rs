use crate::*;
use serde_json::json;
const CGI: &str = "/www/cgi-bin/luci";
const DISPATCHER: &str = "/usr/lib/lua/luci/dispatcher.lua";
const CACHE: &str = "/tmp/luci-indexcache";
const CACHE_HASH: &str = "644a60356044d33d06b64784a12a7937432271a5824810d506f3098b40277def";
const BASE: &str = "/tp_data/user_data/internetdeco-ssh";
fn metadata() -> Value {
    serde_json::from_str(include_str!("../assets/payloads.json")).expect("build metadata")
}
fn asset(name: &str) -> &'static [u8] {
    match name {
        "temporary" => include_bytes!("../assets/temporary-server.gz"),
        "password" => include_bytes!("../assets/password-server.gz"),
        "reader" => include_bytes!("../assets/nand-reader"),
        _ => &[],
    }
}
fn bootstrappath(e: &Engine<'_>) -> PathBuf {
    e.home.join("nodes").join(&e.host).join("bootstrap.json")
}
fn writepath(e: &Engine<'_>) -> PathBuf {
    e.home.join("nodes").join(&e.host).join("native-write.json")
}
fn nodepath(e: &Engine<'_>) -> PathBuf {
    e.home.join("nodes").join(&e.host).join("device.json")
}
fn ascii_bytes(value: &str) -> Result<Vec<u8>> {
    value
        .chars()
        .map(|c| {
            u8::try_from(c as u32)
                .map_err(|_| "Invalid byte encoding in the Deco file response".into())
        })
        .collect()
}
struct Rpc {
    main: String,
    host: String,
    port: u16,
    peer: Option<String>,
    namespace: String,
}
fn main_lua(e: &mut Engine<'_>, main: &str, source: &str) -> Result<Value> {
    let old = std::mem::replace(&mut e.host, main.to_string());
    let reply = e.run_lua(source);
    e.host = old;
    reply
}
fn rpc(
    e: &mut Engine<'_>,
    r: &Rpc,
    opcode: u16,
    path: &str,
    content: Option<&str>,
) -> Result<Value> {
    if opcode != 0xf0 && opcode != 0xc456 {
        return Err("Unreviewed operation code".into());
    }
    let mut params = json!({"file":path});
    if let Some(c) = content {
        params["content"] = json!(c)
    }
    if let Some(peer) = &r.peer {
        let token = nonce();
        let wire = format!("/tmp/deco-control-relay-{}-{token}", r.namespace);
        let req = json!({"opcode":opcode,"target_id":peer,"save":wire,"data":{"params":params}});
        let code = format!(
            "{}\nlocal j=require('luci.json');local u=assert(require('ubus').connect());local path={};assert(not require('nixio.fs').lstat(path));local f=assert(io.open(path,'wb'));f:close();u:call('sync','request',assert(j.decode({})));u:close();f=assert(io.open(path,'rb'));local raw=f:read(262145) or '';f:close();assert(#raw<=262144);local v=assert(j.decode(raw));assert(v.success==1 and v.total==1 and type(v.data)=='table');local d=v.data;local result={{error_code=d.error_code}};if type(d.result)=='table' and type(d.result.data)=='string' then assert(#d.result.data<=131072);result.data_b64=encode64(d.result.data)end;local encoded=j.encode(result);f=assert(io.open(path..'.done','wb'));assert(f:write(encoded));assert(f:close());io.write(encoded)",
            scripts::ENCODE64,
            lua(&wire),
            lua(&req.to_string())
        );
        match main_lua(e, &r.main, &code) {
            Ok(v) => Ok(v),
            Err(_) => {
                let c = format!(
                    "local f=assert(io.open({},'rb'));local s=f:read(262145);f:close();io.write(s)",
                    lua(&(wire + ".done"))
                );
                main_lua(e, &r.main, &c)
            }
        }
    } else {
        let body = serde_json::to_vec(&json!({"params":params})).unwrap();
        let data = crate::native_protocol::request(r.port, opcode, &body)
            .map_err(|err| format!("{}:20001 · owner file channel: {err}", r.main))?;
        let v: Value =
            serde_json::from_str(&data.iter().copied().map(char::from).collect::<String>())
                .map_err(|_| "Invalid response from the owner channel".to_string())?;
        Ok(v)
    }
}
fn rpc_read(e: &mut Engine<'_>, r: &Rpc, path: &str) -> Result<Vec<u8>> {
    let v = rpc(e, r, 0xf0, path, None)?;
    if v["error_code"] != 0 {
        return Err("Deco did not permit reading this file".into());
    }
    if let Some(b) = v["data_b64"].as_str() {
        decode(b)
    } else {
        ascii_bytes(text(&v["result"], "data")?)
    }
}
fn rpc_write(e: &mut Engine<'_>, r: &Rpc, path: &str, content: &str) -> Result<()> {
    if content.len() > 7500
        || !(path == CACHE || path.starts_with(&format!("/tmp/deco-x50-ssh-{}", r.namespace)))
    {
        return Err("Write path is outside the prepared operation".into());
    }
    let journal = writepath(e);
    if journal.exists() && load(&journal)?["pending"] == true {
        return Err("The previous write is unconfirmed. Check its result first".into());
    }
    let j = json!({"pending":true,"path":path,"content":content,"host":r.host,"main":r.main,"peer":r.peer,"namespace":r.namespace});
    save(&journal, &j)?;
    let ack = rpc(e, r, 0xc456, path, Some(content));
    let good = ack.as_ref().is_ok_and(|v| v["error_code"] == 0);
    if !good && rpc_read(e, r, path).ok().as_deref() != Some(content.as_bytes()) {
        return Err("Reply lost. The write is journaled and automatic retry is blocked".into());
    }
    save(
        &journal,
        &json!({"pending":false,"path":path,"confirmed_at":now()}),
    )
}
fn routed_rpc(e: &mut Engine<'_>, args: &Value, namespace: &str) -> Result<Rpc> {
    let main = target(
        args["owner_host"]
            .as_str()
            .filter(|v| !v.is_empty())
            .unwrap_or(&e.host),
    )?;
    let owner = e.p.call(json!({"op":"owner","host":main}))?;
    let port = owner["port"]
        .as_u64()
        .filter(|p| *p > 0 && *p < 65536)
        .ok_or("Could not create the SSH channel")? as u16;
    let peer = if main == e.host {
        None
    } else {
        let old = e.host.clone();
        e.host = main.clone();
        let main_args = json!({"owner_host":main});
        let result = bootstrap(e, &main_args);
        e.host = old;
        result?;
        let inventory = main_lua(e, &main, scripts::INVENTORY)?;
        let entries = inventory
            .as_object()
            .ok_or("Could not read mesh inventory")?;
        let (id, node) = entries
            .iter()
            .find(|(_, n)| n["ip"] == e.host)
            .ok_or("The selected Deco is not in the mesh inventory")?;
        if node["device_model"] != "X50"
            || node["role"] != "RE"
            || !id.chars().all(|c| c.is_ascii_hexdigit())
            || !(16..=64).contains(&id.len())
        {
            return Err("This root profile supports a verified Deco X50 satellite".into());
        }
        Some(id.clone())
    };
    Ok(Rpc {
        main,
        host: e.host.clone(),
        port,
        peer,
        namespace: namespace.into(),
    })
}
/// Authenticated read-only owner access check. No bootstrap or configuration writes.
pub fn owner_check(e: &mut Engine<'_>, args: &Value) -> Result<Value> {
    let controller = target(
        args["owner_host"]
            .as_str()
            .filter(|v| !v.is_empty())
            .unwrap_or(&e.host),
    )?;
    e.event(&format!("Checking owner SSH at {controller}:20001"))?;
    let owner = e.p.call(json!({"op":"owner","host":controller}))?;
    let port = owner["port"]
        .as_u64()
        .filter(|v| *v > 0 && *v < 65536)
        .ok_or("Owner SSH channel did not open")? as u16;
    let raw =
        crate::native_protocol::request(port, 0xf0, br#"{"params":{"file":"/proc/self/status"}}"#)
            .map_err(|err| format!("{controller}:20001 · owner file channel: {err}"))?;
    let value: Value =
        serde_json::from_str(&raw.iter().copied().map(char::from).collect::<String>())
            .map_err(|_| "Invalid owner file service reply")?;
    let status = text(&value["result"], "data")?;
    if value["error_code"] != 0
        || !status
            .lines()
            .any(|l| l.starts_with("Uid:") && l.split_whitespace().skip(1).eq(["0", "0", "0", "0"]))
    {
        return Err("Owner file service did not confirm UID 0".into());
    }
    Ok(
        json!({"controller":controller,"ssh_port":20001,"file_service":true,"uid":0,"request_id":args["request_id"]}),
    )
}
fn can_restart_staging(state: &Value, pending: Option<&Value>, cache_hash: &str) -> bool {
    ["upload_restore", "upload_server"].contains(&state["phase"].as_str().unwrap_or(""))
        && !pending.is_some_and(|v| v["pending"] == true)
        && cache_hash == CACHE_HASH
}
pub fn bootstrap(e: &mut Engine<'_>, args: &Value) -> Result<Value> {
    // Existing ordinary root SSH is always preferred. No bootstrap write occurs
    // merely because a saved connection can already authenticate.
    let existing = e.connect();
    if existing
        .as_ref()
        .err()
        .is_some_and(|v| v.to_ascii_lowercase().contains("host key"))
    {
        return Err(existing.unwrap_err());
    }
    if existing.is_ok() {
        let actual = e.run_lua(scripts::IDENTITY)?;
        return Ok(json!({"ready":true,"identity":actual,"source":"password_ssh"}));
    }
    let path = bootstrappath(e);
    if path.exists() {
        let state = load(&path)?;
        if state["phase"] == "ready" {
            let key = text(&state, "public_key")?;
            if e.p
                .call(json!({"op":"temporary_connect","host":e.host,"port":20003,"key":key}))
                .is_ok()
            {
                e.connect()?;
                return Ok(json!({"ready":true,"source":"temporary_ssh"}));
            }
            // A new authenticated boot proves the old RAM-only server is gone.
            // Failure on the same boot is never treated as permission to retry.
            let r = routed_rpc(e, args, &nonce())?;
            let current =
                String::from_utf8_lossy(&rpc_read(e, &r, "/proc/sys/kernel/random/boot_id")?)
                    .trim()
                    .to_string();
            if current.len() != 36
                || state["boot_before"]
                    .as_str()
                    .is_none_or(|old| old == current)
            {
                return Err(
                    "Temporary SSH is unavailable on the same boot. Check the network and operation result"
                        .into(),
                );
            }
            save(
                &path.with_file_name(format!("bootstrap-completed-{}.json", nonce())),
                &state,
            )?;
            fs::remove_file(&path)
                .map_err(|_| "Could not archive the completed preparation".to_string())?;
        } else if ["upload_restore", "upload_server"]
            .contains(&state["phase"].as_str().unwrap_or(""))
        {
            let pending = if writepath(e).exists() {
                Some(load(&writepath(e))?)
            } else {
                None
            };
            if pending.as_ref().is_some_and(|v| v["pending"] == true) {
                return Err(
                    "A staged write is unconfirmed. Use Check result before retrying".into(),
                );
            }
            let r = routed_rpc(e, args, &nonce())?;
            let original = rpc_read(e, &r, CACHE)?;
            if state["host"] != e.host
                || !can_restart_staging(&state, pending.as_ref(), &sha256(&original))
            {
                return Err(
                    "Previous preparation cannot be safely restarted. Use Check result".into(),
                );
            }
            save(
                &path.with_file_name(format!("bootstrap-inactive-{}.json", nonce())),
                &state,
            )?;
            fs::remove_file(&path).map_err(|_| "Could not archive inactive preparation")?;
            e.event("Recovered an inactive preparation; starting fresh RAM staging")?;
        } else if state["phase"] == "prewrite_stopped" {
            if writepath(e).exists() && load(&writepath(e))?["pending"] == true {
                return Err("The write is still unconfirmed".into());
            }
            save(
                &path.with_file_name(format!("bootstrap-prewrite-{}.json", nonce())),
                &state,
            )?;
            fs::remove_file(&path)
                .map_err(|_| "Could not save the previous preparation".to_string())?;
        } else {
            return Err("Access preparation already started. Check its result first".into());
        }
    }
    e.event("Connecting to Deco owner access")?;
    let n = nonce();
    let operation = nonce();
    let r = routed_rpc(e, args, &n)?;
    let mut state = json!({"phase":"fingerprints","host":e.host,"nonce":n,"operation":operation,"main":r.main,"peer":r.peer,"created_at":now()});
    let status = rpc_read(e, &r, "/proc/self/status")?;
    state["boot_before"] =
        json!(String::from_utf8_lossy(&rpc_read(e, &r, "/proc/sys/kernel/random/boot_id")?).trim());
    let s = String::from_utf8_lossy(&status);
    if !s
        .lines()
        .any(|l| l.starts_with("Uid:") && l.split_whitespace().skip(1).eq(["0", "0", "0", "0"]))
    {
        return Err("The authenticated file service did not confirm UID 0".into());
    }
    if !rpc_read(e, &r, "/sys/firmware/devicetree/base/compatible")?
        .windows(12)
        .any(|s| s == b"qcom,ipq5018")
    {
        return Err("This hardware platform is not supported by the root profile".into());
    }
    let mounts = String::from_utf8(rpc_read(e, &r, "/proc/mounts")?)
        .map_err(|_| "Invalid mount table".to_string())?;
    if !mounts.lines().any(|l| {
        let a = l.split_whitespace().collect::<Vec<_>>();
        a.len() > 3 && a[1] == "/tmp" && a[2] == "tmpfs"
    }) {
        return Err("The temporary directory is not RAM-backed".into());
    }
    for (file, expected) in [
        (
            CGI,
            "ec8c7bffd74101985aad2fe58525e0cd4f7e52893f58489b0a06967fc43e030f",
        ),
        (
            DISPATCHER,
            "6dd3afbf542613d47acebbe6447581117bbe100ac14edbfc866532ea825a456a",
        ),
    ] {
        if sha256(&rpc_read(e, &r, file)?) != expected {
            return Err("Firmware fingerprints do not match the reviewed version. No configuration change was made".into());
        }
    }
    let cert = rpc_read(e, &r, "/etc/uhttpd.crt")?;
    let mut original = rpc_read(e, &r, CACHE).ok();
    if original.is_none() {
        e.p.call(json!({"op":"https","host":e.host,"certificate":b64(&cert),"path":"/cgi-bin/luci/;stok=/login?form=keys&operation=read"}))?;
        original = rpc_read(e, &r, CACHE).ok()
    }
    let original = original.ok_or("Web index is unavailable")?;
    if original.len() != 19955 || sha256(&original) != CACHE_HASH {
        return Err("Original web index did not match. No configuration change was made".into());
    }
    let pubkey = e.p.call(json!({"op":"client_public"}))?;
    let pubkey = text(&pubkey, "key")?;
    if !pubkey.starts_with("ssh-ed25519 ")
        || pubkey.len() > 180
        || !pubkey
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b" -+/=".contains(&c))
    {
        return Err("App public key failed validation".into());
    }
    let original_path = path.with_extension("cache-before");
    fs::write(&original_path, &original)
        .map_err(|_| "Could not save the original web index to the phone".to_string())?;
    state["certificate"] = json!(b64(&cert));
    state["phase"] = json!("upload_restore");
    save(&path, &state)?;
    let encoded = b64(&original);
    for (i, part) in encoded.as_bytes().chunks(3000).enumerate() {
        let file = format!("/tmp/deco-x50-ssh-{n}-part-{i:02}.b64");
        let content = std::str::from_utf8(part).unwrap();
        rpc_write(e, &r, &file, content)?;
        if rpc_read(e, &r, &file)? != part {
            return Err("Restore part did not match".into());
        }
    }
    let server = b64(asset("temporary"));
    state["phase"] = json!("upload_server");
    save(&path, &state)?;
    for (i, part) in server.as_bytes().chunks(6000).enumerate() {
        let file = format!("/tmp/deco-x50-ssh-{n}-bin-{i:03}.b64");
        rpc_write(e, &r, &file, std::str::from_utf8(part).unwrap())?;
        if rpc_read(e, &r, &file)? != part {
            return Err("Temporary SSH server did not match".into());
        }
        if i % 8 == 0 {
            e.event(&format!(
                "Preparing access: {} of {} parts",
                i + 1,
                server.len().div_ceil(6000)
            ))?
        }
    }
    let wrapper = include_str!("../assets/bootstrap.lua")
        .replace("@NONCE@", &n)
        .replace("@OP@", &operation)
        .replace("@TARGET@", &e.host)
        .replace("@PUBLIC_KEY@", pubkey);
    state["phase"] = json!("cache_write_pending");
    save(&path, &state)?;
    rpc_write(e, &r, CACHE, &wrapper)?;
    state["phase"] = json!("trigger_sent");
    save(&path, &state)?;
    let query =
        format!("/cgi-bin/luci/;stok=/login?form=keys&operation=read&deco_backup={operation}-001");
    let _response =
        e.p.call(json!({"op":"https","host":e.host,"certificate":b64(&cert),"path":query}));
    finish_bootstrap(e, &r, &path, &mut state)
}
fn finish_bootstrap(e: &mut Engine<'_>, r: &Rpc, path: &Path, state: &mut Value) -> Result<Value> {
    let n = text(state, "nonce")?.to_string();
    let op = text(state, "operation")?.to_string();
    if sha256(&rpc_read(e, r, CACHE)?) != CACHE_HASH {
        return Err("Web index restoration is unconfirmed. Check the result".into());
    }
    let result = rpc_read(e, r, &format!("/tmp/deco-x50-ssh-{n}-result-{op}-001.txt"))?;
    let s = String::from_utf8(result).map_err(|_| "Invalid SSH receipt".to_string())?;
    let lines = s.lines().collect::<Vec<_>>();
    if lines.len() != 4
        || lines[0] != format!("READY:{op}-001")
        || lines[3] != format!("{}:20003", e.host)
        || !lines[2].starts_with("ssh-ed25519 ")
    {
        return Err("Temporary SSH startup is unconfirmed. Do not repeat the writes".into());
    }
    state["public_key"] = json!(lines[2]);
    state["server_pid"] = json!(lines[1]);
    state["phase"] = json!("ready");
    save(path, state)?;
    e.p.call(json!({"op":"temporary_connect","host":e.host,"port":20003,"key":lines[2]}))?;
    e.connect()?;
    e.event("Root access confirmed. Original web index restored")?;
    Ok(json!({"ready":true,"source":"temporary_ssh"}))
}
fn preflight(e: &mut Engine<'_>) -> Result<Value> {
    e.connect()?;
    let value = e.run_lua(scripts::IDENTITY)?;
    let compatible = value["compatible"].as_str().unwrap_or("");
    let model = value["model"]
        .as_str()
        .unwrap_or("")
        .replace([' ', '-', '_'], "")
        .to_uppercase();
    if !["X50", "DECOX50"].contains(&model.as_str())
        || value["hardware"] != "1.0"
        || value["architecture"] != "aarch64"
        || value["kernel"] != "4.4.60"
        || !compatible.contains("qcom,ipq5018")
    {
        return Err("This installation profile requires the reviewed X50 V1 stock firmware".into());
    }
    let expected: Value = serde_json::from_str(include_str!("../assets/stock-fingerprints.json"))
        .expect("stock manifest");
    for (path, digest) in expected.as_object().unwrap() {
        if sha256(&e.read(path, 131072)?) != digest.as_str().unwrap() {
            return Err(
                "Firmware code differs from the reviewed version. No configuration change was made"
                    .into(),
            );
        }
    }
    Ok(value)
}
pub fn backup_config(e: &mut Engine<'_>) -> Result<Value> {
    preflight(e)?;
    e.event("Saving configuration backup to the phone")?;
    let work = format!("/tmp/deco-control-{}", nonce());
    let cmd = format!(
        "set -eu\numask 077\nmkdir {}\nnvrammanager -r {}/device-config.bak -p device-config\ncp /etc/config/firewall {}/firewall.before\nsha1sum {}/device-config.bak {}/firewall.before",
        shell(&work),
        shell(&work),
        shell(&work),
        shell(&work),
        shell(&work)
    );
    let data = e.exec(&cmd, &[], 8192)?;
    let output = String::from_utf8(data).map_err(|_| "Invalid backup receipt".to_string())?;
    let folder =
        e.home
            .join("backups")
            .join(&e.host)
            .join(format!("config-{}-{}", now(), &nonce()[..8]));
    fs::create_dir_all(&folder).map_err(|_| "Could not create backup directory".to_string())?;
    let mut files = json!({});
    for name in ["device-config.bak", "firewall.before"] {
        let remote = format!("{work}/{name}");
        let hash = output
            .lines()
            .find_map(|l| {
                l.split_once("  ")
                    .filter(|(_, p)| *p == remote)
                    .map(|(h, _)| h)
            })
            .ok_or("Missing backup checksum")?;
        let content = e.read(&remote, 131072)?;
        if content.is_empty() || sha1(&content) != hash {
            return Err("Backup checksum did not match".into());
        }
        let path = folder.join(name);
        fs::write(&path, &content).map_err(|_| "Could not save backup to the phone".to_string())?;
        files[name] =
            json!({"sha1":hash,"sha256":sha256(&content),"bytes":content.len(),"path":path});
    }
    let report = json!({"kind":"device-config-backup","host":e.host,"created_at":now(),"files":files,"verified":true,"firmware_written":false});
    save(&folder.join("verified.json"), &report)?;
    Ok(report)
}
pub fn install(e: &mut Engine<'_>, _args: &Value) -> Result<Value> {
    if e.journal().exists() {
        let old = load(&e.journal())?;
        if old["status"] == "prewrite_stopped" {
            let archive = e
                .journal()
                .with_file_name(format!("operation-prewrite-{}.json", nonce()));
            save(&archive, &old)?;
            fs::remove_file(e.journal())
                .map_err(|_| "Could not save the previous check".to_string())?;
        } else if old["status"] != "verified" {
            return Err("The previous installation is incomplete. Check its result first".into());
        } else {
            return Err("Permanent SSH is already installed. Open startup verification".into());
        }
    }
    let identity = preflight(e)?;
    let own=e.run_lua("local fs=require('nixio.fs');io.write(require('luci.json').encode({present=fs.stat('/tp_data/user_data/internetdeco-ssh/OWNER')~=nil}))")?;
    if own["present"] == true {
        let binary = e.read(&format!("{BASE}/dropbearmulti"), 1048576)?;
        if sha1(&binary) != metadata()["password_binary_sha1"].as_str().unwrap() {
            return Err(
                "Existing SSH uses a different build. Automatic replacement is blocked".into(),
            );
        }
        let owner = String::from_utf8(e.read(&format!("{BASE}/OWNER"), 100)?)
            .map_err(|_| "Invalid SSH ownership marker")?;
        let include_value=e.run_lua("local u=require('luci.model.uci').cursor();io.write(require('luci.json').encode({token=u:get('firewall','internetdeco_probe','idm_probe_token')}))")?;
        let include = text(&include_value, "token")?.to_string();
        let owner = owner.trim();
        let include = include.trim();
        if ![owner, include]
            .iter()
            .all(|v| v.len() == 32 && v.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            return Err("Unrecognized existing installation".into());
        }
        let proof = json!({"owner_token":owner,"include_owner_token":include,
            "password_hash_sha256":sha256(&e.read(&format!("{BASE}/password.hash"),192)?),
            "launch_sha256":sha256(&e.read("/tp_data/user_data/internetdeco-include-probe/launch.sh",32768)?)});
        persistent_install_proof(e, &proof)?;
        e.p.call(json!({"op":"remember_root","host":e.host}))?;
        let record = json!({"identity":identity,"root_installed":true,"adopted":true,"owner_token":owner,"include_token":include,"last_boot_check":e.boot()?,"verified_boot_ids":[],"last_seen":now()});
        save(&nodepath(e), &record)?;
        return Ok(json!({"adopted":true,"installed":true,"needs_boot_checks":true}));
    }
    let credentials = e.p.call(json!({"op":"install_password","host":e.host}))?;
    let password = text(&credentials, "password")?.to_string();
    crate::crypto::password_hash(&password, None)?;
    let backup = backup_config(e)?;
    let token = nonce();
    let include_token = nonce();
    let work = format!("/tmp/internetdeco-include-{token}");
    let include_work = format!("/tmp/internetdeco-include-{include_token}");
    let boot = e.boot()?;
    let mut op = json!({"action":"install","target":e.host,"token":token,"owner_token":token,"include_owner_token":include_token,"work":work,"include_work":include_work,"boot_before":boot,"phase":"before_mutation","status":"pending","backup":backup,"created_at":now()});
    save(&e.journal(), &op)?;
    e.exec(
        &format!(
            "umask 077; mkdir {} && mkdir {}",
            shell(&work),
            shell(&include_work)
        ),
        &[],
        2048,
    )?;
    let old_launch = include_str!("../assets/launch.sh");
    let include_context = json!({"token":include_token,"owner_token":include_token,"action":"install","work":include_work,"config_sha1":backup["files"]["device-config.bak"]["sha1"],"firewall_sha1":backup["files"]["firewall.before"]["sha1"],"launch":old_launch});
    let inc_source = format!(
        "local CTX=assert(require('luci.json').decode({}))\n{}",
        lua(&include_context.to_string()),
        include_str!("../assets/include.lua")
    );
    op["phase"] = json!("include_once");
    save(&e.journal(), &op)?;
    e.event("Registering verified startup configuration")?;
    e.exec("lua -", inc_source.as_bytes(), 65536)?;
    let inc: Value =
        serde_json::from_slice(&e.read(&format!("{include_work}/result.json"), 65536)?)
            .map_err(|_| "Invalid startup receipt".to_string())?;
    validate_include(&inc, &op)?;
    if inc["ok"] != true {
        return Err(format!(
            "Startup configuration stopped: {}. Check the result",
            inc["error_code"].as_str().unwrap_or("check failed")
        ));
    }
    password_stage(e, &mut op, password)
}
fn password_stage(e: &mut Engine<'_>, op: &mut Value, mut password: String) -> Result<Value> {
    let token = text(op, "token")?.to_string();
    let include_token = text(op, "include_owner_token")?.to_string();
    let work = text(op, "work")?.to_string();
    let boot = e.boot()?;
    op["boot_before"] = json!(boot);
    save(&e.journal(), op)?;
    let identity = preflight(e)?;
    let hash = crate::crypto::password_hash(&password, None)?;
    let old_launch = include_str!("../assets/launch.sh");
    let service = include_str!("../assets/ssh-service.sh").replace("@TARGET@", &e.host);
    let append = include_str!("../assets/ssh-append.sh")
        .replace("@SERVICE_SHA1@", &sha1(service.as_bytes()));
    let new_launch = format!("{}\n\n{}", old_launch.trim_end(), append);
    let encoded = b64(asset("password"));
    let parts = encoded
        .as_bytes()
        .chunks(7000)
        .enumerate()
        .map(|(i, b)| (format!("{work}-binary-{i:03}"), b))
        .collect::<Vec<_>>();
    for (path, data) in &parts {
        e.exec(&format!("umask 077; cat > {}", shell(path)), data, 1024)?;
        if e.read(path, 7001)? != *data {
            return Err("Uploaded SSH server part did not match".into());
        }
    }
    let context = json!({"token":token,"owner_token":token,"target":e.host,"work":work,"expected_boot_id":boot,"action":"install","include_owner_token":include_token,"old_launch":old_launch,"new_launch":new_launch,"service":service,"password_hash":hash,"binary_sha1":metadata()["password_binary_sha1"],"binary_parts":parts.iter().map(|(p,_)|p).collect::<Vec<_>>()});
    let tcp = format!(
        "{:02X}{:02X}{:02X}{:02X}:0016",
        e.host.parse::<Ipv4Addr>().unwrap().octets()[3],
        e.host.parse::<Ipv4Addr>().unwrap().octets()[2],
        e.host.parse::<Ipv4Addr>().unwrap().octets()[1],
        e.host.parse::<Ipv4Addr>().unwrap().octets()[0]
    );
    let engine = include_str!("../assets/password.lua")
        .replace("@TARGET@", &e.host)
        .replace("@TARGET_TCP@", &tcp);
    let source = format!(
        "{}\nlocal CTX=assert(require('luci.json').decode({}))\n{}",
        include_str!("../assets/base64.lua"),
        lua(&context.to_string()),
        engine
    );
    op["password_hash_sha256"] = json!(sha256(hash.as_bytes()));
    op["launch_sha256"] = json!(sha256(new_launch.as_bytes()));
    op["phase"] = json!("password_install_once");
    save(&e.journal(), op)?;
    e.event("Installing personal SSH access")?;
    e.exec("lua -", source.as_bytes(), 65536)?;
    let installed: Value = serde_json::from_slice(&e.read(&format!("{work}/result.json"), 65536)?)
        .map_err(|_| "Invalid SSH server receipt".to_string())?;
    validate_receipt(&installed, &op)?;
    if installed["ok"] != true {
        return Err(format!(
            "SSH setup stopped: {}",
            installed["error_code"]
                .as_str()
                .unwrap_or("verification required")
        ));
    }
    let pubkey = e.exec(
        &format!("{BASE}/dropbearmulti dropbearkey -y -f {BASE}/host_key"),
        &[],
        4096,
    )?;
    let line = String::from_utf8_lossy(&pubkey)
        .lines()
        .find(|l| l.starts_with("ssh-ed25519 "))
        .ok_or("Could not read the SSH public key")?
        .to_string();
    e.p.call(json!({"op":"verify_password","host":e.host,"password":password,"key":line}))?;
    password.clear();
    op["phase"] = json!("verified");
    op["status"] = json!("verified");
    save(&e.journal(), op)?;
    save(
        &nodepath(e),
        &json!({"identity":identity,"root_installed":true,"owner_token":token,"include_token":include_token,"verified_boot_ids":[],"last_seen":now()}),
    )?;
    e.event("Password SSH is ready. Reboot remains a separate action")?;
    Ok(json!({"installed":true,"host":e.host,"needs_boot_checks":true}))
}

fn validate_include(value: &Value, op: &Value) -> Result<()> {
    if value["schema"] != 1
        || value["engine_version"] != 2
        || value["kind"] != "include-probe-transaction"
        || value["token"] != op["include_owner_token"]
        || value["owner_token"] != op["include_owner_token"]
        || value["action"] != "install"
    {
        return Err("Startup receipt does not belong to this operation".into());
    }
    if value["ok"] == true
        && (value["readback_verified"] != true || value["traffic_preserved"] != true)
    {
        return Err("Startup persistence was not confirmed".into());
    }
    Ok(())
}
pub fn continue_install(e: &mut Engine<'_>) -> Result<Value> {
    let mut op = load(&e.journal())?;
    if op["target"] != e.host || op["action"] != "install" || op["status"] != "include_ready" {
        return Err("No verified installation stage to continue".into());
    }
    preflight(e)?;
    let path = format!("{}/result.json", text(&op, "include_work")?);
    let inc: Value = serde_json::from_slice(&e.read(&path, 65536)?)
        .map_err(|_| "Invalid startup receipt".to_string())?;
    validate_include(&inc, &op)?;
    if inc["ok"] != true {
        return Err("Startup configuration is not confirmed yet".into());
    }
    let own=e.run_lua("io.write(require('luci.json').encode({present=require('nixio.fs').stat('/tp_data/user_data/internetdeco-ssh/OWNER')~=nil}))")?;
    if own["present"] == true {
        return Err("SSH might already have been installed. Check the result".into());
    }
    let credentials = e.p.call(json!({"op":"install_password","host":e.host}))?;
    password_stage(e, &mut op, text(&credentials, "password")?.to_string())
}
fn validate_receipt(v: &Value, op: &Value) -> Result<()> {
    if v["token"] != op["token"]
        || v["target"] != op["target"]
        || v["action"] != "install"
        || v["schema"] != 1
        || v["system_accounts_changed"] != false
        || v["config_save_called"] != false
        || v["firewall_reloaded"] != false
    {
        return Err("Receipt does not belong to this installation".into());
    }
    Ok(())
}
fn finish_resolved_install(e: &mut Engine<'_>, op: &mut Value) -> Result<()> {
    let pending =
        e.p.call(json!({"op":"pending_password_present","host":e.host}))?;
    if pending["present"] == true {
        e.p.call(json!({"op":"verify_pending_password","host":e.host}))?;
    } else {
        e.p.call(json!({"op":"remember_root","host":e.host}))?;
    }
    let mut record = load(&nodepath(e)).unwrap_or(json!({"verified_boot_ids":[]}));
    record["root_installed"] = true.into();
    record["owner_token"] = op["owner_token"].clone();
    record["include_token"] = op["include_owner_token"].clone();
    save(&nodepath(e), &record)?;
    op["status"] = json!("verified");
    op["phase"] = json!("verified");
    save(&e.journal(), op)
}
fn persistent_install_proof(e: &mut Engine<'_>, op: &Value) -> Result<()> {
    if e.read(&format!("{BASE}/OWNER"), 100)?
        != format!("{}\n", text(op, "owner_token")?).as_bytes()
        || e.read(&format!("{BASE}/ENABLE"), 100)? != b"enabled\n"
    {
        return Err("SSH service ownership is unconfirmed".into());
    }
    if sha1(&e.read(&format!("{BASE}/dropbearmulti"), 1048576)?)
        != metadata()["password_binary_sha1"].as_str().unwrap()
    {
        return Err("SSH server binary changed".into());
    }
    if sha256(&e.read(&format!("{BASE}/password.hash"), 192)?)
        != op["password_hash_sha256"]
            .as_str()
            .ok_or("Missing original password checksum")?
        || sha256(&e.read(
            "/tp_data/user_data/internetdeco-include-probe/launch.sh",
            32768,
        )?) != op["launch_sha256"]
            .as_str()
            .ok_or("Missing startup checksum")?
    {
        return Err("Saved installation files did not match".into());
    }
    let expected = include_str!("../assets/ssh-service.sh").replace("@TARGET@", &e.host);
    if e.read(&format!("{BASE}/internetdeco-ssh"), 32768)? != expected.as_bytes() {
        return Err("Service file changed".into());
    }
    let code = format!(
        "local c=require('luci.model.uci').cursor();assert(c:get('firewall','internetdeco_probe','path')=='/tp_data/user_data/internetdeco-include-probe/launch.sh');assert(c:get('firewall','internetdeco_probe','idm_probe_token')=={});io.write('{{\"owned\":true}}')",
        lua(text(op, "include_owner_token")?)
    );
    e.run_lua(&code)?;
    Ok(())
}
pub fn guard_pending(e: &Engine<'_>, action: &str) -> Result<()> {
    if ["bootstrap", "continue_install"].contains(&action) {
        return Ok(());
    }
    for path in [
        writepath(e),
        e.journal(),
        e.home
            .join("nodes")
            .join(&e.host)
            .join("password-change.json"),
    ] {
        if !path.exists() {
            continue;
        }
        let value = load(&path)?;
        let status = value["status"].as_str().unwrap_or("");
        if value["pending"] == true || ["pending", "include_ready"].contains(&status) {
            return Err(
                "Check the unfinished operation on this Deco before making another change".into(),
            );
        }
    }
    Ok(())
}
pub fn result(e: &mut Engine<'_>) -> Result<Value> {
    let password_journal = e
        .home
        .join("nodes")
        .join(&e.host)
        .join("password-change.json");
    if password_journal.exists() {
        let mut change = load(&password_journal)?;
        if change["status"] == "pending" {
            e.connect()?;
            let actual = e.read(&format!("{BASE}/password.hash"), 192)?;
            if sha256(&actual) == change["expected_sha256"].as_str().unwrap_or("") {
                e.p.call(json!({"op":"verify_pending_password","host":e.host}))?;
                change["status"] = json!("verified");
                save(&password_journal, &change)?;
                return Ok(json!({"changed":true,"resolved":true}));
            }
            change["status"] = json!("not_applied");
            save(&password_journal, &change)?;
            return Ok(json!({"changed":false,"not_applied":true}));
        }
    }
    if e.journal().exists() {
        let mut op = load(&e.journal())?;
        e.connect()?;
        if ["include_once", "include_ready"].contains(&op["phase"].as_str().unwrap_or("")) {
            let path = format!("{}/result.json", text(&op, "include_work")?);
            let value: Value = serde_json::from_slice(&e.read(&path, 65536)?).map_err(|_| {
                "Startup receipt is unavailable. Automatic retry is blocked".to_string()
            })?;
            validate_include(&value, &op)?;
            if value["ok"] == true {
                op["status"] = json!("include_ready");
                op["phase"] = json!("include_ready");
                save(&e.journal(), &op)?;
                return Ok(json!({"include_ready":true,"host":e.host}));
            }
            if value["save_attempted"] == false
                && [
                    "guards",
                    "baseline_read",
                    "baseline_decode",
                    "compare_ram_flash",
                ]
                .contains(&value["stage"].as_str().unwrap_or(""))
            {
                op["status"] = json!("prewrite_stopped");
                save(&e.journal(), &op)?;
                return Ok(json!({"prewrite_stopped":true,"error_code":value["error_code"]}));
            }
            return Err(
                "Startup configuration is unconfirmed. Repeating the write is blocked".into(),
            );
        }
        let work = text(&op, "work")?;
        let bytes = e.read(&format!("{work}/result.json"), 65536);
        if bytes.is_err() && op["phase"] == "password_install_once" {
            persistent_install_proof(e, &op)?;
            finish_resolved_install(e, &mut op)?;
            return Ok(
                json!({"installed":true,"resolved_from_persistent_ownership":true,"receipt_lost_on_reboot":true}),
            );
        }
        let v: Value = serde_json::from_slice(&bytes?)
            .map_err(|_| "Receipt is unavailable. Repeating installation is blocked".to_string())?;
        validate_receipt(&v, &op)?;
        if v["ok"] == true {
            finish_resolved_install(e, &mut op)?;
        }
        return Ok(v);
    }
    let path = bootstrappath(e);
    let mut state = load(&path)?;
    let n = text(&state, "nonce")?.to_string();
    let main = text(&state, "main")?.to_string();
    let owner = e.p.call(json!({"op":"owner","host":main}))?;
    let r = Rpc {
        main,
        host: e.host.clone(),
        port: owner["port"].as_u64().ok_or("Owner channel unavailable")? as u16,
        peer: state["peer"].as_str().map(String::from),
        namespace: n,
    };
    if writepath(e).exists() {
        let pending = load(&writepath(e))?;
        if pending["pending"] == true {
            let path = text(&pending, "path")?;
            let data = rpc_read(e, &r, path)?;
            if data != text(&pending, "content")?.as_bytes() {
                return Err("Write is unconfirmed. Restore the original web index before making further changes".into());
            }
            save(&writepath(e), &json!({"pending":false,"resolved":true}))?;
        }
    }
    if ["upload_restore", "upload_server"].contains(&state["phase"].as_str().unwrap_or("")) {
        if sha256(&rpc_read(e, &r, CACHE)?) != CACHE_HASH {
            return Err("Original web index changed. New writes are blocked".into());
        }
        state["phase"] = json!("prewrite_stopped");
        save(&path, &state)?;
        return Ok(json!({"prewrite_stopped":true,"bootstrap_only":true}));
    }
    finish_bootstrap(e, &r, &path, &mut state)
}
pub fn boot_check(e: &mut Engine<'_>) -> Result<Value> {
    e.connect()?;
    let boot = e.boot()?;
    let mut record = load(&nodepath(e)).unwrap_or(json!({}));
    let state=e.run_lua(r#"local u=assert(require('luci.model.uci').cursor());local fs=require('nixio.fs');local j=require('luci.json');local function read(p)local f=assert(io.open(p,'rb'));local s=f:read(4096);f:close();return s end;local boot=read('/proc/sys/kernel/random/boot_id'):match('^([%x-]+)');local receipt=j.decode(read('/tmp/internetdeco-include-probe.'..boot..'/receipt.json'));local pid=read('/tmp/internetdeco-ssh/server.pid'):match('^(%d+)');assert(pid and fs.readlink('/proc/'..pid..'/exe')=='/tmp/internetdeco-ssh/dropbearmulti');io.write(j.encode({enabled=read('/tp_data/user_data/internetdeco-ssh/ENABLE')=='enabled\n',include=u:get('firewall','internetdeco_probe','path')=='/tp_data/user_data/internetdeco-include-probe/launch.sh',receipt=receipt.uid==0 and receipt.boot_id==boot and receipt.kind=='include-probe'}))"#)?;
    if state["enabled"] != true || state["include"] != true || state["receipt"] != true {
        return Err("Permanent SSH startup on the current boot was not confirmed".into());
    }
    e.p.call(json!({"op":"remember_root","host":e.host}))?;
    let baseline = record["last_boot_check"]
        .as_str()
        .map(String::from)
        .or_else(|| {
            load(&e.journal())
                .ok()
                .and_then(|v| v["boot_before"].as_str().map(String::from))
        })
        .unwrap_or_default();
    let mut boots = record["verified_boot_ids"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    let install_boot = load(&e.journal())
        .ok()
        .and_then(|v| v["boot_before"].as_str().map(String::from));
    if boot != baseline
        && install_boot.as_deref() != Some(&boot)
        && !baseline.is_empty()
        && !boots.contains(&json!(boot))
    {
        boots.push(json!(boot.clone()));
    }
    record["last_boot_check"] = json!(boot);
    record["verified_boot_ids"] = json!(boots);
    record["root_installed"] = true.into();
    save(&nodepath(e), &record)?;
    Ok(
        json!({"boot_id":boot,"distinct_post_install_boots":boots.len(),"required":2,"same_boot":boot==baseline,"startup_configured":true}),
    )
}
pub fn change_password(e: &mut Engine<'_>, _args: &Value) -> Result<Value> {
    preflight(e)?;
    let p = e.p.call(json!({"op":"install_password","host":e.host}))?;
    let pass = text(&p, "password")?;
    let hash = crate::crypto::password_hash(pass, None)?;
    let token = nonce();
    let temp = format!("{BASE}/password.hash.{token}.new");
    let mut op = json!({"action":"password","phase":"before_write","status":"pending","target":e.host,"token":token,"expected_sha256":sha256(hash.as_bytes())});
    let journal = e
        .home
        .join("nodes")
        .join(&e.host)
        .join("password-change.json");
    if journal.exists()
        && !["verified", "not_applied"].contains(&load(&journal)?["status"].as_str().unwrap_or(""))
    {
        return Err("The previous password change is unconfirmed".into());
    }
    save(&journal, &op)?;
    let code = format!(
        "local fs=require('nixio.fs');local old=assert(fs.lstat('{BASE}/password.hash'));assert(old.type=='reg' and old.uid==0);local p={};assert(not fs.lstat(p));local f=assert(io.open(p,'wb'));assert(f:write({}));assert(f:close());assert(fs.chmod(p,'0600'));assert(os.rename(p,'{BASE}/password.hash'));io.write('{{\"saved\":true}}')",
        lua(&temp),
        lua(&hash)
    );
    e.run_lua(&code)?;
    if e.read(&format!("{BASE}/password.hash"), 192)? != hash.as_bytes() {
        return Err("New password was not confirmed".into());
    }
    e.p.call(json!({"op":"verify_password","host":e.host,"password":pass}))?;
    op["status"] = json!("verified");
    op["phase"] = json!("verified");
    save(&journal, &op)?;
    Ok(json!({"changed":true,"rebooted":false}))
}
pub fn backup_nand(e: &mut Engine<'_>) -> Result<Value> {
    preflight(e)?; // Use actual physical layout from the proven backup client at build time.
    let layout: Value = serde_json::from_str(include_str!("../assets/nand-layout.json"))
        .map_err(|_| "Invalid NAND profile".to_string())?;
    let mtd = String::from_utf8(e.read("/proc/mtd", 8192)?).map_err(|_| "NAND map".to_string())?;
    let expected = layout.as_array().ok_or("Invalid NAND profile")?;
    let total = expected.iter().filter_map(|v| v[1].as_u64()).sum::<u64>();
    let disk = e.p.call(json!({"op":"disk"}))?;
    if disk["free_bytes"].as_u64().unwrap_or(0) < total * 2 + (64 << 20) {
        return Err("Free at least 300 MiB on the phone for a complete backup".into());
    }
    for (i, item) in expected.iter().enumerate() {
        let line = mtd
            .lines()
            .find(|s| s.starts_with(&format!("mtd{i}:")))
            .ok_or("NAND partition map changed")?;
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if fields.len() < 4
            || u64::from_str_radix(fields[1], 16).ok() != item[1].as_u64()
            || fields[3].trim_matches('"') != item[0].as_str().unwrap_or("")
        {
            return Err("NAND map differs from the reviewed X50 V1 layout".into());
        }
    }
    let n = nonce();
    let work = format!("/tmp/deco-control-{n}");
    e.exec(&format!("umask 077; mkdir {}", shell(&work)), &[], 1024)?;
    let helper = format!("{work}/reader");
    e.upload(&helper, asset("reader"))?;
    e.exec(&format!("chmod 700 {}", shell(&helper)), &[], 1024)?;
    let folder = e
        .home
        .join("backups")
        .join(&e.host)
        .join(format!("nand-{}", now()));
    fs::create_dir_all(&folder)
        .map_err(|_| "Could not create backup directory NAND".to_string())?;
    let mut report = json!({"kind":"visible-NAND-DATA-backup","target":e.host,"status":"running","partitions":[],"firmware_written":false,"oob_backed_up":false,"restore_tested":false});
    for (i, item) in expected.iter().enumerate() {
        e.event(&format!(
            "Copying NAND: partition {} of {}",
            i + 1,
            expected.len()
        ))?;
        let name = format!("mtd{i:02}.bin");
        let destination = folder.join(&name);
        let received=e.p.call(json!({"op":"nand_download","host":e.host,"command":format!("{} dump {i} {n}",shell(&helper)),"path":destination,"bytes":item[1],"index":i,"nonce":n}))?;
        let check = e.exec(&format!("{} hash {i} {n}", shell(&helper)), &[], 8192)?;
        let lines = String::from_utf8(check).map_err(|_| "NAND verification".to_string())?;
        let last: Value =
            serde_json::from_str(lines.lines().last().ok_or("Missing NAND checksum")?)
                .map_err(|_| "Invalid NAND receipt".to_string())?;
        if received["sha256"] != last["sha256"]
            || last["nonce"] != n
            || last["index"] != i
            || last["ecc_failed_after"] != last["ecc_failed_before"]
            || last["badblock_map_stable"] != true
        {
            return Err("NAND second read did not match. Partial copies were retained".into());
        }
        report["partitions"].as_array_mut().unwrap().push(json!({"index":i,"name":item[0],"bytes":item[1],"sha256":last["sha256"],"verified_two_reads":true}));
        save(&folder.join("report.json"), &report)?;
    }
    report["status"] = json!("verified");
    save(&folder.join("report.json"), &report)?;
    Ok(
        json!({"verified_partitions":expected.len(),"directory":folder,"bytes":expected.iter().filter_map(|v|v[1].as_u64()).sum::<u64>()}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn binary_and_service_match_reviewed_artifacts() {
        let mut raw = vec![];
        let mut z = flate2::read::GzDecoder::new(asset("password"));
        std::io::Read::read_to_end(&mut z, &mut raw).unwrap();
        assert_eq!(
            sha1(&raw),
            metadata()["password_binary_sha1"].as_str().unwrap()
        );
        let service = include_str!("../assets/ssh-service.sh").replace("@TARGET@", "10.20.30.40");
        let append = include_str!("../assets/ssh-append.sh")
            .replace("@SERVICE_SHA1@", &sha1(service.as_bytes()));
        assert!(!service.contains('@'));
        assert!(!append.contains('@'));
        assert!(service.contains(metadata()["password_binary_sha1"].as_str().unwrap()));
        assert!(append.contains(&sha1(service.as_bytes())));
        assert!(service.contains("-I 900"));
        assert!(service.contains("-K 30"));
        assert!(service.contains("10.20.30.40:22"));
    }
    #[test]
    fn runtime_wrapper_has_no_open_listener_or_untrusted_trigger() {
        let value = include_str!("../assets/bootstrap.lua");
        assert!(value.contains("{'@TARGET@:20003'}"));
        assert!(value.contains("QUERY_STRING"));
        assert!(value.find("restore readback").unwrap() < value.find("REQUEST_METHOD").unwrap());
        assert!(value.contains("-D "));
        assert!(value.contains("@PUBLIC_KEY@"));
    }
    #[test]
    fn receipts_are_owned_and_not_interchangeable() {
        let op = json!({"token":"a","target":"192.168.99.101","include_owner_token":"b"});
        let value = json!({"schema":1,"token":"a","target":"192.168.99.101","action":"install","system_accounts_changed":false,"config_save_called":false,"firewall_reloaded":false});
        assert!(validate_receipt(&value, &op).is_ok());
        let mut other = value.clone();
        other["token"] = json!("x");
        assert!(validate_receipt(&other, &op).is_err());
        other = value.clone();
        other["target"] = json!("192.168.99.102");
        assert!(validate_receipt(&other, &op).is_err());
        other = value.clone();
        other["system_accounts_changed"] = true.into();
        assert!(validate_receipt(&other, &op).is_err());
        let inc = json!({"schema":1,"engine_version":2,"kind":"include-probe-transaction","token":"b","owner_token":"b","action":"install","ok":true,"readback_verified":true,"traffic_preserved":true});
        assert!(validate_include(&inc, &op).is_ok());
        let mut bad = inc;
        bad["traffic_preserved"] = false.into();
        assert!(validate_include(&bad, &op).is_err());
    }
    #[test]
    fn protected_credentials_are_never_in_profile_assets() {
        let meta = metadata();
        assert_eq!(
            meta["temporary_binary_parts"].as_u64().unwrap() as usize,
            b64(asset("temporary")).len().div_ceil(6000)
        );
        assert_eq!(
            sha256(asset("reader")),
            meta["nand_helper_sha256"].as_str().unwrap()
        );
        let layout: Value =
            serde_json::from_str(include_str!("../assets/nand-layout.json")).unwrap();
        assert_eq!(layout.as_array().unwrap().len(), 16);
        assert_eq!(
            layout
                .as_array()
                .unwrap()
                .iter()
                .filter_map(|v| v[1].as_u64())
                .sum::<u64>(),
            122421248
        );
    }
    #[test]
    fn latin1_responses_preserve_every_byte() {
        let source = (0u8..=255).collect::<Vec<_>>();
        let string = source.iter().copied().map(char::from).collect::<String>();
        assert_eq!(ascii_bytes(&string).unwrap(), source);
        assert!(ascii_bytes("🌐").is_err());
    }
    #[test]
    fn unresolved_writes_block_new_mutations_without_sending_commands() {
        struct NoNetwork;
        impl Platform for NoNetwork {
            fn call(&mut self, _: Value) -> Result<Value> {
                panic!("pending guard must be local")
            }
        }
        let home = std::env::temp_dir().join(format!("deco-guard-test-{}", nonce()));
        let mut platform = NoNetwork;
        let e = Engine {
            p: &mut platform,
            home: home.clone(),
            host: "192.168.99.101".into(),
        };
        for pending in [
            json!({"status":"pending"}),
            json!({"status":"include_ready"}),
            json!({"pending":true}),
        ] {
            save(&e.journal(), &pending).unwrap();
            for action in [
                "install",
                "password",
                "reboot",
                "write_file",
                "upload",
                "locate",
            ] {
                assert!(guard_pending(&e, action).is_err());
            }
        }
        save(&e.journal(), &json!({"status":"prewrite_stopped"})).unwrap();
        assert!(guard_pending(&e, "install").is_ok());
        save(&e.journal(), &json!({"status":"verified"})).unwrap();
        assert!(guard_pending(&e, "password").is_ok());
        fs::write(e.journal(), b"broken").unwrap();
        assert!(guard_pending(&e, "install").is_err());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn owner_readiness_only_reads_status_and_checks_root_uid() {
        use std::{
            io::{Read, Write},
            net::TcpListener,
            thread,
        };
        struct OwnerOnly {
            port: u16,
        }
        impl Platform for OwnerOnly {
            fn call(&mut self, v: Value) -> Result<Value> {
                match v["op"].as_str() {
                    Some("event") => Ok(json!({})),
                    Some("owner") => Ok(json!({"port":self.port})),
                    _ => panic!("readiness must never execute or write: {}", v["op"]),
                }
            }
        }
        let listener = TcpListener::bind(("127.0.0.1", 9229)).expect("test port 9229 must be free");
        for uid in [0, 1000] {
            let l = listener.try_clone().unwrap();
            let task = thread::spawn(move || {
                let (mut socket, _) = l.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut hello = [0u8; 4];
                socket.read_exact(&mut hello).unwrap();
                assert_eq!(hello, [1, 1, 1, 0]);
                socket.write_all(&[1, 1, 2, 0]).unwrap();
                socket.read_exact(&mut hello).unwrap();
                assert_eq!(hello, [1, 1, 2, 0]);
                for (op,number,body)in [(1u16,1u16,vec![0;32]),(0xf0,2,serde_json::to_vec(&json!({"error_code":0,"result":{"data":format!("Name:\tfixture\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n")}})).unwrap())]{
                    let mut h=[0u8;36];socket.read_exact(&mut h).unwrap();assert_eq!(u16::from_be_bytes([h[18],h[19]]),op);let len=u16::from_be_bytes([h[4],h[5]])as usize-20;let mut input=vec![0;len];socket.read_exact(&mut input).unwrap();
                    if op==0xf0{let request:Value=serde_json::from_slice(&input).unwrap();assert_eq!(request,json!({"params":{"file":"/proc/self/status"}}));}
                    let mut response=vec![1,1,5,0];response.extend(((body.len()+20)as u16).to_be_bytes());response.extend([0u8;6]);response.extend(0x5a6b7c8du32.to_be_bytes());response.extend([1,2]);response.extend(op.to_be_bytes());response.extend([5,0]);response.extend(number.to_be_bytes());response.extend(crc32fast::hash(&body).to_be_bytes());response.extend((body.len()as u32).to_be_bytes());response.extend(0u32.to_be_bytes());response.extend(&body);let crc=crc32fast::hash(&response);response[12..16].copy_from_slice(&crc.to_be_bytes());socket.write_all(&response).unwrap();
                }
            });
            let mut platform = OwnerOnly { port: 9229 };
            let mut e = Engine {
                p: &mut platform,
                home: std::env::temp_dir(),
                host: "10.20.30.40".into(),
            };
            let check = owner_check(
                &mut e,
                &json!({"owner_host":"10.20.30.40","request_id":"test-ticket"}),
            );
            if uid == 0 {
                let v = check.unwrap();
                assert_eq!(v["request_id"], "test-ticket");
                assert_eq!(v["controller"], "10.20.30.40");
            } else {
                assert!(check.is_err());
            }
            task.join().unwrap();
        }
    }
    #[test]
    fn interrupted_key_preparation_can_retry_only_before_cache_activation() {
        for phase in ["upload_restore", "upload_server"] {
            assert!(can_restart_staging(
                &json!({"phase":phase}),
                None,
                CACHE_HASH
            ));
            assert!(!can_restart_staging(
                &json!({"phase":phase}),
                Some(&json!({"pending":true})),
                CACHE_HASH
            ));
            assert!(!can_restart_staging(
                &json!({"phase":phase}),
                None,
                "changed"
            ));
        }
        for phase in ["cache_write_pending", "trigger_sent", "ready"] {
            assert!(!can_restart_staging(
                &json!({"phase":phase}),
                None,
                CACHE_HASH
            ));
        }
    }
}
