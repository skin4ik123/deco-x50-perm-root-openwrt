# v1.1.1 — Deco Control public preview

Android ARM64 app for the reviewed ordinary Deco X50 V1 EU stock profile.

- Persistent local root SSH with independent credentials, compatibility checks and private backups.
- Saved device profiles/passwords, connection progress and bounded transport retries, terminal, files and diagnostics.
- Guided one-time OpenWrt RAM boot through a Windows Ethernet/TFTP server, with pinned original image download and post-boot device/operation verification.
- Separate classic RTL8367S Ethernet repair, including the required second VLAN-configuration step, isolated tests and complete corresponding GPL sources.
- English app UI, English README and a detailed Russian walkthrough; SHA-256 for every release asset.

**Installing permanent OpenWrt from the running RAM system is possible and owner-confirmed on X50 V1.** It is a separate step using a suitable image; this app wizard boots RAM and does not automatically flash permanent firmware.

Use only the original image pinned in the README. The app does not accept an NSS or modified image in its place. Switch repair applies only to the stated classic-switch/kernel combination and is not NSS acceleration.

The existing signed APK is preserved. Publication changes are documentation, portable build-helper paths/localoptions handling and switch-source packaging. Runtime code, assets and JNI are unchanged. APK build records: 44 Rust tests, 83 JVM tests, signature and 16KiB alignment; publication checks include 8 isolated Ethernet tests. The full 1.1.1 wizard still needs a physical-phone end-to-end check. Earlier root, TFTP RAM boot, Ethernet access and separate permanent installation were owner-confirmed.

RAM boot writes the single-copy boot environment before rebooting. Keep power stable and private backups/journals. Read README.ru.md/README.md before starting; do not replay an operation whose outcome is unknown.

The app, source snapshot, Ethernet upload ZIP, full GPL source ZIP and walkthrough are attached below. Download the pinned RAM firmware from its upstream author using the link in OPENWRT_IMAGE.txt or the app. No signing keys, owner data or NAND backups are published.
