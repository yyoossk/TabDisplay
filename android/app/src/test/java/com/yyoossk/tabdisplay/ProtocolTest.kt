package com.yyoossk.tabdisplay

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** つなぐ部分の自動テスト（PC 側の --selftest と同じ値を確かめる） */
class ProtocolTest {
    @Test
    fun writeThenRead() {
        val bo = ByteArrayOutputStream()
        val out = DataOutputStream(bo)
        Protocol.write(out, Protocol.T_PING, "{\"tab\":1}".toByteArray())
        Protocol.write(out, Protocol.T_BYE, ByteArray(0))
        val bytes = bo.toByteArray()
        // [種類 1][長さ 4（ビッグエンディアン）][中身]
        assertEquals(Protocol.T_PING, bytes[0].toInt())
        assertEquals(9, bytes[4].toInt())
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val a = Protocol.read(input)
        assertEquals(Protocol.T_PING, a.type)
        assertEquals("{\"tab\":1}", String(a.data))
        val b = Protocol.read(input)
        assertEquals(Protocol.T_BYE, b.type)
        assertEquals(0, b.data.size)
    }

    @Test
    fun typeNumbersMatchPc() {
        // PC 側 Protocol.cs と同じ番号であること
        assertEquals(1, Protocol.T_HELLO)
        assertEquals(4, Protocol.T_PING)
        assertEquals(39, Protocol.T_CRASH)
        assertEquals(11, Protocol.P_VIDEO)
        assertEquals(12, Protocol.P_PONG)
        assertEquals(14, Protocol.P_ERROR)
        assertEquals(46, Protocol.P_SLEEP)
    }

    @Test
    fun pcIdMatchesPc() {
        // PC の Beacon.PcId("abc") と同じ（SHA-256 の先頭 8 バイト）
        assertEquals("ba7816bf8f01cfea", Target.pcIdOf("abc"))
    }

    @Test
    fun splitNalsWorks() {
        val d = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = splitNals(d)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 3), nals[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5), nals[2])
    }

    @Test
    fun keyMap() {
        assertEquals(0x41, KeyMap.toVk(KeyEvent.KEYCODE_A))
        assertEquals(0x0D, KeyMap.toVk(KeyEvent.KEYCODE_ENTER))
        assertEquals(0x70, KeyMap.toVk(KeyEvent.KEYCODE_F1))
    }
}
