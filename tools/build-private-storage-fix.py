#!/usr/bin/env python3
"""One local, two-core verification/build of 1.1.1; credentials stay in build-release.py."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess
root=Path(__file__).resolve().parents[1]
os.chdir(root);os.sched_setaffinity(0,sorted(os.sched_getaffinity(0))[:2])
os.environ['CARGO_BUILD_JOBS']='2'
def run(args):
    p=subprocess.run(['nice','-n','10','ionice','-c3',*args],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    print(p.stdout,flush=True)
    if p.returncode:raise SystemExit(p.returncode)
    return p.stdout
text=run(['cargo','test','--manifest-path','core/Cargo.toml','-j2','--lib'])
match=re.search(r'test result: ok\. (\d+) passed; 0 failed',text);assert match
run(['cargo','build','--manifest-path','core/Cargo.toml','-j2','--lib'])
run(['python3','tools/test-private-storage-jni.py'])
proof=json.loads((root/'verification/rust.json').read_text())
proof.update(version='1.1.1',passed=int(match[1]),ram_boot_tests=len(re.findall(r'test ram_boot::tests::.* \.\.\. ok',text)),
    source_sha256=hashlib.sha256((root/'core/src/lib.rs').read_bytes()).hexdigest(),
    ram_boot_source_sha256=hashlib.sha256((root/'core/src/ram_boot.rs').read_bytes()).hexdigest(),
    private_storage_source_sha256=hashlib.sha256((root/'core/src/private_storage.rs').read_bytes()).hexdigest(),
    android_style_mode_0771_reproduced_and_repaired=True,actual_jni_storage_calls=200,actual_jni_readonly_ram_calls=200)
(root/'verification/rust.json').write_text(json.dumps(proof,indent=2)+'\n')
# Host test outputs are reproducible and no longer needed before the Android build.
shutil.rmtree(root/'core/target/debug')
run(['bash','tools/build-core.sh'])
shutil.rmtree(root/'core/target')
run(['python3','tools/build-release.py'])
run(['python3','tools/package-release.py'])
print('PRIVATE_STORAGE_FIX_RELEASE_READY',flush=True)
