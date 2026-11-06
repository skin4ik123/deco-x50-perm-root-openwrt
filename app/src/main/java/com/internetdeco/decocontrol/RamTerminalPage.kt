package com.internetdeco.decocontrol

import androidx.compose.runtime.Composable
import org.json.JSONObject

@Composable internal fun RamTerminalPage(s: ScreenState, node: Node?) {
    val host = node?.host.orEmpty()
    val ram = RamState(result(s, host, "ram_status") as? JSONObject)
    if (!ram.openWrtVerified || ram.operationId.isBlank()) {
        Empty("Verify OpenWrt first", "Connect your phone to the RAM Wi-Fi and verify the running system.", "Open RAM boot") { Controller.page("openwrt") }
        return
    }
    val endpoint = RamEndpoint(host, ram.operationId)
    TerminalPage(endpoint.terminalKey, Controller.bridge.connected(endpoint.terminalKey), displayHost = RamEndpoint.HOST, connectionHost = endpoint.terminalKey) {
        Controller.mutate("ram_check_openwrt", JSONObject().put("operation_id", ram.operationId), host)
    }
}
