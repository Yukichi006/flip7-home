package io.github.yukichi006.fliphome

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ロック画面の壁紙を、ホーム画面と同じ「黒い布地に白いステッチ」で描いて設定する。
 * Android ではロック画面そのものは作り替えられないので、壁紙として描く（Galaxy の時計やショートカットは、その上に重なる）。
 *
 * 上の 4 割ほどは Galaxy の時計のために空けておき、下の方に、刺繍の月のカレンダーを縫い付けたワッペンを置く。
 * 日付が変わったら描き直す（[refreshIfNeeded]）。
 */
object LockArt {
    private const val PREFS = "lock"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    /** ロック画面の壁紙をステッチにする（すぐに描いて設定する）。 */
    fun enable(context: Context, done: (Boolean) -> Unit) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
        apply(context, done)
    }

    /** ロック画面の壁紙を元に戻す（ホーム画面と同じ壁紙に戻る）。 */
    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).remove("date").apply()
        runCatching { WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK) }
    }

    /** 使っていて、まだ今日の分を描いていなければ描き直す。 */
    fun refreshIfNeeded(context: Context) {
        if (!enabled(context)) return
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("date", null) == today()) return
        apply(context) {}
    }

    private fun apply(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        // 布地やカレンダーは画面の部品と同じ描き方なので、描くのはメインスレッド、壁紙の設定は裏のスレッドで行う
        val bitmap = runCatching { render(app) }.getOrNull() ?: return done(false)
        ioThread.execute {
            val ok = runCatching {
                WallpaperManager.getInstance(app).setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }.isSuccess
            if (ok) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("date", today()).apply()
            mainHandler.post { done(ok) }
        }
    }

    /** 画面の大きさの壁紙を描く。 */
    fun render(context: Context): Bitmap {
        // 画面いっぱいの大きさ（取れなければ Flip7 のメイン画面の大きさ）
        val bounds = context.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.bounds
        val w = bounds?.width()?.takeIf { it > 0 } ?: 1080
        val h = bounds?.height()?.takeIf { it > 0 } ?: 2520
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Fabric.paint(context, Cloth.BASE))
        // 画面の縁のステッチ
        val inset = context.dp(5f)
        val r = context.dp(26f)
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, context.stitch(1.4f))

        // 月の名前（例：11月）と、カレンダーのワッペン
        val side = context.dp(22f)
        val patchTop = h * 0.5f
        val patchBottom = h * 0.83f
        val title = context.threadText(26f, bold = true).apply { letterSpacing = 0.15f }
        val now = Calendar.getInstance()
        val month = SimpleDateFormat("M月", Locale.JAPAN).format(now.time)
        val year = SimpleDateFormat("yyyy", Locale.US).format(now.time)
        canvas.drawText(month, side + context.dp(6f), patchTop - context.dp(14f), title)
        val small = context.threadText(13f, color = Thread.DIM).apply { textAlign = Paint.Align.RIGHT; letterSpacing = 0.2f }
        canvas.drawText(year, w - side - context.dp(6f), patchTop - context.dp(16f), small)

        val pw = (w - side * 2).toInt()
        val ph = (patchBottom - patchTop).toInt()
        canvas.save()
        canvas.translate(side, patchTop)
        PatchPainter(context, PatchPainter.Shape.Round(28f)).draw(canvas, pw, ph)
        // カレンダーは、ホーム画面の「月のカレンダー」の部品をそのまま描く
        val cal = DotCalendarView(context, mondayFirst = false, hasSwipe = { false }, onSwipe = {})
        val pad = context.dpi(18f)
        cal.measure(View.MeasureSpec.makeMeasureSpec(pw - pad * 2, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph - pad * 2, View.MeasureSpec.EXACTLY))
        cal.layout(0, 0, pw - pad * 2, ph - pad * 2)
        canvas.translate(pad.toFloat(), pad.toFloat())
        cal.draw(canvas)
        canvas.restore()

        // 下の飾り：二本の縫い目
        val y = patchBottom + context.dp(28f)
        canvas.drawDoubleSeam(side + context.dp(30f), w - side - context.dp(30f), y, context.dp(5f), context.stitch(1.0f, Thread.DIM))
        return bitmap
    }
}
