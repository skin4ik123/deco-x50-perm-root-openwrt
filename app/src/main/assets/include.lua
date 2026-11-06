-- Reviewed diagnostic transaction. CTX is injected by the phone tool.
-- No reload/restart/reboot. Decoder output and all logs stay in private RAM.
local fs = require("nixio.fs")
local json = require("luci.json")
local config = require("luci.sys.config")
local uci = require("luci.model.uci")
local crypto = require("luci.model.crypto")
local prefix = "/tp_data/user_data/internetdeco-include-probe"
local section = "internetdeco_probe"
local stage, save_attempted, readback_verified = "start", false, false
local created_own, manual_receipt = false, false
local traffic_preserved = false
local boot_id

local function need(value, code)
    if not value then error(code or "check", 0) end
    return value
end
local function read(path, limit)
    local f = need(io.open(path, "rb"), "open")
    local value = f:read((limit or 1048576) + 1) or ""
    f:close()
    need(#value <= (limit or 1048576), "file_limit")
    return value
end
local function write(path, value)
    local f = need(io.open(path, "wb"), "write_open")
    need(f:write(value), "write")
    need(f:close(), "close")
end
local function quote(value)
    return "'" .. value:gsub("'", "'\\''") .. "'"
end
local function run(command)
    need(os.execute(command) == 0, "command")
end
local function capture(command)
    local f = need(io.popen(command, "r"), "popen")
    local value = f:read(4097) or ""
    f:close()
    need(#value <= 4096, "capture_limit")
    return value:gsub("%s+$", "")
end
local function sha1(path)
    local value = capture("sha1sum " .. quote(path)):match("^([0-9a-f]+)%s")
    need(value and #value == 40, "sha1")
    return value
end
local function same(a, b)
    if type(a) ~= type(b) then return false end
    if type(a) ~= "table" then return a == b end
    for k, v in pairs(a) do if not same(v, b[k]) then return false end end
    for k in pairs(b) do if a[k] == nil then return false end end
    return true
end
local function same_except_traffic(a, b)
    for key, value in pairs(a) do
        if key ~= "month_traffic" and not same(value, b[key]) then return false end
    end
    for key in pairs(b) do
        if key ~= "month_traffic" and a[key] == nil then return false end
    end
    return true
end
local function traffic_span(xml)
    local stack, position, found, start, tag = {}, 1
    while position <= #xml do
        local newline = xml:find("\n", position, true) or (#xml + 1)
        local line = xml:sub(position, newline - 1):match("^%s*(.-)%s*$")
        local close = line:match("^</([%w_%-]+)>$")
        local open = line:match("^<([%w_%-]+)[^<>]*>$")
        if close then
            need(stack[#stack] == close, "xml_nesting")
            if #stack == 2 and start and close == tag then
                need(not found, "duplicate_traffic_package")
                found = {first = start, last = math.min(newline, #xml), tag = tag}
                start = nil
            end
            stack[#stack] = nil
        elseif open and line:sub(-2) ~= "/>" then
            if #stack == 1 and stack[1] == "config" and
                    (open == "month_traffic" or open:match("^month_traffic_%d+$")) then
                need(not found and not start, "duplicate_traffic_package")
                start, tag = position, open
            end
            stack[#stack + 1] = open
        end
        position = newline + 1
    end
    need(#stack == 0 and found and not start, "traffic_xml_package")
    found.data = xml:sub(found.first, found.last)
    return found
end
local plain_snapshot
local function save_preserving_traffic(original_xml, expected)
    local original = traffic_span(original_xml)
    local formatter = config.fileToXml
    need(type(formatter) == "function", "formatter")
    config.fileToXml = function(path, predicate)
        need(path == "/tmp/save-devconf.xml" and predicate == config.is_device_config, "save_scope")
        -- saveconfig has acquired DEVICE_CONFIG_SAVE_LOCK at this point.
        local locked = CTX.work .. "/locked-before.cry"
        run("nvrammanager -r " .. quote(locked) .. " -p device-config")
        need(sha1(locked) == sha1(CTX.work .. "/before.cry"), "persisted_config_changed_before_save")
        local existing = fs.lstat(path)
        need(not existing or existing.type == "reg", "unsafe_save_temp")
        write(path, "IDM_PENDING_" .. CTX.token .. "\n")
        formatter(path, predicate)
        local candidate = read(path, 1048576)
        local current = traffic_span(candidate)
        need(current.tag == original.tag, "traffic_version_changed")
        write(path, candidate:sub(1, current.first - 1) .. original.data .. candidate:sub(current.last + 1))
        need(same(plain_snapshot(path, CTX.work .. "/candidate"), expected), "candidate_config_difference")
    end
    local ok = pcall(config.saveconfig, "device-config")
    config.fileToXml = formatter
    need(ok, "save_exception")
end
local function package_snapshot(cursor, name)
    if type(cursor.load) == "function" then need(cursor:load(name), "uci_load") end
    local data = cursor:get_all(name) or {}
    local ordered, seen = {}, {}
    for _, entry in pairs(data) do
        need(type(entry) == "table" and type(entry[".type"]) == "string", "uci_section")
        local index = entry[".index"]
        need(type(index) == "number" and index >= 0 and index == math.floor(index) and not seen[index], "uci_index")
        seen[index] = true
        local normalized = {kind = entry[".type"], options = {}}
        if not entry[".anonymous"] then
            need(type(entry[".name"]) == "string", "uci_name")
            normalized.name = entry[".name"]
        end
        for key, value in pairs(entry) do
            need(type(key) == "string", "uci_key")
            if key:sub(1, 1) ~= "." then
                if type(value) == "table" then
                    local array = {}
                    for i, item in pairs(value) do
                        need(type(i) == "number" and i >= 1 and i <= #value and type(item) == "string", "uci_list")
                        array[i] = item
                    end
                    normalized.options[key] = array
                else
                    need(type(value) == "string", "uci_value")
                    normalized.options[key] = value
                end
            end
        end
        ordered[#ordered + 1] = {index = index, value = normalized}
    end
    table.sort(ordered, function(a, b) return a.index < b.index end)
    local result = {}
    for _, entry in ipairs(ordered) do result[#result + 1] = entry.value end
    return result
end
local function snapshot(directory)
    local iterator = need(fs.dir(directory), "config_dir")
    local cursor = uci.cursor(directory)
    local result = {}
    for name in iterator do
        if name ~= "." and name ~= ".." then
            if config.is_device_config(name) then
                need(name:match("^[%w_%-]+$"), "package_name")
                local package = package_snapshot(cursor, name)
                if #package > 0 then result[name] = package end
            end
        end
    end
    return result
end
plain_snapshot = function(xml, destination)
    run("mkdir -m 700 " .. quote(destination))
    run("mkdir -m 700 " .. quote(destination .. "/config"))
    need(read(xml, 1048576):sub(1, 6) == "<?xml ", "decrypt_xml")
    -- Stock set_subcfg appends /tmp/fileversion.xml. Replace it only in this
    -- Lua process so decoding cannot mutate the live version catalogue.
    local original = config.set_subcfg
    config.set_subcfg = function(name)
        local base = name:match("(.+)_%d") or name
        need(base:match("^[%w_%-]+$"), "decoded_package")
        return base
    end
    local ok, value = pcall(config.xmlToFile, xml, destination)
    config.set_subcfg = original
    need(ok and value == true, "xml_decode")
    return snapshot(destination .. "/config")
end
local function decoded_snapshot(cipher, label)
    local xml = CTX.work .. "/" .. label .. ".xml"
    local profile = uci.cursor("/etc/profile.d")
    if profile:get("profile", "global", "trk_enc_file") == "true" then
        crypto.dec_file_entry_prv(cipher, xml)
    else
        crypto.dec_file_entry(cipher, xml)
    end
    return plain_snapshot(xml, CTX.work .. "/" .. label)
end
local function own_entry(value)
    return value and value.kind == "include" and value.name == section and
        same(value.options, {path = prefix .. "/launch.sh", idm_probe_token = CTX.owner_token})
end
local function find_own(packages)
    local found
    for i, entry in ipairs(packages.firewall or {}) do
        if entry.name == section then need(not found, "duplicate_own_section"); found = i end
    end
    return found
end
local function disable_owned()
    local directory = fs.lstat(prefix)
    need(directory and directory.type == "dir", "foreign_probe_dir")
    local launch, enable = fs.lstat(prefix .. "/launch.sh"), fs.lstat(prefix .. "/ENABLE")
    need(launch and launch.type == "reg" and enable and enable.type == "reg", "foreign_probe_file")
    need(read(prefix .. "/launch.sh", 16384) == CTX.launch, "foreign_probe_data")
    write(prefix .. "/ENABLE", "disabled\n")
end
local function execute()
    stage = "guards"
    need(CTX.token:match("^[0-9a-f]+$") and #CTX.token == 32, "token")
    need(CTX.owner_token:match("^[0-9a-f]+$") and #CTX.owner_token == 32, "owner_token")
    need(CTX.work == "/tmp/internetdeco-include-" .. CTX.token, "work")
    need(CTX.action == "install" or CTX.action == "rollback", "action")
    local status = read("/proc/self/status", 131072)
    need(status:match("\nUid:%s+0%s+0%s+0%s+0%s*\n"), "uid0")
    boot_id = read("/proc/sys/kernel/random/boot_id", 100):gsub("%s+$", "")
    need(#boot_id == 36 and boot_id:match("^[0-9a-f%-]+$"), "boot_id")
    local persistent = false
    for line in read("/proc/mounts", 131072):gmatch("[^\n]+") do
        local _, path, kind, flags = line:match("^(%S+)%s+(%S+)%s+(%S+)%s+(%S+)")
        if path == "/tp_data/user_data" and kind == "ubifs" and ("," .. flags .. ","):find(",rw,", 1, true) then persistent = true end
    end
    need(persistent, "persistent_mount")
    need(config.is_device_config("firewall") == true and config.is_user_config("firewall") == false and config.is_emmc_config("firewall") == false, "partition")
    need(not fs.stat("/tmp/reset_now"), "reset_in_progress")
    need(capture("readlink /etc/rc.d/S45firewall") == "../init.d/firewall", "firewall_boot_link")
    need(capture("readlink /etc/init.d") == "../etc_ro/init.d", "init_link")
    stage = "baseline_read"
    local before = CTX.work .. "/before.cry"
    run("nvrammanager -r " .. quote(before) .. " -p device-config")
    if CTX.action == "install" then
        need(sha1(before) == CTX.config_sha1 and sha1("/etc/config/firewall") == CTX.firewall_sha1, "backup_changed")
    end
    stage = "baseline_decode"
    local baseline = decoded_snapshot(before, "before")
    stage = "compare_ram_flash"
    local live = snapshot("/etc/config")
    local found, live_found = find_own(baseline), find_own(live)
    if CTX.action == "install" then
        need(baseline.firewall and baseline.month_traffic and live.month_traffic and same_except_traffic(baseline, live), "unsaved_config_difference")
        traffic_span(read(CTX.work .. "/before.xml", 1048576))
        need(not found and not fs.lstat(prefix), "own_path_exists")
        stage = "stage_probe"
        run("mkdir -m 700 " .. quote(prefix))
        created_own = true
        write(prefix .. "/launch.sh", CTX.launch)
        write(prefix .. "/ENABLE", "enabled\n")
        need(read(prefix .. "/launch.sh", 16384) == CTX.launch, "launch_readback")
        stage = "manual_probe"
        run("/bin/sh -c " .. quote(". " .. quote(prefix .. "/launch.sh")))
        local receipt = json.decode(read("/tmp/internetdeco-include-probe." .. boot_id .. "/receipt.json", 4096))
        need(receipt.uid == 0 and receipt.boot_id == boot_id and receipt.kind == "include-probe", "manual_probe")
        manual_receipt = true
        baseline.firewall[#baseline.firewall + 1] = {kind = "include", name = section, options = {path = prefix .. "/launch.sh", idm_probe_token = CTX.owner_token}}
        live.firewall[#live.firewall + 1] = {kind = "include", name = section, options = {path = prefix .. "/launch.sh", idm_probe_token = CTX.owner_token}}
    else
        if found then need(own_entry(baseline.firewall[found]), "foreign_section") end
        if live_found then need(own_entry(live.firewall[live_found]), "foreign_ram_section") end
        if found then table.remove(baseline.firewall, found) end
        if live_found then table.remove(live.firewall, live_found) end
        if baseline.firewall and #baseline.firewall == 0 then baseline.firewall = nil end
        if live.firewall and #live.firewall == 0 then live.firewall = nil end
        need(same_except_traffic(baseline, live), "unsaved_config_difference")
        if fs.lstat(prefix) then disable_owned() end
        if live_found then
            run("uci delete firewall." .. section .. " && uci commit firewall")
            need(same_except_traffic(live, snapshot("/etc/config")), "rollback_ram_difference")
        end
        if not found then readback_verified = true; traffic_preserved = true; stage = "already_absent"; return end
    end
    stage = "commit_own_section"
    local command
    if CTX.action == "install" then
        command = "uci set firewall." .. section .. "=include && uci set firewall." .. section .. ".path=" .. quote(prefix .. "/launch.sh") .. " && uci set firewall." .. section .. ".idm_probe_token=" .. quote(CTX.owner_token) .. " && uci commit firewall"
    else
        command = ":" -- RAM removal already verified above.
    end
    run(command)
    need(same_except_traffic(live, snapshot("/etc/config")), "unexpected_ram_change")
    stage = "save_device_config"
    save_attempted = true
    save_preserving_traffic(read(CTX.work .. "/before.xml", 1048576), baseline)
    stage = "readback"
    local after = CTX.work .. "/after.cry"
    run("nvrammanager -r " .. quote(after) .. " -p device-config")
    local saved = decoded_snapshot(after, "after")
    need(same(saved, baseline), "saved_config_difference")
    need(traffic_span(read(CTX.work .. "/after.xml", 1048576)).data == traffic_span(read(CTX.work .. "/before.xml", 1048576)).data, "saved_traffic_changed")
    traffic_preserved = true
    readback_verified = true
    stage = "verified"
end
local ok, detail = xpcall(execute, function(error_value) return tostring(error_value) end)
if not ok and CTX.action == "install" and created_own then
    -- Disable future execution without making another NVRAM save attempt.
    pcall(disable_owned)
end
local result = {schema = 1, engine_version = 2, traffic_preserved = traffic_preserved,
    kind = "include-probe-transaction", token = CTX.token,
    owner_token = CTX.owner_token, action = CTX.action, ok = ok, stage = stage,
    save_attempted = save_attempted, readback_verified = readback_verified,
    manual_receipt = manual_receipt, boot_id = boot_id}
if not ok then
    result.error_code = detail:match("^[a-z0-9_]+$") or "stage_failed"
    print(detail) -- Private RAM log only, never part of the phone status report.
end
write(CTX.work .. "/result.tmp", json.encode(result))
need(os.rename(CTX.work .. "/result.tmp", CTX.work .. "/result.json"), "result_rename")
