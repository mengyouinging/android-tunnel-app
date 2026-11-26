package org.example.androidtunnel

import android.content.Context
import org.example.androidtunnel.security.SecretStore
import java.security.KeyPairGenerator
import java.util.Base64

data class LocalKeys(val awgPrivate: String, val awgPublic: String, val realityPrivate: String, val realityPublic: String)

object KeyRepository {
    private val b64 = Base64.getEncoder()
    // X25519 standard DER encodings: verify the exact headers rather than slicing arbitrary keys.
    private val privateHeader = byteArrayOf(0x30,0x2e,0x02,0x01,0x00,0x30,0x05,0x06,0x03,0x2b,0x65,0x6e,0x04,0x22,0x04,0x20)
    private val publicHeader = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x6e,0x03,0x21,0x00)
    private fun raw(encoded: ByteArray, header: ByteArray): String {
        require(encoded.size == header.size + 32 && encoded.copyOfRange(0, header.size).contentEquals(header))
        return b64.encodeToString(encoded.copyOfRange(header.size, encoded.size))
    }
    private fun jcaPair(): Pair<String,String> {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        return raw(kp.private.encoded, privateHeader) to raw(kp.public.encoded, publicHeader)
    }
    private fun standard(value: String): String {
        val normal = value.replace('-', '+').replace('_', '/').let { if (it.length == 43) "$it=" else it }
        require(V2.key(normal)) { "已有密钥格式无效" }
        return normal
    }
    @Synchronized fun prepare(context: Context, rootReady: Boolean): LocalKeys {
        val store = SecretStore(context)
        fun pair(privateName: String, publicName: String): Pair<String,String> {
            val prior = store.get(privateName)?.takeIf(String::isNotBlank)
            if (prior != null) {
                val priv = standard(prior)
                val pub = if (rootReady) RootController.publicKey(priv)
                    else store.get(publicName)?.let(::standard) ?: error("已有密钥需在启动服务时推导公钥")
                store.put(publicName, pub)
                return priv to pub
            }
            val generated = runCatching { jcaPair() }.getOrElse {
                require(rootReady) { "当前系统需在首次启动服务时生成密钥" }
                val priv = RootController.generatePrivate()
                priv to RootController.publicKey(priv)
            }
            store.put(privateName, generated.first)
            store.put(publicName, generated.second)
            return generated
        }
        val awg = pair("awg_private", "awg_public")
        val reality = pair("reality_private", "reality_public")
        return LocalKeys(awg.first, awg.second, reality.first, reality.second)
    }
}
