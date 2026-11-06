//! One owned U-Boot TFTP RAM boot. Firmware images are never written here.
//! The single-copy boot environment IS written and is not power-fail atomic.
use crate::{Engine, Result, b64, decode, nonce, now, sha256, target};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::Ipv4Addr;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path, PathBuf};

const PROFILE: &str = "x50-v1-1818916f-tftp-ram-v2";
const APPSBL_SHA: &str = "1818916fd6141d8cb5263b0dac228d7d979aa5c54030ba5d356006d2c1a6f197";
const WRITER_SHA: &str = "abf9fd6861bcfe1c8c3fb86fb991ed4f4e75ec07b8cb89b60012c22e02ffbf1c";
const ENV_BYTES: usize = 524288;
const ENV_DATA_BYTES: usize = 262144;
const APPSBL_BYTES: usize = 1310720;
const IMAGE_FILE: &str = "openwrt-qualcommax-ipq50xx-tplink_deco-x50-v1-initramfs-uImage.itb";
const IMAGE_SHA: &str = "ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2";
const IMAGE_BYTES: usize = 13056916;
const RAM_HOST: &str = "192.168.1.1";
const IDENTITY: &str = "set -eu\nid -u\nuname -m\nuname -r\ncat /proc/sys/kernel/random/boot_id\ncat /sys/firmware/devicetree/base/compatible\n";
const CONFIG: &str = "cat /etc/fw_env.config";
const GEOMETRY: &str = "cat /sys/class/mtd/mtd7/size /sys/class/mtd/mtd7/erasesize /sys/class/mtd/mtd7/bad_blocks /sys/class/mtd/mtd7/flags";
const MTD_NAMES: &str = "cat /sys/class/mtd/mtd7/name /sys/class/mtd/mtd8/name";
const READ_ENV: &str = "cat /dev/mtd7";
const WRITE_ENV: &str = "/usr/sbin/fw_setenv -s -";
const REBOOT: &str = "printf 'TFTP_REBOOT_REQUESTED\\n'; sync; /sbin/reboot";
const OPENWRT_ID: &str = "set -eu\nid -u\nuname -r\ncat /proc/sys/kernel/random/boot_id\ncat /proc/cmdline\nubus call system board\n";
const NOFOLLOW: i32 = libc::O_NOFOLLOW; // Linux and Android ARM64 use different values.
type Environment = BTreeMap<Vec<u8>, Vec<u8>>;

#[derive(Clone)]
struct Pins { appsbl: String, writer: String }
fn pins() -> Pins { Pins { appsbl: APPSBL_SHA.into(), writer: WRITER_SHA.into() } }

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Fingerprints {
    appsbl: String, writer: String, config: String, geometry: String, compatible: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct OpenWrt {
    verified: bool, kernel: String, board: String, boot_id: String,
    model: String, release: String, revision: String,
    reference_release_match: bool, checked_at: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Record {
    schema: u32, profile: String, id: String, target: String,
    phase: String, resolved: bool, started: bool,
    writer_attempted: bool, restore_attempted: bool,
    server_ip: String, boot_id: String,
    environment_before_sha256: String, fingerprints: Fingerprints,
    plan_sha256: String, batch_sha256: String,
    prepared_at: u64, updated_at: u64,
    original_boot_policy_restored: bool, stock_boot_changed: bool,
    original_environment_exact_match: bool,
    resolution: Option<String>, error: Option<String>, openwrt: Option<OpenWrt>,
}
struct Snapshot { boot: String, raw: Vec<u8>, env: Environment, fingerprints: Fingerprints }
struct Plan { script: Vec<u8>, batch: Vec<u8>, expected: Environment }

fn need(ok: bool, message: &str) -> Result<()> { if ok { Ok(()) } else { Err(message.into()) } }
fn hex_string(s: &str, size: usize) -> bool {
    s.len() == size && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn boot_id(s: &str) -> bool {
    uuid::Uuid::parse_str(s).is_ok_and(|v| v.to_string() == s)
}
fn server(s: &str) -> Result<String> {
    let ip = s.parse::<Ipv4Addr>().map_err(|_| "Use a Windows Ethernet address in 192.168.1.2–254".to_string())?;
    let o = ip.octets();
    need(ip.to_string() == s && o[..3] == [192,168,1] && (2..=254).contains(&o[3]),
        "Use a canonical Windows Ethernet address in 192.168.1.2–254")?;
    Ok(s.into())
}
fn parse_env(raw: &[u8]) -> Result<Environment> {
    need(raw.len() == ENV_BYTES, "Boot environment size changed")?;
    need(raw[ENV_DATA_BYTES..].iter().all(|b| *b == 255), "Boot environment spare half is not erased")?;
    let expected = u32::from_le_bytes(raw[..4].try_into().unwrap());
    need(crc32fast::hash(&raw[4..ENV_DATA_BYTES]) == expected, "Boot environment CRC is invalid")?;
    let end = raw[4..ENV_DATA_BYTES].windows(2).position(|v| v == b"\0\0")
        .ok_or("Boot environment terminator is missing")? + 4;
    let mut values = Environment::new();
    for row in raw[4..end].split(|b| *b == 0) {
        let equals = row.iter().position(|b| *b == b'=').ok_or("Boot environment entry is invalid")?;
        let key = &row[..equals];
        need(!key.is_empty() && key.len() <= 64 && key.iter().all(|b|
            b.is_ascii_alphanumeric() || b"_.@-".contains(b)), "Boot environment key is invalid")?;
        need(values.insert(key.to_vec(), row[equals+1..].to_vec()).is_none(), "Duplicate boot environment key")?;
    }
    Ok(values)
}
fn value<'a>(env: &'a Environment, key: &[u8]) -> Option<&'a [u8]> { env.get(key).map(Vec::as_slice) }
fn make_plan(env: &Environment, address: &str, operation_id: &str) -> Result<Plan> {
    let address = server(address)?;
    need(hex_string(operation_id,32),"Invalid RAM operation identifier")?;
    need(matches!(value(env,b"bootcmd"), None | Some(b"bootipq")), "A custom boot command is present; this profile cannot replace it")?;
    let delay = value(env,b"bootdelay").unwrap_or(b"1");
    need((1..=2).contains(&delay.len()) && delay.iter().all(u8::is_ascii_digit) &&
        std::str::from_utf8(delay).ok().and_then(|s|s.parse::<u8>().ok()).is_some_and(|n|n<=10),
        "Boot delay differs from the verified profile")?;
    let original_fdt = value(env,b"fdtcontroladdr");
    let fdt = if let Some(bytes) = original_fdt {
        let text = std::str::from_utf8(bytes).map_err(|_| "Unexpected FDT control address".to_string())?;
        let digits = text.strip_prefix("0x").or_else(||text.strip_prefix("0X")).unwrap_or(text);
        need((1..=8).contains(&digits.len()) && digits.bytes().all(|b|b.is_ascii_hexdigit()), "Unexpected FDT control address")?;
        Some(text)
    } else { None };
    let restore = format!("setenv bootcmd{} && setenv fdtcontroladdr{} && saveenv",
        if env.contains_key(b"bootcmd".as_slice()) { " bootipq" } else { "" },
        fdt.map(|s|format!(" {s}")).unwrap_or_default());
    // A positive interface count makes `go` return shell failure. The semicolon
    // is deliberate; joining this call with && prevents a successful RAM boot.
    let script = format!("if {restore}; then go 0x4a985a2d; if setenv autostart no && setenv verify yes && setenv bootargs \"console=ttyMSM0,115200n8 rdinit=/init rootfstype=ramfs panic=15 internetdeco_ram_id={operation_id}\" && setenv ipaddr 192.168.1.1 && setenv netmask 255.255.255.0 && setenv serverip {address} && setenv ethact eth0 && setenv netretry no && setenv tftptimeout 2000 && setenv tftptimeoutcountmax 4; then while test 1 = 1; do if tftpboot 0x44000000 {IMAGE_FILE}; then if itest.l ${{filesize}} == c73b94 && crc32 44000000 c73b94 43800000 && itest.l *43800000 == 80efe96b; then bootm 44000000; fi; bootipq; fi; sleep 1; done; fi; fi; bootipq");
    need(script.is_ascii() && !script.contains(['\n','\r']) && script.len() < 900, "RAM boot command exceeds its verified bounds")?;
    let mut batch = format!("bootcmd {script}\n").into_bytes();
    let mut expected = env.clone();
    expected.insert(b"bootcmd".to_vec(), script.as_bytes().to_vec());
    if original_fdt.is_some() {
        batch.extend_from_slice(b"fdtcontroladdr\n"); expected.remove(b"fdtcontroladdr".as_slice());
    }
    Ok(Plan { script: script.into_bytes(), batch, expected })
}
fn number(s: &[u8], radix: u32) -> Result<u64> {
    let s = std::str::from_utf8(s).map_err(|_|"Invalid device geometry".to_string())?;
    u64::from_str_radix(s.trim_start_matches("0x").trim_start_matches("0X"),radix)
        .map_err(|_|"Invalid device geometry".into())
}
fn validate_config(raw: &[u8]) -> Result<()> {
    let text = std::str::from_utf8(raw).map_err(|_|"Invalid environment configuration".to_string())?;
    let rows: Vec<Vec<&str>> = text.lines().map(|s|s.split('#').next().unwrap().split_whitespace().collect())
        .filter(|r: &Vec<&str>|!r.is_empty()).collect();
    need(rows.len()==1 && rows[0].len()==5 && rows[0][0]=="/dev/mtd7", "Environment configuration differs from the verified profile")?;
    let numbers: Vec<u64> = rows[0][1..].iter().map(|s|number(s.as_bytes(),16)).collect::<Result<_>>()?;
    need(numbers==[0,262144,131072,2], "Environment erase geometry changed")
}
fn validate_geometry(raw: &[u8]) -> Result<()> {
    let parts: Vec<&[u8]> = raw.split(u8::is_ascii_whitespace).filter(|s|!s.is_empty()).collect();
    need(parts.len()==4, "Invalid MTD geometry response")?;
    need(number(parts[0],10)?==524288 && number(parts[1],10)?==131072 && number(parts[2],10)?==0,
        "MTD size, erase size or bad-block status changed")?;
    let radix = if parts[3].starts_with(b"0x") || parts[3].starts_with(b"0X") {16} else {10};
    need(number(parts[3],radix)? & 0x400 != 0, "Boot environment is not writable")
}
fn identity(e: &mut Engine<'_>) -> Result<(String,String)> {
    let raw = e.exec(IDENTITY,&[],4096)?;
    let lines: Vec<&[u8]> = raw.splitn(5,|b|*b==b'\n').collect();
    need(lines.len()==5 && lines[..3]==[b"0".as_slice(),b"aarch64",b"4.4.60"] &&
        lines[4].split(|b|*b==0).any(|s|s==b"qcom,ipq5018"), "This RAM profile requires the verified stock X50 V1 platform")?;
    let boot = std::str::from_utf8(lines[3]).map_err(|_|"Invalid stock boot ID".to_string())?;
    need(boot_id(boot),"Invalid stock boot ID")?;
    Ok((boot.into(),sha256(lines[4])))
}
fn snapshot(e: &mut Engine<'_>, expected: &Pins) -> Result<Snapshot> {
    e.connect()?;
    let (boot,compatible)=identity(e)?;
    let config=e.exec(CONFIG,&[],4096)?;validate_config(&config)?;
    let geometry=e.exec(GEOMETRY,&[],1024)?;validate_geometry(&geometry)?;
    need(e.exec(MTD_NAMES,&[],128)?==b"0:APPSBLENV\n0:APPSBL\n", "MTD partition identities changed")?;
    let app=e.exec("cat /dev/mtd8",&[],APPSBL_BYTES)?;
    need(app.len()==APPSBL_BYTES && sha256(&app)==expected.appsbl,"Actual bootloader fingerprint differs from the verified profile")?;
    let writer=e.exec("cat /usr/sbin/fw_setenv",&[],65536)?;
    need(sha256(&writer)==expected.writer,"Environment writer fingerprint changed")?;
    let raw=e.exec(READ_ENV,&[],ENV_BYTES)?;let env=parse_env(&raw)?;
    need(e.exec(READ_ENV,&[],ENV_BYTES)?==raw && identity(e)?.0==boot,"Environment or stock boot changed during verification")?;
    Ok(Snapshot {boot,raw,env,fingerprints:Fingerprints {appsbl:expected.appsbl.clone(),writer:expected.writer.clone(),config:sha256(&config),geometry:sha256(&geometry),compatible}})
}

unsafe extern "C" { fn geteuid() -> u32; }
fn uid() -> u32 { unsafe {geteuid()} }
fn path_safe(e: &Engine<'_>) -> Result<()> {
    need(e.home.is_absolute() && !e.home.components().any(|v|matches!(v,Component::ParentDir)),"Invalid app storage path")?;
    need(target(&e.host)?==e.host,"Device address is not canonical")
}
fn safe_dir(path: &Path, private: bool) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(m) => need(m.is_dir() && !m.file_type().is_symlink() && m.uid()==uid() &&
            m.mode() & if private {0o077} else {0o022} == 0,"Unsafe local RAM operation directory"),
        Err(err) if err.kind()==std::io::ErrorKind::NotFound => {
            use std::os::unix::fs::DirBuilderExt;
            fs::DirBuilder::new().mode(0o700).create(path).map_err(|_|"Could not create private RAM operation directory".to_string())?;
            File::open(path.parent().ok_or("Invalid private directory")?).and_then(|f|f.sync_all())
                .map_err(|_|"Could not persist the private RAM directory".to_string())
        },
        Err(_) => Err("Could not inspect the private RAM directory".into()),
    }
}
fn node(e: &Engine<'_>) -> PathBuf {e.home.join("nodes").join(&e.host)}
fn journal(e: &Engine<'_>) -> PathBuf {node(e).join("ram-boot.json")}
fn backup_dir(e: &Engine<'_>, id: &str) -> PathBuf {e.home.join("backups").join(&e.host).join(format!("ram-{id}"))}
fn directories(e: &Engine<'_>) -> Result<()> {
    path_safe(e)?;safe_dir(&e.home,false)?;
    for path in [e.home.join("nodes"),node(e),e.home.join("backups"),e.home.join("backups").join(&e.host)] {safe_dir(&path,false)?;}
    Ok(())
}
fn read_private(path: &Path, limit: usize) -> Result<Vec<u8>> {
    let before=fs::symlink_metadata(path).map_err(|_|"Required RAM journal or backup is missing".to_string())?;
    need(before.is_file() && !before.file_type().is_symlink() && before.uid()==uid() && before.mode()&0o077==0 && before.nlink()==1 && before.len()<=limit as u64,
        "Unsafe RAM journal or backup file")?;
    let mut f=OpenOptions::new().read(true).custom_flags(NOFOLLOW).open(path).map_err(|_|"Could not open private RAM state".to_string())?;
    let m=f.metadata().map_err(|_|"Could not inspect private RAM state".to_string())?;
    need(m.ino()==before.ino() && m.dev()==before.dev(),"RAM state changed while opening it")?;
    let mut data=vec![];Read::by_ref(&mut f).take(limit as u64+1).read_to_end(&mut data).map_err(|_|"Could not read private RAM state".to_string())?;
    need(data.len()<=limit,"Private RAM state exceeds its size limit")?;Ok(data)
}
fn write_new(path: &Path, data: &[u8]) -> Result<()> {
    let mut f=OpenOptions::new().write(true).create_new(true).mode(0o600).custom_flags(NOFOLLOW).open(path)
        .map_err(|_|"Private RAM backup already exists or cannot be created".to_string())?;
    f.write_all(data).and_then(|_|f.sync_all()).map_err(|_|"Could not durably save private RAM state".to_string())?;
    File::open(path.parent().ok_or("Invalid RAM state path")?).and_then(|f|f.sync_all())
        .map_err(|_|"Could not persist the RAM state directory".into())
}
fn valid_record(e: &Engine<'_>, r: &Record, expected: &Pins) -> Result<()> {
    need(r.schema==1 && r.profile==PROFILE && r.target==e.host && hex_string(&r.id,32) && boot_id(&r.boot_id) && server(&r.server_ip).is_ok(),
        "RAM operation belongs to another device or profile")?;
    need([&r.environment_before_sha256,&r.plan_sha256,&r.batch_sha256,&r.fingerprints.config,&r.fingerprints.geometry,&r.fingerprints.compatible]
        .iter().all(|s|hex_string(s,64)) && r.fingerprints.appsbl==expected.appsbl && r.fingerprints.writer==expected.writer,
        "RAM operation fingerprints are invalid")?;
    let phases=["prepared","env_write_requested","env_write_unconfirmed","env_readback_unconfirmed","armed_verified","reboot_requested","reboot_unconfirmed","openwrt_verified","restore_write_requested","restore_unconfirmed","resolved"];
    need(phases.contains(&r.phase.as_str()) && r.resolved==(r.phase=="resolved"),"RAM journal phase is invalid")?;
    need(!r.started || r.writer_attempted,"RAM journal write history is invalid")?;
    need(r.phase!="prepared" || (!r.started && !r.writer_attempted && !r.restore_attempted),"Prepared RAM journal already contains a write intent")?;
    Ok(())
}
fn load_record(e: &Engine<'_>, expected: &Pins) -> Result<Option<Record>> {
    path_safe(e)?;
    for p in [e.home.clone(),e.home.join("nodes"),node(e)] {
        match fs::symlink_metadata(&p) {
            Err(err) if err.kind()==std::io::ErrorKind::NotFound=>return Ok(None),
            Err(_)=>return Err("Could not inspect the RAM journal directory".into()),
            Ok(_)=>safe_existing_dir(&p)?,
        }
    }
    match fs::symlink_metadata(journal(e)) {
        Err(err) if err.kind()==std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("Could not inspect the RAM operation journal".into()),
        Ok(_) => {},
    }
    let r:Record=serde_json::from_slice(&read_private(&journal(e),32768)?).map_err(|_|"RAM operation journal is damaged".to_string())?;
    valid_record(e,&r,expected)?;Ok(Some(r))
}
fn safe_existing_dir(path: &Path) -> Result<()> {
    let m=fs::symlink_metadata(path).map_err(|_|"Private RAM directory is missing".to_string())?;
    need(m.is_dir() && !m.file_type().is_symlink() && m.uid()==uid() && m.mode()&0o022==0,"Unsafe private RAM directory")
}
fn original(e: &Engine<'_>, r: &Record) -> Result<(Vec<u8>,Environment,Plan)> {
    for p in [e.home.join("backups"),e.home.join("backups").join(&e.host),backup_dir(e,&r.id)] {safe_existing_dir(&p)?;}
    let folder=fs::symlink_metadata(backup_dir(e,&r.id)).map_err(|_|"Private RAM backup directory is missing".to_string())?;
    need(folder.mode()&0o077==0,"Private RAM backup directory permissions changed")?;
    let raw=read_private(&backup_dir(e,&r.id).join("environment.before.bin"),ENV_BYTES)?;
    need(sha256(&raw)==r.environment_before_sha256,"Private RAM environment backup changed")?;
    let env=parse_env(&raw)?;let plan=make_plan(&env,&r.server_ip,&r.id)?;
    need(sha256(&plan.script)==r.plan_sha256 && sha256(&plan.batch)==r.batch_sha256,"RAM plan no longer matches its journal")?;
    Ok((raw,env,plan))
}
fn save_record(e: &Engine<'_>, r: &mut Record, create: bool, expected: &Pins) -> Result<()> {
    valid_record(e,r,expected)?;
    let old=load_record(e,expected)?;
    need(if create {old.is_none()} else {old.as_ref().is_some_and(|v|v.id==r.id)},"RAM journal changed; no mutation was requested")?;
    r.updated_at=now();let data=serde_json::to_vec(r).map_err(|_|"Could not encode RAM journal".to_string())?;
    let temp=node(e).join(format!("ram-boot.{}.new",nonce()));write_new(&temp,&data)?;
    fs::rename(&temp,journal(e)).map_err(|_|"Could not commit RAM journal".to_string())?;
    File::open(node(e)).and_then(|f|f.sync_all()).map_err(|_|"Could not persist RAM journal commit".into())
}
fn archive(e:&Engine<'_>, r:&Record) -> Result<()> {
    need(r.resolved,"An unfinished RAM operation must be checked or restored first")?;
    let raw=read_private(&journal(e),32768)?;let path=node(e).join(format!("ram-boot.{}.resolved.json",r.id));
    if path.exists() || fs::symlink_metadata(&path).is_ok() {need(read_private(&path,32768)?==raw,"Existing RAM archive differs; it was preserved")?;}
    else {write_new(&path,&raw)?;}
    fs::remove_file(journal(e)).map_err(|_|"Could not archive the completed RAM operation".to_string())?;
    File::open(node(e)).and_then(|f|f.sync_all()).map_err(|_|"Could not persist RAM archive".into())
}
fn expected_id(args:&Value,r:&Record,mandatory:bool)->Result<()> {
    match args.get("operation_id") {
        Some(v)=>need(v.as_str()==Some(r.id.as_str()),"RAM operation changed; refresh this device before continuing"),
        None=>need(!mandatory,"Refresh the RAM operation before confirming this action"),
    }
}
fn confirmed(args:&Value,r:&Record)->Result<()> {expected_id(args,r,true)?;need(args["confirmed"]==true,"Confirm this specific RAM boot or policy restore first")}
fn status(e:&Engine<'_>, r:Option<&Record>)->Value {
    let phase=r.map(|v|v.phase.as_str()).unwrap_or("none");
    let resolved=r.is_none_or(|v|v.resolved);
    let message=match phase {
        "none"=>"Prepare the verified X50 V1 RAM boot profile.",
        "prepared"=>"Backup and read-only checks are complete. Confirm Start only when the Windows TFTP server and Ethernet cable are ready.",
        "resolved"=>"Original stock boot policy is verified. A new RAM operation can be prepared.",
        "openwrt_verified"=>"OpenWrt RAM userspace is verified. Stock boot policy still requires checking after stock returns.",
        "armed_verified"=>"One-time boot policy was read back. Check the current result; do not repeat Start.",
        "reboot_requested"|"reboot_unconfirmed"=>"Reboot was requested; completion is unconfirmed. Verify OpenWrt or check stock after it returns.",
        _=>"A boot-policy write is unresolved. Preserve the backup, check stock, or explicitly restore the owned boot policy. Never repeat Start.",
    };
    json!({"profile":PROFILE,"target":e.host,"operation_id":r.map(|v|v.id.as_str()),"id":r.map(|v|v.id.as_str()),
        "phase":phase,"resolved":resolved,"started":r.is_some_and(|v|v.started),
        "server_ip":r.map(|v|v.server_ip.as_str()).unwrap_or("192.168.1.2"),"client_ip":RAM_HOST,
        "image_file":IMAGE_FILE,"image_bytes":IMAGE_BYTES,"image_sha256":IMAGE_SHA,"image_crc32":"6be9ef80","reference_release":"r37324.2",
        "can_prepare":resolved,"can_start":phase=="prepared","can_restore":r.is_some_and(|v|!v.resolved&&!v.restore_attempted),
        "original_boot_policy_restored":r.is_some_and(|v|v.original_boot_policy_restored),
        "stock_boot_changed":r.is_some_and(|v|v.stock_boot_changed),
        "original_environment_exact_match":r.is_some_and(|v|v.original_environment_exact_match),
        "backup_folder":r.map(|v|backup_dir(e,&v.id)),"openwrt":r.and_then(|v|v.openwrt.as_ref()),
        "message":message,"error":r.and_then(|v|v.error.as_deref()),"resolution":r.and_then(|v|v.resolution.as_deref()),
        "single_copy_env_non_atomic":true,"boot_environment_writes":r.is_some_and(|v|v.writer_attempted),
        "firmware_image_writes":false,"tftp_wait_repeats_until_file_or_power_cycle":true})
}
fn guard_bootstrap(e:&Engine<'_>)->Result<()> {
    let p=node(e).join("bootstrap.json");
    if !p.exists() && fs::symlink_metadata(&p).is_err(){return Ok(());}
    let v:Value=serde_json::from_slice(&read_private(&p,1024*1024)?).map_err(|_|"Check the unfinished root setup first".to_string())?;
    need(!["upload_restore","upload_server","cache_write_pending","trigger_sent"].contains(&v["phase"].as_str().unwrap_or("")),
        "Check the unfinished root setup before a RAM boot operation")
}

/// Called for ordinary mutations too, including root bootstrap/continuation.
pub fn guard_pending(e:&Engine<'_>,action:&str)->Result<()> {
    guard_pending_with_pins(e,action,&pins())
}
fn guard_pending_with_pins(e:&Engine<'_>,action:&str,expected:&Pins)->Result<()> {
    if action.starts_with("ram_"){return Ok(());}
    if let Some(r)=load_record(e,expected)? {
        need(r.resolved,"This Deco has an unfinished RAM boot operation. Check or restore it before making another change")?;
    }
    Ok(())
}
fn prepare(e:&mut Engine<'_>,args:&Value,expected:&Pins)->Result<Value> {
    directories(e)?;guard_bootstrap(e)?;
    if let Some(r)=load_record(e,expected)? {original(e,&r)?;archive(e,&r)?;}
    let address=server(args["server_ip"].as_str().unwrap_or("192.168.1.2"))?;
    e.event("Checking the stock X50 bootloader and making a private boot-environment backup")?;
    let snap=snapshot(e,expected)?;let id=nonce();let plan=make_plan(&snap.env,&address,&id)?;
    let folder=backup_dir(e,&id);safe_dir(&folder,true)?;
    write_new(&folder.join("environment.before.bin"),&snap.raw)?;
    need(read_private(&folder.join("environment.before.bin"),ENV_BYTES)?==snap.raw,"Private environment backup readback failed")?;
    let environment_before_sha256=sha256(&snap.raw);
    let verified=serde_json::to_vec(&json!({"kind":"boot_environment","verified":true,"host":e.host,
        "bytes":ENV_BYTES,"sha256":environment_before_sha256,"full_nand_backup":false}))
        .map_err(|_|"Could not encode the environment backup receipt".to_string())?;
    write_new(&folder.join("verified.json"),&verified)?;
    let mut r=Record {schema:1,profile:PROFILE.into(),id,target:e.host.clone(),phase:"prepared".into(),resolved:false,started:false,
        writer_attempted:false,restore_attempted:false,server_ip:address,boot_id:snap.boot,environment_before_sha256,
        fingerprints:snap.fingerprints,plan_sha256:sha256(&plan.script),batch_sha256:sha256(&plan.batch),prepared_at:now(),updated_at:now(),
        original_boot_policy_restored:true,stock_boot_changed:false,original_environment_exact_match:true,resolution:None,error:None,openwrt:None};
    save_record(e,&mut r,true,expected)?;Ok(status(e,Some(&r)))
}
fn get(e:&Engine<'_>,args:&Value,expected:&Pins)->Result<Record> {
    let r=load_record(e,expected)?.ok_or("Prepare a RAM boot operation first")?;
    expected_id(args,&r,false)?;original(e,&r)?;Ok(r)
}
fn start(e:&mut Engine<'_>,args:&Value,expected:&Pins)->Result<Value> {
    let mut r=get(e,args,expected)?;confirmed(args,&r)?;guard_bootstrap(e)?;
    need(r.phase=="prepared"&&!r.resolved&&!r.writer_attempted,"RAM Start was already requested or this operation is not prepared. Check its result; never repeat it")?;
    let (before,_,plan)=original(e,&r)?;let snap=snapshot(e,expected)?;
    need(snap.boot==r.boot_id && snap.fingerprints==r.fingerprints && snap.raw==before,
        "Stock boot, environment or verified fingerprints changed. No write was requested")?;
    r.phase="env_write_requested".into();r.writer_attempted=true;r.started=true;r.error=None;
    r.original_boot_policy_restored=false;r.original_environment_exact_match=false;
    save_record(e,&mut r,false,expected)?;
    if e.exec(WRITE_ENV,&plan.batch,2048).is_err() {
        r.phase="env_write_unconfirmed".into();r.error=Some("Writer completion is unknown; do not repeat Start".into());
        save_record(e,&mut r,false,expected)?;return Ok(status(e,Some(&r)));
    }
    let verified=(||->Result<()>{let raw=e.exec(READ_ENV,&[],ENV_BYTES)?;
        need(parse_env(&raw)?==plan.expected,"Boot environment write readback differs")?;
        need(identity(e)?.0==r.boot_id,"Stock boot changed after the environment write")?;Ok(())})();
    if verified.is_err() {
        r.phase="env_readback_unconfirmed".into();r.error=Some("Boot-policy readback was not verified; reboot was not requested".into());
        save_record(e,&mut r,false,expected)?;return Ok(status(e,Some(&r)));
    }
    r.phase="armed_verified".into();save_record(e,&mut r,false,expected)?;
    r.phase="reboot_requested".into();save_record(e,&mut r,false,expected)?;
    if e.exec(REBOOT,&[],1024).is_err() {
        r.phase="reboot_unconfirmed".into();r.error=Some("SSH disconnected; reboot completion remains unconfirmed".into());save_record(e,&mut r,false,expected)?;
    }
    Ok(status(e,Some(&r)))
}
fn same_policy(a:&Environment,b:&Environment)->bool {
    [b"bootcmd".as_slice(),b"fdtcontroladdr"].iter().all(|key|value(a,key)==value(b,key))
}
fn check_stock(e:&mut Engine<'_>,args:&Value,expected:&Pins)->Result<Value> {
    let mut r=get(e,args,expected)?;let (before,old,_)=original(e,&r)?;let snap=snapshot(e,expected)?;
    r.original_boot_policy_restored=same_policy(&snap.env,&old);r.stock_boot_changed=snap.boot!=r.boot_id;
    r.original_environment_exact_match=snap.raw==before;
    if r.original_boot_policy_restored&&r.stock_boot_changed {
        r.phase="resolved".into();r.resolved=true;r.resolution=Some("stock_return_and_original_policy_verified".into());r.error=None;
    }
    save_record(e,&mut r,false,expected)?;Ok(status(e,Some(&r)))
}
fn restore_batch(current:&Environment,old:&Environment)->(Vec<u8>,Environment) {
    let mut batch=vec![];let mut expected=current.clone();
    for key in [b"bootcmd".as_slice(),b"fdtcontroladdr"] {
        if value(current,key)==value(old,key){continue;}
        batch.extend_from_slice(key);
        match value(old,key) {
            Some(v)=>{batch.push(b' ');batch.extend_from_slice(v);expected.insert(key.to_vec(),v.to_vec());},
            None=>{expected.remove(key);},
        }
        batch.push(b'\n');
    }
    (batch,expected)
}
fn restore(e:&mut Engine<'_>,args:&Value,expected:&Pins)->Result<Value> {
    let mut r=get(e,args,expected)?;confirmed(args,&r)?;guard_bootstrap(e)?;
    need(!r.resolved&&!r.restore_attempted,"This restore is already requested or complete; check stock rather than repeating a write")?;
    let (before,old,plan)=original(e,&r)?;let snap=snapshot(e,expected)?;
    need(snap.fingerprints==r.fingerprints,"Device fingerprints changed; policy restore is blocked")?;
    if r.phase=="prepared"&&!r.writer_attempted {
        need(snap.boot==r.boot_id&&same_policy(&snap.env,&old),"Prepared boot policy or stock boot changed; use Check stock first")?;
        r.phase="resolved".into();r.resolved=true;r.original_boot_policy_restored=true;r.original_environment_exact_match=snap.raw==before;
        r.resolution=Some("cancelled_before_any_environment_write".into());r.error=None;
        save_record(e,&mut r,false,expected)?;return Ok(status(e,Some(&r)));
    }
    need(value(&snap.env,b"bootcmd")==Some(plan.script.as_slice()),"Current boot command is not owned by this operation; no restore write is allowed")?;
    let (batch,wanted)=restore_batch(&snap.env,&old);need(!batch.is_empty(),"No owned boot-policy change to restore")?;
    r.phase="restore_write_requested".into();r.restore_attempted=true;r.error=None;save_record(e,&mut r,false,expected)?;
    let result=(||->Result<()>{e.exec(WRITE_ENV,&batch,2048)?;let raw=e.exec(READ_ENV,&[],ENV_BYTES)?;
        need(parse_env(&raw)?==wanted,"Policy restore readback differs")?;
        need(identity(e)?.0==snap.boot,"Stock boot changed during restore")?;Ok(())})();
    if result.is_err() {
        r.phase="restore_unconfirmed".into();r.error=Some("Policy restore completion is unknown; do not repeat the writer".into());
    } else {
        r.phase="resolved".into();r.resolved=true;r.original_boot_policy_restored=true;r.stock_boot_changed=snap.boot!=r.boot_id;
        r.resolution=Some("explicit_owned_policy_restore_verified".into());
    }
    save_record(e,&mut r,false,expected)?;Ok(status(e,Some(&r)))
}
fn check_openwrt(e:&mut Engine<'_>,args:&Value,expected:&Pins)->Result<Value> {
    let mut r=get(e,args,expected)?;
    need(!r.resolved&&["reboot_requested","reboot_unconfirmed","openwrt_verified"].contains(&r.phase.as_str()),
        "OpenWrt verification requires this operation's reboot request")?;
    e.p.call(json!({"op":"ram_connect","host":RAM_HOST,"stock_host":e.host,"operation_id":r.id}))?;
    let reply=e.p.call(json!({"op":"ram_exec","host":RAM_HOST,"stock_host":e.host,"operation_id":r.id,"command":OPENWRT_ID,"input":b64(&[]),"limit":16384}))?;
    need(reply["exit"]==0,"OpenWrt identity command did not complete")?;
    let data=decode(reply["data"].as_str().ok_or("Invalid OpenWrt response")?)?;
    need(data.len()<=16384,"OpenWrt identity response exceeds its bounds")?;
    let lines:Vec<&[u8]>=data.splitn(5,|b|*b==b'\n').collect();
    need(lines.len()==5&&lines[0]==b"0"&&lines[1]==b"6.18.55","The connected system is not the expected root OpenWrt6.18.55")?;
    let boot=std::str::from_utf8(lines[2]).map_err(|_|"Invalid OpenWrt boot ID".to_string())?;
    need(boot_id(boot)&&boot!=r.boot_id,"A new OpenWrt boot ID was not observed")?;
    let command=std::str::from_utf8(lines[3]).map_err(|_|"Invalid OpenWrt kernel command line".to_string())?;
    need(command.split_whitespace().any(|v|v=="rdinit=/init")&&command.split_whitespace().any(|v|v=="rootfstype=ramfs"),"The connected system does not show the expected RAM boot arguments")?;
    let operation_arg=format!("internetdeco_ram_id={}",r.id);
    let mut operation_args=command.split_whitespace().filter(|v|v.starts_with("internetdeco_ram_id="));
    need(operation_args.next()==Some(operation_arg.as_str())&&operation_args.next().is_none(),
        "This OpenWrt RAM boot belongs to another operation or has no operation identifier")?;
    let board:Value=serde_json::from_slice(lines[4]).map_err(|_|"Invalid OpenWrt board response".to_string())?;
    need(board["board_name"]=="tplink,deco-x50-v1","OpenWrt board does not match Deco X50 V1")?;
    let field=|v:&Value|v.as_str().unwrap_or("").chars().take(256).collect::<String>();
    let revision=field(&board["release"]["revision"]);let release=field(&board["release"]["description"]);
    r.openwrt=Some(OpenWrt{verified:true,kernel:"6.18.55".into(),board:"tplink,deco-x50-v1".into(),boot_id:boot.into(),
        model:field(&board["model"]),reference_release_match:revision=="r37324.2"||release.contains("r37324.2"),release,revision,checked_at:now()});
    r.phase="openwrt_verified".into();r.error=None;save_record(e,&mut r,false,expected)?;Ok(status(e,Some(&r)))
}
fn dispatch_with_pins(e:&mut Engine<'_>,name:&str,args:&Value,expected:&Pins)->Result<Value> {
    path_safe(e)?;
    match name {
        "ram_status"=>{let r=load_record(e,expected)?;if let Some(r)=&r {original(e,r)?;}Ok(status(e,r.as_ref()))},
        "ram_prepare"=>prepare(e,args,expected),
        "ram_start"=>start(e,args,expected),
        "ram_check_stock"=>check_stock(e,args,expected),
        "ram_check_openwrt"=>check_openwrt(e,args,expected),
        "ram_restore"=>restore(e,args,expected),
        _=>Err("Unknown RAM boot action".into()),
    }
}
pub fn dispatch(e:&mut Engine<'_>,name:&str,args:&Value)->Result<Value> {
    dispatch_with_pins(e,name,args,&pins())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Platform;
    use std::os::unix::fs::{DirBuilderExt,PermissionsExt,symlink};

    const BOOT_A:&str="11111111-1111-4111-8111-111111111111";
    const BOOT_B:&str="22222222-2222-4222-8222-222222222222";
    const BOOT_RAM:&str="33333333-3333-4333-8333-333333333333";
    const TEST_ID:&str="0123456789abcdef0123456789abcdef";

    fn encode_rows(rows:&[(&[u8],&[u8])])->Vec<u8> {
        let mut raw=vec![255;ENV_BYTES];raw[4..ENV_DATA_BYTES].fill(0);
        let mut pos=4;
        for (key,val)in rows {
            for part in [*key,b"=".as_slice(),*val,b"\0"] {
                raw[pos..pos+part.len()].copy_from_slice(part);pos+=part.len();
            }
        }
        let crc=crc32fast::hash(&raw[4..ENV_DATA_BYTES]);raw[..4].copy_from_slice(&crc.to_le_bytes());raw
    }
    fn encode(env:&Environment)->Vec<u8> {
        encode_rows(&env.iter().map(|(k,v)|(k.as_slice(),v.as_slice())).collect::<Vec<_>>())
    }
    fn original_env()->Vec<u8> {
        encode_rows(&[(b"bootdelay",b"1"),(b"opaque",&[0xfe,0xff,b'\n',b'=',0x81]),(b"empty",b"")])
    }
    struct Fake {
        home:PathBuf,host:String,raw:Vec<u8>,boot:String,app:Vec<u8>,writer:Vec<u8>,config:Vec<u8>,
        geometry:Vec<u8>,calls:Vec<Value>,writes:Vec<Vec<u8>>,reboots:usize,
        unknown_writer_at:Option<usize>,writer_applies:bool,extra_after_write:bool,
        reboot_unknown:bool,ram_kernel:String,ram_board:String,ram_boot:String,ram_cmdline:String,
    }
    impl Platform for Fake {
        fn call(&mut self, request:Value)->Result<Value> {
            self.calls.push(request.clone());
            match request["op"].as_str().unwrap() {
                "event"=>Ok(json!({})),
                "connect"=>{assert_eq!(request["host"],self.host);Ok(json!({}))},
                "ram_connect"=>{
                    assert_eq!(request["host"],RAM_HOST);assert_eq!(request["stock_host"],self.host);
                    let r:Record=serde_json::from_slice(&fs::read(self.home.join("nodes").join(&self.host).join("ram-boot.json")).unwrap()).unwrap();
                    assert_eq!(request["operation_id"],r.id);Ok(json!({}))
                },
                "ram_exec"=>{
                    assert_eq!(request["host"],RAM_HOST);assert_eq!(request["stock_host"],self.host);
                    assert_eq!(request["command"],OPENWRT_ID);
                    let board=json!({"board_name":self.ram_board,"model":"TP-Link Deco X50 V1","release":{"revision":"r37324.2","description":"OpenWrt r37324.2"}});
                    Ok(json!({"exit":0,"data":b64(format!("0\n{}\n{}\n{}\n{}\n",self.ram_kernel,self.ram_boot,self.ram_cmdline,board).as_bytes())}))
                },
                "exec"=>{
                    assert_eq!(request["host"],self.host);
                    let cmd=request["command"].as_str().unwrap();
                    let body=match cmd {
                        "id -u"=>b"0\n".to_vec(),
                        IDENTITY=>[format!("0\naarch64\n4.4.60\n{}\n",self.boot).as_bytes(),b"tplink,deco-x50-v1\0qcom,ipq5018\0"].concat(),
                        CONFIG=>self.config.clone(),GEOMETRY=>self.geometry.clone(),MTD_NAMES=>b"0:APPSBLENV\n0:APPSBL\n".to_vec(),
                        "cat /dev/mtd8"=>self.app.clone(),"cat /usr/sbin/fw_setenv"=>self.writer.clone(),READ_ENV=>self.raw.clone(),
                        WRITE_ENV=>{
                            let record=self.home.join("nodes").join(&self.host).join("ram-boot.json");
                            let r:Record=serde_json::from_slice(&fs::read(record).unwrap()).unwrap();
                            assert!(["env_write_requested","restore_write_requested"].contains(&r.phase.as_str()));
                            let backup=self.home.join("backups").join(&self.host).join(format!("ram-{}",r.id)).join("environment.before.bin");
                            assert_eq!(sha256(&fs::read(backup).unwrap()),r.environment_before_sha256);
                            let input=decode(request["input"].as_str().unwrap())?;self.writes.push(input.clone());
                            let unknown=self.unknown_writer_at==Some(self.writes.len());
                            if !unknown||self.writer_applies {
                                let mut env=parse_env(&self.raw)?;
                                for line in input.split(|b|*b==b'\n').filter(|v|!v.is_empty()) {
                                    if let Some(i)=line.iter().position(|b|*b==b' ') {env.insert(line[..i].to_vec(),line[i+1..].to_vec());}
                                    else {env.remove(line);}
                                }
                                if self.extra_after_write {env.insert(b"other-admin".to_vec(),vec![0x82,0x83]);self.extra_after_write=false;}
                                self.raw=encode(&env);
                            }
                            if unknown{return Err("SSH transport lost after writer request".into());}
                            b"saved\n".to_vec()
                        },
                        REBOOT=>{
                            self.reboots+=1;
                            let r:Record=serde_json::from_slice(&fs::read(self.home.join("nodes").join(&self.host).join("ram-boot.json")).unwrap()).unwrap();
                            assert_eq!(r.phase,"reboot_requested");
                            if self.reboot_unknown{return Err("SSH disconnected".into());}
                            vec![]
                        },
                        _=>panic!("unreviewed fake command {cmd}"),
                    };
                    Ok(json!({"exit":0,"data":b64(&body)}))
                },
                other=>panic!("unreviewed fake operation {other}"),
            }
        }
    }
    struct Fixture {home:PathBuf,host:String,fake:Fake,pins:Pins}
    impl Fixture {
        fn new()->Self {
            let home=std::env::temp_dir().join(format!("deco-ram-tests-{}",nonce()));
            fs::DirBuilder::new().mode(0o700).create(&home).unwrap();
            let host="10.22.33.44".to_string();
            let app=vec![0x41;APPSBL_BYTES];let writer=b"fake-reviewed-writer".to_vec();
            let pins=Pins{appsbl:sha256(&app),writer:sha256(&writer)};
            let fake=Fake{home:home.clone(),host:host.clone(),raw:original_env(),boot:BOOT_A.into(),app,writer,
                config:b"/dev/mtd7 0x0000 0x40000 0x20000 2\n".to_vec(),geometry:b"524288\n131072\n0\n0x400\n".to_vec(),
                calls:vec![],writes:vec![],reboots:0,unknown_writer_at:None,writer_applies:true,extra_after_write:false,reboot_unknown:true,
                ram_kernel:"6.18.55".into(),ram_board:"tplink,deco-x50-v1".into(),ram_boot:BOOT_RAM.into(),
                ram_cmdline:"console=ttyMSM0,115200n8 rdinit=/init rootfstype=ramfs panic=15".into()};
            Self{home,host,fake,pins}
        }
        fn run(&mut self,name:&str,args:Value)->Result<Value> {
            let mut e=Engine{p:&mut self.fake,home:self.home.clone(),host:self.host.clone()};
            dispatch_with_pins(&mut e,name,&args,&self.pins)
        }
        fn record(&self)->Record {serde_json::from_slice(&fs::read(self.home.join("nodes").join(&self.host).join("ram-boot.json")).unwrap()).unwrap()}
        fn prepare(&mut self)->Value {
            let s=self.run("ram_prepare",json!({"server_ip":"192.168.1.22"})).unwrap();
            self.fake.ram_cmdline=format!("console=ttyMSM0,115200n8 rdinit=/init rootfstype=ramfs panic=15 internetdeco_ram_id={}",s["operation_id"].as_str().unwrap());
            s
        }
        fn confirm(&self)->Value {json!({"operation_id":self.record().id,"confirmed":true})}
        fn start(&mut self)->Value {let args=self.confirm();self.run("ram_start",args).unwrap()}
        fn restore(&mut self)->Value {let args=self.confirm();self.run("ram_restore",args).unwrap()}
    }
    impl Drop for Fixture {fn drop(&mut self){let _=fs::remove_dir_all(&self.home);}}

    #[test] fn env_parser_preserves_non_utf8_empty_values_and_exact_bytes() {
        let raw=original_env();let parsed=parse_env(&raw).unwrap();
        assert_eq!(value(&parsed,b"opaque"),Some([0xfe,0xff,b'\n',b'=',0x81].as_slice()));
        assert_eq!(value(&parsed,b"empty"),Some(b"".as_slice()));assert_eq!(parse_env(&encode(&parsed)).unwrap(),parsed);
    }
    #[test] fn env_parser_rejects_duplicate_keys_crc_and_spare_changes() {
        assert!(parse_env(&encode_rows(&[(b"x",b"a"),(b"x",b"b")])).is_err());
        let mut raw=original_env();raw[9]^=1;assert!(parse_env(&raw).is_err());
        let mut raw=original_env();raw[ENV_DATA_BYTES]=0;assert!(parse_env(&raw).is_err());
        assert!(parse_env(&raw[..ENV_BYTES-1]).is_err());
        assert!(parse_env(&encode_rows(&[(b"bad/key",b"a")])).is_err());
    }
    #[test] fn golden_boot_command_restores_absence_and_go_is_not_AND_chained() {
        let env=parse_env(&original_env()).unwrap();let p=make_plan(&env,"192.168.1.22",TEST_ID).unwrap();let script=std::str::from_utf8(&p.script).unwrap();
        assert!(script.starts_with("if setenv bootcmd && setenv fdtcontroladdr && saveenv; then go 0x4a985a2d; if "));
        assert!(!script.contains("go 0x4a985a2d &&"));assert!(script.len()<900);assert!(!script.contains('\n'));
        assert!(script.contains(&format!("itest.l ${{filesize}} == {:x}",IMAGE_BYTES)));
        assert!(script.contains("crc32 44000000 c73b94 43800000 && itest.l *43800000 == 80efe96b"));
        assert_eq!(0x6be9ef80u32.swap_bytes(),0x80efe96b);assert!(!p.expected.contains_key(b"fdtcontroladdr".as_slice()));
        assert_eq!(p.batch,format!("bootcmd {script}\n").as_bytes());
        assert!(p.batch.split(|b|*b==b'\n').all(|line|line.len()<1024));
        let added=format!(" internetdeco_ram_id={TEST_ID}");
        assert_eq!(script.matches(added.as_str()).count(),1);assert!(script.contains(&format!("panic=15{added}\"")));
        // The sole change from the proven Python command is this operation tag.
        // These complete base hashes were obtained independently from Python make_plan.
        assert_eq!(sha256(script.replace(&added,"").as_bytes()),"280a5d52c60675a4e8fc4db32500f6b9a096e297b8bbf5842ead21a0eb119b19");
        assert_eq!(sha256(std::str::from_utf8(&p.batch).unwrap().replace(&added,"").as_bytes()),"87dccee1b6bab6115dfd602adb74300bf5910510e25d4dafbe89697205d49c88");
    }
    #[test] fn plan_preserves_present_stock_policy_and_removes_early_FDT_override() {
        let env=parse_env(&encode_rows(&[(b"bootcmd",b"bootipq"),(b"fdtcontroladdr",b"0x4a982000")])).unwrap();
        let p=make_plan(&env,"192.168.1.22",TEST_ID).unwrap();
        assert!(p.script.starts_with(b"if setenv bootcmd bootipq && setenv fdtcontroladdr 0x4a982000 && saveenv;"));
        assert!(p.batch.ends_with(b"\nfdtcontroladdr\n"));assert!(!p.expected.contains_key(b"fdtcontroladdr".as_slice()));
        let added=format!(" internetdeco_ram_id={TEST_ID}");
        assert_eq!(sha256(std::str::from_utf8(&p.script).unwrap().replace(&added,"").as_bytes()),"08310c115a53698a2cb3385c95b0ba2fe214eb60cc9213b2da85126febd1474f");
        assert_eq!(sha256(std::str::from_utf8(&p.batch).unwrap().replace(&added,"").as_bytes()),"43b22a392be155f20a4984791eac1714992b27f0861cffad6da5a254da38d095");
    }
    #[test] fn server_and_boot_policy_injection_are_rejected() {
        for bad in ["192.168.1.0","192.168.1.1","192.168.1.255","192.168.01.2","192.168.1.2; reboot","10.1.1.2"," 192.168.1.2"] {assert!(server(bad).is_err());}
        for (k,v)in [(b"bootcmd".as_slice(),b"custom".as_slice()),(b"bootdelay",b"11"),(b"fdtcontroladdr",b"0x1; reboot")] {
            let mut env=parse_env(&original_env()).unwrap();env.insert(k.to_vec(),v.to_vec());assert!(make_plan(&env,"192.168.1.2",TEST_ID).is_err());
        }
        for id in ["", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcdef;reboot"] {
            assert!(make_plan(&parse_env(&original_env()).unwrap(),"192.168.1.2",id).is_err());
        }
    }
    #[test] fn prepare_is_read_only_remote_and_status_is_completely_local() {
        let mut f=Fixture::new();let status=f.run("ram_status",json!({})).unwrap();assert_eq!(status["can_prepare"],true);assert!(f.fake.calls.is_empty());
        let s=f.prepare();assert_eq!(s["phase"],"prepared");assert_eq!(s["target"],"10.22.33.44");assert_eq!(s["original_boot_policy_restored"],true);
        assert!(f.fake.writes.is_empty()&&f.fake.reboots==0);
        let count=f.fake.calls.len();f.run("ram_status",json!({})).unwrap();assert_eq!(f.fake.calls.len(),count);
        let public=s.to_string();assert!(!public.contains("opaque")&&!public.contains("bootcmd "));
        let r=f.record();let p=f.home.join("backups").join(&f.host).join(format!("ram-{}",r.id)).join("environment.before.bin");
        assert_eq!(fs::read(&p).unwrap(),original_env());assert_eq!(fs::metadata(&p).unwrap().mode()&0o777,0o600);
        let receipt=p.with_file_name("verified.json");let v:Value=serde_json::from_slice(&fs::read(&receipt).unwrap()).unwrap();
        assert_eq!(v,json!({"kind":"boot_environment","verified":true,"host":f.host,"bytes":ENV_BYTES,"sha256":sha256(&original_env()),"full_nand_backup":false}));
        assert_eq!(fs::metadata(receipt).unwrap().mode()&0o777,0o600);
    }
    #[test] fn start_requires_confirmation_current_id_and_cannot_repeat() {
        let mut f=Fixture::new();f.prepare();assert!(f.run("ram_start",json!({"confirmed":true,"operation_id":"wrong"})).is_err());
        assert!(f.run("ram_start",json!({"operation_id":f.record().id})).is_err());assert!(f.fake.writes.is_empty());
        let s=f.start();assert_eq!(s["phase"],"reboot_unconfirmed");assert_eq!(f.fake.writes.len(),1);assert_eq!(f.fake.reboots,1);
        let args=f.confirm();assert!(f.run("ram_start",args).is_err());assert_eq!(f.fake.writes.len(),1);
    }
    #[test] fn changed_boot_raw_env_or_fingerprint_blocks_before_writer() {
        for change in 0..4 {
            let mut f=Fixture::new();f.prepare();match change {
                0=>f.fake.boot=BOOT_B.into(),
                1=>{let mut env=parse_env(&f.fake.raw).unwrap();env.insert(b"new".to_vec(),b"value".to_vec());f.fake.raw=encode(&env)},
                2=>f.fake.config.extend_from_slice(b"# changed\n"),
                _=>f.fake.app[1]^=1,
            }
            let args=f.confirm();assert!(f.run("ram_start",args).is_err());assert!(f.fake.writes.is_empty()&&f.fake.reboots==0);
        }
    }
    #[test] fn unknown_writer_outcome_stays_unresolved_without_reboot_or_retry() {
        let mut f=Fixture::new();f.prepare();f.fake.unknown_writer_at=Some(1);let s=f.start();
        assert_eq!(s["phase"],"env_write_unconfirmed");assert_eq!(s["resolved"],false);assert_eq!(f.fake.reboots,0);
        let args=f.confirm();assert!(f.run("ram_start",args).is_err());assert_eq!(f.fake.writes.len(),1);
        let s=f.run("ram_check_stock",json!({})).unwrap();assert_eq!(s["resolved"],false);assert_eq!(f.fake.writes.len(),1);
    }
    #[test] fn same_boot_original_policy_is_not_a_successful_RAM_or_stock_return() {
        let mut f=Fixture::new();f.prepare();f.fake.unknown_writer_at=Some(1);f.fake.writer_applies=false;f.start();
        let s=f.run("ram_check_stock",json!({})).unwrap();assert_eq!(s["original_boot_policy_restored"],true);
        assert_eq!(s["stock_boot_changed"],false);assert_eq!(s["resolved"],false);assert!(s["openwrt"].is_null());
    }
    #[test] fn failed_readback_never_reboots_and_policy_restore_preserves_other_values() {
        let mut f=Fixture::new();f.prepare();f.fake.extra_after_write=true;let s=f.start();assert_eq!(s["phase"],"env_readback_unconfirmed");assert_eq!(f.fake.reboots,0);
        let s=f.restore();assert_eq!(s["resolved"],true);assert_eq!(f.fake.writes.len(),2);
        let env=parse_env(&f.fake.raw).unwrap();assert_eq!(value(&env,b"other-admin"),Some([0x82,0x83].as_slice()));
        assert!(value(&env,b"bootcmd").is_none()&&value(&env,b"fdtcontroladdr").is_none());
        assert_eq!(f.fake.writes[1],b"bootcmd\n");
    }
    #[test] fn prepared_can_be_explicitly_cancelled_without_any_remote_mutation() {
        let mut f=Fixture::new();f.prepare();let s=f.restore();assert_eq!(s["resolved"],true);assert_eq!(s["can_prepare"],true);
        assert!(f.fake.writes.is_empty()&&f.fake.reboots==0);assert_eq!(s["resolution"],"cancelled_before_any_environment_write");
    }
    #[test] fn prepared_cancel_allows_non_policy_drift_but_never_changed_boot_policy() {
        let mut f=Fixture::new();f.prepare();let mut env=parse_env(&f.fake.raw).unwrap();
        env.insert(b"another-owner-value".to_vec(),vec![0xf1,0xf2]);f.fake.raw=encode(&env);
        let args=f.confirm();assert!(f.run("ram_start",args).is_err());
        let s=f.restore();assert_eq!(s["resolved"],true);assert_eq!(s["original_environment_exact_match"],false);
        assert!(f.fake.writes.is_empty()&&f.fake.reboots==0);
        let mut f=Fixture::new();f.prepare();let mut env=parse_env(&f.fake.raw).unwrap();
        env.insert(b"bootcmd".to_vec(),b"other-owner-policy".to_vec());f.fake.raw=encode(&env);
        let args=f.confirm();assert!(f.run("ram_restore",args).is_err());assert!(f.fake.writes.is_empty());
    }
    #[test] fn restore_refuses_foreign_boot_command_and_never_replays_unknown_restore() {
        let mut f=Fixture::new();f.prepare();f.start();let mut env=parse_env(&f.fake.raw).unwrap();env.insert(b"bootcmd".to_vec(),b"other-owner-command".to_vec());f.fake.raw=encode(&env);
        let args=f.confirm();assert!(f.run("ram_restore",args).is_err());assert_eq!(f.fake.writes.len(),1);
        let mut f=Fixture::new();f.prepare();f.start();f.fake.unknown_writer_at=Some(2);let s=f.restore();assert_eq!(s["phase"],"restore_unconfirmed");
        let args=f.confirm();assert!(f.run("ram_restore",args).is_err());assert_eq!(f.fake.writes.len(),2);
        f.fake.boot=BOOT_B.into();let s=f.run("ram_check_stock",json!({})).unwrap();assert_eq!(s["resolved"],true);
    }
    #[test] fn stock_check_needs_new_stock_boot_and_original_policy_and_archives_on_new_prepare() {
        let mut f=Fixture::new();f.prepare();let first=f.record().id;f.start();f.fake.raw=original_env();f.fake.boot=BOOT_B.into();
        let s=f.run("ram_check_stock",json!({})).unwrap();assert_eq!(s["resolved"],true);assert!(s["openwrt"].is_null());
        f.prepare();assert_ne!(f.record().id,first);assert!(f.home.join("nodes").join(&f.host).join(format!("ram-boot.{first}.resolved.json")).is_file());
    }
    #[test] fn unfinished_prepare_is_never_overwritten() {
        let mut f=Fixture::new();f.prepare();let first=f.record().id;
        assert!(f.run("ram_prepare",json!({"server_ip":"192.168.1.99"})).is_err());assert_eq!(f.record().id,first);
    }
    #[test] fn OpenWrt_verification_uses_separate_RAM_ops_and_remains_unresolved() {
        let mut f=Fixture::new();f.prepare();f.start();let s=f.run("ram_check_openwrt",json!({})).unwrap();
        assert_eq!(s["phase"],"openwrt_verified");assert_eq!(s["openwrt"]["verified"],true);assert_eq!(s["resolved"],false);
        assert_eq!(s["openwrt"]["board"],"tplink,deco-x50-v1");assert_eq!(s["openwrt"]["reference_release_match"],true);
        assert_eq!(f.fake.calls.iter().filter(|r|r["op"]=="ram_connect").count(),1);
        assert_eq!(f.fake.calls.iter().filter(|r|r["op"]=="ram_exec").count(),1);
        assert!(!f.fake.calls.iter().any(|r|r["op"]=="exec"&&r["host"]==RAM_HOST));
    }
    #[test] fn wrong_RAM_kernel_board_boot_or_cmdline_does_not_record_success() {
        for case in 0..4 {
            let mut f=Fixture::new();f.prepare();f.start();match case {
                0=>f.fake.ram_kernel="4.4.60".into(),1=>f.fake.ram_board="tplink,deco-m4r-v2".into(),
                2=>f.fake.ram_boot=BOOT_A.into(),_=>f.fake.ram_cmdline="root=/dev/ubiblock0_0".into(),
            }
            assert!(f.run("ram_check_openwrt",json!({})).is_err());assert!(f.record().openwrt.is_none());assert!(!f.record().resolved);
        }
    }
    #[test] fn missing_foreign_or_duplicate_operation_nonce_never_verifies_another_RAM() {
        for case in 0..3 {
            let mut f=Fixture::new();f.prepare();f.start();
            // The kernel, board and new boot ID remain the expected values.
            match case {
                0=>f.fake.ram_cmdline="rdinit=/init rootfstype=ramfs panic=15".into(),
                1=>f.fake.ram_cmdline="rdinit=/init rootfstype=ramfs panic=15 internetdeco_ram_id=00000000000000000000000000000000".into(),
                _=>f.fake.ram_cmdline.push_str(" internetdeco_ram_id=00000000000000000000000000000000"),
            }
            assert!(f.run("ram_check_openwrt",json!({})).is_err());
            assert!(f.record().openwrt.is_none());assert!(!f.record().resolved);assert_eq!(f.fake.writes.len(),1);
        }
    }
    #[test] fn backup_and_journal_identity_corruption_stop_locally_before_SSH() {
        let mut f=Fixture::new();f.prepare();let r=f.record();let p=f.home.join("backups").join(&f.host).join(format!("ram-{}",r.id)).join("environment.before.bin");
        let mut raw=fs::read(&p).unwrap();raw[10]^=1;fs::write(&p,raw).unwrap();let calls=f.fake.calls.len();
        assert!(f.run("ram_status",json!({})).is_err());assert_eq!(f.fake.calls.len(),calls);
        let mut f=Fixture::new();f.prepare();let p=f.home.join("nodes").join(&f.host).join("ram-boot.json");
        let mut r=f.record();r.target="10.22.33.45".into();fs::write(&p,serde_json::to_vec(&r).unwrap()).unwrap();let calls=f.fake.calls.len();
        assert!(f.run("ram_status",json!({})).is_err());assert_eq!(f.fake.calls.len(),calls);
    }
    #[test] fn android_storage_permissions_are_repaired_before_read_only_ram_status() {
        let mut f=Fixture::new();
        fs::set_permissions(&f.home,fs::Permissions::from_mode(0o771)).unwrap();
        assert_eq!(f.run("ram_status",json!({})).unwrap_err(),"Unsafe private RAM directory");
        assert!(f.fake.calls.is_empty());
        crate::private_storage::prepare(&f.home,&f.host).unwrap();
        f.run("ram_status",json!({})).unwrap();assert!(f.fake.calls.is_empty());
        f.prepare();assert_eq!(f.fake.writes.len(),0);assert_eq!(f.fake.reboots,0);
    }
    #[test] fn symlinked_state_or_backup_is_never_followed_or_clobbered() {
        let mut f=Fixture::new();f.prepare();let r=f.record();let b=f.home.join("backups").join(&f.host).join(format!("ram-{}",r.id)).join("environment.before.bin");
        let other=f.home.join("other");fs::write(&other,original_env()).unwrap();fs::set_permissions(&other,fs::Permissions::from_mode(0o600)).unwrap();
        fs::remove_file(&b).unwrap();symlink(&other,&b).unwrap();assert!(f.run("ram_status",json!({})).is_err());assert_eq!(fs::read(other).unwrap(),original_env());
    }
    #[test] fn guard_blocks_all_ordinary_mutations_including_bootstrap_until_prepared_cancel() {
        let mut f=Fixture::new();f.prepare();
        for name in ["bootstrap","continue_install","reboot","write_file"] {
            let e=Engine{p:&mut f.fake,home:f.home.clone(),host:f.host.clone()};assert!(guard_pending_with_pins(&e,name,&f.pins).is_err());
        }
        f.restore();let e=Engine{p:&mut f.fake,home:f.home.clone(),host:f.host.clone()};assert!(guard_pending_with_pins(&e,"bootstrap",&f.pins).is_ok());
    }
    #[test] fn unresolved_bootstrap_blocks_prepare_without_remote_calls() {
        let mut f=Fixture::new();directories(&Engine{p:&mut f.fake,home:f.home.clone(),host:f.host.clone()}).unwrap();
        write_new(&f.home.join("nodes").join(&f.host).join("bootstrap.json"),br#"{"phase":"cache_write_pending"}"#).unwrap();
        assert!(f.run("ram_prepare",json!({})).is_err());assert!(f.fake.calls.is_empty());
    }
}
