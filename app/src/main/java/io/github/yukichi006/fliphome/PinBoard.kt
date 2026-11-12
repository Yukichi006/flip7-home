package io.github.yukichi006.fliphome

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** 縫い付けメモの 1 枚（文字。[done] は、はがすと完了にする前の作りで使っていた印で、いまは使わない）。 */
data class PinTask(val text: String, val done: Boolean = false) {
    companion object {
        fun parse(s: String?): List<PinTask> = runCatching {
            val a = JSONArray(s ?: return emptyList())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> PinTask(o.getString("t"), o.optBoolean("d")) } }
        }.getOrDefault(emptyList())

        fun toJson(list: List<PinTask>): String =
            JSONArray(list.map { JSONObject().put("t", it.text).put("d", it.done) }).toString()
    }
}

/**
 * 縫い付けメモ（部品）。布のボードに、やることを書いた正方形の布の付箋を、まち針で留めておく。
 * - 空いたところをタップ → [onAdd]（書く欄を開く）。書いた付箋は、まち針で「ぷすっ」と留まる
 * - 付箋をつまんで引く → 角からめくれて、指についてくる。引ききって離すと、はがれて落ちる（＝完了。付箋はなくなる）。
 *   途中で離すと、元の場所に戻る
 * - 付箋を長押し → いちばん前へ留め直す
 * 残っている付箋の数を、右上にオレンジのタグで出す。中身は [onChange] で保存してもらう（端末の中だけ）。
 */
@SuppressLint("ViewConstructor")
class PinBoardView(
    context: Context,
    initial: List<PinTask>,
    private val onChange: (List<PinTask>) -> Unit,
    private val onAdd: () -> Unit,
) : PatchView(context, PatchPainter.Shape.Round(20f)) {
    private var tasks = initial

    private val title = context.threadText(10.5f, bold = true, color = Thread.DIM).apply { letterSpacing = 0.12f }
    private val hint = context.threadText(12f, color = Thread.DIM)
    private val more = context.threadText(13f, bold = true, color = Thread.DIM).apply { textAlign = Paint.Align.CENTER }

    /** 付箋の布の色（生成り・淡い黄・淡い桃・淡い水色）。文字から決めるので、同じ付箋はいつも同じ色。 */
    private val noteColors = intArrayOf(0xFFE9E4D8.toInt(), 0xFFEFE2A8.toInt(), 0xFFEBCFC0.toInt(), 0xFFCFDDE0.toInt())
    private val noteFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteBack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteSeam = stitchPaint(context.dp(0.9f), context.dp(2.4f), context.dp(1.8f), 0x55000000)
    private val text = context.threadText(12.5f, color = 0xFF1A1A1B.toInt()).apply { clearShadowLayer() }
    private val pinHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.ALERT
        setShadowLayer(context.dp(1.5f), 0f, context.dp(1f), 0xC0000000.toInt())
    }
    private val pinShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val pinNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFCBC3.toInt()
        strokeWidth = context.dp(1.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val notePath = Path()
    private val foldPath = Path()

    init {
        // 付箋のないところをタップしたら、書く欄を開く（長押しは編集モード）
        setOnClickListener { onAdd() }
    }

    /** 付箋を描いた場所（指の当たり判定に使う）。 */
    private val rects = mutableListOf<RectF>()

    // 動き：留めたばかりの付箋（大きく浮いて、まち針で留まる）と、はがしている付箋（角からめくれて、指についてくる）
    private var pinning = -1
    private var pinT = 1f
    private var peeling = -1
    private var peelDx = 0f
    private var peelDy = 0f
    /** はがれて落ちていく進み具合（0〜1）。 */
    private var fallT = 0f

    fun setTasks(list: List<PinTask>, pinnedNew: Boolean = false) {
        tasks = list
        if (pinnedNew && list.isNotEmpty()) {
            pinning = 0
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                interpolator = OvershootInterpolator(1.6f)
                addUpdateListener { pinT = it.animatedValue as Float; invalidate() }
                start()
            }
            postDelayed({ performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, 300)
        }
        invalidate()
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        rects.clear()
        canvas.drawText("やること", area.left, area.top + title.textSize, title)
        val top = area.top + title.textSize + context.dp(10f)
        if (tasks.isEmpty()) {
            canvas.drawText("タップして書く", area.left, top + hint.textSize * 1.6f, hint)
            return
        }
        // 正方形の付箋を、入るだけ並べる（一辺は 70dp くらい。部品が小さければ、入る大きさまで小さくする）
        val gap = context.dp(8f)
        val w = area.width()
        val h = area.bottom - top
        var cols = ((w + gap) / (context.dp(70f) + gap)).toInt().coerceAtLeast(1)
        var side = (w - gap * (cols - 1)) / cols
        if (side > h) {
            side = h.coerceAtLeast(context.dp(24f))
            cols = ((w + gap) / (side + gap)).toInt().coerceAtLeast(1)
        }
        val rows = ((h + gap) / (side + gap)).toInt().coerceAtLeast(1)
        val room = cols * rows
        val shownCount = if (tasks.size > room) room - 1 else tasks.size
        // 並べた全体を、横の真ん中に寄せる
        val left = area.left + (w - (cols * side + (cols - 1) * gap)) / 2f
        fun cell(i: Int) = RectF(
            left + (i % cols) * (side + gap), top + (i / cols) * (side + gap),
            left + (i % cols) * (side + gap) + side, top + (i / cols) * (side + gap) + side,
        )
        for (i in 0 until shownCount) rects += cell(i)
        // はがしている付箋は、ほかの付箋より手前に描く
        for (i in (0 until shownCount).filter { it != peeling }) drawNote(canvas, i, rects[i])
        if (peeling in 0 until shownCount) drawNote(canvas, peeling, rects[peeling])
        if (tasks.size > shownCount) {
            val c = cell(shownCount)
            canvas.drawText("+${tasks.size - shownCount}", c.centerX(), c.centerY() + more.textSize * 0.35f, more)
        }
        drawCountTag(canvas, context, width - patch.margin - context.dp(12f), patch.margin + context.dp(2f), tasks.size)
    }

    /** [i] 番目の付箋を [r] に描く（留めたばかり・はがしている途中の動きも）。 */
    private fun drawNote(canvas: Canvas, i: Int, r: RectF) {
        val t = tasks[i]
        val side = r.width()
        canvas.save()
        // 少しずつ傾けて、手で貼ったように
        var tilt = floatArrayOf(-2.2f, 1.6f, -0.8f, 2.4f, 0.6f, -1.6f)[i % 6]
        var lift = 0f
        var pinOut = 0f
        var fold = 0f
        var alpha = 255
        if (i == pinning && pinT < 1f) {
            lift = (1f - pinT) * context.dp(10f)
            tilt *= 1f + (1f - pinT) * 3f
            pinOut = 1f - pinT
        }
        if (i == peeling) {
            val dist = hypot(peelDx, peelDy)
            // 引くほど、右下の角がめくれる。少し引いたところで、まち針が抜ける
            fold = (dist / side).coerceIn(0f, 0.55f) * side
            pinOut = (dist / (side * 0.35f)).coerceIn(0f, 1f)
            canvas.translate(peelDx * 0.9f, peelDy * 0.9f + fallT * context.dp(46f))
            tilt += peelDx / side * 9f + fallT * 14f
            alpha = ((1f - fallT) * 255).toInt()
        }
        canvas.rotate(tilt, r.centerX(), r.top)
        canvas.translate(0f, -lift)
        val layer = if (alpha < 255) canvas.saveLayerAlpha(r.left - side, r.top - side, r.right + side, r.bottom + side, alpha) else -1
        val color = noteColors[(t.text.hashCode() and 0x7fffffff) % noteColors.size]
        noteFill.color = color
        noteFill.setShadowLayer(context.dp(3f) + fold * 0.12f, 0f, context.dp(1.5f) + fold * 0.08f, 0xB0000000.toInt())
        val rad = context.dp(3f)
        // 付箋の形（右下の角がめくれていれば、そこを斜めに切る）
        notePath.reset()
        if (fold <= 0f) notePath.addRoundRect(r, rad, rad, Path.Direction.CW) else {
            notePath.moveTo(r.left, r.top)
            notePath.lineTo(r.right, r.top)
            notePath.lineTo(r.right, r.bottom - fold)
            notePath.lineTo(r.right - fold, r.bottom)
            notePath.lineTo(r.left, r.bottom)
            notePath.close()
        }
        canvas.drawPath(notePath, noteFill)
        canvas.save()
        canvas.clipPath(notePath)
        val inset = RectF(r).apply { inset(context.dp(3.5f), context.dp(3.5f)) }
        canvas.drawRoundRect(inset, rad, rad, noteSeam)
        // 文字は、付箋の中で折り返す（入らない分は「…」）
        val pad = context.dp(7f)
        val textTop = r.top + context.dp(13f)
        val maxW = (side - pad * 2).toInt().coerceAtLeast(1)
        text.textSize = (side * 0.17f).coerceIn(context.sp(9.5f), context.sp(13f))
        val lineH = text.fontSpacing
        val lines = ((r.bottom - pad - textTop) / lineH).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(t.text, 0, t.text.length, text, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.translate(r.left + pad, textTop + ((r.bottom - pad - textTop) - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        // めくれた角の裏側（少し暗い色の三角）
        if (fold > 0f) {
            noteBack.color = darker(color)
            noteBack.setShadowLayer(context.dp(2f), -context.dp(1f), -context.dp(1f), 0x90000000.toInt())
            foldPath.reset()
            foldPath.moveTo(r.right, r.bottom - fold)
            foldPath.lineTo(r.right - fold, r.bottom)
            foldPath.lineTo(r.right - fold, r.bottom - fold)
            foldPath.close()
            canvas.drawPath(foldPath, noteBack)
        }
        // まち針：付箋の上の真ん中を刺す（抜けかけると上へ浮き、抜けきると消える）
        if (pinOut < 1f || i == pinning) {
            val pr = context.dp(4.5f)
            val px = r.centerX()
            val py = r.top + context.dp(5f) - pinOut * context.dp(12f)
            canvas.drawLine(px, py, px + context.dp(7f), py + context.dp(8f), pinNeedle)
            canvas.drawCircle(px, py, pr, pinHead)
            canvas.drawCircle(px - pr * 0.3f, py - pr * 0.35f, pr * 0.32f, pinShine)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restore()
    }

    private fun darker(color: Int): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(color) * 0.78f).toInt(),
        (android.graphics.Color.green(color) * 0.78f).toInt(),
        (android.graphics.Color.blue(color) * 0.78f).toInt(),
    )

    // ---------------- 指の動き ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var touched = -1
    private var dragging = false
    private var longDone = false
    private val longPress = Runnable {
        val i = touched
        if (i in tasks.indices && !dragging) {
            longDone = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // いちばん前へ留め直す
            val list = tasks.toMutableList()
            val t = list.removeAt(i)
            list.add(0, t)
            setTasks(list, pinnedNew = true)
            onChange(list)
        }
    }

    private fun noteAt(x: Float, y: Float) = rects.indexOfFirst { it.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                dragging = false; longDone = false
                // はがれて落ちている途中は、ほかの付箋を触れない
                touched = if (fallT > 0f) -1 else noteAt(ev.x, ev.y)
                if (touched < 0) return super.onTouchEvent(ev)
                // 付箋の上のスワイプは、ページの切り替えではなく、付箋をはがすのに使う
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touched < 0) return super.onTouchEvent(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && !longDone && hypot(dx, dy) > slop) {
                    removeCallbacks(longPress)
                    dragging = true
                }
                if (dragging) { peeling = touched; peelDx = dx; peelDy = dy; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touched < 0) return super.onTouchEvent(ev)
                removeCallbacks(longPress)
                val i = touched
                touched = -1
                if (longDone || !dragging) return true
                val side = rects.getOrNull(i)?.width() ?: width.toFloat()
                if (ev.actionMasked == MotionEvent.ACTION_UP && hypot(peelDx, peelDy) > side * 0.6f) peelOff(i) else stickBack()
                return true
            }
        }
        return true
    }

    /** 途中で離したら、元の場所に貼り直す。 */
    private fun stickBack() {
        val fromX = peelDx
        val fromY = peelDy
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 170
            addUpdateListener {
                val f = it.animatedValue as Float
                peelDx = fromX * f; peelDy = fromY * f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { peeling = -1; peelDx = 0f; peelDy = 0f; invalidate() }
            })
            start()
        }
    }

    /** はがれた付箋が、引いた向きへ流れながら落ちて消える（＝完了）。 */
    private fun peelOff(i: Int) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val fromX = peelDx
        val fromY = peelDy
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                fallT = it.animatedValue as Float
                peelDx = fromX * (1f + 0.5f * fallT)
                peelDy = fromY * (1f + 0.5f * fallT)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    peeling = -1; fallT = 0f; peelDx = 0f; peelDy = 0f
                    val list = tasks.filterIndexed { k, _ -> k != i }
                    setTasks(list)
                    onChange(list)
                }
            })
            start()
        }
    }
}
