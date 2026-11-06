package io.github.yukichi006.fliphome

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextUtils
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * 次に鳴るアラーム（刺繍の部品）。ベルの刺繍と、縫った数字の時刻、「明日」「11月7日（土）」などの日付。
 * Android の「次のアラーム」を読むだけなので、新しい権限は使わない。アラームを変えたら、すぐに描き直す。タップで時計アプリのアラーム。
 */
class NextAlarmView(context: Context) : PatchView(context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val sub = context.threadText(12f, color = Thread.DIM).apply { letterSpacing = 0.04f }
    private val none = context.threadText(13f, color = Thread.DIM)
    private val path = Path()
    private val bounds = Rect()

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = invalidate()
    }

    init {
        setOnClickListener {
            runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(changed, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED).apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        })
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(changed) }
        super.onDetachedFromWindow()
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        val next = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
        // ベル（左上、部品が低いときは左の真ん中）
        val bell = min(area.height() * 0.42f, area.width() * 0.28f).coerceAtMost(context.dp(40f))
        val wide = area.width() > area.height() * 1.6f
        val bx = area.left
        val by = if (wide) area.centerY() - bell / 2 else area.top
        drawBell(canvas, bx, by, bell, next != null)
        val textLeft = if (wide) bx + bell + context.dp(10f) else area.left
        val textTop = if (wide) area.top else by + bell + context.dp(6f)
        val textW = area.right - textLeft
        val textH = area.bottom - textTop
        if (next == null) {
            val t = TextUtils.ellipsize("アラームなし", none, textW, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, textLeft, textTop + textH / 2 + none.textSize * 0.35f, none)
            return
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        val time = DateFormat.format(pattern, next).toString()
        // 縫った数字の時刻：幅と高さ（下に日付の行を残す）に入る大きさにする
        val subH = sub.textSize * 1.5f
        digits.textSize = 100f
        digits.getTextBounds("00:00", 0, 5, bounds)
        val scale = min(textW * 0.95f / bounds.width(), (textH - subH) * 0.85f / bounds.height()).coerceAtLeast(0.05f)
        digits.textSize = 100f * scale
        digits.getTextBounds(time, 0, time.length, bounds)
        val baseline = textTop + (textH - subH) / 2 + bounds.height() / 2f
        path.reset()
        digits.getTextPath(time, 0, time.length, textLeft - bounds.left, baseline, path)
        val w = (digits.textSize * 0.035f).coerceIn(context.dp(1f), context.dp(2.2f))
        canvas.drawPath(path, stitchPaint(w, w * 3f, w * 1.9f))
        // 日付（今日・明日、それより先は日付）
        val day = dayLabel(next)
        val t = TextUtils.ellipsize(day, sub, textW, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, textLeft, baseline + subH, sub)
    }

    private fun dayLabel(at: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        return when (dayIndex(target) - dayIndex(today)) {
            0 -> "今日"
            1 -> "明日"
            else -> java.text.SimpleDateFormat("M月d日（E）", Locale.JAPAN).format(target.time)
        }
    }

    /** ベルの刺繍（アラームがないときは薄い糸で、斜線を入れる）。 */
    private fun drawBell(canvas: Canvas, x: Float, y: Float, s: Float, on: Boolean) {
        val color = if (on) Thread.COLOR else Thread.DIM
        val p = stitchPaint(s * 0.06f, s * 0.12f, s * 0.08f, color)
        val cx = x + s / 2
        val bell = Path().apply {
            moveTo(cx - s * 0.32f, y + s * 0.72f)
            cubicTo(cx - s * 0.28f, y + s * 0.5f, cx - s * 0.3f, y + s * 0.18f, cx, y + s * 0.16f)
            cubicTo(cx + s * 0.3f, y + s * 0.18f, cx + s * 0.28f, y + s * 0.5f, cx + s * 0.32f, y + s * 0.72f)
            lineTo(cx + s * 0.4f, y + s * 0.8f)
            lineTo(cx - s * 0.4f, y + s * 0.8f)
            close()
        }
        canvas.drawPath(bell, p)
        canvas.drawCircle(cx, y + s * 0.9f, s * 0.07f, p)
        canvas.drawLine(cx, y + s * 0.06f, cx, y + s * 0.16f, p)
        if (!on) canvas.drawLine(x + s * 0.12f, y + s * 0.1f, x + s * 0.88f, y + s * 0.9f, p)
    }
}

/**
 * ひと月の予定（刺繍の部品）。今月のマス目に、日付と、その日の予定の件名を小さく縫う（予定の色の短い縫い目つき）。
 * 土日・祝日の日付はオレンジ、今日は輪で囲む。タップでカレンダーアプリ。
 * カレンダーを読む許可は「今日の予定」と同じ（なければタップで許可を求める）。
 */
class MonthEventsView(context: Context) : PatchView(context) {
    private class Event(val day: Int, val title: String, val color: Int)

    private var events: List<Event>? = null
    private var month = -1
    private val title = context.threadText(10.5f, bold = true, color = Thread.DIM).apply { letterSpacing = 0.08f }
    private val week = context.threadText(9f, color = Thread.DIM).apply { textAlign = Paint.Align.CENTER }
    private val weekend = context.threadText(9f, color = Thread.ALERT).apply { textAlign = Paint.Align.CENTER }
    private val num = context.threadText(10f, bold = true)
    private val numOff = context.threadText(10f, bold = true, color = Thread.ALERT)
    private val ev = context.threadText(8f)
    private val more = context.threadText(8f, color = Thread.DIM)
    private val hint = context.threadText(12f, color = Thread.DIM)
    private val ring = context.stitch(1.0f)
    private val grid = context.stitch(0.7f, Thread.FAINT)
    private val tick = object : BroadcastReceiver() {
        private var count = 0
        override fun onReceive(c: Context, i: Intent) {
            // 15 分ごと、日付が変わったら読み直す
            if (i.action != Intent.ACTION_TIME_TICK || ++count % 15 == 0) reload()
        }
    }

    init {
        setOnClickListener {
            if (!granted()) (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), AgendaView.REQUEST)
            else openCalendar(context)
        }
    }

    private fun granted() = context.hasPermission(Manifest.permission.READ_CALENDAR)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_DATE_CHANGED) })
        reload()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    fun reload() {
        if (!granted()) { events = null; invalidate(); return }
        val resolver = context.contentResolver
        ioThread.execute {
            val list = runCatching { query(resolver) }.getOrDefault(emptyList())
            mainHandler.post { events = list; invalidate() }
        }
    }

    private fun query(resolver: android.content.ContentResolver): List<Event> {
        val cal = Calendar.getInstance()
        month = cal.get(Calendar.MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
        )
        val list = mutableListOf<Event>()
        val c = Calendar.getInstance()
        resolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null,
            "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0)?.takeIf { it.isNotBlank() } ?: "（件名なし）"
                val begin = cur.getLong(1)
                val finish = cur.getLong(2)
                val allDay = cur.getInt(3) == 1
                val color = StitchIcons.threadTone(cur.getInt(4))
                // 何日にもまたがる予定は、その日ごとに出す（終日の予定は UTC で入っているので、終わりの日を含めない）
                var t = maxOf(begin, start)
                val last = minOf(finish - 1, end - 1)
                c.timeInMillis = t
                if (allDay) c.timeZone = java.util.TimeZone.getTimeZone("UTC")
                var guard = 0
                while (t <= last && guard++ < 40) {
                    if (c.get(Calendar.MONTH) == month) list += Event(c.get(Calendar.DAY_OF_MONTH), name, color)
                    c.add(Calendar.DAY_OF_MONTH, 1)
                    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
                    t = c.timeInMillis
                }
            }
        }
        return list
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.MONTH) != month && events != null) post { reload() }
        canvas.drawText("${cal.get(Calendar.MONTH) + 1}月の予定", area.left, area.top + title.textSize, title)
        if (!granted()) {
            canvas.drawText("タップして予定を表示", area.left, area.top + title.textSize + hint.textSize * 1.8f, hint)
            return
        }
        val year = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val lead = cal.get(Calendar.DAY_OF_WEEK) - 1
        val weeks = (lead + days + 6) / 7
        val headTop = area.top + title.textSize + context.dp(6f)
        val names = "日月火水木金土"
        val cw = area.width() / 7f
        for (i in 0 until 7) canvas.drawText(names[i].toString(), area.left + cw * (i + 0.5f), headTop + week.textSize, if (i == 0 || i == 6) weekend else week)
        val top = headTop + week.textSize * 1.6f
        val ch = (area.bottom - top) / weeks
        val byDay = events.orEmpty().groupBy { it.day }
        // 文字の大きさを、マスの大きさに合わせる
        num.textSize = (ch * 0.2f).coerceIn(context.sp(8f), context.sp(11f)); numOff.textSize = num.textSize
        ev.textSize = (ch * 0.15f).coerceIn(context.sp(6.5f), context.sp(9f)); more.textSize = ev.textSize
        for (wk in 1 until weeks) canvas.drawLine(area.left, top + ch * wk, area.right, top + ch * wk, grid)
        for (cell in 0 until weeks * 7) {
            val day = cell - lead + 1
            if (day < 1 || day > days) continue
            val col = cell % 7
            val x = area.left + cw * col
            val y = top + ch * (cell / 7)
            val off = col == 0 || col == 6 || Holidays.isHoliday(year, m, day)
            val np = if (off) numOff else num
            val nx = x + context.dp(2f)
            val ny = y + np.textSize + context.dp(2f)
            canvas.drawText(day.toString(), nx, ny, np)
            if (day == today) {
                val tw = np.measureText(day.toString())
                canvas.drawCircle(nx + tw / 2, ny - np.textSize * 0.35f, np.textSize * 0.85f, ring)
            }
            // その日の予定：入るだけ縫い、入らない分は「+2」のように
            val list = byDay[day].orEmpty()
            val lineH = ev.textSize * 1.25f
            val room = ((y + ch - ny - context.dp(2f)) / lineH).toInt().coerceAtLeast(0)
            val shown = if (list.size > room) (room - 1).coerceAtLeast(0) else list.size
            var ly = ny + lineH
            for (e in list.take(shown)) {
                val dash = Paint(ring).apply { color = e.color; strokeWidth = context.dp(1.6f); pathEffect = null; strokeCap = Paint.Cap.ROUND }
                canvas.drawLine(x + context.dp(2f), ly - ev.textSize * 0.35f, x + context.dp(5f), ly - ev.textSize * 0.35f, dash)
                val t = TextUtils.ellipsize(e.title, ev, cw - context.dp(9f), TextUtils.TruncateAt.END)
                canvas.drawText(t, 0, t.length, x + context.dp(7f), ly, ev)
                ly += lineH
            }
            if (list.size > shown && room > 0) canvas.drawText("+${list.size - shown}", x + context.dp(7f), ly, more)
        }
    }
}
