# Build and verify

The published APK is the existing reviewed Deco Control **1.1.1**, package `com.internetdeco.decocontrol`, versionCode6. This is not Mesh Manager. Runtime Rust/Kotlin/Java/Lua/assets and the packaged JNI are preserved from its audited source archive. Public build helpers remove private server paths and accept both bundled Dropbear localoptions formats.

## Toolchain

Tested: JDK21, Gradle9.8.2 (installed `gradle`; no wrapper is bundled), Android SDK37.0/build-tools36.0.0, NDK30.0.16248370, Rust1.99.1 with aarch64-linux-android. Component rebuilds additionally use Zig0.17.0. Linux helpers use bubblewrap, qemu-aarch64 and extracted OEM/OpenWrt runtimes when explicitly configured. Those firmware extractions, credentials, backups and signing keys are not in this repository.

Set `ANDROID_HOME` and `ANDROID_NDK_HOME` to your SDK/NDK. The app includes the exact reviewed ARM64 JNI and generic appliance components. Rebuild JNI if you change Rust:

```sh
export CARGO_BUILD_JOBS=2
bash tools/build-core.sh
gradle --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
gradle --stop
```

The debug build uses a local Android debug identity for development. Official release signing requires the existing release keystore. A release build without signing inputs fails; there is no debug-signing fallback for releases. Set `ID_SIGNING_STORE_FILE`, `ID_SIGNING_KEY_ALIAS` and `ID_SIGNING_PASSWORD` privately (or `ID_SIGNING_PASSWORD_FILE` for `tools/build-release.py`). Never commit any of these values or files. A build signed with another certificate cannot update the official APK in place.

`python3 tools/build-release.py` requires explicit signing inputs, applies resource checks and runs tests/lint/build. The official packager `tools/package-release.py` verifies the project's published release certificate and reports. It is not a generic packager for arbitrary community signing certificates.

## Tests

```sh
cargo test --manifest-path core/Cargo.toml --locked -j2
python3 -m unittest discover -s ethernet -p test_helper.py -q
busybox ash -n ethernet/InternetDeco_X50_Ethernet_RAM_v1.sh
```

The Ethernet tests use fake insmod/rmmod/swconfig and do not touch hardware. Optional scripts under tools/ exercise JNI, OEM Lua and SSH compatibility against explicitly supplied isolated runtimes. They do not establish real phone/network behavior. Avoid recording Paparazzi golden images when verifying an unchanged UI.

Optional appliance component builds (existing pinned app assets are not replaced automatically):

```sh
ZIG=/path/to/zig python3 tools/build-components.py temporary
ZIG=/path/to/zig python3 tools/build-components.py password
```

Both string and dictionary `localoptions` metadata are supported. The full upstream Dropbear sources, patches and license are in third-party/dropbear. Standalone Ethernet module builds need the complete corresponding-source release ZIP, not just the small upload ZIP. See its README and CROSS_COMPILE recipe. Do not rebuild or load this module for a different firmware/kernel merely on the basis of `uname -r`.

The original APK's test reports are retained in verification/ with builder-home paths redacted. New portable helper/config checks are recorded in publication/. No claim of deterministic reproduction of the signed APK bytes is made; signatures/build metadata/toolchain differences matter.
