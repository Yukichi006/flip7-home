package io.github.yukichi006.fliphome

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ロック画面の壁紙を、ホーム画面と同じ「黒い布地に白いステッチ」で描く。
 * Android ではロック画面そのものは作り替えられないので、壁紙として描く（Galaxy の時計やショートカットは、その上に重なる）。
 *
 * - **動く壁紙**（[openPicker]、[LockWallpaper]）：FlipHome 自身が壁紙として動き、ロック画面が出るたびに自分で描く。
 *   画像を毎日差し替える前のやり方は、Galaxy が「ロック画面の編集」を開くまで古い絵を出し続けたのでやめた。
 *   動く壁紙は絵を Galaxy に渡さず、その場で描くので、日付が変わればそのまま今日の印が動く。
 * - **手動**（[saveAndOpen]）：動く壁紙を使いたくないとき用。ギャラリーに保存して、Galaxy の「壁紙に設定」の画面を開く。
 *   ひと月使えるよう、今日の印は付けない。
 *
 * 上の 3 割ほどは Galaxy の時計のために空けておき、その下に、刺繍の月のカレンダーを縫い付けたワッペンを置く。
 */
object LockArt {
    private fun component(context: Context) = ComponentName(context, LockWallpaper::class.java)

    private fun liveInfo(context: Context, which: Int) = runCatching {
        val wm = WallpaperManager.getInstance(context)
        if (android.os.Build.VERSION.SDK_INT >= 34) wm.getWallpaperInfo(which) else if (which == WallpaperManager.FLAG_SYSTEM) wm.wallpaperInfo else null
    }.getOrNull()

    /** FlipHome の動く壁紙が、いま壁紙に設定されているか。 */
    fun active(context: Context) =
        liveInfo(context, WallpaperManager.FLAG_LOCK)?.component == component(context) ||
            liveInfo(context, WallpaperManager.FLAG_SYSTEM)?.component == component(context)

    /**
     * FlipHome の動く壁紙を設定する画面（Galaxy のプレビュー）を開く。そこで「ホーム画面とロック画面」を選んでもらう。
     * 開けたかを返す。
     */
    fun openPicker(context: Context): Boolean {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context))
        return runCatching { context.startActivity(direct) }.isSuccess ||
            runCatching { context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }.isSuccess
    }

    /**
     * ロック画面とホーム画面に、どんな壁紙が設定されているかを、人が読める文にする（うまく変わらないときに調べる用）。
     * 動く壁紙なら、そのアプリの名前とパッケージ名を書く。壁紙の絵そのものは読まない。
     */
    fun describe(context: Context): String {
        val wm = WallpaperManager.getInstance(context)
        val pm = context.packageManager
        fun live(which: Int): String? {
            val info = liveInfo(context, which) ?: return null
            if (info.component == component(context)) return "FlipHome の刺繍（動く壁紙）"
            return "動く壁紙「${runCatching { info.loadLabel(pm) }.getOrDefault("?")}」（${info.packageName}）"
        }
        val lockId = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
        val lock = live(WallpaperManager.FLAG_LOCK) ?: if (lockId > 0) "静止画（番号 $lockId）" else "ホーム画面と同じ"
        val home = live(WallpaperManager.FLAG_SYSTEM) ?: "静止画"
        return "ロック画面：$lock\nホーム画面：$home\n最後に描いた日：${LockWallpaper.lastDrawn ?: "まだ（FlipHome が動き直してから）"}"
    }

    /** 絵を描いてギャラリーに保存し、壁紙に設定する画面を開く。うまくいかなければ理由を [done] に渡す。 */
    fun saveAndOpen(context: Context, done: (String?) -> Unit) {
        val bitmap = runCatching { render(context, markToday = false) }.getOrNull() ?: return done("絵を描けませんでした")
        val app = context.applicationContext
        ioThread.execute {
            val uri = runCatching { save(app, bitmap) }.getOrNull()
            mainHandler.post {
                if (uri == null) return@post done("ギャラリーに保存できませんでした")
                // Galaxy の「壁紙に設定」（ロック画面・ホーム画面を選べる）を開く。開けなければ「画像を設定」の選択肢を出す
                val setter = runCatching { WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri) }.getOrNull()
                val attach = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/png").putExtra("mimeType", "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val ok = (setter != null && runCatching { context.startActivity(setter.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.isSuccess) ||
                    runCatching { context.startActivity(Intent.createChooser(attach, "ロック画面の壁紙に設定")) }.isSuccess
                done(if (ok) null else "壁紙の画面を開けませんでした。ギャラリーの「Pictures/FlipHome」から設定してください")
            }
        }
    }

    /** ギャラリー（Pictures/FlipHome）に PNG で保存する（自分で作った画像なので、ストレージの権限は要らない）。 */
    private fun save(context: Context, bitmap: Bitmap): Uri {
        val name = "fliphome-lock-" + SimpleDateFormat("yyyyMM-HHmmss", Locale.US).format(Calendar.getInstance().time) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FlipHome")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存先を作れませんでした")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("書き込めませんでした")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** 画面の大きさの壁紙を、画像として描く（保存して自分で設定するとき）。 */
    fun render(context: Context, markToday: Boolean): Bitmap {
        // 画面いっぱいの大きさ（取れなければ Flip7 のメイン画面の大きさ）
        val bounds = context.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.bounds
        val w = bounds?.width()?.takeIf { it > 0 } ?: 1080
        val h = bounds?.height()?.takeIf { it > 0 } ?: 2520
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), w, h, markToday)
        return bitmap
    }

    /** 幅 [w]・高さ [h] の壁紙を [canvas] に描く。[markToday] なら、カレンダーに今日の印を付ける（動く壁紙のとき）。 */
    fun draw(context: Context, canvas: Canvas, w: Int, h: Int, markToday: Boolean) {
        canvas.drawPaint(Fabric.paint(context, Cloth.BASE))
        // 画面の縁のステッチ
        val inset = context.dp(5f)
        val r = context.dp(26f)
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, context.stitch(1.4f))

        // 月の名前（例：11月）と、カレンダーのワッペン
        val side = context.dp(22f)
        val patchTop = h * 0.46f
        val patchBottom = h * 0.76f
        val title = context.threadText(26f, bold = true).apply { letterSpacing = 0.15f }
        val now = Calendar.getInstance()
        canvas.drawText(SimpleDateFormat("M月", Locale.JAPAN).format(now.time), side + context.dp(6f), patchTop - context.dp(14f), title)
        val small = context.threadText(13f, color = Thread.DIM).apply { textAlign = Paint.Align.RIGHT; letterSpacing = 0.2f }
        canvas.drawText(SimpleDateFormat("yyyy", Locale.US).format(now.time), w - side - context.dp(6f), patchTop - context.dp(16f), small)

        val pw = (w - side * 2).toInt()
        val ph = (patchBottom - patchTop).toInt()
        canvas.save()
        canvas.translate(side, patchTop)
        PatchPainter(context, PatchPainter.Shape.Round(28f)).draw(canvas, pw, ph)
        // カレンダーは、ホーム画面の「月のカレンダー」の部品をそのまま描く
        val cal = DotCalendarView(context, mondayFirst = false, hasSwipe = { false }, onSwipe = {}, markToday = markToday, showMonth = false)
        val pad = context.dpi(18f)
        cal.measure(View.MeasureSpec.makeMeasureSpec(pw - pad * 2, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph - pad * 2, View.MeasureSpec.EXACTLY))
        cal.layout(0, 0, pw - pad * 2, ph - pad * 2)
        canvas.translate(pad.toFloat(), pad.toFloat())
        cal.draw(canvas)
        canvas.restore()

        // 下の飾り：二本の縫い目
        val y = patchBottom + context.dp(28f)
        canvas.drawDoubleSeam(side + context.dp(30f), w - side - context.dp(30f), y, context.dp(5f), context.stitch(1.0f, Thread.DIM))
    }
}

/**
 * ロック画面（とホーム画面）の「動く壁紙」。刺繍のカレンダーを、その場で描く。
 *
 * 動くのは、壁紙が見えるようになったとき・画面が点いたとき・日付や時刻が変わったときだけで、
 * 日付が変わっていなければ何もしない（アニメーションはしないので、電池はほとんど使わない）。
 * 通信も、ほかの壁紙の読み取りもしない。Android の決まりで、システム（壁紙の仕組み）だけがこのサービスにつなげる。
 */
class LockWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = ArtEngine()

    private inner class ArtEngine : Engine() {
        private var width = 0
        private var height = 0
        /** 最後に描いた日（同じ日なら描き直さない）。 */
        private var drawn: String? = null

        private val clock = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = draw(force = false)
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            // システムからの知らせだけを受ける（ほかのアプリからは受けない）
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(clock, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(clock, filter)
        }

        override fun onDestroy() {
            runCatching { unregisterReceiver(clock) }
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            draw(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw(force = true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) draw(force = false)
        }

        private fun draw(force: Boolean) {
            if (width <= 0 || height <= 0) return
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
            if (!force && drawn == today) return
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                LockArt.draw(this@LockWallpaper, canvas, width, height, markToday = true)
                drawn = today
                lastDrawn = today
            } catch (e: Exception) {
                // 描けなくても壁紙ごと落ちないようにする（次に見えたときにもう一度描く）
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    companion object {
        /** 最後に描いた日（「壁紙の状態を調べる」用。FlipHome が動いている間だけ覚えている）。 */
        @Volatile var lastDrawn: String? = null
    }
}
