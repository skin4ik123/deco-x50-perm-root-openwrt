#!/usr/bin/env python3
"""Execute production scripts with the exact stock ARM64 Lua/nixio in isolation.
Only synthetic proc/sys data and disposable fixture files are accessible.
"""
import json,os,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT.parent/'openwrt-research/nixio-compat/root'
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
with tempfile.TemporaryDirectory(prefix='decocontrol-lua-') as temp:
 p=Path(temp);scripts=p/'scripts'
 subprocess.run(['cargo','run','-q','--manifest-path',str(ROOT/'core/Cargo.toml'),'--example','export_lua','--',str(scripts)],check=True,env=dict(os.environ,CARGO_BUILD_JOBS='2'))
 root=p/'root';shutil.copytree(RUNTIME,root,symlinks=True)
 for name in ('bin','proc','proc-real','sys','tmp','etc','tp_data/user_data'):(root/name).mkdir(parents=True,exist_ok=True)
 shutil.copyfile('/usr/bin/busybox',root/'bin/busybox');(root/'bin/busybox').chmod(0o755)
 for name in ('id','df','grep','tail'):(root/'bin'/name).symlink_to('busybox')
 for source,dest in [('/usr/bin/dash','bin/sh'),('/lib/x86_64-linux-gnu/libc.so.6','lib/x86_64-linux-gnu/libc.so.6'),('/lib64/ld-linux-x86-64.so.2','lib64/ld-linux-x86-64.so.2')]:
  f=root/dest;f.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,f);f.chmod(0o755)
 (root/'bin/uname').write_text('#!/bin/sh\ncase "$1" in -m) echo aarch64;; *) echo 4.4.60;; esac\n');(root/'bin/uname').chmod(0o755)
 (root/'bin/getfirm').write_text('#!/bin/sh\ncase "$1" in MODEL) echo "Deco X50";; HARDVERSION) echo 1.0;; esac\n');(root/'bin/getfirm').chmod(0o755)
 (root/'bin/logread').write_text('#!/bin/sh\necho "dropbear fixture: login ok"\n');(root/'bin/logread').chmod(0o755)
 def write(name,data):
  f=root/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(data if isinstance(data,bytes) else data.encode())
 for name,data in {
  'proc/sys/kernel/random/boot_id':'11111111-1111-4111-8111-111111111111\n',
  'proc/sys/kernel/osrelease':'4.4.60\n','proc/sys/kernel/hostname':'synthetic-X50\n',
  'sys/firmware/devicetree/base/compatible':b'qcom,ipq5018\0',
  'proc/uptime':'120.50 11.30\n','proc/loadavg':'0.10 0.15 0.20 1/25 90\n',
  'proc/stat':'cpu 100 0 80 900 10 0 0 0\n',
  'proc/meminfo':'MemTotal: 524288 kB\nMemAvailable: 262144 kB\n',
  'proc/net/dev':'Inter-| Receive | Transmit\n eth0: 12345 20 0 0 0 0 0 0 67890 30 1 0 0 0 0 0\n',
  'sys/class/net/eth0/operstate':'up\n','sys/class/net/eth0/address':'02:01:02:03:04:05\n',
  'sys/class/thermal/thermal_zone0/temp':'52500\n','sys/class/thermal/thermal_zone0/type':'fixture\n',
  'sys/class/thermal/thermal_zone1/temp':'74\n','sys/class/thermal/thermal_zone1/type':'tsens_tz_sensor1\n',
  'proc/1/status':'Name:\tprocd\nState:\tS (sleeping)\nVmRSS:\t512 kB\nThreads:\t1\n',
  'proc/net/tcp':' 0: 4E58A8C0:0016 00000000:0000 0A 00000000:00000000\n',
  'proc/sys/kernel/random/entropy_avail':'256\n',
 }.items():write(name,data)
 shutil.rmtree(root/'proc/self',ignore_errors=True)
 # OEM musl realpath resolves O_PATH descriptors through /proc/self/fd.
 # Keep synthetic /proc metrics and supply only the sandbox's own descriptor view.
 (root/'proc/self').symlink_to('/proc-real/self')
 fixture=p/'fixture';fixture.mkdir();(fixture/'value.txt').write_text('before\n');(fixture/'value.txt').chmod(0o640)
 def run(name,success=True):
  command=['bwrap','--unshare-all','--die-with-parent','--uid','0','--gid','0','--clearenv','--setenv','PATH','/bin:/usr/bin','--ro-bind',str(root),'/',
   '--dev','/dev','--proc','/proc-real','--tmpfs','/tmp','--bind',str(fixture),'/tmp/fixture','--ro-bind',str(scripts/(name+'.lua')),'/tmp/check.lua',
   '/usr/bin/qemu-aarch64','/usr/bin/lua','/tmp/check.lua']
  out=subprocess.run(command,capture_output=True,timeout=30)
  if not success:
   assert out.returncode!=0 and b'Editing through links outside user directories' in out.stderr,(name,out.stderr.decode(errors='replace')[-800:])
   return
  assert out.returncode==0,(name,out.stderr.decode(errors='replace')[-800:])
  return json.loads(out.stdout)
 identity=run('identity');assert identity['uid']==0 and identity['model']=='Deco X50' and identity['architecture']=='aarch64',identity
 metrics=run('monitor');assert metrics['mem']['MemAvailable']==268435456 and metrics['interfaces'][0]['rx']==12345 and metrics['temperatures'][0]['raw']==52500 and metrics['temperatures'][0]['unit']=='millicelsius'
 assert metrics['temperatures'][1]['raw']==74 and metrics['temperatures'][1]['unit']=='celsius'
 proc=run('processes');assert proc[0]['pid']==1 and proc[0]['rss_kib']==512
 health=run('ssh_health');assert health['tcp_states']['0A']==1
 clients=run('clients');assert clients in ({},[])
 files=run('files');assert files['entries'][0]['name']=='value.txt'
 assert files['writable'] is True and files['canonical']=='/tmp/fixture',files
 readback=run('read_file');assert readback['writable'] is True and readback['canonical']=='/tmp/fixture/value.txt'
 written=run('write_file');assert written['saved'] is True
 assert (fixture/'value.txt').read_text()=='after: Русский текст\n'
 assert (fixture/'value.txt').stat().st_mode & 0o777 == 0o640
 assert len(list(fixture.glob('*.before')))==1 and next(fixture.glob('*.before')).read_text()=='before\n'
 write('etc/protected/value.txt','protected fixture\n')
 (fixture/'outside').symlink_to('/etc/protected',target_is_directory=True)
 (fixture/'link.txt').symlink_to('/tmp/fixture/value.txt')
 os.link(fixture/'value.txt',fixture/'hard.txt')
 outside=run('read_outside');assert outside['writable'] is False
 for name in ('write_outside','write_link','write_hardlink'):run(name,success=False)
 assert (root/'etc/protected/value.txt').read_text()=='protected fixture\n'
 assert (fixture/'value.txt').read_text()=='after: Русский текст\n'
 assert (fixture/'value.txt').stat().st_mode & 0o777 == 0o640
 report={'kind':'exact-OEM-ARM64-Lua-nixio-production-script-check','scripts_passed':12,'write_parent_symlink_rejected':True,'write_final_symlink_rejected':True,'write_hardlink_rejected':True,'synthetic_fixtures_only':True,'real_router_tested':False}
 (ROOT/'verification').mkdir(exist_ok=True);(ROOT/'verification/oem-lua.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps(report))
