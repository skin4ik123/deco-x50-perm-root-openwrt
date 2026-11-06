package com.internetdeco.decocontrol

/** A RAM session never replaces a saved stock login, even if both use 192.168.1.1. */
internal data class RamEndpoint(val stockHost: String, val operationId: String) {
    init {
        require(Controller.validHost(stockHost)) { "Invalid stock Deco address" }
        require(operationId.matches(Regex("[0-9a-f]{32}"))) { "Invalid RAM operation" }
    }
    val terminalKey: String get() = "ram:$stockHost:$operationId"
    val pinScope: String get() = "ram-pin:$stockHost:$operationId"

    companion object {
        const val HOST = "192.168.1.1"
        fun fromTerminalKey(key: String): RamEndpoint {
            val parts = key.split(':')
            require(parts.size == 3 && parts[0] == "ram") { "Invalid RAM terminal" }
            return RamEndpoint(parts[1], parts[2])
        }
    }
}
