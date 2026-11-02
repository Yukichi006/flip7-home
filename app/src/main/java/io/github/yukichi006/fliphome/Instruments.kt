package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 部品の中で文字などを寄せる位置。MPC と同じく 2 文字で表す（1 文字目：t 上・m 中・b 下、2 文字目：l 左・c 中央・r 右）。
 */
data class Align(val v: Char, val h: Char) {
    companion object {
        fun parse(s: String?, default: String = "mc"): Align {
            val t = (s ?: default).padEnd(2, 'c')
            return Align(t[0].takeIf { it in "tmb" } ?: 'm', t[1].takeIf { it in "lcr" } ?: 'c')
        }

        val CHOICES = listOf(
            "tl" to "左上", "tc" to "上", "tr" to "右上",
            "ml" to "左", "mc" to "真ん中", "mr" to "右",
            "bl" to "左下", "bc" to "下", "br" to "右下",
        )
    }

    /** 幅 [w]・高さ [h] の中に、幅 [cw]・高さ [ch] のものを置くときの左上。 */
    fun place(w: Float, h: Float, cw: Float, ch: Float, pad: Float): Pair<Float, Float> {
        val x = when (this.h) { 'l' -> pad; 'r' -> w - cw - pad; else -> (w - cw) / 2 }
        val y = when (v) { 't' -> pad; 'b' -> h - ch - pad; else -> (h - ch) / 2 }
        return x to y
    }
}

/** 毎分（[seconds] なら毎秒）描き直す部品の土台。 */
abstract class TickingView(context: Context) : View(context) {
    protected open val seconds = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = invalidate()
    }
    private val everySecond = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        if (seconds) post(everySecond)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        removeCallbacks(everySecond)
        super.onDetachedFromWindow()
    }
}

// ----------------------------------------------------------------------
// 時刻・日付・見出し
// ----------------------------------------------------------------------

/** 時刻だけ。数字の輪郭をステッチで縫う。[showSeconds] なら秒を小さく横に添える。タップでアラーム。 */
@SuppressLint("ViewConstructor")
class TimeView(context: Context, private val align: Align, private val showSeconds: Boolean) : TickingView(context) {
    override val seconds get() = showSeconds
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val sec = context.threadText(12f, bold = true).apply { fontFeatureSettings = "tnum" }
    private val path = Path()
    private val bounds = Rect()

    init {
        setOnClickListener {
            runCatching { context.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", now).toString()
        val secText = if (showSeconds) ":%02d".format(now.get(Calendar.SECOND)) else ""
        digits.textSize = 100f
        digits.getTextBounds("00:00", 0, 5, bounds)
        val pad = context.dp(8f)
        val secRatio = if (showSeconds) 0.42f else 0f
        val scale = min((width - pad * 2) / (bounds.width() * (1 + secRatio)), (height - pad * 2) / bounds.height().toFloat())
        digits.textSize = 100f * scale
        digits.getTextBounds(time, 0, time.length, bounds)
        sec.textSize = digits.textSize * 0.38f
        val totalW = bounds.width() + (if (showSeconds) sec.measureText(secText) + context.dp(2f) else 0f)
        val (x, y) = align.place(width.toFloat(), height.toFloat(), totalW, bounds.height().toFloat(), pad)
        path.reset()
        digits.getTextPath(time, 0, time.length, x - bounds.left, y - bounds.top, path)
        val w = (bounds.height() * 0.035f).coerceIn(context.dp(1.1f), context.dp(2.4f))
        canvas.drawPath(path, stitchPaint(w, w * 3f, w * 1.9f))
        if (showSeconds) canvas.drawText(secText, x + bounds.width() + context.dp(2f), y + bounds.height(), sec)
    }
}

/** 日付だけ。[style] が "dot" なら「11.02 MON」、そうでなければ「11月2日（月）」。タップでカレンダー。 */
@SuppressLint("ViewConstructor")
class DateView(context: Context, private val align: Align, private val style: String?) : TickingView(context) {
    private val text = context.threadText(20f).apply { letterSpacing = 0.06f }

    init { setOnClickListener { openCalendar(context) } }

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        val s = if (style == "dot") {
            java.text.SimpleDateFormat("MM.dd EEE", Locale.US).format(now.time).uppercase()
        } else {
            java.text.SimpleDateFormat("M月d日（E）", Locale.JAPAN).format(now.time)
        }
        val pad = context.dp(8f)
        text.textSize = 100f
        val fit = min((width - pad * 2) / text.measureText(s) * 100f, height * 0.42f)
        text.textSize = fit.coerceAtMost(context.sp(30f))
        val cw = text.measureText(s)
        val (x, y) = align.place(width.toFloat(), height.toFloat(), cw, text.textSize, pad)
        canvas.drawText(s, x, y + text.textSize * 0.82f, text)
    }
}

/**
 * 見出し（ページの名前など）。横長なら文字の下に縫い目の線を、縦長なら文字を縦に回し、横に短い縫い目を梯子のように並べる。
 */
@SuppressLint("ViewConstructor")
class TitleView(context: Context, private val title: String, private val align: Align) : View(context) {
    private val text = context.threadText(22f, bold = true).apply { letterSpacing = 0.12f }
    private val seam = context.stitch(1.0f, Thread.DIM)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        strokeWidth = context.dp(3.2f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(8f)
        if (height > width * 1.4f) {
            // 縦長：左に文字を縦に、右に梯子のような縫い目
            text.textSize = min(context.sp(20f), width * 0.36f)
            text.color = Thread.DIM
            val t = TextUtils.ellipsize(title, text, height - pad * 2, TextUtils.TruncateAt.END).toString()
            canvas.save()
            canvas.rotate(90f)
            canvas.drawText(t, pad, -pad, text)
            canvas.restore()
            val x0 = pad + text.textSize + context.dp(6f)
            val x1 = width - pad
            val step = context.dp(10f)
            var y = pad + context.dp(4f)
            while (y < height - pad) {
                canvas.drawLine(x0, y, x1, y, bar)
                y += step
            }
            return
        }
        text.color = Thread.COLOR
        text.textSize = min(context.sp(26f), height * 0.45f)
        val t = TextUtils.ellipsize(title, text, width - pad * 2, TextUtils.TruncateAt.END).toString()
        val cw = text.measureText(t)
        val (x, y) = align.place(width.toFloat(), height.toFloat(), cw, text.textSize + context.dp(10f), pad)
        canvas.drawText(t, x, y + text.textSize * 0.85f, text)
        val ly = y + text.textSize + context.dp(7f)
        canvas.drawLine(pad, ly, width - pad, ly, seam)
    }
}

fun openCalendar(context: Context) {
    val uri = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        .let { android.content.ContentUris.appendId(it, System.currentTimeMillis()) }.build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ----------------------------------------------------------------------
// 月のカレンダー（刺繍の点）
// ----------------------------------------------------------------------

/**
 * 今月を、刺繍の点で表すカレンダー。今月の日は糸の玉、前後の月の日は薄い輪、今日は糸の玉に輪を重ねる。
 * タップでカレンダーアプリ。上下左右のスワイプには、[onSwipe] で好きな動作を割り当てられる。
 */
@SuppressLint("ViewConstructor")
class DotCalendarView(
    context: Context,
    private val mondayFirst: Boolean,
    private val hasSwipe: (String) -> Boolean,
    private val onSwipe: (String) -> Unit,
) : TickingView(context) {
    private val ring = context.stitch(0.9f, Thread.FAINT)
    private val todayRing = context.stitch(1.2f)
    private val week = context.threadText(9f, color = Thread.DIM).apply { textAlign = Paint.Align.CENTER }
    private val knot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(0.8f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDow = cal.get(Calendar.DAY_OF_WEEK) // 1 = 日曜
        val lead = if (mondayFirst) (firstDow + 5) % 7 else firstDow - 1
        val weeks = (lead + days + 6) / 7
        val pad = context.dp(10f)
        val headH = week.textSize * 1.6f
        val cw = (width - pad * 2) / 7f
        val ch = (height - pad * 2 - headH) / weeks
        val r = (min(cw, ch) * 0.24f).coerceAtMost(context.dp(9f))
        val names = if (mondayFirst) "月火水木金土日" else "日月火水木金土"
        for (i in 0 until 7) canvas.drawText(names[i].toString(), pad + cw * (i + 0.5f), pad + week.textSize, week)
        for (cell in 0 until weeks * 7) {
            val day = cell - lead + 1
            val cx = pad + cw * (cell % 7 + 0.5f)
            val cy = pad + headH + ch * (cell / 7 + 0.5f)
            when {
                day < 1 || day > days -> canvas.drawCircle(cx, cy, r * 0.75f, ring)
                day == today -> {
                    canvas.drawCircle(cx, cy, r * 1.05f, knot)
                    canvas.drawCircle(cx, cy, r * 1.6f, todayRing)
                }
                else -> canvas.drawCircle(cx, cy, r * if (day < today) 0.8f else 1f, knot.apply { alpha = if (day < today) 150 else 255 })
            }
            knot.alpha = 255
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // スワイプを割り当てていれば、ホーム画面のスワイプ（ページ・一覧・通知）より優先する
                if (listOf("up", "down", "left", "right").any(hasSwipe)) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) < slop * 2 && abs(dy) < slop * 2) {
                    if (!isLongPressFired()) openCalendar(context)
                } else {
                    val dir = if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSwipe(dir)
                }
            }
        }
        super.onTouchEvent(event)
        return true
    }

    private var longFired = false
    private fun isLongPressFired() = longFired.also { longFired = false }

    override fun performLongClick(): Boolean {
        longFired = true
        return super.performLongClick()
    }
}

// ----------------------------------------------------------------------
// フェーダー（ファスナー）
// ----------------------------------------------------------------------

/**
 * 縦のファスナーの形をしたフェーダー。引き手を上下に動かして値を変える。閉じている（引き手より下の）歯は白く、開いている歯は薄く描く。
 * - "volume"：メディアの音量。タップで消音の切り替え
 * - "brightness"：画面の明るさ（「システム設定の変更」の許可が要る）。タップで明るさの自動調整の切り替え
 * - "app"：真ん中で待つバネのフェーダー。上端・下端まで倒すとアプリを開き、離すと真ん中へ戻る
 */
@SuppressLint("ViewConstructor")
class ZipperFader(
    context: Context,
    private val function: String,
    private val label: String,
    private val onEnd: (top: Boolean) -> Unit = {},
    private val needPermission: () -> Unit = {},
) : View(context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val tooth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tape = context.stitch(0.9f, Thread.DIM)
    private val pullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(4f), 0f, context.dp(2f), 0xC0000000.toInt())
    }
    private val pullStitch = context.stitch(1.1f)
    private val text = context.threadText(10f, bold = true).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.15f }
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(16f))

    /** 0（下）〜1（上）。 */
    private var value = 0.5f
    private var dragging = false
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (!dragging) { readValue(); invalidate() } }
    }

    init {
        contentDescription = label
        readValue()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (function == "volume") context.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED)
        readValue()
    }

    override fun onDetachedFromWindow() {
        if (function == "volume") runCatching { context.unregisterReceiver(volumeReceiver) }
        super.onDetachedFromWindow()
    }

    private fun readValue() {
        value = when (function) {
            "volume" -> audio?.let { a ->
                val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                a.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            } ?: 0.5f
            "brightness" -> runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
            else -> 0.5f
        }
    }

    private fun writeValue() {
        when (function) {
            "volume" -> audio?.let { a ->
                val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
            }
            "brightness" -> if (Settings.System.canWrite(context)) runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (value * 255).roundToInt().coerceIn(1, 255))
            }
        }
    }

    private fun trackTop() = patch.margin + context.dp(14f)
    private fun trackBottom() = height - patch.margin - context.dp(38f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (function == "brightness" && !Settings.System.canWrite(context)) {
                    needPermission()
                    return false
                }
                downY = event.y
                moved = false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(event.y - downY) > slop) moved = true
                if (moved) {
                    val v = 1f - ((event.y - trackTop()) / (trackBottom() - trackTop())).coerceIn(0f, 1f)
                    if (function == "app") {
                        val edge = v > 0.97f || v < 0.03f
                        val wasEdge = value > 0.97f || value < 0.03f
                        if (edge && !wasEdge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else if ((v * 15).toInt() != (value * 15).toInt()) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    value = v
                    if (function != "app") writeValue()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) tap()
                if (function == "app") {
                    if (moved && value > 0.97f) onEnd(true)
                    if (moved && value < 0.03f) onEnd(false)
                    // バネのように真ん中へ戻る
                    val from = value
                    android.animation.ValueAnimator.ofFloat(from, 0.5f).apply {
                        duration = 260
                        interpolator = android.view.animation.OvershootInterpolator(2f)
                        addUpdateListener { value = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
            }
        }
        return true
    }

    private fun tap() {
        when (function) {
            "volume" -> audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "brightness" -> runCatching {
                val auto = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                android.widget.Toast.makeText(context,
                    if (auto == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "明るさ：手動" else "明るさ：自動", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        readValue()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        patch.draw(canvas, width, height)
        val cx = width / 2f
        val top = trackTop()
        val bottom = trackBottom()
        val tw = context.dp(5f)
        // ファスナーのテープ（左右の縫い目）
        canvas.drawLine(cx - tw * 2.2f, top, cx - tw * 2.2f, bottom, tape)
        canvas.drawLine(cx + tw * 2.2f, top, cx + tw * 2.2f, bottom, tape)
        val pullY = bottom - (bottom - top) * value
        // 歯：左右交互に小さな四角を並べる。引き手より下は閉じて白く、上は開いて薄い
        val step = context.dp(4.5f)
        var y = top
        var left = true
        while (y <= bottom) {
            val closed = if (function == "app") false else y >= pullY
            tooth.color = if (closed) Thread.COLOR else Thread.FAINT
            val open = if (closed) 0f else context.dp(1.5f)
            val x0 = if (left) cx - tw - open else cx + open
            canvas.drawRoundRect(RectF(x0, y - step * 0.32f, x0 + tw, y + step * 0.32f), context.dp(1f), context.dp(1f), tooth)
            y += step
            left = !left
        }
        // 引き手（本体と、下に垂れたつまみ）
        val pw = tw * 4.4f
        val ph = context.dp(20f)
        val body = RectF(cx - pw / 2, pullY - ph / 2, cx + pw / 2, pullY + ph / 2)
        val tab = RectF(cx - pw * 0.28f, body.bottom - context.dp(3f), cx + pw * 0.28f, body.bottom + context.dp(13f))
        canvas.drawRoundRect(tab, context.dp(5f), context.dp(5f), pullFill)
        canvas.drawRoundRect(RectF(tab.left + context.dp(2.5f), tab.top + context.dp(4f), tab.right - context.dp(2.5f), tab.bottom - context.dp(2.5f)), context.dp(3f), context.dp(3f), pullStitch)
        canvas.drawRoundRect(body, context.dp(6f), context.dp(6f), pullFill)
        canvas.drawRoundRect(RectF(body.left + context.dp(2.5f), body.top + context.dp(2.5f), body.right - context.dp(2.5f), body.bottom - context.dp(2.5f)), context.dp(4f), context.dp(4f), pullStitch)
        canvas.drawText(label, cx, height - patch.margin - context.dp(10f), text)
    }
}

// ----------------------------------------------------------------------
// 再生中の曲
// ----------------------------------------------------------------------

/**
 * 再生中の曲名と、前へ・再生／一時停止・次へのボタン。
 * 再生中のアプリを知るのに「通知へのアクセス」の許可を使う（許可がなければ、タップで説明を開く）。
 */
@SuppressLint("ViewConstructor")
class MediaView(context: Context, private val needAccess: () -> Unit) : PatchView(context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    private val titleText = context.threadText(13f, bold = true)
    private val subText = context.threadText(11f, color = Thread.DIM)
    private val icon = context.stitch(1.4f)
    private val buttons = Array(3) { RectF() }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = invalidate()
        override fun onPlaybackStateChanged(state: PlaybackState?) = invalidate()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list?.firstOrNull()) }

    private fun attach(c: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Badges.enabled(context)) runCatching {
            val component = ComponentName(context, BadgeListener::class.java)
            manager?.addOnActiveSessionsChangedListener(sessions, component)
            attach(manager?.getActiveSessions(component)?.firstOrNull())
        }
    }

    override fun onDetachedFromWindow() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        super.onDetachedFromWindow()
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        if (!Badges.enabled(context)) {
            canvas.drawText("再生中の曲", area.left, area.top + titleText.textSize, titleText)
            canvas.drawText("タップして表示を許可", area.left, area.top + titleText.textSize * 2.6f, subText)
            buttons.forEach { it.setEmpty() }
            return
        }
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "再生していません"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val t = TextUtils.ellipsize(title, titleText, area.width(), TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, area.left, area.top + titleText.textSize, titleText)
        if (artist.isNotEmpty()) {
            val a = TextUtils.ellipsize(artist, subText, area.width(), TextUtils.TruncateAt.END)
            canvas.drawText(a, 0, a.length, area.left, area.top + titleText.textSize + subText.textSize * 1.5f, subText)
        }
        // ボタン（前へ・再生／一時停止・次へ）
        val s = min(context.dp(26f), area.height() * 0.45f)
        val y = area.bottom - s
        for (i in 0 until 3) buttons[i].set(area.left + i * s * 1.8f, y, area.left + i * s * 1.8f + s, y + s)
        drawPrev(canvas, buttons[0])
        if (playing) drawPause(canvas, buttons[1]) else drawPlay(canvas, buttons[1])
        drawNext(canvas, buttons[2])
    }

    private fun drawPlay(canvas: Canvas, r: RectF) =
        canvas.drawPath(Path().apply { moveTo(r.left + r.width() * 0.25f, r.top); lineTo(r.right, r.centerY()); lineTo(r.left + r.width() * 0.25f, r.bottom); close() }, icon)

    private fun drawPause(canvas: Canvas, r: RectF) {
        canvas.drawLine(r.left + r.width() * 0.3f, r.top, r.left + r.width() * 0.3f, r.bottom, icon)
        canvas.drawLine(r.right - r.width() * 0.3f, r.top, r.right - r.width() * 0.3f, r.bottom, icon)
    }

    private fun drawPrev(canvas: Canvas, r: RectF) {
        canvas.drawPath(Path().apply { moveTo(r.right, r.top); lineTo(r.left + r.width() * 0.3f, r.centerY()); lineTo(r.right, r.bottom); close() }, icon)
        canvas.drawLine(r.left + r.width() * 0.15f, r.top, r.left + r.width() * 0.15f, r.bottom, icon)
    }

    private fun drawNext(canvas: Canvas, r: RectF) {
        canvas.drawPath(Path().apply { moveTo(r.left, r.top); lineTo(r.right - r.width() * 0.3f, r.centerY()); lineTo(r.left, r.bottom); close() }, icon)
        canvas.drawLine(r.right - r.width() * 0.15f, r.top, r.right - r.width() * 0.15f, r.bottom, icon)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!Badges.enabled(context)) { needAccess(); return true }
            val t = controller?.transportControls
            val hit = buttons.indexOfFirst { RectF(it).apply { inset(-context.dp(10f), -context.dp(10f)) }.contains(event.x, event.y) }
            when (hit) {
                0 -> t?.skipToPrevious()
                1 -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t?.pause() else t?.play()
                2 -> t?.skipToNext()
                else -> controller?.sessionActivity?.let { runCatching { it.send() } }
            }
            if (hit >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        super.onTouchEvent(event)
        return true
    }
}
