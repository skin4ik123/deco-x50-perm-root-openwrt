package com.internetdeco.decocontrol

enum class ConnectionPhase {
    DISCONNECTED, QUEUED, ROUTING, CONNECTING, BANNER, KEY_EXCHANGE, VERIFYING_KEY,
    AUTHENTICATING, RETRY_WAIT, CONNECTED, FAILED, CANCELLED
}

data class ConnectionState(
    val host: String,
    val port: Int = 22,
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val attempt: Int = 0,
    val attempts: Int = 3,
    val startedAt: Long = System.currentTimeMillis(),
    val retryAt: Long = 0,
    val detail: String? = null,
    val profileKey: String = host,
) {
    val key: String get() = "$profileKey:$port"
    val busy: Boolean get() = phase in setOf(
        ConnectionPhase.QUEUED, ConnectionPhase.ROUTING, ConnectionPhase.CONNECTING, ConnectionPhase.BANNER,
        ConnectionPhase.KEY_EXCHANGE, ConnectionPhase.VERIFYING_KEY,
        ConnectionPhase.AUTHENTICATING, ConnectionPhase.RETRY_WAIT,
    )
    val connected: Boolean get() = phase == ConnectionPhase.CONNECTED
    val label: String get() = when (phase) {
        ConnectionPhase.DISCONNECTED -> "Disconnected"
        ConnectionPhase.QUEUED -> "Waiting to connect"
        ConnectionPhase.ROUTING -> "Selecting Wi-Fi"
        ConnectionPhase.CONNECTING -> "Connecting"
        ConnectionPhase.BANNER -> "Waiting for SSH"
        ConnectionPhase.KEY_EXCHANGE -> "Securing connection"
        ConnectionPhase.VERIFYING_KEY -> "Verify device key"
        ConnectionPhase.AUTHENTICATING -> "Signing in"
        ConnectionPhase.RETRY_WAIT -> "Retrying connection"
        ConnectionPhase.CONNECTED -> "SSH connected"
        ConnectionPhase.FAILED -> "Connection failed"
        ConnectionPhase.CANCELLED -> "Connection cancelled"
    }
}

data class ConnectionOptions(
    val tcpTimeoutMs: Int = 30_000,
    val handshakeTimeoutMs: Int = 90_000,
    val channelTimeoutMs: Int = 30_000,
    val commandTimeoutMs: Int = 90_000,
    val attempts: Int = 3,
) {
    init {
        require(tcpTimeoutMs in 5_000..120_000)
        require(handshakeTimeoutMs in 15_000..180_000)
        require(channelTimeoutMs in 5_000..120_000)
        require(commandTimeoutMs in 15_000..180_000)
        require(attempts in 1..3)
    }
}
