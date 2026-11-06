#!/bin/sh
# One RAM-only RTL8367S trial on the reviewed OpenWrt kernel. GPL-2.0-only.
# This never installs firmware, edits UCI or restarts the network/Wi-Fi.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
umask 077
stage=arguments
candidate=/tmp/rtl8367b-familyc.ko
original=/lib/modules/6.18.55/rtl8367b.ko
marker=/tmp/internetdeco-ethernet-familyc-v1.attempted
new_sha=d02f5bf162eb2557b7f1c1b1e8c6f3909962874cc9ebc6abb64a35d73fcdc1b1
old_sha=feb426e2a72879113d2908a345fc59bd4dd5d3c788c4b464b5532a5d1e1f0cb4
stop() { printf 'ETHERNET_STOPPED=%s\n' "$stage" >&2; exit 1; }
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
bound() {
    for entry in /sys/bus/platform/drivers/rtl8367b/*; do
        [ -L "$entry" ] || continue
        [ "${entry##*/}" = module ] && continue
        return 0
    done
    return 1
}
status() {
    printf 'KERNEL='; uname -r
    if [ -d /sys/module/rtl8367b ]; then
        printf 'MODULE_STATE='; cat /sys/module/rtl8367b/initstate
        printf 'MODULE_REFS='; cat /sys/module/rtl8367b/refcnt
    else printf 'MODULE_STATE=absent\n'; fi
    if bound; then printf 'DRIVER_BOUND=1\n'; else printf 'DRIVER_BOUND=0\n'; fi
    swconfig list || true
    dmesg | grep -Ei 'rtl836|hsgmii|mdio|uniphy' | tail -n 30 || true
}
case "${1:---status}" in
    --status) status; exit 0 ;;
    --apply) [ "$#" = 1 ] || stop ;;
    *) printf 'Use --status or --apply\n' >&2; exit 2 ;;
esac
stage=platform
[ "$(id -u)" = 0 ] && [ "$(uname -r)" = 6.18.55 ] && [ "$(uname -m)" = aarch64 ] || stop
stage=required_tools
for tool in sha256sum cp wc cut tr mktemp rmmod insmod swconfig; do
    command -v "$tool" >/dev/null 2>&1 || stop
done
stage=previous_attempt
[ ! -e "$marker" ] && [ ! -L "$marker" ] || stop
stage=candidate
[ -f "$candidate" ] && [ ! -L "$candidate" ] || stop
[ "$(wc -c < "$candidate" | tr -d ' ')" = 32792 ] || stop
[ "$(hash "$candidate")" = "$new_sha" ] || stop
stage=original_module
[ -f "$original" ] && [ ! -L "$original" ] && [ "$(hash "$original")" = "$old_sha" ] || stop
stage=module_state
for module in rtl8367b rtl8366_smi swconfig; do
    [ "$(cat /sys/module/$module/initstate)" = live ] || stop
done
[ "$(cat /sys/module/rtl8367b/refcnt)" = 0 ] || stop
if [ -f /sys/module/rtl8367b/taint ]; then
    [ -z "$(cat /sys/module/rtl8367b/taint)" ] || stop
fi
stage=existing_switch
switches=$(swconfig list) || stop
[ -z "$switches" ] || stop
if bound; then stop; fi
stage=private_RAM_copies
work=$(mktemp -d /tmp/internetdeco-ethernet-familyc-XXXXXX) || stop
cp "$original" "$work/original.ko" || stop
cp "$candidate" "$work/candidate.ko" || stop
[ "$(hash "$work/original.ko")" = "$old_sha" ] || stop
[ "$(hash "$work/candidate.ko")" = "$new_sha" ] || stop
stage=record_attempt
(set -C; printf '%s\n' "$work" > "$marker") || stop
trap 'rc=$?; if [ "$rc" != 0 ]; then printf "ETHERNET_OUTCOME_UNCONFIRMED=%s; run --status, do not repeat --apply.\n" "$stage" >&2; fi' 0
stage=unload_failed_driver
rmmod rtl8367b || stop
[ ! -d /sys/module/rtl8367b ] || stop
stage=load_familyC_once
insmod "$work/candidate.ko" || stop
stage=verify_registration
[ "$(cat /sys/module/rtl8367b/initstate)" = live ] || stop
bound || stop
switches=$(swconfig list) || stop
case "$switches" in *switch0*) ;; *) stop ;; esac
printf 'FAMILYC_DRIVER_REGISTERED=1\n'
printf 'CPU_PORT=6; DT_AND_UCI_UNCHANGED=1; VLAN_APPLY_NOT_RUN=1\n'
printf 'The driver is registered; Ethernet traffic still needs checking.\n'
status
