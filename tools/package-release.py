#!/usr/bin/env python3
"""Verify the signed APK and package only reviewable, non-private sources."""
import hashlib,json,os,re,shutil,subprocess,tempfile,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
VERSION=re.search(r'versionName\s*=\s*"([^"]+)"',(ROOT/'app/build.gradle.kts').read_text()).group(1)
SDK=Path(os.environ['ANDROID_HOME']);BUILD=SDK/'build-tools/36.0.0'
APK=ROOT/'app/build/outputs/apk/release/app-release.apk'
assert APK.is_file(),'Release APK is missing'
cert=subprocess.run([str(BUILD/'apksigner'),'verify','--verbose','--print-certs',str(APK)],capture_output=True,text=True,check=True).stdout
assert 'InternetDeco Release' in cert,'Unexpected signer'
subprocess.run([str(BUILD/'zipalign'),'-c','-P','16','4',str(APK)],check=True)
badging=subprocess.run([str(BUILD/'aapt2'),'dump','badging',str(APK)],capture_output=True,text=True,check=True).stdout
assert "name='com.internetdeco.decocontrol'" in badging and f"versionName='{VERSION}'" in badging
assert "minSdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging
assert 'application-debuggable' not in badging
assert "native-code: 'arm64-v8a'" in badging
lint=ROOT/'app/build/intermediates/lint_intermediate_text_report/release/lintReportRelease/lint-results-release.txt'
text=lint.read_text();assert not re.search(r'\bError:',text),'Lint errors remain'
ui_tests=0
for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 v=ET.parse(p).getroot();assert v.get('failures')=='0' and v.get('errors')=='0';ui_tests+=int(v.get('tests','0'))
assert ui_tests>=39
with zipfile.ZipFile(APK)as z:
 assert z.read('lib/arm64-v8a/libdeco_core.so')==(ROOT/'app/src/main/jniLibs/arm64-v8a/libdeco_core.so').read_bytes(),'Stale Rust engine in APK'
 assert not any(any(x in n.lower()for x in ['.jks','keystore','project_memory','backup.bak'])for n in z.namelist())
release=ROOT/'release';release.mkdir(exist_ok=True)
output=release/f'Deco-Control-{VERSION}-arm64.apk';shutil.copyfile(APK,output)
signer=re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',cert).group(1)
assert signer=='7a19db762989d9f2106df2c3e5f1a150ab80c0e00dddfbf2520a7f7161887c55','Stable signing certificate changed'
previous=Path(os.environ.get('ID_PREVIOUS_APK','__no_previous_apk__'))
if previous.is_file():
 old=subprocess.run([str(BUILD/'apksigner'),'verify','--print-certs',str(previous)],capture_output=True,text=True,check=True).stdout
 assert re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',old).group(1)==signer,'Signing key changed'
rust=json.loads((ROOT/'verification/rust.json').read_text());assert rust.get('version')==VERSION and rust.get('failed')==0,'Fresh Rust verification is required'
report={'version':VERSION,'application_id':'com.internetdeco.decocontrol','signed':True,'signer_sha256':signer,'apk_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'apk_bytes':output.stat().st_size,'release_tests_passed':ui_tests,'rust_tests_passed':rust['passed'],'lint_errors':0,'lint_warnings':int(re.search(r'(\d+) warnings',text).group(1)) if 'warnings' in text else 0,'page_alignment_16KiB':True,'fresh_rust_engine_verified':True,'previous_release_root_confirmed_by_owner':True,'original_tftp_ram_route_confirmed_by_owner':True,'this_release_real_android_phone_tested':False,'permanent_firmware_flashing':False,'live_wifi_ssh_timeout_cause_confirmed':False}
(ROOT/'verification/release.json').write_text(json.dumps(report,indent=2)+'\n')
source=release/f'Deco-Control-{VERSION}-sources.zip';excluded={'.gradle','.kotlin','build','target','release','.git','__pycache__','.publication-assets'}
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=6)as z:
 for directory,folders,files in os.walk(ROOT):
  folders[:]=sorted(n for n in folders if n not in excluded)
  for name in sorted(files):
   p=Path(directory)/name;rel=p.relative_to(ROOT)
   if name in {'PROJECT_MEMORY.json','local.properties','.env'} or name.startswith('.env.') or name.endswith(('.log','.jks','.keystore','.partial','.hprof','.pem','.key','.pyc')):continue
   assert not p.is_symlink(),f'Unexpected symlink {rel}'
   z.write(p,'Deco-Control/'+rel.as_posix())
preview=release/f'Deco-Control-{VERSION}-preview.png';shutil.copyfile(ROOT/'app/src/test/snapshots/images/com.internetdeco.decocontrol_ScreenshotsTest_openWrtWizard_openwrt-wizard.png',preview)
checks=[]
for p in [output,source,preview]:checks.append(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name)
(release/'SHA256SUMS.txt').write_text('\n'.join(checks)+'\n')
print(json.dumps(report,indent=2));print('Release artifacts:',output.name,source.name,preview.name)
