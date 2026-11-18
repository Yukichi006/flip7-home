package io.github.yukichi006.fliphome

import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView

/**
 * ほかのアプリのウィジェット（Galaxy の「バッテリー」など）の中身から、電池の残量を読み取る。
 *
 * Galaxy Fit3 などは、残量を Samsung のアプリにしか送らないため、FlipHome から直接は読めない。
 * Galaxy の「バッテリー」ウィジェットには出るので、FlipHome の中でそのウィジェットを見えないまま動かし、
 * 表示されている「92%」などの文字を読む。新しい権限は要らない（FlipHome が自分で表示しているウィジェットの文字を読むだけ）。
 *
 * ウィジェットの作りは Samsung の更新で変わることがあるので、決まった部品の番号には頼らず、
 * 「%」の付いた文字を探し、その行にある名前（読み上げ用の説明を含む）を拾う。
 */
object WidgetBattery {
    class Entry(
        /** 機器の名前。ウィジェットに名前が書かれていなければ null。 */
        val name: String?,
        val level: Int,
        val charging: Boolean,
    )

    private val PERCENT = Regex("""(\d{1,3})\s*[%％]""")

    /** 名前から取り除く言葉（残量の説明に付く言葉）。 */
    private val NOISE = listOf("バッテリー残量", "バッテリー", "電池残量", "電池", "残量", "充電中", "充電済み", "充電",
        "Battery level", "Battery", "battery", "Charging", "charging", "percent", "パーセント")

    /** [root] の中から、行ごとの残量を上から順に読み取る。 */
    fun read(root: View): List<Entry> {
        val levelViews = mutableListOf<Pair<View, Int>>()
        walk(root) { v ->
            levelOf(v)?.let { levelViews += v to it }
        }
        // 「%」の文字があればそれを使い、なければ残量の棒を使う（同じ行に両方あっても 1 行に数える）
        val primary = levelViews.filter { it.first is TextView }.ifEmpty { levelViews }
        val marks = primary.map { it.first }
        val rows = primary.map { (v, level) -> rowOf(v, root, marks) to level }.distinctBy { it.first }
        // 画面の上から順（部品の並び順）にそろえる
        val order = mutableListOf<View>()
        walk(root) { order += it }
        return rows.sortedBy { order.indexOf(it.first) }.map { (row, level) ->
            val words = wordsIn(row)
            Entry(nameFrom(words), level, words.any { "充電中" in it || "harging" in it })
        }
    }

    /** 診断用に、ウィジェットの中身（部品の種類・文字・読み上げ用の説明）を字下げして並べる。 */
    fun dump(root: View): String = buildString {
        fun go(v: View, depth: Int) {
            if (length > 6000) return
            append("  ".repeat(depth)).append(v.javaClass.simpleName)
            if (v.visibility != View.VISIBLE) append(" [隠れ]")
            (v as? TextView)?.text?.takeIf { it.isNotBlank() }?.let { append(" 「").append(it).append("」") }
            v.contentDescription?.takeIf { it.isNotBlank() }?.let { append(" 説明「").append(it).append("」") }
            (v as? ProgressBar)?.let { if (!it.isIndeterminate) append(" 棒 ${it.progress}/${it.max}") }
            append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i), depth + 1)
        }
        go(root, 0)
    }

    /** 表示されている部品だけを、上から順にたどる。 */
    private fun walk(v: View, visit: (View) -> Unit) {
        if (v.visibility != View.VISIBLE) return
        visit(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
    }

    /** 部品が表している残量（%）。「92%」の文字か、残量の棒。どちらでもなければ null。 */
    private fun levelOf(v: View): Int? {
        if (v is TextView) {
            val m = PERCENT.find(v.text ?: return null) ?: return null
            return m.groupValues[1].toInt().takeIf { it in 0..100 }
        }
        if (v is ProgressBar && !v.isIndeterminate && v.max > 0) {
            return (v.progress * 100 / v.max).takeIf { v.progress > 0 && it in 0..100 }
        }
        return null
    }

    /**
     * 残量の部品 [v] を含む「行」。親をたどり、ほかの行の残量（[marks]）を含まないいちばん外側の入れ物を行とみなす。
     */
    private fun rowOf(v: View, root: View, marks: List<View>): View {
        var row = v
        while (row !== root) {
            val parent = row.parent as? ViewGroup ?: break
            if (marks.any { it !== v && isInside(it, parent) }) break
            row = parent
        }
        return row
    }

    private fun isInside(v: View, group: ViewGroup): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === group) return true
            p = p.parent
        }
        return false
    }

    /** 行の中の文字と読み上げ用の説明を、上から順に集める。 */
    private fun wordsIn(row: View): List<String> {
        val words = mutableListOf<String>()
        walk(row) { v ->
            v.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { words += it }
            (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { words += it }
        }
        return words
    }

    /** 集めた言葉から、機器の名前らしいものを選ぶ（「%」や「バッテリー」などを取り除いて残ったもの）。 */
    fun nameFrom(words: List<String>): String? = words.asSequence()
        .map { w ->
            var t = PERCENT.replace(w, " ")
            for (n in NOISE) t = t.replace(n, " ")
            t.replace(Regex("""[,、，:：()（）\[\]・]"""), " ").replace(Regex("""\s+"""), " ").trim()
        }
        .firstOrNull { t -> t.length >= 2 && t.any { it.isLetter() } }

    /**
     * ウィジェットから読んだ行 [entries] を、出す行（名前・残量・充電中）にする。
     * - 本体の行は除く（名前が本体らしいものか、本体の機種名 [phoneModel]。どれもなければ、いちばん上の行）
     * - すでに出している機器 [shown]（名前・残量）と同じ名前の行は除く
     * - 名前のない行は、すでに出している機器と同じ残量なら、その機器の行とみなして除く。
     *   残りには、[candidates]（つながっているのに残量の読めない機器の名前）を順に当てる
     */
    fun merge(entries: List<Entry>, shown: List<Pair<String, Int>>, candidates: List<String>, phoneModel: String): List<Triple<String, Int, Boolean>> {
        // Galaxy の「バッテリー」は本体をいちばん上に出すので、本体らしい名前の行がなければ、いちばん上を本体とみなす
        val phone = entries.indexOfFirst { e -> e.name?.let { isPhoneName(it, phoneModel) } == true }.coerceAtLeast(0)
        val rest = entries.filterIndexed { i, _ -> i != phone }
        val left = candidates.filter { c -> shown.none { sameDevice(it.first, c) } }.toMutableList()
        val out = mutableListOf<Triple<String, Int, Boolean>>()
        for (e in rest) {
            val name = e.name
            if (name != null) {
                if (shown.any { sameDevice(it.first, name) }) continue
                left.removeAll { sameDevice(it, name) }
                out += Triple(name, e.level, e.charging)
            } else if (shown.none { it.second == e.level }) {
                out += Triple(if (left.isNotEmpty()) left.removeAt(0) else "Bluetooth 機器", e.level, e.charging)
            }
        }
        return out
    }

    private fun isPhoneName(name: String, phoneModel: String): Boolean {
        val n = name.lowercase()
        return listOf("スマートフォン", "スマホ", "携帯", "本体", "電話", "phone", "flip").any { it in n } ||
            (phoneModel.isNotBlank() && n == phoneModel.lowercase())
    }

    /** 2 つの名前が同じ機器を指していそうか（大文字・小文字と空白を無視して、片方がもう片方を含む）。 */
    fun sameDevice(a: String, b: String): Boolean {
        val x = a.lowercase().replace(" ", "")
        val y = b.lowercase().replace(" ", "")
        return x.isNotEmpty() && y.isNotEmpty() && (x in y || y in x)
    }

    /** 読んだ時刻 [at] が 30 分より前なら、「40分前」「3時間前」「2日前」。 */
    fun ageText(at: Long?, now: Long = System.currentTimeMillis()): String? {
        val min = ((now - (at ?: return null)) / 60_000L).takeIf { it >= 30 } ?: return null
        return when {
            min < 60 -> "${min}分前"
            min < 24 * 60 -> "${min / 60}時間前"
            else -> "${min / (24 * 60)}日前"
        }
    }
}
