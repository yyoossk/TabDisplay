package com.yyoossk.tabdisplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * この端末の画面を、ほかのタブレットの TabDisplay に映す（PC なし）。
 * 受ける側は「QR コードを読む」でこの画面の QR を読むだけ。
 */
class SenderActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var qrText: TextView
    private lateinit var startStop: Button
    private lateinit var a11y: Button
    private lateinit var audio: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            setText(R.string.sender_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        col.addView(TextView(this).apply { setText(R.string.sender_help) })
        audio = CheckBox(this).apply {
            setText(R.string.sender_audio)
            isEnabled = Build.VERSION.SDK_INT >= 29
            isChecked = Build.VERSION.SDK_INT >= 29 && getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).getBoolean("sender_audio", true)
            setOnCheckedChangeListener { _, b ->
                getSharedPreferences("tabdisplay", Context.MODE_PRIVATE).edit().putBoolean("sender_audio", b).apply()
            }
        }
        col.addView(audio)
        a11y = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        col.addView(a11y)
        startStop = Button(this).apply { setOnClickListener { if (SenderService.running) stop() else begin() } }
        col.addView(startStop)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)
        qr = ImageView(this)
        col.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
        qrText = TextView(this).apply { setTextColor(Color.GRAY) }
        col.addView(qrText)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        SenderService.onStatus = { refresh() }
        refresh()
    }

    override fun onPause() {
        SenderService.onStatus = null
        super.onPause()
    }

    private fun refresh() {
        val on = SenderService.running
        startStop.setText(if (on) R.string.sender_stop else R.string.sender_start)
        status.text = if (on) SenderService.status else getString(R.string.sender_stopped)
        a11y.setText(if (TouchService.enabled) R.string.sender_a11y_on else R.string.sender_a11y_off)
        if (on) showQr() else { qr.setImageBitmap(null); qrText.text = "" }
    }

    private fun begin() {
        val need = ArrayList<String>()
        if (audio.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 2)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK || data == null) return
        val i = Intent(this, SenderService::class.java)
            .putExtra(SenderService.EXTRA_RESULT, resultCode)
            .putExtra(SenderService.EXTRA_DATA, data)
            .putExtra(SenderService.EXTRA_AUDIO, audio.isChecked)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        window.decorView.postDelayed({ refresh() }, 500)
    }

    private fun stop() {
        startService(Intent(this, SenderService::class.java).setAction(SenderService.ACTION_STOP))
        window.decorView.postDelayed({ refresh() }, 300)
    }

    /** 受ける側が読む QR（PC の QR と同じ形） */
    private fun showQr() {
        val ips = localIpv4()
        if (ips.isEmpty()) {
            qrText.setText(R.string.sender_no_ip)
            return
        }
        val url = "tabdisplay://connect?h=" + ips.joinToString(",") +
            "&p=" + Protocol.PORT +
            "&t=" + SenderTls.token(this) +
            "&f=" + SenderTls.fingerprint() +
            "&n=" + Uri.encode(Build.MODEL)
        val size = 512
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (y in 0 until size) for (x in 0 until size) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        qr.setImageBitmap(bmp)
        qrText.text = Build.MODEL + "  " + ips.joinToString(" / ") { "$it:${Protocol.PORT}" }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
