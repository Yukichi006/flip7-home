package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * アプリのパネルの布 1 枚。大きなフェルトのワッペンの中に、アプリを並べるマス目（[grid]）を入れる。
 * 下には、ページの点とめくる角のための帯を空けておく。
 */
@SuppressLint("ViewConstructor")
class SheetView(context: Context, val grid: ModuleGrid) : FrameLayout(context) {
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(24f))
    val margin get() = patch.margin

    init {
        setWillNotDraw(false)
        val p = patch.contentPadding.toInt() - dpi(6f)
        setPadding(p, p, p, p + dpi(STRIP_DP))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onDraw(canvas: Canvas) = patch.draw(canvas, width, height)

    companion object {
        /** 下の帯（ページの点とめくる角）の高さ。 */
        const val STRIP_DP = 26f

        /** 布の縁からマス目までの幅（左右と上）。 */
        fun side(context: Context) = PatchPainter(context, PatchPainter.Shape.Round(24f)).contentPadding.toInt() - context.dpi(6f)
    }
}

/**
 * 片手の画面の右の列。下にめくれるアプリのパネル（[panel]）、その上に見るだけの情報の欄（[top]）を置く。
 * マスの大きさは列の幅で決まり（3 列、高さは幅の [Thumb.CELL_RATIO] 倍）、パネルの高さは段の数（[rows]）で決まる。
 * 残りの高さに入るだけの行が、上の情報の欄になる。
 *
 * 編集モード（[editing]）では、パネルの上の縁に「つまみ」を縫い付ける。つまみを上下にドラッグすると、
 * パネルの段の数が変わり、離すと [onRowsChanged] に新しい段の数を渡す。
 */
@SuppressLint("ViewConstructor")
class RightColumn(
    context: Context,
    val top: ModuleGrid,
    private val onRowsChanged: (Int) -> Unit,
) : ViewGroup(context) {
    var panel: BookPanel? = null
        set(value) {
            if (field === value) return
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it) }
        }

    var rows = Thumb.DEFAULT_ROWS
        set(value) { if (field != value) { field = value; requestLayout() } }

    var editing = false
        set(value) { field = value; invalidate() }

    private val side = SheetView.side(context)
    private val strip = context.dpi(SheetView.STRIP_DP)
    private val gap = context.dpi(6f)

    init {
        setWillNotDraw(false)
        addView(top)
    }

    private fun cellW(w: Int) = (w - side * 2) / Thumb.COLS.toFloat()
    private fun cellH(w: Int) = cellW(w) * Thumb.CELL_RATIO
    private fun panelHeight(w: Int, h: Int, r: Int) = minOf(h, (r * cellH(w)).roundToInt() + side + side + strip)

    /** この画面の高さで、パネルにできるいちばん多い段の数。 */
    fun maxRows(): Int {
        if (width == 0) return LayoutStore.MAX_ROWS
        return floor((height - side * 2 - strip) / cellH(width)).toInt().coerceIn(Thumb.MIN_ROWS, LayoutStore.MAX_ROWS)
    }

    /** 上の情報の欄に入る行の数。 */
    private fun topRows(w: Int, h: Int) = floor((h - panelHeight(w, h, rows) - gap) / cellH(w)).toInt().coerceAtLeast(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ph = panelHeight(w, h, rows)
        panel?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
        val tr = topRows(w, h)
        top.configure(Thumb.COLS, tr.coerceAtLeast(1))
        top.visibility = if (tr > 0) View.VISIBLE else View.GONE
        top.measure(MeasureSpec.makeMeasureSpec(w - side * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((tr * cellH(w)).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val ph = panelHeight(w, h, rows)
        panel?.layout(0, h - ph, w, h)
        // 情報の欄は上にそろえる（パネルの高さを変えても、上の部品の場所は動かない）
        top.layout(side, 0, side + top.measuredWidth, top.measuredHeight)
    }

    // ---- 編集モードの、パネルの高さを変えるつまみ ----

    private val tab = RectF()
    private val tabPatch = PatchPainter(context, PatchPainter.Shape.Round(12f))
    private val chevron = context.stitch(1.4f)
    private val label = context.threadText(12f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private var dragging = false

    private fun tabRect(): RectF {
        val y = (panel?.top ?: height).toFloat()
        val tw = context.dp(132f)
        val th = context.dp(34f)
        tab.set(width / 2f - tw / 2, y - th * 0.55f, width / 2f + tw / 2, y + th * 0.45f)
        return tab
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        val r = tabRect()
        canvas.save()
        canvas.translate(r.left - tabPatch.margin, r.top - tabPatch.margin)
        tabPatch.draw(canvas, (r.width() + tabPatch.margin * 2).toInt(), (r.height() + tabPatch.margin * 2).toInt())
        canvas.restore()
        // 上下の矢印を縫った山形と、今の段の数
        val cx = r.left + context.dp(22f)
        val cy = r.centerY()
        val a = context.dp(5f)
        val path = Path().apply {
            moveTo(cx - a, cy - a * 0.6f); lineTo(cx, cy - a * 1.6f); lineTo(cx + a, cy - a * 0.6f)
            moveTo(cx - a, cy + a * 0.6f); lineTo(cx, cy + a * 1.6f); lineTo(cx + a, cy + a * 0.6f)
        }
        canvas.drawPath(path, chevron)
        canvas.drawText("アプリ $rows 段", r.centerX() + context.dp(10f), cy - (label.ascent() + label.descent()) / 2, label)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editing || ev.actionMasked != MotionEvent.ACTION_DOWN) return dragging
        val r = tabRect()
        val slack = context.dp(10f)
        dragging = ev.x in r.left - slack..r.right + slack && ev.y in r.top - slack..r.bottom + slack
        if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 指の高さから、パネルの段の数を決める（マスの高さごとに、ぴたっと止まる）
                val r = ((height - ev.y - side * 2 - strip) / cellH(width)).roundToInt().coerceIn(Thumb.MIN_ROWS, maxRows())
                if (r != rows) {
                    rows = r
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onRowsChanged(rows)
            }
        }
        return true
    }
}

/**
 * めくれるアプリのパネル。いちばん上の布（[top]）の角をつまんで [peel] の向きにめくると、下の布（[under]）が見え、
 * めくりきって離すと次の布に替わる（[onTurned]）。最後の布の次は最初の布に戻る。
 * 布が 2 枚以上あるときは、めくる角が少しだけめくれていて、下の布がのぞく。下の帯にページの点を縫う。
 */
@SuppressLint("ViewConstructor")
class BookPanel(
    context: Context,
    private val peel: PeelFrom,
    private val onTurned: () -> Unit,
) : FrameLayout(context) {
    private var top: SheetView? = null
    private var under: SheetView? = null
    var count = 1
        private set
    var index = 0
        private set

    private val back = Fabric.paint(context, Cloth.BACK)
    private val flapShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.BACK
        setShadowLayer(context.dp(10f), 0f, context.dp(3f), 0xD0000000.toInt())
    }
    private val foldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF; strokeWidth = context.dp(1f) }
    private val backStitch = context.stitch(1.1f, Thread.DIM)
    private val ring = context.stitch(0.9f, Thread.DIM)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var anchor: Pair<Float, Float>? = null
    private var finger: Pair<Float, Float>? = null
    private var downX = 0f
    private var downY = 0f
    private var inZone = false
    private var peeling = false
    private var armed = false
    private var anim: android.animation.ValueAnimator? = null

    init { setWillNotDraw(false) }

    /** 今の布と、その次の布を入れる。 */
    fun setSheets(topSheet: SheetView, underSheet: SheetView?, index: Int, count: Int) {
        anim?.cancel()
        removeAllViews()
        this.index = index
        this.count = count
        under = underSheet
        top = topSheet
        underSheet?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        addView(topSheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        anchor = null
        finger = null
        peeling = false
        invalidate()
    }

    private fun canTurn() = count > 1 && under != null

    private fun sheetRect(): RectF {
        val m = top?.margin ?: 0f
        return RectF(m, m, width - m, height - m)
    }

    private fun startPoint(r: RectF, x: Float, y: Float): Pair<Float, Float> {
        val cx = when (peel.sx) { 1 -> r.right; -1 -> r.left; else -> x.coerceIn(r.left, r.right) }
        val cy = when (peel.sy) { 1 -> r.bottom; -1 -> r.top; else -> y.coerceIn(r.top, r.bottom) }
        return cx to cy
    }

    private fun extent(r: RectF) = if (peel.corner) hypot(r.width(), r.height()) else if (peel.sx != 0) r.width() else r.height()

    /** めくり始められる所：角なら角のまわり、辺なら辺に沿った帯。 */
    private fun zone(x: Float, y: Float): Boolean {
        val r = sheetRect()
        val reach = dp(72f)
        val nearX = when (peel.sx) { 1 -> x > r.right - reach; -1 -> x < r.left + reach; else -> true }
        val nearY = when (peel.sy) { 1 -> y > r.bottom - reach; -1 -> y < r.top + reach; else -> true }
        return nearX && nearY
    }

    private fun restDepth() = if (peel.corner) dp(34f) else dp(12f)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                peeling = false
                armed = false
                inZone = canTurn() && zone(ev.x, ev.y)
                if (inZone) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> detectStart(ev)
        }
        return peeling
    }

    /** めくる向きに動いたら、めくり始める。違う向きなら、ホーム画面のスワイプに任せる。 */
    private fun detectStart(ev: MotionEvent) {
        if (!inZone || peeling) return
        val dx = ev.x - downX
        val dy = ev.y - downY
        val along = dx * peel.ux + dy * peel.uy
        if (along > slop) {
            peeling = true
            anim?.cancel()
            anchor = startPoint(sheetRect(), downX, downY)
        } else if (along < -slop || abs(-dx * peel.uy + dy * peel.ux) > slop * 2) {
            inZone = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 布の下の帯など、アプリのない所から触り始めたときは、ここで初めて動きを見る
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            inZone = canTurn() && zone(ev.x, ev.y)
            return inZone
        }
        if (!peeling) detectStart(ev)
        if (!peeling) return inZone
        val r = sheetRect()
        val c = anchor ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                finger = if (peel.corner) {
                    ev.x.coerceIn(r.left, r.right) to ev.y.coerceIn(r.top, r.bottom)
                } else {
                    val t = ((ev.x - c.first) * peel.ux + (ev.y - c.second) * peel.uy).coerceIn(0f, extent(r))
                    (c.first + peel.ux * t) to (c.second + peel.uy * t)
                }
                val f = finger!!
                val nowArmed = hypot(c.first - f.first, c.second - f.second) / extent(r) > 0.35f
                if (nowArmed != armed) {
                    armed = nowArmed
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                peeling = false
                if (armed && ev.actionMasked == MotionEvent.ACTION_UP) turnOver() else settleBack()
            }
        }
        return true
    }

    /** めくりきった：布を最後まで返してから、次の布に替える。 */
    private fun turnOver() {
        val c = anchor ?: return
        val r = sheetRect()
        val f = finger ?: c
        val far = (c.first + peel.ux * extent(r) * 2.1f) to (c.second + peel.uy * extent(r) * 2.1f)
        animateFinger(f, far, 240) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            anchor = null
            finger = null
            onTurned()
        }
    }

    private fun settleBack() {
        val c = anchor ?: return
        val f = finger ?: return
        val rest = (c.first + peel.ux * restDepth()) to (c.second + peel.uy * restDepth())
        animateFinger(f, rest, 240) { anchor = null; finger = null; invalidate() }
    }

    private fun animateFinger(from: Pair<Float, Float>, to: Pair<Float, Float>, ms: Long, end: () -> Unit) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener {
                val t = it.animatedValue as Float
                finger = (from.first + (to.first - from.first) * t) to (from.second + (to.second - from.second) * t)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    // ---------------- 描画 ----------------

    /** 今のめくり（折り目の位置と向き）。めくれていなければ null。 */
    private fun fold(): FloatArray? {
        if (!canTurn()) return null
        val r = sheetRect()
        val c = anchor ?: startPoint(r, r.centerX(), r.centerY())
        val f = finger ?: ((c.first + peel.ux * restDepth()) to (c.second + peel.uy * restDepth()))
        var nx = c.first - f.first
        var ny = c.second - f.second
        val len = hypot(nx, ny)
        if (len < 1f) return null
        nx /= len; ny /= len
        return floatArrayOf((c.first + f.first) / 2f, (c.second + f.second) / 2f, nx, ny)
    }

    private fun halfPlane(mx: Float, my: Float, nx: Float, ny: Float, sign: Float): Path {
        val big = (width + height) * 4f
        val ex = -ny
        val ey = nx
        return Path().apply {
            moveTo(mx + ex * big, my + ey * big)
            lineTo(mx - ex * big, my - ey * big)
            lineTo(mx - ex * big + nx * big * sign, my - ey * big + ny * big * sign)
            lineTo(mx + ex * big + nx * big * sign, my + ey * big + ny * big * sign)
            close()
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val f = fold()
        if (child !== top || f == null) {
            // めくれていないときは、下の布は見せない（今の布だけ）
            if (child === under && f == null) return false
            return super.drawChild(canvas, child, drawingTime)
        }
        val (mx, my, nx, ny) = listOf(f[0], f[1], f[2], f[3])
        // 今の布は、めくれていない側だけを描く
        canvas.save()
        canvas.clipPath(halfPlane(mx, my, nx, ny, -1f))
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        // めくれた布の裏側（折り目で裏返した形）
        val r = sheetRect()
        val shape = Path().apply { addRoundRect(r, dp(24f), dp(24f), Path.Direction.CW) }
        val angle = Math.toDegrees(kotlin.math.atan2(nx.toDouble(), -ny.toDouble())).toFloat()
        val reflect = Matrix().apply {
            postTranslate(-mx, -my); postRotate(-angle); postScale(1f, -1f); postRotate(angle); postTranslate(mx, my)
        }
        val flap = Path(shape).apply { op(halfPlane(mx, my, nx, ny, 1f), Path.Op.INTERSECT); transform(reflect) }
        canvas.drawPath(flap, flapShadow)
        canvas.drawPath(flap, back)
        canvas.save()
        canvas.clipPath(flap)
        canvas.concat(reflect)
        canvas.drawRoundRect(RectF(r).apply { inset(dp(7f), dp(7f)) }, dp(18f), dp(18f), backStitch)
        canvas.restore()
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawLine(mx - ny * 2000, my + nx * 2000, mx + ny * 2000, my - nx * 2000, foldLine)
        canvas.restore()
        return drawn
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 下の帯に、ページの点（今の布は糸の玉）
        if (count <= 1) return
        val step = dp(16f)
        val y = height - (top?.margin ?: 0f) - dp(SheetView.STRIP_DP) / 2f - dp(4f)
        val start = width / 2f - step * (count - 1) / 2f
        for (i in 0 until count) {
            val x = start + i * step
            if (i == index) drawKnot(canvas, context, x, y) else canvas.drawCircle(x, y, dp(3.2f), ring)
        }
    }
}
