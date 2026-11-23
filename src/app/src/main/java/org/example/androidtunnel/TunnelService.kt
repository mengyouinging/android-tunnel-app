package org.example.androidtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.example.androidtunnel.security.SecretStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ddnsUpdater: DdnsUpdater? = null
    private var ddnsScheduled = false
    private var monitorScheduled = false
    private val prefs by lazy { getSharedPreferences("tunnel_status", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("正在检查组件"))
        prefs.edit().putBoolean("running", true).apply()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidTunnel:service").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        registerNetworkChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopComponents()
            stopSelf()
            return START_NOT_STICKY
        }
        startDdnsUpdater()
        worker.execute {
            startRuntime()
            if (!monitorScheduled) {
                monitorScheduled = true
                worker.scheduleWithFixedDelay({ monitorLink() }, 20, 20, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopComponents()
        runCatching { networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        worker.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        File(filesDir, "runtime").listFiles()?.forEach { it.delete() }
        prefs.edit().putBoolean("running", false).putString("mode", TunnelMode.STOPPED.name)
            .putString("detail", "服务已停止").putLong("latency", -1L).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRuntime() {
        val installed = ComponentManager(this).prepare()
        if (!installed.ready) {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = installed.missing.joinToString("；") { "组件缺失：$it" }))
            return
        }
        val peer = try { readPeerConfig() } catch (e: Exception) {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = e.message ?: "服务配置无效"))
            return
        }
        try { writeRuntimeConfigs(peer) } catch (e: Exception) {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "服务配置写入失败：${e.message ?: "未知错误"}"))
            return
        }
        if (!RootController.hasRoot()) {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "未取得 root 授权"))
            return
        }
        if (!RootController.installComponents(installed.files)) {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "组件已校验但无法复制到可执行目录"))
            return
        }
        val binDir = File("/data/local/tmp/android-tunnel")
        val runDir = File(filesDir, "runtime")
        if (!RootController.startComponent("amneziawg-go", File(binDir, "amneziawg-go").absolutePath)) {
            stopComponents()
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "amneziawg-go 启动失败"))
            return
        }
        if (!RootController.setupInterface(peer.interfaceAddress, peer.allowedIps)) {
            stopComponents()
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "接口配置失败；需要可用的 /dev/net/tun、ip 与 ip6tables"))
            return
        }
        if (!RootController.configureAwg(File(runDir, "amneziawg-go.conf").absolutePath)) {
            stopComponents()
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "AmneziaWG 配置失败"))
            return
        }
        if (
            !RootController.startComponent("sing-box", File(binDir, "sing-box").absolutePath, File(runDir, "sing-box.json").absolutePath)) {
            stopComponents()
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "IPv6 主链路启动失败，已回滚已启动组件"))
            return
        }
        val direct = TcpPeerProbe().probe(peer.probeHost, peer.probePort, PROBE_TIMEOUT_MS)
        if (direct.answered) {
            updateStatus(TunnelStatus(TunnelMode.DIRECT_IPV6, currentIpv6 = prefs.getString("ipv6", null),
                peerLatencyMs = direct.latencyMs, detail = "主链路已启动并收到对端回应"))
            return
        }
        RootController.stopComponent("amneziawg-go")
        RootController.stopComponent("sing-box")
        val easyTier = RootController.startComponent("easytier-core", File(binDir, "easytier-core").absolutePath, File(runDir, "easytier-core.args").absolutePath)
        val fallback = if (easyTier) TcpPeerProbe().probe(peer.probeHost, peer.probePort, PROBE_TIMEOUT_MS) else ProbeResult(false, null)
        if (fallback.answered) {
            updateStatus(TunnelStatus(TunnelMode.FALLBACK, currentIpv6 = prefs.getString("ipv6", null),
                peerLatencyMs = fallback.latencyMs, detail = "EasyTier 回退已收到对端回应"))
            return
        }
        RootController.stopComponent("easytier-core")
        val emergencyStarted = RootController.startComponent("cloudflared", File(binDir, "cloudflared").absolutePath, File(runDir, "cloudflared.conf").absolutePath) &&
            RootController.startComponent("wstunnel", File(binDir, "wstunnel").absolutePath, File(runDir, "wstunnel.args").absolutePath)
        if (!emergencyStarted) {
            stopComponents()
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "直连与 EasyTier 探测超时，应急管理通道启动失败"))
        } else updateStatus(TunnelStatus(TunnelMode.EMERGENCY, detail = "cloudflared 与 wstunnel 管理通道进程已启动；peer 数据链路未确认，不显示延迟"))
    }

    private fun readPeerConfig(): PeerConfig {
        val store = SecretStore(this)
        val privatePrefs = getSharedPreferences("private_config", Context.MODE_PRIVATE)
        val jitter = (privatePrefs.getString("jitter", "3,10,50") ?: "3,10,50").split(',').map { it.trim().toInt() }
        require(jitter.size == 3) { "Jc/Jmin/Jmax 配置无效" }
        val port = store.get("port")?.toIntOrNull() ?: 51820
        return PeerConfig(
            store.get("awg_private") ?: "", store.get("peer_public") ?: "", store.get("peer_endpoint") ?: "",
            store.get("allowed_ips") ?: "", store.get("interface_address") ?: "", port, jitter[0], jitter[1], jitter[2], store.get("vless_uuid") ?: "",
            store.get("reality_private") ?: "", store.get("reality_short_id") ?: "", store.get("reality_server_name") ?: "",
            store.get("hysteria_password") ?: "", store.get("hysteria_cert_path") ?: "", store.get("hysteria_key_path") ?: "",
            store.get("easytier_network") ?: "", store.get("easytier_secret") ?: "",
            store.get("relay") ?: "", store.get("probe_host") ?: "", store.get("probe_port")?.toIntOrNull() ?: 0,
            store.get("cloudflared_token") ?: "", store.get("wstunnel_remote") ?: ""
        ).also(PeerConfig::validate)
    }

    private fun writeRuntimeConfigs(peer: PeerConfig) {
        val dir = File(filesDir, "runtime").apply { mkdirs() }
        fun write(name: String, value: String) {
            val file = File(dir, name)
            file.writeText(value, Charsets.UTF_8)
            file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
            file.setReadable(true, true); file.setWritable(true, true)
        }
        write("amneziawg-go.conf", ServiceConfigGenerator.amneziaWg(peer))
        write("sing-box.json", ServiceConfigGenerator.singBox(peer))
        write("easytier-core.args", ServiceConfigGenerator.easyTier(peer))
        write("cloudflared.conf", SecretStore(this).get("cloudflared_token") ?: error("Tunnel Token 待配置"))
        write("wstunnel.args", "client --remote ${peer.wstunnelRemote} tcp://127.0.0.1:22")
        dir.listFiles()?.filter { it.name.endsWith(".conf") || it.name.endsWith(".json") || it.name.endsWith(".args") }?.forEach {
            it.setReadable(false, false); it.setWritable(false, false); it.setReadable(true, true); it.setWritable(true, true)
        }
    }

    private fun stopComponents() {
        runCatching { RootController.stopAllComponents() }
    }

    private fun monitorLink() {
        if (!prefs.getBoolean("running", false)) return
        val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", TunnelMode.FAILED.name)!!) }.getOrDefault(TunnelMode.FAILED)
        if (mode !in setOf(TunnelMode.DIRECT_IPV6, TunnelMode.FALLBACK)) return
        val peer = runCatching { readPeerConfig() }.getOrNull() ?: return
        val result = TcpPeerProbe().probe(peer.probeHost, peer.probePort, PROBE_TIMEOUT_MS)
        if (result.answered) {
            updateStatus(TunnelStatus(mode, currentIpv6 = prefs.getString("ipv6", null), peerLatencyMs = result.latencyMs,
                detail = if (mode == TunnelMode.DIRECT_IPV6) "周期探测收到对端回应" else "EasyTier 回退周期探测收到对端回应"))
        } else {
            updateStatus(TunnelStatus(TunnelMode.FAILED, detail = "对端探测超时；正在重新选择链路"))
            stopComponents()
            startRuntime()
        }
    }

    private fun registerNetworkChanges() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (prefs.getBoolean("running", false)) {
                    runDdnsCheck()
                    worker.execute { stopComponents(); startRuntime() }
                }
            }
            override fun onLost(network: Network) {
                if (prefs.getBoolean("running", false)) worker.execute { stopComponents(); startRuntime() }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback); networkCallback = callback }
    }

    private fun startDdnsUpdater() {
        if (ddnsScheduled) return
        ddnsScheduled = true
        runDdnsCheck()
        worker.scheduleWithFixedDelay({ runDdnsCheck() }, 5, 5, TimeUnit.MINUTES)
    }

    private fun runDdnsCheck() {
        worker.execute {
            try {
                val store = SecretStore(this)
                val token = store.get("token") ?: return@execute
                val domain = store.get("domain") ?: return@execute
                val updater = ddnsUpdater ?: synchronized(this) {
                    ddnsUpdater ?: DdnsUpdater(CloudflareClient(), SystemIpv6AddressProvider(), token, domain).also { ddnsUpdater = it }
                }
                val result = updater.refresh()
                if (result != null) {
                    val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", TunnelMode.FAILED.name)!!) }.getOrDefault(TunnelMode.FAILED)
                    val latency = prefs.getLong("latency", -1L).takeIf { it >= 0 }
                    updateStatus(TunnelStatus(mode, currentIpv6 = result.content, peerLatencyMs = latency, detail = "Cloudflare AAAA 已更新"))
                }
            } catch (e: Exception) {
                val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", TunnelMode.FAILED.name)!!) }.getOrDefault(TunnelMode.FAILED)
                updateStatus(TunnelStatus(mode, currentIpv6 = prefs.getString("ipv6", null),
                    peerLatencyMs = prefs.getLong("latency", -1L).takeIf { it >= 0 }, detail = "DDNS 更新失败：${e.message ?: "未知错误"}"))
            }
        }
    }

    private fun updateStatus(status: TunnelStatus) {
        prefs.edit().putString("mode", status.mode.name).putString("detail", status.detail)
            .putString("ipv6", status.currentIpv6).putLong("latency", status.peerLatencyMs ?: -1L).apply()
        val content = buildString {
            append(status.mode.displayMode())
            if (status.currentIpv6 != null) append(" · ").append(status.currentIpv6)
            if (status.peerLatencyMs != null) append(" · ").append(status.peerLatencyMs).append(" ms")
            if (status.detail.isNotBlank()) append(" · ").append(status.detail)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private fun notification(content: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder.setContentTitle("Android Tunnel").setContentText(content).setStyle(Notification.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(openApp).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "隧道服务", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_START = "org.example.androidtunnel.START"
        const val ACTION_STOP = "org.example.androidtunnel.STOP"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val PROBE_TIMEOUT_MS = 2500

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP)) }
    }
}
