package com.yyoossk.tabdisplay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * 送る側（この端末の画面をほかのタブレットに映す）の暗号化。
 * 鍵と証明書は Android の鍵の保管庫（AndroidKeyStore）に作り、外に出さない。
 * 受ける側は QR で受け取った証明書の指紋で、本物か確かめる（PC と同じ仕組み）。
 */
object SenderTls {
    private const val ALIAS = "tabdisplay_sender"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(): KeyStore {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=TabDisplay"))
                .setCertificateNotBefore(start)
                .setCertificateNotAfter(end)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        return ks
    }

    fun certificate(): X509Certificate = ensureKey().getCertificate(ALIAS) as X509Certificate

    /** 受ける側に渡す指紋（小文字 16 進） */
    fun fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(certificate().encoded).joinToString("") { "%02x".format(it) }

    fun serverSocket(port: Int): SSLServerSocket {
        val ks = ensureKey()
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val chain = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = key
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(km), null, SecureRandom())
        val ss = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(port))
        ss.enabledProtocols = ss.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        return ss
    }

    /** 送る側の合言葉（初回に作って保存） */
    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE)
        var t = sp.getString("sender_token", null)
        if (t == null) {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            t = (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
            sp.edit().putString("sender_token", t).apply()
        }
        return t
    }
}
