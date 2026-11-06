
local fs=require('nixio.fs');local j=require('luci.json')
local function read(p,max)local f=io.open(p,'rb');if not f then return nil end;local s=f:read(max or 65536) or '';f:close();return s end
local function run(c,max)local f=io.popen(c,'r');if not f then return '' end;local s=f:read(max or 65536) or '';f:close();return s end
local function trim(s)return (s or ''):match('^%s*(.-)%s*$')end
local function exists(p)return fs.stat(p)~=nil end
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
