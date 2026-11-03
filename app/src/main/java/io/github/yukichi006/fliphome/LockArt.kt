package io.github.yukichi006.fliphome

import android.app.WallpaperManager
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
 * Android ではロック画面そのものは作り替えられない。また Galaxy では、アプリから直接ロック画面の壁紙を設定しても
 * 反映されないことがあるので、描いた絵をギャラリー（Pictures/FlipHome）に保存し、Galaxy の「壁紙に設定」の画面を開いて、
 * そこでロック画面を選んで設定してもらう。
 *
 * 上の 4 割ほどは Galaxy の時計のために空けておき、下の方に、刺繍の月のカレンダーを縫い付けたワッペンを置く。
 * ひと月使い続けられるよう、カレンダーには「今日」の印を付けない。
 */
object LockArt {
    /** 絵を描いてギャラリーに保存し、壁紙に設定する画面を開く。うまくいかなければ理由を [done] に渡す。 */
    fun saveAndOpen(context: Context, done: (String?) -> Unit) {
        val bitmap = runCatching { render(context) }.getOrNull() ?: return done("絵を描けませんでした")
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
        canvas.drawText(SimpleDateFormat("M月", Locale.JAPAN).format(now.time), side + context.dp(6f), patchTop - context.dp(14f), title)
        val small = context.threadText(13f, color = Thread.DIM).apply { textAlign = Paint.Align.RIGHT; letterSpacing = 0.2f }
        canvas.drawText(SimpleDateFormat("yyyy", Locale.US).format(now.time), w - side - context.dp(6f), patchTop - context.dp(16f), small)

        val pw = (w - side * 2).toInt()
        val ph = (patchBottom - patchTop).toInt()
        canvas.save()
        canvas.translate(side, patchTop)
        PatchPainter(context, PatchPainter.Shape.Round(28f)).draw(canvas, pw, ph)
        // カレンダーは、ホーム画面の「月のカレンダー」の部品をそのまま描く（今日の印は付けない）
        val cal = DotCalendarView(context, mondayFirst = false, hasSwipe = { false }, onSwipe = {}, markToday = false, showMonth = false)
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
