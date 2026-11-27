package com.yyoossk.tabdisplay

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * PC の画面を映す画面。PC から adb で起動されるか、QR / 履歴から開かれる。
 */
class DisplayActivity : Activity(), Connection.Listener, VideoDecoder.Listener, SurfaceHolder.Callback {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var message: TextView
    private lateinit var statsView: TextView
    private lateinit var keyView: KeyInputView
    private lateinit var kbButton: TextView
    private var keyboardShown = false
    private val audioLock = Object()
    private var audio: AudioPlayer? = null
    private val ui = Handler(Looper.getMainLooper())

    private var target: Target? = null
    private var conn: Connection? = null

    // デコーダーまわり（ネットワークのスレッドと UI スレッドの両方から触るのでロックする）
    private val decLock = Object()
    private var decoder: VideoDecoder? = null
    @Volatile private var surfaceReady = false
    private var cfgW = 0
    private var cfgH = 0
    private var cfgSps: ByteArray? = null
    private var cfgPps: ByteArray? = null
    private var lastIdrRequest = 0L
    private val decoderErrors = ArrayList<Long>()

    // 統計
    @Volatile private var framesThisSecond = 0
    @Volatile private var latencySumUs = 0L
    @Volatile private var latencyCount = 0
    private var lastSentSize: Pair<Int, Int>? = null
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        BuildConfigCompat.versionCode = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        video = SurfaceView(this)
        video.holder.addCallback(this)
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val p = dp(24)
            setPadding(p, p, p, p)
        }
        root.addView(message, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        statsView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setBackgroundColor(0x80000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            visibility = View.GONE
        }
        root.addView(statsView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        // キーボードを出すボタン（右下・半透明）と、見えない入力欄
        keyView = KeyInputView(this) { k, v -> sendKeyInput(k, v) }
        root.addView(keyView, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        kbButton = TextView(this).apply {
            text = "⌨"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            alpha = 0.6f
            setOnClickListener { toggleKeyboard() }
        }
        root.addView(kbButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(12), dp(12))
        })
        setContentView(root)
        Discovery.start(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        setupTouch()
        hideSystemUi()

        handleIntent(intent)
        ui.postDelayed(statsTick, 1000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val t = Target.fromAdbIntent(i) ?: target
        if (t == null) {
            finish()
            return
        }
        if (conn != null && t == target && !conn!!.isClosed) return
        conn?.close()
        target = t
        showMessage(getString(R.string.connecting, t.name))
        conn = Connection(t, this).also { it.start() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // ---------- 回転 ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUi()
        // 回した直後は大きさがまだ古いことがあるので、少し待ってから送る
        ui.postDelayed({ sendSizeIfChanged() }, 250)
    }

    private fun sendSizeIfChanged() {
        val s = currentSize()
        if (s == lastSentSize) return
        lastSentSize = s
        conn?.post(Protocol.T_SIZE, JSONObject().put("w", s.first).put("h", s.second))
        layoutVideo()
    }

    override fun currentSize(): Pair<Int, Int> {
        val p = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(p)
        val q = prefs.quality.coerceIn(25, 100)
        val s = VideoDecoder.fitSize(p.x * q / 100, p.y * q / 100)
        lastSentSize = s
        return s
    }

    /** PC の文字の大きさを決めるための値（解像度を下げているときはその分小さく） */
    override fun dpi(): Int = resources.displayMetrics.densityDpi * prefs.quality.coerceIn(25, 100) / 100

    override fun deviceId(): String = prefs.deviceId

    // ---------- 音 ----------

    override fun onAudioConfig(rate: Int, channels: Int) {
        synchronized(audioLock) {
            if (!prefs.audio) return
            if (audio?.sameFormat?.invoke(rate, channels) == true) return
            audio?.release()
            audio = try { AudioPlayer(rate, channels) } catch (_: Exception) { null }
        }
    }

    override fun onAudio(data: ByteArray) {
        synchronized(audioLock) { audio?.push(data, 0, data.size) }
    }

    /** 映像の縦横比を保ったまま、画面いっぱいに置く */
    private fun layoutVideo() {
        val w = cfgW
        val h = cfgH
        val rw = root.width
        val rh = root.height
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        val lw = (w * scale).toInt()
        val lh = (h * scale).toInt()
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (lp.width != lw || lp.height != lh) {
            lp.width = lw
            lp.height = lh
            lp.gravity = Gravity.CENTER
            video.layoutParams = lp
        }
    }

    // ---------- 接続 ----------

    override fun onConnecting(host: String) {
        ui.post { if (cfgW == 0) showMessage(getString(R.string.connecting, target?.name ?: host)) }
    }

    override fun onConnected(host: String) {
        ui.post {
            target?.let { prefs.remember(it) }
            showMessage(getString(R.string.waiting_video))
        }
    }

    override fun onConfig(w: Int, h: Int, fps: Int, sps: ByteArray, pps: ByteArray) {
        synchronized(decLock) {
            cfgW = w; cfgH = h; cfgSps = sps; cfgPps = pps
            restartDecoderLocked()
        }
        ui.post {
            layoutVideo()
            video.visibility = View.VISIBLE
        }
    }

    private fun restartDecoderLocked() {
        decoder?.release()
        decoder = null
        val sps = cfgSps ?: return
        val pps = cfgPps ?: return
        if (!surfaceReady || cfgW <= 0) return
        try {
            decoder = VideoDecoder(video.holder.surface, cfgW, cfgH, sps, pps, this).also { it.start() }
        } catch (e: Exception) {
            noteDecoderError()
        }
    }

    override fun onVideo(data: ByteArray, offset: Int, length: Int, pcTimeUs: Long, flags: Int) {
        val key = flags and Protocol.F_KEY != 0
        val ok = synchronized(decLock) {
            val d = decoder
            if (d == null) {
                null
            } else {
                val r = d.feed(data, offset, length, pcTimeUs, flags)
                if (!r && !key) d.waitForKeyFrame()
                r
            }
        } ?: return
        if (!ok) requestIdr()
        else if (key) ui.post { if (message.visibility == View.VISIBLE) hideMessage() }
    }

    private fun requestIdr() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIdrRequest < 1000) return
        lastIdrRequest = now
        conn?.send(Protocol.T_IDR)
    }

    override fun onStatus(s: String) {
        ui.post {
            when (s) {
                "pc_only" -> { showMessage(getString(R.string.pc_only)); video.visibility = View.INVISIBLE }
                "no_driver" -> { showMessage(getString(R.string.no_driver)); video.visibility = View.INVISIBLE }
                "ok" -> video.visibility = View.VISIBLE
            }
        }
    }

    override fun onError(e: String) {
        ui.post {
            showMessage(if (e == "token") getString(R.string.err_token) else e)
        }
    }

    override fun onLost(willRetry: Boolean) {
        ui.post { showMessage(getString(if (willRetry) R.string.retrying else R.string.disconnected)) }
    }

    // ---------- デコーダー ----------

    override fun onFrameRendered(pcTimeUs: Long) {
        val c = conn ?: return
        framesThisSecond++
        // 遅れ = 今（PC の時計に直したもの） − PC が送った時刻
        val lat = VideoDecoder.nowUs() + c.clockOffsetUs - pcTimeUs
        if (lat in 0..2_000_000) {
            latencySumUs += lat
            latencyCount++
        }
    }

    override fun onDecoderError(e: Exception) {
        Thread {
            synchronized(decLock) { restartDecoderLocked() }
            requestIdr()
        }.start()
        noteDecoderError()
    }

    private fun noteDecoderError() {
        val now = SystemClock.elapsedRealtime()
        synchronized(decoderErrors) {
            decoderErrors.add(now)
            decoderErrors.removeAll { now - it > 10_000 }
            if (decoderErrors.size >= 4) ui.post { showMessage(getString(R.string.err_decoder)) }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(decLock) {
            surfaceReady = true
            restartDecoderLocked()
        }
        requestIdr()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(decLock) {
            surfaceReady = false
            decoder?.release()
            decoder = null
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val fps = framesThisSecond
            framesThisSecond = 0
            val lat = if (latencyCount > 0) (latencySumUs / latencyCount / 1000).toInt() else 0
            latencySumUs = 0; latencyCount = 0
            val rtt = ((conn?.rttUs ?: 0L) / 1000).toInt()
            if (prefs.showStats) {
                statsView.visibility = View.VISIBLE
                statsView.text = getString(R.string.stats_fmt, fps, lat, rtt)
            } else {
                statsView.visibility = View.GONE
            }
            if (fps > 0) conn?.post(Protocol.T_STATS, JSONObject().put("fps", fps).put("lat", lat).put("rtt", rtt))
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- タッチ ----------
    // 1 本指：タップ → 左クリック / 長押し → 右クリック / なぞる → スクロール
    // 2 本指：タップ → 右クリック / つまむ・広げる → 拡大縮小（Ctrl + ホイール）
    // ペン：筆圧つきのペン入力（Windows のペンとして）

    private fun setupTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        val notchPx = dp(40).toFloat()   // 40dp なぞるとホイール 1 目盛り
        val zoomStep = ln(1.15)          // 指の間が 15% 変わるごとに 1 段
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false
        var longFired = false
        var accX = 0f
        var accY = 0f
        // 2 本指
        var two = false
        var twoDone = false
        var twoStart = 0L
        var twoMoved = false
        var twoMidX = 0f
        var twoMidY = 0f
        var lastDist = 0f
        var zoomAcc = 0.0

        val longPress = Runnable {
            longFired = true
            if (prefs.haptic) video.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sendInput("rclick", downX, downY)
        }

        video.setOnTouchListener { v, e ->
            if (isPen(e)) {
                handlePen(v, e)
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    dragging = false; longFired = false; two = false; twoDone = false
                    accX = 0f; accY = 0f
                    ui.postDelayed(longPress, longMs)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    ui.removeCallbacks(longPress)
                    if (e.pointerCount == 2 && !dragging && !longFired && !twoDone) {
                        two = true
                        twoStart = SystemClock.elapsedRealtime()
                        twoMoved = false
                        twoMidX = (e.getX(0) + e.getX(1)) / 2
                        twoMidY = (e.getY(0) + e.getY(1)) / 2
                        lastDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        zoomAcc = 0.0
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (two && e.pointerCount >= 2) {
                        val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val midX = (e.getX(0) + e.getX(1)) / 2
                        val midY = (e.getY(0) + e.getY(1)) / 2
                        if (!twoMoved && (hypot(midX - twoMidX, midY - twoMidY) > slop || abs(dist - lastDist) > slop)) {
                            twoMoved = true
                            sendInput("move", twoMidX, twoMidY)
                        }
                        if (twoMoved && lastDist > 0 && dist > 0) {
                            zoomAcc += ln((dist / lastDist).toDouble()) / zoomStep * 120.0
                            lastDist = dist
                            val z = zoomAcc.toInt()
                            if (abs(z) >= 1) {
                                zoomAcc -= z
                                conn?.post(Protocol.T_INPUT, JSONObject().put("k", "zoom").put("z", z))
                            }
                        }
                    } else if (!two && !twoDone) {
                        if (!dragging && !longFired && hypot(e.x - downX, e.y - downY) > slop) {
                            dragging = true
                            ui.removeCallbacks(longPress)
                            // ホイールは指の下のウィンドウに届くよう、先にカーソルを動かす
                            sendInput("move", downX, downY)
                        }
                        if (dragging) {
                            accY += (e.y - lastY) / notchPx * 120f
                            accX += -(e.x - lastX) / notchPx * 120f
                            lastX = e.x; lastY = e.y
                            val wy = accY.toInt()
                            val wx = accX.toInt()
                            if (abs(wy) >= 1 || abs(wx) >= 1) {
                                accY -= wy; accX -= wx
                                conn?.post(Protocol.T_INPUT, JSONObject()
                                    .put("k", "scroll")
                                    .put("x", (downX / v.width).toDouble())
                                    .put("y", (downY / v.height).toDouble())
                                    .put("wx", wx).put("wy", wy))
                            }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (two) {
                        if (!twoMoved && SystemClock.elapsedRealtime() - twoStart < 350) {
                            if (prefs.haptic) video.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            sendInput("rclick", twoMidX, twoMidY)
                        }
                        two = false
                        twoDone = true // 指を全部離すまで、ほかの操作にしない
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (!dragging && !longFired && !two && !twoDone) sendInput("click", e.x, e.y)
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
            }
            true
        }

        // ペンを浮かせて動かしたとき（カーソルが付いてくる）
        video.setOnGenericMotionListener { v, e ->
            if (!isPen(e)) return@setOnGenericMotionListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> sendPen(v, e, "h")
                MotionEvent.ACTION_HOVER_EXIT -> sendPen(v, e, "x")
            }
            true
        }
    }

    private fun isPen(e: MotionEvent): Boolean {
        val t = e.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun handlePen(v: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> sendPen(v, e, "d")
            MotionEvent.ACTION_MOVE -> sendPen(v, e, "m")
            MotionEvent.ACTION_UP -> sendPen(v, e, "u")
            MotionEvent.ACTION_CANCEL -> sendPen(v, e, "x")
        }
    }

    private fun sendPen(v: View, e: MotionEvent, action: String) {
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0 || h <= 0) return
        // 傾き：AXIS_TILT（垂直からの角度）と AXIS_ORIENTATION（向き）を、Windows の左右・前後の傾きに直す
        val tilt = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT).toDouble())
        val ori = e.getAxisValue(MotionEvent.AXIS_ORIENTATION).toDouble()
        val tx = (tilt * sin(ori)).toInt().coerceIn(-90, 90)
        val ty = (-tilt * cos(ori)).toInt().coerceIn(-90, 90)
        val barrel = e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        val eraser = e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        conn?.post(Protocol.T_INPUT, JSONObject()
            .put("k", "pen").put("a", action)
            .put("x", (e.x / w).coerceIn(0f, 1f).toDouble())
            .put("y", (e.y / h).coerceIn(0f, 1f).toDouble())
            .put("p", e.pressure.coerceIn(0f, 1f).toDouble())
            .put("tx", tx).put("ty", ty)
            .put("b", barrel).put("e", eraser))
    }

    private fun sendInput(kind: String, x: Float, y: Float) {
        val w = video.width.toFloat()
        val h = video.height.toFloat()
        if (w <= 0 || h <= 0) return
        conn?.post(Protocol.T_INPUT, JSONObject()
            .put("k", kind)
            .put("x", (x / w).coerceIn(0f, 1f).toDouble())
            .put("y", (y / h).coerceIn(0f, 1f).toDouble()))
    }

    // ---------- キーボード ----------

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(keyView.windowToken, 0)
            keyboardShown = false
        } else {
            keyView.requestFocus()
            imm.showSoftInput(keyView, InputMethodManager.SHOW_FORCED)
            keyboardShown = true
        }
    }

    /** ソフトキーボードから：text = 文字、vk = キーを 1 回押す */
    private fun sendKeyInput(kind: String, v: Any?) {
        val j = JSONObject().put("k", kind)
        if (kind == "text") j.put("s", v as String) else j.put("vk", v as Int)
        conn?.post(Protocol.T_INPUT, j)
    }

    /** つないだキーボード（Bluetooth・USB）のキーを PC に送る */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            sendKeyInput("text", event.characters)
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (KeyMap.isPlainChar(event)) {
            if (down) sendKeyInput("text", event.unicodeChar.toChar().toString())
            return true
        }
        val vk = KeyMap.toVk(code) ?: return super.dispatchKeyEvent(event)
        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", vk).put("d", down))
        return true
    }

    // ---------- そのほか ----------

    private fun showMessage(s: String) {
        message.text = s
        message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        message.visibility = View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < 2000) {
            finish()
        } else {
            lastBack = now
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        Discovery.stop()
        synchronized(audioLock) {
            audio?.release()
            audio = null
        }
        conn?.close()
        conn = null
        synchronized(decLock) {
            decoder?.release()
            decoder = null
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
