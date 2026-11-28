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
import android.net.LinkProperties
import android.os.IBinder
import android.os.PowerManager
import java.net.Inet6Address
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class TunnelService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val prefs by lazy { getSharedPreferences("tunnel_status", Context.MODE_PRIVATE) }
    private val runtime by lazy { File(filesDir, "runtime").apply { mkdirs() } }
    private val journal by lazy { File(filesDir, "v2-nat-undo") }
    private val manager by lazy { getSystemService(ConnectivityManager::class.java) }
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false
    private val networkQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var scheduled = false
    private var cleaned = false
    private var rootAuthorized = false
    private var bootOrigin = false
    private var bootWaitCount = 0
    private var directReady = false
    private var cfStarted = false
    private var lastNetwork: String? = null
    @Volatile private var ipv6: String? = null
    private var registeredIpv6: String? = null
    private var attemptedEmptyRegistration = false
    private var registration = "尚未登记"
    private var directDetail = ""
    private var probePassword = ""
    private var lastCfProbeAt = 0L
    private var cfProbeMs: Long? = null
    @Volatile private var keys: LocalKeys? = null
    private var settings: RelaySettings? = null
    private var config: SharedConfig? = null
    private var peerServer: PeerInfoServer? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "隧道服务", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).cancel(FAILURE_NOTIFICATION)
        startForeground(NOTIFICATION, notification("启动中"))
        prefs.edit().putBoolean("running", true).putString("mode", TunnelMode.STARTING.name)
            .putString("detail", "正在检测 Root 权限").remove("failure_code").remove("failure_detail").remove("ipv6").putBoolean("direct_ready", false)
            .putBoolean("cf_connected", false).putLong("cf_probe", -1L).apply()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidTunnel:service").also {
            it.setReferenceCounted(false); it.acquire()
        }
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = signalNetwork()
            override fun onLost(network: Network) = signalNetwork()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = signalNetwork()
        }
        runCatching { manager.registerDefaultNetworkCallback(listener); callback = listener }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            worker.execute { cleanup(); stopSelf() }
            return START_NOT_STICKY
        }
        if (!scheduled) {
            scheduled = true
            bootOrigin = intent?.action == ACTION_BOOT_START || intent == null
            worker.execute { bootstrap() }
            worker.scheduleWithFixedDelay({ tickSafe() }, 10, 10, TimeUnit.SECONDS)
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopping = true
        runCatching { callback?.let { manager.unregisterNetworkCallback(it) } }
        callback = null
        // Serialize cleanup behind any active startup or probe; do not race root operations.
        runCatching { worker.execute { cleanup() } }
        worker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private fun signalNetwork() {
        if (!stopping && scheduled && networkQueued.compareAndSet(false, true)) runCatching {
            worker.execute { networkQueued.set(false); tickSafe(networkEvent = true) }
        }.onFailure { networkQueued.set(false) }
    }

    private fun privateFile(name: String, data: String) {
        val file = File(runtime, name)
        file.writeText(data)
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
    }

    private fun bootstrap() {
        if (stopping || config != null) return
        // Foreground service notification must be posted immediately, but no network
        // config fetch, key generation, component install or firewall changes precede root.
        status(TunnelMode.STARTING, "正在检测 Root 权限")
        rootAuthorized = RootController.hasRoot()
        if (stopping) return
        if (!rootAuthorized) {
            failStartup("root_required", "未取得 Root 权限，清连无法启动")
            return
        }
        try {
            if (!ensureTunForStartup()) return
            RelayRepository.purgeLegacy(this)
            val selected = RelayRepository.settings(this)
            if (selected.password.isBlank()) {
                failStartup("password_required", "请先打开清连设置中转站口令")
                return
            }
            selected.validate()
            // A real boot can arrive before Wi-Fi/mobile data. Wait for the initial
            // network rather than silently attempting a config request with no network.
            // This is startup only; never repeat the relay handshake after establishment.
            if (bootOrigin && manager.activeNetwork == null && bootWaitCount < 12) {
                bootWaitCount++
                status(TunnelMode.STARTING, "等待网络连接")
                worker.schedule({ bootstrap() }, 15, TimeUnit.SECONDS)
                return
            }
            status(TunnelMode.STARTING, "正在拉取共享配置并准备密钥")
            val shared = RelayRepository.initial(this, selected)
            if (stopping) return
            val installed = ComponentManager(this).prepare()
            require(installed.ready) { "组件校验失败：${installed.missing.joinToString("、")}" }
            require(RootController.stopAll(journal)) { "上次出口规则清理失败，请检查系统防火墙" }
            require(RootController.installComponents(installed.files)) { "组件无法复制到 root 执行目录" }
            val local = KeyRepository.prepare(this, true)
            probePassword = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            privateFile("amneziawg-go.conf", ServiceConfigGenerator.amneziaWg(shared, local.awgPrivate))
            privateFile("sing-box.json", ServiceConfigGenerator.singBox(shared, probePassword))
            settings = selected; keys = local
            peerServer = PeerInfoServer(selected.password) {
                JSONObject().put("ok", true).put("ipv6", ipv6.orEmpty()).put("port", V2.PORT)
                    .put("pubkey", keys?.awgPublic.orEmpty())
                    .put("reality_public_key", keys?.realityPublic.orEmpty())
            }
            config = shared
            tickSafe()
        } catch (e: Exception) {
            if (!stopping) failStartup("startup_failed", safeError(e))
        }
    }

    private fun ensureTunForStartup(): Boolean {
        if (RootController.ensureTunDevice()) return !stopping
        if (!stopping) failStartup("tun_device_failed",
            "TUN 设备节点 /dev/net/tun 创建或复核失败（mknod/chmod 600，需字符设备），请检查 Root 权限、SELinux 或内核支持")
        return false
    }

    private fun failStartup(code: String, detail: String) {
        stopping = true
        cleanup(code, detail)
        // Keep the failure visible even if no Activity is open (e.g. boot).
        // Android may suppress notifications if permission has not been granted.
        val visible = when (code) {
            "root_required" -> getString(R.string.root_required_title)
            "password_required" -> getString(R.string.service_password_required)
            else -> getString(R.string.service_failed)
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(FAILURE_NOTIFICATION, notification(visible, ongoing = false))
        }
        stopSelf()
    }

    private fun safeError(e: Exception): String = if (e is RelayException) "中转站：${e.message}"
        else if (e is IllegalArgumentException || e is IllegalStateException) {
            // Only display deliberately authored messages. Never echo arbitrary exception strings.
            e.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } && it.length < 140 }
                ?: "配置、密钥或系统环境无效"
        } else "网络或系统操作失败"

    private fun tickSafe(networkEvent: Boolean = false) {
        if (stopping || config == null) return
        try { tick(networkEvent) }
        catch (e: Exception) { status(TunnelMode.FAILED, safeError(e)) }
    }
    private fun tick(networkEvent: Boolean) {
        val shared = config ?: return
        val local = keys ?: return
        val selected = settings ?: return
        val network = manager.activeNetwork
        val lp = network?.let { manager.getLinkProperties(it) }
        val iface = lp?.interfaceName
        val newIp = lp?.linkAddresses?.mapNotNull {
            (it.address as? Inet6Address)?.hostAddress?.substringBefore('%')
        }?.filter(V2::globalIpv6)?.sorted()?.let { candidates ->
            ipv6?.takeIf { it in candidates } ?: candidates.firstOrNull()
        }
        val signature = "$network|$iface|$newIp|${lp?.routes?.joinToString()}"
        val changed = signature != lastNetwork
        ipv6 = newIp

        // Cloudflare is a continuously running second entry, not a last-resort SSH channel.
        // Network changes never stop it unless its own process has actually failed.
        if (stopping) return
        ensureCloudflare(shared)

        if (changed || !directReady || !RootController.alive("amneziawg-go")) {
            lastNetwork = signature
            // Keep the AWG process/key and existing session on address change when possible.
            if (!RootController.alive("amneziawg-go")) {
                if (!ensureTunForStartup()) return
                RootController.cleanupInterface()
                directReady = false
                try {
                    require(RootController.startComponent("amneziawg-go") && RootController.configureAwg()) { "IPv6 监听组件未就绪" }
                } catch (e: Exception) {
                    runCatching { RootController.stopComponent("amneziawg-go") }
                    directDetail = safeError(e)
                }
            }
            if (RootController.alive("amneziawg-go")) {
                directReady = false
                try {
                    require(RootController.cleanupNat(journal)) { "旧出口规则清理失败" }
                    if (iface == null) {
                        directDetail = "当前没有上网接口"
                    } else {
                        RootController.setupInterface(shared)
                        val warnings = RootController.setupNat(shared, iface, journal)
                        directReady = true
                        directDetail = if (warnings.isEmpty()) "双栈出口规则已安装" else warnings.joinToString("；")
                    }
                } catch (e: Exception) { directDetail = safeError(e) }
            }
        }
        // Initial registration and address-change registration only. No recurring /config
        // handshake or unchanged-address writes. Failed registration is retried.
        if (newIp == null) {
            registeredIpv6 = null
            if (!attemptedEmptyRegistration && RootController.alive("amneziawg-go")) {
                attemptedEmptyRegistration = true
                registration = try {
                    RelayClient(selected).register("", local.awgPublic)
                    "无公网 IPv6；已提交空地址登记"
                } catch (_: Exception) { "无公网 IPv6；中转站未确认清除地址，请从保底入口读取" }
            }
        } else if (registeredIpv6 != newIp && RootController.alive("amneziawg-go")) {
            attemptedEmptyRegistration = false
            try {
                RelayClient(selected).register(newIp, local.awgPublic)
                registeredIpv6 = newIp
                registration = "公网 IPv6 已登记"
            } catch (e: Exception) { registration = "地址登记失败：${safeError(e)}；将重试" }
        }
        if (stopping) return
        val now = System.currentTimeMillis()
        val connectorReady = cfStarted && ExitProbe.connectorReady()
        if (!connectorReady) cfProbeMs = null
        if (connectorReady && (changed || now - lastCfProbeAt >= 30000)) {
            lastCfProbeAt = now
            cfProbeMs = ExitProbe.fallback(probePassword)
        }
        val direct = directReady && newIp != null &&
            TunnelStateMachine.freshHandshake(RootController.lastHandshake(), now / 1000)
        val fallback = connectorReady && cfProbeMs != null
        val mode = TunnelStateMachine.select(direct, fallback, directReady || cfStarted)
        val keyNote = if (shared.phonePublicKey.isNotBlank() && shared.phonePublicKey != local.awgPublic)
            "；控制端需采用登记的新手机公钥" else ""
        val cfDetail = if (fallback) "保底公网出口自测通过" else if (connectorReady) "保底已连接，出口尚未验证" else "保底连接中"
        status(mode, "$registration；$directDetail；$cfDetail$keyNote", direct, connectorReady, cfProbeMs)
    }

    private fun ensureCloudflare(shared: SharedConfig) {
        if (!RootController.alive("sing-box")) {
            runCatching { RootController.stopComponent("cloudflared") }
            cfStarted = false
            cfProbeMs = null
            if (!RootController.startComponent("sing-box")) return
        }
        if (!RootController.alive("cloudflared")) {
            cfProbeMs = null
            lastCfProbeAt = 0L
            // Token is only held in this service's memory and a transient private token file.
            // It is never persisted in settings or included in logs/status.
            privateFile("cloudflared.conf", shared.tunnelToken)
            cfStarted = RootController.startComponent("cloudflared")
        } else cfStarted = true
        if (cfStarted && ExitProbe.connectorReady()) File(runtime, "cloudflared.conf").delete()
    }

    private fun cleanup(failureCode: String? = null, failureDetail: String? = null) {
        if (cleaned) return
        cleaned = true
        runCatching { peerServer?.close() }; peerServer = null
        // If root was denied, do not run additional su commands or erase old
        // process/NAT cleanup journals that require root for safe recovery.
        val clean = !rootAuthorized || runCatching { RootController.stopAll(journal) }.getOrDefault(false)
        if (rootAuthorized && clean) runtime.listFiles()?.forEach { it.delete() }
        settings = null; keys = null; config = null; probePassword = ""; cfProbeMs = null
        prefs.edit().putBoolean("running", false)
            .putString("mode", if (failureCode == null) TunnelMode.STOPPED.name else TunnelMode.FAILED.name)
            .putString("failure_code", failureCode)
            .putString("failure_detail", if (failureCode == "tun_device_failed") failureDetail else null)
            .putString("detail", failureDetail ?: if (clean) "服务已停止" else "服务已停止，出口规则清理需检查")
            .remove("ipv6").putBoolean("direct_ready", false).putBoolean("cf_connected", false)
            .putLong("cf_probe", -1L).apply()
    }

    private fun status(mode: TunnelMode, detail: String, direct: Boolean = false,
                       cf: Boolean = false, latency: Long? = null) {
        prefs.edit().putString("mode", mode.name).putString("detail", detail).putString("ipv6", ipv6)
            .putString("registration", registration).putBoolean("direct_ready", direct)
            .putBoolean("cf_connected", cf).putLong("cf_probe", latency ?: -1L).apply()
        if (!stopping) {
            val visible = when (mode) {
                TunnelMode.STOPPED -> "已关闭"
                TunnelMode.STARTING -> "启动中"
                TunnelMode.DIRECT_IPV6, TunnelMode.FALLBACK -> "已开启"
                TunnelMode.WAITING -> "连接中"
                TunnelMode.FAILED -> "启动失败"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(visible))
        }
    }
    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tunnel_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).build()
    }
    companion object {
        const val ACTION_START = "org.example.androidtunnel.START"
        const val ACTION_STOP = "org.example.androidtunnel.STOP"
        const val ACTION_BOOT_START = "org.example.androidtunnel.BOOT_START"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION = 1
        private const val FAILURE_NOTIFICATION = 2
        fun start(context: Context, fromBoot: Boolean = false) {
            context.startForegroundService(Intent(context, TunnelService::class.java)
                .setAction(if (fromBoot) ACTION_BOOT_START else ACTION_START))
        }
        fun stop(context: Context) { context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP)) }
    }
}
