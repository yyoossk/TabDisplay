package com.yyoossk.tabdisplay

import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * PC との接続。切れたら自動でつなぎ直す（close() されるまで）。
 */
class Connection(
    private val target: Target,
    private val listener: Listener,
) {
    interface Listener {
        /** 送る画面の大きさ（偶数・デコーダーで再生できる大きさ） */
        fun currentSize(): Pair<Int, Int>
        fun dpi(): Int
        fun onConnecting(host: String)
        fun onConnected(host: String)
        fun onConfig(w: Int, h: Int, fps: Int, codec: String, vps: ByteArray?, sps: ByteArray, pps: ByteArray)
        fun onVideo(data: ByteArray, offset: Int, length: Int, pcTimeUs: Long, flags: Int)
        fun onStatus(s: String)
        fun onError(e: String)
        fun onLost(willRetry: Boolean)
        fun onAudioConfig(rate: Int, channels: Int)
        fun onAudio(data: ByteArray)
        fun deviceId(): String
        fun inputOnly(): Boolean
        fun onArea(w: Int, h: Int)
        fun onClip(text: String)
        fun onFileBegin(id: Int, name: String, size: Long)
        fun onFileData(id: Int, data: ByteArray)
        fun onFileEnd(id: Int)
    }

    @Volatile private var closed = false
    @Volatile private var socket: Socket? = null
    @Volatile private var tlsFailed = false
    @Volatile private var out: DataOutputStream? = null
    private var thread: Thread? = null
    private var pinger: Thread? = null

    /** PC の時計 − タブレットの時計（μs）。ping で求める */
    @Volatile var clockOffsetUs = 0L
        private set
    @Volatile var rttUs = 0L
        private set

    fun start() {
        thread = Thread({ runLoop() }, "connection").also { it.start() }
        pinger = Thread({ pingLoop() }, "ping").also { it.isDaemon = true; it.start() }
    }

    private fun runLoop() {
        var failures = 0
        while (!closed) {
            var connected = false
            for (host in orderedHosts()) {
                if (closed) return
                listener.onConnecting(host)
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 4 * 1024 * 1024
                    s.connect(InetSocketAddress(host, target.port), 3000)
                    // 通信を暗号化する。相手の証明書が QR で受け取った指紋と同じか確かめる
                    s.soTimeout = 8000
                    val tls = wrapTls(s, host)
                    tls.startHandshake()
                    tls.soTimeout = 0
                    socket = tls
                    lastGoodHost = host
                    connected = true
                    failures = 0
                    session(tls, host)
                } catch (e: javax.net.ssl.SSLHandshakeException) {
                    Log.i(TAG, "tls $host: $e")
                    if (tlsFailed) {
                        closed = true
                        listener.onError("tls")
                        return
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "connect/session $host: $e")
                } finally {
                    try { s.close() } catch (_: Exception) {}
                    socket = null
                    out = null
                }
                if (connected) break
            }
            if (closed) return
            failures++
            // USB は PC が adb で張り直すので早めに、Wi-Fi は少し待つ
            listener.onLost(true)
            val wait = if (failures < 3) 700L else 2000L
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    @Volatile private var lastGoodHost: String? = null

    private fun wrapTls(plain: Socket, host: String): SSLSocket {
        val want = target.fp.lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no cert")
                val got = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (want.isEmpty() || got != want) {
                    tlsFailed = true
                    throw CertificateException("fingerprint mismatch")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        val ssl = ctx.socketFactory.createSocket(plain, host, target.port, true) as SSLSocket
        // Android 6 でも TLS 1.2 を使う
        ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        return ssl
    }

    /** Wi-Fi で見つかった今の IP → 前にうまくいった IP → QR の IP の順に試す */
    private fun orderedHosts(): List<String> {
        val list = ArrayList<String>()
        if (!target.usb) Discovery.get(target.pcId)?.let { list.add(it.host) }
        lastGoodHost?.let { list.add(it) }
        list.addAll(target.hosts)
        return list.distinct()
    }

    @Throws(IOException::class)
    private fun session(s: Socket, host: String) {
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16 * 1024))
        out = o
        sendHello()
        listener.onConnected(host)
        while (!closed) {
            val m = Protocol.read(input)
            when (m.type) {
                Protocol.P_VIDEO -> {
                    if (m.data.size < 9) continue
                    var t = 0L
                    for (i in 0 until 8) t = (t shl 8) or (m.data[i].toLong() and 0xff)
                    val flags = m.data[8].toInt() and 0xff
                    // PC の時刻のまま渡す（遅れは表示したときに clockOffsetUs で計算する）
                    listener.onVideo(m.data, 9, m.data.size - 9, t, flags)
                }
                Protocol.P_CONFIG -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    val vps = j.optString("vps", "")
                    listener.onConfig(
                        j.getInt("w"), j.getInt("h"), j.optInt("fps", 60),
                        j.optString("codec", "h264"),
                        if (vps.isNotEmpty() && vps != "null") Base64.decode(vps, Base64.NO_WRAP) else null,
                        Base64.decode(j.getString("sps"), Base64.NO_WRAP),
                        Base64.decode(j.getString("pps"), Base64.NO_WRAP),
                    )
                }
                Protocol.P_PONG -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    val t0 = j.getLong("tab")
                    val pc = j.getLong("pc")
                    val t1 = VideoDecoder.nowUs()
                    val rtt = t1 - t0
                    // 往復の短いときの値を優先して、ゆっくり合わせる
                    val off = pc - (t0 + t1) / 2
                    if (rttUs == 0L || rtt < rttUs * 2) {
                        clockOffsetUs = if (rttUs == 0L) off else (clockOffsetUs * 7 + off) / 8
                    }
                    rttUs = if (rttUs == 0L) rtt else (rttUs * 7 + rtt) / 8
                }
                Protocol.P_STATUS -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    listener.onStatus(j.optString("s"))
                }
                Protocol.P_AUDIO_CFG -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    listener.onAudioConfig(j.optInt("rate", 48000), j.optInt("ch", 2))
                }
                Protocol.P_AUDIO -> listener.onAudio(m.data)
                Protocol.P_CLIP -> listener.onClip(JSONObject(String(m.data, Charsets.UTF_8)).optString("s"))
                Protocol.P_FILE_BEGIN -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    listener.onFileBegin(j.optInt("id"), j.optString("name", "file"), j.optLong("size"))
                }
                Protocol.P_FILE_DATA -> if (m.data.size >= 4) {
                    val id = ((m.data[0].toInt() and 0xff) shl 24) or ((m.data[1].toInt() and 0xff) shl 16) or
                        ((m.data[2].toInt() and 0xff) shl 8) or (m.data[3].toInt() and 0xff)
                    listener.onFileData(id, m.data)
                }
                Protocol.P_FILE_END -> listener.onFileEnd(JSONObject(String(m.data, Charsets.UTF_8)).optInt("id"))
                Protocol.P_AREA -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    listener.onArea(j.optInt("w", 16), j.optInt("h", 9))
                }
                Protocol.P_ERROR -> {
                    val j = JSONObject(String(m.data, Charsets.UTF_8))
                    listener.onError(j.optString("e"))
                    if (j.optString("e") == "token") {
                        closed = true
                    }
                    return
                }
            }
        }
    }

    private fun sendHello() {
        val (w, h) = listener.currentSize()
        val j = JSONObject()
        j.put("token", target.token)
        j.put("id", listener.deviceId())
        j.put("inputOnly", listener.inputOnly())
        j.put("hevc", VideoDecoder.canHevc(w, h))
        j.put("name", (Build.MANUFACTURER + " " + Build.MODEL).trim())
        j.put("w", w)
        j.put("h", h)
        j.put("dpi", listener.dpi())
        j.put("sdk", Build.VERSION.SDK_INT)
        j.put("ver", Protocol.VERSION)
        j.put("app", BuildConfigCompat.versionCode)
        send(Protocol.T_HELLO, j)
    }

    private fun pingLoop() {
        while (!closed) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            if (out != null) send(Protocol.T_PING, JSONObject().put("tab", VideoDecoder.nowUs()))
        }
    }

    fun send(type: Int, j: JSONObject) = send(type, j.toString().toByteArray(Charsets.UTF_8))

    fun send(type: Int, data: ByteArray = ByteArray(0)) {
        val o = out ?: return
        try {
            Protocol.write(o, type, data)
        } catch (e: IOException) {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** UI スレッドから送るとき用（ネットワーク処理をメインスレッドでしない） */
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, ByteArray>>()
    private val sender = Thread({
        while (!closed) {
            val p = try { sendQueue.take() } catch (_: InterruptedException) { break }
            send(p.first, p.second)
        }
    }, "sender").also { it.isDaemon = true; it.start() }

    fun post(type: Int, j: JSONObject) {
        sendQueue.offer(Pair(type, j.toString().toByteArray(Charsets.UTF_8)))
    }

    fun post(type: Int) {
        sendQueue.offer(Pair(type, ByteArray(0)))
    }

    fun close() {
        if (closed) return
        val o = out
        closed = true
        Thread {
            if (o != null) try { Protocol.write(o, Protocol.T_BYE, ByteArray(0)) } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }.start()
        thread?.interrupt()
        pinger?.interrupt()
        sender.interrupt()
    }

    val isClosed get() = closed

    companion object {
        private const val TAG = "Connection"
    }
}

/** BuildConfig を有効にしなくても版を送れるように */
object BuildConfigCompat {
    var versionCode: Int = 0
}
