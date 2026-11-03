package io.github.yukichi006.fliphome

import java.util.Calendar

/**
 * 日本の祝日（振替休日・国民の休日を含む）。インターネットは使わず、決まり（祝日法）から計算する。
 * 春分・秋分の日は、国立天文台の発表とほぼ同じになる近似式で求める（1980〜2099 年）。
 */
object Holidays {
    private val cache = HashMap<Int, Set<Int>>()

    /** [year] 年 [month] 月（1〜12）[day] 日が祝日か。 */
    fun isHoliday(year: Int, month: Int, day: Int): Boolean = dayOfYear(year, month, day) in holidays(year)

    private fun dayOfYear(year: Int, month: Int, day: Int): Int =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.get(Calendar.DAY_OF_YEAR)

    /** その月の [n] 番目の月曜日。 */
    private fun nthMonday(year: Int, month: Int, n: Int): Int {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        val first = (Calendar.MONDAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7 + 1
        return first + (n - 1) * 7
    }

    private fun equinox(year: Int, base: Double): Int =
        (base + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4.0)).toInt()

    private fun holidays(year: Int): Set<Int> = cache.getOrPut(year) {
        val days = sortedSetOf<Int>()
        fun add(m: Int, d: Int) { days += dayOfYear(year, m, d) }
        add(1, 1)                               // 元日
        add(1, nthMonday(year, 1, 2))           // 成人の日
        add(2, 11)                              // 建国記念の日
        if (year >= 2020) add(2, 23)            // 天皇誕生日
        add(3, equinox(year, 20.8431))          // 春分の日
        add(4, 29)                              // 昭和の日
        add(5, 3); add(5, 4); add(5, 5)         // 憲法記念日・みどりの日・こどもの日
        add(7, nthMonday(year, 7, 3))           // 海の日
        add(8, 11)                              // 山の日
        add(9, nthMonday(year, 9, 3))           // 敬老の日
        add(9, equinox(year, 23.2488))          // 秋分の日
        add(10, nthMonday(year, 10, 2))         // スポーツの日
        add(11, 3)                              // 文化の日
        add(11, 23)                             // 勤労感謝の日

        val base = days.toSet()
        val c = Calendar.getInstance()
        fun isSunday(doy: Int): Boolean {
            c.clear(); c.set(Calendar.YEAR, year); c.set(Calendar.DAY_OF_YEAR, doy)
            return c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
        }
        // 国民の休日：祝日にはさまれた平日
        for (d in base) if (d + 2 in base && d + 1 !in base && !isSunday(d + 1)) days += d + 1
        // 振替休日：祝日が日曜なら、その後のいちばん近い祝日でない日
        for (d in base.sorted()) if (isSunday(d)) {
            var n = d + 1
            while (n in days) n++
            days += n
        }
        days
    }
}
