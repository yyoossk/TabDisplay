package com.yyoossk.tabdisplay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * タブレットに映っている PC の画面を録画する。届いた映像をそのまま MP4 に入れるので、画質は落ちず電池も食わない。
 * 保存先：Android 10 以降は「Movies/TabDisplay」、それより前はアプリのフォルダー。
 */
class Recorder(
    private val ctx: Context,
    mime: String,
    w: Int, h: Int,
    sps: ByteArray, pps: ByteArray, vps: ByteArray?,
) {
    private val file: File
    private val muxer: MediaMuxer
    private var track = -1
    private var firstUs = -1L
    private var started = false
    private val START = byteArrayOf(0, 0, 0, 1)

    init {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        dir.mkdirs()
        file = File(dir, "TabDisplay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val f = MediaFormat.createVideoFormat(mime, w, h)
        if (mime == VideoDecoder.HEVC) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(START + (vps ?: ByteArray(0)) + START + sps + START + pps))
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
        }
        track = muxer.addTrack(f)
    }

    /** キーフレームから書き始める */
    @Synchronized
    fun write(data: ByteArray, off: Int, len: Int, pcTimeUs: Long, key: Boolean) {
        if (!started) {
            if (!key) return
            muxer.start()
            started = true
            firstUs = pcTimeUs
        }
        val info = MediaCodec.BufferInfo()
        info.set(0, len, pcTimeUs - firstUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        muxer.writeSampleData(track, ByteBuffer.wrap(data, off, len).slice(), info)
    }

    /** 終わり。保存した場所（見せる用）を返す */
    @Synchronized
    fun stop(): String? {
        try {
            if (started) muxer.stop()
            muxer.release()
        } catch (_: Exception) {}
        if (!started) { file.delete(); return null }
        if (Build.VERSION.SDK_INT >= 29) {
            // 動画のフォルダーに移す（ギャラリーで見られるように）
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TabDisplay")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return file.absolutePath
            ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            file.delete()
            return "Movies/TabDisplay/" + file.name
        }
        return file.absolutePath
    }

    companion object {
        /** 画面写真を保存（Android 10 以降は「Pictures/TabDisplay」） */
        fun savePicture(ctx: Context, bmp: Bitmap): String {
            val name = "TabDisplay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TabDisplay")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return "Pictures/TabDisplay/$name"
            }
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
