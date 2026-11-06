#!/usr/bin/env python3
"""Optional: rebuild generic appliance components from bundled upstream source.
Existing, verified binaries ship in core/assets; this does not touch any router.
Use one profile per invocation and no concurrent Android/native builds.
"""
import argparse,json,os,shutil,subprocess,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];HERE=ROOT/'third-party/dropbear'
a=argparse.ArgumentParser();a.add_argument('profile',choices=['password','temporary']);args=a.parse_args()
mem=next(int(x.split()[1])for x in Path('/proc/meminfo').read_text().splitlines()if x.startswith('MemAvailable:'))
assert mem>=1536*1024 and shutil.disk_usage(ROOT).free>=15*(1<<30),'Need 1.5 GiB RAM and 15 GiB disk free'
os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2]);os.nice(10)
work=ROOT/'build/components'/args.profile;work.mkdir(parents=True,exist_ok=False)
with tarfile.open(HERE/'upstream-28216cd.tar.gz')as archive:archive.extractall(work,filter='data')
source=next(work.glob('dropbear-*'))
subprocess.run(['patch','-p1','--input',str(HERE/(args.profile+'.patch' if args.profile=='temporary' else 'password-dropbear.patch'))],cwd=source,check=True)
meta=json.loads((HERE/(args.profile+'-BUILD.json')).read_text())
options=meta['localoptions']
if isinstance(options,str):
 header=options if options.endswith('\n') else options+'\n'
elif isinstance(options,dict):
 header=''.join(f'#define {k} {int(v)}\n' for k,v in options.items())
else:
 raise ValueError('Unsupported localoptions metadata')
(source/'localoptions.h').write_text(header)
zig=os.environ.get('ZIG',shutil.which('zig') or 'zig');env=dict(os.environ,CC=zig+' cc -target aarch64-linux-musl',AR=zig+' ar',RANLIB=zig+' ranlib',CFLAGS='-Os -fstack-protector-strong -ffunction-sections -fdata-sections',LDFLAGS='-static -s -Wl,--gc-sections',MAKEFLAGS='-j2')
subprocess.run(['./configure','--host=aarch64-linux-musl','--enable-static','--disable-zlib','--disable-syslog','--disable-utmp','--disable-utmpx','--disable-wtmp','--disable-wtmpx','--disable-lastlog'],cwd=source,env=env,check=True)
subprocess.run(['make','-j2','PROGRAMS=dropbear dropbearkey','MULTI=1'],cwd=source,env=env,check=True)
if args.profile=='password':
 subprocess.run([zig,'cc','-target','aarch64-linux-musl','-std=c11','-Os','-static','-s','-ffunction-sections','-fdata-sections','-fstack-protector-strong','-Wl,--gc-sections','-Wall','-Wextra','-Werror','-I.','-Isrc','-Ilibtomcrypt/src/headers',str(HERE/'nand-reader.c'),'libtomcrypt/libtomcrypt.a','-o','nand-reader'],cwd=source,check=True)
print('Built generic components in',source)
