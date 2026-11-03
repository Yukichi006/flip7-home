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

/**
 * 通知の件数を刺繍した布タグ（生成りの布に黒い糸の数字、10 件以上は「9+」）を描く。
 * ([x], [y]) は、タグを吊るす糸の付け根。[swing] だけ傾けてぶら下げる。
 */
fun drawCountTag(canvas: Canvas, context: Context, x: Float, y: Float, count: Int, swing: Float = 0f) {
    val text = if (count > 9) "9+" else "$count"
    val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.BASE
        textSize = context.sp(10f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    val w = (num.measureText(text) + context.dp(9f)).coerceAtLeast(context.dp(17f))
    val h = context.dp(15f)
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(2.5f), 0f, context.dp(1.5f), 0xC0000000.toInt())
    }
    val edge = stitchPaint(context.dp(0.8f), context.dp(2f), context.dp(1.6f), 0x66000000)
    val loop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Thread.COLOR
        strokeWidth = context.dp(1.1f)
    }
    canvas.save()
    canvas.translate(x, y)
    canvas.rotate(-14f + swing)
    // 吊るす糸の輪と、タグの本体
    canvas.drawLine(0f, 0f, -context.dp(2f), context.dp(4f), loop)
    val r = RectF(-w / 2 - context.dp(2f), context.dp(4f), w / 2 - context.dp(2f), context.dp(4f) + h)
    canvas.drawRoundRect(r, context.dp(3.5f), context.dp(3.5f), body)
    canvas.drawRoundRect(RectF(r).apply { inset(context.dp(2f), context.dp(2f)) }, context.dp(2.5f), context.dp(2.5f), edge)
    canvas.drawText(text, r.centerX(), r.centerY() + num.textSize * 0.36f, num)
    canvas.restore()
}

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
 * 裏地をめくり始める側（8 方向）。[sx], [sy] は始まる側（-1：左・上、0：真ん中、1：右・下）。
 * 角から始めるものは角を斜めにめくり、辺から始めるものは辺をまっすぐ折り返す。
 */
enum class PeelFrom(val key: String, val label: String, val sx: Int, val sy: Int) {
    TOP("t", "上から下", 0, -1),
    TOP_RIGHT("tr", "右上から左下", 1, -1),
    RIGHT("r", "右から左", 1, 0),
    BOTTOM_RIGHT("br", "右下から左上", 1, 1),
    BOTTOM("b", "下から上", 0, 1),
    BOTTOM_LEFT("bl", "左下から右上", -1, 1),
    LEFT("l", "左から右", -1, 0),
    TOP_LEFT("tl", "左上から右下", -1, -1),
    ;

    val corner get() = sx != 0 && sy != 0

    /** めくっていく向き（単位ベクトル）。 */
    val ux: Float get() = -sx / kotlin.math.hypot(sx.toFloat(), sy.toFloat())
    val uy: Float get() = -sy / kotlin.math.hypot(sx.toFloat(), sy.toFloat())

    companion object {
        val DEFAULT = TOP
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * アプリのパッド。[framed] なら 1 マスのワッペン（フェルトの布にステッチの縁取り）に載せ、
 * 刺繍アイコンと小さなアプリ名を描く。通知が来ていれば右上に糸の玉を付ける。
 *
 * - タップ：アプリを開く
 * - [peel] の側からつまんでめくる：裏地に縫い付けた 2 つ目のアプリ（[second]）が見え、めくりきって離すと開く。
 *   2 つ目のアプリがあるパッドは、めくる側が少しだけめくれている（裏地がのぞく）
 * - 長押し：[onLongPress]（編集モードに入る）
 */
@SuppressLint("ViewConstructor")
class PadView(
    context: Context,
    val app: AppEntry,
    private val repo: AppRepository,
    private val second: AppEntry? = null,
    private val framed: Boolean = true,
    private val peel: PeelFrom = PeelFrom.DEFAULT,
    private val onLongPress: () -> Unit,
) : View(context), SewAware {
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(16f))
    private val label = context.threadText(9.5f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    private val lining = Fabric.paint(context, Cloth.LINING)
    private val back = Fabric.paint(context, Cloth.BACK)
    private val flapShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.BACK
        setShadowLayer(context.dp(6f), 0f, context.dp(2f), 0xD0000000.toInt())
    }
    private val foldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        strokeWidth = context.dp(1f)
    }
    private val backStitch = context.stitch(1.0f, Thread.DIM)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var inZone = false
    private var peeling = false
    private var armed = false

    /** めくり始めた所（角、または辺の上の点）と、めくっている指の位置。null なら少しだけめくれた待機の形。 */
    private var anchor: Pair<Float, Float>? = null
    private var finger: Pair<Float, Float>? = null
    private var settle: android.animation.ValueAnimator? = null

    /** 通知の件数（増えたらタグを揺らす）と、タグの揺れの角度。 */
    private var lastCount = Badges.count(app.component.packageName)
    private var swing = 0f
    private val badgeListener: () -> Unit = {
        val now = Badges.count(app.component.packageName)
        if (now > lastCount) swingTag()
        lastCount = now
        invalidate()
    }

    private fun swingTag() {
        android.animation.ValueAnimator.ofFloat(0f, 22f, -14f, 8f, -4f, 0f).apply {
            duration = 900
            addUpdateListener { swing = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    init {
        setOnClickListener { repo.launch(app, this) }
        setOnLongClickListener { onLongPress(); true }
        contentDescription = repo.label(app)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Badges.addListener(badgeListener)
    }

    override fun onDetachedFromWindow() {
        Badges.removeListener(badgeListener)
        settle?.cancel()
        super.onDetachedFromWindow()
    }

    private fun patchRect() = RectF(patch.margin, patch.margin, width - patch.margin, height - patch.margin)
    private fun canPeel() = second != null && framed

    /** めくり始められる範囲（めくる側の半分。辺の場合は辺に近い帯）。 */
    private fun zone(x: Float, y: Float): Boolean {
        val okX = when (peel.sx) { 1 -> x > width * 0.5f; -1 -> x < width * 0.5f; else -> true }
        val okY = when (peel.sy) { 1 -> y > height * 0.5f; -1 -> y < height * 0.5f; else -> true }
        return okX && okY
    }

    /** めくり始める所：角なら角、辺なら指の真横の辺の上の点。 */
    private fun startPoint(r: RectF, x: Float, y: Float): Pair<Float, Float> {
        val cx = when (peel.sx) { 1 -> r.right; -1 -> r.left; else -> x.coerceIn(r.left, r.right) }
        val cy = when (peel.sy) { 1 -> r.bottom; -1 -> r.top; else -> y.coerceIn(r.top, r.bottom) }
        return cx to cy
    }

    /** 指の位置を、めくった先の点にする（辺から始めるときは、めくる向きの分だけを使う）。 */
    private fun peelPoint(r: RectF, c: Pair<Float, Float>, x: Float, y: Float): Pair<Float, Float> {
        if (peel.corner) return x.coerceIn(r.left, r.right) to y.coerceIn(r.top, r.bottom)
        val t = ((x - c.first) * peel.ux + (y - c.second) * peel.uy).coerceIn(0f, extent(r))
        return (c.first + peel.ux * t) to (c.second + peel.uy * t)
    }

    private fun extent(r: RectF) = if (peel.corner) kotlin.math.hypot(r.width(), r.height()) else if (peel.sx != 0) r.width() else r.height()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                peeling = false
                armed = false
                settle?.cancel()
                inZone = canPeel() && zone(event.x, event.y)
                if (inZone) parent?.requestDisallowInterceptTouchEvent(true)
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val along = dx * peel.ux + dy * peel.uy
                val across = kotlin.math.abs(-dx * peel.uy + dy * peel.ux)
                if (inZone && !peeling && along > slop && along > across * 0.6f) {
                    peeling = true
                    anchor = startPoint(patchRect(), downX, downY)
                    super.onTouchEvent(MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                } else if (inZone && !peeling && (along < -slop || across > slop * 2)) {
                    // めくる向きではない：ホーム画面のスワイプ（ページ・一覧・通知）に任せる
                    inZone = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                if (peeling) {
                    val r = patchRect()
                    finger = peelPoint(r, anchor!!, event.x, event.y)
                    val nowArmed = peelAmount() > 0.5f
                    if (nowArmed != armed) {
                        armed = nowArmed
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(220).start()
                if (peeling) {
                    if (armed && event.actionMasked == MotionEvent.ACTION_UP) second?.let { repo.launch(it, this) }
                    peeling = false
                    armed = false
                    settleBack()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** めくった量（0〜1。めくれる長さに対する、始めた所から指までの長さ）。 */
    private fun peelAmount(): Float {
        val f = finger ?: return 0f
        val c = anchor ?: return 0f
        return (kotlin.math.hypot(c.first - f.first, c.second - f.second) / extent(patchRect()))
    }

    /** 離したら、布がふわっと元へ戻る。 */
    private fun settleBack() {
        val f = finger ?: return
        val c = anchor ?: return
        val rest = (c.first + peel.ux * restDepth()) to (c.second + peel.uy * restDepth())
        settle = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                val t = it.animatedValue as Float
                finger = (f.first + (rest.first - f.first) * t) to (f.second + (rest.second - f.second) * t)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finger = null; anchor = null; invalidate() }
            })
            start()
        }
    }

    /** 待機中に少しだけめくれている深さ（角は犬の耳、辺は細い折り返し）。 */
    private fun restDepth() = if (peel.corner) dp(18f) else dp(7f)

    override fun onDraw(canvas: Canvas) {
        val alpha = Sewing.contentAlpha()
        patch.alert = Badges.count(app.component.packageName) > 0
        if (!canPeel()) {
            if (framed) patch.draw(canvas, width, height)
            withAlpha(canvas, alpha) { drawFace(canvas, app, withBadge = true) }
            return
        }
        val r = patchRect()
        val c = anchor ?: startPoint(r, r.centerX(), r.centerY())
        val f = finger ?: ((c.first + peel.ux * restDepth()) to (c.second + peel.uy * restDepth()))
        var nx = c.first - f.first
        var ny = c.second - f.second
        val len = kotlin.math.hypot(nx, ny)
        if (len < 1f) {
            patch.draw(canvas, width, height)
            withAlpha(canvas, alpha) { drawFace(canvas, app, withBadge = true) }
            return
        }
        nx /= len; ny /= len
        val mx = (c.first + f.first) / 2f
        val my = (c.second + f.second) / 2f
        val shape = Path().apply { addRoundRect(r, dp(16f), dp(16f), Path.Direction.CW) }
        val cornerSide = halfPlane(mx, my, nx, ny, +1f)
        val frontSide = halfPlane(mx, my, nx, ny, -1f)
        // 1) めくれて見えた所：裏地の布と、2 つ目のアプリ
        val revealed = Path(shape).apply { op(cornerSide, Path.Op.INTERSECT) }
        canvas.save()
        canvas.clipPath(revealed)
        canvas.drawPath(shape, lining)
        withAlpha(canvas, alpha) { drawFace(canvas, second!!, withBadge = false) }
        canvas.restore()
        // 2) 表の布（めくれていない所）
        canvas.save()
        canvas.clipPath(frontSide)
        patch.draw(canvas, width, height)
        withAlpha(canvas, alpha) { drawFace(canvas, app, withBadge = true) }
        canvas.restore()
        // 3) めくれた布の裏側（折り目で裏返した形）
        val angle = Math.toDegrees(kotlin.math.atan2(nx.toDouble(), -ny.toDouble())).toFloat()
        val reflect = android.graphics.Matrix().apply {
            postTranslate(-mx, -my)
            postRotate(-angle)
            postScale(1f, -1f)
            postRotate(angle)
            postTranslate(mx, my)
        }
        val flap = Path(shape).apply { op(cornerSide, Path.Op.INTERSECT); transform(reflect) }
        canvas.drawPath(flap, flapShadow)
        canvas.drawPath(flap, back)
        canvas.save()
        canvas.clipPath(flap)
        canvas.concat(reflect)
        val inset = RectF(r).apply { inset(dp(5f), dp(5f)) }
        canvas.drawRoundRect(inset, dp(11f), dp(11f), backStitch)
        canvas.restore()
        // 折り目の光
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawLine(mx - ny * 1000, my + nx * 1000, mx + ny * 1000, my - nx * 1000, foldLine)
        canvas.restore()
    }

    /** 点 ([mx], [my]) を通り、向き ([nx], [ny]) に垂直な線で分けた片側（[sign] が + なら角の側）。 */
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

    private inline fun withAlpha(canvas: Canvas, alpha: Int, block: () -> Unit) {
        if (alpha >= 255) { block(); return }
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        block()
        canvas.restoreToCount(save)
    }

    /** アイコンとアプリ名（と通知の印）を描く。 */
    private fun drawFace(canvas: Canvas, shown: AppEntry, withBadge: Boolean) {
        val inner = if (framed) patch.margin + dp(9f) else dp(2f)
        val labelH = label.textSize * 1.3f
        val iconPx = min(width - inner * 2, height - inner * 2 - labelH).coerceAtMost(dp(46f)).coerceAtLeast(dp(16f)).toInt()
        val total = iconPx + dp(3f) + labelH
        val top = (height - total) / 2f
        canvas.drawBitmap(GenreIcons.bitmap(repo.genre(shown), iconPx), (width - iconPx) / 2f, top, null)
        val text = android.text.TextUtils.ellipsize(repo.label(shown), label, width - inner * 2, android.text.TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, width / 2f, top + iconPx + dp(3f) + label.textSize, label)
        val count = Badges.count(shown.component.packageName)
        if (withBadge && count > 0) {
            // 右上の角に、件数を刺繍したタグを吊るす
            val m = if (framed) patch.margin + dp(10f) else dp(12f)
            drawCountTag(canvas, context, width - m, if (framed) patch.margin + dp(2f) else dp(2f), count, swing)
        }
    }
}

/**
 * フォルダ（巾着袋）。口を紐で絞ったフェルトの袋と、その下にフォルダの名前。
 * タップで [onOpen]（紐がゆるんで口が開き、中のアプリが袋から出てくる）、長押しで [onLongPress]。
 * 中のアプリのどれかに通知が来ていれば、糸の玉を付ける。
 */
@SuppressLint("ViewConstructor")
class FolderPouch(
    context: Context,
    private val name: String,
    private val packages: List<String>,
    private val onOpen: (View) -> Unit,
    private val onLongPress: () -> Unit,
    /** 袋に刺繍するジャンルのアイコン（アプリ一覧のジャンル別の巾着）。 */
    private val genre: Genre? = null,
) : View(context), SewAware {
    private val felt = Fabric.paint(context, Cloth.FELT)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(6f), 0f, context.dp(3f), 0xC0000000.toInt())
    }
    private val seam = context.stitch(1.0f)
    private val gather = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55000000
        strokeWidth = context.dp(1.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val cord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Thread.COLOR
        strokeWidth = context.dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(context.dp(0.8f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val bead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(1f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val label = context.threadText(9.5f).apply { textAlign = Paint.Align.CENTER }
    private val badgeListener: () -> Unit = { invalidate() }

    /** 口の開き具合（0：絞ってある〜1：開いている）。 */
    var openness = 0f
        set(value) { field = value; invalidate() }

    /** ページを移ったときに、袋が少し揺れる角度。 */
    var sway = 0f
        set(value) { field = value; invalidate() }

    init {
        setOnClickListener { onOpen(this) }
        setOnLongClickListener { onLongPress(); true }
        contentDescription = "フォルダ $name"
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Badges.addListener(badgeListener) }
    override fun onDetachedFromWindow() { Badges.removeListener(badgeListener); super.onDetachedFromWindow() }

    fun animateOpen(open: Boolean) {
        android.animation.ValueAnimator.ofFloat(openness, if (open) 1f else 0f).apply {
            duration = if (open) 220 else 180
            addUpdateListener { openness = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = label.textSize * 1.5f
        val s = min(width.toFloat(), height - labelH) - dp(8f)
        val cx = width / 2f
        val top = (height - labelH - s) / 2f + dp(2f)
        canvas.save()
        canvas.rotate(sway, cx, top)
        val bodyW = s * 0.86f
        val neckY = top + s * 0.3f
        val neckW = s * (0.2f + 0.26f * openness)
        val bottom = top + s
        // 袋の体：首は細く絞り、下へ向かってふっくら丸く広がる
        val body = Path().apply {
            moveTo(cx - neckW / 2, neckY)
            cubicTo(cx - neckW, neckY + s * 0.08f, cx - bodyW / 2, neckY + s * 0.2f, cx - bodyW / 2, neckY + s * 0.42f)
            cubicTo(cx - bodyW / 2, bottom - s * 0.02f, cx - bodyW * 0.3f, bottom, cx, bottom)
            cubicTo(cx + bodyW * 0.3f, bottom, cx + bodyW / 2, bottom - s * 0.02f, cx + bodyW / 2, neckY + s * 0.42f)
            cubicTo(cx + bodyW / 2, neckY + s * 0.2f, cx + neckW, neckY + s * 0.08f, cx + neckW / 2, neckY)
            close()
        }
        canvas.drawPath(body, shadow)
        canvas.drawPath(body, felt)
        // 首から下へ流れる、絞ったしわ
        for (i in -2..2) {
            if (i == 0) continue
            val x = cx + i * neckW * 0.22f
            canvas.drawPath(Path().apply {
                moveTo(x, neckY + dp(2f))
                quadTo(x + i * dp(4f), neckY + s * 0.12f, x + i * dp(7f), neckY + s * (0.24f - 0.14f * openness))
            }, gather)
        }
        // 縁のステッチ（体の少し内側）
        canvas.save()
        canvas.scale(0.82f, 0.84f, cx, neckY + (bottom - neckY) * 0.56f)
        canvas.drawPath(body, seam)
        canvas.restore()
        // ジャンルのアイコンを、袋のふくらみの真ん中に刺繍する
        genre?.let { g ->
            val px = (s * 0.3f).toInt().coerceAtLeast(dpi(12f))
            canvas.drawBitmap(GenreIcons.bitmap(g, px), cx - px / 2f, neckY + (bottom - neckY) * 0.56f - px / 2f, iconPaint)
        }
        // 口のフリル（3 つの小さな山）。開くと口の中（暗い所）が見える
        val fw = neckW * (1.7f + 0.3f * openness)
        val frillTop = neckY - s * (0.13f + 0.04f * openness)
        val frill = Path().apply {
            moveTo(cx - neckW / 2, neckY)
            lineTo(cx - fw / 2, frillTop + dp(2f))
            quadTo(cx - fw / 3, frillTop - dp(2f), cx - fw / 6, frillTop + dp(1.5f))
            quadTo(cx, frillTop - dp(2.5f), cx + fw / 6, frillTop + dp(1.5f))
            quadTo(cx + fw / 3, frillTop - dp(2f), cx + fw / 2, frillTop + dp(2f))
            lineTo(cx + neckW / 2, neckY)
            close()
        }
        canvas.drawPath(frill, shadow)
        canvas.drawPath(frill, felt)
        if (openness > 0.05f) {
            val mouthH = (neckY - frillTop) * 0.55f * openness
            canvas.drawOval(RectF(cx - fw * 0.36f, frillTop + dp(2f), cx + fw * 0.36f, frillTop + dp(2f) + mouthH), inside)
        }
        // 紐：首にひと巻きし、片側で蝶結びの輪と、先に小さな玉。開くとゆるんで輪が広がる
        canvas.drawLine(cx - neckW / 2 - dp(1.5f), neckY, cx + neckW / 2 + dp(1.5f), neckY, cord)
        val kx = cx + neckW / 2 + dp(1.5f)
        val loop = s * (0.07f + 0.05f * openness)
        canvas.drawOval(RectF(kx - dp(1f), neckY - loop, kx + loop * 1.4f, neckY + dp(1f)), cord)
        canvas.drawPath(Path().apply { moveTo(kx, neckY); quadTo(kx + dp(5f), neckY + s * 0.08f, kx + dp(2f), neckY + s * 0.16f) }, cord)
        canvas.drawPath(Path().apply { moveTo(kx, neckY); quadTo(kx - dp(2f), neckY + s * 0.09f, kx - dp(4f), neckY + s * 0.13f) }, cord)
        canvas.drawCircle(kx + dp(2f), neckY + s * 0.16f, dp(2.4f), bead)
        canvas.drawCircle(kx - dp(4f), neckY + s * 0.13f, dp(2.4f), bead)
        canvas.restore()
        val text = android.text.TextUtils.ellipsize(name, label, width - dp(8f), android.text.TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, cx, height - dp(6f), label)
        // 中のアプリの通知の件数を合わせて、紐の先にタグで吊るす
        val total = packages.sumOf { Badges.count(it) }
        if (total > 0) drawCountTag(canvas, context, cx - neckW / 2 - dp(2f), neckY + dp(1f), total, sway)
    }
}

/**
 * 巾着を押したときに、そのすぐ上（入らなければ下）に開く吹き出し。中のアプリを 4 列で並べ、タップで開く。
 * 開くとき、アプリは巾着の口から飛び出して並び、閉じるときは巾着の中へ戻る。
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
    private var shown = false

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
        // 巾着の上に置く。入らなければ下に置く
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val me = IntArray(2)
        getLocationInWindow(me)
        val ay = loc[1] - me[1]
        val bh = bubble.measuredHeight
        val y = (if (ay - bh > dpi(24f)) ay - bh else ay + anchor.height)
            .coerceAtMost(height - bh - dpi(24f)).coerceAtLeast(dpi(24f))
        bubble.layout(dpi(8f), y, width - dpi(8f), y + bh)
        if (!shown) {
            shown = true
            post { flyOut() }
        }
    }

    /** 巾着の口の位置（各アプリから見た相対位置）へ、アプリを集める・そこから並べる。 */
    private fun mouthOffset(child: View): Pair<Float, Float> {
        val a = IntArray(2); anchor.getLocationInWindow(a)
        val c = IntArray(2); child.getLocationInWindow(c)
        val mx = a[0] + anchor.width / 2f
        val my = a[1] + anchor.height * 0.3f
        return (mx - (c[0] + child.width / 2f)) to (my - (c[1] + child.height / 2f))
    }

    private fun flyOut() {
        (anchor as? FolderPouch)?.animateOpen(true)
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            val (dx, dy) = mouthOffset(c)
            c.translationX = dx; c.translationY = dy
            c.scaleX = 0.2f; c.scaleY = 0.2f; c.alpha = 0f
            c.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(60L + i * 35L).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    fun show(parent: ViewGroup) {
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bubble.alpha = 0f
        bubble.animate().alpha(1f).setDuration(160).start()
    }

    fun dismiss() {
        if (closing) return
        closing = true
        (anchor as? FolderPouch)?.animateOpen(false)
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            val (dx, dy) = mouthOffset(c)
            c.animate().translationX(dx).translationY(dy).scaleX(0.2f).scaleY(0.2f).alpha(0f).setStartDelay(0).setDuration(170).start()
        }
        bubble.animate().alpha(0f).setStartDelay(90).setDuration(120).withEndAction {
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

    /** 口のファスナー（null なら、ふつうの二重の縫い目）。右へ引ききると、アプリ一覧が開く。 */
    var zipper: ZipperTrack? = null
        set(value) {
            field = value
            patch.pocketZip = value != null
            val p = patch.contentPadding.toInt()
            // ファスナーの帯の分だけ、アプリを下げる
            val top = if (value != null) (patch.seamInset + value.band).toInt() else p + context.dpi(6f)
            setPadding(p, top, p, p)
            requestLayout()
            invalidate()
        }

    private fun placeZipper(z: ZipperTrack) {
        // かんぬき止め（両端から 14dp）の内側に張る。左は引き手のつまみの分を空ける
        val bar = patch.seamInset + context.dp(14f)
        z.place(bar + context.dp(6f) + z.pullReach, width - bar - context.dp(10f) - z.th * 1.6f, patch.seamInset + z.band / 2 - context.dp(2f))
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
        zipper?.let { z ->
            placeZipper(z)
            val save = if (editing) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 90) else -1
            z.draw(canvas)
            if (save >= 0) canvas.restoreToCount(save)
        }
        if (!editing) return
        val o = context.dp(BadgePainter.R_DP) * 0.3f
        for (i in 0 until LayoutStore.DOCK_SLOTS) {
            slotRect(i, rect)
            canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), chalk)
            if (cells[i] != null) badge.drawRemove(canvas, rect.right - o, rect.top + o)
        }
    }

    private var zipTouch = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editing) return true
        // ファスナーの帯か引き手に触れたら、ファスナーを引く（アプリには渡さない）
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val z = zipper
            zipTouch = z != null && run { placeZipper(z); z.hit(ev.x, ev.y) }
        }
        return zipTouch
    }

    private var downSlot = -1
    private var downRemove = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing && zipTouch) {
            zipper?.onTouch(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) zipTouch = false
            return true
        }
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

/**
 * 下段のポケットに置く「アプリ一覧」のボタン。ほかのアプリと同じ大きさで、9 つの小さな四角を縫ったアイコンと「アプリ一覧」の文字。
 * タップで [onOpen]、長押しで [onLongPress]。
 */
@SuppressLint("ViewConstructor")
class DrawerButton(context: Context, private val onOpen: () -> Unit, private val onLongPress: () -> Unit) : View(context), SewAware {
    private val label = context.threadText(9.5f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    private val rect = RectF()

    init {
        setOnClickListener { onOpen() }
        setOnLongClickListener { onLongPress(); true }
        contentDescription = "アプリ一覧"
    }

    override fun onDraw(canvas: Canvas) {
        // 大きさの決め方は、下段のアプリ（PadView の framed = false）とそろえる
        val inner = dp(2f)
        val labelH = label.textSize * 1.3f
        val iconPx = min(width - inner * 2, height - inner * 2 - labelH).coerceAtMost(dp(46f)).coerceAtLeast(dp(16f))
        val total = iconPx + dp(3f) + labelH
        val top = (height - total) / 2f
        val left = (width - iconPx) / 2f
        // 3×3 の小さな四角（48 の目盛りで、PadView のアイコンと同じ太さの糸）
        val u = iconPx / 48f
        val paint = stitchPaint(iconPx * 0.05f, iconPx * 0.105f, iconPx * 0.075f)
        for (r in 0 until 3) for (c in 0 until 3) {
            val x = left + (6f + c * 13.5f) * u
            val y = top + (6f + r * 13.5f) * u
            rect.set(x, y, x + 9f * u, y + 9f * u)
            canvas.drawRoundRect(rect, 2.2f * u, 2.2f * u, paint)
        }
        canvas.drawText("アプリ一覧", width / 2f, top + iconPx + dp(3f) + label.textSize, label)
    }
}
