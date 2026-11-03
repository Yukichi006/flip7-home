package io.github.yukichi006.fliphome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import java.util.Calendar

/**
 * ロック画面用の「動く壁紙」。黒い布地に、画面の縁のステッチ、刺繍のアナログ時計（本物の時刻で針が進む）、
 * 刺繍の月のカレンダー（今日の印と土日祝のオレンジは、日が変わると自動で変わる）を描く。
 *
 * 上の 3 割ほどは、Galaxy が重ねて出す時計と日付のために空けておく（Galaxy の時計は小さい形にして上に寄せてもらう）。
 * 画面が見えている間だけ 1 秒ごとに描き直し、見えていないときは何もしない（電池を使わないように）。
 * 布地・縁・カレンダーは日が変わるまで同じ絵を使い回し、毎秒描き直すのは時計だけ。
 */
class StitchWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = StitchEngine()

    private inner class StitchEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0
        private val clock = AnalogClockView(this@StitchWallpaper)
        /** 布地・縁・カレンダーを描いた絵と、それを描いた日（日が変わったら描き直す）。 */
        private var background: Bitmap? = null
        private var backgroundDay = -1

        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // 糸の太さは、ホーム画面で選んだものにそろえる
            StitchStyle.scale = LayoutStore(this@StitchWallpaper).thread
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(tick)
            if (visible) tick.run()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            background = null
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick)
            background?.recycle()
            super.onDestroy()
        }

        private fun draw() {
            if (width <= 0 || height <= 0) return
            val canvas = runCatching { surfaceHolder.lockCanvas() }.getOrNull() ?: return
            try {
                val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                val bg = background?.takeIf { backgroundDay == day } ?: drawBackground().also {
                    background?.recycle()
                    background = it
                    backgroundDay = day
                }
                canvas.drawBitmap(bg, 0f, 0f, null)
                // 刺繍のアナログ時計（Galaxy の時計の下、画面の真ん中より少し上）
                val size = minOf(width * 0.66f, height * 0.27f).toInt()
                if (clock.width != size) {
                    clock.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                    clock.layout(0, 0, size, size)
                }
                canvas.save()
                canvas.translate((width - size) / 2f, height * CLOCK_TOP)
                clock.draw(canvas)
                canvas.restore()
            } finally {
                runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
        }

        /** 日が変わるまで変わらない部分：布地、縁のステッチ、カレンダーのワッペン。 */
        private fun drawBackground(): Bitmap {
            val context = this@StitchWallpaper
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawPaint(Fabric.paint(context, Cloth.BASE))
            val inset = context.dp(5f)
            val r = context.dp(26f)
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, context.stitch(1.4f))
            // カレンダー：今日の印も付ける（毎日描き直すので）
            val side = context.dp(22f)
            val top = height * CAL_TOP
            val bottom = height * CAL_BOTTOM
            val pw = (width - side * 2).toInt()
            val ph = (bottom - top).toInt()
            canvas.save()
            canvas.translate(side, top)
            PatchPainter(context, PatchPainter.Shape.Round(28f)).draw(canvas, pw, ph)
            val cal = DotCalendarView(context, mondayFirst = false, hasSwipe = { false }, onSwipe = {})
            val pad = context.dpi(18f)
            cal.measure(View.MeasureSpec.makeMeasureSpec(pw - pad * 2, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph - pad * 2, View.MeasureSpec.EXACTLY))
            cal.layout(0, 0, pw - pad * 2, ph - pad * 2)
            canvas.translate(pad.toFloat(), pad.toFloat())
            cal.draw(canvas)
            canvas.restore()
            return bitmap
        }
    }

    companion object {
        // 画面の高さに対する位置（上の 3 割は Galaxy の時計、下の 1 割はカメラなどのボタンのために空ける）
        const val CLOCK_TOP = 0.30f
        const val CAL_TOP = 0.61f
        const val CAL_BOTTOM = 0.86f
    }
}
