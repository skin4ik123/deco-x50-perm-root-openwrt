#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import shutil
mem=next(int(s.split()[1])for s in Path('/proc/meminfo').read_text().splitlines()if s.startswith('MemAvailable:'))
assert mem>=1536*1024,'Need 1.5 GiB available RAM'
assert shutil.disk_usage('.').free>=15_000_000_000,'Need 15 GB free disk'
PY
export CARGO_BUILD_JOBS=2
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/30.0.16248370}}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
nice -n 10 ionice -c3 cargo build --manifest-path core/Cargo.toml --release --target aarch64-linux-android -j2
mkdir -p app/src/main/jniLibs/arm64-v8a
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o app/src/main/jniLibs/arm64-v8a/libdeco_core.so core/target/aarch64-linux-android/release/libdeco_core.so
