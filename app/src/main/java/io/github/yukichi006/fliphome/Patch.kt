package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout

/**
 * 縫い付けたワッペン（別布）を描く。少し明るいフェルトの布を角丸に切り、下に影を落とし、
 * 縁から少し内側をステッチで縫う。影の分だけ、外側に [margin] の余白を取る。
 */
class PatchPainter(private val context: Context, private val shape: Shape = Shape.Round(18f)) {
    sealed interface Shape {
        /** 角丸の四角。[radiusDp] が高さの半分以上ならカプセル形になる。 */
        data class Round(val radiusDp: Float) : Shape

        /** ジーンズのポケット：上辺は二重のステッチ、下は U 字に縫う。 */
        data object Pocket : Shape
    }

    val margin = context.dp(5f)
    private val inset = context.dp(5f)
    private val felt = Fabric.paint(context, Cloth.FELT)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(7f), 0f, context.dp(3f), 0xC0000000.toInt())
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = 0x0DFFFFFF
    }
    private val stitch = context.stitch()
    private val rivet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        strokeWidth = context.dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val rect = RectF()
    private val path = Path()

    /** 布の内側（中身を置ける範囲）の余白。 */
    val contentPadding: Float get() = margin + inset + context.dp(8f)

    private val measure = android.graphics.PathMeasure()
    private val partial = Path()

    /** 縁をオレンジの糸で縫うか（通知の印）。 */
    var alert = false

    private val alertStitch = Paint(stitch).apply { color = Thread.ALERT }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        rect.set(margin, margin, w - margin, h - margin)
        if (rect.width() <= 0 || rect.height() <= 0) return
        // 縫い上がりの途中は、布をうっすら出し、縁のステッチを端から少しずつ縫う
        val p = Sewing.progress
        val clothAlpha = if (p >= 1f) 255 else (p * 1.6f).coerceAtMost(1f).times(255).toInt()
        shadow.alpha = clothAlpha; felt.alpha = clothAlpha; rim.alpha = clothAlpha / 20
        outline(path, rect)
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, felt)
        canvas.drawPath(path, rim)

        rect.inset(inset, inset)
        when (shape) {
            is Shape.Round -> {
                outline(path, rect)
                // 通知があるときは、縁をオレンジの糸で縫って目立たせる
                val line = path
                val paint = if (alert) alertStitch else stitch
                if (p >= 1f) {
                    canvas.drawPath(line, paint)
                } else {
                    measure.setPath(line, true)
                    partial.reset()
                    measure.getSegment(0f, measure.length * p, partial, true)
                    canvas.drawPath(partial, paint)
                }
            }
            Shape.Pocket -> {
                val gap = context.dp(5f)
                canvas.drawLine(rect.left, rect.top, rect.right, rect.top, stitch)
                canvas.drawLine(rect.left, rect.top + gap, rect.right, rect.top + gap, stitch)
                val r = context.dp(28f).coerceAtMost(rect.height() / 2)
                path.reset()
                path.moveTo(rect.left, rect.top + gap * 2)
                path.lineTo(rect.left, rect.bottom - r)
                path.quadTo(rect.left, rect.bottom, rect.left + r, rect.bottom)
                path.lineTo(rect.right - r, rect.bottom)
                path.quadTo(rect.right, rect.bottom, rect.right, rect.bottom - r)
                path.lineTo(rect.right, rect.top + gap * 2)
                canvas.drawPath(path, stitch)
                // ポケットの口を留める、短いかんぬき止め
                val bx = context.dp(14f)
                canvas.drawLine(rect.left + bx, rect.top - gap * 0.4f, rect.left + bx, rect.top + gap * 1.4f, rivet)
                canvas.drawLine(rect.right - bx, rect.top - gap * 0.4f, rect.right - bx, rect.top + gap * 1.4f, rivet)
            }
        }
    }

    private fun outline(path: Path, r: RectF) {
        path.reset()
        when (shape) {
            is Shape.Round -> {
                val radius = context.dp(shape.radiusDp).coerceAtMost(r.height() / 2).coerceAtMost(r.width() / 2)
                path.addRoundRect(r, radius, radius, Path.Direction.CW)
            }
            Shape.Pocket -> {
                val top = context.dp(10f)
                val bottom = context.dp(34f).coerceAtMost(r.height() / 2)
                path.addRoundRect(r, floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), Path.Direction.CW)
            }
        }
    }
}

/** 中身を自分で描くワッペン。 */
abstract class PatchView(context: Context, shape: PatchPainter.Shape = PatchPainter.Shape.Round(18f)) : View(context), SewAware {
    protected val patch = PatchPainter(context, shape)

    final override fun onDraw(canvas: Canvas) {
        patch.draw(canvas, width, height)
        val p = patch.contentPadding
        val alpha = Sewing.contentAlpha()
        val save = if (alpha < 255) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha) else -1
        drawContent(canvas, RectF(p, p, width - p, height - p))
        if (save >= 0) canvas.restoreToCount(save)
    }

    abstract fun drawContent(canvas: Canvas, area: RectF)
}

/** ほかの部品（検索欄やアイコン）を中に入れるワッペン。 */
@SuppressLint("ViewConstructor")
open class PatchLayout(context: Context, shape: PatchPainter.Shape = PatchPainter.Shape.Round(18f)) : FrameLayout(context) {
    protected val patch = PatchPainter(context, shape)

    init {
        setWillNotDraw(false)
        val p = patch.contentPadding.toInt()
        setPadding(p, p, p, p)
    }

    override fun onDraw(canvas: Canvas) = patch.draw(canvas, width, height)
}
