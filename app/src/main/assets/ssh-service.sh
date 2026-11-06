#!/bin/sh /etc/rc.common
USE_PROCD=1
START=99
STOP=10

start_service() {
    local idm_base=/tp_data/user_data/internetdeco-ssh
    local idm_ram=/tmp/internetdeco-ssh
    local idm_enabled
    [ "$(id -u)" = 0 ] || return 1
    [ ! -L "$idm_base" ] && [ -d "$idm_base" ] || return 1
    [ ! -L "$idm_base/ENABLE" ] && [ -f "$idm_base/ENABLE" ] || return 1
    read -r idm_enabled < "$idm_base/ENABLE" || return 1
    [ "$idm_enabled" = enabled ] || return 1
    [ ! -L "$idm_base/dropbearmulti" ] && [ -f "$idm_base/dropbearmulti" ] || return 1
    [ ! -L "$idm_base/host_key" ] && [ -s "$idm_base/host_key" ] || return 1
    set -- $(sha1sum "$idm_base/dropbearmulti")
    [ "$1" = 18ed1bbb1fbd845eca6be5b979699c5c013ca519 ] || return 1
    [ ! -L "$idm_ram" ] || return 1
    if [ ! -d "$idm_ram" ]; then
        mkdir -m 700 "$idm_ram" || return 1
    fi
    [ ! -L "$idm_ram/dropbearmulti" ] && [ ! -L "$idm_ram/dropbearmulti.new" ] || return 1
    cp "$idm_base/dropbearmulti" "$idm_ram/dropbearmulti.new" || return 1
    chmod 700 "$idm_ram/dropbearmulti.new" || return 1
    set -- $(sha1sum "$idm_ram/dropbearmulti.new")
    [ "$1" = 18ed1bbb1fbd845eca6be5b979699c5c013ca519 ] || return 1
    mv "$idm_ram/dropbearmulti.new" "$idm_ram/dropbearmulti" || return 1
    procd_open_instance owner
    procd_set_param command "$idm_ram/dropbearmulti" dropbear -F \
        -p @TARGET@:22 -P "$idm_ram/server.pid" -r "$idm_base/host_key" \
        -K 30 -I 900 -T 3
    procd_set_param respawn 3600 10 12
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
