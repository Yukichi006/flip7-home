package io.github.yukichi006.fliphome

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * アナログ時計（カバー画面の FlipCover の時計と同じ絵）。フェルトの丸いワッペンに縫い付けた文字盤：
 * - 12 時から今の分までを、内側の輪に沿って縫い進めた糸目で表す
 * - 目盛りは、3・6・9・12 時が × のクロスステッチ、ほかは糸の玉
 * - 時針はサテン刺繍の太い針、分針はステッチの針、秒は縫い針が 1 秒ずつ進む
 * - 真ん中は、針を留める小さなボタン
 * タップでアラーム。
 */
class AnalogClockView(context: Context) : TickingView(context) {
    override val seconds = true
    private val d = resources.displayMetrics.density
    private val felt = Fabric.paint(context, Cloth.FELT)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(8f * d, 0f, 3f * d, 0xC0000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(0.8f * d, 0f, 1f * d, Thread.SHADOW)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFD8D4CC.toInt()
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(1f * d, 0f, 1.2f * d, Thread.SHADOW)
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val oval = RectF()

    init {
        setOnClickListener {
            runCatching { context.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - 8f * d
        if (r <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, shadow)
        canvas.drawCircle(cx, cy, r, felt)
        val w = (r * 0.012f).coerceIn(1.1f * d, 2.4f * d)
        canvas.drawCircle(cx, cy, r - r * 0.06f, stitchPaint(w, w * 3f, w * 2.1f))

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND)
        val min = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + min / 60f

        // 12 時から今の分まで、内側の輪に沿って縫い進めた糸目（まだ縫っていない所は薄い下書き）
        val ring = r * 0.78f
        oval.set(cx - ring, cy - ring, cx + ring, cy + ring)
        canvas.drawArc(oval, -90f, 360f, false, stitchPaint(w * 0.8f, w * 2.5f, w * 2.5f, Thread.FAINT))
        canvas.drawArc(oval, -90f, min / 60f * 360f, false, stitchPaint(w * 1.1f, w * 3.2f, w * 2f))

        // 目盛り
        val cross = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = w * 1.3f * StitchStyle.scale; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val x = cx + (r * 0.88f) * cos(a).toFloat()
            val y = cy + (r * 0.88f) * sin(a).toFloat()
            if (i % 3 == 0) {
                val k = r * 0.04f
                canvas.drawLine(x - k, y - k, x + k, y + k, cross)
                canvas.drawLine(x + k, y - k, x - k, y + k, cross)
            } else {
                canvas.drawCircle(x, y, r * 0.018f, fill)
            }
        }

        // 時針：サテン刺繍の太い針
        hand(canvas, cx, cy, hour / 12f * 360f, r * 0.45f, Paint(fill).apply {
            style = Paint.Style.STROKE; strokeWidth = r * 0.055f; strokeCap = Paint.Cap.ROUND
        })
        // 分針：ステッチの針
        hand(canvas, cx, cy, min / 60f * 360f, r * 0.7f, stitchPaint(w * 1.4f, w * 3f, w * 1.8f))
        // 秒：縫い針（先がとがり、根元に糸を通す穴）
        val a = Math.toRadians(sec * 6.0 - 90)
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        needle.strokeWidth = (r * 0.014f).coerceAtLeast(1.2f * d)
        canvas.drawLine(cx - r * 0.18f * ca, cy - r * 0.18f * sa, cx + r * 0.82f * ca, cy + r * 0.82f * sa, needle)
        canvas.drawCircle(cx - r * 0.13f * ca, cy - r * 0.13f * sa, needle.strokeWidth * 0.9f, hole)
        // 真ん中のボタン
        canvas.drawCircle(cx, cy, r * 0.065f, shadow)
        canvas.drawCircle(cx, cy, r * 0.065f, felt)
        canvas.drawCircle(cx - r * 0.02f, cy, r * 0.012f, hole)
        canvas.drawCircle(cx + r * 0.02f, cy, r * 0.012f, hole)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, len: Float, paint: Paint) {
        val a = Math.toRadians(deg - 90.0)
        canvas.drawLine(cx, cy, cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), paint)
    }
}
