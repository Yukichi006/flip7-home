package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** 通知が来ていることを表す「糸の玉」（フレンチノット）を描く。 */
fun drawKnot(canvas: Canvas, context: Context, cx: Float, cy: Float) {
    val r = context.dp(4.2f)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(1f), 0f, context.dp(1f), Thread.SHADOW)
    }
    canvas.drawCircle(cx, cy, r, fill)
    // 巻きつけた糸の筋
    val coil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(0.8f)
        color = 0x55000000
    }
    canvas.drawArc(RectF(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f), 200f, 220f, false, coil)
}

/**
 * アプリのパッド。[framed] なら 1 マスのワッペン（フェルトの布にステッチの縁取り）に載せ、
 * 刺繍アイコンと小さなアプリ名を描く。通知が来ていれば右上に糸の玉を付ける。
 *
 * - タップ：アプリを開く
 * - 下に引っ張る：2 つ目のアプリ（[second]）を開く。ゴムのように伸び、引ききると 2 つ目のアプリの絵に変わる
 * - 長押し：[onLongPress]（編集モードに入る）
 */
@SuppressLint("ViewConstructor")
class PadView(
    context: Context,
    val app: AppEntry,
    private val repo: AppRepository,
    private val second: AppEntry? = null,
    private val framed: Boolean = true,
    private val onLongPress: () -> Unit,
) : View(context) {
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(16f))
    private val label = context.threadText(9.5f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    private val hint = context.stitch(1.0f, Thread.DIM)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var pull = 0f
    private var armed = false

    private val badgeListener: () -> Unit = { invalidate() }

    init {
        setOnClickListener { repo.launch(app, this) }
        setOnLongClickListener { onLongPress(); true }
        contentDescription = repo.label(app)
        pivotY = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Badges.addListener(badgeListener)
    }

    override fun onDetachedFromWindow() {
        Badges.removeListener(badgeListener)
        super.onDetachedFromWindow()
    }

    /** 引っ張りきったとみなす長さ。 */
    private fun threshold() = height * 0.32f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pulling = false
                armed = false
                animate().cancel()
                animate().scaleX(0.95f).setDuration(90).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (!pulling && second != null && dy > slop && dy > abs(event.x - downX)) {
                    // 下に引っ張り始めた：ホーム画面のスワイプ（通知を開く）に取られないようにする
                    pulling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    super.onTouchEvent(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                }
                if (pulling) {
                    // ゴムのように、引くほど伸びにくくする
                    pull = (dy.coerceAtLeast(0f) * 0.5f).coerceAtMost(height * 0.55f)
                    scaleY = 1f + pull / height
                    val nowArmed = pull >= threshold()
                    if (nowArmed != armed) {
                        armed = nowArmed
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(260).start()
                if (pulling) {
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) second?.let { repo.launch(it, this) }
                    pulling = false
                    armed = false
                    pull = 0f
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (framed) patch.draw(canvas, width, height)
        // 引っ張りきったら、2 つ目のアプリの絵と名前に変える
        val shown = if (armed && second != null) second else app
        // ワッペンの縁のステッチにかからないよう、内側に余白を取る
        val inner = if (framed) patch.margin + context.dp(9f) else context.dp(2f)
        val labelH = label.textSize * 1.3f
        val iconPx = min(width - inner * 2, height - inner * 2 - labelH).coerceAtMost(context.dp(46f)).coerceAtLeast(context.dp(16f)).toInt()
        val total = iconPx + context.dp(3f) + labelH
        val top = (height - total) / 2f
        canvas.drawBitmap(GenreIcons.bitmap(repo.genre(shown), iconPx), (width - iconPx) / 2f, top, null)
        val name = repo.label(shown)
        val text = android.text.TextUtils.ellipsize(name, label, width - inner * 2, android.text.TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, width / 2f, top + iconPx + context.dp(3f) + label.textSize, label)
        // 2 つ目のアプリがあることを、下の小さな山形のステッチで知らせる
        if (second != null && framed) {
            val cx = width / 2f
            val y = height - patch.margin - context.dp(7f)
            val k = context.dp(3f)
            canvas.drawPath(Path().apply { moveTo(cx - k, y - k * 0.5f); lineTo(cx, y + k * 0.3f); lineTo(cx + k, y - k * 0.5f) }, hint)
        }
        if (Badges.count(app.component.packageName) > 0) {
            val m = if (framed) patch.margin + context.dp(9f) else context.dp(8f)
            drawKnot(canvas, context, width - m, m)
        }
    }
}

/**
 * ノブ（フォルダ）。縫い付けた大きなボタン（4 つ穴に糸を × に渡したもの）と、その下にフォルダの名前。
 * タップで [onOpen]（中のアプリを選ぶ吹き出しを開く）、長押しで [onLongPress]。
 * 中のアプリのどれかに通知が来ていれば、糸の玉を付ける。
 */
@SuppressLint("ViewConstructor")
class FolderKnob(
    context: Context,
    private val name: String,
    private val packages: List<String>,
    private val onOpen: (View) -> Unit,
    private val onLongPress: () -> Unit,
) : View(context) {
    private val felt = Fabric.paint(context, Cloth.FELT)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(6f), 0f, context.dp(3f), 0xC0000000.toInt())
    }
    private val rim = context.stitch(1.1f)
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        strokeWidth = context.dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val label = context.threadText(9.5f).apply { textAlign = Paint.Align.CENTER }
    private val badgeListener: () -> Unit = { invalidate() }

    /** 回っている角度（ページを移ると、慣性で少し回る）。 */
    var spin = 0f
        set(value) { field = value; invalidate() }

    init {
        setOnClickListener { onOpen(this) }
        setOnLongClickListener { onLongPress(); true }
        contentDescription = "フォルダ $name"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Badges.addListener(badgeListener) }
    override fun onDetachedFromWindow() { Badges.removeListener(badgeListener); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val labelH = label.textSize * 1.4f
        val r = (min(width.toFloat(), height - labelH) / 2f - context.dp(7f)).coerceAtLeast(context.dp(10f))
        val cx = width / 2f
        val cy = (height - labelH) / 2f + context.dp(2f)
        canvas.drawCircle(cx, cy, r, shadow)
        canvas.drawCircle(cx, cy, r, felt)
        canvas.drawCircle(cx, cy, r - context.dp(4f), rim)
        // 縁の盛り上がり（ボタンのふち）
        canvas.drawCircle(cx, cy, r * 0.62f, rim)
        // 4 つ穴と、縦に 2 本渡した糸（回っている角度の分だけ回す）
        canvas.save()
        canvas.rotate(spin, cx, cy)
        val d = r * 0.25f
        val holes = listOf(-d to -d, d to -d, d to d, -d to d)
        for ((x, y) in holes) canvas.drawCircle(cx + x, cy + y, r * 0.11f, hole)
        canvas.drawLine(cx - d, cy - d, cx - d, cy + d, cross)
        canvas.drawLine(cx + d, cy - d, cx + d, cy + d, cross)
        canvas.restore()
        val text = android.text.TextUtils.ellipsize(name, label, width - context.dp(8f), android.text.TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, cx, height - context.dp(6f), label)
        if (packages.any { Badges.count(it) > 0 }) drawKnot(canvas, context, cx + r * 0.75f, cy - r * 0.75f)
    }
}

/**
 * ノブを押したときに、そのすぐ上（入らなければ下）に開く吹き出し。中のアプリを 4 列で並べ、タップで開く。
 * 吹き出しの外をタップするか、戻るボタンで閉じる。
 */
@SuppressLint("ViewConstructor")
class FolderPopover(
    context: Context,
    title: String,
    private val anchor: View,
    pads: List<View>,
    private val onClosed: () -> Unit,
) : FrameLayout(context) {
    private val bubble = PatchLayout(context, PatchPainter.Shape.Round(22f))
    private val grid = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cw = w / COLS
            val ch = (cw * 1.05f).toInt()
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, ch * ceil(childCount / COLS.toFloat()).toInt().coerceAtLeast(1))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (i % COLS) * c.measuredWidth
                val y = (i / COLS) * c.measuredHeight
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }
    private var closing = false

    init {
        setOnClickListener { dismiss() }
        val column = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = title
            setTextColor(Thread.COLOR)
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(dpi(6f), 0, 0, dpi(4f))
        })
        pads.forEach { grid.addView(it) }
        column.addView(grid)
        bubble.addView(column)
        bubble.isClickable = true
        addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // ノブの上に置く。入らなければ下に置く
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val me = IntArray(2)
        getLocationInWindow(me)
        val ay = loc[1] - me[1]
        val bh = bubble.measuredHeight
        val y = if (ay - bh > dpi(24f)) ay - bh else ay + anchor.height
        bubble.layout(dpi(8f), y, width - dpi(8f), y + bh)
    }

    fun show(parent: ViewGroup) {
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bubble.alpha = 0f
        bubble.scaleX = 0.9f
        bubble.scaleY = 0.9f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    fun dismiss() {
        if (closing) return
        closing = true
        bubble.animate().alpha(0f).setDuration(120).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onClosed()
        }.start()
    }

    companion object {
        private const val COLS = 4
    }
}

/**
 * 下段のジーンズのポケット。[LayoutStore.DOCK_SLOTS] 個の枠に、アプリを 1 つずつ入れる。
 * 編集モード（[editing]）では、枠にチャコペンの縫い線を引き、枠のタップで [onSlotTap]、右上の × で [onSlotRemove]。
 */
class PocketDock(context: Context) : PatchLayout(context, PatchPainter.Shape.Pocket) {
    private var cells: List<View?> = List(LayoutStore.DOCK_SLOTS) { null }
    var onSlotTap: ((Int) -> Unit)? = null
    var onSlotRemove: ((Int) -> Unit)? = null

    var editing = false
        set(value) {
            field = value
            invalidate()
        }

    private val chalk = context.stitch(0.9f, Thread.FAINT)
    private val badge = BadgePainter(context)
    private val rect = RectF()

    init {
        // 上の二重ステッチの下から並べる
        val p = patch.contentPadding.toInt()
        setPadding(p, p + context.dpi(6f), p, p)
    }

    /** 枠ごとのアプリ（空の枠は null）。 */
    fun setCells(list: List<View?>) {
        removeAllViews()
        cells = List(LayoutStore.DOCK_SLOTS) { list.getOrNull(it) }
        cells.forEach { c -> c?.let { addView(it) } }
        requestLayout()
        invalidate()
    }

    private fun slotW() = (width - paddingLeft - paddingRight) / LayoutStore.DOCK_SLOTS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / LayoutStore.DOCK_SLOTS
        val ch = h - paddingTop - paddingBottom
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cw = slotW()
        cells.forEachIndexed { i, c ->
            c ?: return@forEachIndexed
            val x = paddingLeft + i * cw
            c.layout(x, paddingTop, x + c.measuredWidth, paddingTop + c.measuredHeight)
        }
    }

    private fun slotRect(i: Int, out: RectF) {
        val cw = slotW()
        val pad = context.dp(4f)
        out.set(paddingLeft + i * cw + pad, paddingTop + pad, paddingLeft + (i + 1) * cw - pad, height - paddingBottom - pad)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        val o = context.dp(BadgePainter.R_DP) * 0.3f
        for (i in 0 until LayoutStore.DOCK_SLOTS) {
            slotRect(i, rect)
            canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), chalk)
            if (cells[i] != null) badge.drawRemove(canvas, rect.right - o, rect.top + o)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing

    private var downSlot = -1
    private var downRemove = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(ev)
        val i = ((ev.x - paddingLeft) / slotW()).toInt().coerceIn(0, LayoutStore.DOCK_SLOTS - 1)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downSlot = i
                slotRect(i, rect)
                val o = context.dp(BadgePainter.R_DP) * 0.3f
                downRemove = cells[i] != null &&
                    kotlin.math.hypot(ev.x - (rect.right - o), ev.y - (rect.top + o)) < context.dp(BadgePainter.R_DP) * 1.7f
            }
            MotionEvent.ACTION_UP -> {
                if (i == downSlot) if (downRemove) onSlotRemove?.invoke(i) else onSlotTap?.invoke(i)
                downSlot = -1
            }
        }
        return true
    }
}

/** ページの点（今のページは糸の玉、ほかは小さなステッチの輪）。点のタップでそのページへ。 */
class PageDots(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    var count = 1
        set(value) { field = value; invalidate() }
    var current = 0
        set(value) { field = value; invalidate() }
    private val ring = context.stitch(0.9f, Thread.DIM)
    private val step get() = context.dp(18f)

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val start = width / 2f - step * (count - 1) / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            val x = start + i * step
            if (i == current) drawKnot(canvas, context, x, cy) else canvas.drawCircle(x, cy, context.dp(3.5f), ring)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (count <= 1) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val start = width / 2f - step * (count - 1) / 2f
            val i = ((event.x - start) / step + 0.5f).toInt().coerceIn(0, count - 1)
            onPick(i)
        }
        return true
    }
}
