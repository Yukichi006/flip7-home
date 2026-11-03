package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * ホーム画面のマス目に置く部品の種類。[w], [h] は置いたときの最初の大きさ（マスの数）。
 * [addable] が false のものは「部品」の一覧に出さない（アプリは「アプリ」タブから置く）。
 */
enum class ModuleType(val label: String, val note: String, val w: Int, val h: Int, val resizable: Boolean, val addable: Boolean = true) {
    APP("アプリ", "", 1, 1, false, addable = false),
    FOLDER("巾着", "複数のアプリをまとめるフォルダ", 1, 1, false),
    CLOCK("時計と日付", "大きな時計と日付", 5, 2, true),
    AGENDA("今日の予定", "今日これからの予定", 3, 2, true),
    BATTERY("電池", "本体と Bluetooth 機器の残量", 2, 2, true),
    SLIDER("フェーダー", "音量・明るさ・アプリを開くバネ", 1, 2, true),
    TIME("時刻", "時刻だけ（秒も出せる）", 3, 1, true),
    DATE("日付", "日付だけ", 3, 1, true),
    CALENDAR("月のカレンダー", "今月を刺繍の点で。スワイプに動作を割り当てられる", 3, 3, true),
    TITLE("見出し", "ページの名前など", 3, 1, true),
    MEDIA("再生中の曲", "曲名と再生ボタン", 3, 1, true),
    WEATHER("天気", "天気と 13 目盛りの温度計（地点を選ぶ）", 1, 3, true),
}

/**
 * マス目に置いた部品 1 つ。[col], [row] は左上のマス、[w], [h] は占めるマスの数。
 * [props] は部品ごとの設定（アプリのキー、下に引いて開くアプリ、フォルダの名前と中身など）。
 */
data class Module(
    val id: Long,
    val type: ModuleType,
    val col: Int,
    val row: Int,
    val w: Int,
    val h: Int,
    val props: Map<String, String> = emptyMap(),
) {
    fun covers(c: Int, r: Int) = c in col until col + w && r in row until row + h

    fun overlaps(o: Module) = col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h

    operator fun get(key: String): String? = props[key]

    fun with(key: String, value: String?): Module = copy(props = if (value == null) props - key else props + (key to value))

    /** フォルダの中のアプリのキー。 */
    val folderApps: List<String> get() = props[P_APPS]?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type.name); put("col", col); put("row", row); put("w", w); put("h", h)
        put("props", JSONObject(props))
    }

    companion object {
        const val P_APP = "app"
        const val P_LONG_APP = "longApp"
        const val P_NAME = "name"
        const val P_APPS = "apps"
        const val P_ALIGN = "align"
        const val P_SECONDS = "seconds"
        const val P_STYLE = "style"
        const val P_TEXT = "text"
        const val P_MONDAY = "monday"
        const val P_FUNCTION = "function"
        const val P_TOP_APP = "topApp"
        const val P_BOTTOM_APP = "bottomApp"
        const val P_CITY = "city"
        const val P_LAT = "lat"
        const val P_LON = "lon"
        const val P_MINMAX = "minmax"
        const val P_TAP_APP = "tapApp"

        /** スワイプの向き（上下左右）ごとの動作のキー。値は "shade"・"drawer"・"app:<アプリのキー>"。 */
        fun gestureKey(dir: String) = "g.$dir"

        fun fromJson(o: JSONObject): Module? = runCatching {
            val p = o.optJSONObject("props")
            val props = p?.keys()?.asSequence()?.associateWith { p.getString(it) }.orEmpty()
            Module(o.getLong("id"), ModuleType.valueOf(o.getString("type")), o.getInt("col"), o.getInt("row"), o.getInt("w"), o.getInt("h"), props)
        }.getOrNull()
    }
}

/** ホーム画面 1 ページ。 */
data class Page(val modules: List<Module>)

/**
 * ホーム画面全体の設定：マス目の大きさ（[cols] 列 × [rows] 行）、ページ、下段のポケットを出すか、その中身。
 */
data class Home(
    val cols: Int,
    val rows: Int,
    val pages: List<Page>,
    val pocket: Boolean,
    val dock: List<String?>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cols", cols); put("rows", rows); put("pocket", pocket)
        put("dock", JSONArray(dock.map { it ?: "" }))
        put("pages", JSONArray(pages.map { p -> JSONObject().put("modules", JSONArray(p.modules.map { it.toJson() })) }))
    }

    companion object {
        fun fromJson(o: JSONObject): Home {
            val pages = o.getJSONArray("pages").let { arr ->
                (0 until arr.length()).map { i ->
                    val ms = arr.getJSONObject(i).getJSONArray("modules")
                    Page((0 until ms.length()).mapNotNull { Module.fromJson(ms.getJSONObject(it)) })
                }
            }
            val dock = o.optJSONArray("dock")?.let { a -> (0 until a.length()).map { a.getString(it).ifEmpty { null } } }.orEmpty()
            return Home(
                o.optInt("cols", 5).coerceIn(LayoutStore.MIN_COLS, LayoutStore.MAX_COLS),
                o.optInt("rows", 8).coerceIn(LayoutStore.MIN_ROWS, LayoutStore.MAX_ROWS),
                pages.ifEmpty { listOf(Page(emptyList())) },
                o.optBoolean("pocket", true),
                List(LayoutStore.DOCK_SLOTS) { dock.getOrNull(it) },
            )
        }
    }
}

/**
 * ホーム画面の配置と、アプリごとの設定（名前の付け替え・アイコンのジャンル）を保存する。
 * 配置は JSON にまとめて 1 つの値として保存する。
 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var home: Home
        get() = prefs.getString(KEY_HOME, null)?.let { runCatching { Home.fromJson(JSONObject(it)) }.getOrNull() }
            ?: migrate().also { home = it }
        set(value) = prefs.edit().putString(KEY_HOME, value.toJson().toString()).apply()

    fun modules(page: Int): List<Module> = home.pages.getOrNull(page)?.modules.orEmpty()

    private fun updatePage(page: Int, change: (List<Module>) -> List<Module>) {
        val h = home
        if (page !in h.pages.indices) return
        home = h.copy(pages = h.pages.mapIndexed { i, p -> if (i == page) Page(change(p.modules)) else p })
    }

    fun newId(): Long = prefs.getLong(KEY_SEQ, 100L).also { prefs.edit().putLong(KEY_SEQ, it + 1).apply() }

    /** [type] の部品を、[page] の空いているいちばん上・左のマスに置く。置けたら置いた部品を返す。 */
    fun add(page: Int, type: ModuleType, props: Map<String, String> = emptyMap()): Module? {
        val h = home
        val list = modules(page)
        for (r in 0..h.rows - type.h) for (c in 0..h.cols - type.w) {
            val m = Module(0, type, c, r, type.w, type.h, props)
            if (fits(h, list, m)) return m.copy(id = newId()).also { new -> updatePage(page) { it + new } }
        }
        return null
    }

    /**
     * [type] の部品を [page] の [col], [row] のマスに置く。最初の大きさが入らなければ、入るところまで小さくする。
     */
    fun placeAt(page: Int, type: ModuleType, col: Int, row: Int, props: Map<String, String> = emptyMap()): Module? {
        val h = home
        val list = modules(page)
        for (hh in type.h downTo 1) for (ww in type.w downTo 1) {
            val m = Module(0, type, col, row, ww, hh, props)
            if (fits(h, list, m)) return m.copy(id = newId()).also { new -> updatePage(page) { it + new } }
        }
        return null
    }

    fun replace(page: Int, new: Module) = updatePage(page) { list -> list.map { if (it.id == new.id) new else it } }

    fun remove(page: Int, module: Module) = updatePage(page) { list -> list.filter { it.id != module.id } }

    /** [key] のアプリのパッドがあるページ（なければ null）。 */
    fun pageOfApp(key: String): Int? =
        home.pages.indexOfFirst { p -> p.modules.any { it.type == ModuleType.APP && it[Module.P_APP] == key } }.takeIf { it >= 0 }

    fun removeApp(key: String) {
        val h = home
        home = h.copy(pages = h.pages.map { p -> Page(p.modules.filterNot { it.type == ModuleType.APP && it[Module.P_APP] == key }) })
    }

    /** 部品を、大きさはそのままで別のページの空いている場所へ移す。空きがなければ false。 */
    fun moveToPage(from: Int, m: Module, to: Int): Boolean {
        val h = home
        val list = modules(to)
        for (r in 0..h.rows - m.h) for (c in 0..h.cols - m.w) {
            val moved = m.copy(col = c, row = r)
            if (fits(h, list, moved)) {
                remove(from, m)
                updatePage(to) { it + moved }
                return true
            }
        }
        return false
    }

    fun addPage(after: Int): Int {
        val h = home
        val at = (after + 1).coerceIn(0, h.pages.size)
        home = h.copy(pages = h.pages.toMutableList().apply { add(at, Page(emptyList())) })
        return at
    }

    fun removePage(page: Int) {
        val h = home
        if (h.pages.size <= 1) return
        home = h.copy(pages = h.pages.filterIndexed { i, _ -> i != page })
    }

    fun setGrid(cols: Int, rows: Int) { home = home.copy(cols = cols, rows = rows) }

    fun setPocket(on: Boolean) { home = home.copy(pocket = on) }

    fun setDockSlot(index: Int, key: String?) {
        val list = home.dock.toMutableList()
        // 同じアプリが 2 つの枠に入らないようにする
        if (key != null) list.replaceAll { if (it == key) null else it }
        list[index] = key
        home = home.copy(dock = list)
    }

    /** 裏地をめくり始める側。 */
    var peel: PeelFrom
        get() = PeelFrom.of(prefs.getString("peel", null))
        set(value) = prefs.edit().putString("peel", value.key).apply()

    /** 糸の太さ（1 がいちばん太い）。 */
    var thread: Float
        get() = prefs.getFloat("thread", 1f)
        set(value) = prefs.edit().putFloat("thread", value).apply()

    // ------------------------------------------------------------------
    // 片手の画面（左に情報の列、右にめくれるアプリのパネル）
    // ------------------------------------------------------------------

    /** 片手の画面にしているか（false ならマス目の画面）。最初は片手の画面。 */
    var thumbMode: Boolean
        get() = prefs.getBoolean("thumbMode", true)
        set(value) = prefs.edit().putBoolean("thumbMode", value).apply()

    /** アプリのパネルをめくり始める側（最初は左下から右上へ）。 */
    var bookPeel: PeelFrom
        get() = PeelFrom.entries.firstOrNull { it.key == prefs.getString("bookPeel", null) } ?: PeelFrom.BOTTOM_LEFT
        set(value) = prefs.edit().putString("bookPeel", value.key).apply()

    var thumb: Thumb
        get() = prefs.getString(KEY_THUMB, null)?.let { runCatching { Thumb.fromJson(JSONObject(it)) }.getOrNull() }
            ?: thumbFromHome().also { thumb = it }
        set(value) = prefs.edit().putString(KEY_THUMB, value.toJson().toString()).apply()

    /** 左の情報の列（1 列 × [Thumb.LEFT_ROWS] 行）。 */
    fun leftBoard() = Board(1, Thumb.LEFT_ROWS, { thumb.left.modules }, { list -> thumb = thumb.copy(left = Page(list)) }, ::newId)

    /** 右のパネルの [index] 枚目の布（[Thumb.COLS] 列 × 行）。 */
    fun sheetBoard(index: Int) = Board(Thumb.COLS, thumb.rows, { thumb.sheets.getOrNull(index)?.modules.orEmpty() }, { list ->
        val t = thumb
        if (index in t.sheets.indices) thumb = t.copy(sheets = t.sheets.mapIndexed { i, p -> if (i == index) Page(list) else p })
    }, ::newId)

    /** マス目の画面の [page] ページ目。 */
    fun gridBoard(page: Int) = Board(home.cols, home.rows, { modules(page) }, { list -> updatePage(page) { list } }, ::newId)

    fun addSheet(after: Int): Int {
        val t = thumb
        val at = (after + 1).coerceIn(0, t.sheets.size)
        thumb = t.copy(sheets = t.sheets.toMutableList().apply { add(at, Page(emptyList())) })
        return at
    }

    fun removeSheet(index: Int) {
        val t = thumb
        if (t.sheets.size <= 1) return
        thumb = t.copy(sheets = t.sheets.filterIndexed { i, _ -> i != index })
    }

    /** 片手の画面を、今のマス目の画面から作る：情報は時計・カレンダー・天気・電池、アプリは右のパネルへ順に。 */
    private fun thumbFromHome(): Thumb {
        val h = home
        val weather = h.pages.flatMap { it.modules }.firstOrNull { it.type == ModuleType.WEATHER }?.props.orEmpty()
        val left = listOf(
            Module(newId(), ModuleType.CLOCK, 0, 0, 1, 1),
            Module(newId(), ModuleType.CALENDAR, 0, 1, 1, 1),
            Module(newId(), ModuleType.WEATHER, 0, 2, 1, 1, weather),
            Module(newId(), ModuleType.BATTERY, 0, 3, 1, 1),
        )
        val apps = (h.pages.flatMap { p -> p.modules.filter { it.type == ModuleType.APP || it.type == ModuleType.FOLDER }.sortedWith(compareBy({ it.row }, { it.col })) } +
            h.dock.filterNotNull().map { Module(0, ModuleType.APP, 0, 0, 1, 1, mapOf(Module.P_APP to it)) })
            .distinctBy { it[Module.P_APP] ?: "folder:${it.id}" }
        val rows = Thumb.DEFAULT_ROWS
        val perSheet = Thumb.COLS * rows
        val sheets = apps.chunked(perSheet).map { chunk ->
            // アプリが少ない布は、親指の届く下の段から詰める
            val skip = rows - (chunk.size + Thumb.COLS - 1) / Thumb.COLS
            Page(chunk.mapIndexed { i, m -> m.copy(id = newId(), col = i % Thumb.COLS, row = skip + i / Thumb.COLS, w = 1, h = 1) })
        }.ifEmpty { listOf(Page(emptyList())) }
        return Thumb(Page(left), sheets, rows)
    }

    /** アプリの名前の付け替え。付け替えていなければ null。 */
    fun nameOf(key: String): String? = prefs.getString("name:$key", null)

    fun setName(key: String, name: String?) =
        prefs.edit().apply { if (name.isNullOrBlank()) remove("name:$key") else putString("name:$key", name.trim()) }.apply()

    /** アプリのアイコンとして選び直したジャンル。選んでいなければ null（自動で推し量る）。 */
    fun genreOf(key: String): Genre? = prefs.getString("genre:$key", null)?.let { runCatching { Genre.valueOf(it) }.getOrNull() }

    fun setGenre(key: String, genre: Genre?) =
        prefs.edit().apply { if (genre == null) remove("genre:$key") else putString("genre:$key", genre.name) }.apply()

    /** 0.2 までの保存形式（1 ページ・4 列）から読み替える。何もなければ最初の配置。 */
    private fun migrate(): Home {
        val old = prefs.getString("layout", null)
        val oldDock = prefs.getString("dock", null)?.split("\n")?.map { it.ifEmpty { null } }
        val dock = List(DOCK_SLOTS) { oldDock?.getOrNull(it) }
        if (old == null) {
            return Home(5, 8, listOf(Page(listOf(
                Module(1, ModuleType.CLOCK, 0, 0, 5, 2),
                Module(2, ModuleType.AGENDA, 0, 2, 3, 2),
                Module(3, ModuleType.BATTERY, 3, 2, 2, 2),
            ))), pocket = true, dock = dock)
        }
        val modules = old.lines().mapNotNull { line ->
            runCatching {
                val p = line.split(",", limit = 6)
                val type = ModuleType.valueOf(p[0])
                val props = if (type == ModuleType.APP) mapOf(Module.P_APP to p.getOrElse(5) { "" }) else emptyMap()
                Module(newId(), type, p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), props)
            }.getOrNull()
        }
        return Home(4, prefs.getInt("rows", 7).coerceIn(MIN_ROWS, MAX_ROWS), listOf(Page(modules)), pocket = true, dock = dock)
    }

    companion object {
        const val DOCK_SLOTS = 4
        const val MIN_COLS = 3
        const val MAX_COLS = 6
        const val MIN_ROWS = 4
        const val MAX_ROWS = 12
        private const val KEY_HOME = "home2"
        private const val KEY_THUMB = "thumb"
        private const val KEY_SEQ = "seq"

        /** [m] がマス目の中に収まり、[ignore] 以外のどの部品とも重ならないか。 */
        fun fits(home: Home, list: List<Module>, m: Module, ignore: Module? = null): Boolean =
            m.col >= 0 && m.row >= 0 && m.w >= 1 && m.h >= 1 &&
                m.col + m.w <= home.cols && m.row + m.h <= home.rows &&
                list.none { it.id != ignore?.id && it.overlaps(m) }
    }
}

/** 片手の画面の配置：左の情報の列（[left]）と、右のパネルの布（[sheets]、1 枚ずつめくる）。 */
data class Thumb(val left: Page, val sheets: List<Page>, val rows: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rows", rows)
        put("left", JSONObject().put("modules", JSONArray(left.modules.map { it.toJson() })))
        put("sheets", JSONArray(sheets.map { p -> JSONObject().put("modules", JSONArray(p.modules.map { it.toJson() })) }))
    }

    companion object {
        const val COLS = 3
        const val LEFT_ROWS = 4
        const val DEFAULT_ROWS = 8

        private fun page(o: JSONObject?): Page {
            val ms = o?.optJSONArray("modules") ?: return Page(emptyList())
            return Page((0 until ms.length()).mapNotNull { Module.fromJson(ms.getJSONObject(it)) })
        }

        fun fromJson(o: JSONObject): Thumb {
            val sheets = o.optJSONArray("sheets")?.let { a -> (0 until a.length()).map { page(a.getJSONObject(it)) } }.orEmpty()
            return Thumb(page(o.optJSONObject("left")), sheets.ifEmpty { listOf(Page(emptyList())) },
                o.optInt("rows", DEFAULT_ROWS).coerceIn(LayoutStore.MIN_ROWS, LayoutStore.MAX_ROWS))
        }
    }
}

/**
 * 部品を置く盤（マス目の画面の 1 ページ、片手の画面の左の列、右のパネルの 1 枚）。
 * どの盤でも、置く・置き換える・外すを同じやり方でできるようにする。
 */
class Board(
    val cols: Int,
    val rows: Int,
    private val read: () -> List<Module>,
    private val write: (List<Module>) -> Unit,
    private val newId: () -> Long,
) {
    fun modules(): List<Module> = read()

    private fun fits(list: List<Module>, m: Module) =
        m.col >= 0 && m.row >= 0 && m.col + m.w <= cols && m.row + m.h <= rows && list.none { it.id != m.id && it.overlaps(m) }

    /** [type] の部品を [col], [row] に置く。最初の大きさが入らなければ、入るところまで小さくする。 */
    fun placeAt(type: ModuleType, col: Int, row: Int, props: Map<String, String> = emptyMap()): Module? {
        val list = read()
        for (hh in minOf(type.h, rows) downTo 1) for (ww in minOf(type.w, cols) downTo 1) {
            val m = Module(-1, type, col, row, ww, hh, props)
            if (fits(list, m)) return m.copy(id = newId()).also { write(list + it) }
        }
        return null
    }

    /** [type] の部品を、空いているいちばん上・左に置く。 */
    fun add(type: ModuleType, props: Map<String, String> = emptyMap()): Module? {
        val list = read()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (list.none { it.covers(c, r) }) placeAt(type, c, r, props)?.let { return it }
        }
        return null
    }

    fun replace(new: Module) = write(read().map { if (it.id == new.id) new else it })

    fun remove(m: Module) = write(read().filter { it.id != m.id })
}

/**
 * 部品をマス目に並べる入れ物。[cols] 列 × [rows] 行で、マスの大きさは入れ物いっぱいに広げて決める。
 * はみ出した部品は表示しない。
 *
 * 編集モード（[editing]）では、すべてのマスに仕立て屋のチャコペンのような薄い縫い線を引き、
 * - 空いたマスをタップ → [onEmptyTap]
 * - 部品をタップ → [onModuleTap]、右上の × → [onRemove]
 * - 部品をドラッグ → 空いている場所へ移動、右下のつまみをドラッグ → 大きさを変える（どちらも [onChange]）
 */
class ModuleGrid(context: Context) : ViewGroup(context) {
    class Params(val module: Module) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    var onEmptyTap: ((col: Int, row: Int) -> Unit)? = null
    var onModuleTap: ((Module) -> Unit)? = null
    var onRemove: ((Module) -> Unit)? = null
    var onChange: ((new: Module) -> Unit)? = null

    var cols = 5
        private set
    var rows = 8
        private set

    var editing = false
        set(value) {
            field = value
            invalidate()
        }

    fun configure(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        requestLayout()
        invalidate()
    }

    private val chalk = context.stitch(0.9f, Thread.FAINT)
    private val chalkStrong = context.stitch(1.4f, Thread.COLOR)
    private val badge = BadgePainter(context)
    private val rect = RectF()

    /** マスの高さを、幅のこの倍までにする（0 なら入れ物いっぱいに広げる）。余った高さは上に空ける。 */
    var maxCellRatio = 0f
        set(value) { field = value; requestLayout() }

    private fun cellW() = width.toFloat() / cols
    private fun cellH(w: Int = width, h: Int = height): Float {
        val full = h.toFloat() / rows
        return if (maxCellRatio > 0f) minOf(full, w.toFloat() / cols * maxCellRatio) else full
    }

    /** マス目の上の余白（マスの高さに上限があるときは、下に寄せて親指が届くようにする）。 */
    private fun gridTop() = (height - cellH() * rows).coerceAtLeast(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = w.toFloat() / cols
        val ch = cellH(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val m = (child.layoutParams as Params).module
            child.measure(
                MeasureSpec.makeMeasureSpec((m.w * cw).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((m.h * ch).toInt(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    private fun inside(m: Module) = m.row + m.h <= rows && m.col + m.w <= cols

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW()
        val ch = cellH()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val m = (child.layoutParams as Params).module
            val x = (m.col * cw).toInt()
            val y = (gridTop() + m.row * ch).toInt()
            // はみ出した部品は、画面の外に置いて見えなくする（表示・非表示の切り替えはしない）
            if (inside(m)) child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            else child.layout(-child.measuredWidth * 2, 0, -child.measuredWidth, child.measuredHeight)
        }
    }

    fun addModule(view: View, module: Module) = addView(view, Params(module))

    private fun moduleViews(): List<Pair<View, Module>> =
        (0 until childCount).map { getChildAt(it) }.map { it to (it.layoutParams as Params).module }.filter { inside(it.second) }

    private fun cellRect(col: Int, row: Int, w: Int, h: Int, out: RectF) {
        val pad = context.dp(4f)
        val top = gridTop()
        out.set(col * cellW() + pad, top + row * cellH() + pad, (col + w) * cellW() - pad, top + (row + h) * cellH() - pad)
    }

    private fun badgeOffset() = context.dp(BadgePainter.R_DP) * 0.3f

    // ---------------- 描画 ----------------

    override fun dispatchDraw(canvas: Canvas) {
        if (editing) {
            // 空いているマスに、チャコペンの縫い線を引く
            val list = moduleViews().map { it.second }
            for (r in 0 until rows) for (c in 0 until cols) {
                if (list.any { it.covers(c, r) }) continue
                cellRect(c, r, 1, 1, rect)
                canvas.drawRoundRect(rect, context.dp(12f), context.dp(12f), chalk)
            }
        }
        super.dispatchDraw(canvas)
        if (!editing) return
        val o = badgeOffset()
        for ((v, m) in moduleViews()) {
            cellRect(m.col, m.row, m.w, m.h, rect)
            if (v === dragView && !resizing) rect.offset(v.translationX, v.translationY)
            canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), chalk)
            badge.drawRemove(canvas, rect.right - o, rect.top + o)
            if (m.type.resizable) badge.drawResize(canvas, rect.right - o, rect.bottom - o)
        }
        // 動かしている・大きさを変えている部品の、置き先の目印
        preview?.let { p ->
            cellRect(p.col, p.row, p.w, p.h, rect)
            canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), if (previewOk) chalkStrong else chalk)
        }
    }

    // ---------------- 編集モードの操作 ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var touchModule: Module? = null
    private var dragView: View? = null
    private var resizing = false
    private var removeHit = false
    private var preview: Module? = null
    private var previewOk = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing

    /** 部品の × （[bottom] なら右下のつまみ）の中心から、指までの距離。 */
    private fun badgeDistance(m: Module, x: Float, y: Float, bottom: Boolean): Float {
        cellRect(m.col, m.row, m.w, m.h, rect)
        val o = badgeOffset()
        return hypot(x - (rect.right - o), y - if (bottom) rect.bottom - o else rect.top + o)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val c = (x / cellW()).toInt()
        val r = ((y - gridTop()) / cellH()).toInt()
        return if (c in 0 until cols && r in 0 until rows && x >= 0 && y >= gridTop()) c to r else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(ev)
        val list = moduleViews()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                moved = false
                resizing = false
                removeHit = false
                preview = null
                // × とつまみは、隣の部品のものと近いことがあるので、指にいちばん近いものを選ぶ
                val reach = context.dp(BadgePainter.R_DP) * 1.7f
                val badges = list.flatMap { (v, m) ->
                    listOfNotNull(
                        Triple(v, m, false) to badgeDistance(m, ev.x, ev.y, false),
                        if (m.type.resizable) Triple(v, m, true) to badgeDistance(m, ev.x, ev.y, true) else null,
                    )
                }.filter { it.second < reach }.minByOrNull { it.second }?.first
                val body = list.lastOrNull { (v, _) -> ev.x >= v.left && ev.x < v.right && ev.y >= v.top && ev.y < v.bottom }
                when {
                    badges != null && !badges.third -> { touchModule = badges.second; removeHit = true }
                    badges != null -> { touchModule = badges.second; dragView = badges.first; resizing = true }
                    body != null -> { touchModule = body.second; dragView = body.first }
                    else -> { touchModule = null; dragView = null }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!moved && hypot(dx, dy) > slop) moved = true
                val m = touchModule
                val v = dragView
                if (moved && m != null && v != null && !removeHit) {
                    val all = list.map { it.second }
                    val target = if (resizing) {
                        val w = ((ev.x - m.col * cellW()) / cellW()).roundToInt().coerceIn(1, cols - m.col)
                        val h = ((ev.y - gridTop() - m.row * cellH()) / cellH()).roundToInt().coerceIn(1, rows - m.row)
                        m.copy(w = w, h = h)
                    } else {
                        v.translationX = dx
                        v.translationY = dy
                        v.elevation = context.dp(8f)
                        m.copy(col = ((m.col * cellW() + dx) / cellW()).roundToInt(), row = ((m.row * cellH() + dy) / cellH()).roundToInt())
                    }
                    if (target != preview) {
                        previewOk = all.none { it.id != m.id && it.overlaps(target) } &&
                            target.col >= 0 && target.row >= 0 && target.col + target.w <= cols && target.row + target.h <= rows
                        if (preview != null && previewOk) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        preview = target
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val m = touchModule
                val p = preview
                if (!moved) {
                    when {
                        m != null && removeHit -> onRemove?.invoke(m)
                        m != null -> onModuleTap?.invoke(m)
                        else -> cellAt(ev.x, ev.y)?.let { (c, r) -> onEmptyTap?.invoke(c, r) }
                    }
                } else if (m != null && p != null && previewOk && p != m) {
                    onChange?.invoke(p)
                }
                endDrag()
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun endDrag() {
        dragView?.let { v ->
            v.animate().translationX(0f).translationY(0f).setDuration(120).withEndAction { v.elevation = 0f }.start()
        }
        dragView = null
        touchModule = null
        preview = null
        resizing = false
        removeHit = false
        invalidate()
    }
}

/** 編集モードの丸い印（× とつまみ）を描く。フェルトの丸に、ステッチの輪と糸の印。 */
class BadgePainter(private val context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cloth.FELT
        setShadowLayer(context.dp(3f), 0f, context.dp(1.5f), 0xC0000000.toInt())
    }
    private val ring = context.stitch(1.1f)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        strokeWidth = context.dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private fun base(canvas: Canvas, x: Float, y: Float): Float {
        val r = context.dp(R_DP)
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r - context.dp(2.5f), ring)
        return r * 0.35f
    }

    fun drawRemove(canvas: Canvas, x: Float, y: Float) {
        val k = base(canvas, x, y)
        canvas.drawLine(x - k, y - k, x + k, y + k, mark)
        canvas.drawLine(x + k, y - k, x - k, y + k, mark)
    }

    fun drawResize(canvas: Canvas, x: Float, y: Float) {
        val k = base(canvas, x, y)
        canvas.drawLine(x - k, y + k, x + k, y - k, mark)
        canvas.drawLine(x, y + k, x + k, y, mark)
    }

    companion object {
        const val R_DP = 12f
    }
}
