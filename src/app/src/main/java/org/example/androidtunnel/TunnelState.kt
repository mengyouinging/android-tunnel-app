package org.example.androidtunnel

enum class TunnelMode {
    STOPPED, DIRECT_IPV6, FALLBACK, EMERGENCY, FAILED;

    fun displayMode(): String = when (this) {
        STOPPED -> "已停止"
        DIRECT_IPV6 -> "IPv6 直连"
        FALLBACK -> "回退中"
        EMERGENCY -> "应急"
        FAILED -> "启动失败"
    }
}

data class TunnelStatus(val mode: TunnelMode, val currentIpv6: String? = null, val peerLatencyMs: Long? = null, val detail: String = "") {
}

object TunnelStateMachine {
    fun select(directPeerAnswered: Boolean, fallbackPeerAnswered: Boolean, emergencyAvailable: Boolean): TunnelMode = when {
        directPeerAnswered -> TunnelMode.DIRECT_IPV6
        fallbackPeerAnswered -> TunnelMode.FALLBACK
        emergencyAvailable -> TunnelMode.EMERGENCY
        else -> TunnelMode.FAILED
    }

    fun validatedLatency(mode: TunnelMode, peerAnswered: Boolean, latencyMs: Long?): Long? =
        if (mode in setOf(TunnelMode.DIRECT_IPV6, TunnelMode.FALLBACK) && peerAnswered && latencyMs != null && latencyMs >= 0) latencyMs else null
}
