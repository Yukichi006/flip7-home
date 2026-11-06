package io.github.yukichi006.fliphome

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ロック画面の壁紙を、ホーム画面と同じ「黒い布地に白いステッチ」で描く。
 * Android ではロック画面そのものは作り替えられないので、壁紙として描く（Galaxy の時計やショートカットは、その上に重なる）。
 *
 * - **自動**（[enable]）：ロック画面の壁紙に直接設定し、日付が変わるたびに描き直す（今日の印が毎日動く）。
 *   日付が変わったときは [MidnightReceiver] で、ほかに FlipHome を開いたときにも、今日の分になっているか確かめる。
 * - **手動**（[saveAndOpen]）：自動でうまく変わらないとき用。ギャラリーに保存して、Galaxy の「壁紙に設定」の画面を開く。
 *   ひと月使えるよう、今日の印は付けない。
 *
 * 上の 3 割ほどは Galaxy の時計のために空けておき、その下に、刺繍の月のカレンダーを縫い付けたワッペンを置く。
 */
object LockArt {
    private const val PREFS = "lock"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)

    /**
     * ホーム画面の壁紙にも同じ絵を設定するか。Galaxy は「ロック画面の編集」をしたあと、ロック画面の壁紙だけを差し替えても
     * 前の絵を出し続けることがあるため。ホーム画面の壁紙は FlipHome が布地を描くので、ふだんは見えない。
     */
    fun alsoHome(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("home", false)

    fun setAlsoHome(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("home", on).remove("date").apply()
        if (enabled(context)) apply(context) {}
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    /** ロック画面の壁紙を刺繍にし、毎日自動で描き直す。設定できたかを [done] に渡す。 */
    fun enable(context: Context, done: (Boolean) -> Unit) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", true).apply()
        apply(context, done)
    }

    /** 自動をやめ、ロック画面の壁紙を元に戻す（ホーム画面と同じ壁紙になる）。 */
    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).remove("date").apply()
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
        runCatching { WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_LOCK) }
    }

    /** 自動にしていて、まだ今日の分を描いていなければ描き直す（次の日付の変わり目の予約もし直す）。 */
    fun refreshIfNeeded(context: Context) {
        if (!enabled(context)) return
        scheduleMidnight(context)
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("date", null) == today()) return
        apply(context) {}
    }

    private fun apply(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        // 布地やカレンダーは画面の部品と同じ描き方なので、描くのはメインスレッド、壁紙の設定は裏のスレッドで行う
        val bitmap = runCatching { render(app, markToday = true) }.getOrNull() ?: return done(false)
        ioThread.execute {
            val ok = runCatching {
                val which = if (alsoHome(app)) WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_LOCK
                WallpaperManager.getInstance(app).setBitmap(bitmap, null, true, which)
            }.isSuccess
            if (ok) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("date", today()).apply()
            mainHandler.post {
                scheduleMidnight(app)
                done(ok)
            }
        }
    }

    /**
     * ロック画面とホーム画面に、どんな壁紙が設定されているかを、人が読める文にする（うまく変わらないときに調べる用）。
     * 動く壁紙なら、そのアプリの名前とパッケージ名を書く。壁紙の絵そのものは読まない。
     */
    fun describe(context: Context): String {
        val wm = WallpaperManager.getInstance(context)
        val pm = context.packageManager
        fun live(which: Int): String? {
            val info = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 34) wm.getWallpaperInfo(which) else if (which == WallpaperManager.FLAG_SYSTEM) wm.wallpaperInfo else null
            }.getOrNull() ?: return null
            return "動く壁紙「${runCatching { info.loadLabel(pm) }.getOrDefault("?")}」（${info.packageName}）"
        }
        val lockId = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
        val lock = live(WallpaperManager.FLAG_LOCK) ?: if (lockId > 0) "静止画（番号 $lockId）" else "ホーム画面と同じ"
        val home = live(WallpaperManager.FLAG_SYSTEM) ?: "静止画"
        val auto = if (enabled(context)) "オン（最後に描いた日：${context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("date", null) ?: "まだ"}）" else "オフ"
        return "ロック画面：$lock\nホーム画面：$home\nFlipHome の自動更新：$auto"
    }

    private fun midnightIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, MidnightReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 次の日付の変わり目（0 時を少し過ぎたころ）に描き直す予約をする（時刻ぴったりでなくてよいので、正確なアラームは使わない）。 */
    private fun scheduleMidnight(context: Context) {
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 30); set(Calendar.MILLISECOND, 0)
        }
        runCatching { context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC, next.timeInMillis, midnightIntent(context)) }
    }

    /** 日付が変わったときに呼ばれ、ロック画面の壁紙を今日の分に描き直す。 */
    class MidnightReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!enabled(context)) return
            val pending = goAsync()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("date").apply()
            apply(context) { pending.finish() }
        }
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

    /** 画面の大きさの壁紙を描く。[markToday] なら、カレンダーに今日の印を付ける（毎日描き直すとき）。 */
    fun render(context: Context, markToday: Boolean): Bitmap {
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
        // カレンダーは、ホーム画面の「月のカレンダー」の部品をそのまま描く（今日の印は付けない）
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
        return bitmap
    }
}
