package io.github.yukichi006.fliphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Galaxy の「バッテリー」から読み取った行を、電池の部品に出す行にまとめるテスト（[WidgetBattery.merge]）。
 * 画面の部品を使わないので、Robolectric なしで動く。
 */
class WidgetMergeTest {
    private fun entry(name: String?, level: Int) = WidgetBattery.Entry(name, level, false)

    @Test
    fun `名前がなければ、いちばん上の行を本体とみなし、Buds と同じ残量の行は重ねない`() {
        // 今の Galaxy の「バッテリー」：本体 94%・Fit3 92%・Buds 80%（Buds は Android から読めている）
        val out = WidgetBattery.merge(
            listOf(entry(null, 94), entry(null, 92), entry(null, 80)),
            shown = listOf("Galaxy Buds3 Pro" to 80),
            candidates = listOf("Galaxy Fit3 (A1B2)"),
            phoneModel = "SM-F766Q",
        )
        assertEquals(listOf("Galaxy Fit3 (A1B2)" to 92), out.map { it.first to it.second })
    }

    @Test
    fun `名前があれば、本体とすでに出している機器を除いて足す`() {
        val out = WidgetBattery.merge(
            listOf(entry("Galaxy Z Flip7", 94), entry("Galaxy Fit3", 92), entry("Galaxy Buds3 Pro", 80)),
            shown = listOf("Galaxy Buds3 Pro" to 80),
            candidates = emptyList(),
            phoneModel = "SM-F766Q",
        )
        assertEquals(listOf("Galaxy Fit3" to 92), out.map { it.first to it.second })
    }

    @Test
    fun `名前も候補もなければ「Bluetooth 機器」として出す`() {
        val out = WidgetBattery.merge(listOf(entry(null, 94), entry(null, 92)), shown = emptyList(), candidates = emptyList(), phoneModel = "SM-F766Q")
        assertEquals(listOf("Bluetooth 機器" to 92), out.map { it.first to it.second })
    }

    @Test
    fun `同じ機器かどうかは、大文字小文字と空白を無視して比べる`() {
        assertTrue(WidgetBattery.sameDevice("Galaxy Fit3 (A1B2)", "galaxy fit3"))
        assertFalse(WidgetBattery.sameDevice("Galaxy Fit3", "Galaxy Buds3 Pro"))
    }

    @Test
    fun `名前の一部だけが書かれていても、本体の行は出さない`() {
        // 本体の行に名前がなく、ほかの行にだけ名前があるとき
        val out = WidgetBattery.merge(listOf(entry(null, 94), entry("Galaxy Fit3", 92)), shown = emptyList(),
            candidates = listOf("evofit C8E7"), phoneModel = "SM-F766Q")
        assertEquals(listOf("Galaxy Fit3" to 92), out.map { it.first to it.second })
    }

    @Test
    fun `名前の書かれた機器は、候補から外す（同じ機器を二度出さない）`() {
        val out = WidgetBattery.merge(listOf(entry("Galaxy Z Flip7", 94), entry("Galaxy Fit3", 92), entry(null, 60)),
            shown = emptyList(), candidates = listOf("Galaxy Fit3 (A1B2)", "evofit C8E7"), phoneModel = "SM-F766Q")
        assertEquals(listOf("Galaxy Fit3" to 92, "evofit C8E7" to 60), out.map { it.first to it.second })
    }
}
