package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * ほかのアプリのホーム画面用ウィジェット（Suica の残高、カレンダーなど）を置くための窓口。
 * 新しい権限は使わない（ウィジェットを置くたびに、Android が「このアプリにウィジェットを許可するか」を確認する）。
 */
object AppWidgets {
    /** FlipHome の中でウィジェットの置き場所を区別する番号（変えないこと）。 */
    private const val HOST_ID = 8001

    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    /** ウィジェットの名前（「アプリ名：ウィジェット名」）。見つからなければ null。 */
    fun label(context: Context, id: Int): String? {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return null
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)) }.getOrNull()
        return listOfNotNull(app, info.loadLabel(pm)).joinToString("：")
    }
}

/**
 * ウィジェットを包む枠。タッチはそのままウィジェットに渡すが、長押しされたら [onLongPress]（編集モード）にする。
 * 大きさが変わったら、ウィジェットに今の大きさを伝える（中身をその大きさに合わせてもらう）。
 */
@SuppressLint("ViewConstructor")
class WidgetCell(
    context: Context,
    /** ウィジェットの本来の最小の大きさ（px）。マスがこれより小さいときは、この大きさで描いてから縮めて収める。 */
    private val minW: Int = 0,
    private val minH: Int = 0,
    private val onLongPress: () -> Unit,
) : FrameLayout(context) {
    /** ほかの部品と同じくらい、マスの内側に空ける余白。 */
    private val inset = (resources.displayMetrics.density * 4f).toInt()

    /** 中身が実際に必要とする大きさ（測ったもの）。申告の最小の大きさより大きな中身を描くウィジェット（Suica など）があるため。 */
    private var needW = 0
    private var needH = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val child = getChildAt(0) ?: return
        val aw = (w - inset * 2).coerceAtLeast(1)
        val ah = (h - inset * 2).coerceAtLeast(1)
        // 中身を大きさの制限なしで測り、マスに入りきるか確かめる（極端に大きいもの・一覧などは、マスの 2 倍までにとどめる）
        val content = (child as? android.view.ViewGroup)?.getChildAt(0)
        if (content != null) {
            content.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            needW = minOf(content.measuredWidth, aw * 2)
            needH = minOf(content.measuredHeight, ah * 2)
        } else {
            needW = 0; needH = 0
        }
        val (cw, ch) = contentSize(w, h)
        child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    /**
     * ウィジェットを描く大きさ：余白を除いたマスの大きさ。ただし、申告の最小の大きさや、中身が必要とする大きさより
     * 小さくはしない（そのときは、あとで縦横の比を保って縮める）。
     */
    private fun contentSize(w: Int, h: Int): Pair<Int, Int> {
        val aw = (w - inset * 2).coerceAtLeast(1)
        val ah = (h - inset * 2).coerceAtLeast(1)
        return maxOf(aw, minW, needW) to maxOf(ah, minH, needH)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress()
    }

    /** 長押しの待ち時間を取り消す（ホーム画面のスワイプが始まったとき）。 */
    fun cancelPendingLongPress() = removeCallbacks(longPress)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; longFired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        // 長押しのあとは、ウィジェットにタップを渡さない
        return longFired
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = longFired

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val w = right - left
        val h = bottom - top
        val (cw, ch) = contentSize(w, h)
        child.layout(0, 0, cw, ch)
        // マスより大きく描いたときは、縦横の比を保ったまま縮めて、マスの真ん中に収める
        val scale = minOf((w - inset * 2).toFloat() / cw, (h - inset * 2).toFloat() / ch, 1f)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = (w - cw * scale) / 2f
        child.translationY = (h - ch * scale) / 2f
        roundCorners()
    }

    /**
     * ウィジェットの角を丸める。Android 12 からのウィジェットは、角を自分では丸めず、土台に印（android:id/background）を付けて、
     * 丸めるのをホームアプリに任せている（Galaxy のホーム画面も丸めている）。土台（[findBackground]）を、Android が決めている丸みで切り取る。
     */
    private fun roundCorners() {
        val host = getChildAt(0) as? android.appwidget.AppWidgetHostView ?: return
        val bg = findBackground(host) ?: return
        // 縮めて収めているときは、縮めたあとでほかのウィジェットと同じ丸みになるように
        val radius = cornerRadius() / host.scaleX.coerceAtLeast(0.1f)
        if (bg.clipToOutline && (bg.outlineProvider as? Rounded)?.radius == radius) return
        bg.outlineProvider = Rounded(radius)
        bg.clipToOutline = true
    }

    /**
     * ウィジェットの土台を探す（ふつうの Android のホーム画面と同じ考え方）。
     * 1. 土台の印（android:id/background）が付いた部品
     * 2. 中に部品が 1 つずつしかない入れ子なら、背景を持つ最初の部品
     * 3. 背景を持つ部品のうち、いちばん大きいもの（ウィジェットの半分以上を占めるときだけ）
     * 見つからなければ null（丸めない）。
     */
    private fun findBackground(host: android.view.ViewGroup): android.view.View? {
        host.findViewById<android.view.View>(android.R.id.background)?.let { return it }
        var v: android.view.View = host
        while (v is android.view.ViewGroup && v.childCount == 1) {
            v = v.getChildAt(0)
            if (v.background != null) return v
        }
        var best: android.view.View? = null
        var bestArea = 0
        fun walk(x: android.view.View) {
            if (x !== host && x.background != null && x.visibility == VISIBLE) {
                val area = x.width * x.height
                if (area > bestArea) { best = x; bestArea = area }
            }
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(host)
        return best?.takeIf { bestArea * 2 >= host.width * host.height }
    }

    private fun cornerRadius(): Float {
        val sys = android.content.res.Resources.getSystem()
        val id = sys.getIdentifier("system_app_widget_background_radius", "dimen", "android")
        return if (id != 0) sys.getDimension(id) else resources.displayMetrics.density * 24f
    }

    /** 角を丸めた形。 */
    private class Rounded(val radius: Float) : android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val view = getChildAt(0) as? android.appwidget.AppWidgetHostView ?: return
        val d = resources.displayMetrics.density
        // ウィジェットには、縮める前の（描いている）大きさを伝える
        val (cw, ch) = contentSize(w, h)
        runCatching { view.updateAppWidgetSize(Bundle(), listOf(SizeF(cw / d, ch / d))) }
    }
}
