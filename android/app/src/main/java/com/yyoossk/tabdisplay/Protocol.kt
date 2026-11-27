package com.yyoossk.tabdisplay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * PC とのやりとりの形式（PC 側の Protocol.cs と同じ）。
 *
 * 1 つの TCP 接続の上で、次の形のメッセージを両方向に送る。
 *   [種類 1 バイト][長さ 4 バイト（ビッグエンディアン）][中身]
 *
 * 映像（VIDEO）の中身:
 *   [PC の送信時刻 μs 8 バイト][フラグ 1 バイト][H.264（Annex-B）]
 */
object Protocol {
    const val PORT = 38500
    const val VERSION = 1

    // タブレット → PC
    const val T_HELLO = 1      // JSON: token, id, name, w, h, dpi, sdk, ver
    const val T_INPUT = 2      // JSON: k(click/rclick/scroll/move/zoom/text/key/pen), x, y, ...
    const val T_SIZE = 3       // JSON: w, h（回したとき）
    const val T_PING = 4       // JSON: tab（タブレットの時刻 μs）
    const val T_IDR = 5        // 中身なし：キーフレームがほしい
    const val T_STATS = 6      // JSON: fps, lat（PC の画面に出すため）
    const val T_BYE = 7
    const val T_OPTS = 8       // JSON: inputOnly（映像なしの入力専用＝ペンタブレット）
    const val T_CLIP = 9       // JSON: s（タブレットでコピーした文字）
    const val T_FILE_BEGIN = 30 // JSON: id, name, size
    const val T_FILE_DATA = 31  // [id 4 バイト][中身]
    const val T_FILE_END = 32   // JSON: id

    // PC → タブレット
    const val P_CONFIG = 10    // JSON: w, h, fps, sps, pps（base64）, name
    const val P_VIDEO = 11
    const val P_PONG = 12      // JSON: tab, pc
    const val P_STATUS = 13    // JSON: s（"pc_only" / "no_driver" / "ok"）
    const val P_ERROR = 14     // JSON: e（"token" / "version"）
    const val P_AUDIO_CFG = 15 // JSON: rate, ch
    const val P_AUDIO = 16     // PCM 16bit（リトルエンディアン、交互）
    const val P_AREA = 17      // JSON: w, h（入力専用のとき、当てる PC の画面の大きさ）
    const val P_CLIP = 18      // JSON: s（PC でコピーした文字）
    const val P_NOTIFY = 19    // JSON: app, title, body（PC の通知）
    const val P_FILE_BEGIN = 40
    const val P_FILE_DATA = 41
    const val P_FILE_END = 42

    /** PC が Wi-Fi に出す「ここにいます」の知らせ（UDP ブロードキャスト） */
    const val DISCOVERY_PORT = 38501

    const val F_KEY = 1
    const val F_CONFIG = 2

    const val MAX_LEN = 32 * 1024 * 1024

    class Message(val type: Int, val data: ByteArray)

    @Throws(IOException::class)
    fun read(input: DataInputStream): Message {
        val type = input.readUnsignedByte()
        val len = input.readInt()
        if (len < 0 || len > MAX_LEN) throw IOException("bad length $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return Message(type, buf)
    }

    /** 複数のスレッドから呼ばれるので out で同期する */
    @Throws(IOException::class)
    fun write(out: DataOutputStream, type: Int, data: ByteArray) {
        synchronized(out) {
            out.writeByte(type)
            out.writeInt(data.size)
            out.write(data)
            out.flush()
        }
    }
}
