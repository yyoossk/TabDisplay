package com.yyoossk.tabdisplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PC が Wi-Fi に 2 秒ごとに出す「ここにいます」（UDP 38501 番のブロードキャスト）を聞く。
 * 前につないだ PC の今の IP がわかるので、IP が変わっても自動でつながる。
 */
object Discovery {
    data class Found(val id: String, val host: String, val port: Int, val name: String, val time: Long)

    private val found = ConcurrentHashMap<String, Found>()
    private val listeners = CopyOnWriteArrayList<(Found) -> Unit>()
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var users = 0

    @Synchronized
    fun start(ctx: Context) {
        users++
        if (thread != null) return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("tabdisplay").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) {
            Log.w("Discovery", "lock: $e")
        }
        thread = Thread({ loop() }, "discovery").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        users = maxOf(0, users - 1)
        if (users > 0) return
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        thread = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    /** 最近（15 秒以内）見つかった PC */
    fun get(pcId: String): Found? =
        found[pcId]?.takeIf { SystemClock.elapsedRealtime() - it.time < 15_000 }

    fun addListener(l: (Found) -> Unit) = listeners.add(l)
    fun removeListener(l: (Found) -> Unit) = listeners.remove(l)

    private fun loop() {
        val me = Thread.currentThread()
        while (thread === me) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                socket = s
                val buf = ByteArray(2048)
                while (thread === me) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val j = try { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) } catch (_: Exception) { continue }
                    if (j.optString("app") != "tabdisplay") continue
                    val f = Found(j.optString("i"), p.address.hostAddress ?: continue,
                        j.optInt("p", Protocol.PORT), j.optString("n", "PC"), SystemClock.elapsedRealtime())
                    if (f.id.isEmpty()) continue
                    found[f.id] = f
                    listeners.forEach { it(f) }
                }
            } catch (e: Exception) {
                if (thread !== me) return
                Log.i("Discovery", "socket: $e")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }
}
