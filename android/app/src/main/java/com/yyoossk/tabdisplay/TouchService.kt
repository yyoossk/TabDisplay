package com.yyoossk.tabdisplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 送る側の端末を、受ける側のタブレットから操作するためのユーザー補助。
 * タップ・長押し・スクロール・文字入力（入力欄に足す）・Backspace・Enter に対応（Android 7 以降）。
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile private var instance: TouchService? = null
        val enabled get() = instance != null
        private var lastX = 0.5
        private var lastY = 0.5

        fun handle(j: JSONObject) {
            val s = instance ?: return
            s.onInput(j)
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun screen(): Point {
        val p = Point()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    private fun onInput(j: JSONObject) {
        val k = j.optString("k")
        if (j.has("x")) { lastX = j.optDouble("x", lastX); lastY = j.optDouble("y", lastY) }
        val p = screen()
        val x = (lastX * (p.x - 1)).toFloat()
        val y = (lastY * (p.y - 1)).toFloat()
        when (k) {
            "click", "lclick" -> gesture(x, y, x, y, 50)
            "rclick", "rclick_here" -> gesture(x, y, x, y, 700)
            "scroll" -> {
                // ホイールの向き：上（+）＝ 指を下へなぞる
                val dy = j.optInt("wy") / 120f * (p.y / 8f)
                val dx = -j.optInt("wx") / 120f * (p.x / 8f)
                if (dx != 0f || dy != 0f) {
                    val ty = (y + dy).coerceIn(1f, p.y - 2f)
                    val tx = (x + dx).coerceIn(1f, p.x - 2f)
                    gesture(x, y, tx, ty, 120)
                }
            }
            "pen" -> if (j.optString("a") == "u") gesture(x, y, x, y, 50)
            "text" -> typeText(j.optString("s"))
            "vk", "key" -> {
                if (k == "key" && !j.optBoolean("d", true)) return
                when (j.optInt("vk")) {
                    0x08 -> backspace()
                    0x0D -> enter()
                }
            }
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
            lineTo(x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun focused(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setText(n: AccessibilityNodeInfo, text: String) {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun current(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintTextCompat()) "" else (n.text?.toString() ?: "")

    private fun typeText(s: String) {
        val n = focused() ?: return
        setText(n, current(n) + s)
    }

    private fun backspace() {
        val n = focused() ?: return
        val t = current(n)
        if (t.isNotEmpty()) setText(n, t.dropLast(1))
    }

    private fun enter() {
        if (Build.VERSION.SDK_INT >= 30) {
            focused()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat(): Boolean =
        Build.VERSION.SDK_INT >= 26 && isShowingHintText
}
