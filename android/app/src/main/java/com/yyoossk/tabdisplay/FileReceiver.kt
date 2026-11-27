package com.yyoossk.tabdisplay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * PC から届いたファイルを保存する。
 *  Android 10 以降：「Download/TabDisplay」（ファイルアプリから見える）
 *  Android 9 まで：アプリ用のフォルダー（Android/data/com.yyoossk.tabdisplay/files/Download）
 */
class FileReceiver(private val ctx: Context, val name: String) {
    private var out: OutputStream? = null
    private var uri: Uri? = null
    var where: String = ""
        private set

    init {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TabDisplay")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            out = uri?.let { ctx.contentResolver.openOutputStream(it) }
            where = "Download/TabDisplay/$safe"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            dir.mkdirs()
            var f = File(dir, safe)
            var i = 2
            while (f.exists()) {
                f = File(dir, safe.substringBeforeLast('.') + " ($i)" + (if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""))
                i++
            }
            out = f.outputStream()
            where = f.absolutePath
        }
    }

    fun write(data: ByteArray, off: Int, len: Int) {
        out?.write(data, off, len)
    }

    fun finish() {
        try { out?.close() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 29) {
            uri?.let {
                val v = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(it, v, null, null)
            }
        }
    }
}
