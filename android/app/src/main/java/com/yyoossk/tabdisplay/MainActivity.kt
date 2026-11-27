package com.yyoossk.tabdisplay

import android.app.Activity
import android.app.AlertDialog
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

/**
 * はじめの画面。見た目はあとで差し替える前提で、部品だけ並べている。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var recentBox: LinearLayout
    private lateinit var shortcutBox: LinearLayout
    /** 開いてから 1 回だけ自動接続する（切断して戻ってきたときにまたつながらないように） */
    private var autoDone = false
    private val onFound: (Discovery.Found) -> Unit = { f ->
        if (prefs.autoConnect && !autoDone) {
            val t = prefs.recent().firstOrNull { it.pcId == f.id }
            if (t != null) {
                autoDone = true
                runOnUiThread { open(t) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        handleIntent(intent)
        // 初めて開いたときは、準備のしかたを案内する
        val sp = getSharedPreferences("tabdisplay", MODE_PRIVATE)
        if (!sp.getBoolean("guided", false)) {
            sp.edit().putBoolean("guided", true).apply()
            showGuide()
        }
        AppUpdate.check(this, quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
        Discovery.start(this)
        Discovery.addListener(onFound)
    }

    override fun onPause() {
        Discovery.removeListener(onFound)
        Discovery.stop()
        super.onPause()
    }

    /** QR の URL（tabdisplay://connect?...）や adb からの起動に対応 */
    private fun handleIntent(i: Intent?) {
        val t = Target.fromUri(i?.data) ?: Target.fromAdbIntent(i) ?: return
        open(t)
    }

    private fun open(t: Target) {
        autoDone = true
        startActivity(t.putInto(Intent(this, DisplayActivity::class.java)))
    }

    private fun buildUi() {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(title(R.string.usb_title))
        col.addView(body(getString(R.string.usb_body)))

        col.addView(Button(this).apply {
            setText(R.string.guide_open)
            setOnClickListener { showGuide() }
        })

        col.addView(title(R.string.tether_title))
        col.addView(body(getString(R.string.tether_body)))
        col.addView(Button(this).apply {
            setText(R.string.tether_open)
            setOnClickListener { openTetherSettings() }
        })

        col.addView(title(R.string.wifi_title))
        col.addView(body(getString(R.string.wifi_body)))
        col.addView(Button(this).apply {
            setText(R.string.scan_qr)
            setOnClickListener { scan() }
        })

        col.addView(body(getString(R.string.gestures)))
        col.addView(body(getString(R.string.buttons_help)))

        col.addView(Button(this).apply {
            setText(R.string.sender_open)
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, SenderActivity::class.java)) }
        })

        col.addView(title(R.string.recent_title))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentBox)

        col.addView(title(R.string.settings_title))
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_stats)
            isChecked = prefs.showStats
            setOnCheckedChangeListener { _, b -> prefs.showStats = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_auto)
            isChecked = prefs.autoConnect
            setOnCheckedChangeListener { _, b -> prefs.autoConnect = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_audio)
            isChecked = prefs.audio
            setOnCheckedChangeListener { _, b -> prefs.audio = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_clip)
            isChecked = prefs.clipboard
            setOnCheckedChangeListener { _, b -> prefs.clipboard = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_split)
            isChecked = prefs.split
            setOnCheckedChangeListener { _, b -> prefs.split = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_dim)
            isChecked = prefs.dim
            setOnCheckedChangeListener { _, b -> prefs.dim = b }
        })
        col.addView(CheckBox(this).apply {
            setText(R.string.opt_haptic)
            isChecked = prefs.haptic
            setOnCheckedChangeListener { _, b -> prefs.haptic = b }
        })
        col.addView(body(getString(R.string.opt_quality)))
        val group = RadioGroup(this)
        listOf(100 to R.string.quality_full, 75 to R.string.quality_75, 50 to R.string.quality_50).forEach { (q, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = prefs.quality == q
                setOnCheckedChangeListener { _, b -> if (b) prefs.quality = q }
            })
        }
        col.addView(group)

        col.addView(title(R.string.shortcuts_title))
        col.addView(body(getString(R.string.shortcuts_help)))
        shortcutBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(shortcutBox)
        refreshShortcuts()

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply {
            setText(R.string.upd_check)
            setOnClickListener { AppUpdate.check(this@MainActivity, quiet = false) }
        })
        tools.addView(Button(this).apply {
            setText(R.string.log_share)
            setOnClickListener { shareLog() }
        })
        col.addView(tools)

        col.addView(title(R.string.notes_title))
        col.addView(body(getString(R.string.notes)).apply { alpha = 0.75f })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val list = prefs.recent()
        if (list.isEmpty()) {
            recentBox.addView(body(getString(R.string.recent_none)))
            return
        }
        for (t in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Button(this).apply {
                text = t.name
                isAllCaps = false
                setOnClickListener { open(t) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                setText(R.string.forget)
                setOnClickListener { prefs.forget(t); refreshRecent() }
            })
            recentBox.addView(row)
        }
    }

    /** ショートカットのボタンの選び方と並び順 */
    private fun refreshShortcuts() {
        shortcutBox.removeAllViews()
        val order = prefs.shortcutOrder
        val shown = prefs.shortcuts.toSet()
        order.forEachIndexed { i, id ->
            val item = Shortcuts.byId(id) ?: return@forEachIndexed
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CheckBox(this).apply {
                setText(item.label)
                isChecked = id in shown
                setOnCheckedChangeListener { _, b ->
                    val now = prefs.shortcuts.toMutableSet()
                    if (b) now.add(id) else now.remove(id)
                    prefs.shortcuts = prefs.shortcutOrder.filter { it in now }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (i > 0) row.addView(Button(this).apply {
                text = "↑"
                setOnClickListener {
                    val o = prefs.shortcutOrder.toMutableList()
                    val k = o.indexOf(id)
                    if (k > 0) {
                        o[k] = o[k - 1]
                        o[k - 1] = id
                        prefs.shortcutOrder = o
                        val now = prefs.shortcuts.toSet()
                        prefs.shortcuts = o.filter { it in now }
                        refreshShortcuts()
                    }
                }
            })
            shortcutBox.addView(row)
        }
    }

    /** はじめの案内：USB デバッグのオンのしかた・Wi-Fi・USB テザリング */
    private fun showGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(R.string.guide_dev) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            }
            .setNeutralButton(R.string.guide_about) { _, _ -> startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .setNegativeButton(R.string.guide_close, null)
            .show()
    }

    private fun openTetherSettings() {
        val i = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** 不具合の報告用に、このアプリのログを共有する（メール・メッセージなどに貼れる） */
    private fun shareLog() {
        Thread {
            val text = try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
                p.inputStream.bufferedReader().readText()
            } catch (e: Exception) { e.toString() }
            val head = "TabDisplay build ${AppUpdate.currentBuild(this)} / Android ${android.os.Build.VERSION.RELEASE} / " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n"
            val body = head + text.takeLast(200_000)
            runOnUiThread {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TabDisplay log")
                    putExtra(Intent.EXTRA_TEXT, body)
                }, getString(R.string.log_share)))
            }
        }.start()
    }

    private fun scan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val text = r.contents ?: return
        val t = Target.fromUri(try { Uri.parse(text) } catch (_: Exception) { null })
        if (t == null) {
            Toast.makeText(this, R.string.bad_qr, Toast.LENGTH_LONG).show()
            return
        }
        prefs.remember(t)
        open(t)
    }

    private fun title(res: Int) = TextView(this).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
