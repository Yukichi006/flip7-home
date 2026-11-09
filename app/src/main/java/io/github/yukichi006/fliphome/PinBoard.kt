package io.github.yukichi006.fliphome

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/** 縫い付けメモの 1 枚（文字と、済んだか）。 */
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
 * 縫い付けメモ（部品）。布のボードに、やることを書いた布の名札をまち針で留めておく。
 * - 空いたところをタップ → [onAdd]（書く欄を開く）。書いた名札は、まち針で「ぷすっ」と留まる
 * - 名札をタップ → 済み（文字に二重の縫い目を通す）／もう一度タップで戻す
 * - 名札を横へスワイプ → まち針が抜けて、名札が落ちて消える
 * - 名札を長押し → いちばん上へ留め直す
 * 済んでいない名札の数を、右上にオレンジのタグで出す。中身は [onChange] で保存してもらう（端末の中だけ）。
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
    private val more = context.threadText(11f, color = Thread.DIM)
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9E4D8.toInt()
        setShadowLayer(context.dp(2.5f), 0f, context.dp(1.5f), 0xB0000000.toInt())
    }
    private val tagDone = Paint(tagFill).apply { color = 0xFF9E9A92.toInt() }
    private val tagSeam = stitchPaint(context.dp(0.9f), context.dp(2.4f), context.dp(1.8f), 0x66000000)
    private val text = context.threadText(13f, color = 0xFF1A1A1B.toInt()).apply { clearShadowLayer() }
    private val strike = stitchPaint(context.dp(1.1f), context.dp(3f), context.dp(1.6f), 0xCC1A1A1B.toInt())
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

    init {
        // 名札のないところをタップしたら、書く欄を開く（長押しは編集モード）
        setOnClickListener { onAdd() }
    }

    /** 名札を描いた場所（タップの当たり判定に使う）。 */
    private val rects = mutableListOf<RectF>()

    // 動き：留めたばかりの名札（大きく浮いて、まち針で留まる）と、外している名札（横へ滑って落ちる）
    private var pinning = -1
    private var pinT = 1f
    private var leaving = -1
    private var leaveDx = 0f
    private var leaveT = 0f

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
        val open = tasks.count { !it.done }
        val top = area.top + title.textSize + context.dp(8f)
        if (tasks.isEmpty()) {
            canvas.drawText("タップして書く", area.left, top + hint.textSize * 1.6f, hint)
            return
        }
        val tagH = (text.textSize * 2.3f)
        val gap = context.dp(7f)
        val room = ((area.bottom - top + gap) / (tagH + gap)).toInt().coerceAtLeast(1)
        val shownCount = if (tasks.size > room) room - 1 else tasks.size
        val pin = context.dp(5f)
        for (i in 0 until shownCount) {
            val t = tasks[i]
            val y = top + i * (tagH + gap)
            val r = RectF(area.left + pin, y, area.right - context.dp(2f), y + tagH)
            rects += RectF(r)
            canvas.save()
            // 少しずつ傾けて、手で留めたように
            var tilt = if (i % 2 == 0) -1.2f else 0.9f
            var lift = 0f
            if (i == pinning && pinT < 1f) { lift = (1f - pinT) * context.dp(10f); tilt *= 1f + (1f - pinT) * 3f }
            if (i == leaving) {
                canvas.translate(leaveDx, leaveT * context.dp(40f))
                tilt += leaveDx / width * 25f
            }
            canvas.rotate(tilt, r.left, r.centerY())
            canvas.translate(0f, -lift)
            if (i == leaving) {
                val save = canvas.saveLayerAlpha(r.left - pin * 2, r.top - pin * 2, r.right + pin, r.bottom + pin * 2, ((1f - leaveT) * 255).toInt())
                drawTag(canvas, r, t, pin, pinOut = leaveT)
                canvas.restoreToCount(save)
            } else drawTag(canvas, r, t, pin, pinOut = if (i == pinning && pinT < 1f) (1f - pinT) else 0f)
            canvas.restore()
        }
        if (tasks.size > shownCount) {
            canvas.drawText("ほか ${tasks.size - shownCount} 枚", area.left + pin, top + shownCount * (tagH + gap) + more.textSize * 1.2f, more)
        }
        if (open > 0) drawCountTag(canvas, context, width - patch.margin - context.dp(12f), patch.margin + context.dp(2f), open)
    }

    /** 名札 1 枚：生成りの布に縫い目の縁、文字、左のまち針（[pinOut] は 0〜1、抜けかけ具合）。 */
    private fun drawTag(canvas: Canvas, r: RectF, t: PinTask, pin: Float, pinOut: Float) {
        val rad = context.dp(5f)
        canvas.drawRoundRect(r, rad, rad, if (t.done) tagDone else tagFill)
        val inset = RectF(r).apply { inset(context.dp(3f), context.dp(3f)) }
        canvas.drawRoundRect(inset, rad * 0.6f, rad * 0.6f, tagSeam)
        val tx = r.left + pin + context.dp(14f)
        val maxW = r.right - tx - context.dp(8f)
        val s = TextUtils.ellipsize(t.text, text, maxW, TextUtils.TruncateAt.END)
        val baseline = r.centerY() + text.textSize * 0.35f
        text.alpha = if (t.done) 150 else 255
        canvas.drawText(s, 0, s.length, tx, baseline, text)
        if (t.done) {
            val w = min(text.measureText(s.toString()), maxW)
            canvas.drawLine(tx - context.dp(2f), r.centerY() - context.dp(1.5f), tx + w + context.dp(2f), r.centerY() - context.dp(1.5f), strike)
            canvas.drawLine(tx - context.dp(2f), r.centerY() + context.dp(1.5f), tx + w + context.dp(2f), r.centerY() + context.dp(1.5f), strike)
        }
        // まち針：名札の左上を刺し、頭は布の外へ（抜けかけると上へ浮く）
        val px = r.left + pin
        val py = r.top + context.dp(6f) - pinOut * context.dp(12f)
        canvas.drawLine(px, py, px + context.dp(9f), py + context.dp(9f), pinNeedle)
        canvas.drawCircle(px, py, pin, pinHead)
        canvas.drawCircle(px - pin * 0.3f, py - pin * 0.35f, pin * 0.32f, pinShine)
    }

    // ---------------- 指の動き ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var touched = -1
    private var swiping = false
    private var longDone = false
    private val longPress = Runnable {
        val i = touched
        if (i in tasks.indices && !swiping) {
            longDone = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // いちばん上へ留め直す
            val list = tasks.toMutableList()
            val t = list.removeAt(i)
            list.add(0, t)
            setTasks(list, pinnedNew = true)
            onChange(list)
        }
    }

    private fun tagAt(x: Float, y: Float) = rects.indexOfFirst { it.contains(x, y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                swiping = false; longDone = false
                touched = tagAt(ev.x, ev.y)
                if (touched < 0) return super.onTouchEvent(ev)
                // 名札の上の横のスワイプは、ページの切り替えではなく名札を外すのに使う
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touched < 0) return super.onTouchEvent(ev)
                val dx = ev.x - downX
                if (!swiping && (abs(dx) > slop || abs(ev.y - downY) > slop)) {
                    removeCallbacks(longPress)
                    if (abs(dx) > abs(ev.y - downY)) swiping = true
                }
                if (swiping) { leaving = touched; leaveDx = dx; leaveT = 0f; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touched < 0) return super.onTouchEvent(ev)
                removeCallbacks(longPress)
                val i = touched
                touched = -1
                if (longDone) return true
                if (swiping) {
                    if (abs(leaveDx) > width * 0.28f) removeTask(i) else {
                        // 戻す
                        ValueAnimator.ofFloat(leaveDx, 0f).apply {
                            duration = 160
                            addUpdateListener { leaveDx = it.animatedValue as Float; invalidate() }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(a: android.animation.Animator) { leaving = -1; invalidate() }
                            })
                            start()
                        }
                    }
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && i in tasks.indices) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val list = tasks.mapIndexed { k, t -> if (k == i) t.copy(done = !t.done) else t }
                    setTasks(list)
                    onChange(list)
                }
                return true
            }
        }
        return true
    }

    /** まち針が抜けて、名札が横へ滑りながら落ちて消える。 */
    private fun removeTask(i: Int) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val dir = if (leaveDx >= 0) 1 else -1
        val from = leaveDx
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                leaveT = it.animatedValue as Float
                leaveDx = from + dir * width * 0.4f * leaveT
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    leaving = -1; leaveT = 0f; leaveDx = 0f
                    val list = tasks.filterIndexed { k, _ -> k != i }
                    setTasks(list)
                    onChange(list)
                }
            })
            start()
        }
    }
}
