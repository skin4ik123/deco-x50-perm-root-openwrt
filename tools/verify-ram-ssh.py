#!/usr/bin/env python3
"""Exact original OpenWrt ARM64 Dropbear and APK SSH policy, with synthetic root.

This is a loopback protocol test, not an OpenWrt hardware boot test. Only the
pinned public ITB supplies the guest daemon and musl libraries. The child shell
is the host BusyBox inside an unprivileged root user namespace. No owner router,
credentials, NAND images, firmware files, or persistent services are accessed.
"""

import hashlib
import json
import lzma
import os
import shutil
import socket
import stat
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT.parent
ITB = BASE / "openwrt-research/openwrt-qualcommax-ipq50xx-tplink_deco-x50-v1-initramfs-uImage.itb"
ITB_SIZE = 13056916
ITB_SHA256 = "ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2"
IMAGE_SHA256 = "6c4d0afb6d43dfeeaef6506b5c6148e4102e5a9d446a569d0f2488719c2ca187"
CPIO_SHA256 = "715f1630124ee1791a948c1a06715c6bb9e785caf5deae54eff7338e26820334"
POLICY = ROOT / "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
POLICY_CLASS = POLICY / "com/internetdeco/decocontrol/SshPolicy.class"
QEMU = BASE / "firmware-x50-analysis/emulator/root/usr/bin/qemu-aarch64"
JRE = Path(shutil.which("java")).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
DEPENDENCIES = [
    ("com.github.mwiede/jsch", "2.28.0", "/jsch.jar"),
    ("org.bouncycastle/bcprov-jdk18on", "1.84", "/bc.jar"),
    ("org.jetbrains.kotlin/kotlin-stdlib", "2.4.21", "/kotlin.jar"),
]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(65536), b""):
            digest.update(data)
    return digest.hexdigest()


def extract_runtime(work):
    """All offsets belong to this exact SHA-pinned FIT, never another image."""
    assert ITB.stat().st_size == ITB_SIZE and sha256(ITB) == ITB_SHA256
    image = work / "Image"
    decoder = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE, memlimit=32 * 1024 * 1024)
    with ITB.open("rb") as source, image.open("xb") as target:
        source.seek(232)  # /images/kernel-1/data in this exact FIT
        remaining = 13030466
        written = 0
        while remaining:
            data = source.read(min(65536, remaining))
            assert data, "Truncated pinned FIT"
            remaining -= len(data)
            while True:
                output = decoder.decompress(data, max_length=65536)
                target.write(output)
                written += len(output)
                assert written <= 23291912, "Unexpected Image file size"
                if decoder.eof or decoder.needs_input:
                    break
                data = b""
            if decoder.eof:
                assert not remaining and not decoder.unused_data
        assert decoder.eof and written == 23291912
    assert sha256(image) == IMAGE_SHA256
    archive = work / "initramfs.cpio"
    compressed = work / "initramfs.zst"
    with image.open("rb") as source, compressed.open("xb") as target:
        source.seek(13304952)
        remaining = 8495955
        while remaining:
            data = source.read(min(65536, remaining))
            assert data, "Truncated original initramfs"
            remaining -= len(data)
            target.write(data)
    with archive.open("xb") as target:
        subprocess.run(["zstd", "--quiet", "--decompress", "--stdout", "--memory=32MB",
                        str(compressed)], stdout=target, check=True, timeout=30)
    assert archive.stat().st_size == 26137088 and sha256(archive) == CPIO_SHA256
    wanted = {
        "usr/sbin/dropbear", "lib/libc.so", "lib/libgcc_s.so.1",
        "lib/ld-musl-aarch64.so.1", "etc/init.d/dropbear", "etc/shadow",
    }
    runtime = work / "ram"
    (runtime / "lib").mkdir(parents=True)
    (runtime / "usr/lib").mkdir(parents=True)
    evidence = {}
    with archive.open("rb") as source:
        while True:
            header = source.read(110)
            assert len(header) == 110 and header[:6] == b"070701", "Unexpected CPIO"
            fields = [int(header[6 + i * 8:14 + i * 8], 16) for i in range(13)]
            length, namesize, mode = fields[6], fields[11], fields[1]
            assert 0 < namesize <= 4096
            name = source.read(namesize).rstrip(b"\0").decode()
            source.read(-(110 + namesize) % 4)
            if name == "TRAILER!!!":
                break
            if name in wanted:
                assert length <= 1024 * 1024
                data = source.read(length)
                assert len(data) == length
                evidence[name] = {"sha256": hashlib.sha256(data).hexdigest(), "size": length}
                if name == "etc/shadow":
                    evidence[name] = {"original_root_password_empty": any(
                        line.startswith("root::") for line in data.decode().splitlines())}
                elif name == "etc/init.d/dropbear":
                    evidence[name]["literal_blank_password_flag_in_init"] = "-B" in data.decode()
                else:
                    path = runtime / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    if stat.S_ISLNK(mode):
                        link = data.rstrip(b"\0").decode()
                        assert name == "lib/ld-musl-aarch64.so.1" and link == "libc.so"
                        path.symlink_to(link)
                    else:
                        assert stat.S_ISREG(mode)
                        path.write_bytes(data)
                        path.chmod(0o755)
            else:
                source.seek(length, 1)
            source.read(-length % 4)
    assert set(evidence) == wanted, "Missing original runtime evidence"
    assert evidence["etc/shadow"]["original_root_password_empty"]
    image.unlink()
    archive.unlink()
    compressed.unlink()
    return runtime, evidence


GUEST = r'''
import base64,json,socket,subprocess,sys,time
from pathlib import Path
port=int(sys.argv[1])
Path('/etc/passwd').write_text('root:x:0:0:root:/root:/bin/ash\n')
Path('/etc/shadow').write_text('root::0:0:99999:7:::\n');Path('/etc/shadow').chmod(0o600)
Path('/etc/group').write_text('root:x:0:\n')
Path('/etc/shells').write_text('/bin/ash\n')
q=['/qemu','-L','/ram']
subprocess.run(q+['-0','dropbearkey','/server','-t','ed25519','-f','/tmp/host_key'],check=True,capture_output=True,timeout=15)
public=subprocess.run(q+['-0','dropbearkey','/server','-y','-f','/tmp/host_key'],check=True,capture_output=True,text=True,timeout=10)
key=next(line.split()[1] for line in public.stdout.splitlines() if line.startswith('ssh-ed25519 '))
assert len(base64.b64decode(key,validate=True))<1024
Path('/tmp/host_key.pub').write_text(key)
report={}
for mode,extra in [('original_init_default',[]),('explicit_allow_blank',['-B'])]:
 with Path('/tmp/dropbear.log').open('wb') as log:
  server=subprocess.Popen(q+['-0','dropbear','/server','-F','-E','-r','/tmp/host_key','-p',f'127.0.0.1:{port}','-P','/tmp/pid']+extra,stdout=log,stderr=log)
  try:
   ready=False
   for _ in range(100):
    if server.poll() is not None:break
    try:
     with socket.create_connection(('127.0.0.1',port),.1) as connection:
      connection.settimeout(1);banner=connection.recv(128).split(b'\n',1)[0].decode('ascii').strip();ready=banner.startswith('SSH-2.0-')
     if ready:break
    except OSError:time.sleep(.025)
   assert ready,Path('/tmp/dropbear.log').read_text(errors='replace')[:4096]
   result=subprocess.run(['/jre/bin/java','-XX:ActiveProcessorCount=2','-Xmx128m','-cp','/classes:/policy:/jsch.jar:/bc.jar:/kotlin.jar','RamSshCheck',str(port),'/tmp/host_key.pub'],capture_output=True,text=True,timeout=60)
   assert result.returncode==0,result.stderr+Path('/tmp/dropbear.log').read_text(errors='replace')[-4096:]
   report[mode]=json.loads(result.stdout)
   report[mode]['server_banner']=banner
   report[mode]['server_explicit_blank_flag']=bool(extra)
  finally:
   server.terminate()
   try:server.wait(timeout=3)
   except subprocess.TimeoutExpired:server.kill();server.wait(timeout=3)
assert report['explicit_allow_blank']['authenticated'], 'Original Dropbear -B did not accept synthetic empty root'
print(json.dumps(report))
'''


def main():
    assert POLICY_CLASS.is_file(), "Run Android compilation before this verification"
    assert shutil.which("bwrap") and QEMU.is_file()
    os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:2])
    # Stay in the assigned local development range; never bind a public address.
    port = 9225
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", port))
    jars = [(next((CACHE / name / version).glob("*/*.jar")), destination)
            for name, version, destination in DEPENDENCIES]
    with tempfile.TemporaryDirectory(prefix="deco-ram-ssh-", dir=BASE) as directory:
        work = Path(directory)
        runtime, evidence = extract_runtime(work)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([
            str(JRE / "bin/javac"), "-J-XX:ActiveProcessorCount=2", "-J-Xmx128m",
            "-cp", ":".join(map(str, [*(path for path, _ in jars), POLICY])),
            "-d", str(classes), str(ROOT / "tools/RamSshCheck.java"),
        ], check=True, timeout=30)
        script = work / "check.py"
        script.write_text(GUEST)
        args = [
            "bwrap", "--unshare-all", "--share-net", "--die-with-parent", "--new-session",
            "--uid", "0", "--gid", "0", "--cap-drop", "ALL", "--clearenv",
            "--setenv", "PATH", "/usr/sbin:/usr/bin:/sbin:/bin",
            "--ro-bind", "/usr", "/usr", "--dir", "/bin",
            "--ro-bind", "/usr/bin/sh", "/bin/sh",
            "--ro-bind", "/usr/bin/busybox", "/bin/ash",
            "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
            "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/etc",
            "--dir", "/root",
        ]
        bindings = [
            (runtime, "/ram"), (runtime / "usr/sbin/dropbear", "/server"),
            (QEMU, "/qemu"), (JRE, "/jre"), (classes, "/classes"),
            (POLICY, "/policy"), (script, "/check.py"), *jars,
        ]
        for source, destination in bindings:
            args += ["--ro-bind", str(source), destination]
        done = subprocess.run(args + ["/usr/bin/python3", "-I", "/check.py", str(port)],
                              capture_output=True, text=True, timeout=150)
        assert done.returncode == 0, done.stderr[-8192:]
        report = {
            "scope": "QEMU user-mode ARM64 OpenWrt Dropbear in an unprivileged root user namespace; host BusyBox child shell",
            "real_openwrt_hardware_boot_tested": False,
            "android_runtime_ui_tested": False,
            "owner_router_contacted": False,
            "synthetic_accounts_only": True,
            "loopback_address": f"127.0.0.1:{port}",
            "original_itb_sha256": ITB_SHA256,
            "original_image_sha256": IMAGE_SHA256,
            "original_initramfs_sha256": CPIO_SHA256,
            "runtime_extracted_from_pinned_itb_during_check": True,
            "original_runtime_files": evidence,
            "production_ssh_policy": True,
            "owner_policy": False,
            "policy_source_sha256": sha256(ROOT / "app/src/main/java/com/internetdeco/decocontrol/SshPolicy.kt"),
            "compiled_policy_sha256": sha256(POLICY_CLASS),
            "jsch_version": "2.28.0",
            "qemu_sha256": sha256(QEMU),
            "checks": json.loads(done.stdout),
        }
        output = ROOT / "verification/ram-ssh.json"
        output.parent.mkdir(exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
