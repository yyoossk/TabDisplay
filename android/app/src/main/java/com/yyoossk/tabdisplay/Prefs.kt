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
    /** PC の証明書の指紋（SHA-256）。暗号化した通信の相手が本物か確かめる */
    val fp: String = "",
    /** PIN（USB の「接続する」のときだけ PC が渡す） */
    val pin: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("hosts", JSONArray(hosts))
        put("port", port)
        put("token", token)
        put("usb", usb)
        put("fp", fp)
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
                o.optString("fp", ""),
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
            val fp = u.getQueryParameter("f") ?: ""
            return Target(name, hosts, port, token, fp = fp)
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
            val fp = i.getStringExtra("fp") ?: ""
            val pin = i.getStringExtra("pin") ?: ""
            return Target(name, listOf(host), port, token, usb = host == "127.0.0.1", fp = fp, pin = pin)
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

    /** 5 分さわらないと画面を暗くする */
    var dim: Boolean
        get() = sp.getBoolean("dim", true)
        set(v) = sp.edit().putBoolean("dim", v).apply()

    /** 入力専用（ペンタブレット）モード：映像を出さず、タブレットの面を PC の画面に当てる */
    var inputOnly: Boolean
        get() = sp.getBoolean("inputOnly", false)
        set(v) = sp.edit().putBoolean("inputOnly", v).apply()

    /** 左端の「右クリック・中ボタン・スクロール」の帯 */
    var buttonStrip: Boolean
        get() = sp.getBoolean("strip", false)
        set(v) = sp.edit().putBoolean("strip", v).apply()

    /** クリップボード（コピーした文字）を PC と共有する */
    var clipboard: Boolean
        get() = sp.getBoolean("clip", true)
        set(v) = sp.edit().putBoolean("clip", v).apply()

    /** 画面を分ける：上＝PC の画面、下＝ボタンとメモ */
    var split: Boolean
        get() = sp.getBoolean("split", false)
        set(v) = sp.edit().putBoolean("split", v).apply()

    var memo: String
        get() = sp.getString("memo", "") ?: ""
        set(v) = sp.edit().putString("memo", v).apply()

    /** 明るさ（-1 = 端末の設定のまま、10〜100） */
    var brightness: Int
        get() = sp.getInt("bright", -1)
        set(v) = sp.edit().putInt("bright", v).apply()

    /** 暖色（0 = なし〜100） */
    var warmth: Int
        get() = sp.getInt("warm", 0)
        set(v) = sp.edit().putInt("warm", v).apply()

    /** コントラスト（%、100 = そのまま。PC 側で変える） */
    var contrast: Int
        get() = sp.getInt("contrast", 100)
        set(v) = sp.edit().putInt("contrast", v).apply()

    /** ミニ画面（スマホ向け：時計・PC の通知・ショートカット） */
    var mini: Boolean
        get() = sp.getBoolean("mini", false)
        set(v) = sp.edit().putBoolean("mini", v).apply()

    /** 左利き：ボタンを左側に */
    var leftHanded: Boolean
        get() = sp.getBoolean("left", false)
        set(v) = sp.edit().putBoolean("left", v).apply()

    /** タッチを Windows のタッチとしてそのまま送る */
    var nativeTouch: Boolean
        get() = sp.getBoolean("ntouch", false)
        set(v) = sp.edit().putBoolean("ntouch", v).apply()

    /** 手のひらの誤タッチを無視する（ペンを使っている間・大きな面のタッチ） */
    var palmReject: Boolean
        get() = sp.getBoolean("palm", true)
        set(v) = sp.edit().putBoolean("palm", v).apply()

    /** タブレットから選ぶ画質（pc = PC の設定のまま / auto / text / motion） */
    var quality2: String
        get() = sp.getString("quality2", "pc") ?: "pc"
        set(v) = sp.edit().putString("quality2", v).apply()

    /** 電池が少ない・熱いときに省電力モードにするか聞く */
    var powerAsk: Boolean
        get() = sp.getBoolean("power_ask", true)
        set(v) = sp.edit().putBoolean("power_ask", v).apply()

    /** Wi-Fi が不安定なときに知らせる */
    var weakWarn: Boolean
        get() = sp.getBoolean("weak_warn", true)
        set(v) = sp.edit().putBoolean("weak_warn", v).apply()

    /** 画面の向き：0 = 自動、1 = 横に固定、2 = 縦に固定 */
    var orientation: Int
        get() = sp.getInt("orient", 0)
        set(v) = sp.edit().putInt("orient", v).apply()

    /** ほかのアプリを開いたら小窓（ピクチャー・イン・ピクチャー）で出し続ける */
    var pip: Boolean
        get() = sp.getBoolean("pip", true)
        set(v) = sp.edit().putBoolean("pip", v).apply()

    /** つないだキーボードのキーを「キーで送る」（ゲーム向け）。オフなら文字で送る（配列に左右されない） */
    var keyMode: Boolean
        get() = sp.getBoolean("keymode", false)
        set(v) = sp.edit().putBoolean("keymode", v).apply()

    /** 定型文（1 行に 1 つ） */
    var snippets: List<String>
        get() = (sp.getString("snippets", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("snippets", v.joinToString("\n")).apply()

    /** PC ごとの PIN（合言葉で見分ける） */
    fun pinFor(token: String): String = sp.getString("pin_$token", "") ?: ""
    fun setPin(token: String, pin: String) = sp.edit().putString("pin_$token", pin).apply()

    /** タッチパッドモード */
    var touchpad: Boolean
        get() = sp.getBoolean("touchpad", false)
        set(v) = sp.edit().putBoolean("touchpad", v).apply()

    /** 並べるショートカットの ID（並び順） */
    var shortcuts: List<String>
        get() = sp.getString("shortcuts", null)?.split(',')?.filter { it.isNotEmpty() } ?: Shortcuts.defaultIds
        set(v) = sp.edit().putString("shortcuts", v.joinToString(",")).apply()

    /** 全部のショートカットの並び順（表示しないものも含む） */
    var shortcutOrder: List<String>
        get() {
            val saved = sp.getString("shortcut_order", null)?.split(',')?.filter { Shortcuts.byId(it) != null } ?: emptyList()
            return saved + Shortcuts.all.map { it.id }.filter { it !in saved }
        }
        set(v) = sp.edit().putString("shortcut_order", v.joinToString(",")).apply()

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
