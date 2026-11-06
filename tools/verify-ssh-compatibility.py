#!/usr/bin/env python3
"""Real shipped ARM64 password server against the same JSch used in the APK."""
import ctypes,gzip,json,os,shutil,socket,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
QEMU=ROOT.parent/'firmware-x50-analysis/emulator/root/usr/bin/qemu-aarch64'
JRE=Path(shutil.which('java')).resolve().parents[1]
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
JARS=[next((CACHE/name/version).glob('*/*.jar')) for name,version in [('com.github.mwiede/jsch','2.28.0'),('org.bouncycastle/bcprov-jdk18on','1.84')]]
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
with socket.socket() as probe:probe.bind(('127.0.0.1',9226))
with tempfile.TemporaryDirectory(prefix='deco-ssh-compat-') as tmp:
 p=Path(tmp);classes=p/'classes';classes.mkdir()
 subprocess.run([str(JRE/'bin/javac'),'-cp',':'.join(map(str,JARS)),'-d',str(classes),str(ROOT/'tools/SshCompatibilityCheck.java')],check=True)
 (p/'server').write_bytes(gzip.decompress((ROOT/'core/assets/password-server.gz').read_bytes()));(p/'server').chmod(0o755)
 lib=ctypes.CDLL('libcrypt.so.1');lib.crypt.argtypes=[ctypes.c_char_p,ctypes.c_char_p];lib.crypt.restype=ctypes.c_char_p
 (p/'hash').write_bytes(lib.crypt(b'test-only-password',b'$6$rounds=100000$abcdefghijklmnop$')+b'\n')
 script=r'''
import json,os,socket,subprocess,time
from pathlib import Path
base=Path('/tp_data/user_data/internetdeco-ssh');base.mkdir(mode=0o700)
f=base/'password.hash';f.write_bytes(Path('/hash').read_bytes());f.chmod(0o600)
Path('/etc/passwd').write_text('root:x:0:0:root:/root:/bin/ash\n');Path('/etc/group').write_text('root:x:0:\n');Path('/etc/shells').write_text('/bin/ash\n')
key=base/'host_key';subprocess.run(['/qemu','/server','dropbearkey','-t','ed25519','-f',str(key)],check=True,capture_output=True)
with Path('/tmp/dropbear.log').open('wb') as log:
 server=subprocess.Popen(['/qemu','/server','dropbear','-F','-r',str(key),'-p','127.0.0.1:9226','-P','/tmp/server.pid','-K','30','-I','900','-T','3'],stdout=log,stderr=log)
 try:
  for _ in range(100):
   try:s=socket.create_connection(('127.0.0.1',9226),.1);s.close();break
   except OSError:time.sleep(.025)
  result=subprocess.run(['/jre/bin/java','-XX:ActiveProcessorCount=2','-Xmx128m','-cp','/classes:/jsch.jar:/bc.jar','SshCompatibilityCheck'],capture_output=True,text=True,timeout=60)
  assert result.returncode==0,result.stderr+Path('/tmp/dropbear.log').read_text()
  print(result.stdout,end='')
 finally:
  server.terminate()
  try:server.wait(timeout=3)
  except subprocess.TimeoutExpired:server.kill();server.wait()
'''
 (p/'check.py').write_text(script)
 args=['bwrap','--unshare-all','--share-net','--die-with-parent','--new-session','--uid','0','--gid','0','--cap-drop','ALL','--clearenv','--setenv','PATH','/usr/sbin:/usr/bin:/sbin:/bin','--ro-bind','/usr','/usr','--dir','/bin','--ro-bind','/usr/bin/sh','/bin/sh','--ro-bind','/usr/bin/busybox','/bin/ash','--symlink','usr/sbin','/sbin','--symlink','usr/lib','/lib','--symlink','usr/lib64','/lib64','--proc','/proc','--dev','/dev','--tmpfs','/tmp','--tmpfs','/etc','--dir','/root','--dir','/tp_data','--tmpfs','/tp_data/user_data']
 for source,target in [(JRE,'/jre'),(classes,'/classes'),(p/'server','/server'),(p/'hash','/hash'),(p/'check.py','/check.py'),(QEMU,'/qemu'),(JARS[0],'/jsch.jar'),(JARS[1],'/bc.jar')]:args+=['--ro-bind',str(source),target]
 done=subprocess.run(args+['/usr/bin/python3','-I','/check.py'],capture_output=True,text=True,timeout=80)
 assert done.returncode==0,done.stderr
 report=json.loads(done.stdout);(ROOT/'verification/ssh-compatibility.json').write_text(json.dumps(report,indent=2)+'\n');print(done.stdout,end='')
