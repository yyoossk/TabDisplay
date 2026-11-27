package com.yyoossk.tabdisplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** ゲームパッドのボタンの配置（画面の割合で位置、dp で大きさ）と、割り当てるキー */
data class PadKey(val id: String, var label: String, var vk: Int, var x: Float, var y: Float, var w: Int, var h: Int)

object GamepadLayout {
    fun defaults() = listOf(
        PadKey("up", "▲", 0x26, 0.12f, 0.58f, 64, 64),
        PadKey("left", "◀", 0x25, 0.06f, 0.70f, 64, 64),
        PadKey("right", "▶", 0x27, 0.18f, 0.70f, 64, 64),
        PadKey("down", "▼", 0x28, 0.12f, 0.82f, 64, 64),
        PadKey("y", "Y", 0x41, 0.70f, 0.70f, 64, 64),
        PadKey("x", "X", 0x53, 0.76f, 0.58f, 64, 64),
        PadKey("a", "A", 0x58, 0.82f, 0.70f, 64, 64),
        PadKey("b", "B", 0x5A, 0.76f, 0.82f, 64, 64),
        PadKey("l", "L", 0x51, 0.10f, 0.12f, 128, 56),
        PadKey("r", "R", 0x57, 0.78f, 0.12f, 128, 56),
        PadKey("select", "SELECT", 0xA1, 0.42f, 0.92f, 120, 40),
        PadKey("start", "START", 0x0D, 0.58f, 0.92f, 120, 40),
    )

    fun load(ctx: Context): MutableList<PadKey> {
        val s = ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).getString("pad", null)
            ?: return defaults().toMutableList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                PadKey(o.getString("id"), o.getString("label"), o.getInt("vk"),
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getInt("w"), o.getInt("h"))
            }.toMutableList()
        } catch (_: Exception) { defaults().toMutableList() }
    }

    fun save(ctx: Context, keys: List<PadKey>) {
        val a = JSONArray()
        keys.forEach {
            a.put(JSONObject().put("id", it.id).put("label", it.label).put("vk", it.vk)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("w", it.w).put("h", it.h))
        }
        ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).edit().putString("pad", a.toString()).apply()
    }

    fun reset(ctx: Context) {
        ctx.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).edit().remove("pad").apply()
    }

    /** 割り当てられるキー（名前, 仮想キーコード） */
    val choices: List<Pair<String, Int>> by lazy {
        val l = ArrayList<Pair<String, Int>>()
        l += listOf("↑" to 0x26, "↓" to 0x28, "←" to 0x25, "→" to 0x27, "Enter" to 0x0D, "Space" to 0x20,
            "Esc" to 0x1B, "Tab" to 0x09, "Backspace" to 0x08, "Shift" to 0xA0, "右Shift" to 0xA1,
            "Ctrl" to 0xA2, "Alt" to 0xA4)
        for (c in 'A'..'Z') l += c.toString() to c.code
        for (c in '0'..'9') l += c.toString() to c.code
        for (i in 1..12) l += "F$i" to (0x6F + i)
        l
    }
}
