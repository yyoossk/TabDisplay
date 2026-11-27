package com.yyoossk.tabdisplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** つなぐ相手の PC */
data class Target(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val token: String,
    val usb: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("hosts", JSONArray(hosts))
        put("port", port)
        put("token", token)
        put("usb", usb)
    }

    /** PC を見分ける ID（合言葉から作る。合言葉そのものは知らせに出さない）。PC 側と同じ計算 */
    val pcId: String get() = pcIdOf(token)

    fun putInto(i: Intent): Intent = i.putExtra(EXTRA_TARGET, toJson().toString())

    companion object {
        const val EXTRA_TARGET = "target"

        fun pcIdOf(token: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            return d.take(8).joinToString("") { "%02x".format(it) }
        }

        fun fromJson(o: JSONObject): Target {
            val a = o.optJSONArray("hosts") ?: JSONArray()
            val hosts = (0 until a.length()).map { a.getString(it) }
            return Target(
                o.optString("name", "PC"),
                hosts,
                o.optInt("port", Protocol.PORT),
                o.optString("token", ""),
                o.optBoolean("usb", false),
            )
        }

        /** tabdisplay://connect?h=192.168.1.5,10.0.0.2&p=38500&t=TOKEN&n=NAME */
        fun fromUri(u: Uri?): Target? {
            if (u == null || u.scheme != "tabdisplay" || u.host != "connect") return null
            val hosts = (u.getQueryParameter("h") ?: return null)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return null
            val token = u.getQueryParameter("t") ?: return null
            val port = u.getQueryParameter("p")?.toIntOrNull() ?: Protocol.PORT
            val name = u.getQueryParameter("n") ?: hosts[0]
            return Target(name, hosts, port, token)
        }

        /** PC が adb で起動したとき：am start --es host 127.0.0.1 --ei port 38500 --es token X --es name Y */
        fun fromAdbIntent(i: Intent?): Target? {
            if (i == null) return null
            i.getStringExtra(EXTRA_TARGET)?.let {
                return try { fromJson(JSONObject(it)) } catch (_: Exception) { null }
            }
            val host = i.getStringExtra("host") ?: return null
            val token = i.getStringExtra("token") ?: return null
            val port = i.getIntExtra("port", Protocol.PORT)
            val name = i.getStringExtra("name") ?: "PC"
            return Target(name, listOf(host), port, token, usb = host == "127.0.0.1")
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE)

    var showStats: Boolean
        get() = sp.getBoolean("stats", false)
        set(v) = sp.edit().putBoolean("stats", v).apply()

    /** 100 / 75 / 50（%） */
    var quality: Int
        get() = sp.getInt("quality", 100)
        set(v) = sp.edit().putInt("quality", v).apply()

    /** このタブレットの ID（PC が同じタブレットのつなぎ直しを見分ける） */
    val deviceId: String
        get() {
            var s = sp.getString("id", null)
            if (s == null) {
                s = UUID.randomUUID().toString()
                sp.edit().putString("id", s).apply()
            }
            return s
        }

    /** 前につないだ PC が Wi-Fi で見つかったら自動でつなぐ */
    var autoConnect: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** PC の音を鳴らす */
    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    /** Wi-Fi でつないだ PC（新しい順・最大 5 台）。USB は毎回 PC から起動されるので覚えない */
    fun recent(): List<Target> {
        val a = try { JSONArray(sp.getString("recent", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull {
            try { Target.fromJson(a.getJSONObject(it)) } catch (_: Exception) { null }
        }
    }

    fun remember(t: Target) {
        if (t.usb) return
        val list = recent().filter { it.token != t.token }.toMutableList()
        list.add(0, t)
        val a = JSONArray()
        list.take(5).forEach { a.put(it.toJson()) }
        sp.edit().putString("recent", a.toString()).apply()
    }

    fun forget(t: Target) {
        val a = JSONArray()
        recent().filter { it.token != t.token }.forEach { a.put(it.toJson()) }
        sp.edit().putString("recent", a.toString()).apply()
    }
}
