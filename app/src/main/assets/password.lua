-- CTX and decode64 are supplied by the reviewed, fixed-node phone installer.
-- Only our own persistent files and our own procd service can change.
local fs = require('nixio.fs')
local json = require('luci.json')
local base = '/tp_data/user_data/internetdeco-ssh'
local launch_path = '/tp_data/user_data/internetdeco-include-probe/launch.sh'
local stage, created, activated = 'guards', false, false
local boot_id, pid
local function need(value, code) if not value then error(code, 0) end return value end
local function read(path, limit)
    local f = need(io.open(path, 'rb'), 'read_open')
    local value = f:read((limit or 1048576) + 1) or ''; f:close()
    need(#value <= (limit or 1048576), 'read_limit'); return value
end
local function write(path, value, mode)
    need(not fs.lstat(path), 'path_exists')
    local f = need(io.open(path, 'wb'), 'write_open')
    need(f:write(value), 'write'); need(f:close(), 'close')
    need(fs.chmod(path, mode or '0600'), 'file_mode')
    need(read(path) == value, 'file_readback')
end
local function quote(value) return "'" .. value:gsub("'", "'\\''") .. "'" end
local function run(command) need(os.execute(command) == 0, 'command_failed') end
local function sha1(path)
    local f = need(io.popen('sha1sum ' .. quote(path), 'r'), 'hash_open')
    local value = f:read(1024) or ''; f:close()
    return need(value:match('^([0-9a-f]+)%s'), 'hash_read')
end
local function regular(path)
    local st = fs.lstat(path)
    need(st and st.type == 'reg' and st.uid == 0, 'file_identity')
end
local function set_enabled(value)
    regular(base .. '/ENABLE')
    need(not fs.lstat(base .. '/ENABLE.new'), 'enable_temp_exists')
    write(base .. '/ENABLE.new', value)
    need(os.rename(base .. '/ENABLE.new', base .. '/ENABLE'), 'enable_rename')
end
local function replace_launch(value)
    regular(launch_path)
    need(not fs.lstat(launch_path .. '.ssh-new'), 'launch_temp_exists')
    write(launch_path .. '.ssh-new', value)
    need(os.rename(launch_path .. '.ssh-new', launch_path), 'launch_rename')
end
local function service_info()
    local connection = need(require('ubus').connect(), 'ubus_connect')
    local services = need(connection:call('service', 'list', {name='internetdeco-ssh'}), 'service_list_failed')
    connection:close()
    need(type(services) == 'table', 'service_list_invalid')
    return services['internetdeco-ssh']
end
local function service_pid()
    local service = service_info()
    local instance = service and service.instances and service.instances.owner
    if instance and instance.running == true and type(instance.pid) == 'number' then
        return instance.pid
    end
end
local function stop_owned()
    run('/bin/sh /etc/rc.common ' .. quote(base .. '/internetdeco-ssh') .. ' stop')
end
local function execute()
    need(CTX.target == '@TARGET@', 'target')
    need(CTX.token:match('^[0-9a-f]+$') and #CTX.token == 32, 'token')
    need(CTX.work == '/tmp/internetdeco-include-' .. CTX.token, 'work')
    need(read('/proc/self/status', 131072):match('\nUid:%s+0%s+0%s+0%s+0%s*\n'), 'uid0')
    boot_id = read('/proc/sys/kernel/random/boot_id', 100):gsub('%s+$', '')
    need(boot_id == CTX.expected_boot_id, 'boot_changed')
    local cursor = require('luci.model.uci').cursor()
    need(cursor:get('firewall', 'internetdeco_probe') == 'include' and
        cursor:get('firewall', 'internetdeco_probe', 'path') == launch_path and
        cursor:get('firewall', 'internetdeco_probe', 'idm_probe_token') == CTX.include_owner_token,
        'include_ownership')
    if CTX.action == 'disable' then
        stage = 'disable_guards'
        need(read(base .. '/OWNER', 100) == CTX.owner_token .. '\n', 'owner_identity')
        need(read(launch_path, 16384) == CTX.new_launch, 'launch_changed')
        need(read(base .. '/probe-launch.before', 16384) == CTX.old_launch, 'backup_changed')
        need(read(base .. '/internetdeco-ssh', 16384) == CTX.service, 'service_changed')
        stage = 'disable'
        set_enabled('disabled\n'); stop_owned(); replace_launch(CTX.old_launch)
        run('sync'); stage = 'disabled'; return
    end
    need(CTX.action == 'install', 'action')
    regular(launch_path)
    need(read(launch_path, 16384) == CTX.old_launch, 'launch_changed')
    need(read('/tp_data/user_data/internetdeco-include-probe/ENABLE', 100) == 'enabled\n', 'probe_disabled')
    need(not fs.lstat(base), 'own_prefix_exists')
    need(not service_info(), 'service_already_present')
    need(not fs.lstat('/tmp/internetdeco-ssh-start') and not fs.lstat('/tmp/internetdeco-ssh'), 'own_ram_exists')
    -- Refuse an occupied port, including a wildcard listener. Do not stop it.
    for line in read('/proc/net/tcp', 131072):gmatch('[^\n]+') do
        local port, state = line:match('^%s*%d+:%s+[0-9A-Fa-f]+:([0-9A-Fa-f]+)%s+%S+%s+(%S+)')
        need(not (port and tonumber(port, 16) == 22 and state == '0A'), 'ssh_port_occupied')
    end
    for line in read('/proc/net/tcp6', 131072):gmatch('[^\n]+') do
        local port, state = line:match('^%s*%d+:%s+[0-9A-Fa-f]+:([0-9A-Fa-f]+)%s+%S+%s+(%S+)')
        need(not (port and tonumber(port, 16) == 22 and state == '0A'), 'ssh_port_occupied')
    end
    stage = 'stage_owned_files'
    run('mkdir -m 700 ' .. quote(base)); created = true
    write(base .. '/OWNER', CTX.owner_token .. '\n')
    write(base .. '/ENABLE', 'disabled\n')
    write(base .. '/probe-launch.before', CTX.old_launch)
    write(base .. '/password.hash', CTX.password_hash)
    write(base .. '/internetdeco-ssh', CTX.service)
    local pieces = {}
    for _, path in ipairs(CTX.binary_parts) do pieces[#pieces + 1] = read(path, 7000) end
    write(CTX.work .. '/server.gz', decode64(table.concat(pieces)))
    run('/bin/busybox gzip -dc ' .. quote(CTX.work .. '/server.gz') .. ' > ' .. quote(base .. '/dropbearmulti'))
    need(fs.chmod(base .. '/dropbearmulti', '0700'), 'binary_mode')
    need(sha1(base .. '/dropbearmulti') == CTX.binary_sha1, 'binary_checksum')
    stage = 'generate_private_host_key'
    -- Keys stay on this node. Suppress key material and tool output.
    run(quote(base .. '/dropbearmulti') .. ' dropbearkey -t ed25519 -f ' .. quote(base .. '/host_key') .. ' >/dev/null 2>&1')
    need(fs.chmod(base .. '/host_key', '0600'), 'key_mode')
    stage = 'activate'
    run('sync')
    replace_launch(CTX.new_launch); activated = true
    set_enabled('enabled\n'); run('sync')
    run('/bin/sh -c ' .. quote('. ' .. quote(launch_path)))
    stage = 'verify_service'
    for _ = 1, 40 do
        pid = service_pid()
        if pid and fs.readlink('/proc/' .. pid .. '/exe') == '/tmp/internetdeco-ssh/dropbearmulti' then break end
        pid = nil; require('nixio').nanosleep(0, 250000000)
    end
    need(pid, 'service_not_running')
    local listening = false
    for line in read('/proc/net/tcp', 131072):gmatch('[^\n]+') do
        local address, state = line:match('^%s*%d+:%s+(%S+)%s+%S+%s+(%S+)')
        if address == '@TARGET_TCP@' and state == '0A' then listening = true end
    end
    need(listening, 'service_not_listening')
    need(read(base .. '/password.hash', 192) == CTX.password_hash, 'credential_readback')
    need(read(launch_path, 16384) == CTX.new_launch, 'launch_readback')
    stage = 'verified'
end
local ok, detail = pcall(execute)
if not ok and created then
    pcall(set_enabled, 'disabled\n')
    pcall(stop_owned)
    pcall(function()
        if activated and read(launch_path, 16384) == CTX.new_launch then replace_launch(CTX.old_launch) end
    end)
end
local result = {schema=1, kind='owner-password-ssh', token=CTX.token, action=CTX.action,
    target=CTX.target, boot_id=boot_id, ok=ok, stage=stage, pid=pid,
    system_accounts_changed=false, config_save_called=false, firewall_reloaded=false}
if not ok then result.error_code = tostring(detail):match('^[a-z0-9_]+$') or 'stage_failed' end
write(CTX.work .. '/result.json', json.encode(result))
