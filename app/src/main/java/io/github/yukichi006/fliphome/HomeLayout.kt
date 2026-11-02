package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** ホーム画面のマス目に置く部品の種類。[w], [h] は置いたときの最初の大きさ（マスの数）。 */
enum class ModuleType(val label: String, val note: String, val w: Int, val h: Int, val resizable: Boolean) {
    CLOCK("時計", "大きな時計と日付", 4, 2, true),
    AGENDA("今日の予定", "今日これからの予定", 2, 2, true),
    BATTERY("電池", "本体と Bluetooth 機器の残量", 2, 2, true),
    APP("アプリ", "", 1, 1, false),
}

/** マス目に置いた部品 1 つ。[col], [row] は左上のマス、[w], [h] は占めるマスの数。[arg] はアプリのキーなど。 */
data class Module(val type: ModuleType, val col: Int, val row: Int, val w: Int, val h: Int, val arg: String = "") {
    fun covers(c: Int, r: Int) = c in col until col + w && r in row until row + h

    fun overlaps(o: Module) = col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h

    fun serialize() = listOf(type.name, col, row, w, h, arg).joinToString(",")

    companion object {
        fun parse(line: String): Module? = runCatching {
            val p = line.split(",", limit = 6)
            Module(ModuleType.valueOf(p[0]), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p.getOrElse(5) { "" })
        }.getOrNull()
    }
}

/** ホーム画面の配置・下段（ポケット）のアプリ・アイコンの選び直しを保存する。 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var modules: List<Module>
        get() = prefs.getString(KEY_LAYOUT, null)?.lines()?.mapNotNull { Module.parse(it) } ?: DEFAULT
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.joinToString("\n") { it.serialize() }).apply()

    /** 下段の [DOCK_SLOTS] 個の枠に入れたアプリのキー（空の枠は null）。まだ一度も決めていなければ null。 */
    var dock: List<String?>?
        get() = prefs.getString(KEY_DOCK, null)?.let { s ->
            val keys = s.split("\n").map { it.ifEmpty { null } }
            List(DOCK_SLOTS) { keys.getOrNull(it) }
        }
        set(value) = prefs.edit().putString(KEY_DOCK, value.orEmpty().joinToString("\n") { it.orEmpty() }).apply()

    fun setDockSlot(index: Int, key: String?) {
        val list = (dock ?: List(DOCK_SLOTS) { null }).toMutableList()
        // 同じアプリが 2 つの枠に入らないようにする
        if (key != null) list.replaceAll { if (it == key) null else it }
        list[index] = key
        dock = list
    }

    /** 1 枚目に収まるマスの行数（最後に表示したときのもの）。空きマスを探すのに使う。 */
    var rows: Int
        get() = prefs.getInt(KEY_ROWS, 7)
        set(value) = prefs.edit().putInt(KEY_ROWS, value).apply()

    /** アプリのアイコンとして選び直したジャンル。選んでいなければ null（自動で推し量る）。 */
    fun genreOf(key: String): Genre? = prefs.getString("genre:$key", null)?.let { runCatching { Genre.valueOf(it) }.getOrNull() }

    fun setGenre(key: String, genre: Genre?) =
        prefs.edit().apply { if (genre == null) remove("genre:$key") else putString("genre:$key", genre.name) }.apply()

    /** [type] の部品（アプリなら [arg]）を、空いているいちばん上・左のマスに置く。置けなければ false。 */
    fun add(type: ModuleType, arg: String = ""): Boolean {
        val list = modules
        val spot = findFree(list, type.w, type.h, rows) ?: return false
        modules = list + Module(type, spot.first, spot.second, type.w, type.h, arg)
        return true
    }

    /**
     * [type] の部品を [col], [row] のマスに置く。最初の大きさが入らなければ、入るところまで小さくする。
     * 置けたら、置いた部品を返す。
     */
    fun placeAt(type: ModuleType, col: Int, row: Int, arg: String = ""): Module? {
        val list = modules
        for (h in type.h downTo 1) for (w in type.w downTo 1) {
            val m = Module(type, col, row, w, h, arg)
            if (fits(list, m, rows)) {
                modules = list + m
                return m
            }
        }
        return null
    }

    fun replace(old: Module, new: Module) {
        modules = modules.map { if (it == old) new else it }
    }

    fun remove(module: Module) {
        modules = modules - module
    }

    fun has(type: ModuleType, arg: String = "") = modules.any { it.type == type && it.arg == arg }

    companion object {
        const val COLUMNS = 4
        const val DOCK_SLOTS = 4
        private const val KEY_LAYOUT = "layout"
        private const val KEY_DOCK = "dock"
        private const val KEY_ROWS = "rows"

        val DEFAULT = listOf(
            Module(ModuleType.CLOCK, 0, 0, 4, 2),
            Module(ModuleType.AGENDA, 0, 2, 2, 2),
            Module(ModuleType.BATTERY, 2, 2, 2, 2),
        )

        /** [m] がマス目の中に収まり、[ignore] 以外のどの部品とも重ならないか。 */
        fun fits(list: List<Module>, m: Module, rows: Int, ignore: Module? = null): Boolean =
            m.col >= 0 && m.row >= 0 && m.w >= 1 && m.h >= 1 &&
                m.col + m.w <= COLUMNS && m.row + m.h <= rows &&
                list.none { it != ignore && it.overlaps(m) }

        fun findFree(list: List<Module>, w: Int, h: Int, rows: Int): Pair<Int, Int>? {
            for (r in 0..rows - h) for (c in 0..COLUMNS - w) {
                if (fits(list, Module(ModuleType.APP, c, r, w, h), rows)) return c to r
            }
            return null
        }
    }
}

/**
 * 部品をマス目に並べる入れ物。横は [LayoutStore.COLUMNS] 列で、マスはほぼ正方形。
 * 縦に収まる行数は画面の高さで決まり、はみ出した部品は表示しない。
 *
 * 編集モード（[editing]）では、すべてのマスに仕立て屋のチャコペンのような薄い縫い線を引き、
 * - 空いたマスをタップ → [onEmptyTap]
 * - 部品をタップ → [onModuleTap]、右上の × → [onRemove]
 * - 部品をドラッグ → 空いている場所へ移動、右下のつまみをドラッグ → 大きさを変える（どちらも [onChange]）
 */
class ModuleGrid(context: Context) : ViewGroup(context) {
    class Params(val module: Module) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var onRowsChanged: ((Int) -> Unit)? = null
    var onEmptyTap: ((col: Int, row: Int) -> Unit)? = null
    var onModuleTap: ((Module) -> Unit)? = null
    var onRemove: ((Module) -> Unit)? = null
    var onChange: ((old: Module, new: Module) -> Unit)? = null

    var rows = 0
        private set

    var editing = false
        set(value) {
            field = value
            invalidate()
        }

    private val chalk = context.stitch(0.9f, Thread.FAINT)
    private val chalkStrong = context.stitch(1.4f, Thread.COLOR)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(3f), 0f, context.dp(1.5f), 0xC0000000.toInt())
    }
    private val badgeRing = context.stitch(1.1f)
    private val badgeMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        strokeWidth = context.dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    private fun cellW() = width.toFloat() / LayoutStore.COLUMNS
    private fun cellH(w: Int = width) = w.toFloat() / LayoutStore.COLUMNS * CELL_RATIO

    /** マス目の上の余白（余った高さを上下に半分ずつ配る）。 */
    private fun gridTop() = ((height - rows * cellH()) / 2).coerceAtLeast(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val ch = cellH(w)
        val newRows = max(1, (h / ch).toInt())
        if (newRows != rows && h > 0) {
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
        val top = gridTop()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val m = (child.layoutParams as Params).module
            val inside = m.row + m.h <= rows && m.col + m.w <= LayoutStore.COLUMNS
            val x = (m.col * cw).toInt()
            val y = (top + m.row * ch).toInt()
            // はみ出した部品は、画面の外に置いて見えなくする（表示・非表示の切り替えはしない）
            if (inside) child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            else child.layout(-child.measuredWidth * 2, 0, -child.measuredWidth, child.measuredHeight)
        }
    }

    fun addModule(view: View, module: Module) = addView(view, Params(module))

    private fun moduleViews(): List<Pair<View, Module>> =
        (0 until childCount).map { getChildAt(it) }.map { it to (it.layoutParams as Params).module }
            .filter { (_, m) -> m.row + m.h <= rows && m.col + m.w <= LayoutStore.COLUMNS }

    private fun cellRect(col: Int, row: Int, w: Int, h: Int, out: RectF) {
        val cw = cellW()
        val ch = cellH()
        val top = gridTop()
        val pad = context.dp(5f)
        out.set(col * cw + pad, top + row * ch + pad, (col + w) * cw - pad, top + (row + h) * ch - pad)
    }

    // ---------------- 描画 ----------------

    override fun dispatchDraw(canvas: Canvas) {
        if (editing) {
            // 空いているマスに、チャコペンの縫い線を引く
            val list = moduleViews().map { it.second }
            for (r in 0 until rows) for (c in 0 until LayoutStore.COLUMNS) {
                if (list.any { it.covers(c, r) }) continue
                cellRect(c, r, 1, 1, rect)
                canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), chalk)
            }
        }
        super.dispatchDraw(canvas)
        if (!editing) return
        val r = context.dp(13f)
        for ((v, m) in moduleViews()) {
            val dragged = v === dragView
            val dx = if (dragged) v.translationX else 0f
            val dy = if (dragged) v.translationY else 0f
            // 部品の輪郭（チャコペン）
            cellRect(m.col, m.row, m.w, m.h, rect)
            rect.offset(dx, dy)
            canvas.drawRoundRect(rect, context.dp(16f), context.dp(16f), chalk)
            // 右上の ×
            val bx = rect.right - r * 0.4f
            val by = rect.top + r * 0.4f
            canvas.drawCircle(bx, by, r, badgeFill)
            canvas.drawCircle(bx, by, r - context.dp(2.5f), badgeRing)
            val k = r * 0.35f
            canvas.drawLine(bx - k, by - k, bx + k, by + k, badgeMark)
            canvas.drawLine(bx + k, by - k, bx - k, by + k, badgeMark)
            // 右下の大きさを変えるつまみ（斜めの 2 本線）
            if (m.type.resizable) {
                val hx = rect.right - r * 0.4f
                val hy = rect.bottom - r * 0.4f
                canvas.drawCircle(hx, hy, r, badgeFill)
                canvas.drawCircle(hx, hy, r - context.dp(2.5f), badgeRing)
                canvas.drawLine(hx - k, hy + k, hx + k, hy - k, badgeMark)
                canvas.drawLine(hx, hy + k, hx + k, hy, badgeMark)
            }
        }
        // 動かしている・大きさを変えている部品の、置き先の目印
        preview?.let { p ->
            cellRect(p.col, p.row, p.w, p.h, rect)
            canvas.drawRoundRect(rect, context.dp(16f), context.dp(16f), if (previewOk) chalkStrong else chalk)
        }
    }

    // ---------------- 編集モードの操作 ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var touchModule: Module? = null
    private var dragView: View? = null
    private var resizing = false
    private var removeHit = false
    private var preview: Module? = null
    private var previewOk = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing

    /** 部品の × （[bottom] なら右下のつまみ）の中心から、指までの距離。 */
    private fun badgeDistance(m: Module, x: Float, y: Float, bottom: Boolean): Float {
        cellRect(m.col, m.row, m.w, m.h, rect)
        val r = context.dp(13f)
        val bx = rect.right - r * 0.4f
        val by = if (bottom) rect.bottom - r * 0.4f else rect.top + r * 0.4f
        return hypot(x - bx, y - by)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val c = (x / cellW()).toInt()
        val r = ((y - gridTop()) / cellH()).toInt()
        return if (c in 0 until LayoutStore.COLUMNS && r in 0 until rows && y >= gridTop()) c to r else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(ev)
        val list = moduleViews()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                moved = false
                resizing = false
                removeHit = false
                preview = null
                // × とつまみは、隣の部品のものと近いことがあるので、指にいちばん近いものを選ぶ
                val reach = context.dp(13f) * 1.6f
                val badges = list.flatMap { (v, m) ->
                    listOfNotNull(
                        Triple(v, m, false) to badgeDistance(m, ev.x, ev.y, false),
                        if (m.type.resizable) Triple(v, m, true) to badgeDistance(m, ev.x, ev.y, true) else null,
                    )
                }.filter { it.second < reach }.minByOrNull { it.second }?.first
                val body = list.lastOrNull { (v, _) -> ev.x >= v.left && ev.x < v.right && ev.y >= v.top && ev.y < v.bottom }
                when {
                    badges != null && !badges.third -> { touchModule = badges.second; removeHit = true }
                    badges != null -> { touchModule = badges.second; dragView = badges.first; resizing = true }
                    body != null -> { touchModule = body.second; dragView = body.first }
                    else -> { touchModule = null; dragView = null }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!moved && hypot(dx, dy) > slop) moved = true
                val m = touchModule
                val v = dragView
                if (moved && m != null && v != null && !removeHit) {
                    val all = list.map { it.second }
                    val target = if (resizing) {
                        val w = ((ev.x - m.col * cellW()) / cellW()).roundToInt().coerceIn(1, LayoutStore.COLUMNS - m.col)
                        val h = ((ev.y - gridTop() - m.row * cellH()) / cellH()).roundToInt().coerceIn(1, rows - m.row)
                        m.copy(w = w, h = h)
                    } else {
                        v.translationX = dx
                        v.translationY = dy
                        v.elevation = context.dp(8f)
                        val c = ((m.col * cellW() + dx) / cellW()).roundToInt()
                        val r = ((m.row * cellH() + dy) / cellH()).roundToInt()
                        m.copy(col = c, row = r)
                    }
                    if (target != preview) {
                        previewOk = LayoutStore.fits(all, target, rows, ignore = m)
                        if (preview != null && previewOk) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        preview = target
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val m = touchModule
                val p = preview
                if (!moved) {
                    when {
                        m != null && removeHit -> onRemove?.invoke(m)
                        m != null -> onModuleTap?.invoke(m)
                        else -> cellAt(ev.x, ev.y)?.let { (c, r) -> onEmptyTap?.invoke(c, r) }
                    }
                } else if (m != null && p != null && previewOk && p != m) {
                    onChange?.invoke(m, p)
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun endDrag() {
        dragView?.let { v ->
            v.animate().translationX(0f).translationY(0f).setDuration(120).withEndAction { v.elevation = 0f }.start()
        }
        dragView = null
        touchModule = null
        preview = null
        resizing = false
        removeHit = false
        invalidate()
    }

    companion object {
        /** マスの高さ（幅に対する割合）。 */
        const val CELL_RATIO = 0.98f
    }
}
