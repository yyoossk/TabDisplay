package com.yyoossk.tabdisplay

/** タブレットの端に並べるショートカットのボタン */
object Shortcuts {
    class Item(val id: String, val label: Int, val vks: IntArray)

    private const val CTRL = 0xA2
    private const val ALT = 0xA4
    private const val SHIFT = 0xA0
    private const val WIN = 0x5B

    val all = listOf(
        Item("copy", R.string.sc_copy, intArrayOf(CTRL, 0x43)),
        Item("paste", R.string.sc_paste, intArrayOf(CTRL, 0x56)),
        Item("cut", R.string.sc_cut, intArrayOf(CTRL, 0x58)),
        Item("undo", R.string.sc_undo, intArrayOf(CTRL, 0x5A)),
        Item("redo", R.string.sc_redo, intArrayOf(CTRL, 0x59)),
        Item("selall", R.string.sc_selall, intArrayOf(CTRL, 0x41)),
        Item("save", R.string.sc_save, intArrayOf(CTRL, 0x53)),
        Item("alttab", R.string.sc_alttab, intArrayOf(ALT, 0x09)),
        Item("start", R.string.sc_start, intArrayOf(WIN)),
        Item("desktop", R.string.sc_desktop, intArrayOf(WIN, 0x44)),
        Item("snip", R.string.sc_snip, intArrayOf(WIN, SHIFT, 0x53)),
        Item("esc", R.string.sc_esc, intArrayOf(0x1B)),
        Item("del", R.string.sc_del, intArrayOf(0x2E)),
        Item("enter", R.string.sc_enter, intArrayOf(0x0D)),
    )

    val defaultIds = listOf("copy", "paste", "undo", "redo", "alttab", "esc")

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
