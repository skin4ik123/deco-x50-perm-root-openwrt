# Start only the owner's separate service; never block stock firewall startup.
(
    PATH=/usr/sbin:/usr/bin:/sbin:/bin
    export PATH
    umask 077
    idm_ssh_base=/tp_data/user_data/internetdeco-ssh
    [ "$(id -u)" = 0 ] || exit 0
    [ ! -L "$idm_ssh_base" ] && [ -d "$idm_ssh_base" ] || exit 0
    [ ! -L "$idm_ssh_base/ENABLE" ] && [ -f "$idm_ssh_base/ENABLE" ] || exit 0
    read -r idm_ssh_enabled < "$idm_ssh_base/ENABLE" || exit 0
    [ "$idm_ssh_enabled" = enabled ] || exit 0
    [ ! -L "$idm_ssh_base/internetdeco-ssh" ] || exit 0
    set -- $(sha1sum "$idm_ssh_base/internetdeco-ssh")
    [ "$1" = @SERVICE_SHA1@ ] || exit 0
    mkdir -m 700 /tmp/internetdeco-ssh-start 2>/dev/null || exit 0
    (
        /bin/sh /etc/rc.common "$idm_ssh_base/internetdeco-ssh" start
    ) </dev/null >/tmp/internetdeco-ssh-start/startup.log 2>&1 &
)
:
