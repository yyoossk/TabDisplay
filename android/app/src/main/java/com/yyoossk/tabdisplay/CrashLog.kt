package com.yyoossk.tabdisplay

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * アプリが落ちたとき、原因をファイルに残す。次に PC とつないだとき、PC のログに送る
 * （Wi-Fi だけで使っている人の不具合も、PC の「ログをまとめる」で追えるように）。
 */
object CrashLog {
    @Volatile private var installed = false

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText("thread ${t.name}\n$sw")
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 残っていれば、PC に送る形にして消す */
    fun take(ctx: Context): JSONObject? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (text.isEmpty()) return null
        return JSONObject()
            .put("text", text.take(60_000))
            .put("build", AppUpdate.currentBuild(ctx))
            .put("android", Build.VERSION.RELEASE)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

/** 設定の書き出し・読み込み（この端末の ID は移さない） */
object SettingsIO {
    private val skip = setOf("id", "upd_last", "guided")

    fun export(ctx: Context): String {
        val sp = ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE)
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k in skip || v == null) continue
            o.put(k, JSONObject().put("t", when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s"
            }).put("v", v))
        }
        return JSONObject().put("app", "tabdisplay").put("settings", o).toString(2)
    }

    fun import(ctx: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            if (root.optString("app") != "tabdisplay") return false
            val o = root.getJSONObject("settings")
            val e = ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                if (k in skip) continue
                val x = o.getJSONObject(k)
                when (x.optString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    else -> e.putString(k, x.getString("v"))
                }
            }
            e.apply()
            true
        } catch (_: Exception) { false }
    }
}
