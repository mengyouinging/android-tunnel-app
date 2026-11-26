package org.example.androidtunnel

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object UpdateClient {
    private val checked = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val manualRequested = AtomicBoolean(false)
    private var currentActivity = WeakReference<Activity>(null)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }
    fun fetch(): UpdateInfo {
        val j = HttpJson.get(V2.UPDATE)
        return UpdateInfo(j.getString("versionName"), j.getLong("versionCode"), j.getString("apk_url"),
            j.getLong("apk_size"), j.getString("apk_sha256")).validate()
    }
    fun checkOnce(activity: Activity) {
        currentActivity = WeakReference(activity)
        if (checked.compareAndSet(false, true)) check(activity, manual = false)
    }

    fun checkNow(activity: Activity) {
        currentActivity = WeakReference(activity)
        check(activity, manual = true)
    }

    private fun check(activity: Activity, manual: Boolean) {
        if (downloading.get()) {
            if (manual) Toast.makeText(activity, "正在下载更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (manual) {
            manualRequested.set(true)
            Toast.makeText(activity, "正在检查更新", Toast.LENGTH_SHORT).show()
        }
        if (!checking.compareAndSet(false, true)) return
        val context = activity.applicationContext
        Thread {
            val result = runCatching {
                val info = fetch()
                info to info.newerThan(installedCode(context))
            }
            val info = result.getOrNull()?.first
            val newer = result.getOrNull()?.second == true
            val cached = if (newer && info != null) verifiedCache(context, info) else false
            context.getSharedPreferences("tunnel_status", Context.MODE_PRIVATE).edit()
                .putString("update_status", when {
                    info == null -> "版本检查失败"
                    newer -> "发现新版 ${info.versionName}"
                    else -> "已是当前版本"
                }).apply()
            mainHandler.post {
                checking.set(false)
                val showResult = manualRequested.getAndSet(false)
                val host = currentActivity.get() ?: return@post
                if (host.isFinishing || host.isDestroyed) return@post
                when {
                    info != null && newer -> AlertDialog.Builder(host)
                        .setTitle("发现新版 ${info.versionName}")
                        .setMessage(if (cached) "安装包已下载，是否安装？" else "是否下载更新？")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton(if (cached) "安装" else "下载") { _, _ ->
                            if (cached) install(host) else download(host, info)
                        }.show()
                    showResult -> Toast.makeText(host,
                        if (info == null) "检查更新失败，请稍后重试" else "已是最新版本",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Re-entering the update menu after granting installer permission revalidates the cached APK. */
    private fun verifiedCache(context: Context, info: UpdateInfo): Boolean = runCatching {
        val file = File(context.cacheDir, "updates/update.apk")
        require(file.isFile && file.length() == info.apkSize)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(info.sha256, true))
        validatePackage(context, file, info)
        true
    }.getOrDefault(false)

    private fun download(activity: Activity, info: UpdateInfo) {
        if (!downloading.compareAndSet(false, true)) return
        Toast.makeText(activity, "正在下载更新", Toast.LENGTH_SHORT).show()
        Thread {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            val partial = File(dir, "update.part")
            val target = File(dir, "update.apk")
            val result = runCatching {
                info.validate()
                require(info.newerThan(installedCode(activity)))
                target.delete(); partial.delete()
                val con = URI(info.apkUrl).toURL().openConnection() as HttpURLConnection
                try {
                    con.instanceFollowRedirects = false
                    con.connectTimeout = 15000; con.readTimeout = 20000
                    con.setRequestProperty("User-Agent", V2.UA)
                    con.setRequestProperty("Accept-Encoding", "identity")
                    require(con.responseCode == 200)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    con.inputStream.use { input ->
                        partial.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                size += n
                                require(size <= info.apkSize)
                                out.write(buffer, 0, n); digest.update(buffer, 0, n)
                            }
                        }
                    }
                    require(size == info.apkSize)
                    require(digest.digest().joinToString("") { "%02x".format(it) }.equals(info.sha256, true))
                } finally { con.disconnect() }
                validatePackage(activity, partial, info)
                require(partial.renameTo(target))
            }
            if (result.isFailure) { partial.delete(); target.delete() }
            downloading.set(false)
            mainHandler.post {
                val host = currentActivity.get() ?: return@post
                if (host.isFinishing || host.isDestroyed) return@post
                if (result.isSuccess) AlertDialog.Builder(host).setTitle("更新已下载")
                    .setMessage("是否安装更新？")
                    .setNegativeButton("暂不安装", null)
                    .setPositiveButton("安装") { _, _ -> install(host) }.show()
                else AlertDialog.Builder(host).setTitle("下载或校验失败")
                    .setMessage("更新文件无效或下载未完成，请重试。")
                    .setPositiveButton("确定", null).show()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(context: Context, file: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(candidate.packageName == context.packageName && code == info.versionCode && candidate.versionName == info.versionName)
        fun signatures(p: android.content.pm.PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        val current = signatures(installed)
        require(current.isNotEmpty() && signatures(candidate) == current)
    }
    private fun install(activity: Activity) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("需要系统安装权限")
                .setMessage("请允许安装应用，返回后点击“更新”继续。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开设置") { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                }.show()
            return
        }
        val uri = Uri.parse("content://${activity.packageName}.updates/update.apk")
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(activity, "系统安装器不可用", Toast.LENGTH_LONG).show() }
    }

}

/** No filesystem path is taken from the URI. Only a previously verified APK can be shared. */
class UpdateProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        require(uri.path == "/update.apk")
        return File(requireNotNull(context).cacheDir, "updates/update.apk")
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> "android-tunnel-update.apk"
            OpenableColumns.SIZE -> file(uri).length()
            else -> null
        } }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
