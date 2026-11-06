# Security and scope

Use only devices you own or administer with permission. Support is restricted to the reviewed X50 V1 EU stock profile and exact firmware fingerprints. Stop on a compatibility refusal. Do not expose root SSH to the Internet.

Root configuration is persistent stock-data modification. The one-time RAM flow also writes a single-copy boot environment, although the OpenWrt image itself runs in RAM. Keep stable power and private backups. Uncertain operations must be reconciled, not replayed by deleting journals.

The original test firmware starts open Wi-Fi and an empty root password. Perform initial verification on an isolated connection, then secure it; the app's initial RAM verifier uses its original empty-password login.

Do not attach credentials, emails, private keys, calibration, NAND/bootenv/config backups or private operation journals to public Issues. Send model/revision, firmware build, app version, step and redacted error only. Never upload signing material. This repository includes public test fixtures but no owner credentials or device data.

Report a security problem privately to the repository owner through GitHub rather than posting an exploit with third-party credentials. Permanent installation from RAM has been owner-confirmed on X50 V1, but is a separate step outside this app wizard. Stock-root OTA/factory-reset persistence and universal recovery are not guaranteed.
