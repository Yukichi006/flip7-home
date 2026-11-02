package io.github.yukichi006.fliphome

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** ホーム画面のマス目に置く部品の種類。 */
enum class ModuleType(val label: String, val w: Int, val h: Int) {
    CLOCK("時計", 4, 2),
    AGENDA("今日の予定", 2, 2),
    BATTERY("電池", 2, 2),
    APP("アプリ", 1, 1),
}

/** マス目に置いた部品 1 つ。[col], [row] は左上のマス、[w], [h] は占めるマスの数。[arg] はアプリのキーなど。 */
data class Module(val type: ModuleType, val col: Int, val row: Int, val w: Int, val h: Int, val arg: String = "") {
    fun covers(c: Int, r: Int) = c in col until col + w && r in row until row + h

    fun serialize() = listOf(type.name, col, row, w, h, arg).joinToString(",")

    companion object {
        fun parse(line: String): Module? = runCatching {
            val p = line.split(",", limit = 6)
            Module(ModuleType.valueOf(p[0]), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p.getOrElse(5) { "" })
        }.getOrNull()
    }
}

/** ホーム画面の配置と下段（ポケット）のアプリを保存する。 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var modules: List<Module>
        get() = prefs.getString(KEY_LAYOUT, null)?.lines()?.mapNotNull { Module.parse(it) } ?: DEFAULT
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.joinToString("\n") { it.serialize() }).apply()

    /** 下段のアプリのキー（左から）。まだ決めていなければ null。 */
    var dock: List<String>?
        get() = prefs.getString(KEY_DOCK, null)?.split("\n")?.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_DOCK, value.orEmpty().joinToString("\n")).apply()

    /** 1 枚目に収まるマスの行数（最後に表示したときのもの）。空きマスを探すのに使う。 */
    var rows: Int
        get() = prefs.getInt(KEY_ROWS, 7)
        set(value) = prefs.edit().putInt(KEY_ROWS, value).apply()

    /** [type] の部品（アプリなら [arg]）を、空いているいちばん上・左のマスに置く。置けなければ false。 */
    fun add(type: ModuleType, arg: String = ""): Boolean {
        val list = modules
        val spot = findFree(list, type.w, type.h, rows) ?: return false
        modules = list + Module(type, spot.first, spot.second, type.w, type.h, arg)
        return true
    }

    fun remove(module: Module) {
        modules = modules - module
    }

    fun has(type: ModuleType, arg: String = "") = modules.any { it.type == type && it.arg == arg }

    companion object {
        const val COLUMNS = 4
        private const val KEY_LAYOUT = "layout"
        private const val KEY_DOCK = "dock"
        private const val KEY_ROWS = "rows"

        val DEFAULT = listOf(
            Module(ModuleType.CLOCK, 0, 0, 4, 2),
            Module(ModuleType.AGENDA, 0, 2, 2, 2),
            Module(ModuleType.BATTERY, 2, 2, 2, 2),
        )

        fun findFree(list: List<Module>, w: Int, h: Int, rows: Int): Pair<Int, Int>? {
            for (r in 0..rows - h) for (c in 0..COLUMNS - w) {
                val free = (r until r + h).all { rr -> (c until c + w).all { cc -> list.none { it.covers(cc, rr) } } }
                if (free) return c to r
            }
            return null
        }
    }
}

/**
 * 部品をマス目に並べる入れ物。横は [LayoutStore.COLUMNS] 列で、マスはほぼ正方形。
 * 縦に収まる行数は画面の高さで決まり、はみ出した部品は表示しない。
 */
class ModuleGrid(context: Context) : ViewGroup(context) {
    class Params(val module: Module) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var onRowsChanged: ((Int) -> Unit)? = null

    var rows = 0
        private set

    private fun cellW() = width.toFloat() / LayoutStore.COLUMNS
    private fun cellH(w: Int = width) = w.toFloat() / LayoutStore.COLUMNS * CELL_RATIO

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val ch = cellH(w)
        val newRows = max(1, (h / ch).toInt())
        if (newRows != rows) {
            rows = newRows
            post { onRowsChanged?.invoke(newRows) }
        }
        val cw = w.toFloat() / LayoutStore.COLUMNS
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val m = (child.layoutParams as Params).module
            child.measure(
                MeasureSpec.makeMeasureSpec((m.w * cw).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((m.h * ch).toInt(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW()
        val ch = cellH()
        // 余った高さは、マス目の上下に半分ずつ配る
        val top = ((height - rows * ch) / 2).coerceAtLeast(0f)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val m = (child.layoutParams as Params).module
            if (m.row + m.h > rows || m.col + m.w > LayoutStore.COLUMNS) {
                child.visibility = View.INVISIBLE
                child.layout(0, 0, 0, 0)
                continue
            }
            child.visibility = View.VISIBLE
            val x = (m.col * cw).toInt()
            val y = (top + m.row * ch).toInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    fun addModule(view: View, module: Module) = addView(view, Params(module))

    companion object {
        /** マスの高さ（幅に対する割合）。 */
        const val CELL_RATIO = 0.98f
    }
}
