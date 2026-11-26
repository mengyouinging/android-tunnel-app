package org.example.androidtunnel

import java.net.Socket
import java.net.InetSocketAddress
import java.io.DataInputStream
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import java.net.HttpURLConnection
import java.net.URL

object ExitProbe {
    private const val TARGET = "www.cloudflare.com"
    fun connectorReady(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", V2.METRICS_PORT), 1500)
            socket.soTimeout = 1500
            socket.getOutputStream().write("GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine()?.matches(Regex("HTTP/1\\.[01] 200(?: .*)?")) == true
        }
    }.getOrDefault(false)

    /** A real HTTPS request through the public hostname and VLESS/WS origin.
     * Does not prove moyu-side route selection. */
    fun fallback(password: String): Long? = runCatching {
        val start = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", V2.PROBE_PORT), 3000)
            socket.soTimeout = 6000
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            out.write(byteArrayOf(5,1,2)); out.flush()
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            val user = "probe".toByteArray(Charsets.US_ASCII)
            val pass = password.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            out.flush()
            require(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            val host = TARGET.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5,1,0,3,host.size.toByte()) + host + byteArrayOf(1, 187.toByte()))
            out.flush()
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0)
            val size = when (input.readUnsignedByte()) {
                1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS response invalid")
            }
            input.readFully(ByteArray(size + 2))
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, TARGET, 443, true) as SSLSocket
            tls.use {
                it.soTimeout = 6000
                it.sslParameters = it.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake()
                it.outputStream.write(("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TARGET\r\nUser-Agent: AndroidTunnel/2\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                val reader = it.inputStream.bufferedReader()
                require(reader.readLine()?.matches(Regex("HTTP/1\\.[01] 200(?: .*)?")) == true)
                var found = false
                for (i in 0 until 96) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("ip=") && line.length in 4..100) { found = true; break }
                }
                require(found) { "出口响应不完整" }
            }
        }
        (System.nanoTime() - start) / 1_000_000
    }.getOrNull()
}
