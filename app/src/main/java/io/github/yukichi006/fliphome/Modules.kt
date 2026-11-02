package io.github.yukichi006.fliphome

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/** 部品どうしで使い回す、ちょっとした裏方の仕事用のスレッド。 */
val ioThread = Executors.newSingleThreadExecutor()
val mainHandler = Handler(Looper.getMainLooper())

fun Context.hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

// ----------------------------------------------------------------------
// 時計
// ----------------------------------------------------------------------

/**
 * 大きな時計。数字の輪郭をステッチで縫い、下に日付、いちばん下に二本の縫い目の区切り線を描く。
 * タップでアラーム（時計アプリ）を開く。
 */
@SuppressLint("ViewConstructor")
class ClockView(context: Context) : View(context) {
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.CENTER
    }
    private val date = context.threadText(16f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val seam = context.stitch(1.0f, Thread.DIM)
    private val path = Path()
    private val bounds = Rect()
    private var stitch = context.stitch(1.5f)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = invalidate()
    }

    init {
        setOnClickListener {
            runCatching {
                context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 数字が大きいほど糸も太く、縫い目も長くする
        val width = (h * 0.011f).coerceIn(context.dp(1.3f), context.dp(2.4f))
        stitch = stitchPaint(width, width * 3f, width * 1.9f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val time = DateFormat.format(pattern, now).toString()

        val seamY = height - context.dp(10f)
        val dateY = seamY - context.dp(22f)
        val maxH = dateY - date.textSize - context.dp(18f)
        // 幅と高さの両方に収まる大きさにする
        digits.textSize = 100f
        digits.getTextBounds("00:00", 0, 5, bounds)
        val scale = min(width * 0.86f / bounds.width(), maxH * 0.92f / bounds.height())
        digits.textSize = 100f * scale
        digits.getTextBounds(time, 0, time.length, bounds)
        val baseline = (maxH + bounds.height()) / 2 + context.dp(6f)
        path.reset()
        digits.getTextPath(time, 0, time.length, width / 2f, baseline, path)
        canvas.drawPath(path, stitch)

        val dateText = DateFormat.format("M月d日（E）", now).toString()
        canvas.drawText(dateText, width / 2f, dateY, date)

        val side = context.dp(14f)
        canvas.drawDoubleSeam(side, width - side, seamY, context.dp(5f), seam)
    }
}

// ----------------------------------------------------------------------
// 今日の予定
// ----------------------------------------------------------------------

/**
 * 今日これからの予定を、時刻と件名で並べるワッペン。カレンダーの許可がなければ、タップで許可を求める。
 * 許可があれば、タップでカレンダーアプリを開く。
 */
@SuppressLint("ViewConstructor")
class AgendaView(context: Context) : PatchView(context) {
    private class Event(val time: String, val title: String, val now: Boolean)

    private var events: List<Event>? = null
    private val title = context.threadText(10.5f, color = Thread.DIM).apply { letterSpacing = 0.2f }
    private val line = context.threadText(14.5f)
    private val timeText = context.threadText(14.5f).apply {
        typeface = Typeface.MONOSPACE
    }
    private val hint = context.threadText(12.5f, color = Thread.DIM)

    private val tick = object : BroadcastReceiver() {
        private var count = 0
        override fun onReceive(c: Context, i: Intent) {
            // 5 分ごとに読み直す（終わった予定を消すため）
            if (++count % 5 == 0) reload()
        }
    }

    init {
        setOnClickListener {
            if (!granted()) {
                (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST)
            } else {
                val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                    .let { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun granted() = context.hasPermission(Manifest.permission.READ_CALENDAR)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        reload()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    fun reload() {
        if (!granted()) {
            events = null
            invalidate()
            return
        }
        val resolver = context.contentResolver
        ioThread.execute {
            val list = runCatching { query(resolver) }.getOrDefault(emptyList())
            mainHandler.post {
                events = list
                invalidate()
            }
        }
    }

    private fun query(resolver: android.content.ContentResolver): List<Event> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
        )
        val list = mutableListOf<Event>()
        resolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null,
            "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "（件名なし）"
                val begin = c.getLong(1)
                val finish = c.getLong(2)
                val allDay = c.getInt(3) == 1
                if (!allDay && finish <= now) continue
                val time = if (allDay) "終日" else DateFormat.format("HH:mm", begin).toString()
                list += Event(time, name, !allDay && begin <= now)
            }
        }
        return list
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        var y = area.top + title.textSize
        canvas.drawText("TODAY", area.left, y, title)
        y += context.dp(10f)
        val list = events
        if (list == null) {
            y += hint.textSize
            canvas.drawText("タップして", area.left, y, hint)
            canvas.drawText("予定を表示", area.left, y + hint.textSize * 1.4f, hint)
            return
        }
        if (list.isEmpty()) {
            canvas.drawText("予定はありません", area.left, y + hint.textSize, hint)
            return
        }
        val rowH = line.textSize * 1.55f
        val timeW = timeText.measureText("00:00") + context.dp(10f)
        for (e in list) {
            if (y + rowH > area.bottom + context.dp(4f)) break
            y += rowH
            // 始まっている予定は少し明るく、この先の予定は少し控えめに
            val alpha = if (e.now) 255 else 215
            timeText.alpha = alpha
            line.alpha = alpha
            canvas.drawText(e.time, area.left, y, timeText)
            val t = TextUtils.ellipsize(e.title, line, area.width() - timeW, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, area.left + timeW, y, line)
        }
    }

    companion object {
        const val REQUEST = 11
    }
}

// ----------------------------------------------------------------------
// アプリのパッド
// ----------------------------------------------------------------------

/**
 * アプリ 1 つ。刺繍にしたアイコンと、その下に名前を描く。タップで起動、長押しで [onMenu]。
 * 触れている間は少し沈ませる。
 */
@SuppressLint("ViewConstructor")
class AppCell(
    context: Context,
    val app: AppEntry,
    private val repo: AppRepository,
    private val iconDp: Float = 46f,
    private val onMenu: (AppCell) -> Unit,
) : View(context) {
    private var icon: Bitmap? = null
    private val label: TextPaint = context.threadText(11.5f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val iconPx = context.dpi(iconDp)

    init {
        setOnClickListener { repo.launch(app, this) }
        setOnLongClickListener { onMenu(this); true }
        contentDescription = app.label
        icon = StitchIcons.cached(app, iconPx)
        if (icon == null) StitchIcons.load(context, app, iconPx) { icon = it; invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val gap = context.dp(7f)
        val total = iconPx + gap + label.textSize
        val top = (height - total) / 2
        icon?.let { canvas.drawBitmap(it, (width - iconPx) / 2f, top, null) }
        val text = TextUtils.ellipsize(app.label, label, width - context.dp(6f), TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, width / 2f, top + iconPx + gap + label.textSize * 0.85f, label)
    }
}

/** 下段のジーンズのポケット。アプリを最大 [MAX] 個、等間隔に並べる。 */
class PocketDock(context: Context) : PatchLayout(context, PatchPainter.Shape.Pocket) {
    init {
        // 上の二重ステッチの下から並べる
        val p = patch.contentPadding.toInt()
        setPadding(p, p + context.dpi(6f), p, p)
    }

    fun setCells(cells: List<AppCell>) {
        removeAllViews()
        cells.take(MAX).forEach { addView(it) }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / MAX
        val ch = h - paddingTop - paddingBottom
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cw = (width - paddingLeft - paddingRight) / MAX
        // アプリが少ないときも真ん中に寄せる
        val start = paddingLeft + (MAX - childCount) * cw / 2
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = start + i * cw
            c.layout(x, paddingTop, x + c.measuredWidth, paddingTop + c.measuredHeight)
        }
    }

    companion object {
        const val MAX = 4
    }
}
