# Deco Control 1.1.1

Android 8+ / ARM64. Kotlin and Jetpack Compose interface, Rust engine through JNI,
JSch SSH channels, Android Keystore encryption, a real remote PTY terminal.
There is no Termux or Python dependency on the phone.

Implemented: device profiles and switching, independent SSH sessions/terminals,
password root provisioning for the reviewed stock Deco X50 V1 firmware,
configuration and streamed NAND DATA backups, monitoring and graphs, Wi-Fi
clients, processes/services, log search, file browser/editor and transfers,
SSH phase timing, password changes, boot checks, explicit stock reboot and a
guided OpenWrt RAM boot through a wired Windows TFTP server.

Installation is one node at a time. The root workflow checks source fingerprints,
backs up configuration to the phone, restores the temporary web index before
running a command, preserves traffic counters and records uncertain operations.
Root installation does not automatically reboot. The separate RAM-boot action
explicitly confirms and requests a reboot. M4R and permanent firmware flashing
are not implemented in this release.

The installation profile is deliberately restricted to ordinary X50 V1,
aarch64/IPQ5018, stock 4.4.60 and the exact reviewed OEM Lua fingerprints.
Other firmware revisions are stopped before configuration changes.
Backups stay in private app storage until the owner explicitly exports them.
NAND copies cover visible DATA, not OOB; restoration is not tested by the app.

## 1.1.1 OpenWrt RAM boot

Open **Tools → OpenWrt RAM boot**, or the OpenWrt card in a connected device's
Overview. Devices remain user-created; no personal router addresses are seeded.
The wizard uses the physically demonstrated U-Boot/TFTP route instead of kexec.

1. Connect a Windows laptop directly to the selected X50 with Ethernet. Configure
   its adapter as 192.168.1.2/24 (or another 192.168.1.2–254 address) and run a
   TFTP server on UDP 69 with its DHCP server disabled. The phone uses stock Wi-Fi.
2. Download the pinned original RAM image in the app and share/save it for the
   laptop, or copy the exact download link to Windows. The app streams the image,
   checks its complete SHA-256 and length, and publishes the cache atomically.
3. **Check compatibility and back up** reads the router only. It verifies root,
   platform, actual APPSBL and fw_setenv hashes, MTD geometry and environment CRC,
   then saves a private boot environment snapshot on the phone.
4. **Boot OpenWrt in RAM** confirms this specific device and prepared operation.
   It rechecks the original state, writes and reads back the one-time command,
   and requests a stock reboot. The bootloader restores the original boot policy
   before Ethernet/TFTP, then checks file length, CRC and FIT hashes before bootm.
5. Join the RAM image's **X50-OpenWrt** Wi-Fi on the phone and tap **Verify OpenWrt
   over SSH**. A separate empty-password RAM connection checks root UID, board,
   kernel, new boot ID and this operation's random ID in the RAM command line.
   This prevents another X50 with the same Wi-Fi name/address from being accepted
   as this boot. The RAM terminal and LuCI link are
   available after verification. Stock passwords and SSH key pins are retained.

The experimental upstream image is pinned to Oein's `deco-x50-v1-r37324.2`
release, filename `openwrt-qualcommax-ipq50xx-tplink_deco-x50-v1-initramfs-uImage.itb`,
13,056,916 bytes, SHA-256
`ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2`.
It is downloaded from its author, not bundled or modified in the APK. Source and
hardware details: <https://github.com/Oein/Deco-X50-Firmware-Unpack/releases/tag/deco-x50-v1-r37324.2>.
Networking differs between X50 hardware variants; this profile cannot promise
LAN DHCP or Wi-Fi operation on every board. The image uses open Wi-Fi and an
empty root password by default. Its top/middle Ethernet jacks are intended for
LAN, bottom for WAN. If DHCP is unavailable, the laptop can keep its static /24
address and reach 192.168.1.1.

RAM boot preserves the installed firmware but **does write the single-copy NAND
boot environment**, which is not power-fail atomic. Backups/journals are private,
fsynced before writes, and exportable by the owner. Unknown writer/reboot outcomes
never replay Start; the wizard offers read-only stock reconciliation or an
explicit guarded restore of only the owned boot policy. A prepared operation can
be cancelled without an environment write. After stock returns, its original
boot policy and a different stock boot ID are verified before a new operation.
Process restart restores the wizard from the per-device journal. A RAM SSH
connection and its new key remain provisional until the device and operation ID
are checked; a failed check cannot open a terminal or replace a saved stock key.
An SSH disconnect is never labelled successful OpenWrt boot.

The TFTP route and original image boot were confirmed by the owner on a real
X50. This Android integration still requires the owner's physical phone test.
It does not invoke sysupgrade, raw firmware flashing or GitHub publication.

## 1.0.3 root setup entry

New devices show **Set up root** as the primary action on the Devices and
Overview screens. Adding a device opens its Overview with that action visible.
Existing SSH access has a separate **Already have root? Sign in** action. The
SSH sign-in form also links directly to root setup. Unchecked access is shown
as unknown; a failed SSH connection does not mean root is absent. Root setup
opens the existing confirmed installation workflow; opening it sends no writes.
The native engine and installation checks are unchanged from 1.0.2.

## 1.0.2 changes

- Saved passwords use the existing Android Keystore alias and AES-GCM format.
  Verified passwords are saved after authentication; reconnect with one tap.
  An unreadable vault is preserved instead of generating a replacement key.
- Connection status shows Wi-Fi routing, TCP, SSH banner, key verification,
  authentication, elapsed time, attempt count and cancellation. The default is
  a 90-second SSH handshake and up to three transport attempts. Settings allow
  30–120 seconds and 1–3 attempts. Authentication/key errors are not retried.
  Commands, uploads and writes are never automatically replayed.
- Existing SSH sessions are reused; idle sessions receive a bounded health
  probe before reuse. Every device keeps its own connection and saved password.
- The phone terminal has live session status, reconnect/sign-in, full screen,
  keyboard controls, pinch zoom, saved font size, terminal-aware special keys,
  transcript selection and independently retained scrollback. It resizes the
  remote PTY when the viewport or keyboard changes.
- File browser has per-device directory history, Android Back navigation,
  search/sorting, custom paths and file names preserved on upload. Text editing
  warns about unsaved changes and keeps the draft across Activity recreation or a failed save. Read-only
  locations and transfer errors are explicit. New folder requests supersede
  stale responses. Downloads are cancellable and use unique names for simultaneous device transfers.
- Top-anchored scrollable forms consume IME/system insets once and keep actions
  visible. The terminal gets extra space while the keyboard is open.
- File writes check canonical parent paths and reject symlink/hardlink escapes.
  Uploads publish without overwriting an existing file. Text writes retain a
  private backup and the original permissions.

The owner confirmed root provisioning from 1.0.1 on a real Deco. The new UI,
keyboard behavior, credential persistence across Android restarts, and longer
connection policy still need testing on the owner's physical phone and Wi-Fi.
Isolated tests do not establish the cause of the live Dropbear connection delays.
The installation profile and shipped SSH server binaries are unchanged.

## Build

JDK 21, Gradle 9.8.2, Android SDK 37 + build tools 36.0.0, NDK
30.0.16248370, Rust with the aarch64-linux-android target. Versions of Android
and Rust dependencies are pinned in Gradle files and Cargo.lock.
Set ANDROID_HOME/ANDROID_NDK_HOME for your local toolchain and local.properties
with sdk.dir if necessary. Run tools/build-core.sh, then
`python3 tools/build-release.py`. Release signing requires an existing keystore
via ID_SIGNING_STORE_FILE, ID_SIGNING_KEY_ALIAS, and either ID_SIGNING_PASSWORD
or ID_SIGNING_PASSWORD_FILE. No debug-signature fallback exists.

Builds are restricted to two CPUs/two workers with a 1536 MiB Gradle heap;
resource guards require 1.5 GiB available RAM and 15 GB available disk.

## Verification and limits

`cargo test --manifest-path core/Cargo.toml -j2`

`gradle --no-daemon :app:testDebugUnitTest :app:lintRelease`

Paparazzi screenshots cover dark/light interfaces, enlarged text, node list,
connection states and the RAM wizard. Rust RAM tests cover environment parsing,
restore-first commands, write/readback/transport failures, durable operation
guards and reconciliation. JVM tests also cover bounded downloads, SHA-256,
redirect restrictions, cache publication and distinct stock/RAM session keys.
`tools/verify-ram-ssh.py` tests the exact ARM64 Dropbear and libraries extracted
from the pinned RAM image, using the app's compiled SSH policy and an empty
password. Both original-default and explicit `-B` modes passed authentication,
changed-key rejection, UID 0, binary I/O, PTY/resize and session reuse in user-mode
QEMU. The child shell is host BusyBox in an isolated namespace; this does not
constitute an Android phone or hardware boot test.
`tools/verify-jni.py` exercises the real JNI bridge under JVM CheckJNI.
`tools/verify-ssh-compatibility.py` tests the shipped ARM64 password server with
the actual JSch version, including PTY, binary data and session reuse. It needs
bubblewrap, QEMU, and a local extracted firmware runtime.
`tools/verify-oem-lua.py` executes production scripts with exact OEM ARM64 Lua
and nixio against synthetic proc/sys fixtures. Its runtime path can be adjusted
for another local firmware extraction. No real credentials are used in tests.

Reports in verification/ distinguish isolated verification from physical router
or Android device testing. The APK still requires owner testing on a real phone
and Deco. Timing in the isolated test does not establish the cause of Wi-Fi
connection failures on a live network.

Bundled component sources/patches and licenses are in third-party/.
`tools/build-components.py password` or `temporary` can rebuild those profiles
in a disposable build directory. This does not replace the app's pinned assets;
new component binaries require a reviewed manifest and integration checks.

1.1.1 fixes Android internal-directory permissions before reading RAM boot state. Existing journals and backups are preserved; no router commands are issued by this repair. The native file-open flags now use the target architecture constants. Verified with 44 Rust tests, 83 JVM tests, and 200 real JNI storage repairs/status reads.
