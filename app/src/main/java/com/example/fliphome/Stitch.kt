package com.example.fliphome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.roundToInt
import kotlin.random.Random

/** 黒い布地と白い糸の配色。 */
object Thread {
    /** 糸の色（真っ白ではなく、少し生成りに寄せる）。 */
    const val COLOR = 0xFFEFEBE2.toInt()
    const val DIM = 0x8CEFEBE2.toInt()
    const val FAINT = 0x30EFEBE2.toInt()

    /** 糸の下に落ちる影。糸が布から浮いて見えるようにする。 */
    const val SHADOW = 0xF2000000.toInt()
}

object Cloth {
    /** 画面全体の布。 */
    const val BASE = 0xFF0C0C0D.toInt()

    /** 縫い付けたワッペンの布（少しだけ明るいフェルト）。 */
    const val FELT = 0xFF171718.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).roundToInt()
fun Context.sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

/**
 * ステッチ（ミシンの縫い目）の線を描く筆。点線を丸い端で描き、下に濃い影を落とす。
 * [width]・[dash]・[gap] はピクセル。
 */
fun stitchPaint(width: Float, dash: Float, gap: Float, color: Int = Thread.COLOR): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        pathEffect = DashPathEffect(floatArrayOf(dash, gap), 0f)
        setShadowLayer(width * 0.35f, 0f, width * 0.55f, Thread.SHADOW)
    }

/** 画面の大きさに合わせた、標準のステッチの筆。 */
fun Context.stitch(widthDp: Float = 1.3f, color: Int = Thread.COLOR): Paint =
    stitchPaint(dp(widthDp), dp(widthDp * 3.1f), dp(widthDp * 2.2f), color)

/** 布に刺繍した文字のような、糸の色の文字の筆。 */
fun Context.threadText(sizeSp: Float, bold: Boolean = false, color: Int = Thread.COLOR): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(sizeSp)
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setShadowLayer(dp(0.6f), 0f, dp(1f), Thread.SHADOW)
    }

/**
 * 布地の模様。綾織りの斜めの目と、繊維のざらつきを小さなタイルに描き、敷き詰めて使う。
 * 一度作ったタイルは使い回す。
 */
object Fabric {
    private val tiles = mutableMapOf<Int, Bitmap>()

    fun paint(context: Context, base: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(tile(context, base), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

    private fun tile(context: Context, base: Int): Bitmap = tiles.getOrPut(base) {
        // 織り目の間隔。継ぎ目が出ないよう、タイルの一辺はその倍数にする
        val period = context.dpi(2.6f).coerceAtLeast(3)
        val size = period * 40
        val random = Random(base)
        val px = IntArray(size * size)
        val r0 = Color.red(base)
        val g0 = Color.green(base)
        val b0 = Color.blue(base)
        // 横方向の糸のむら（行ごとの明るさのゆらぎ）
        val rowShade = IntArray(size) { random.nextInt(-3, 4) }
        for (y in 0 until size) {
            for (x in 0 until size) {
                var d = rowShade[y] + random.nextInt(-6, 7)
                // 右上がりの明るい目と、右下がりの暗い目で綾織りに見せる
                if ((x + y) % period < period * 0.4f) d += 7
                if ((x - y + size) % period < period * 0.3f) d -= 6
                px[y * size + x] = Color.rgb(
                    (r0 + d).coerceIn(0, 255), (g0 + d).coerceIn(0, 255), (b0 + d).coerceIn(0, 255),
                )
            }
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/** 角丸の四角に沿ってステッチを描く。 */
fun Canvas.drawStitchedRoundRect(rect: RectF, radius: Float, paint: Paint) {
    val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
    drawPath(path, paint)
}

/** 縫い目の区切り線（二本の細いステッチ）を [left]〜[right] に、[y] を中心に描く。 */
fun Canvas.drawDoubleSeam(left: Float, right: Float, y: Float, gap: Float, paint: Paint) {
    drawLine(left, y - gap / 2, right, y - gap / 2, paint)
    drawLine(left, y + gap / 2, right, y + gap / 2, paint)
}
