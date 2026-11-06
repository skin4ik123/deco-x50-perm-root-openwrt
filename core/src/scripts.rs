use crate::lua;
pub const ENCODE64: &str = include_str!("encode64.lua");
pub const HELPERS: &str = r#"
local fs=require('nixio.fs');local j=require('luci.json')
local function read(p,max)local f=io.open(p,'rb');if not f then return nil end;local s=f:read(max or 65536) or '';f:close();return s end
local function run(c,max)local f=io.popen(c,'r');if not f then return '' end;local s=f:read(max or 65536) or '';f:close();return s end
local function trim(s)return (s or ''):match('^%s*(.-)%s*$')end
local function exists(p)return fs.stat(p)~=nil end
local function writable_dir(p)
 local real=fs.realpath(p);if not real then return false end
 return real=='/tmp' or real:sub(1,5)=='/tmp/' or real=='/tp_data/user_data' or real:sub(1,19)=='/tp_data/user_data/'
end
local function writable_file(p)
 local parent=p:match('^(.*)/[^/]+$');local old=fs.lstat(p)
 return parent~=nil and writable_dir(parent) and (not old or (old.type=='reg' and old.nlink==1))
end
local function identity()
 local uid=tonumber(trim(run('id -u',32)))
 return {uid=uid,kernel=trim(read('/proc/sys/kernel/osrelease',128)),architecture=trim(run('uname -m',64)),
  model=trim(run('getfirm MODEL 2>/dev/null',128)),hardware=trim(run('getfirm HARDVERSION 2>/dev/null',128)),
  hostname=trim(read('/proc/sys/kernel/hostname',128)),compatible=read('/sys/firmware/devicetree/base/compatible',512),
  boot_id=trim(read('/proc/sys/kernel/random/boot_id',100)),
  password_ssh=exists('/tp_data/user_data/internetdeco-ssh/OWNER'),
  enabled=trim(read('/tp_data/user_data/internetdeco-ssh/ENABLE',64))=='enabled',
  uptime=tonumber((read('/proc/uptime',128) or ''):match('^[%d.]+'))}
end
"#;
// Full scripts are composed once by Engine.run_lua; no unbounded proc/log reads.
pub const IDENTITY: &str = concat!(
    include_str!("identity-helper.lua"),
    "\nio.write(j.encode(identity()))"
);
pub const MONITOR: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local v=identity();v.load=trim(read('/proc/loadavg',128));v.cpu_raw=(read('/proc/stat',32768) or ''):match('[^\n]+');v.mem={}
for k,n in (read('/proc/meminfo',16384) or ''):gmatch('([%w_()]+):%s*(%d+)')do v.mem[k]=tonumber(n)*1024 end
v.temperatures={};local oem_ipq5018=v.kernel=='4.4.60' and (v.compatible or ''):find('qcom,ipq5018',1,true)~=nil
for i=0,63 do local p='/sys/class/thermal/thermal_zone'..i;local t=tonumber(trim(read(p..'/temp',32)));if t then local name=trim(read(p..'/type',100));local unit=(oem_ipq5018 and name:match('^tsens_tz_sensor%d+$')) and 'celsius' or 'millicelsius';v.temperatures[#v.temperatures+1]={name=name,path=p..'/temp',raw=t,unit=unit}end end
v.interfaces={};for line in (read('/proc/net/dev',32768) or ''):gmatch('[^\n]+')do local name,data=line:match('^%s*([%w_.-]+):%s*(.*)$');if name and name~='lo' then local a={};for n in data:gmatch('%d+')do a[#a+1]=tonumber(n)end;v.interfaces[#v.interfaces+1]={name=name,rx=a[1],tx=a[9],rx_errors=a[3],tx_errors=a[11],state=trim(read('/sys/class/net/'..name..'/operstate',32)),mac=trim(read('/sys/class/net/'..name..'/address',32))}end end
v.filesystems=run('df -k / /tmp /tp_data/user_data 2>/dev/null',4096);v.conntrack=tonumber(trim(read('/proc/sys/net/netfilter/nf_conntrack_count',32)));v.conntrack_max=tonumber(trim(read('/proc/sys/net/netfilter/nf_conntrack_max',32)))
io.write(j.encode(v))
"#
);
pub const INVENTORY: &str = r#"local u=assert(require('ubus').connect());local n=assert(u:call('sync','list',{}));u:close();io.write(require('luci.json').encode(n))"#;
pub const PROCESSES: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local out={};for name in fs.dir('/proc')do if name:match('^%d+$')then local status=read('/proc/'..name..'/status',8192) or '';out[#out+1]={pid=tonumber(name),name=trim(status:match('Name:%s*([^\n]+)')),state=trim(status:match('State:%s*([^\n]+)')),rss_kib=tonumber(status:match('VmRSS:%s*(%d+)')) or 0,threads=tonumber(status:match('Threads:%s*(%d+)')) or 0}end end;table.sort(out,function(a,b)return a.rss_kib>b.rss_kib end);io.write(j.encode(out))
"#
);
pub const SERVICES: &str = r#"local u=assert(require('ubus').connect());local s=assert(u:call('service','list',{}));u:close();local out={};for name,svc in pairs(s)do for id,v in pairs(svc.instances or {})do out[#out+1]={name=name,instance=id,running=v.running==true,pid=v.pid,respawn=v.respawn~=nil}end end;table.sort(out,function(a,b)return a.name<b.name end);io.write(require('luci.json').encode(out))"#;
pub const CLIENTS: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local out={};for name in fs.dir('/sys/class/net')do if name:match('^[%w_.-]+$') and (exists('/sys/class/net/'..name..'/wireless') or name:match('^ath%d') or name:match('^wlan%d')) then
 local raw=run('iw dev '..name..' station dump 2>/dev/null',131072);local source='iw';if raw=='' then raw=run('wlanconfig '..name..' list sta 2>/dev/null',131072);source='wlanconfig'end
 local info=run('iw dev '..name..' info 2>/dev/null',8192);out[#out+1]={interface=name,source=source,raw=raw,info=info}
 end end;io.write(j.encode(out))
"#
);
pub const DIAGNOSTICS: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local v=identity();v.version=read('/etc/openwrt_release',4096);v.dmesg=run('dmesg | tail -n 120',32768);v.routes=read('/proc/net/route',16384);v.tcp=read('/proc/net/tcp',65536);v.tcp6=read('/proc/net/tcp6',65536);v.mtd=read('/proc/mtd',8192);v.kernel_image_loaded=trim(read('/sys/kernel/kexec_loaded',16));v.restart_armed=trim(read('/sys/module/kexec_mod/parameters/arm_restart',16));io.write(j.encode(v))
"#
);
pub const LOCATE: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local chosen
for name in fs.dir('/sys/class/leds')do if name:match('^[%w_:.-]+$') and (name:find('green',1,true) or name:find('blue',1,true)) then chosen=name;break end end
assert(chosen,'No supported indicator found');local base='/sys/class/leds/'..chosen;local trigger=read(base..'/trigger',1024) or '';local original=trigger:match('%[([^%]]+)%]') or 'none';local bright=tonumber(trim(read(base..'/brightness',32))) or 0
assert(original:match('^[%w_-]+$'));local command="(echo none > "..base.."/trigger; i=0; while [ $i -lt 15 ]; do echo 1 > "..base.."/brightness; sleep 1; echo 0 > "..base.."/brightness; sleep 1; i=$((i+1)); done; echo "..bright.." > "..base.."/brightness; echo "..original.." > "..base.."/trigger) </dev/null >/dev/null 2>&1 &"
assert(os.execute(command)==0);io.write(j.encode({seconds=30,led=chosen}))
"#
);
pub fn files(path: &str) -> String {
    format!(
        "{HELPERS}\nlocal p={};assert(fs.stat(p,'type')=='dir','Directory is not available');local out={{}};local truncated=false;for name in fs.dir(p)do if #out>=2000 then truncated=true;break end;local child=p:gsub('/+$','')..'/'..name;local s=fs.lstat(child);if s then out[#out+1]={{name=name,type=s.type,directory=fs.stat(child,'type')=='dir',size=s.size,mode=s.modestr,mtime=s.mtime}}end end;io.write(j.encode({{path=p,canonical=fs.realpath(p),writable=writable_dir(p),entries=out,truncated=truncated}}))",
        lua(path)
    )
}
pub fn read_file(path: &str) -> String {
    format!(
        "{HELPERS}\n{ENCODE64}\nlocal p={};assert(fs.stat(p,'type')=='reg','Choose a regular file or use Download');local canonical=assert(fs.realpath(p));assert(canonical:sub(1,5)~='/dev/','Device data cannot be opened as a text file');local data=assert(read(p,262145));assert(#data<=262144,'Text files must be 256 KiB or smaller');io.write(j.encode({{data=encode64(data),canonical=canonical,writable=writable_file(p)}}))", lua(path)
    )
}
pub fn write_file(path: &str, data: &str, token: &str) -> String {
    format!(
        "{HELPERS}\n{}\nlocal path={};assert(writable_file(path),'Editing through links outside user directories is not allowed');local data=decode64({});local tmp=path..'.deco-{}';assert(not fs.lstat(tmp));local old=fs.lstat(path);assert(not old or old.type=='reg');if old then assert(old.nlink==1);local f=assert(io.open(path,'rb'));local before=f:read(262145) or '';f:close();assert(#before<=262144);local b=assert(io.open(tmp..'.before','wb'));assert(b:write(before));assert(b:close());fs.chmod(tmp..'.before','0600')end;local f=assert(io.open(tmp,'wb'));assert(f:write(data));assert(f:close());assert(fs.chmod(tmp,old and old.modedec or '0600'));assert(read(tmp,262145)==data);assert(os.rename(tmp,path));io.write(j.encode({{saved=true,backup=old and tmp..'.before' or false}}))",
        include_str!("../assets/base64.lua"),
        lua(path),
        lua(&crate::b64(data.as_bytes())),
        token
    )
}

pub const SSH_HEALTH: &str = concat!(
    include_str!("identity-helper.lua"),
    r#"
local v={load=trim(read('/proc/loadavg',100)),uptime=tonumber((read('/proc/uptime',100) or ''):match('^[%d.]+')),entropy=tonumber(trim(read('/proc/sys/kernel/random/entropy_avail',32))),tcp_states={}}
for line in (read('/proc/net/tcp',65536) or ''):gmatch('[^\n]+')do local port,state=line:match('^%s*%d+:%s+[%x]+:([%x]+)%s+%S+%s+(%S+)');if port and tonumber(port,16)==22 then v.tcp_states[state]=(v.tcp_states[state] or 0)+1 end end
v.logs=run("logread | grep -iE 'dropbear|internetdeco-ssh' | tail -n 40",16384)
io.write(j.encode(v))
"#
);
