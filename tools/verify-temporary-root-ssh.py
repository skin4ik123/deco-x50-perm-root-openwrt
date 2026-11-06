#!/usr/bin/env python3
"""The shipped ARM64 temporary key server with the app's key storage sequence."""
import ctypes,gzip,json,os,shutil,socket,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
QEMU=ROOT.parent/'firmware-x50-analysis/emulator/root/usr/bin/qemu-aarch64'
JRE=Path(shutil.which('java')).resolve().parents[1]
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
JARS=[next((CACHE/name/version).glob('*/*.jar')) for name,version in [('com.github.mwiede/jsch','2.28.0'),('org.bouncycastle/bcprov-jdk18on','1.84')]]
KOTLIN=next((CACHE/'org.jetbrains.kotlin/kotlin-stdlib/2.4.21').glob('*/*.jar'))
PRODUCTION=ROOT/'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
assert (PRODUCTION/'com/internetdeco/decocontrol/SshClientKeys.class').exists(),'Compile the current Android sources first'
EXTRA=[KOTLIN,PRODUCTION]
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
with socket.socket() as probe:probe.bind(('127.0.0.1',9226))
with tempfile.TemporaryDirectory(prefix='deco-ssh-compat-') as tmp:
 p=Path(tmp);classes=p/'classes';classes.mkdir()
 subprocess.run([str(JRE/'bin/javac'),'-cp',':'.join(map(str,JARS+EXTRA)),'-d',str(classes),str(ROOT/'tools/TemporaryRootSshCheck.java')],check=True)
 (p/'server').write_bytes(gzip.decompress((ROOT/'core/assets/temporary-server.gz').read_bytes()));(p/'server').chmod(0o755)
 subprocess.run([str(JRE/'bin/java'),'-XX:ActiveProcessorCount=2','-Xmx128m','-cp',str(classes)+':'+':'.join(map(str,JARS+EXTRA)),'TemporaryRootSshCheck','prepare',str(p/'private-key'),str(p/'public-key')],check=True)
 script=r'''
import json,os,socket,subprocess,time
from pathlib import Path
base=Path('/tmp/auth');base.mkdir(mode=0o700)
f=base/'authorized_keys';f.write_bytes(Path('/public-key').read_bytes());f.chmod(0o600)
Path('/etc/passwd').write_text('root:x:0:0:root:/root:/bin/ash\n');Path('/etc/group').write_text('root:x:0:\n');Path('/etc/shells').write_text('/bin/ash\n')
key=base/'host_key';subprocess.run(['/qemu','/server','dropbearkey','-t','ed25519','-f',str(key)],check=True,capture_output=True)
with Path('/tmp/dropbear.log').open('wb') as log:
 server=subprocess.Popen(['/qemu','/server','dropbear','-F','-r',str(key),'-p','127.0.0.1:9226','-P','/tmp/server.pid','-D',str(base),'-K','30','-T','3'],stdout=log,stderr=log)
 try:
  for _ in range(100):
   try:s=socket.create_connection(('127.0.0.1',9226),.1);s.close();break
   except OSError:time.sleep(.025)
  result=subprocess.run(['/jre/bin/java','-XX:ActiveProcessorCount=2','-Xmx128m','-cp','/classes:/production:/kotlin.jar:/jsch.jar:/bc.jar','TemporaryRootSshCheck','check','/private-key'],capture_output=True,text=True,timeout=60)
  assert result.returncode==0,result.stderr+Path('/tmp/dropbear.log').read_text()
  print(result.stdout,end='')
 finally:
  server.terminate()
  try:server.wait(timeout=3)
  except subprocess.TimeoutExpired:server.kill();server.wait()
'''
 (p/'check.py').write_text(script)
 args=['bwrap','--unshare-all','--share-net','--die-with-parent','--new-session','--uid','0','--gid','0','--cap-drop','ALL','--clearenv','--setenv','PATH','/usr/sbin:/usr/bin:/sbin:/bin','--ro-bind','/usr','/usr','--dir','/bin','--ro-bind','/usr/bin/sh','/bin/sh','--ro-bind','/usr/bin/busybox','/bin/ash','--symlink','usr/sbin','/sbin','--symlink','usr/lib','/lib','--symlink','usr/lib64','/lib64','--proc','/proc','--dev','/dev','--tmpfs','/tmp','--tmpfs','/etc','--dir','/root','--dir','/tp_data','--tmpfs','/tp_data/user_data']
 for source,target in [(JRE,'/jre'),(classes,'/classes'),(p/'server','/server'),(p/'private-key','/private-key'),(p/'public-key','/public-key'),(p/'check.py','/check.py'),(QEMU,'/qemu'),(JARS[0],'/jsch.jar'),(JARS[1],'/bc.jar'),(KOTLIN,'/kotlin.jar'),(PRODUCTION,'/production')]:args+=['--ro-bind',str(source),target]
 done=subprocess.run(args+['/usr/bin/python3','-I','/check.py'],capture_output=True,text=True,timeout=80)
 assert done.returncode==0,done.stderr
 report=json.loads(done.stdout);(ROOT/'verification/temporary-root-ssh.json').write_text(json.dumps(report,indent=2)+'\n');print(done.stdout,end='')
