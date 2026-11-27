package com.yyoossk.tabdisplay

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.ssl.SSLServerSocket

/**
 * 送る側：この端末の画面を、ほかのタブレットの TabDisplay に映す（PC なし）。
 *  画面の取り込み（MediaProjection）→ H.264 圧縮（MediaCodec）→ 暗号化した通信で送る。
 *  受ける側からの操作は、ユーザー補助（TouchService）がオンなら、この端末のタップ・スクロールになる。
 *  音は Android 10 以降で、ほかのアプリの音を送る。
 */
class SenderService : Service() {

    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO = "audio"
        const val ACTION_STOP = "stop"
        private const val TAG = "Sender"
        private const val NOTIF_ID = 38500
        @Volatile var running = false
            private set
        @Volatile var status: String = ""
            private set
        var onStatus: ((String) -> Unit)? = null

        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var server: SSLServerSocket? = null
    private var client: Client? = null
    private var beacon: Thread? = null
    @Volatile private var stopped = false
    private var withAudio = false
    private lateinit var token: String
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 画面の取り込みの前に、前面サービスにしておく（Android 10 以降の決まり）
        startForegroundCompat()
        val result = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (result != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        withAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(result, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        token = SenderTls.token(this)
        running = true
        stopped = false
        Thread({ acceptLoop() }, "sender-accept").start()
        beacon = Thread({ beaconLoop() }, "sender-beacon").also { it.isDaemon = true; it.start() }
        setStatus(getString(R.string.sender_waiting))
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("sender", getString(R.string.sender_title), NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, SenderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "sender") else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.sender_title))
            .setContentText(getString(R.string.sender_running))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, getString(R.string.sender_stop), stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(s: String) {
        status = s
        main.post { onStatus?.invoke(s) }
    }

    // ---------- 受け付け ----------

    private fun acceptLoop() {
        try {
            val ss = SenderTls.serverSocket(Protocol.PORT)
            server = ss
            while (!stopped) {
                val s = ss.accept()
                Thread({ handle(s) }, "sender-client").start()
            }
        } catch (e: Exception) {
            if (!stopped) {
                Log.w(TAG, "accept: $e")
                setStatus(getString(R.string.sender_error, e.message ?: ""))
            }
        }
    }

    private fun handle(s: Socket) {
        val c = Client(s)
        try {
            s.tcpNoDelay = true
            s.soTimeout = 8000
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val first = Protocol.read(input)
            if (first.type != Protocol.T_HELLO) return
            val hello = JSONObject(String(first.data, Charsets.UTF_8))
            if (hello.optString("token") != token) {
                c.send(Protocol.P_ERROR, JSONObject().put("e", "token"))
                return
            }
            s.soTimeout = 0
            synchronized(this) {
                client?.close()
                client = c
            }
            setStatus(getString(R.string.sender_connected, hello.optString("name", "")))
            c.start(hello.optInt("w", 1920), hello.optInt("h", 1080))
            while (!stopped && !c.closed) {
                val m = Protocol.read(input)
                when (m.type) {
                    Protocol.T_PING -> {
                        val j = JSONObject(String(m.data, Charsets.UTF_8))
                        c.send(Protocol.P_PONG, JSONObject().put("tab", j.optLong("tab")).put("pc", nowUs()))
                    }
                    Protocol.T_IDR -> c.requestKeyFrame()
                    Protocol.T_INPUT -> TouchService.handle(JSONObject(String(m.data, Charsets.UTF_8)))
                    Protocol.T_SIZE -> {
                        val j = JSONObject(String(m.data, Charsets.UTF_8))
                        c.receiverSize(j.optInt("w"), j.optInt("h"))
                    }
                    Protocol.T_BYE -> break
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "client: $e")
        } finally {
            c.close()
            synchronized(this) {
                if (client === c) {
                    client = null
                    if (!stopped) setStatus(getString(R.string.sender_waiting))
                }
            }
        }
    }

    // ---------- 1 台分 ----------

    private inner class Client(private val socket: Socket) {
        private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        @Volatile var closed = false
        private var encoder: MediaCodec? = null
        private var encThread: Thread? = null
        private var audio: AudioRecord? = null
        private var recvW = 1920
        private var recvH = 1080
        private var curW = 0
        private var curH = 0
        private val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                // この端末を回した：縦横を合わせて圧縮をやり直す
                val (w, h) = targetSize()
                if (w != curW || h != curH) Thread { restartEncoder() }.start()
            }
        }

        fun send(type: Int, j: JSONObject) = send(type, j.toString().toByteArray(Charsets.UTF_8))

        fun send(type: Int, data: ByteArray) {
            if (closed) return
            try {
                Protocol.write(out, type, data)
            } catch (e: Exception) {
                close()
            }
        }

        fun start(w: Int, h: Int) {
            recvW = w; recvH = h
            send(Protocol.P_STATUS, JSONObject().put("s", "ok"))
            restartEncoder()
            main.post {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)
            }
            if (withAudio) startAudio()
        }

        fun receiverSize(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            recvW = w; recvH = h
            val (tw, th) = targetSize()
            if (tw != curW || th != curH) Thread { restartEncoder() }.start()
        }

        /** この端末の画面を、受ける側の画面に収まる大きさに（16 の倍数・長い辺は 1920 まで） */
        private fun targetSize(): Pair<Int, Int> {
            val p = Point()
            @Suppress("DEPRECATION")
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
            var bw = recvW
            var bh = recvH
            if ((p.x >= p.y) != (bw >= bh)) { val t = bw; bw = bh; bh = t }
            var s = minOf(1.0, bw.toDouble() / p.x, bh.toDouble() / p.y)
            s = minOf(s, 1920.0 / maxOf(p.x, p.y))
            val w = maxOf(320, (p.x * s).toInt() and 15.inv())
            val h = maxOf(240, (p.y * s).toInt() and 15.inv())
            return Pair(w, h)
        }

        @Synchronized
        fun restartEncoder() {
            if (closed) return
            stopEncoder()
            val (w, h) = targetSize()
            curW = w; curH = h
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            // 画面が止まっていても、0.5 秒ごとに絵を出す（受ける側がつなぎ直したときのため）
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000)
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val enc = MediaCodec.createEncoderByType("video/avc")
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc
            val dpi = resources.displayMetrics.densityDpi
            val v = vd
            if (v == null) {
                vd = projection?.createVirtualDisplay("TabDisplay", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            } else {
                // 取り込み用の画面は作り直さずに、大きさと出力先だけ替える（Android 14 の決まりにも合う）
                v.resize(w, h, dpi)
                v.surface = surface
            }
            encThread = Thread({ drain(enc) }, "sender-enc").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        }

        private var sps: ByteArray? = null
        private var pps: ByteArray? = null

        private fun drain(enc: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (!closed && encoder === enc) {
                val idx = try { enc.dequeueOutputBuffer(info, 50_000) } catch (_: Exception) { break }
                if (idx < 0) continue
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parseConfig(data)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val payload = if (key && sps != null && pps != null)
                            START + sps!! + START + pps!! + data else data
                        sendVideo(payload, key)
                    }
                }
                try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) { break }
            }
        }

        /** 設定（SPS・PPS）を取り出して、受ける側に送る */
        private fun parseConfig(d: ByteArray) {
            val nals = splitNals(d)
            sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
            pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
            if (sps == null || pps == null) return
            send(Protocol.P_CONFIG, JSONObject()
                .put("w", curW).put("h", curH).put("fps", 60)
                .put("sps", Base64.encodeToString(sps, Base64.NO_WRAP))
                .put("pps", Base64.encodeToString(pps, Base64.NO_WRAP)))
        }

        private fun sendVideo(annexB: ByteArray, key: Boolean) {
            val buf = ByteArray(9 + annexB.size)
            var t = nowUs()
            for (i in 7 downTo 0) { buf[i] = (t and 0xff).toByte(); t = t shr 8 }
            buf[8] = if (key) Protocol.F_KEY.toByte() else 0
            System.arraycopy(annexB, 0, buf, 9, annexB.size)
            send(Protocol.P_VIDEO, buf)
        }

        fun requestKeyFrame() {
            try {
                encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {}
        }

        private fun stopEncoder() {
            val e = encoder
            encoder = null
            try { encThread?.join(300) } catch (_: Exception) {}
            try { e?.stop() } catch (_: Exception) {}
            try { e?.release() } catch (_: Exception) {}
        }

        // ---------- 音（Android 10 以降） ----------

        private fun startAudio() {
            if (Build.VERSION.SDK_INT < 29) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val p = projection ?: return
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val rate = 48000
                val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, 19200))
                    .setAudioPlaybackCaptureConfig(cfg).build()
                rec.startRecording()
                audio = rec
                send(Protocol.P_AUDIO_CFG, JSONObject().put("rate", rate).put("ch", 2))
                Thread({
                    val buf = ByteArray(rate * 4 / 100) // 10ms
                    while (!closed && audio === rec) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) send(Protocol.P_AUDIO, buf.copyOf(n))
                    }
                }, "sender-audio").start()
            } catch (e: Exception) {
                Log.w(TAG, "audio: $e")
            }
        }

        fun close() {
            if (closed) return
            closed = true
            main.post {
                try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) } catch (_: Exception) {}
            }
            stopEncoder()
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            audio = null
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ---------- Wi-Fi に「ここにいます」 ----------

    private fun beaconLoop() {
        val payload = JSONObject()
            .put("app", "tabdisplay")
            .put("n", Build.MODEL)
            .put("p", Protocol.PORT)
            .put("i", Target.pcIdOf(token))
            .toString().toByteArray(Charsets.UTF_8)
        while (!stopped) {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), Protocol.DISCOVERY_PORT))
                }
            } catch (_: Exception) {}
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    override fun onDestroy() {
        stopped = true
        running = false
        synchronized(this) { client?.close(); client = null }
        try { server?.close() } catch (_: Exception) {}
        beacon?.interrupt()
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        setStatus(getString(R.string.sender_stopped))
        super.onDestroy()
    }
}

private val START = byteArrayOf(0, 0, 0, 1)

/** Annex-B（00 00 01 / 00 00 00 01 区切り）を NAL ごとに分ける */
fun splitNals(d: ByteArray): List<ByteArray> {
    val list = ArrayList<ByteArray>()
    var i = 0
    var start = -1
    while (i + 3 <= d.size) {
        val sc3 = d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1
        if (sc3) {
            if (start >= 0) {
                var end = i
                if (end > start && d[end - 1].toInt() == 0) end--
                list.add(d.copyOfRange(start, end))
            }
            i += 3
            start = i
        } else {
            i++
        }
    }
    if (start in 0 until d.size) list.add(d.copyOfRange(start, d.size))
    return list
}

/** この端末の IPv4 アドレス（QR に入れる） */
fun localIpv4(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .map { it.hostAddress ?: "" }
        .filter { it.isNotEmpty() }
} catch (_: Exception) { emptyList() }
