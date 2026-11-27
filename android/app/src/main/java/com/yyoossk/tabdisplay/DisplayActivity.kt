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
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
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
    private lateinit var tpButton: TextView
    private lateinit var sideBar: LinearLayout
    private lateinit var padView: View
    private lateinit var strip: LinearLayout
    private lateinit var gamepad: FrameLayout
    private lateinit var ioButton: TextView
    private lateinit var stripButton: TextView
    private lateinit var padButton: TextView
    private var areaW = 16
    private var areaH = 9
    /** 左端の「右」「中」を押している間は、タップがその ボタンのクリックになる */
    @Volatile private var rHeld = false
    @Volatile private var mHeld = false
    /** いまさわっている面（映像か、入力専用の面） */
    private var touchView: View? = null
    private lateinit var sideScroll: ScrollView
    private var lastTouch = SystemClock.elapsedRealtime()
    private var dimmed = false
    private val tpHelp by lazy { Toast.makeText(this, R.string.touchpad_on, Toast.LENGTH_LONG) }
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
        // 入力専用（ペンタブレット）のときの面。PC の画面と同じ縦横比の灰色の板
        padView = View(this).apply {
            setBackgroundColor(0xFF37474F.toInt())
            visibility = View.GONE
        }
        root.addView(padView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
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
        // 右下のボタン：⌨ キーボード / ☰ ショートカット / 🖱 タッチパッドモード（半透明）
        fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            alpha = 0.6f
            setOnClickListener { onClick() }
        }
        kbButton = roundButton("⌨") { toggleKeyboard() }
        tpButton = roundButton("🖱") { toggleTouchpad() }
        val menuButton = roundButton("☰") {
            sideScroll.visibility = if (sideScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ioButton = roundButton("✎") { toggleInputOnly() }
        stripButton = roundButton("◧") {
            prefs.buttonStrip = !prefs.buttonStrip
            updateModeButtons()
        }
        padButton = roundButton("🎮") {
            gamepad.visibility = if (gamepad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateModeButtons()
        }
        for (b in listOf(menuButton, padButton, stripButton, ioButton, tpButton, kbButton)) {
            buttons.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, dp(6), 0, 0) })
        }
        root.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(10), dp(10)) })

        // ショートカットのボタンの列（右端・☰ で出し入れ）
        sideBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideScroll = ScrollView(this).apply {
            addView(sideBar)
            visibility = View.GONE
            setBackgroundColor(0x88000000.toInt())
        }
        root.addView(sideScroll, FrameLayout.LayoutParams(
            dp(120), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, dp(60), 0)
        })
        buildShortcuts()
        buildStrip()
        buildGamepad()
        updateTouchpadButton()
        updateModeButtons()
        setContentView(root)
        Discovery.start(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo(); layoutPad() }

        setupTouch(video)
        setupTouch(padView)
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

    override fun onResume() {
        super.onResume()
        buildShortcuts()
        updateTouchpadButton()
    }

    private fun buildShortcuts() {
        sideBar.removeAllViews()
        for (id in prefs.shortcuts) {
            val item = Shortcuts.byId(id) ?: continue
            sideBar.addView(TextView(this).apply {
                setText(item.label)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener {
                    if (prefs.haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val a = JSONArray()
                    item.vks.forEach { a.put(it) }
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "combo").put("vks", a))
                }
            })
        }
    }

    private fun toggleTouchpad() {
        prefs.touchpad = !prefs.touchpad
        updateTouchpadButton()
        if (prefs.touchpad) tpHelp.show()
        else Toast.makeText(this, R.string.touchpad_off, Toast.LENGTH_SHORT).show()
    }

    private fun updateTouchpadButton() {
        tpButton.alpha = if (prefs.touchpad) 1f else 0.6f
        tpButton.setBackgroundColor(if (prefs.touchpad) 0xCC1E88E5.toInt() else 0x66000000)
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
        // 回した直後は大きさがまだ古いことがあるので、何度か確かめて、変わったらすぐ送る
        for (d in longArrayOf(0, 60, 200, 500)) ui.postDelayed({ sendSizeIfChanged() }, d)
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

    // ---------- 入力専用（ペンタブレット） ----------

    override fun inputOnly(): Boolean = prefs.inputOnly

    override fun onArea(w: Int, h: Int) {
        ui.post {
            areaW = maxOf(1, w); areaH = maxOf(1, h)
            layoutPad()
        }
    }

    /** PC の画面と同じ縦横比で、タブレットいっぱいに置く */
    private fun layoutPad() {
        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / areaW, rh.toFloat() / areaH)
        val lp = padView.layoutParams as FrameLayout.LayoutParams
        val lw = (areaW * scale).toInt()
        val lh = (areaH * scale).toInt()
        if (lp.width != lw || lp.height != lh) {
            lp.width = lw; lp.height = lh
            padView.layoutParams = lp
        }
    }

    private fun toggleInputOnly() {
        prefs.inputOnly = !prefs.inputOnly
        conn?.post(Protocol.T_OPTS, JSONObject().put("inputOnly", prefs.inputOnly))
        if (!prefs.inputOnly) padView.visibility = View.GONE
        Toast.makeText(this, if (prefs.inputOnly) R.string.input_only_on else R.string.input_only_off, Toast.LENGTH_LONG).show()
        updateModeButtons()
    }

    private fun updateModeButtons() {
        fun mark(b: TextView, on: Boolean) {
            b.alpha = if (on) 1f else 0.6f
            b.setBackgroundColor(if (on) 0xCC1E88E5.toInt() else 0x66000000)
        }
        mark(ioButton, prefs.inputOnly)
        mark(stripButton, prefs.buttonStrip)
        mark(padButton, gamepad.visibility == View.VISIBLE)
        strip.visibility = if (prefs.buttonStrip) View.VISIBLE else View.GONE
    }

    // ---------- 左端の帯：押している間「右」「中」・スクロール ----------

    private fun buildStrip() {
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x55000000)
        }
        fun holdButton(label: String, set: (Boolean) -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { set(true); v.setBackgroundColor(0xCC1E88E5.toInt()) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { set(false); v.setBackgroundColor(0) }
                }
                true
            }
        }
        strip.addView(holdButton(getString(R.string.strip_right)) { rHeld = it },
            LinearLayout.LayoutParams(dp(56), dp(64)))
        strip.addView(holdButton(getString(R.string.strip_middle)) { mHeld = it },
            LinearLayout.LayoutParams(dp(56), dp(64)))
        // スクロールの帯：上下になぞるとホイール
        val scroll = TextView(this).apply {
            text = "↕"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x33FFFFFF)
            var lastY = 0f
            var acc = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastY = e.y; acc = 0f }
                    MotionEvent.ACTION_MOVE -> {
                        acc += (e.y - lastY) / dp(20) * 120f
                        lastY = e.y
                        val wy = acc.toInt()
                        if (wy != 0) {
                            acc -= wy
                            conn?.post(Protocol.T_INPUT, JSONObject().put("k", "scroll").put("wx", 0).put("wy", -wy))
                        }
                    }
                }
                true
            }
        }
        strip.addView(scroll, LinearLayout.LayoutParams(dp(56), 0, 1f))
        root.addView(strip, FrameLayout.LayoutParams(dp(56), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    // ---------- ゲームパッド（キーボードのキーとして送る） ----------

    private fun buildGamepad() {
        gamepad = FrameLayout(this).apply { visibility = View.GONE }
        fun key(label: String, vk: Int) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            alpha = 0.8f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (prefs.haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        v.setBackgroundColor(0xCC1E88E5.toInt())
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", vk).put("d", true))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.setBackgroundColor(0x66000000)
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", vk).put("d", false))
                    }
                }
                true
            }
        }
        val s = dp(64)
        fun place(v: View, gravity: Int, x: Int, y: Int, w: Int = s, h: Int = s) {
            gamepad.addView(v, FrameLayout.LayoutParams(w, h, gravity).apply {
                if (gravity and Gravity.END == Gravity.END) rightMargin = x else leftMargin = x
                if (gravity and Gravity.BOTTOM == Gravity.BOTTOM) bottomMargin = y else topMargin = y
            })
        }
        val bl = Gravity.BOTTOM or Gravity.START
        val br = Gravity.BOTTOM or Gravity.END
        // 十字キー（左下）
        place(key("▲", 0x26), bl, dp(20) + s, dp(20) + s * 2)
        place(key("◀", 0x25), bl, dp(20), dp(20) + s)
        place(key("▶", 0x27), bl, dp(20) + s * 2, dp(20) + s)
        place(key("▼", 0x28), bl, dp(20) + s, dp(20))
        // A B X Y（右下。右のボタン列よりさらに内側）
        val rx = dp(70)
        place(key("Y", 0x41), br, rx + s * 2, dp(20) + s)
        place(key("X", 0x53), br, rx + s, dp(20) + s * 2)
        place(key("A", 0x58), br, rx, dp(20) + s)
        place(key("B", 0x5A), br, rx + s, dp(20))
        // L R（上の左右）
        place(key("L", 0x51), Gravity.TOP or Gravity.START, dp(20), dp(20), s * 2, s)
        place(key("R", 0x57), Gravity.TOP or Gravity.END, rx, dp(20), s * 2, s)
        // SELECT START（下の真ん中）
        place(key("SELECT", 0xA1), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(20), s * 2, dp(40))
        gamepad.addView(key("START", 0x0D), FrameLayout.LayoutParams(s * 2, dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(70)
        })
        root.addView(gamepad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
                "no_region" -> { showMessage(getString(R.string.no_region)); video.visibility = View.INVISIBLE }
                "input_only" -> {
                    hideMessage()
                    video.visibility = View.INVISIBLE
                    padView.visibility = View.VISIBLE
                    layoutPad()
                }
                "no_driver" -> { showMessage(getString(R.string.no_driver)); video.visibility = View.INVISIBLE }
                "ok" -> {
                    video.visibility = View.VISIBLE
                    padView.visibility = View.GONE
                }
            }
        }
    }

    override fun onError(e: String) {
        ui.post {
            showMessage(when (e) {
                "token" -> getString(R.string.err_token)
                "tls" -> getString(R.string.err_tls)
                else -> e
            })
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
            // 省電力：5 分さわらなければ画面を暗くする
            if (prefs.dim && !dimmed && SystemClock.elapsedRealtime() - lastTouch > 5 * 60_000L) {
                dimmed = true
                window.attributes = window.attributes.apply { screenBrightness = 0.05f }
            }
            conn?.post(Protocol.T_STATS, JSONObject().put("fps", fps).put("lat", lat).put("rtt", rtt))
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- タッチ ----------
    // 1 本指：タップ → 左クリック / 長押し → 右クリック / なぞる → スクロール
    // 2 本指：タップ → 右クリック / つまむ・広げる → 拡大縮小（Ctrl + ホイール）
    // ペン：筆圧つきのペン入力（Windows のペンとして）

    private fun setupTouch(target: View) {
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
            if (prefs.haptic) target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sendInput("rclick", downX, downY)
        }

        target.setOnTouchListener { v, e ->
            touched()
            touchView = v
            if (prefs.touchpad && !isPen(e)) {
                touchpad(v, e)
                return@setOnTouchListener true
            }
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
                            if (prefs.haptic) target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            sendInput("rclick", twoMidX, twoMidY)
                        }
                        two = false
                        twoDone = true // 指を全部離すまで、ほかの操作にしない
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (!dragging && !longFired && !two && !twoDone) {
                        // 左端の「右」「中」を押していれば、そのボタンのクリックにする
                        sendInput(if (rHeld) "rclick" else if (mHeld) "mclick" else "click", e.x, e.y)
                    }
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
            }
            true
        }

        // ペンを浮かせて動かしたとき（カーソルが付いてくる）
        target.setOnGenericMotionListener { v, e ->
            if (!isPen(e)) return@setOnGenericMotionListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> sendPen(v, e, "h")
                MotionEvent.ACTION_HOVER_EXIT -> sendPen(v, e, "x")
            }
            true
        }
    }

    /** さわったら暗くしていた画面を戻す */
    private fun touched() {
        lastTouch = SystemClock.elapsedRealtime()
        if (dimmed) {
            dimmed = false
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // ---------- タッチパッドモード ----------
    // なぞる → カーソルを動かす / タップ → クリック / 2 本指タップ → 右クリック
    // 2 本指でなぞる → スクロール / 2 回タップしてなぞる → ドラッグ

    private var tpLastX = 0f
    private var tpLastY = 0f
    private var tpDownX = 0f
    private var tpDownY = 0f
    private var tpDownTime = 0L
    private var tpMoved = false
    private var tpTwo = false
    private var tpTwoDone = false
    private var tpTwoMoved = false
    private var tpTwoStart = 0L
    private var tpLastTapUp = 0L
    private var tpDragging = false
    private var tpAccX = 0f
    private var tpAccY = 0f
    private var tpScrollY = 0f
    private var tpScrollX = 0f

    private fun touchpad(v: View, e: MotionEvent) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val speed = 1.6f
        val notchPx = dp(40).toFloat()
        val now = SystemClock.elapsedRealtime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tpLastX = e.x; tpLastY = e.y; tpDownX = e.x; tpDownY = e.y
                tpDownTime = now; tpMoved = false; tpTwo = false; tpTwoDone = false
                tpAccX = 0f; tpAccY = 0f
                if (now - tpLastTapUp < 300) {
                    tpDragging = true
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "ldown"))
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !tpDragging) {
                tpTwo = true; tpTwoMoved = false; tpTwoStart = now
                tpLastX = (e.getX(0) + e.getX(1)) / 2
                tpLastY = (e.getY(0) + e.getY(1)) / 2
                tpScrollX = 0f; tpScrollY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (tpTwo && e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    if (hypot(mx - tpLastX, my - tpLastY) > 0) {
                        tpScrollY += (my - tpLastY) / notchPx * 120f
                        tpScrollX += -(mx - tpLastX) / notchPx * 120f
                        if (abs(tpScrollY) > 3 || abs(tpScrollX) > 3) tpTwoMoved = true
                        val wy = tpScrollY.toInt()
                        val wx = tpScrollX.toInt()
                        if (abs(wy) >= 1 || abs(wx) >= 1) {
                            tpScrollY -= wy; tpScrollX -= wx
                            conn?.post(Protocol.T_INPUT, JSONObject().put("k", "scroll").put("wx", wx).put("wy", wy))
                        }
                    }
                    tpLastX = mx; tpLastY = my
                } else if (!tpTwo && !tpTwoDone) {
                    if (hypot(e.x - tpDownX, e.y - tpDownY) > slop) tpMoved = true
                    tpAccX += (e.x - tpLastX) * speed
                    tpAccY += (e.y - tpLastY) * speed
                    tpLastX = e.x; tpLastY = e.y
                    val dx = tpAccX.toInt()
                    val dy = tpAccY.toInt()
                    if (dx != 0 || dy != 0) {
                        tpAccX -= dx; tpAccY -= dy
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "rel").put("dx", dx).put("dy", dy))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (tpTwo) {
                if (!tpTwoMoved && now - tpTwoStart < 350) {
                    if (prefs.haptic) video.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "rclick_here"))
                }
                tpTwo = false
                tpTwoDone = true
            }
            MotionEvent.ACTION_UP -> {
                if (tpDragging) {
                    tpDragging = false
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "lup"))
                    tpLastTapUp = 0
                } else if (!tpMoved && !tpTwoDone && now - tpDownTime < 250) {
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "lclick"))
                    tpLastTapUp = now
                }
            }
            MotionEvent.ACTION_CANCEL -> if (tpDragging) {
                tpDragging = false
                conn?.post(Protocol.T_INPUT, JSONObject().put("k", "lup"))
            }
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
        val tv = touchView ?: video
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
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
