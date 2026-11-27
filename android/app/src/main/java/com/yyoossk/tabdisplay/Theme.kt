package com.yyoossk.tabdisplay

import android.content.Context

/** 見た目の値。中身は res/values/theme_colors.xml（ここを差し替えれば全体が変わる） */
object Theme {
    var buttonBg = 0x66000000
    var active = 0xCC1E88E5.toInt()
    var panelBg = 0x88000000.toInt()
    var stripBg = 0x55000000
    var softButton = 0x33FFFFFF
    var softButton2 = 0x44FFFFFF
    var surface = 0xFF37474F.toInt()
    var splitBg = 0xFF263238.toInt()
    var miniBg = 0xFF101418.toInt()
    var snippet = 0xFF80DEEA.toInt()
    var edit = 0x99FF9800.toInt()
    var statsBg = 0x80000000.toInt()
    var weakBg = 0xCCE65100.toInt()
    var buttonSize = 0

    fun init(ctx: Context) {
        val r = ctx.resources
        fun c(id: Int) = try { r.getColor(id, null) } catch (_: Exception) { null }
        c(R.color.td_button_bg)?.let { buttonBg = it }
        c(R.color.td_active)?.let { active = it }
        c(R.color.td_panel_bg)?.let { panelBg = it }
        c(R.color.td_strip_bg)?.let { stripBg = it }
        c(R.color.td_soft_button)?.let { softButton = it }
        c(R.color.td_soft_button2)?.let { softButton2 = it }
        c(R.color.td_surface)?.let { surface = it }
        c(R.color.td_split_bg)?.let { splitBg = it }
        c(R.color.td_mini_bg)?.let { miniBg = it }
        c(R.color.td_snippet)?.let { snippet = it }
        c(R.color.td_edit)?.let { edit = it }
        c(R.color.td_stats_bg)?.let { statsBg = it }
        c(R.color.td_weak_bg)?.let { weakBg = it }
        buttonSize = r.getDimensionPixelSize(R.dimen.td_button_size)
    }
}
