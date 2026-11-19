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

    /** 0.36.2 の診断で、専用アプリを開いたときに届いた「機器の情報」の答え（アプリの表示は版 2.18）。 */
    private val info = "02 00 1e 00 a3 00 12 02 00 3c 20 01 00 03 e3 01 d4 0a 21 00 09 00 01 00 74 40 20 00 41 7f"

    @Test
    fun `機器の情報の答えから、残量と版を読む`() {
        val d = RingPacket.deviceInfo(hex(info))!!
        assertEquals(60, d.level)
        assertEquals("2.18", d.version)
        assertEquals("機器の情報：残量 60%・版 2.18", RingPacket.explain("be940001-7333-be46-b7ae-689e71722bd5", hex(info)))
    }

    @Test
    fun `ほかの包みや、壊れた包みからは残量を読まない`() {
        assertNull(RingPacket.deviceInfo(hex("02 26 07 00 fc 5b ee")))
        assertNull(RingPacket.deviceInfo(hex("06 00 0c 00 d5 03 5c 02 1e 00 43 fe")))
        // CRC を 1 つ変えたもの
        assertNull(RingPacket.deviceInfo(hex(info.dropLast(2) + "7e")))
    }

    @Test
    fun `少し前に読んだ残量には、いつ読んだかを添える`() {
        val now = 10L * 24 * 60 * 60_000
        assertNull(WidgetBattery.ageText(now - 5 * 60_000, now))
        assertEquals("40分前", WidgetBattery.ageText(now - 40 * 60_000, now))
        assertEquals("3時間前", WidgetBattery.ageText(now - 3 * 60 * 60_000 - 59_000, now))
        assertEquals("2日前", WidgetBattery.ageText(now - 2 * 24 * 60 * 60_000L, now))
        assertNull(WidgetBattery.ageText(null, now))
    }

    private fun ByteArray.hexText() = joinToString(" ") { "%02x".format(it) }

    @Test
    fun `FlipHome から送る問い合わせは、機器の情報を聞く 2 通りだけ`() {
        assertEquals(listOf("02 00 08 00 47 43 6f ec", "02 00 06 00 0e c3"), RingPacket.DEVICE_INFO_QUERIES.map { it.hexText() })
        for (q in RingPacket.DEVICE_INFO_QUERIES) {
            val p = RingPacket.parse(q)!!
            assertTrue(p.crcOk)
            // 種類 02（読み取りの質問）・番号 00（機器の情報）だけ。設定を変える種類の包みは送らない
            assertEquals(0x02, p.type)
            assertEquals(0x00, p.cmd)
        }
    }

    @Test
    fun `問い合わせの形に対応していないという答えを見分ける`() {
        assertTrue(RingPacket.isDeviceInfoRefusal(hex("02 00 07 00 fc 8c fe")))
        assertFalse(RingPacket.isDeviceInfoRefusal(hex(info)))
        assertFalse(RingPacket.isDeviceInfoRefusal(hex("02 26 07 00 fc 5b ee")))
    }
}
