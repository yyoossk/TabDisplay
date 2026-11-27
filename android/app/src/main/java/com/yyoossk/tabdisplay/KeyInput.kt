package com.yyoossk.tabdisplay

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * 文字入力を PC に送る。
 *  ・ソフトキーボード（日本語入力も）：確定した文字を送る
 *  ・つないだキーボード：キーをそのまま（Ctrl+C なども効く）
 */
object KeyMap {
    /** Android のキーコード → Windows の仮想キーコード（文字キーは文字で送るので、ここは記号以外） */
    fun toVk(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41 + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30 + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x70 + (code - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0D
        KeyEvent.KEYCODE_DEL -> 0x08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E
        KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_ESCAPE -> 0x1B
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25
        KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        KeyEvent.KEYCODE_MOVE_HOME -> 0x24
        KeyEvent.KEYCODE_MOVE_END -> 0x23
        KeyEvent.KEYCODE_PAGE_UP -> 0x21
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
        KeyEvent.KEYCODE_INSERT -> 0x2D
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3
        KeyEvent.KEYCODE_ALT_LEFT -> 0xA4
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5
        KeyEvent.KEYCODE_META_LEFT -> 0x5B
        KeyEvent.KEYCODE_META_RIGHT -> 0x5C
        // 日本語キーボードのキー
        KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> 0x19   // 半角/全角 → 日本語入力のオン／オフ
        KeyEvent.KEYCODE_EISU -> 0xF0              // 英数
        KeyEvent.KEYCODE_MUHENKAN -> 0x1D          // 無変換
        KeyEvent.KEYCODE_HENKAN -> 0x1C            // 変換
        KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> 0xF2 // カタカナ/ひらがな
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x14
        else -> null
    }

    /**
     * 「キーで送る」モード（ゲーム向け）で、記号のキーも仮想キーコードで送る。
     * PC のキー配列で決まる文字になる（タブレットと PC のキーボードの配列が同じなら、そのとおりの文字）。
     */
    fun toVkAll(code: Int): Int? = toVk(code) ?: when (code) {
        KeyEvent.KEYCODE_MINUS -> 0xBD
        KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC
        KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE
        KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_COMMA -> 0xBC
        KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SLASH -> 0xBF
        KeyEvent.KEYCODE_AT -> 0xC0
        KeyEvent.KEYCODE_YEN -> 0xDC
        KeyEvent.KEYCODE_RO -> 0xE2
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 0x60 + (code - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D
        KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F
        else -> null
    }

    /** 文字として送るほうがよいキー（記号など、PC のキー配列に左右されないように） */
    fun isPlainChar(e: KeyEvent): Boolean {
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        if (toVk(e.keyCode) != null && e.keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            e.keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return false
        return e.unicodeChar != 0
    }
}

/** ソフトキーボードを出すための見えない入力欄 */
class KeyInputView(ctx: Context, private val send: (String, Any?) -> Unit) : View(ctx) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing: CharSequence = ""

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                if (!text.isNullOrEmpty()) send("text", text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = text ?: ""
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) send("text", composing.toString())
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(50)) { send("vk", 0x08) }
                repeat(afterLength.coerceAtMost(50)) { send("vk", 0x2E) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val vk = KeyMap.toVk(event.keyCode)
                    if (vk != null) send("vk", vk)
                    else if (event.unicodeChar != 0) send("text", event.unicodeChar.toChar().toString())
                }
                return true
            }
        }
    }
}
