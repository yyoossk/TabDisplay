package com.yyoossk.tabdisplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 を Surface に直接描く。遅れを減らすため：
 *  - 届いた映像はためずにすぐデコーダーへ渡す
 *  - 出てきた絵は時刻を待たずにすぐ表示する（releaseOutputBuffer(i, true)）
 *  - 低遅延モード（Android 11 以降の標準キーと、各社の独自キー）をオンにする
 */
class VideoDecoder(
    private val surface: Surface,
    val width: Int,
    val height: Int,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val listener: Listener,
    /** "video/avc"（H.264）か "video/hevc"（H.265） */
    private val mime: String = MIME,
    private val vps: ByteArray? = null,
) {
    interface Listener {
        /** 絵を 1 枚表示した。pcTimeUs = PC がその絵を送った時刻 */
        fun onFrameRendered(pcTimeUs: Long)
        /** デコーダーが壊れた。キーフレームからやり直したい */
        fun onDecoderError(e: Exception)
    }

    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var outThread: Thread? = null
    private var waitingKey = true

    fun start() {
        val fmt = MediaFormat.createVideoFormat(mime, width, height)
        setCsd(fmt)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 512 * 1024))
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // リアルタイム
        if (Build.VERSION.SDK_INT >= 30) {
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // 各社の独自の低遅延キー（知らないキーは無視される）
        fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        fmt.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
        fmt.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
        fmt.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        fmt.setInteger("vendor.low-latency.enable", 1)

        val name = bestDecoder(mime)?.name
        val c = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            MediaCodec.createDecoderByType(mime)
        }
        try {
            c.configure(fmt, surface, null, 0)
        } catch (e: Exception) {
            // 独自キーを嫌う機種向けに、最小の設定でもう一度
            Log.w(TAG, "configure failed, retry plain", e)
            c.reset()
            val plain = MediaFormat.createVideoFormat(mime, width, height)
            setCsd(plain)
            plain.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 512 * 1024))
            c.configure(plain, surface, null, 0)
        }
        c.start()
        codec = c
        running = true
        waitingKey = true
        outThread = Thread({ drainLoop() }, "decoder-out").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** H.264 は csd-0 = SPS・csd-1 = PPS、H.265 は csd-0 に VPS・SPS・PPS をまとめて入れる */
    private fun setCsd(f: MediaFormat) {
        if (mime == HEVC) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(START + (vps ?: ByteArray(0)) + START + sps + START + pps))
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
        }
    }

    /** ネットワークのスレッドから呼ぶ。false = 入れられなかった（落とした） */
    fun feed(data: ByteArray, offset: Int, length: Int, pcTimeUs: Long, flags: Int): Boolean {
        val c = codec ?: return false
        val key = flags and Protocol.F_KEY != 0
        if (waitingKey && !key) return false
        try {
            val idx = c.dequeueInputBuffer(30_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return false
            buf.clear()
            if (length > buf.remaining()) {
                c.queueInputBuffer(idx, 0, 0, pcTimeUs, 0)
                waitingKey = true
                return false
            }
            buf.put(data, offset, length)
            c.queueInputBuffer(idx, 0, length, pcTimeUs, 0)
            if (key) waitingKey = false
            return true
        } catch (e: Exception) {
            if (running) listener.onDecoderError(e)
            return false
        }
    }

    /** これより後はキーフレームが来るまで落とす */
    fun waitForKeyFrame() {
        waitingKey = true
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            try {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                    listener.onFrameRendered(info.presentationTimeUs)
                }
            } catch (e: Exception) {
                if (running) listener.onDecoderError(e)
                break
            }
        }
    }

    fun release() {
        running = false
        try { outThread?.join(300) } catch (_: InterruptedException) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
        const val MIME = "video/avc"
        const val HEVC = "video/hevc"
        private val START = byteArrayOf(0, 0, 0, 1)

        /** ハードウェアのデコーダーを優先して選ぶ */
        private fun bestDecoder(mime: String = MIME): MediaCodecInfo? {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            fun soft(n: String) = n.startsWith("OMX.google.") || n.startsWith("c2.android.") ||
                n.contains("ffmpeg", true) || n.contains(".sw.", true)
            return list.firstOrNull { !soft(it.name) } ?: list.firstOrNull()
        }

        /**
         * この端末のデコーダーで再生できる大きさにする。
         * 画面の縦横比はそのままで、偶数にそろえる。
         */
        fun fitSize(w: Int, h: Int): Pair<Int, Int> {
            var cw = w and 1.inv()
            var ch = h and 1.inv()
            val caps = try {
                bestDecoder()?.getCapabilitiesForType(MIME)?.videoCapabilities
            } catch (_: Exception) { null } ?: return Pair(cw, ch)
            var guard = 0
            while (guard++ < 40) {
                val ok = try { caps.isSizeSupported(cw, ch) } catch (_: Exception) { true }
                if (ok) break
                cw = ((cw * 0.9).toInt()) and 1.inv()
                ch = ((ch * 0.9).toInt()) and 1.inv()
                if (cw < 320 || ch < 240) break
            }
            return Pair(maxOf(cw, 320), maxOf(ch, 240))
        }

        /** H.265 をハードウェアで、この大きさで再生できるか（ソフトウェアだけなら遅いので使わない） */
        fun canHevc(w: Int, h: Int): Boolean = try {
            val d = bestDecoder(HEVC)
            val soft = d == null || d.name.startsWith("OMX.google.") || d.name.startsWith("c2.android.")
            !soft && d!!.getCapabilitiesForType(HEVC).videoCapabilities.let {
                it.isSizeSupported(w, h) || it.isSizeSupported(h, w)
            }
        } catch (_: Exception) { false }

        fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
    }
}
