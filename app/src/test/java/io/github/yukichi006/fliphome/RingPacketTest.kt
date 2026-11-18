package io.github.yukichi006.fliphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * スマートリング（evofit C8E7）から実際に届いた値を読み解くテスト（[RingPacket]）。
 * 値は、持ち主に 0.36.0 の診断で記録してもらったもの。
 */
class RingPacketTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `独自のサービスの包みを読み、CRC を確かめる`() {
        val p = RingPacket.parse(hex("02 26 07 00 fc 5b ee"))!!
        assertEquals(0x02, p.type)
        assertEquals(0x26, p.cmd)
        assertEquals(listOf(0xfc), p.payload.map { it.toInt() and 0xFF })
        assertTrue(p.crcOk)
    }

    @Test
    fun `CRC が合わない包みは、合わないと分かる`() {
        assertFalse(RingPacket.parse(hex("02 26 07 00 fd 5b ee"))!!.crcOk)
    }

    @Test
    fun `長さが合わなければ、包みとして読まない`() {
        assertNull(RingPacket.parse(hex("02 26 09 00 fc 5b ee")))
        assertNull(RingPacket.parse(hex("04 52")))
    }

    @Test
    fun `心拍と歩数を読める`() {
        assertEquals("心拍 82", RingPacket.explain("2a37", hex("04 52")))
        assertEquals("歩数 9020・距離 5980m・消費 279kcal", RingPacket.explain("fea1", hex("07 3c 23 00 5c 17 00 17 01 00")))
    }

    @Test
    fun `独自のサービスの包みを、種類・番号・中身で説明する`() {
        assertEquals("包み 種類02 番号26 中身[fc]",
            RingPacket.explain("be940001-7333-be46-b7ae-689e71722bd5", hex("02 26 07 00 fc 5b ee")))
    }
}
