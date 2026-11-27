package org.example.androidtunnel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import org.example.androidtunnel.security.SecretStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsSanitizer {
    private val uuidPattern = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val base64KeyPattern = Regex("\\b[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/])")
    private val secretAssignment = Regex("(?i)(\\b(?:password|passwd|token|secret|private[_ -]?key|vless[_ -]?uuid|hysteria[_ -]?password|easytier[_ -]?secret)\\b\\s*[:=]\\s*)([^\\s,;]+)")
    fun sanitize(text: String, secrets: Collection<String> = emptyList()): String {
        var safe = text
        secrets.filter { it.isNotEmpty() }.distinct().forEach { secret ->
            safe = safe.replace(secret, "[已脱敏]")
        }
        safe = uuidPattern.replace(safe, "[UUID已脱敏]")
        safe = base64KeyPattern.replace(safe, "[密钥已脱敏]")
        safe = secretAssignment.replace(safe) { match -> match.groupValues[1] + "present" }
        return safe
    }
    fun fileName(version: String, timestamp: String): String {
        val safeVersion = version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(24).ifBlank { "unknown" }
        val safeTime = timestamp.filter { it.isDigit() }.take(14).padEnd(14, '0')
        return "清连-诊断-$safeVersion-$safeTime.txt"
    }
}

object DiagnosticsExporter {
    private val componentNames = ComponentAssets.names
    private val processNames = listOf("amneziawg-go", "sing-box", "cloudflared")

    fun export(context: Context, onDone: (String) -> Unit) {
        Thread {
            val result = runCatching { create(context.applicationContext) }
            val message = result.fold({ it }, { "诊断导出失败：${it.javaClass.simpleName}" })
            android.os.Handler(context.mainLooper).post { onDone(message) }
        }.start()
    }

    private fun create(context: Context): String {
        val now = Date()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionName = packageInfo.versionName ?: "unknown"
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val fileName = DiagnosticsSanitizer.fileName(versionName, stamp)
        val prefs = context.getSharedPreferences("tunnel_status", Context.MODE_PRIVATE)
        val sections = mutableListOf<String>()
        val secrets = mutableListOf<String>()
        val secretStore = SecretStore(context)
        listOf("relay_password", "awg_private", "reality_private").forEach { key ->
            runCatching { secretStore.get(key) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(secrets::add)
        }
        val runtime = File(context.filesDir, "runtime")
        val tokenFile = File(runtime, "cloudflared.conf")
        if (tokenFile.isFile) runCatching { tokenFile.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(secrets::add)
        val singConfig = File(runtime, "sing-box.json")
        val singText = runCatching { if (singConfig.isFile) singConfig.readText() else "" }.getOrDefault("")
        val vlessPresent = Regex("(?i)\"uuid\"\\s*:\\s*\"[0-9a-f-]{36}\"").containsMatchIn(singText)

        sections += section(1, "设备与版本") {
            line("App versionName", versionName)
            line("App versionCode", versionCode.toString())
            line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            line("手机型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            line("ABI", Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "未获取：系统未报告 ABI" })
            line("构建时间", "未获取：当前 APK 未嵌入构建时间")
            line("导出时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(now))
        }
        sections += section(2, "服务状态") {
            val keys = listOf("running", "mode", "detail", "registration", "ipv6", "direct_ready",
                "cf_connected", "cf_probe", "failure_code", "failure_detail", "config_status", "config_time")
            keys.forEach { key ->
                val value = when (key) {
                    "running", "direct_ready", "cf_connected" -> prefs.all[key]?.toString() ?: "未获取：SharedPreferences 无此项"
                    "cf_probe", "config_time" -> {
                        val raw = prefs.all[key]
                        if (raw is Number) if (key == "config_time" && raw.toLong() > 0)
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(raw.toLong()))
                        else raw.toString() else "未获取：SharedPreferences 无此项"
                    }
                    else -> prefs.getString(key, null) ?: "未获取：SharedPreferences 无此项"
                }
                line(key, value)
            }
        }

        val rootResult = runCatching { RootController.diagnosticRootUid() }
        val rootOk = rootResult.getOrNull()?.let { StartupPolicy.rootGranted(it.exitCode, it.output) } == true
        sections += section(3, "Root") {
            line("id -u 输出", rootResult.getOrNull()?.output?.trim().orUnavailable(rootResult.exceptionOrNull()))
            line("Root 是否通过", if (rootOk) "是" else "否")
        }

        sections += section(4, "组件文件（6 个）") {
            componentNames.forEach { name ->
                line(name, runCatching {
                    val assetExists = runCatching { context.assets.open("arm64/$name").use { true } }.getOrDefault(false)
                    val assetDigest = if (assetExists) context.assets.open("arm64/$name").use { input ->
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(16384)
                        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf,0,n) }
                        md.digest().joinToString("") { "%02x".format(it) }
                    } else ""
                    val expected = context.assets.open("arm64/SHA256SUMS").bufferedReader().use {
                        ComponentAssets.parseManifest(it.readText())[name].orEmpty()
                    }
                    val copied = File(context.filesDir, "components/$name")
                    val localCopied = copied.isFile
                    val localHash = if (localCopied) ComponentAssets.sha256(copied) else ""
                    val rootInfo = if (rootOk) RootController.diagnosticComponent(name).output else ""
                    val rootMap = parsePairs(rootInfo)
                    "assets=${yesNo(assetExists)}; app复制=${yesNo(localCopied)}; root组件目录=${rootMap["installed_exists"] ?: "未获取：Root 检查失败"}; " +
                        "SHA一致=${yesNo(assetDigest.isNotBlank() && expected.isNotBlank() && assetDigest == expected && (!localCopied || localHash == expected) && (rootMap["installed_sha256"] == expected))}; " +
                        "可执行=${rootMap["installed_executable"] ?: if (localCopied) yesNo(copied.canExecute()) else "未获取：文件不存在"}; " +
                        "assets大小=${if (assetExists) assetSize(context, name) else "不存在"}; app复制大小=${if (localCopied) copied.length() else "不存在"}; " +
                        "root组件目录大小=${rootMap["installed_size"] ?: "未获取：Root 检查失败"}"
                }.getOrElse { "未获取：${it.javaClass.simpleName}" })
            }
        }

        sections += section(5, "组件进程") {
            processNames.forEach { name ->
                line(name, if (!rootOk) "未获取：Root 未通过" else runCatching {
                    val result = RootController.diagnosticProcess(name)
                    if (result.exitCode != 0) "未获取：root 命令退出 ${result.exitCode}"
                    else DiagnosticsSanitizer.sanitize(result.output.trim(), secrets).replace("\n", "; ")
                }.getOrElse { "未获取：${it.javaClass.simpleName}" })
            }
        }

        sections += section(6, "二进制冒烟测试（--help，5 秒超时）") {
            componentNames.forEach { name ->
                line(name, if (!rootOk) "未获取：Root 未通过" else runCatching {
                    val result = RootController.diagnosticSmoke(name)
                    val timedOut = result.exitCode == 124 || result.exitCode == 137
                    val output = result.output.lineSequence().take(20).joinToString("\n").ifBlank { "（无输出）" }
                    "退出码=${result.exitCode}${if (timedOut) "（超时）" else ""}\n${DiagnosticsSanitizer.sanitize(output, secrets)}"
                }.getOrElse { "失败：${it.message ?: it.javaClass.simpleName}" } )
            }
        }

        val tun = if (rootOk) runCatching { RootController.diagnosticTun().output } else null
        sections += section(7, "TUN 设备") {
            line("Root 命令", tun?.let { DiagnosticsSanitizer.sanitize(it, secrets) } ?: "未获取：Root 未通过")
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = runCatching { cm.activeNetwork?.let(cm::getLinkProperties) }.getOrNull()
        sections += section(8, "网络") {
            val globals = lp?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress?.substringBefore('%') }
                .filter(V2::globalIpv6)
            line("全局 IPv6", globals.joinToString(", ").ifBlank { "未获取：当前无全局 IPv6 地址" })
            line("上网接口", lp?.interfaceName ?: "未获取：无活动网络")
            line("IPv6 默认路由", if (rootOk) runCatching { RootController.diagnosticRoutes().output.substringAfter("IPv6 default route:").substringBefore("IPv4 default route:").trim().ifBlank { "无" } }.getOrElse { "未获取：${it.javaClass.simpleName}" } else "未获取：Root 未通过")
            line("IPv4 默认路由", if (rootOk) runCatching { RootController.diagnosticRoutes().output.substringAfter("IPv4 default route:").trim().ifBlank { "无" } }.getOrElse { "未获取：${it.javaClass.simpleName}" } else "未获取：Root 未通过")
            line("DNS", lp?.dnsServers?.joinToString { it.hostAddress.orEmpty() }?.ifBlank { "未获取：无 DNS 信息" } ?: "未获取：无活动网络")
        }

        sections += section(9, "中继") {
            line("config_status", prefs.getString("config_status", null) ?: "未获取：SharedPreferences 无此项")
            val relay = runCatching { RelayRepository.settings(context).relay }.getOrDefault(V2.RELAY)
            line("relay 地址", relay)
            line("/health HTTP 状态码", healthStatus(relay))
            line("口令", "present: ${yesNo(runCatching { RelayRepository.settings(context).password.isNotBlank() }.getOrDefault(false))}")
        }

        sections += section(10, "NAT/回滚日志") {
            val journal = File(context.filesDir, "v2-nat-undo")
            val boot = File(context.filesDir, "v2-nat-undo.boot")
            line("v2-nat-undo 存在", yesNo(journal.isFile))
            line("内容", if (journal.isFile) runCatching { journal.readText().take(12000) }.getOrElse { "未获取：${it.javaClass.simpleName}" } else "未获取：日志文件不存在")
            line("boot id 文件存在", yesNo(boot.isFile))
            line("boot id 内容", if (boot.isFile) runCatching { boot.readText().trim().take(80) }.getOrElse { "未获取：${it.javaClass.simpleName}" } else "未获取：文件不存在")
        }

        sections += section(11, "最近错误") {
            line("failure_detail", prefs.getString("failure_detail", null) ?: prefs.getString("detail", null) ?: "未获取：无错误详情")
            line("failure_code", prefs.getString("failure_code", null) ?: "未获取：无失败代码")
            line("最近状态变化", "未获取：当前版本未持久化状态变化历史")
        }

        val presence = buildString {
            appendLine("敏感字段存在性（仅标记，不输出值）")
            appendLine("AWG 私钥：present: ${yesNo(secrets.any { it == runCatching { secretStore.get("awg_private") }.getOrNull() })}")
            appendLine("Reality 私钥：present: ${yesNo(secrets.any { it == runCatching { secretStore.get("reality_private") }.getOrNull() })}")
            appendLine("Cloudflare Tunnel token：present: ${if (tokenFile.isFile && tokenFile.length() > 0) "yes" else "unknown（token 文件不存在，服务内存未读取）"}")
            appendLine("VLESS UUID：present: ${if (vlessPresent) "yes" else "unknown（共享配置未持久化，未读取服务内存）"}")
            appendLine("Hysteria2 密码：present: unknown（未持久化，未读取服务内存）")
            appendLine("EasyTier 密钥：present: unknown（未持久化，未读取服务内存）")
        }
        val full = buildString {
            appendLine("清连诊断信息")
            appendLine("生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(now)}")
            appendLine(presence)
            sections.forEach { appendLine(it); appendLine() }
        }
        val safe = DiagnosticsSanitizer.sanitize(full, secrets)
        val cacheDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val cached = File(cacheDir, fileName)
        cached.writeText(safe, Charsets.UTF_8)
        val downloadPath = saveDownload(context, fileName, safe)
        val shareUri = Uri.parse("content://${context.packageName}.diagnostics/${Uri.encode(fileName)}")
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, "清连诊断信息 $versionName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(context.contentResolver, fileName, shareUri)
            }
            context.startActivity(Intent.createChooser(send, "分享诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "诊断信息已生成；Download 副本：$downloadPath"
        } catch (_: Exception) {
            "诊断信息已生成；分享面板不可用，已保存到：$downloadPath"
        }
    }

    private fun saveDownload(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建 Download 文件")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入 Download 文件")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
            return "Download/$name"
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) check(dir.mkdirs()) { "无法创建 Download 目录" }
        File(dir, name).writeText(text, Charsets.UTF_8)
        return File(dir, name).absolutePath
    }

    private fun healthStatus(relay: String): String = runCatching {
        val uri = URI(relay.trimEnd('/') + "/health")
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.instanceFollowRedirects = false
            "HTTP ${conn.responseCode}"
        } finally { conn.disconnect() }
    }.getOrElse { "未获取：${it.javaClass.simpleName}" }

    private fun parsePairs(text: String): Map<String, String> = text.lineSequence().mapNotNull {
        val i = it.indexOf('=')
        if (i > 0) it.substring(0, i) to it.substring(i + 1).trim() else null
    }.toMap()
    private fun assetSize(context: Context, name: String): String = runCatching {
        context.assets.open("arm64/$name").use { input -> var n=0L; val b=ByteArray(16384); while(true){val r=input.read(b);if(r<0)break;n+=r}; n.toString() }
    }.getOrDefault("未获取")
    private fun yesNo(value: Boolean) = if (value) "是" else "否"
    private fun String?.orUnavailable(error: Throwable?): String = this?.takeIf { it.isNotBlank() } ?: "未获取：${error?.javaClass?.simpleName ?: "无输出"}"
    private fun section(number: Int, title: String, body: SectionBuilder.() -> Unit): String =
        runCatching { SectionBuilder(number, title).apply(body).toString() }
            .getOrElse { "【$number. $title】\n未获取：${it.javaClass.simpleName}" }
    private class SectionBuilder(private val number: Int, private val title: String) {
        private val lines = mutableListOf<String>()
        fun line(key: String, value: String) { lines += "$key：$value" }
        override fun toString() = buildString { appendLine("【$number. $title】"); lines.forEach { appendLine(it) } }.trimEnd()
    }
}

/** Read-only provider for the generated cache copy; URI paths are restricted to safe filenames. */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: error("missing filename")
        require(name.matches(Regex("清连-诊断-[A-Za-z0-9.-]{1,24}-[0-9]{14}\\.txt")))
        return File(requireNotNull(context).cacheDir, "diagnostics/$name").also { require(it.isFile) }
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> f.name
            OpenableColumns.SIZE -> f.length()
            else -> null
        } }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
