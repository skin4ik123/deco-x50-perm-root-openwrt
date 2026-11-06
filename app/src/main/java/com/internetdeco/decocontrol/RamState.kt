package com.internetdeco.decocontrol

import org.json.JSONObject

data class RamImageState(
    val downloading: Boolean = false,
    val received: Long = 0,
    val total: Long = 13_056_916,
    val path: String? = null,
    val error: String? = null,
) {
    val verified: Boolean get() = path != null && !downloading && error == null
}

internal class RamState(val data: JSONObject?) {
    private fun string(key: String): String = if (data != null && !data.isNull(key)) data.optString(key) else ""
    val phase: String get() = string("phase")
    val operationId: String get() = string("operation_id")
    val pending: Boolean get() = operationId.isNotBlank() && data?.optBoolean("resolved", false) != true
    val canPrepare: Boolean get() = data?.optBoolean("can_prepare", !pending) ?: true
    val canStart: Boolean get() = phase == "prepared" && data?.optBoolean("can_start") == true
    val canRestore: Boolean get() = data?.optBoolean("can_restore") == true
    val started: Boolean get() = pending && phase in setOf("reboot_requested", "reboot_unconfirmed", "openwrt_verified")
    val openWrtVerified: Boolean get() = pending && phase == "openwrt_verified" && data?.optJSONObject("openwrt")?.optBoolean("verified") == true
    val serverIp: String get() = string("server_ip").ifBlank { "192.168.1.2" }
    val backupFolder: String? get() = string("backup_folder").takeIf { it.isNotBlank() }
    val title: String get() = when {
        openWrtVerified -> "OpenWrt verified"
        !pending && operationId.isNotBlank() -> "Stock boot policy restored"
        phase == "prepared" -> "Ready for a RAM boot"
        phase == "armed_verified" -> "One-time boot configured"
        phase in setOf("reboot_requested", "reboot_unconfirmed") -> "Waiting for OpenWrt"
        pending -> "Check this operation"
        else -> "Try OpenWrt in RAM"
    }
    val message: String get() = string("message")
    val error: String get() = string("error")
}

internal fun validTftpServer(value: String): Boolean = Controller.validHost(value) &&
    value.startsWith("192.168.1.") && value != "192.168.1.1"

internal fun ramOperationTitle(action: String, data: JSONObject?): String {
    if (action == "ram_image") return "RAM image verified"
    val ram = RamState(data)
    return when (action) {
        "ram_prepare" -> "Compatibility checked · backup saved"
        "ram_start" -> if (ram.started) "Boot requested · OpenWrt not yet verified" else "Check the boot policy before continuing"
        "ram_check_openwrt" -> if (ram.openWrtVerified) "OpenWrt is running in RAM" else "OpenWrt has not been verified"
        "ram_check_stock", "ram_restore" -> ram.title
        else -> "Operation complete"
    }
}
