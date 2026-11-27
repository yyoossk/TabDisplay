package com.yyoossk.tabdisplay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private var buttonsCol: View? = null
    private lateinit var splitPanel: LinearLayout
    private lateinit var splitButtons: LinearLayout
    private lateinit var splitButton: TextView
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
    // クリップボード・ファイル
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    @Volatile private var lastRemoteClip: String? = null
    private val receiving = java.util.concurrent.ConcurrentHashMap<Int, FileReceiver>()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!prefs.clipboard) return@OnPrimaryClipChangedListener
        val t = try { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null }
        if (t != null && t != lastRemoteClip && t.length < 512 * 1024) {
            lastRemoteClip = t
            conn?.post(Protocol.T_CLIP, JSONObject().put("s", t))
        }
    }
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
    private var cfgVps: ByteArray? = null
    private var cfgCodec = "h264"
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
        Theme.init(this)
        CrashLog.install(this)
        BuildConfigCompat.versionCode = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 画面の向き（設定で固定できる）
        requestedOrientation = when (prefs.orientation) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        video = SurfaceView(this)
        video.holder.addCallback(this)
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        // 入力専用（ペンタブレット）のときの面。PC の画面と同じ縦横比の灰色の板
        padView = View(this).apply {
            setBackgroundColor(Theme.surface)
            visibility = View.GONE
        }
        root.addView(padView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        // 暖色の膜（明るさ・色の設定）。映像の上、ボタンの下
        warmView = View(this).apply { visibility = View.GONE }
        root.addView(warmView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
            setBackgroundColor(Theme.statsBg)
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
            setBackgroundColor(Theme.buttonBg)
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
        buttonsCol = buttons
        ioButton = roundButton("✎") { toggleInputOnly() }
        stripButton = roundButton("◧") {
            prefs.buttonStrip = !prefs.buttonStrip
            updateModeButtons()
        }
        padButton = roundButton("🎮") {
            gamepad.visibility = if (gamepad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateModeButtons()
        }
        magButton = roundButton("🔍") { toggleMagnifier() }
        splitButton = roundButton("▤") {
            prefs.split = !prefs.split
            applySplit()
        }
        for (b in listOf(menuButton, magButton, splitButton, padButton, stripButton, ioButton, tpButton, kbButton)) {
            buttons.addView(b, LinearLayout.LayoutParams(Theme.buttonSize, Theme.buttonSize).apply { setMargins(0, dp(6), 0, 0) })
        }
        root.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or (if (prefs.leftHanded) Gravity.START else Gravity.END)).apply {
            // 左利きなら左下に
            if (prefs.leftHanded) setMargins(dp(10), 0, 0, dp(10)) else setMargins(0, 0, dp(10), dp(10))
        })

        // ショートカットのボタンの列（右端・☰ で出し入れ）
        sideBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sideScroll = ScrollView(this).apply {
            addView(sideBar)
            visibility = View.GONE
            setBackgroundColor(Theme.panelBg)
        }
        root.addView(sideScroll, FrameLayout.LayoutParams(
            dp(120), FrameLayout.LayoutParams.WRAP_CONTENT,
            (if (prefs.leftHanded) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL).apply {
            if (prefs.leftHanded) setMargins(dp(60), 0, 0, 0) else setMargins(0, 0, dp(60), 0)
        })
        buildSplitPanel()
        buildShortcuts()
        buildStrip()
        buildGamepad()
        updateTouchpadButton()
        updateModeButtons()
        setContentView(root)
        Discovery.start(this)
        clipboard.addPrimaryClipChangedListener(clipListener)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo(); layoutPad(); layoutSplitPanel() }

        setupTouch(video)
        setupTouch(padView)
        buildSleepOverlay()
        if (prefs.mini) buildMini()
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
        applyLook()
        applySplit()
        buildShortcuts()
        updateTouchpadButton()
    }

    // ---------- 画面を分ける（上＝PC の画面、下＝ボタンとメモ） ----------

    private fun buildSplitPanel() {
        splitPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.splitBg)
            visibility = View.GONE
        }
        splitButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        splitPanel.addView(HorizontalScrollView(this).apply { addView(splitButtons) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        splitPanel.addView(EditText(this).apply {
            setText(prefs.memo)
            setHint(R.string.memo_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Theme.surface)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { prefs.memo = e?.toString() ?: "" }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(8), dp(4), dp(70), dp(8))
        })
        root.addView(splitPanel, 2, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
    }

    private fun layoutSplitPanel() {
        if (!::splitPanel.isInitialized) return
        val lp = splitPanel.layoutParams as FrameLayout.LayoutParams
        val h = root.height - availHeight()
        if (lp.height != h && h > 0) {
            lp.height = h
            splitPanel.layoutParams = lp
        }
    }

    private fun applySplit() {
        if (!::splitPanel.isInitialized) return
        splitPanel.visibility = if (prefs.split) View.VISIBLE else View.GONE
        splitButton.alpha = if (prefs.split) 1f else 0.6f
        splitButton.setBackgroundColor(if (prefs.split) Theme.active else Theme.buttonBg)
        layoutVideo()
        layoutPad()
        layoutSplitPanel()
        // PC 側の画面の大きさも合わせる
        sendSizeIfChanged()
    }

    private fun buildShortcuts() {
        if (::splitButtons.isInitialized) {
            // 画面を分けたときの下のボタン（大きめ）
            splitButtons.removeAllViews()
            for (id in prefs.shortcuts) {
                val item = Shortcuts.byId(id) ?: continue
                splitButtons.addView(TextView(this).apply {
                    setText(item.label)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setBackgroundColor(Theme.softButton2)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener {
                        if (prefs.haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        val a = JSONArray()
                        item.vks.forEach { a.put(it) }
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "combo").put("vks", a))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(4), dp(6), dp(4), dp(2))
                })
            }
        }
        sideBar.removeAllViews()
        sideBar.addView(TextView(this).apply {
            setText(R.string.file_send)
            setTextColor(Color.YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(6), dp(12))
            setOnClickListener { pickFilesToSend() }
        })
        for ((label, action) in listOf(
            R.string.launcher_open to { openLauncher() },
            (if (prefs.nativeTouch) R.string.ntouch_off else R.string.ntouch_on) to {
                prefs.nativeTouch = !prefs.nativeTouch
                Toast.makeText(this, if (prefs.nativeTouch) R.string.ntouch_on_msg else R.string.ntouch_off_msg, Toast.LENGTH_LONG).show()
                buildShortcuts()
            },
            R.string.shot_take to { takePicture() },
            R.string.quality_menu to { chooseQuality() },
            (if (powerSave) R.string.power_off else R.string.power_on) to { setPowerSave(!powerSave, true) },
            R.string.check_menu to { runCheck() },
            (if (recorder != null) R.string.rec_stop else R.string.rec_start) to { toggleRecording() },
        )) {
            sideBar.addView(TextView(this).apply {
                setText(label)
                setTextColor(Color.YELLOW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener { action() }
            })
        }
        // 定型文：タップで PC に入力
        for (text in prefs.snippets) {
            sideBar.addView(TextView(this).apply {
                this.text = "✎ " + if (text.length > 12) text.take(12) + "…" else text
                setTextColor(Theme.snippet)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    if (prefs.haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "text").put("s", text))
                }
            })
        }
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
        tpButton.setBackgroundColor(if (prefs.touchpad) Theme.active else Theme.buttonBg)
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
        // 画面を分けているときは、上の部分だけを PC の画面にする
        if (prefs.split) p.y = p.y * SPLIT / 100
        val q = prefs.quality.coerceIn(25, 100)
        return VideoDecoder.fitSize(p.x * q / 100, p.y * q / 100)
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
    /** PC の画面に使える高さ（画面を分けているときは上の部分だけ） */
    private fun availHeight() = if (prefs.split) root.height * SPLIT / 100 else root.height
    private fun videoGravity() = if (prefs.split) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER

    private fun layoutVideo() {
        val w = cfgW
        val h = cfgH
        val rw = root.width
        val rh = availHeight()
        if (w <= 0 || h <= 0 || rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / w, rh.toFloat() / h)
        val bw = (w * scale).toInt()
        val bh = (h * scale).toInt()
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (magOn && zoom > 1.001f) {
            // 拡大鏡：大きくした映像を、はみ出さない範囲で動かす
            val lw = (bw * zoom).toInt()
            val lh = (bh * zoom).toInt()
            val baseL = (rw - bw) / 2f
            val baseT = if (prefs.split) 0f else (rh - bh) / 2f
            panX = panX.coerceIn(bw - lw.toFloat(), 0f)
            panY = panY.coerceIn(bh - lh.toFloat(), 0f)
            lp.width = lw
            lp.height = lh
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (baseL + panX).toInt()
            lp.topMargin = (baseT + panY).toInt()
            video.layoutParams = lp
        } else if (lp.width != bw || lp.height != bh || lp.gravity != videoGravity() || lp.leftMargin != 0 || lp.topMargin != 0) {
            lp.width = bw
            lp.height = bh
            lp.gravity = videoGravity()
            lp.leftMargin = 0
            lp.topMargin = 0
            video.layoutParams = lp
        }
        layoutSplitPanel()
    }

    // ---------- クリップボード・ファイル ----------

    override fun onClip(text: String) {
        if (!prefs.clipboard) return
        ui.post {
            lastRemoteClip = text
            try { clipboard.setPrimaryClip(ClipData.newPlainText("TabDisplay", text)) } catch (_: Exception) {}
        }
    }

    override fun onFileBegin(id: Int, name: String, size: Long) {
        try {
            receiving[id] = FileReceiver(this, name)
            ui.post { Toast.makeText(this, getString(R.string.file_receiving, name), Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            ui.post { Toast.makeText(this, getString(R.string.file_failed, name), Toast.LENGTH_LONG).show() }
        }
    }

    override fun onFileData(id: Int, data: ByteArray) {
        try { receiving[id]?.write(data, 4, data.size - 4) } catch (_: Exception) {}
    }

    override fun onFileEnd(id: Int) {
        val r = receiving.remove(id) ?: return
        r.finish()
        ui.post { Toast.makeText(this, getString(R.string.file_saved, r.where), Toast.LENGTH_LONG).show() }
    }

    /** タブレットのファイルを選んで PC に送る（PC の「ダウンロード\TabDisplay」に保存される） */
    private fun pickFilesToSend() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SEND_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SEND_FILES || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val c = conn ?: return
        Thread {
            var id = (SystemClock.elapsedRealtime() and 0x7fffffff).toInt()
            for (u in uris) {
                id++
                val name = queryName(u)
                try {
                    contentResolver.openInputStream(u)?.use { input ->
                        c.send(Protocol.T_FILE_BEGIN, JSONObject().put("id", id).put("name", name))
                        val buf = ByteArray(256 * 1024 + 4)
                        buf[0] = (id ushr 24).toByte(); buf[1] = (id ushr 16).toByte()
                        buf[2] = (id ushr 8).toByte(); buf[3] = id.toByte()
                        while (true) {
                            val n = input.read(buf, 4, buf.size - 4)
                            if (n <= 0) break
                            c.send(Protocol.T_FILE_DATA, buf.copyOf(4 + n))
                            Thread.sleep(2)
                        }
                        c.send(Protocol.T_FILE_END, JSONObject().put("id", id))
                    }
                    ui.post { Toast.makeText(this, getString(R.string.file_sent, name), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    ui.post { Toast.makeText(this, getString(R.string.file_failed, name), Toast.LENGTH_LONG).show() }
                }
            }
        }.start()
    }

    private fun queryName(u: android.net.Uri): String {
        try {
            contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return u.lastPathSegment ?: "file"
    }

    // ---------- 入力専用（ペンタブレット） ----------

    override fun inputOnly(): Boolean = prefs.inputOnly || prefs.mini
    override fun wantsNotify(): Boolean = prefs.mini

    override fun onArea(w: Int, h: Int) {
        ui.post {
            areaW = maxOf(1, w); areaH = maxOf(1, h)
            layoutPad()
        }
    }

    /** PC の画面と同じ縦横比で、タブレットいっぱいに置く */
    private fun layoutPad() {
        val rw = root.width
        val rh = availHeight()
        if (rw <= 0 || rh <= 0) return
        val scale = minOf(rw.toFloat() / areaW, rh.toFloat() / areaH)
        val lp = padView.layoutParams as FrameLayout.LayoutParams
        val lw = (areaW * scale).toInt()
        val lh = (areaH * scale).toInt()
        if (lp.width != lw || lp.height != lh || lp.gravity != videoGravity()) {
            lp.width = lw; lp.height = lh
            lp.gravity = videoGravity()
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
            b.setBackgroundColor(if (on) Theme.active else Theme.buttonBg)
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
            setBackgroundColor(Theme.stripBg)
        }
        fun holdButton(label: String, set: (Boolean) -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { set(true); v.setBackgroundColor(Theme.active) }
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
            setBackgroundColor(Theme.softButton)
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
        // 左利きのときは、ボタンの列とぶつからないよう右端に
        root.addView(strip, FrameLayout.LayoutParams(dp(56), FrameLayout.LayoutParams.MATCH_PARENT,
            if (prefs.leftHanded) Gravity.END else Gravity.START))
    }

    // ---------- ゲームパッド（キーボードのキーとして送る。配置・大きさ・キーは変えられる） ----------

    private var padKeys: MutableList<PadKey> = mutableListOf()
    private var padEdit = false
    private var padScale = 1f
    private lateinit var padToolbar: LinearLayout

    private fun buildGamepad() {
        gamepad = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(gamepad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        padScale = getSharedPreferences("tabdisplay", MODE_PRIVATE).getFloat("pad_scale", 1f)
        padKeys = GamepadLayout.load(this)
        // 配置を変えるための帯（上の真ん中）
        padToolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Theme.panelBg) }
        fun tb(label: Int, f: () -> Unit) = TextView(this).apply {
            setText(label)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { f() }
        }
        padToolbar.addView(tb(R.string.pad_edit) {
            padEdit = !padEdit
            if (!padEdit) GamepadLayout.save(this, padKeys)
            Toast.makeText(this, if (padEdit) R.string.pad_edit_on else R.string.pad_edit_off, Toast.LENGTH_SHORT).show()
            buildPadKeys()
        })
        padToolbar.addView(tb(R.string.pad_bigger) { padScale = minOf(2f, padScale + 0.1f); savePadScale(); buildPadKeys() })
        padToolbar.addView(tb(R.string.pad_smaller) { padScale = maxOf(0.5f, padScale - 0.1f); savePadScale(); buildPadKeys() })
        padToolbar.addView(tb(R.string.pad_reset) {
            GamepadLayout.reset(this)
            padKeys = GamepadLayout.load(this)
            padScale = 1f; savePadScale()
            buildPadKeys()
        })
        gamepad.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) buildPadKeys() }
    }

    private fun savePadScale() {
        getSharedPreferences("tabdisplay", MODE_PRIVATE).edit().putFloat("pad_scale", padScale).apply()
    }

    /** ボタンを置き直す（画面の大きさ・配置・大きさが変わったとき） */
    private fun buildPadKeys() {
        gamepad.removeAllViews()
        val rw = gamepad.width
        val rh = gamepad.height
        if (rw <= 0 || rh <= 0) return
        gamepad.addView(padToolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (k in padKeys) {
            val w = (dp(k.w) * padScale).toInt()
            val h = (dp(k.h) * padScale).toInt()
            val v = TextView(this).apply {
                text = k.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * padScale)
                gravity = Gravity.CENTER
                setBackgroundColor(if (padEdit) Theme.edit else Theme.buttonBg)
                alpha = 0.85f
            }
            val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
                leftMargin = (k.x * rw - w / 2).toInt().coerceIn(0, maxOf(0, rw - w))
                topMargin = (k.y * rh - h / 2).toInt().coerceIn(0, maxOf(0, rh - h))
            }
            var downX = 0f
            var downY = 0f
            var moved = false
            v.setOnTouchListener { view, e ->
                if (padEdit) {
                    // 配置を変える：なぞると動く、タップで割り当てるキーを選ぶ
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; moved = false }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (moved || hypot(dx, dy) > dp(8)) {
                                moved = true
                                val p = view.layoutParams as FrameLayout.LayoutParams
                                p.leftMargin = (p.leftMargin + dx).toInt().coerceIn(0, maxOf(0, rw - w))
                                p.topMargin = (p.topMargin + dy).toInt().coerceIn(0, maxOf(0, rh - h))
                                view.layoutParams = p
                                downX = e.rawX; downY = e.rawY
                                k.x = (p.leftMargin + w / 2f) / rw
                                k.y = (p.topMargin + h / 2f) / rh
                            }
                        }
                        MotionEvent.ACTION_UP -> if (!moved) choosePadKey(k)
                    }
                    return@setOnTouchListener true
                }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (prefs.haptic) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        view.setBackgroundColor(Theme.active)
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", k.vk).put("d", true))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.setBackgroundColor(Theme.buttonBg)
                        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", k.vk).put("d", false))
                    }
                }
                true
            }
            gamepad.addView(v, lp)
        }
    }

    private fun choosePadKey(k: PadKey) {
        val names = GamepadLayout.choices.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pad_choose, k.label))
            .setItems(names) { _, i ->
                val (name, vk) = GamepadLayout.choices[i]
                k.vk = vk
                if (k.id !in listOf("up", "down", "left", "right")) k.label = name
                GamepadLayout.save(this, padKeys)
                buildPadKeys()
            }
            .show()
    }

    // ---------- 拡大鏡（映像そのものを拡大して見る） ----------
    // 🔍 をオンにすると：2 本指で広げる・つまむ → 拡大縮小、1 本指でなぞる → 動かす、タップ → クリック、長押し → 右クリック

    private var magOn = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private lateinit var magButton: TextView

    private var mDownX = 0f
    private var mDownY = 0f
    private var mLastX = 0f
    private var mLastY = 0f
    private var mDist = 0f
    private var mMoved = false
    private var mTwo = false
    private var mDownTime = 0L

    private fun toggleMagnifier() {
        magOn = !magOn
        if (!magOn) { zoom = 1f; panX = 0f; panY = 0f }
        magButton.alpha = if (magOn) 1f else 0.6f
        magButton.setBackgroundColor(if (magOn) Theme.active else Theme.buttonBg)
        Toast.makeText(this, if (magOn) R.string.mag_on else R.string.mag_off, Toast.LENGTH_SHORT).show()
        layoutVideo()
    }

    private fun handleMagnify(v: View, e: MotionEvent) {
        val now = SystemClock.elapsedRealtime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = e.rawX; mDownY = e.rawY; mLastX = e.rawX; mLastY = e.rawY
                mMoved = false; mTwo = false; mDownTime = now
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                mTwo = true
                mDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
            }
            MotionEvent.ACTION_MOVE -> {
                if (mTwo && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    if (mDist > 0 && d > 0) {
                        val nz = (zoom * d / mDist).coerceIn(1f, 5f)
                        // 指の真ん中を中心に拡大する
                        val fx = (e.getX(0) + e.getX(1)) / 2
                        val fy = (e.getY(0) + e.getY(1)) / 2
                        val k = nz / zoom
                        panX -= fx * (k - 1)
                        panY -= fy * (k - 1)
                        zoom = nz
                        mDist = d
                        layoutVideo()
                    }
                } else if (!mTwo) {
                    if (hypot(e.rawX - mDownX, e.rawY - mDownY) > dp(8)) mMoved = true
                    if (mMoved) {
                        panX += e.rawX - mLastX
                        panY += e.rawY - mLastY
                        layoutVideo()
                    }
                    mLastX = e.rawX; mLastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!mMoved && !mTwo) {
                    val long = now - mDownTime > ViewConfiguration.getLongPressTimeout()
                    touchView = v
                    sendInput(if (long) "rclick" else "click", e.x, e.y)
                }
            }
        }
    }

    // ---------- 明るさ・色（夜は暖色）・コントラスト ----------

    private lateinit var warmView: View

    private fun applyLook() {
        val b = prefs.brightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (b < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b / 100f
        }
        // 暖色：だいだい色の薄い膜を重ねる（さわっても下に通る）
        warmView.setBackgroundColor(Color.argb((prefs.warmth * 0.45f * 255 / 100).toInt(), 255, 140, 0))
        warmView.visibility = if (prefs.warmth > 0) View.VISIBLE else View.GONE
        conn?.post(Protocol.T_OPTS, JSONObject().put("contrast", prefs.contrast / 100.0))
    }

    override fun contrast(): Double = prefs.contrast / 100.0

    // ---------- 画面写真・録画 ----------

    @Volatile private var recorder: Recorder? = null

    private fun takePicture() {
        if (Build.VERSION.SDK_INT < 24 || cfgW <= 0) {
            Toast.makeText(this, R.string.shot_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = android.graphics.Bitmap.createBitmap(cfgW, cfgH, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(video, bmp, { r ->
            if (r == android.view.PixelCopy.SUCCESS) {
                Thread {
                    val where = try { Recorder.savePicture(this, bmp) } catch (e: Exception) { null }
                    ui.post {
                        Toast.makeText(this, if (where != null) getString(R.string.shot_saved, where) else getString(R.string.shot_failed), Toast.LENGTH_LONG).show()
                    }
                }.start()
            } else {
                Toast.makeText(this, R.string.shot_failed, Toast.LENGTH_SHORT).show()
            }
        }, ui)
    }

    private fun toggleRecording() {
        val r = recorder
        if (r != null) {
            recorder = null
            Thread {
                val where = r.stop()
                ui.post {
                    Toast.makeText(this, if (where != null) getString(R.string.rec_saved, where) else getString(R.string.rec_failed), Toast.LENGTH_LONG).show()
                    buildShortcuts()
                }
            }.start()
            return
        }
        val sps = cfgSps
        val pps = cfgPps
        if (sps == null || pps == null || cfgW <= 0) return
        try {
            recorder = Recorder(this, if (cfgCodec == "hevc") VideoDecoder.HEVC else VideoDecoder.MIME, cfgW, cfgH, sps, pps, cfgVps)
            lastIdrRequest = 0L
            requestIdr() // キーフレームから始めるため
            Toast.makeText(this, R.string.rec_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show()
        }
        buildShortcuts()
    }

    // ---------- PC の通知 ----------

    override fun onNotify(app: String, title: String, body: String) {
        ui.post {
            addMiniNotice(app, title, body)
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel("pc", getString(R.string.notify_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "pc") else android.app.Notification.Builder(this)
            val n = b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (app.isNotEmpty()) "$title（$app）" else title)
                .setContentText(body)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
            try { nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), n) } catch (_: Exception) {}
        }
    }

    // ---------- タッチをそのまま Windows のタッチとして送る ----------

    private fun sendNativeTouch(v: View, e: MotionEvent) {
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0 || h <= 0) return
        val a = JSONArray()
        val act = e.actionMasked
        for (i in 0 until e.pointerCount) {
            val st = when {
                act == MotionEvent.ACTION_CANCEL -> "u"
                i == e.actionIndex && (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) -> "d"
                i == e.actionIndex && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) -> "u"
                else -> "m"
            }
            a.put(JSONObject().put("id", e.getPointerId(i))
                .put("x", (e.getX(i) / w).coerceIn(0f, 1f).toDouble())
                .put("y", (e.getY(i) / h).coerceIn(0f, 1f).toDouble())
                .put("s", st))
        }
        conn?.post(Protocol.T_INPUT, JSONObject().put("k", "touch").put("pts", a))
    }

    // ---------- カメラ・マイク（PC に頼まれたら送る） ----------

    private var camSender: CameraSender? = null
    private var micSender: MicSender? = null
    private var pendingCam: String? = null
    private var pendingMic = false

    override fun onCamera(on: Boolean, facing: String) {
        ui.post {
            camSender?.stop(); camSender = null
            if (!on) return@post
            val c = conn ?: return@post
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingCam = facing
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM)
                return@post
            }
            camSender = CameraSender(this, c, facing).also { it.start() }
            Toast.makeText(this, R.string.cam_started, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMic(on: Boolean) {
        ui.post {
            micSender?.stop(); micSender = null
            if (!on) return@post
            val c = conn ?: return@post
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingMic = true
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
                return@post
            }
            micSender = MicSender(c).also { it.start() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_CAM && ok) pendingCam?.let { onCamera(true, it) }
        if (requestCode == REQ_MIC && ok && pendingMic) onMic(true)
        pendingCam = null
        pendingMic = false
    }

    // ---------- ランチャー（PC のソフトを開く） ----------

    private fun openLauncher() {
        sideScroll.visibility = View.GONE
        conn?.post(Protocol.T_APPS_REQ)
    }

    override fun onApps(items: JSONArray) {
        ui.post {
            if (items.length() == 0) {
                Toast.makeText(this, R.string.launcher_empty, Toast.LENGTH_LONG).show()
                return@post
            }
            val grid = android.widget.GridLayout(this).apply { columnCount = 4 }
            var dlg: android.app.AlertDialog? = null
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    val icon = o.optString("icon", "")
                    if (icon.isNotEmpty() && icon != "null") {
                        val b = Base64.decode(icon, Base64.NO_WRAP)
                        addView(android.widget.ImageView(this@DisplayActivity).apply {
                            setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size))
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    }
                    addView(TextView(this@DisplayActivity).apply {
                        text = o.optString("name")
                        gravity = Gravity.CENTER
                        maxLines = 2
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    }, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
                    setOnClickListener {
                        conn?.post(Protocol.T_LAUNCH, JSONObject().put("id", o.optInt("id")))
                        dlg?.dismiss()
                    }
                }
                grid.addView(cell)
            }
            dlg = android.app.AlertDialog.Builder(this)
                .setTitle(R.string.launcher_open)
                .setView(ScrollView(this).apply { addView(grid) })
                .setNegativeButton(R.string.guide_close, null)
                .show()
        }
    }

    // ---------- つないだまま画面を消す（PC が使われていないとき） ----------

    private lateinit var sleepView: View
    private var sleeping = false

    private fun buildSleepOverlay() {
        sleepView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, e ->
                // さわったら起こす（PC も起こす）
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    setSleep(false)
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "rel").put("dx", 1).put("dy", 0))
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "rel").put("dx", -1).put("dy", 0))
                }
                true
            }
        }
        root.addView(sleepView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onSleep(on: Boolean) {
        ui.post { setSleep(on) }
    }

    private fun setSleep(on: Boolean) {
        if (sleeping == on) return
        sleeping = on
        sleepView.visibility = if (on) View.VISIBLE else View.GONE
        sleepView.bringToFront()
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else if (prefs.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness / 100f
        }
    }

    // ---------- ミニ画面（スマホ向け：時計・PC の通知・ショートカット） ----------

    private var miniList: LinearLayout? = null
    private var miniClock: TextView? = null
    private var miniDate: TextView? = null

    private fun buildMini() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.miniBg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
        }
        miniClock = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER
        }
        miniDate = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        col.addView(miniClock)
        col.addView(miniDate)
        // ショートカットのボタン
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (id in prefs.shortcuts) {
            val item = Shortcuts.byId(id) ?: continue
            row.addView(TextView(this).apply {
                setText(item.label)
                setTextColor(Color.WHITE)
                setBackgroundColor(Theme.softButton)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    val a = JSONArray()
                    item.vks.forEach { a.put(it) }
                    conn?.post(Protocol.T_INPUT, JSONObject().put("k", "combo").put("vks", a))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(12), dp(4), dp(4))
            })
        }
        col.addView(HorizontalScrollView(this).apply { addView(row) })
        col.addView(TextView(this).apply {
            setText(R.string.mini_notify)
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        })
        miniList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { addView(miniList) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val tick = object : Runnable {
            override fun run() {
                val now = java.util.Date()
                miniClock?.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now)
                miniDate?.text = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(now)
                ui.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    private fun addMiniNotice(app: String, title: String, body: String) {
        val list = miniList ?: return
        list.addView(TextView(this).apply {
            text = (if (app.isNotEmpty()) "[$app] " else "") + title + if (body.isNotEmpty()) "\n" + body else ""
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
        }, 0)
        while (list.childCount > 30) list.removeViewAt(list.childCount - 1)
    }

    // ---------- 小窓（ピクチャー・イン・ピクチャー、Android 8 以降） ----------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!prefs.pip || Build.VERSION.SDK_INT < 26 || cfgW <= 0 || prefs.mini) return
        try {
            // 縦横比は 1:2.39〜2.39:1 の間にする（Android の決まり）
            var w = cfgW
            var h = cfgH
            if (w > h * 239 / 100) w = h * 239 / 100
            if (h > w * 239 / 100) h = w * 239 / 100
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(w, h)).build())
        } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 小窓のときは、映像だけ出す
        val v = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        buttonsCol?.visibility = v
        if (isInPictureInPictureMode) {
            sideScroll.visibility = View.GONE
            strip.visibility = View.GONE
            gamepad.visibility = View.GONE
            splitPanel.visibility = View.GONE
            message.visibility = View.GONE
        } else {
            updateModeButtons()
            applySplit()
        }
        ui.postDelayed({ layoutVideo() }, 100)
    }

    // ---------- 接続 ----------

    override fun onConnecting(host: String) {
        ui.post { if (cfgW == 0) showMessage(getString(R.string.connecting, target?.name ?: host)) }
    }

    override fun onConnected(host: String) {
        ui.post {
            target?.let { prefs.remember(it) }
            lastSentSize = currentSize() // 接続のときに送った大きさ
            sendPowerAndQuality()
            showMessage(getString(R.string.waiting_video))
        }
    }

    override fun onConfig(w: Int, h: Int, fps: Int, codec: String, vps: ByteArray?, sps: ByteArray, pps: ByteArray) {
        // 大きさが変わったら録画はそこで終える（1 つの動画の中で大きさは変えられない）
        if ((w != cfgW || h != cfgH) && recorder != null) ui.post { toggleRecording() }
        synchronized(decLock) {
            cfgW = w; cfgH = h; cfgSps = sps; cfgPps = pps; cfgVps = vps; cfgCodec = codec
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
            val mime = if (cfgCodec == "hevc") VideoDecoder.HEVC else VideoDecoder.MIME
            decoder = VideoDecoder(video.holder.surface, cfgW, cfgH, sps, pps, this, mime, cfgVps).also { it.start() }
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
        recorder?.let { r -> try { r.write(data, offset, length, pcTimeUs, key) } catch (_: Exception) {} }
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
        lastStatus = s
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
            if (e == "pin") {
                askPin()
                return@post
            }
            showMessage(when (e) {
                "token" -> getString(R.string.err_token)
                "tls" -> getString(R.string.err_tls)
                else -> e
            })
        }
    }

    override fun takeCrash(): JSONObject? = CrashLog.take(this)

    override fun pin(): String {
        val t = target ?: return ""
        return prefs.pinFor(t.token).ifEmpty { t.pin }
    }

    /** PC に PIN を聞かれた：入れてもらって、つなぎ直す */
    private fun askPin() {
        val t = target ?: return
        val box = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_title, t.name))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setPin(t.token, box.text.toString().trim())
                conn?.close()
                showMessage(getString(R.string.connecting, t.name))
                conn = Connection(t, this).also { it.start() }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    override fun onLost(willRetry: Boolean) {
        ui.post { showMessage(getString(if (willRetry) R.string.retrying else R.string.disconnected)) }
    }

    // ---------- デコーダー ----------

    override fun onFrameRendered(pcTimeUs: Long) {
        val c = conn ?: return
        framesThisSecond++
        lastFrameAt = SystemClock.elapsedRealtime()
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
            checkWeakLink(fps, lat, rtt)
            if (++powerTick % 30 == 1) checkPower()
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
            if (isPen(e)) lastPenAt = SystemClock.elapsedRealtime()
            else if (palmFilter(e)) return@setOnTouchListener true
            if (magOn && v === video && !isPen(e)) {
                handleMagnify(v, e)
                return@setOnTouchListener true
            }
            if (prefs.nativeTouch && !prefs.touchpad && !isPen(e)) {
                sendNativeTouch(v, e)
                return@setOnTouchListener true
            }
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
        // 「キーで送る」モード（ゲーム向け）：記号も含めて全部キーで送る
        if (prefs.keyMode) {
            val vk = KeyMap.toVkAll(code)
            if (vk != null) {
                conn?.post(Protocol.T_INPUT, JSONObject().put("k", "key").put("vk", vk).put("d", down))
                return true
            }
        }
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

    // ---------- 手のひらの誤タッチ防止 ----------
    // ペンを使っている間（ペンが近い・さわった直後）と、指よりずっと大きな面のタッチは無視する

    private var lastPenAt = 0L
    private var palmBlocked = false

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // ペンを浮かせているとき（ホバー）も「ペンを使っている」とみなす
        if (isPen(ev)) lastPenAt = SystemClock.elapsedRealtime()
        return super.dispatchGenericMotionEvent(ev)
    }

    /** true なら、このタッチは無視する */
    private fun palmFilter(e: MotionEvent): Boolean {
        if (!prefs.palmReject || palmCancelling) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val penNear = SystemClock.elapsedRealtime() - lastPenAt < 700
                palmBlocked = penNear || isPalm(e)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> if (!palmBlocked && isPalm(e)) {
                // 途中で手のひらが乗ったら、そこで止める
                palmBlocked = true
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                palmCancel(c)
                c.recycle()
                return true
            }
        }
        val blocked = palmBlocked
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) palmBlocked = false
        return blocked
    }

    private var palmCancelling = false

    /** 手のひらと分かったとき、始まっていた操作を取り消す（ふつうのタッチ処理に CANCEL を流す） */
    private fun palmCancel(c: MotionEvent) {
        if (palmCancelling) return
        palmCancelling = true
        try { touchView?.dispatchTouchEvent(c) } finally { palmCancelling = false }
    }

    private fun isPalm(e: MotionEvent): Boolean {
        val limit = dp(110) // 指はふつう 60dp ほど。それよりずっと大きい面は手のひら
        for (i in 0 until e.pointerCount) {
            if (e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) continue
            if (e.getTouchMajor(i) > limit) return true
        }
        return false
    }

    // ---------- 省電力モード（電池が少ない・熱いとき、聞いてから） ----------

    private var powerSave = false
    private var powerTick = 0
    private var powerAskedAt = 0L
    private var powerDialog: android.app.AlertDialog? = null

    private fun checkPower() {
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val low = pct <= 20 && !plugged
        val hot = temp >= 43.0
        if (powerSave) {
            // 充電を始めて、熱も下がったら元に戻す
            if (!low && !hot && (plugged || pct > 30) && temp < 40.0) {
                setPowerSave(false, false)
                Toast.makeText(this, R.string.power_auto_off, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!prefs.powerAsk || conn == null || (!low && !hot)) return
        if (powerDialog?.isShowing == true) return
        // 「いいえ」のあとは 30 分聞かない
        if (SystemClock.elapsedRealtime() - powerAskedAt < 30 * 60_000L && powerAskedAt != 0L) return
        powerAskedAt = SystemClock.elapsedRealtime()
        val msg = if (hot) getString(R.string.power_hot_msg, temp.toInt()) else getString(R.string.power_low_msg, pct)
        powerDialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (hot) R.string.power_hot_title else R.string.power_low_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(msg + "\n\n" + getString(R.string.power_question))
            .setPositiveButton(R.string.power_yes) { _, _ -> setPowerSave(true, true) }
            .setNegativeButton(R.string.power_no, null)
            .setNeutralButton(R.string.power_never) { _, _ -> prefs.powerAsk = false }
            .show()
    }

    private fun setPowerSave(on: Boolean, notify: Boolean) {
        powerSave = on
        conn?.post(Protocol.T_OPTS, JSONObject().put("power", on))
        // 画面も少し暗くする
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.3f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (notify) Toast.makeText(this, if (on) R.string.power_on_msg else R.string.power_off_msg, Toast.LENGTH_LONG).show()
        buildShortcuts()
    }

    // ---------- タブレットから画質を選ぶ ----------

    private fun chooseQuality() {
        val keys = arrayOf("pc", "auto", "text", "motion")
        val names = arrayOf(getString(R.string.q2_pc), getString(R.string.q2_auto), getString(R.string.q2_text), getString(R.string.q2_motion))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.quality_menu)
            .setSingleChoiceItems(names, keys.indexOf(prefs.quality2).coerceAtLeast(0)) { d, i ->
                prefs.quality2 = keys[i]
                conn?.post(Protocol.T_OPTS, JSONObject().put("quality", keys[i]))
                d.dismiss()
            }
            .show()
    }

    private fun sendPowerAndQuality() {
        val o = JSONObject().put("quality", prefs.quality2)
        if (powerSave) o.put("power", true)
        conn?.post(Protocol.T_OPTS, o)
    }

    // ---------- Wi-Fi が不安定なときに知らせる ----------

    private var weakTicks = 0
    private var goodTicks = 0
    private var weakBar: TextView? = null
    private var weakDismissedAt = 0L

    private fun checkWeakLink(fps: Int, lat: Int, rtt: Int) {
        val t = target
        if (!prefs.weakWarn || t == null || t.usb || conn == null) { hideWeak(); return }
        val active = SystemClock.elapsedRealtime() - lastFrameAt < 3000
        val bad = active && (rtt > 80 || lat > 150)
        if (bad) { weakTicks++; goodTicks = 0 } else { goodTicks++; if (goodTicks >= 5) weakTicks = 0 }
        if (weakTicks >= 4 && SystemClock.elapsedRealtime() - weakDismissedAt > 10 * 60_000L) showWeak()
        if (goodTicks >= 8) hideWeak()
    }

    private fun showWeak() {
        if (weakBar != null) return
        val bar = TextView(this).apply {
            text = getString(R.string.weak_msg)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Theme.weakBg)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showUsbHelp() }
        }
        weakBar = bar
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
    }

    private fun hideWeak() {
        weakBar?.let { root.removeView(it) }
        weakBar = null
    }

    private fun showUsbHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.weak_title)
            .setMessage(R.string.weak_help)
            .setPositiveButton(R.string.weak_try_usb) { _, _ ->
                // PC の「接続する」で USB の道ができていれば、そのままつながる
                val t = target ?: return@setPositiveButton
                hideWeak()
                val usb = t.copy(hosts = listOf("127.0.0.1"), usb = true)
                target = usb
                conn?.close()
                conn = Connection(usb, this).also { it.start() }
            }
            .setNegativeButton(R.string.weak_later) { _, _ ->
                weakDismissedAt = SystemClock.elapsedRealtime()
                hideWeak()
            }
            .show()
    }

    // ---------- 動作チェック（映像・タッチ・音を順に確かめる） ----------

    @Volatile private var lastFrameAt = 0L
    @Volatile private var lastStatus = ""
    private var checkReply: ((JSONObject) -> Unit)? = null

    override fun onCheck(j: JSONObject) {
        ui.post {
            val f = checkReply
            checkReply = null
            f?.invoke(j)
        }
    }

    /** PC に問い合わせて、3 秒以内に答えが来なければ null */
    private fun askPc(step: String, done: (JSONObject?) -> Unit) {
        val c = conn
        if (c == null) { done(null); return }
        var finished = false
        checkReply = { j -> if (!finished) { finished = true; done(j) } }
        c.post(Protocol.T_CHECK, JSONObject().put("step", step))
        ui.postDelayed({ if (!finished) { finished = true; checkReply = null; done(null) } }, 3000)
    }

    private fun runCheck() {
        sideScroll.visibility = View.GONE
        val results = ArrayList<String>()
        // 1. つながっているか・映像
        askPc("link") { j ->
            if (j == null) {
                results.add("✕ " + getString(R.string.check_link_ng))
                showCheckResult(results)
                return@askPc
            }
            results.add("○ " + getString(R.string.check_link_ok))
            val videoOk = SystemClock.elapsedRealtime() - lastFrameAt < 3000
            results.add(when {
                videoOk -> "○ " + getString(R.string.check_video_ok)
                lastStatus == "input_only" -> "－ " + getString(R.string.check_video_input_only)
                lastStatus == "pc_only" -> "✕ " + getString(R.string.check_video_pc_only)
                lastStatus == "no_driver" -> "✕ " + getString(R.string.check_video_no_driver)
                else -> "✕ " + getString(R.string.check_video_ng)
            })
            // 2. タッチ：PC のマウスを画面の真ん中に動かして、見えたか聞く
            val tv = touchView ?: video
            touchView = tv
            sendInput("move", tv.width / 2f, tv.height / 2f)
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.check_title)
                .setMessage(R.string.check_touch_q)
                .setCancelable(false)
                .setPositiveButton(R.string.check_yes) { _, _ ->
                    results.add("○ " + getString(R.string.check_touch_ok)); checkSound(results)
                }
                .setNegativeButton(R.string.check_no) { _, _ ->
                    results.add("✕ " + getString(R.string.check_touch_ng)); checkSound(results)
                }
                .show()
        }
    }

    private fun checkSound(results: ArrayList<String>) {
        if (!prefs.audio) {
            results.add("✕ " + getString(R.string.check_sound_tablet_off))
            showCheckResult(results)
            return
        }
        askPc("sound") { j ->
            if (j == null || !j.optBoolean("audio")) {
                results.add("✕ " + getString(R.string.check_sound_pc_off))
                showCheckResult(results)
                return@askPc
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.check_title)
                .setMessage(R.string.check_sound_q)
                .setCancelable(false)
                .setPositiveButton(R.string.check_yes) { _, _ ->
                    results.add("○ " + getString(R.string.check_sound_ok)); showCheckResult(results)
                }
                .setNegativeButton(R.string.check_no) { _, _ ->
                    results.add("✕ " + getString(R.string.check_sound_ng)); showCheckResult(results)
                }
                .setNeutralButton(R.string.check_again) { _, _ -> checkSound(results) }
                .show()
        }
    }

    private fun showCheckResult(results: List<String>) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.check_result)
            .setMessage(results.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        Discovery.stop()
        camSender?.stop(); camSender = null
        micSender?.stop(); micSender = null
        recorder?.let { r -> recorder = null; Thread { r.stop() }.start() }
        try { clipboard.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        receiving.values.forEach { it.finish() }
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

    companion object {
        private const val REQ_SEND_FILES = 7
        private const val REQ_CAM = 8
        private const val REQ_MIC = 9
        /** 画面を分けるとき、PC の画面に使う高さ（%） */
        private const val SPLIT = 65
    }
}
