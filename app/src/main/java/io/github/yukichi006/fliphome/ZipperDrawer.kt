package io.github.yukichi006.fliphome

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

/**
 * 下段のポケットの口に付けた、横向きのファスナー。開き具合が、アプリ一覧の開き具合とつながっている。
 * - 左端の引き手をつまんで右へ引くと、引き手が通ったところが V の字に開き、そのぶんアプリ一覧が下からせり上がる
 * - 開ききると、アプリ一覧がファスナーの上まで出る。ファスナーはそのまま残り、左へ引いて閉じると、アプリ一覧も下へ沈んで閉じる
 * - 途中で離すと、近いほう（開く・閉じる）へ動く。閉じているときにタップすると、少しだけ開いて「引ける」ことを知らせる
 *
 * 開き具合が変わるたびに [listener] の onZip（0〜1）、開ききった・閉じきったら onZipSettled を呼ぶ。
 * 描くのと指の動きを受けるのは、ポケット（[host]）から呼んでもらう。
 */
class ZipperTrack(private val host: View, private val listener: Listener) {
    interface Listener {
        /** 開き具合（0：閉じている〜1：開ききった）が変わったとき（指で引いているときも、動いているときも）。 */
        fun onZip(value: Float)

        /** 開ききった（[open] が true）・閉じきったとき。 */
        fun onZipSettled(open: Boolean)
    }

    private val context = host.context
    private val lining = Fabric.paint(context, Cloth.LINING)
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(context.dp(0.8f), 0f, context.dp(1f), Thread.SHADOW) }
    private val pullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(4f), 0f, context.dp(2f), 0xC0000000.toInt())
    }
    private val pullStitch = context.stitch(1.1f)
    private val peek = context.stitch(0.9f, Thread.DIM)
    private val rect = RectF()
    private val path = Path()

    /** 歯の高さ（片側）。 */
    val th = context.dp(6.5f)

    /** ファスナーの帯の高さ（ポケットの上に、この分を空けてもらう）。 */
    val band = th * 2 + context.dp(16f)

    // 引き手の大きさ
    private val bodyW = th * 3.4f
    private val bodyH = th * 3.0f
    private val tabW = th * 3.6f

    /** 引き手のつまみ（左へ垂れた分）と本体の左側の長さ。左端はこの分だけ空ける。 */
    val pullReach = bodyW * 0.35f + tabW - context.dp(4f)

    /** 開き具合（0：閉じている〜1：右端まで開いた）。 */
    var value = 0f
        private set(v) { field = v; host.invalidate(); listener.onZip(v) }

    /** ファスナーの上の縁の高さ（ポケットの中の位置。引き手の上まで）。 */
    val topY: Float get() = cy - th * 1.6f

    private var left = 0f
    private var right = 0f
    private var cy = 0f

    /** ファスナーを張る場所（歯の並ぶ左端と右端、真ん中の高さ）。描く前に決めてもらう。 */
    fun place(left: Float, right: Float, cy: Float) {
        this.left = left
        this.right = right
        this.cy = cy
    }

    private fun pullX() = left + (right - left) * value

    // ---------------- 指の動き ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startValue = 0f
    private var moved = false
    var dragging = false
        private set
    private var anim: ValueAnimator? = null

    /** この場所に触れたら、ファスナーを引き始めるか（帯の上か、引き手の上）。 */
    fun hit(x: Float, y: Float): Boolean {
        val px = pullX()
        val onPull = x in px - pullReach - context.dp(8f)..px + bodyW + context.dp(8f) && abs(y - cy) < bodyH
        val onBand = abs(y - cy) < band / 2 + context.dp(4f) && x in left - pullReach..right
        return onPull || onBand
    }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = ev.x
                downY = ev.y
                startValue = value
                moved = false
                dragging = true
                host.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = ev.x - downX
                if (!moved && (abs(dx) > slop || abs(ev.y - downY) > slop)) moved = true
                if (moved) {
                    val v = (startValue + dx / (right - left)).coerceIn(0f, 1f)
                    // 1 割開くごとに、カチッと小さく震える
                    if ((v * 10).toInt() != (value * 10).toInt()) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    value = v
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val wasOpen = startValue > 0.5f
                when {
                    !moved && ev.actionMasked == MotionEvent.ACTION_UP -> if (!wasOpen) nudge()
                    // 閉じていたら半分を少し越えれば開き、開いていたら少し閉じれば閉じる
                    if (wasOpen) value > 0.75f else value > 0.55f -> settle(true)
                    else -> settle(false)
                }
            }
        }
        return true
    }

    /** 開ききる（[open]）・閉じきるまで動かす。 */
    private fun settle(open: Boolean) {
        val target = if (open) 1f else 0f
        val ms = (260 * abs(target - value)).toLong().coerceAtLeast(90)
        animateTo(target, ms, DecelerateInterpolator(1.4f)) {
            if (open) host.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            listener.onZipSettled(open)
        }
    }

    /** 外から閉じる（アプリ一覧を戻るボタンなどで閉じたとき）。アプリ一覧もファスナーといっしょに沈む。 */
    fun close() = settle(false)

    /** 外から開ききった形に戻す（アプリ一覧を少し下げて離したとき）。 */
    fun reopen() = settle(true)

    /** 外から開き具合を合わせる（アプリ一覧を指で下げたときなど）。 */
    fun snapTo(v: Float) {
        anim?.cancel()
        value = v.coerceIn(0f, 1f)
    }

    /** タップされたら、少しだけ開いて閉じる（引けることを知らせる）。 */
    private fun nudge() {
        animateTo(0.12f, 140, DecelerateInterpolator()) { animateTo(0f, 320, OvershootInterpolator(2.2f)) { listener.onZipSettled(false) } }
    }

    private fun animateTo(target: Float, ms: Long, interp: android.animation.TimeInterpolator, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(value, target).apply {
            duration = ms
            interpolator = interp
            addUpdateListener { value = it.animatedValue as Float }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    // ---------------- 描く ----------------

    fun draw(canvas: Canvas) {
        if (right <= left) return
        val tw = th * 0.62f
        val step = tw * 1.15f
        val px = pullX()
        // 開いたところの広がり（引き手の近くは狭く、遠いほど広い V の字）
        val spread = th * 1.2f
        fun gapAt(x: Float) = if (x >= px) 0f else spread * ((px - x) / (px - left + context.dp(30f))).coerceIn(0f, 1f) + th * 0.15f

        // 1) すき間：裏地と、その奥にのぞくアプリの四角
        if (px > left + context.dp(1f)) {
            path.reset()
            path.moveTo(left, cy - gapAt(left))
            path.lineTo(px, cy)
            path.lineTo(left, cy + gapAt(left))
            path.close()
            canvas.drawPath(path, dark)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPath(path, lining)
            val s = th * 1.1f
            var x = left + s * 0.4f
            while (x < px) {
                rect.set(x, cy - s / 2, x + s, cy + s / 2)
                canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, peek)
                x += s * 1.6f
            }
            canvas.restore()
        }

        // 2) 歯：上下交互に並べる。閉じたところ（引き手より右）はかみ合って白く、開いたところは離れて薄い
        var x = left
        var top = true
        while (x <= right) {
            val g = gapAt(x)
            tooth.color = if (x < px) Thread.DIM else Thread.COLOR
            if (top) rect.set(x - tw / 2, cy - th - g, x + tw / 2, cy + th * 0.15f - g)
            else rect.set(x - tw / 2, cy - th * 0.15f + g, x + tw / 2, cy + th + g)
            canvas.drawRoundRect(rect, tw * 0.3f, tw * 0.3f, tooth)
            x += step
            top = !top
        }
        // 右端の留め具
        rect.set(right + tw * 0.4f, cy - th * 1.2f, right + tw * 2.2f, cy + th * 1.2f)
        canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, pullFill)

        // 3) 引き手：歯を包む本体と、左へ垂れたつまみ
        val d = context.dp(1f)
        val body = RectF(px - bodyW * 0.35f, cy - bodyH / 2, px + bodyW * 0.65f, cy + bodyH / 2)
        val tab = RectF(body.left - tabW + 4 * d, cy - th * 0.9f, body.left + 4 * d, cy + th * 0.9f)
        canvas.drawRoundRect(tab, th * 0.8f, th * 0.8f, pullFill)
        rect.set(tab.left + 3 * d, tab.top + 2.5f * d, tab.right - 4 * d, tab.bottom - 2.5f * d)
        canvas.drawRoundRect(rect, th * 0.6f, th * 0.6f, pullStitch)
        canvas.drawRoundRect(body, th * 0.9f, th * 0.9f, pullFill)
        rect.set(body.left + 2.5f * d, body.top + 2.5f * d, body.right - 2.5f * d, body.bottom - 2.5f * d)
        canvas.drawRoundRect(rect, th * 0.7f, th * 0.7f, pullStitch)
    }
}
