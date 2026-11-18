package io.github.yukichi006.fliphome

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Galaxy の「バッテリー」ウィジェットのような中身から、残量を読み取るテスト（[WidgetBattery.read]）。
 */
@RunWith(RobolectricTestRunner::class)
class WidgetBatteryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 1 行：機器の絵（読み上げ用の説明つき）・残量の棒・「92%」の文字。 */
    private fun row(desc: String?, level: Int, visible: Boolean = true) = FrameLayout(context).apply {
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = level })
        addView(LinearLayout(context).apply {
            addView(ImageView(context).apply { contentDescription = desc })
            addView(TextView(context).apply { text = "$level%" })
        })
        if (!visible) visibility = View.GONE
    }

    private fun widget(vararg rows: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEach { addView(it) }
    }

    @Test
    fun `名前の書かれていないウィジェットでも、行ごとの残量を上から順に読める`() {
        val w = widget(row(null, 94), row(null, 92), row(null, 80), FrameLayout(context))
        val got = WidgetBattery.read(w)
        assertEquals(listOf(94, 92, 80), got.map { it.level })
        assertTrue(got.all { it.name == null })
    }

    @Test
    fun `読み上げ用の説明から、機器の名前を拾う`() {
        val w = widget(row("Galaxy Z Flip7", 94), row("Galaxy Fit3 (A1B2)、バッテリー残量", 92), row("Galaxy Buds3 Pro", 80))
        val got = WidgetBattery.read(w)
        assertEquals(listOf("Galaxy Z Flip7", "Galaxy Fit3 A1B2", "Galaxy Buds3 Pro"), got.map { it.name })
    }

    @Test
    fun `隠れている行は読まない`() {
        val w = widget(row(null, 94), row(null, 55, visible = false), row(null, 92))
        assertEquals(listOf(94, 92), WidgetBattery.read(w).map { it.level })
    }

    @Test
    fun `「%」の文字がなければ、残量の棒から読む`() {
        val w = widget(
            FrameLayout(context).apply { addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 70 }) },
            FrameLayout(context).apply { addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 200; progress = 50 }) },
        )
        assertEquals(listOf(70, 25), WidgetBattery.read(w).map { it.level })
    }

    @Test
    fun `充電中と書かれていれば、充電中として読む`() {
        val w = widget(row(null, 94), row("Galaxy Fit3 充電中", 40))
        val got = WidgetBattery.read(w)
        assertFalse(got[0].charging)
        assertTrue(got[1].charging)
        assertEquals("Galaxy Fit3", got[1].name)
    }
}
