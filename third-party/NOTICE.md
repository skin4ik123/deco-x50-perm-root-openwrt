Terminal emulator and renderer: termux/termux-app v0.118.3,
commit 5b657c6adf4304e5198951ce815fe0205dcac29c.
https://github.com/termux/termux-app/tree/v0.118.3
The Java sources retain their original notices. See LICENSE.termux and ../LICENSE.

Dropbear: commit 28216cd9af822732a1549b78621c2ea9a76a0fe5.
https://github.com/mkj/dropbear/tree/28216cd9af822732a1549b78621c2ea9a76a0fe5
Complete upstream source archive, the two appliance patches, build options,
NAND helper source and component license files are included in dropbear/.
The password profile uses an independent root credential and does not change
/etc/passwd or /etc/shadow. The temporary profile accepts only the app's key.

JSch 2.28.0: https://github.com/mwiede/jsch (BSD-3-Clause).
Bouncy Castle 1.84: https://www.bouncycastle.org/about/license/ (MIT).
Rust dependencies are pinned in core/Cargo.lock. Each retains its own license.
Original Deco Control code is GPLv3; the combined Android application is GPLv3.

No owner credentials, device backups, private keys or signing materials are
part of this source distribution. Included fixture credentials are test-only.

Additional upstream license/notice texts are retained in licenses/: JSch,
JZlib/jBCrypt, Bouncy Castle, Apache-2.0 and cached Rust crates. The Rust
inventory covers Cargo.lock (including build and other-target entries); it is
not a claim that every listed crate ships in the APK. Android dependency
coordinates are pinned in Gradle/BOM metadata, with original embedded AndroidX
notices retained in the APK.
