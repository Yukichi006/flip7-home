package io.github.yukichi006.fliphome

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

/**
 * 横向きの大きなファスナー（アプリ一覧を開く部品）。左端の引き手をつまんで右へ引くと、引き手が通ったところが開き、
 * すき間から裏地とアプリの四角がのぞく。右端まで開いて離すと、アプリ一覧に切り替わる（[onOpen]）。
 * 途中で離すと、引き手は左へ戻ってファスナーが閉じる。タップすると、少しだけ開いて「引ける」ことを知らせる。
 * 長押しで [onLongPress]（編集モード）。
 */
@SuppressLint("ViewConstructor")
class ZipperDrawerView(
    context: Context,
    private val onOpen: () -> Unit,
    private val onLongPress: () -> Unit,
) : View(context), SewAware {
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(16f))
    private val lining = Fabric.paint(context, Cloth.LINING)
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(context.dp(0.8f), 0f, context.dp(1f), Thread.SHADOW) }
    private val tape = context.stitch(1.0f, Thread.DIM)
    private val pullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(4f), 0f, context.dp(2f), 0xC0000000.toInt())
    }
    private val pullStitch = context.stitch(1.1f)
    private val peek = context.stitch(0.9f, Thread.DIM)
    private val rect = RectF()
    private val path = Path()

    /** 開き具合（0：閉じている〜1：右端まで開いた）。 */
    private var value = 0f
        set(v) { field = v; invalidate() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startValue = 0f
    private var grabbed = false
    private var moved = false
    private var anim: ValueAnimator? = null
    private val longPress = Runnable { if (!moved) { grabbed = false; onLongPress() } }

    init { contentDescription = "ファスナー（アプリ一覧）" }

    /** 歯の高さ（片側）。部品の高さに合わせて大きくする。 */
    private fun th() = (height * 0.12f).coerceIn(dp(6f), dp(14f))

    // 左端は、引き手のつまみが布からはみ出さないだけ空ける。右端は留め具の分
    private fun trackLeft() = patch.margin + dp(6f) + th() * (3.4f * 0.35f + 3.6f) - dp(4f)
    private fun trackRight() = width - patch.margin - dp(12f) - th() * 2.4f
    private fun pullX() = trackLeft() + (trackRight() - trackLeft()) * value

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                downX = ev.x
                downY = ev.y
                startValue = value
                moved = false
                grabbed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (grabbed) {
                val dx = ev.x - downX
                if (!moved && (abs(dx) > slop || abs(ev.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (moved) {
                    val v = (startValue + dx / (trackRight() - trackLeft())).coerceIn(0f, 1f)
                    // 歯を 10 個ぶん開くごとに、カチッと小さく震える
                    if ((v * 10).toInt() != (value * 10).toInt()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    value = v
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (!grabbed) return true
                grabbed = false
                when {
                    !moved && ev.actionMasked == MotionEvent.ACTION_UP -> nudge()
                    value > 0.85f -> openAll()
                    else -> closeBack()
                }
            }
        }
        return true
    }

    /** 右端まで開ききってから、アプリ一覧に切り替える。一覧が開いたら、見えないうちに閉じておく。 */
    private fun openAll() {
        animateTo(1f, 120, DecelerateInterpolator()) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onOpen()
            postDelayed({ value = 0f }, 450)
        }
    }

    private fun closeBack() = animateTo(0f, 260, DecelerateInterpolator(1.6f))

    /** タップされたら、少しだけ開いて閉じる（引けることを知らせる）。 */
    private fun nudge() {
        animateTo(0.12f, 140, DecelerateInterpolator()) { animateTo(0f, 320, OvershootInterpolator(2.2f)) }
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

    override fun onDraw(canvas: Canvas) {
        patch.draw(canvas, width, height)
        val alpha = Sewing.contentAlpha()
        val save = if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha) else -1
        drawZipper(canvas)
        if (save >= 0) canvas.restoreToCount(save)
    }

    private fun drawZipper(canvas: Canvas) {
        val left = trackLeft()
        val right = trackRight()
        if (right <= left) return
        val cy = height / 2f
        val th = th()
        val tw = th * 0.62f                                      // 歯の幅
        val step = tw * 1.15f
        val px = pullX()
        // 開いたところの広がり（引き手の近くは狭く、遠いほど広い V の字）
        val spread = th * 1.6f
        fun gapAt(x: Float) = if (x >= px) 0f else spread * ((px - x) / (px - left + dp(30f))).coerceIn(0f, 1f) + th * 0.15f

        // 1) すき間：裏地と、その奥にのぞくアプリの四角
        if (px > left + dp(1f)) {
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

        // 2) テープの縫い目（上下の 2 本。開いたところは外へ逃げる）
        val tapeOff = th * 1.9f
        path.reset()
        path.moveTo(left, cy - tapeOff - gapAt(left)); path.lineTo(px, cy - tapeOff); path.lineTo(right, cy - tapeOff)
        canvas.drawPath(path, tape)
        path.reset()
        path.moveTo(left, cy + tapeOff + gapAt(left)); path.lineTo(px, cy + tapeOff); path.lineTo(right, cy + tapeOff)
        canvas.drawPath(path, tape)

        // 3) 歯：上下交互に並べる。閉じたところ（引き手より右）はかみ合って白く、開いたところは離れて薄い
        var x = left
        var top = true
        while (x <= right) {
            val open = x < px
            val g = gapAt(x)
            tooth.color = if (open) Thread.DIM else Thread.COLOR
            if (top) rect.set(x - tw / 2, cy - th - g, x + tw / 2, cy + th * 0.15f - g)
            else rect.set(x - tw / 2, cy - th * 0.15f + g, x + tw / 2, cy + th + g)
            canvas.drawRoundRect(rect, tw * 0.3f, tw * 0.3f, tooth)
            x += step
            top = !top
        }
        // 右端の留め具
        rect.set(right + tw * 0.4f, cy - th * 1.2f, right + tw * 2.2f, cy + th * 1.2f)
        canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, pullFill)


        // 4) 引き手：歯を包む本体と、左へ垂れたつまみ
        val bw = th * 3.4f
        val bh = th * 3.0f
        val body = RectF(px - bw * 0.35f, cy - bh / 2, px + bw * 0.65f, cy + bh / 2)
        val tabW = th * 3.6f
        val tab = RectF(body.left - tabW + dp(4f), cy - th * 0.9f, body.left + dp(4f), cy + th * 0.9f)
        canvas.drawRoundRect(tab, th * 0.8f, th * 0.8f, pullFill)
        rect.set(tab.left + dp(3f), tab.top + dp(2.5f), tab.right - dp(4f), tab.bottom - dp(2.5f))
        canvas.drawRoundRect(rect, th * 0.6f, th * 0.6f, pullStitch)
        canvas.drawRoundRect(body, th * 0.9f, th * 0.9f, pullFill)
        rect.set(body.left + dp(2.5f), body.top + dp(2.5f), body.right - dp(2.5f), body.bottom - dp(2.5f))
        canvas.drawRoundRect(rect, th * 0.7f, th * 0.7f, pullStitch)
    }
}
