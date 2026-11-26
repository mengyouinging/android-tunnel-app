package org.example.androidtunnel

enum class TunnelMode {
    STOPPED, STARTING, DIRECT_IPV6, FALLBACK, WAITING, FAILED;
    fun displayMode(): String = when (this) {
        STOPPED -> "已停止"
        STARTING -> "正在握手"
        DIRECT_IPV6 -> "IPv6 直连"
        FALLBACK -> "Cloudflare 隧道保底"
        WAITING -> "等待链路验证"
        FAILED -> "启动失败"
    }
}
object TunnelStateMachine {
    fun select(directHandshaken: Boolean, fallbackEgress: Boolean, ready: Boolean): TunnelMode = when {
        directHandshaken -> TunnelMode.DIRECT_IPV6
        fallbackEgress -> TunnelMode.FALLBACK
        ready -> TunnelMode.WAITING
        else -> TunnelMode.FAILED
    }
    fun freshHandshake(timestamp: Long, now: Long): Boolean = timestamp > 0 && now - timestamp in 0..180
}
