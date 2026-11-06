#!/usr/bin/env python3
"""Local release build. Never prints or copies signing credentials."""
import os,shutil,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
mem=next(int(s.split()[1])for s in Path('/proc/meminfo').read_text().splitlines()if s.startswith('MemAvailable:'))
assert mem>=1536*1024,'Need 1.5 GiB available RAM'
assert shutil.disk_usage(ROOT).free>=15_000_000_000,'Need 15 GB free disk'
env=os.environ.copy();store=env.get('ID_SIGNING_STORE_FILE','')
assert store and Path(store).is_file(),'Set ID_SIGNING_STORE_FILE to your existing release keystore'
password=env.get('ID_SIGNING_PASSWORD')
if not password:
 password_file=Path(env.get('ID_SIGNING_PASSWORD_FILE',''))
 assert env.get('ID_SIGNING_PASSWORD_FILE') and password_file.is_file(),'Provide the release keystore password using the environment or password file'
 password=password_file.read_text().rstrip('\r\n')
env.update(ID_SIGNING_STORE_FILE=store,ID_SIGNING_KEY_ALIAS=env.get('ID_SIGNING_KEY_ALIAS','internetdeco'),ID_SIGNING_PASSWORD=password)
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
tasks=[':app:testDebugUnitTest',':app:lintRelease',':app:assembleRelease']
if env.get('ID_RECORD_SNAPSHOTS')=='1':tasks.insert(1,':app:recordPaparazziDebug')
command=['nice','-n','10','ionice','-c3','gradle','--no-daemon','--max-workers=2',*tasks]
try:subprocess.run(command,cwd=ROOT,env=env,check=True)
finally:env.pop('ID_SIGNING_PASSWORD',None);password=None
