#!/usr/bin/env python3
"""Negotiate with the actual OEM ARM64 SSH daemon, using synthetic credentials.
This checks the app's owner SSH policy, not a real account or root installation.
"""
import json,os,shutil,socket,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OEM=ROOT.parent/'firmware-x50-analysis/emulator/root';JRE=Path(shutil.which('java')).resolve().parents[1]
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
JARS=[next((CACHE/name/version).glob('*/*.jar'))for name,version in [('com.github.mwiede/jsch','2.28.0'),('org.bouncycastle/bcprov-jdk18on','1.84'),('org.jetbrains.kotlin/kotlin-stdlib','2.4.21')]]
POLICY=ROOT/'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
assert (POLICY/'com/internetdeco/decocontrol/SshPolicy.class').exists(),'Run Android compilation first'
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
with socket.socket()as probe:probe.bind(('127.0.0.1',9227))
with tempfile.TemporaryDirectory(prefix='deco-stock-ssh-')as temp:
 p=Path(temp);classes=p/'classes';classes.mkdir();env=dict(os.environ)
 subprocess.run([str(JRE/'bin/javac'),'-cp',':'.join(map(str,[*JARS,POLICY])),'-d',str(classes),str(ROOT/'tools/StockOwnerSshCheck.java')],check=True)
 script=r'''
import json,socket,subprocess,time
from pathlib import Path
Path('/etc/passwd').write_text('root:x:0:0:root:/root:/bin/ash\n');Path('/etc/group').write_text('root:x:0:\n');Path('/etc/shells').write_text('/bin/ash\n')
q=['/qemu','-L','/oem']
key=subprocess.run(q+['-0','dropbearkey','/server','-t','rsa','-s','2048','-f','/tmp/key'],capture_output=True,timeout=20)
assert key.returncode==0,key.stderr.decode(errors='replace')
with Path('/tmp/stock.log').open('wb')as log:
 server=subprocess.Popen(q+['-0','dropbear','/server','-F','-E','-r','/tmp/key','-p','127.0.0.1:9227','-P','/tmp/pid'],stdout=log,stderr=log)
 try:
  for _ in range(100):
   try:s=socket.create_connection(('127.0.0.1',9227),.1);s.close();break
   except OSError:time.sleep(.05)
  result=subprocess.run(['/jre/bin/java','-XX:ActiveProcessorCount=2','-Xmx128m','-cp','/classes:/policy:/jsch.jar:/bc.jar:/kotlin.jar','StockOwnerSshCheck'],capture_output=True,text=True,timeout=25)
  assert result.returncode==0,result.stderr+Path('/tmp/stock.log').read_text(errors='replace')
  print(result.stdout,end='')
 finally:
  server.terminate()
  try:server.wait(timeout=3)
  except subprocess.TimeoutExpired:server.kill();server.wait()
'''
 (p/'check.py').write_text(script)
 args=['bwrap','--unshare-all','--share-net','--die-with-parent','--new-session','--uid','0','--gid','0','--cap-drop','ALL','--clearenv','--setenv','PATH','/usr/sbin:/usr/bin:/sbin:/bin','--ro-bind','/usr','/usr','--dir','/bin','--ro-bind','/usr/bin/sh','/bin/sh','--ro-bind','/usr/bin/busybox','/bin/ash','--symlink','usr/lib','/lib','--symlink','usr/lib64','/lib64','--proc','/proc','--dev','/dev','--tmpfs','/tmp','--tmpfs','/etc','--dir','/root']
 for src,dst in [(OEM/'lib','/oem/lib'),(OEM/'usr/lib','/oem/usr/lib'),(OEM/'usr/sbin/dropbear','/server'),(OEM/'usr/bin/qemu-aarch64','/qemu'),(JRE,'/jre'),(classes,'/classes'),(POLICY,'/policy'),(p/'check.py','/check.py'),(JARS[0],'/jsch.jar'),(JARS[1],'/bc.jar'),(JARS[2],'/kotlin.jar')]:args+=['--ro-bind',str(src),dst]
 done=subprocess.run(args+['/usr/bin/python3','-I','/check.py'],capture_output=True,text=True,timeout=65)
 assert done.returncode==0,done.stderr
 report=json.loads(done.stdout);(ROOT/'verification/stock-ssh.json').write_text(json.dumps(report,indent=2)+'\n');print(done.stdout,end='')
