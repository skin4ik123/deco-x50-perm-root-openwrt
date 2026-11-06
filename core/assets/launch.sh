#!/bin/sh
# Sourceable one-shot boot diagnostic. Only a private RAM receipt is written.
# All settings and failures stay in the subshell; the include returns success.
(
    PATH=/usr/sbin:/usr/bin:/sbin:/bin
    export PATH
    umask 077

    idm_probe_base=/tp_data/user_data/internetdeco-include-probe
    [ ! -L "$idm_probe_base" ] || exit 0
    [ -d "$idm_probe_base" ] || exit 0
    [ ! -L "$idm_probe_base/ENABLE" ] || exit 0
    [ -f "$idm_probe_base/ENABLE" ] || exit 0
    read -r idm_probe_enabled < "$idm_probe_base/ENABLE" || exit 0
    [ "$idm_probe_enabled" = enabled ] || exit 0
    idm_probe_uid=$(id -u) || exit 0
    [ "$idm_probe_uid" = 0 ] || exit 0

    idm_probe_boot=$(cat /proc/sys/kernel/random/boot_id) || exit 0
    case "$idm_probe_boot" in ''|*[!0-9a-f-]*) exit 0 ;; esac
    [ "${#idm_probe_boot}" -eq 36 ] || exit 0
    idm_probe_ram="/tmp/internetdeco-include-probe.$idm_probe_boot"
    # Existing directories/symlinks are left untouched, including on reload.
    mkdir -m 700 "$idm_probe_ram" 2>/dev/null || exit 0
    read -r idm_probe_uptime idm_probe_unused < /proc/uptime || exit 0
    case "$idm_probe_uptime" in ''|*[!0-9.]*) exit 0 ;; esac
    printf '{"schema":1,"kind":"include-probe","uid":0,"boot_id":"%s","uptime_seconds":"%s"}\n' \
        "$idm_probe_boot" "$idm_probe_uptime" > "$idm_probe_ram/receipt.tmp" || exit 0
    mv "$idm_probe_ram/receipt.tmp" "$idm_probe_ram/receipt.json" || exit 0
)
# Never turn a diagnostic failure into a firewall-start failure.
:
