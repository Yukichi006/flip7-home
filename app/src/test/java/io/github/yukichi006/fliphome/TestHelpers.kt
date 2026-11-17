package io.github.yukichi006.fliphome

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** テストで使う、ちょっとした道具。 */
object T {
    val app: Context get() = RuntimeEnvironment.getApplication()

    /** 保存してある配置をすべて消して、最初からにする（「片手の画面」からの移し替えは済んだことにする）。 */
    fun freshStore(): LayoutStore {
        app.getSharedPreferences("home", Context.MODE_PRIVATE).edit().clear().putBoolean("unified", true).commit()
        return LayoutStore(app)
    }

    /** 画面の処理（アニメーションや、あとで行う処理）を進める。 */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** [root] の中の部品を、入れ子の奥まですべて並べる。 */
    fun all(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    /** 説明（ボタンの文字）が [prefix] で始まる、見えている部品。 */
    fun byText(root: View, prefix: String): View? =
        all(root).lastOrNull { it.isShown && it.contentDescription?.toString()?.startsWith(prefix) == true }

    /** [view] の中の (x, y) を、指でタップする（画面全体から指を通すので、途中の部品の受け渡しも本物と同じになる）。 */
    fun tap(activity: Activity, view: View, x: Float, y: Float) {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, t + 30, action, loc[0] + x, loc[1] + y, 0)
            activity.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idle()
    }
}
