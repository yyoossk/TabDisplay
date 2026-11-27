package com.yyoossk.tabdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Range
import android.view.Surface
import org.json.JSONObject

/**
 * タブレットのカメラを PC の Web カメラにする：カメラ → H.264（1280×720・30 枚）→ PC
 */
class CameraSender(private val ctx: Context, private val conn: Connection, private val facing: String) {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val thread = HandlerThread("camera").also { it.start() }
    private val handler = Handler(thread.looper)
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", W, H)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val enc = MediaCodec.createEncoderByType("video/avc")
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            encoder = enc
            Thread({ drain(enc) }, "camera-enc").start()

            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (facing == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull() ?: return
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camera = c
                    val surface = inputSurface ?: return
                    @Suppress("DEPRECATION")
                    c.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                            }
                            try { s.setRepeatingRequest(req.build(), null, handler) } catch (e: Exception) { Log.w(TAG, "repeat: $e") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "configure failed") }
                    }, handler)
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, error: Int) { Log.w(TAG, "camera error $error"); c.close() }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "camera start: $e")
            stop()
        }
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running && encoder === enc) {
            val idx = try { enc.dequeueOutputBuffer(info, 50_000) } catch (_: Exception) { break }
            if (idx < 0) continue
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val d = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(d)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val nals = splitNals(d)
                    val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
                    val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
                    if (sps != null && pps != null) {
                        conn.send(Protocol.T_CAM_CONFIG, JSONObject()
                            .put("w", W).put("h", H)
                            .put("sps", Base64.encodeToString(sps, Base64.NO_WRAP))
                            .put("pps", Base64.encodeToString(pps, Base64.NO_WRAP)))
                    }
                } else {
                    val out = ByteArray(9 + d.size)
                    out[8] = if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) Protocol.F_KEY.toByte() else 0
                    System.arraycopy(d, 0, out, 9, d.size)
                    conn.send(Protocol.T_CAM_VIDEO, out)
                }
            }
            try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) { break }
        }
    }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        val e = encoder
        encoder = null
        try { e?.stop() } catch (_: Exception) {}
        try { e?.release() } catch (_: Exception) {}
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraSender"
        const val W = 1280
        const val H = 720
    }
}

/** タブレットのマイクの音を PC へ（48kHz・1ch・16bit、20ms ずつ） */
class MicSender(private val conn: Connection) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val rate = 48000
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5))
            r.startRecording()
            rec = r
            running = true
            conn.send(Protocol.T_MIC_CFG, JSONObject().put("rate", rate).put("ch", 1))
            Thread({
                val buf = ByteArray(rate * 2 / 50) // 20ms
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) conn.send(Protocol.T_MIC, buf.copyOf(n))
                }
            }, "mic").start()
        } catch (e: Exception) {
            Log.w("MicSender", "start: $e")
        }
    }

    fun stop() {
        running = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
