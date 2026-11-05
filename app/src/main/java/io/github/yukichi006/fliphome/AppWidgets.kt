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
class WidgetCell(context: Context, private val onLongPress: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress()
    }

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
        super.onLayout(changed, left, top, right, bottom)
        roundCorners()
    }

    /**
     * ウィジェットの角を丸める。Android 12 からのウィジェットは、角を自分では丸めず、土台に印（android:id/background）を付けて、
     * 丸めるのをホームアプリに任せている（Galaxy のホーム画面も丸めている）。印の付いた土台を、Android が決めている丸みで切り取る。
     * 印がなければ、そのままにする（自分で丸めた絵を描いているウィジェットなど）。
     */
    private fun roundCorners() {
        val host = getChildAt(0) as? android.appwidget.AppWidgetHostView ?: return
        val bg = host.findViewById<android.view.View>(android.R.id.background) ?: return
        val radius = cornerRadius()
        if (bg.clipToOutline && (bg.outlineProvider as? Rounded)?.radius == radius) return
        bg.outlineProvider = Rounded(radius)
        bg.clipToOutline = true
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
        runCatching { view.updateAppWidgetSize(Bundle(), listOf(SizeF(w / d, h / d))) }
    }
}
