package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 天気を取ってくる係。Open-Meteo（https://open-meteo.com/ ・無料で鍵の要らない気象データ）だけに、HTTPS でつなぐ。
 * 送るのは、選んだ地点の緯度・経度（と、地点を探すときの地名）だけ。位置情報の権限は使わない。
 * 取った天気は地点ごとに覚えておき、30 分たつまでは取り直さない。
 */
object WeatherRepo {
    class Now(val temp: Double, val code: Int, val max: Double?, val min: Double?, val at: Long)
    class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    private const val FRESH_MS = 30 * 60_000L
    private const val MAX_BYTES = 256 * 1024
    private val cache = mutableMapOf<String, Now>()
    private val loading = mutableSetOf<String>()
    private val listeners = mutableSetOf<() -> Unit>()

    private fun key(lat: Double, lon: Double) = "%.3f,%.3f".format(java.util.Locale.US, lat, lon)

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun get(lat: Double, lon: Double): Now? = cache[key(lat, lon)]

    /** 古ければ（[force] なら必ず）取り直す。終わったらリスナーに知らせる。 */
    fun refresh(lat: Double, lon: Double, force: Boolean = false) {
        val k = key(lat, lon)
        val old = cache[k]
        if (!force && old != null && SystemClock.elapsedRealtime() - old.at < FRESH_MS) return
        if (!loading.add(k)) return
        ioThread.execute {
            val now = runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, lat, lon) +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
                val o = JSONObject(fetch(url))
                val cur = o.getJSONObject("current")
                val daily = o.optJSONObject("daily")
                Now(
                    cur.getDouble("temperature_2m"), cur.getInt("weather_code"),
                    daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeIf { !it.isNaN() },
                    daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeIf { !it.isNaN() },
                    SystemClock.elapsedRealtime(),
                )
            }.getOrNull()
            mainHandler.post {
                loading.remove(k)
                if (now != null) cache[k] = now
                listeners.toList().forEach { it() }
            }
        }
    }

    /** 地名で地点を探す（裏のスレッドで動かし、結果をメインスレッドで渡す）。 */
    fun search(query: String, done: (List<Place>) -> Unit) {
        ioThread.execute {
            val list = runCatching {
                val q = URLEncoder.encode(query.trim(), "UTF-8")
                val o = JSONObject(fetch("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=ja&format=json"))
                val arr = o.optJSONArray("results")
                (0 until (arr?.length() ?: 0)).map { i ->
                    val r = arr!!.getJSONObject(i)
                    Place(
                        r.getString("name"),
                        listOfNotNull(r.optString("admin1").takeIf { it.isNotEmpty() }, r.optString("country").takeIf { it.isNotEmpty() }).joinToString("・"),
                        r.getDouble("latitude"), r.getDouble("longitude"),
                    )
                }
            }.getOrDefault(emptyList())
            mainHandler.post { done(list) }
        }
    }

    /** HTTPS でだけ取りに行く。大きすぎる答えは読まない。 */
    private fun fetch(url: String): String {
        require(url.startsWith("https://")) { "HTTPS だけを使う" }
        val c = URL(url).openConnection() as HttpsURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = false
        try {
            require(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= MAX_BYTES) { "答えが大きすぎる" }
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** 気象コード（WMO）を、晴れ・くもりなどの種類と名前に分ける。 */
    fun kind(code: Int): Pair<String, String> = when (code) {
        0 -> "sun" to "晴れ"
        1, 2 -> "partly" to "晴れ時々くもり"
        3 -> "cloud" to "くもり"
        45, 48 -> "fog" to "霧"
        in 51..67, in 80..82 -> "rain" to "雨"
        in 71..77, 85, 86 -> "snow" to "雪"
        in 95..99 -> "thunder" to "雷"
        else -> "cloud" to "くもり"
    }

    /** 天気の刺繍アイコン（48×48 の線画）。 */
    private val ICONS = mapOf(
        "sun" to "O24,24,8 M24 6v5 M24 37v5 M6 24h5 M37 24h5 M11 11l4 4 M33 33l4 4 M37 11l-4 4 M15 33l-4 4",
        "partly" to "O17,16,6 M17 4v3 M5 16h3 M8.5 7.5l2 2 M25.5 7.5l-2 2 M16 39h20c4 0 7-3 7-7s-3-7-7-7c-1-4-5-6-9-5c-3 1-5 4-5 7c-4 0-6 3-6 6s3 6 6 6z",
        "cloud" to "M14 36h22c5 0 8-4 8-8s-3-8-8-8c-1-5-6-8-11-7c-4 1-7 5-7 9c-5 0-8 3-8 7s3 7 8 7z",
        "rain" to "M14 28h22c5 0 8-4 8-8s-3-8-8-8c-1-5-6-8-11-7c-4 1-7 5-7 9c-5 0-8 3-8 7s3 7 8 7z M16 34l-3 7 M25 34l-3 7 M34 34l-3 7",
        "snow" to "M14 28h22c5 0 8-4 8-8s-3-8-8-8c-1-5-6-8-11-7c-4 1-7 5-7 9c-5 0-8 3-8 7s3 7 8 7z O15,37,1.5 O24,40,1.5 O33,37,1.5",
        "thunder" to "M14 28h22c5 0 8-4 8-8s-3-8-8-8c-1-5-6-8-11-7c-4 1-7 5-7 9c-5 0-8 3-8 7s3 7 8 7z M26 30l-5 7h6l-4 7",
        "fog" to "M8 16h32 M12 24h28 M8 32h32 M14 40h20",
    )

    fun icon(kind: String): Path = MiniPath.parse(ICONS[kind] ?: ICONS.getValue("cloud"))
}

/**
 * 天気の部品。上に天気の刺繍アイコンと名前、真ん中に 13 個の目盛りの温度計（-10〜40℃）、下に今の気温。
 * 目盛りは今の気温のところまで白い糸で埋め、いちばん上の目盛りは × に縫って目立たせる。
 * [showMinMax] なら、温度計の横に今日の最高・最低の印を付ける。タップで [onTap]。
 */
@SuppressLint("ViewConstructor")
class WeatherView(
    context: Context,
    private val city: String?,
    private val lat: Double?,
    private val lon: Double?,
    private val showMinMax: Boolean,
    private val onTap: () -> Unit,
) : View(context) {
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW) }
    private val segOff = context.stitch(0.8f, Thread.FAINT)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.BASE
        strokeWidth = context.dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val big = context.threadText(18f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private val small = context.threadText(9f, color = Thread.DIM)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Thread.DIM; strokeWidth = context.dp(1.2f); strokeCap = Paint.Cap.ROUND }
    private val name = context.threadText(9f).apply { textAlign = Paint.Align.CENTER }
    private val listener: () -> Unit = { invalidate() }

    init {
        setOnClickListener { onTap() }
        contentDescription = "天気"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WeatherRepo.addListener(listener)
        if (lat != null && lon != null) WeatherRepo.refresh(lat, lon)
    }

    override fun onDetachedFromWindow() {
        WeatherRepo.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // ホーム画面に戻ってきたとき、古ければ取り直す
        if (visibility == VISIBLE && lat != null && lon != null) WeatherRepo.refresh(lat, lon)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dp(6f)
        val now = if (lat != null && lon != null) WeatherRepo.get(lat, lon) else null
        if (lat == null || lon == null) {
            name.textSize = context.sp(10f)
            // 細い部品でも見切れないように、文字を小さくして入れる
            val w = name.measureText("選んでください")
            if (w > width - pad * 2) name.textSize = (name.textSize * (width - pad * 2) / w).coerceAtLeast(context.sp(6f))
            canvas.drawText("地点を", width / 2f, height / 2f - name.textSize * 0.2f, name)
            canvas.drawText("選んでください", width / 2f, height / 2f + name.textSize * 1.2f, name)
            return
        }
        val (kind, label) = now?.let { WeatherRepo.kind(it.code) } ?: ("cloud" to (city ?: ""))
        // アイコン
        val iconS = min(width - pad * 2, context.dp(30f))
        val path = WeatherRepo.icon(kind)
        path.transform(Matrix().apply { setScale(iconS / 48f, iconS / 48f); postTranslate((width - iconS) / 2f, pad) })
        canvas.drawPath(path, stitchPaint(iconS * 0.06f, iconS * 0.11f, iconS * 0.08f))
        // 天気の文字（晴れ時々くもり など）は、幅に入るときだけ書く。入らなければ書かずに、温度計を大きくする
        val showLabel = label.isNotEmpty() && name.measureText(label) <= width - pad * 2
        if (showLabel) canvas.drawText(label, width / 2f, pad + iconS + name.textSize * 1.3f, name)
        // 温度計
        val top = pad + iconS + if (showLabel) name.textSize * 2.2f else context.dp(8f)
        val bottom = height - pad - big.textSize * 1.5f
        val n = SEGMENTS
        val gap = context.dp(3f)
        val segH = ((bottom - top - gap * (n - 1)) / n).coerceAtLeast(context.dp(2f))
        val segW = min(context.dp(22f), width * 0.36f)
        // 最高・最低の数字の分だけ、温度計を左へ寄せる（右の数字が見切れないように）
        val side = if (showMinMax) context.dp(6f) + small.measureText("00°") else 0f
        val cx = (width / 2f - side / 2).coerceAtLeast(segW / 2 + pad)
        fun level(t: Double) = ((t - LOW) / (HIGH - LOW) * n).roundToInt().coerceIn(1, n)
        val lit = now?.let { level(it.temp) } ?: 0
        for (i in 0 until n) {
            val y1 = bottom - i * (segH + gap)
            val r = RectF(cx - segW / 2, y1 - segH, cx + segW / 2, y1)
            if (i < lit) {
                seg.color = Thread.COLOR
                canvas.drawRoundRect(r, context.dp(2f), context.dp(2f), seg)
                if (i == lit - 1) {
                    // いちばん上の目盛りは × に縫って目立たせる
                    canvas.drawLine(r.centerX() - segH * 0.35f, r.top + segH * 0.15f, r.centerX() + segH * 0.35f, r.bottom - segH * 0.15f, cross)
                    canvas.drawLine(r.centerX() + segH * 0.35f, r.top + segH * 0.15f, r.centerX() - segH * 0.35f, r.bottom - segH * 0.15f, cross)
                }
            } else {
                canvas.drawRoundRect(r, context.dp(2f), context.dp(2f), segOff)
            }
        }
        if (showMinMax && now != null) {
            for (t in listOfNotNull(now.max, now.min)) {
                val i = level(t) - 1
                val y = bottom - i * (segH + gap) - segH / 2
                // 目盛りの横に短い線を引き、その先に温度を書く（「-22」だと氷点下に見えるので、符号は付けない）
                val x0 = cx + segW / 2 + context.dp(2f)
                canvas.drawLine(x0, y, x0 + context.dp(4f), y, tick)
                canvas.drawText("${t.roundToInt()}°", x0 + context.dp(6f), y + small.textSize * 0.35f, small)
            }
        }
        canvas.drawText(now?.let { "${it.temp.roundToInt()}°" } ?: "--°", width / 2f, height - pad - big.textSize * 0.25f, big)
    }

    companion object {
        private const val SEGMENTS = 13
        private const val LOW = -10.0
        private const val HIGH = 40.0
    }
}

/** 地点を地名で探して選ぶ欄。入力が止まったら探し、候補をタップで [onPick]。 */
@SuppressLint("ViewConstructor")
class PlacePicker(context: Context, private val onPick: (WeatherRepo.Place) -> Unit) : LinearLayout(context) {
    private var results: List<WeatherRepo.Place> = emptyList()
    private val status = TextView(context).apply {
        setTextColor(Thread.DIM)
        textSize = 13f
        setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(4f))
        text = "地名を入れてください（例：船橋）"
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = results[position]
            return ((convertView as? TextView) ?: TextView(context).apply {
                setTextColor(Thread.COLOR)
                textSize = 16f
                setPadding(dpi(10f), dpi(12f), dpi(10f), dpi(12f))
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            }).also { it.text = "${p.name}　${p.detail}" }
        }
    }
    private val searchNow = Runnable { run() }
    private val edit = EditText(context).apply {
        background = null
        setTextColor(Thread.COLOR)
        setHintTextColor(Thread.DIM)
        hint = "地名で探す"
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dpi(8f), 0, dpi(8f), 0)
    }

    init {
        orientation = VERTICAL
        addView(PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
            addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dpi(60f)).apply { topMargin = dpi(6f) })
        addView(status)
        addView(ListView(context).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            cacheColorHint = 0
            adapter = this@PlacePicker.adapter
            setOnItemClickListener { _, _, pos, _ -> onPick(results[pos]) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                removeCallbacks(searchNow)
                postDelayed(searchNow, 500)
            }
        })
        edit.setOnEditorActionListener { _, _, _ -> run(); true }
    }

    private fun run() {
        val q = edit.text.toString().trim()
        if (q.length < 1) return
        status.text = "探しています…"
        WeatherRepo.search(q) { list ->
            if (edit.text.toString().trim() != q) return@search
            results = list
            status.text = if (list.isEmpty()) "見つかりませんでした（つながっていないかもしれません）" else "候補をタップして選ぶ"
            adapter.notifyDataSetChanged()
        }
    }
}
