package com.yyoossk.tabdisplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリの自動更新：GitHub の最新のリリース（build-番号）と比べて、新しければ APK を落として入れる。
 * （USB でつなぐ人は、PC が新しくなったときに PC から自動で入れ替わるので、これは Wi-Fi で使う人向け）
 */
object AppUpdate {
    private const val API = "https://api.github.com/repos/yyoossk/tabdisplay/releases/latest"

    fun currentBuild(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (_: Exception) { 0 }

    /** 1 日 1 回まで。quiet = 新しい版がないときは何も言わない */
    fun check(a: Activity, quiet: Boolean) {
        val sp = a.getSharedPreferences("tabdisplay", Context.MODE_PRIVATE)
        val last = sp.getLong("upd_last", 0)
        if (quiet && System.currentTimeMillis() - last < 20L * 3600_000) return
        sp.edit().putLong("upd_last", System.currentTimeMillis()).apply()
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "TabDisplay")
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.optString("tag_name")
                val n = tag.removePrefix("build-").toIntOrNull() ?: return@Thread
                var apkUrl: String? = null
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.optString("name") == "TabDisplay.apk") apkUrl = o.optString("browser_download_url")
                }
                val cur = currentBuild(a)
                a.runOnUiThread {
                    if (n <= cur || apkUrl == null) {
                        if (!quiet) Toast.makeText(a, a.getString(R.string.upd_none, cur), Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(a)
                        .setMessage(a.getString(R.string.upd_new, tag))
                        .setPositiveButton(android.R.string.ok) { _, _ -> download(a, apkUrl) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                if (!quiet) a.runOnUiThread { Toast.makeText(a, R.string.upd_fail, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        // Android 8 以降は「この提供元のアプリを許可」が要る
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            Toast.makeText(a, R.string.upd_allow, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(a, R.string.upd_downloading, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = if (Build.VERSION.SDK_INT >= 24) File(a.cacheDir, "update") else a.getExternalFilesDir(null)!!
                dir.mkdirs()
                val f = File(dir, "TabDisplay.apk")
                var u = URL(url)
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = u.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "TabDisplay")
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) {
                        u = URL(u, conn.getHeaderField("Location"))
                        continue
                    }
                    break
                }
                conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                a.runOnUiThread {
                    val uri = if (Build.VERSION.SDK_INT >= 24) Uri.parse("content://${a.packageName}.apk/TabDisplay.apk")
                              else Uri.fromFile(f)
                    @Suppress("DEPRECATION")
                    val i = Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    a.startActivity(i)
                }
            } catch (e: Exception) {
                a.runOnUiThread { Toast.makeText(a, R.string.upd_fail, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}

/** 落とした APK をインストーラーに渡すための、読むだけの入り口（Android 7 以降） */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = File(File(context!!.cacheDir, "update"), "TabDisplay.apk")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
