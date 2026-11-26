package org.example.androidtunnel

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

/** Loopback-only address discovery through the authenticated Cloudflare data channel.
 * GET /peer requires Authorization: Bearer <relay password>. Never returns private keys. */
class PeerInfoServer(private val password: String, private val snapshot: () -> JSONObject) : AutoCloseable {
    private val socket = ServerSocket(V2.PEER_PORT, 8, InetAddress.getByName("127.0.0.1"))
    private val thread = Executors.newSingleThreadExecutor()
    init {
        thread.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use { runCatching { handle(it) } }
            }
        }
    }
    private fun line(input: BufferedInputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 4096) {
            val b = input.read()
            if (b == -1) return null
            if (b == 10) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
        }
        error("Header too long")
    }
    private fun handle(client: Socket) {
        client.soTimeout = 2000
        val input = BufferedInputStream(client.getInputStream())
        val request = line(input) ?: return
        var auth = ""
        for (i in 0 until 32) {
            val header = line(input) ?: return
            if (header.isEmpty()) break
            if (header.substringBefore(':').equals("Authorization", true)) auth = header.substringAfter(':').trim()
            if (i == 31) return
        }
        val authorized = MessageDigest.isEqual(auth.toByteArray(Charsets.UTF_8), "Bearer $password".toByteArray(Charsets.UTF_8))
        val code: String
        val body: String
        if (!authorized) { code = "401 Unauthorized"; body = """{"ok":false}""" }
        else if (request != "GET /peer HTTP/1.1" && request != "GET /peer HTTP/1.0") {
            code = "404 Not Found"; body = """{"ok":false}"""
        } else { code = "200 OK"; body = snapshot().toString() }
        val data = body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(data); flush()
        }
    }
    override fun close() { socket.close(); thread.shutdownNow() }
}
