package org.example.androidtunnel

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ComponentAsset(val name: String, val sha256: String)

object ComponentAssets {
    val names = listOf("amneziawg-go", "awg", "sing-box", "easytier-core", "cloudflared", "wstunnel")

    fun parseManifest(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val match = Regex("^([a-fA-F0-9]{64})\\s+([A-Za-z0-9._-]+)$").matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[2] to match.groupValues[1].lowercase()
        }.toMap()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String = FileInputStream(file).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class ComponentInstallResult(val files: Map<String, File>, val missing: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && files.size == ComponentAssets.names.size
}

class ComponentManager(private val context: Context) {
    fun prepare(): ComponentInstallResult {
        val out = File(context.filesDir, "components").apply { mkdirs() }
        val manifest = runCatching {
            context.assets.open("arm64/SHA256SUMS").bufferedReader().use { ComponentAssets.parseManifest(it.readText()) }
        }.getOrDefault(emptyMap())
        val files = linkedMapOf<String, File>()
        val missing = mutableListOf<String>()
        for (name in ComponentAssets.names) {
            val target = File(out, name)
            val valid = runCatching {
                val expected = manifest[name] ?: error("digest missing")
                val actual = context.assets.open("arm64/$name").use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                if (!actual.equals(expected, ignoreCase = true)) error("asset digest mismatch")
                if (!target.isFile || !ComponentAssets.sha256(target).equals(expected, true)) {
                    context.assets.open("arm64/$name").use { input -> target.outputStream().use(input::copyTo) }
                }
                target.setReadable(true, true)
                target.setExecutable(true, true)
                target.isFile && target.canExecute() && ComponentAssets.sha256(target).equals(expected, true)
            }.getOrDefault(false)
            if (valid) files[name] = target else missing += name
        }
        return ComponentInstallResult(files, missing)
    }
}

