package io.github.yukichi006.fliphome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * アプリのアイコンを「白い糸で縫った刺繍」に作り替える（アイコンの描き方で「アプリのアイコンをなぞる」を選んだアプリ）。
 *
 * 1. アイコンを小さなマス目に描き、形を読み取る
 *    - 白黒用のアイコン（Android 13 以降のテーマアイコン用）があれば、その形をそのまま使う
 *    - なければカラーのアイコンを明るさで 2 つに分け、背景と違う部分（＝図柄）を形とみなす
 * 2. 形の輪郭をたどって線にする（マーチングスクエア法）
 * 3. 輪郭を、影つきの点線（ステッチ）で縫う
 *
 * 作った絵はメモリとファイルに覚えておき、次からはすぐに出す。
 */
object StitchIcons {
    /** 輪郭を読み取るマス目の細かさ。 */
    private const val GRID = 128

    /** 作り方を変えたら上げる（古い保存済みの絵を使わないようにする）。 */
    private const val RECIPE = 3

    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** 作っている途中の絵と、できたら知らせる先（同じ絵を何度も作らないように）。 */
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    /** 覚えていればすぐに返す。 */
    fun cached(app: AppEntry, size: Int): Bitmap? = memory.get(key(app, size))

    /** [app] の刺繍アイコン（一辺 [size] px）を作り、できたらメインスレッドで [done] に渡す。 */
    fun load(context: Context, app: AppEntry, size: Int, done: (Bitmap) -> Unit) {
        val key = key(app, size)
        memory.get(key)?.let { done(it); return }
        pending[key]?.let { it += done; return }
        pending[key] = mutableListOf(done)
        val appContext = context.applicationContext
        worker.execute {
            val file = File(appContext.cacheDir, "stitch/${key.hashCode().toUInt()}.png")
            // 保存済みの絵があればそれ、なければ作る。作れなければ、アプリ名の最初の 1 文字を縫う
            val bitmap = (if (file.exists()) runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() else null)
                ?: runCatching { render(appContext, app, size) }.getOrNull()?.also { b ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                ?: runCatching { sewLetter(app.label, size) }.getOrNull()
            if (bitmap != null) memory.put(key, bitmap)
            // うまくいかなくても「作っている途中」から外す（次に描くときに、また作り直せるように）
            main.post { pending.remove(key)?.let { list -> if (bitmap != null) list.forEach { it(bitmap) } } }
        }
    }

    // 糸の太さを変えたら作り直す
    private fun key(app: AppEntry, size: Int) = "${app.key}@${app.updatedAt}:$size:${StitchStyle.scale}:$RECIPE"

    private fun render(context: Context, app: AppEntry, size: Int): Bitmap {
        val icon = runCatching { app.info.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull()
        val contours = icon?.let { traceIcon(it) }.orEmpty()
        return if (contours.isNotEmpty()) sew(contours, size) else sewLetter(app.label, size)
    }

    // ------------------------------------------------------------------
    // 形を読み取る
    // ------------------------------------------------------------------

    /** アイコンの図柄の輪郭（マス目の座標の折れ線）を返す。読み取れなければ空。 */
    private fun traceIcon(icon: Drawable): List<FloatArray> {
        val mono = if (Build.VERSION.SDK_INT >= 33) (icon as? AdaptiveIconDrawable)?.monochrome else null
        if (mono != null) {
            val alpha = rasterAlpha(mono)
            val lines = contours(alpha, 0.5f)
            if (lines.isNotEmpty()) return lines
        }
        return traceColor(icon)
    }

    /** 白黒用のアイコンを描き、各マスの濃さ（0〜1）を返す。 */
    private fun rasterAlpha(d: Drawable): FloatArray {
        val bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        d.setBounds(0, 0, GRID, GRID)
        d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        d.draw(canvas)
        val px = IntArray(GRID * GRID)
        bitmap.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
        bitmap.recycle()
        return FloatArray(px.size) { Color.alpha(px[it]) / 255f }
    }

    /**
     * カラーのアイコンを明るさで「明るい／暗い」に分け、背景と違う側の輪郭を返す。
     * 図柄と背景の明るさがほとんど同じなら、アイコンの外形だけを返す。
     */
    private fun traceColor(icon: Drawable): List<FloatArray> {
        val bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, GRID, GRID)
        icon.draw(Canvas(bitmap))
        val px = IntArray(GRID * GRID)
        bitmap.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
        bitmap.recycle()

        val alpha = FloatArray(px.size) { Color.alpha(px[it]) / 255f }
        val lum = FloatArray(px.size) {
            val c = px[it]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }
        val inside = BooleanArray(px.size) { alpha[it] > 0.5f }
        val threshold = otsu(lum, inside) ?: return contours(alpha, 0.5f)

        // 背景は、アイコンの外形のすぐ内側に多い側とみなす
        var light = 0
        var dark = 0
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val i = y * GRID + x
            if (!inside[i]) continue
            val edge = (x == 0 || y == 0 || x == GRID - 1 || y == GRID - 1) ||
                !inside[i - 1] || !inside[i + 1] || !inside[i - GRID] || !inside[i + GRID]
            if (edge) if (lum[i] >= threshold) light++ else dark++
        }
        val bgLight = light >= dark
        // 外側は背景と同じ側の値で埋め、外形のところに線が出ないようにする
        val field = FloatArray(px.size) { i ->
            val l = if (inside[i]) lum[i] else if (bgLight) 1f else 0f
            if (bgLight) 1f - l else l
        }
        val smooth = blur(blur(field))
        val iso = if (bgLight) 1f - threshold else threshold
        val lines = contours(smooth, iso)
        return lines.ifEmpty { contours(alpha, 0.5f) }
    }

    /** 大津の方法で、明るさを 2 つに分ける境目を求める。明るさの差が小さすぎれば null。 */
    private fun otsu(values: FloatArray, use: BooleanArray): Float? {
        val hist = IntArray(64)
        var n = 0
        for (i in values.indices) if (use[i]) {
            hist[(values[i] * 63).toInt().coerceIn(0, 63)]++
            n++
        }
        if (n < 50) return null
        var sum = 0.0
        for (k in 0 until 64) sum += k * hist[k].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var bestK = 0
        for (k in 0 until 64) {
            wB += hist[k]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += k * hist[k].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestK = k }
        }
        // 2 つの群の平均の差が小さい（＝ほぼ単色）なら分けない
        var lowSum = 0.0; var lowN = 0; var highSum = 0.0; var highN = 0
        for (k in 0 until 64) if (k <= bestK) { lowSum += k * hist[k].toDouble(); lowN += hist[k] } else { highSum += k * hist[k].toDouble(); highN += hist[k] }
        if (lowN == 0 || highN == 0) return null
        val minority = min(lowN, highN).toFloat() / n
        if ((highSum / highN - lowSum / lowN) / 63 < 0.18 || minority < 0.03f) return null
        return (bestK + 0.5f) / 63f
    }

    private fun blur(f: FloatArray): FloatArray {
        val out = FloatArray(f.size)
        for (y in 0 until GRID) for (x in 0 until GRID) {
            var s = 0f
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until GRID && yy in 0 until GRID) { s += f[yy * GRID + xx]; c++ }
            }
            out[y * GRID + x] = s / c
        }
        return out
    }

    /**
     * マーチングスクエア法で、値が [iso] になる等高線をたどり、つながった折れ線にして返す。
     * 各折れ線は x, y を交互に並べた配列。閉じた線は始点と終点が同じ点になる。
     */
    private fun contours(f: FloatArray, iso: Float): List<FloatArray> {
        val n = GRID
        fun v(x: Int, y: Int) = f[y * n + x]
        // 辺の番号：横の辺 (x,y)-(x+1,y) は偶数、縦の辺 (x,y)-(x,y+1) は奇数
        fun hEdge(x: Int, y: Int) = (y * n + x) * 2
        fun vEdge(x: Int, y: Int) = (y * n + x) * 2 + 1
        val points = HashMap<Int, Long>()
        fun point(edge: Int): Long = points.getOrPut(edge) {
            val cell = edge / 2
            val x = cell % n
            val y = cell / n
            val (ax, ay, bx, by) = if (edge % 2 == 0) listOf(x, y, x + 1, y) else listOf(x, y, x, y + 1)
            val a = v(ax, ay)
            val b = v(bx, by)
            val t = if (abs(b - a) < 1e-6f) 0.5f else ((iso - a) / (b - a)).coerceIn(0f, 1f)
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t
            (java.lang.Float.floatToIntBits(px).toLong() shl 32) or (java.lang.Float.floatToIntBits(py).toLong() and 0xFFFFFFFFL)
        }

        val segA = ArrayList<Int>()
        val segB = ArrayList<Int>()
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val tl = v(x, y) >= iso
            val tr = v(x + 1, y) >= iso
            val br = v(x + 1, y + 1) >= iso
            val bl = v(x, y + 1) >= iso
            val code = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            val top = hEdge(x, y)
            val bottom = hEdge(x, y + 1)
            val left = vEdge(x, y)
            val right = vEdge(x + 1, y)
            fun seg(a: Int, b: Int) { segA += a; segB += b }
            when (code) {
                1, 14 -> seg(left, bottom)
                2, 13 -> seg(bottom, right)
                3, 12 -> seg(left, right)
                4, 11 -> seg(top, right)
                6, 9 -> seg(top, bottom)
                7, 8 -> seg(left, top)
                5, 10 -> {
                    // どちらにもつなげられる形。マスの中央の値で決める
                    val center = (v(x, y) + v(x + 1, y) + v(x + 1, y + 1) + v(x, y + 1)) / 4 >= iso
                    if ((code == 5) == center) { seg(left, top); seg(bottom, right) } else { seg(left, bottom); seg(top, right) }
                }
            }
        }

        // 辺 → その辺を通る線分（多くて 2 本）
        val byEdge = HashMap<Int, IntArray>()
        fun link(edge: Int, s: Int) {
            val cur = byEdge[edge]
            byEdge[edge] = if (cur == null) intArrayOf(s, -1) else cur.also { it[1] = s }
        }
        for (s in segA.indices) { link(segA[s], s); link(segB[s], s) }

        val used = BooleanArray(segA.size)
        val result = ArrayList<FloatArray>()
        for (start in segA.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = ArrayDeque<Int>()
            chain.add(segA[start]); chain.add(segB[start])
            // 後ろへ伸ばす
            fun extend(atEnd: Boolean) {
                while (true) {
                    val edge = if (atEnd) chain.last() else chain.first()
                    val next = byEdge[edge]?.firstOrNull { it >= 0 && !used[it] } ?: return
                    used[next] = true
                    val other = if (segA[next] == edge) segB[next] else segA[next]
                    if (atEnd) chain.addLast(other) else chain.addFirst(other)
                }
            }
            extend(true)
            extend(false)
            val arr = FloatArray(chain.size * 2)
            chain.forEachIndexed { i, e ->
                val p = point(e)
                arr[i * 2] = java.lang.Float.intBitsToFloat((p shr 32).toInt())
                arr[i * 2 + 1] = java.lang.Float.intBitsToFloat(p.toInt())
            }
            result += arr
        }
        return result
    }

    // ------------------------------------------------------------------
    // 縫う
    // ------------------------------------------------------------------

    private fun length(line: FloatArray): Float {
        var s = 0f
        for (i in 2 until line.size step 2) s += hypot(line[i] - line[i - 2], line[i + 1] - line[i - 1])
        return s
    }

    /** 輪郭をアイコンの大きさに合わせて並べ直し、ステッチで縫った絵にする。 */
    private fun sew(raw: List<FloatArray>, size: Int): Bitmap {
        // アイコンの外枠（角の丸い四角や丸の輪郭）は縫わない。中の絵があるときだけ外し、その分だけ絵を大きく縫う
        val inner = raw.filterNot { isFrame(it) }
        val source = if (inner.any { length(it) > GRID * 0.08f }) inner else raw
        // 短すぎる線（ノイズ）を除き、長い順に最大 20 本まで
        var lines = source.filter { length(it) > GRID * 0.08f }.sortedByDescending { length(it) }.take(20)
        if (lines.isEmpty()) lines = source.sortedByDescending { length(it) }.take(1)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (l in lines) for (i in l.indices step 2) {
            minX = min(minX, l[i]); maxX = max(maxX, l[i]); minY = min(minY, l[i + 1]); maxY = max(maxY, l[i + 1])
        }
        // 細い糸で、短い縫い目を詰めて縫う（細かい形も読み取れるように）
        val stroke = size * 0.03f
        // 図柄の大きさをそろえ、どのアプリも同じくらいの大きさの刺繍にする
        val box = size * 0.84f
        val scale = box / max(maxX - minX, maxY - minY).coerceAtLeast(1f)
        val ox = (size - (maxX - minX) * scale) / 2 - minX * scale
        val oy = (size - (maxY - minY) * scale) / 2 - minY * scale

        val path = Path()
        for (l in lines) {
            val pts = chaikin(l)
            val closed = abs(l[0] - l[l.size - 2]) < 0.01f && abs(l[1] - l[l.size - 1]) < 0.01f
            path.moveTo(pts[0] * scale + ox, pts[1] * scale + oy)
            for (i in 2 until pts.size step 2) path.lineTo(pts[i] * scale + ox, pts[i + 1] * scale + oy)
            if (closed) path.close()
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(path, stitchPaint(stroke, size * 0.055f, size * 0.04f))
        return bitmap
    }

    /**
     * アイコンの外枠の輪郭か（形を読み取ると、アイコンの土台の角の丸い四角や丸も線になる）。
     * マス目のほぼ端から端まで広がる、閉じた線を外枠とみなす。
     */
    private fun isFrame(l: FloatArray): Boolean {
        if (l.size < 8) return false
        val closed = abs(l[0] - l[l.size - 2]) < 0.01f && abs(l[1] - l[l.size - 1]) < 0.01f
        if (!closed) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in l.indices step 2) {
            minX = min(minX, l[i]); maxX = max(maxX, l[i]); minY = min(minY, l[i + 1]); maxY = max(maxY, l[i + 1])
        }
        return maxX - minX > GRID * 0.82f && maxY - minY > GRID * 0.82f
    }

    /** 角を少し丸める（チャイキン法を 1 回）。 */
    private fun chaikin(l: FloatArray): FloatArray {
        val n = l.size / 2
        if (n < 3) return l
        val out = FloatArray((n - 1) * 4 + 2)
        var k = 0
        out[k++] = l[0]; out[k++] = l[1]
        for (i in 0 until n - 1) {
            val x0 = l[i * 2]; val y0 = l[i * 2 + 1]; val x1 = l[i * 2 + 2]; val y1 = l[i * 2 + 3]
            if (i > 0) { out[k++] = 0.75f * x0 + 0.25f * x1; out[k++] = 0.75f * y0 + 0.25f * y1 }
            if (i < n - 2) { out[k++] = 0.25f * x0 + 0.75f * x1; out[k++] = 0.25f * y0 + 0.75f * y1 }
        }
        out[k++] = l[l.size - 2]; out[k++] = l[l.size - 1]
        return out.copyOf(k)
    }

    /** 形が読み取れないアイコンは、アプリ名の最初の 1 文字を縫う。 */
    private fun sewLetter(label: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val letter = label.trim().take(1).uppercase().ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.62f
            textAlign = Paint.Align.CENTER
        }
        val path = Path()
        val bounds = android.graphics.Rect()
        text.getTextBounds(letter, 0, letter.length, bounds)
        text.getTextPath(letter, 0, letter.length, size / 2f, size / 2f - bounds.exactCenterY(), path)
        val stitch = stitchPaint(size * 0.04f, size * 0.08f, size * 0.06f)
        // 外枠の丸いステッチ
        val r = size * 0.44f
        canvas.drawStitchedRoundRect(RectF(size / 2f - r, size / 2f - r, size / 2f + r, size / 2f + r), r * 0.45f, stitch)
        canvas.drawPath(path, stitch)
        return bitmap
    }
}
