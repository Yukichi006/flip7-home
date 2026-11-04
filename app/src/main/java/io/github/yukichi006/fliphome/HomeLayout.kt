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
enum class ModuleType(
    val label: String,
    val note: String,
    val w: Int,
    val h: Int,
    val resizable: Boolean,
    val addable: Boolean = true,
    /** 大きさを変えるときの、いちばん小さい幅と、いちばん大きい高さ。 */
    val minW: Int = 1,
    val maxH: Int = 99,
) {
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
    ANALOG("アナログ時計", "カバー画面と同じ、刺繍の文字盤の時計", 2, 2, true),
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

        /** パッドのアプリ名：「show」で出す、「hide」で隠す（なければ、全体の設定に合わせる）。 */
        const val P_LABEL = "label"

        /** ステッチの枠：「1」で付ける、「0」でなくす（なければ、部品と画面ごとの決まり）。 */
        const val P_FRAME = "frame"

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
        for (hh in minOf(type.h, type.maxH) downTo 1) for (ww in type.w downTo type.minW) {
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

    /** パッドのアプリ名の出し方（全体の設定）。 */
    var padLabel: PadLabel
        get() = PadLabel.of(prefs.getString("padLabel", null))
        set(value) = prefs.edit().putString("padLabel", value.key).apply()

    /** 下段のポケットの口をファスナーにするか（右へ引くとアプリ一覧）。最初は付ける。 */
    var dockZip: Boolean
        get() = prefs.getBoolean("dockZip", true)
        set(value) = prefs.edit().putBoolean("dockZip", value).apply()

    /** 片手の画面にしているか（false ならマス目の画面）。最初は片手の画面。 */
    var thumbMode: Boolean
        get() = prefs.getBoolean("thumbMode", true)
        set(value) = prefs.edit().putBoolean("thumbMode", value).apply()

    /** アプリのパネルをめくり始める側（最初は左下から右上へ）。 */
    var bookPeel: PeelFrom
        get() = PeelFrom.entries.firstOrNull { it.key == prefs.getString("bookPeel", null) } ?: PeelFrom.BOTTOM_LEFT
        set(value) = prefs.edit().putString("bookPeel", value.key).apply()

    var thumb: Thumb
        get() = prefs.getString(KEY_THUMB, null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                val t = Thumb.fromJson(o)
                if (o.has("info")) t else fromLegacy(o, t).also { thumb = it }
            }.getOrNull()
        } ?: thumbFromHome().also { thumb = it }
        set(value) = prefs.edit().putString(KEY_THUMB, value.toJson().toString()).apply()

    /**
     * 0.13.0 までの配置（左の列と右上の情報の欄が別々）を、画面全体で 1 つのマス目に移す。
     * 左の列の部品は左の 2 列に、右上の欄の部品は右の 3 列に、そのままの行で置く。
     */
    private fun fromLegacy(o: JSONObject, t: Thumb): Thumb {
        var left = Thumb.page(o.optJSONObject("left"))
        var top = Thumb.page(o.optJSONObject("top"))
        var u = t
        // 0.11.0 の配置（右上の情報の欄がない）は、パネルを低くして、上に情報を置く
        if (!o.has("top")) { u = resized(t, Thumb.DEFAULT_ROWS); top = defaultTop() }
        // 0.12.0 までの左の列（1 列）は、2 列に 2×2 ずつ積み直す
        if (!o.has("leftCols")) left = leftOnGrid(left)
        return u.copy(info = withTop(left, top))
    }

    private fun withTop(left: Page, top: Page) = Page(left.modules + top.modules.map { it.copy(col = it.col + Thumb.INFO_COLS - Thumb.COLS) })

    /**
     * 片手の画面の、情報のマス目（[Thumb.INFO_COLS] 列 × [rows] 行。行の数は画面の高さで決まる）。
     * 右下のアプリのパネルの場所には、部品を置けない。
     */
    fun infoBoard(rows: Int): Board {
        val t = thumb
        val panel = android.graphics.Rect(Thumb.INFO_COLS - Thumb.COLS, rows - t.rows, Thumb.INFO_COLS, rows)
        return Board(Thumb.INFO_COLS, rows, { thumb.info.modules }, { list -> thumb = thumb.copy(info = Page(list)) }, ::newId, panel)
    }

    /** 右上の、最初の中身：日付と今日の予定（右の 3 列の中での場所）。 */
    private fun defaultTop() = Page(listOf(
        Module(newId(), ModuleType.DATE, 0, 0, 3, 1, mapOf(Module.P_ALIGN to "mc")),
        Module(newId(), ModuleType.AGENDA, 0, 1, 3, 2),
    ))

    /**
     * アプリのパネルの段の数を [newRows] に変える。アプリは下（親指の届く側）にそろえたまま動かし、
     * 入りきらなくなったアプリは、空いているマス（なければ新しい布）へ移す。
     */
    fun resized(t: Thumb, newRows: Int): Thumb {
        val rows = newRows.coerceIn(Thumb.MIN_ROWS, LayoutStore.MAX_ROWS)
        val d = rows - t.rows
        val overflow = mutableListOf<Module>()
        val sheets = t.sheets.map { p ->
            Page(p.modules.mapNotNull { m ->
                val r = m.row + d
                if (r < 0 || r + m.h > rows) { overflow += m; null } else m.copy(row = r)
            })
        }.toMutableList()
        for (m0 in overflow) {
            val m = m0.copy(w = minOf(m0.w, Thumb.COLS), h = minOf(m0.h, rows))
            var spot: Pair<Int, Pair<Int, Int>>? = null
            while (spot == null) {
                spot = sheets.indices.firstNotNullOfOrNull { i -> freeSpot(sheets[i], m, rows)?.let { i to it } }
                if (spot == null) sheets += Page(emptyList())
            }
            val (i, at) = spot
            sheets[i] = Page(sheets[i].modules + m.copy(col = at.first, row = at.second))
        }
        return t.copy(sheets = sheets, rows = rows)
    }

    /** 布 [p] の中で、[m] が入る空いた場所を、下の段から探す。 */
    private fun freeSpot(p: Page, m: Module, rows: Int): Pair<Int, Int>? {
        for (r in rows - m.h downTo 0) for (c in 0..Thumb.COLS - m.w) {
            val at = m.copy(col = c, row = r)
            if (p.modules.none { it.overlaps(at) }) return c to r
        }
        return null
    }

    /** 左の列の部品を、2 列に 2×2 ずつ上から積み直す（0.12.0 までの 1 列の配置から移すとき）。 */
    private fun leftOnGrid(left: Page) = Page(left.modules.sortedBy { it.row }.mapIndexed { i, m ->
        m.copy(col = 0, row = i * 2, w = 2, h = 2)
    })

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
            Module(newId(), ModuleType.CLOCK, 0, 0, 2, 2),
            Module(newId(), ModuleType.CALENDAR, 0, 2, 2, 2),
            Module(newId(), ModuleType.WEATHER, 0, 4, 2, 2, weather),
            Module(newId(), ModuleType.BATTERY, 0, 6, 2, 2),
        )
        val apps = (h.pages.flatMap { p -> p.modules.filter { it.type == ModuleType.APP || it.type == ModuleType.FOLDER }.sortedWith(compareBy({ it.row }, { it.col })) } +
            h.dock.filterNotNull().filterNot { it.startsWith("@") }.map { Module(0, ModuleType.APP, 0, 0, 1, 1, mapOf(Module.P_APP to it)) })
            .distinctBy { it[Module.P_APP] ?: "folder:${it.id}" }
        val rows = Thumb.DEFAULT_ROWS
        val perSheet = Thumb.COLS * rows
        val sheets = apps.chunked(perSheet).map { chunk ->
            // アプリが少ない布は、親指の届く下の段から詰める
            val skip = rows - (chunk.size + Thumb.COLS - 1) / Thumb.COLS
            Page(chunk.mapIndexed { i, m -> m.copy(id = newId(), col = i % Thumb.COLS, row = skip + i / Thumb.COLS, w = 1, h = 1) })
        }.ifEmpty { listOf(Page(emptyList())) }
        return Thumb(withTop(Page(left), defaultTop()), sheets, rows)
    }

    /** アプリの名前の付け替え。付け替えていなければ null。 */
    fun nameOf(key: String): String? = prefs.getString("name:$key", null)

    fun setName(key: String, name: String?) =
        prefs.edit().apply { if (name.isNullOrBlank()) remove("name:$key") else putString("name:$key", name.trim()) }.apply()

    /** アプリのアイコンとして選び直したジャンル。選んでいなければ null（自動で推し量る）。 */
    fun genreOf(key: String): Genre? = prefs.getString("genre:$key", null)?.let { runCatching { Genre.valueOf(it) }.getOrNull() }

    /** アプリ一覧の巾着の分類（自分で選んでいなければ null）。 */
    fun shelfOf(key: String): Shelf? = prefs.getString("shelf:$key", null)?.let { runCatching { Shelf.valueOf(it) }.getOrNull() }

    fun setShelf(key: String, shelf: Shelf?) =
        prefs.edit().apply { if (shelf == null) remove("shelf:$key") else putString("shelf:$key", shelf.name) }.apply()

    /** FlipHome から開いた回数（「よく使う」の巾着に使う。端末の外には出さない）。 */
    fun launches(key: String): Int = prefs.getInt("use:$key", 0)

    fun countLaunch(key: String) = prefs.edit().putInt("use:$key", launches(key) + 1).apply()

    /** アイコンを、アプリ本来のアイコンをなぞった刺繍にするか（false ならジャンルのアイコン）。 */
    fun iconTraced(key: String): Boolean = prefs.getBoolean("trace:$key", false)

    fun setIconTraced(key: String, traced: Boolean) =
        prefs.edit().apply { if (traced) putBoolean("trace:$key", true) else remove("trace:$key") }.apply()

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

        /** 下段の枠に置く「アプリ一覧」のボタン（アプリのキーの代わりに入れる）。 */
        const val DRAWER_KEY = "@drawer"
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

/**
 * 片手の画面の配置：画面全体の情報のマス目（[info]、[INFO_COLS] 列）と、その右下に重ねるアプリのパネルの布
 * （[sheets]、1 枚ずつめくる。[COLS] 列 × [rows] 段。パネルは情報のマス目の右下 [COLS] 列 × [rows] 行を占める）。
 */
data class Thumb(val info: Page, val sheets: List<Page>, val rows: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("rows", rows)
        put("info", JSONObject().put("modules", JSONArray(info.modules.map { it.toJson() })))
        put("sheets", JSONArray(sheets.map { p -> JSONObject().put("modules", JSONArray(p.modules.map { it.toJson() })) }))
    }

    companion object {
        const val COLS = 3
        const val INFO_COLS = 5
        const val DEFAULT_ROWS = 5
        const val MIN_ROWS = 2

        /** マスの高さは幅のこの倍（0.10.0 のマス目の画面のパッドと同じくらいの形）。 */
        const val CELL_RATIO = 1.15f

        fun page(o: JSONObject?): Page {
            val ms = o?.optJSONArray("modules") ?: return Page(emptyList())
            return Page((0 until ms.length()).mapNotNull { Module.fromJson(ms.getJSONObject(it)) })
        }

        fun fromJson(o: JSONObject): Thumb {
            val sheets = o.optJSONArray("sheets")?.let { a -> (0 until a.length()).map { page(a.getJSONObject(it)) } }.orEmpty()
            return Thumb(page(o.optJSONObject("info")), sheets.ifEmpty { listOf(Page(emptyList())) },
                o.optInt("rows", DEFAULT_ROWS).coerceIn(MIN_ROWS, LayoutStore.MAX_ROWS))
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
    /** 部品を置けない場所（マスの単位。片手の画面のアプリのパネル）。 */
    private val reserved: android.graphics.Rect? = null,
) {
    fun modules(): List<Module> = read()

    private fun fits(list: List<Module>, m: Module) =
        m.col >= 0 && m.row >= 0 && m.col + m.w <= cols && m.row + m.h <= rows && list.none { it.id != m.id && it.overlaps(m) } &&
            reserved?.let { r -> m.col < r.right && r.left < m.col + m.w && m.row < r.bottom && r.top < m.row + m.h } != true

    /** [type] の部品を [col], [row] に置く。最初の大きさが入らなければ、入るところまで小さくする。 */
    fun placeAt(type: ModuleType, col: Int, row: Int, props: Map<String, String> = emptyMap()): Module? {
        val list = read()
        for (hh in minOf(type.h, rows, type.maxH) downTo 1) for (ww in minOf(type.w, cols) downTo type.minW) {
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

    /** 右下の、部品を置けない場所（片手の画面のアプリのパネル）。[reservedCols] 列 × [reservedRows] 行。 */
    var reservedCols = 0
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var reservedRows = 0
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    private fun isReserved(c: Int, r: Int) = c >= cols - reservedCols && r >= rows - reservedRows && reservedCols > 0 && reservedRows > 0
    private fun inReserved(m: Module) =
        reservedCols > 0 && reservedRows > 0 && m.col + m.w > cols - reservedCols && m.row + m.h > rows - reservedRows

    /** 右の [n] 列で、下から何行ぶん部品がないか（片手の画面のパネルを、部品にぶつかるまで広げられるように）。 */
    fun freeRowsAtBottomRight(n: Int): Int {
        val list = (0 until childCount).map { (getChildAt(it).layoutParams as Params).module }
        var free = 0
        while (free < rows) {
            val y = rows - 1 - free
            if (list.any { m -> m.col + m.w > cols - n && m.row <= y && y < m.row + m.h }) break
            free++
        }
        return free
    }

    /** 0 より大きいと、行の数を入れ物の高さから決める（マスの高さが幅のこの倍くらいになるだけ）。 */
    var autoRowsRatio = 0f
        set(value) { field = value; requestLayout() }

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
        // 行の数を高さから決める（マスの高さが、幅の [autoRowsRatio] 倍にいちばん近くなるだけ入れる）
        if (autoRowsRatio > 0f && w > 0 && h > 0) {
            val r = (h / (w.toFloat() / cols * autoRowsRatio)).roundToInt().coerceAtLeast(1)
            if (r != rows) { rows = r; invalidate() }
        }
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

    private fun inside(m: Module) = m.row + m.h <= rows && m.col + m.w <= cols && !inReserved(m)

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
                if (isReserved(c, r) || list.any { it.covers(c, r) }) continue
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
        return if (c in 0 until cols && r in 0 until rows && x >= 0 && y >= gridTop() && !isReserved(c, r)) c to r else null
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
                        val w = ((ev.x - m.col * cellW()) / cellW()).roundToInt().coerceIn(1, cols - m.col).coerceAtLeast(m.type.minW)
                        val h = ((ev.y - gridTop() - m.row * cellH()) / cellH()).roundToInt().coerceIn(1, minOf(rows - m.row, m.type.maxH).coerceAtLeast(1))
                        m.copy(w = w, h = h)
                    } else {
                        v.translationX = dx
                        v.translationY = dy
                        v.elevation = context.dp(8f)
                        m.copy(col = ((m.col * cellW() + dx) / cellW()).roundToInt(), row = ((m.row * cellH() + dy) / cellH()).roundToInt())
                    }
                    if (target != preview) {
                        previewOk = all.none { it.id != m.id && it.overlaps(target) } &&
                            target.col >= 0 && target.row >= 0 && target.col + target.w <= cols && target.row + target.h <= rows && !inReserved(target)
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
