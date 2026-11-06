Verification uses synthetic fixtures and an isolated filesystem/network namespace.
The reported SSH timings come from local QEMU execution, not the owner's Wi-Fi.
The source Lua/nixio runtime is the actual ARM64 runtime extracted from the
matching OEM firmware. A physical phone or Deco was not remotely available.
Release metadata is written only after signature, APK content, JNI freshness,
16 KiB ZIP alignment, Android unit tests and zero lint errors are checked.

1.0.2 adds credential format/commit/cache regression tests, patient SSH retry and
cancellation tests, per-device file history and editor draft retention tests.
Canonical file-write containment is checked using the exact OEM Lua/nixio.
The new keyboard, rotation and Back navigation still require physical phone
verification; preview images and JVM tests do not constitute that verification.

1.0.3 changes the root-setup entry screens only. The 1.0.2 native engine was
verified byte-for-byte against its signed APK and reused. New root entry
screens are rendered in dark/light themes and with large text. Opening the
setup entry only opens the existing confirmation form.

1.1.0 adds the real Windows Ethernet/TFTP RAM workflow. Rust fixtures verify
environment CRC/geometry/fingerprints, durable backup and write-intent ordering,
unknown transport outcomes, no write replay, prepared cancellation, guarded
policy restore, stock reconciliation and operation-specific OpenWrt identity.
Android/JVM tests cover image hash/length/redirect boundaries, cancellation and
atomic cache publication, isolated stock/RAM session identities, honest progress
states and recovery entry after app interruption. The exact RAM image's ARM64
Dropbear and libraries passed empty-root authentication and command/PTY tests
with the compiled SSH policy, under QEMU user mode with a host BusyBox child
shell. See ram-ssh.json for that precise scope. The proven bootloader path was
reported successful by the owner; this APK's full physical flow remains untested.
No permanent firmware image write or GitHub publication is performed.
