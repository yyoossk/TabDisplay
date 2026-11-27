package com.yyoossk.tabdisplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PC の音を鳴らす（16bit PCM）。遅れがたまらないよう、たまりすぎたら古い分を捨てる。
 */
class AudioPlayer(private val rate: Int, private val channels: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var queuedBytes = 0
    @Volatile private var running = true
    private val bytesPerSec = rate * channels * 2
    private val track: AudioTrack
    private val thread: Thread

    init {
        val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, bytesPerSec / 20) // 50ms 程度
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(chMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        track = b.build()
        track.play()
        thread = Thread({ loop() }, "audio").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun push(data: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        queue.offer(data.copyOfRange(offset, offset + length))
        queuedBytes += length
        // 150ms より多くたまったら、古い分を捨てて追いつく
        while (queuedBytes > bytesPerSec * 15 / 100) {
            val old = queue.poll() ?: break
            queuedBytes -= old.size
        }
    }

    private fun loop() {
        while (running) {
            val d = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            queuedBytes -= d.size
            try { track.write(d, 0, d.size) } catch (_: Exception) { break }
        }
    }

    fun release() {
        running = false
        thread.interrupt()
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }

    val sameFormat: (Int, Int) -> Boolean = { r, c -> r == rate && c == channels }
}
