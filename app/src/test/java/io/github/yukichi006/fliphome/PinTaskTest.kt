package io.github.yukichi006.fliphome

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** 縫い付けメモの付箋（ジャンル・納期・並べ方）のテスト。 */
@RunWith(RobolectricTestRunner::class)
class PinTaskTest {
    private val today = LocalDate.of(2026, 11, 19)
    private fun day(d: LocalDate) = d.toEpochDay()

    @Test
    fun `ジャンルと納期も、保存して読み戻すと同じ`() {
        val list = listOf(
            PinTask("見積もりを送る", genre = 1, due = day(today.plusDays(2))),
            PinTask("牛乳", genre = 3),
            PinTask("納期なし・ジャンルなし"),
        )
        assertEquals(list, PinTask.parse(PinTask.toJson(list)))
    }

    @Test
    fun `前の作りで保存した付箋は、ジャンルなし・納期なしで読める`() {
        val old = """[{"t":"牛乳を買う","d":false},{"t":"済み","d":true}]"""
        assertEquals(listOf(PinTask("牛乳を買う"), PinTask("済み", done = true)), PinTask.parse(old))
    }

    @Test
    fun `知らないジャンルの番号は、なしにする`() {
        assertEquals(0, PinTask.parse("""[{"t":"a","g":99}]""").single().genre)
    }

    @Test
    fun `納期の近い順に並び、納期のないものは後ろ。同じ納期どうしは今の順のまま`() {
        val a = PinTask("なし1")
        val b = PinTask("来週", due = day(today.plusDays(7)))
        val c = PinTask("明日1", due = day(today.plusDays(1)))
        val d = PinTask("なし2")
        val e = PinTask("過ぎた", due = day(today.minusDays(3)))
        val f = PinTask("明日2", due = day(today.plusDays(1)))
        assertEquals(listOf(e, c, f, b, a, d), PinTask.sorted(listOf(a, b, c, d, e, f)))
    }

    @Test
    fun `納期の文字`() {
        assertEquals("2日過ぎ" to true, PinTask.dueLabel(today.minusDays(2), today))
        assertEquals("今日まで" to true, PinTask.dueLabel(today, today))
        assertEquals("明日まで" to false, PinTask.dueLabel(today.plusDays(1), today))
        assertEquals("あと6日" to false, PinTask.dueLabel(today.plusDays(6), today))
        assertEquals("11/26" to false, PinTask.dueLabel(today.plusDays(7), today))
        assertEquals("1/5" to false, PinTask.dueLabel(LocalDate.of(2027, 1, 5), today))
    }

    @Test
    fun `納期の段の日：今日・今週は日曜・今月は末日・なし`() {
        // 2026-11-19 は木曜
        assertEquals(today, PinTask.dueAt(0, today))
        assertEquals(LocalDate.of(2026, 11, 22), PinTask.dueAt(1, today))
        assertEquals(LocalDate.of(2026, 11, 30), PinTask.dueAt(2, today))
        assertEquals(null, PinTask.dueAt(PinTask.STOP_NONE, today))
        // 日曜は、今日と今週が同じ日
        val sunday = LocalDate.of(2026, 11, 22)
        assertEquals(sunday, PinTask.dueAt(1, sunday))
    }

    @Test
    fun `書き直すときの段：同じ日の段、なければ収まるいちばん近い段`() {
        assertEquals(PinTask.STOP_NONE, PinTask.stopOf(null, today))
        assertEquals(0, PinTask.stopOf(today, today))
        assertEquals(1, PinTask.stopOf(LocalDate.of(2026, 11, 22), today))
        assertEquals(2, PinTask.stopOf(LocalDate.of(2026, 11, 30), today))
        assertEquals(0, PinTask.stopOf(today.minusDays(3), today)) // 過ぎたもの
        assertEquals(1, PinTask.stopOf(today.plusDays(1), today)) // 前の作りの「明日」
        assertEquals(2, PinTask.stopOf(LocalDate.of(2026, 11, 25), today))
        assertEquals(2, PinTask.stopOf(LocalDate.of(2027, 1, 5), today)) // 前の作りで選んだ遠い日
        // 月末が週の終わりより先に来る週（12/29 火 → 今週は 1/3、今月は 12/31）
        val dec29 = LocalDate.of(2026, 12, 29)
        assertEquals(2, PinTask.stopOf(LocalDate.of(2026, 12, 31), dec29))
        assertEquals(1, PinTask.stopOf(LocalDate.of(2027, 1, 2), dec29))
    }
}
