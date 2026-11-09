package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * ホーム画面。上から、マス目に並べた部品（パッド・巾着・時計・予定・電池など）、ページの点、下段のポケット。
 *
 * - 左右へスワイプ：ページを移る
 * - 上へスワイプ：アプリ一覧を開く
 * - 下へスワイプ：通知を開く
 * - どこかを長押し：編集モード（空いたマスをタップして追加、ドラッグで移動、× で外す、右下のつまみで大きさ）
 */
class HomeActivity : Activity() {
    private lateinit var repo: AppRepository
    private lateinit var store: LayoutStore
    private lateinit var root: HomeRoot
    private lateinit var column: LinearLayout
    private lateinit var grid: ModuleGrid
    private lateinit var dots: PageDots
    private lateinit var dock: PocketDock
    private lateinit var drawer: DrawerView
    private lateinit var banner: View
    private lateinit var editBar: View

    /** 置こうとしているウィジェット（番号と、置くマス）。許可・初期設定の画面から戻るまで覚えておく。 */
    private var pendingWidget: Int = -1
    private var pendingCell: Pair<Int, Int>? = null

    /** めくれるパネルごとに、何枚目の布を出しているか（パネルの部品の番号 → 布の番号）。 */
    private val bookPages = mutableMapOf<Long, Int>()

    /** 設定のパネルを開いた部品が置かれている盤（ページのマス目、またはめくれるパネルの布）。 */
    private var activeBoard: Board? = null

    private fun board(): Board = activeBoard ?: store.gridBoard(page)

    /** ホーム画面を組み立て終えたか（不具合の記録を見せているときは false）。 */
    private var ready = false
    private var editing = false
    private var page = 0
    private var sheet: Sheet? = null
    private var popover: FolderPopover? = null
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        // 前回落ちた・固まった記録があれば、まずそれを見せる（ホーム画面は組み立てない）
        if (Diagnostics.showIfAny(this) { recreate() }) return
        ready = true

        store = LayoutStore(this)
        StitchStyle.scale = store.thread
        StitchIcons.colored = store.traceColored
        repo = AppRepository(this)
        repo.start()
        if (store.home.dock.all { it == null } && !getSharedPreferences("home", 0).getBoolean("dockInit", false)) {
            repo.defaultDock().forEachIndexed { i, app -> store.setDockSlot(i, app.key) }
            getSharedPreferences("home", 0).edit().putBoolean("dockInit", true).apply()
        }

        root = HomeRoot(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = makeBanner()
        grid = ModuleGrid(this).apply {
            setOnLongClickListener { enterEdit(); true }
        }
        wireGrid(grid) { store.gridBoard(page) }
        dots = PageDots(this) { goToPage(it) }
        dock = PocketDock(this).apply {
            setOnLongClickListener { enterEdit(); true }
            onSlotTap = { i -> showDockSheet(i) }
            onSlotRemove = { i -> store.setDockSlot(i, null); rebuildHome() }
        }
        val side = dpi(6f)
        column.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(70f)).apply {
            setMargins(side, dpi(4f), side, 0)
        })
        // どのページも同じマス目。マスの形はパッドに合わせ（高さは幅の [Thumb.CELL_RATIO] 倍くらい）、行の数は画面の高さから決める
        grid.autoRowsRatio = Thumb.CELL_RATIO
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(side, dpi(4f), side, dpi(6f))
        })
        column.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(20f)))
        column.addView(dock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(124f)).apply {
            setMargins(side, 0, side, dpi(4f))
        })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        editBar = makeEditBar()
        editBar.visibility = View.GONE
        root.addView(editBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpi(70f), Gravity.TOP).apply {
            setMargins(dpi(6f), 0, dpi(6f), 0)
        })

        drawer = DrawerView(this, repo, onMenu = { app, _ -> showAppSettings(app) }, onClose = { closeDrawer() })
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            insetBottom = bars.bottom
            column.setPadding(0, bars.top, 0, bars.bottom)
            (editBar.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            editBar.requestLayout()
            drawerInsetTop = bars.top
            drawerInsetBottom = max(bars.bottom, ime.bottom)
            drawer.applyInsets(bars.top, if (zipDrawer) 0 else drawerInsetBottom)
            insets
        }
        setContentView(root)

        repo.addListener {
            rebuildHome()
            if (drawer.visibility == View.VISIBLE) drawer.rebuild()
        }
        rebuildHome()
    }

    private fun setupWindow() {
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        // 暗い布の上なので、ステータスバーの文字は白にする。
        // window.insetsController は画面の土台（decorView）ができる前に呼ぶと落ちるので、土台から取る
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        banner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
        refreshModules()
        // ロック画面の刺繍の壁紙が、今日の分になっているか（電源を入れ直して予約が消えたときなど）
        LockArt.refreshIfNeeded(this)
    }

    override fun onStart() {
        super.onStart()
        // 表示している間だけ、ウィジェットの更新を受け取る
        runCatching { AppWidgets.host(this).startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { AppWidgets.host(this).stopListening() }
        if (ready) popover?.dismiss()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!ready) return
        // ホーム画面にいるときにホームボタンを押したら、開いているものを閉じて元に戻す
        popover?.dismiss()
        sheet?.dismiss()
        if (editing) exitEdit()
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ready) return
        when {
            popover != null -> popover?.dismiss()
            sheet != null -> sheet?.dismiss()
            drawer.visibility == View.VISIBLE && drawer.dismissPopover() -> Unit
            editing -> exitEdit()
            drawer.visibility == View.VISIBLE -> closeDrawer()
        }
        // ホーム画面そのものは戻るボタンで閉じない
    }

    override fun onDestroy() {
        if (ready) repo.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!ready) return
        refreshModules()
    }

    // ------------------------------------------------------------------
    // ホーム画面の組み立て
    // ------------------------------------------------------------------

    private var peelFrom = PeelFrom.DEFAULT

    /** マス目に、編集モードの操作（空いたマスのタップ・部品のタップ・外す・動かす）をつなぐ。[board] は、その時点の盤を返す。 */
    private fun wireGrid(g: ModuleGrid, board: () -> Board) {
        g.onEmptyTap = { c, r -> activeBoard = board(); showCellSheet(c, r) }
        g.onModuleTap = { m -> activeBoard = board(); showModuleSheet(m) }
        g.onRemove = { m ->
            board().remove(m)
            // ウィジェットを外したら、その番号も返す
            m[Module.P_WIDGET]?.toIntOrNull()?.let { runCatching { AppWidgets.host(this).deleteAppWidgetId(it) } }
            rebuildHome()
        }
        g.onChange = { new ->
            val b = board()
            val old = b.modules().firstOrNull { it.id == new.id }
            // めくれるパネルの大きさを変えたら、中のアプリを並べ直す
            b.replace(if (old != null && new.type == ModuleType.BOOK) store.reflowBook(old, new) else new)
            rebuildHome()
        }
    }

    private fun rebuildHome() {
        peelFrom = store.peel
        // 0.27 までの配置（片手の画面）を、1 つのマス目の形に移す。行の数はマス目の高さから決まるので、測ってから
        if (store.needsUnify) {
            if (grid.height == 0) {
                grid.post { if (grid.height > 0) rebuildHome() }
                return
            }
            store.unify(grid.rows)
        }
        // 行の数は画面の高さ（と列の数）で決まる。変わっていたら覚え直す
        if (grid.height > 0 && store.home.rows != grid.rows) store.setGrid(store.home.cols, grid.rows)
        val home = store.home
        page = page.coerceIn(0, home.pages.size - 1)
        grid.configure(home.cols, home.rows)
        fill(grid, store.modules(page), inPatches = true)
        dots.count = home.pages.size
        dots.current = page
        dots.visibility = if (home.pages.size > 1) View.VISIBLE else View.GONE
        rebuildDock(home)
        grid.editing = editing
        // 列の数を変えたときは、測り直したあとの行の数で並べ直す
        grid.post { if (grid.height > 0 && store.home.rows != grid.rows) rebuildHome() }
    }

    /** 下段のポケット（どのページでも同じ中身）。 */
    private fun rebuildDock(home: Home) {
        dock.visibility = if (home.pocket) View.VISIBLE else View.GONE
        // 口のファスナー：右へ引ききると、アプリ一覧が開く。ファスナーの帯の分だけ、ポケットを高くする
        val zip = store.dockZip
        if ((dock.zipper != null) != zip) dock.zipper = if (zip) ZipperTrack(dock, zipLink) else null
        val h = dpi(if (zip) 138f else 124f)
        if (dock.layoutParams.height != h) dock.layoutParams = dock.layoutParams.apply { height = h }
        dock.setCells(home.dock.map { key ->
            if (key == LayoutStore.DRAWER_KEY) DrawerButton(this, onOpen = { openDrawer() }, onLongPress = { enterEdit() })
            else key?.let { repo.find(it) }?.let { app -> PadView(this, app, repo, framed = false, labelMode = store.padLabel) { enterEdit() } }
        })
        dock.editing = editing
    }

    /** マス目 [g] に、[modules] の部品を並べる。 */
    private fun fill(g: ModuleGrid, modules: List<Module>, inPatches: Boolean = false) {
        g.removeAllViews()
        for (m in modules) {
            val view = viewFor(m, inPatches) ?: continue
            g.addModule(view, m)
        }
        g.editing = editing
    }

    /** めくれるパネル [m]：今の布と、その下の次の布。角をめくりきると次の布になる。中のマス目は、パネルの大きさ（マスの数）。 */
    private fun bookView(m: Module): View {
        val sheets = m.sheets
        val count = sheets.size
        val idx = (bookPages[m.id] ?: 0).coerceIn(0, count - 1)
        val onPage = page
        fun sheetFor(i: Int): SheetView {
            val g = ModuleGrid(this).apply { setOnLongClickListener { enterEdit(); true } }
            g.configure(m.w, m.h)
            fill(g, sheets[i].modules)
            if (i == idx) wireGrid(g) { store.bookBoard(onPage, m.id, idx) }
            return SheetView(this, g)
        }
        return BookPanel(this, store.bookPeel) {
            bookPages[m.id] = (idx + 1) % count
            turnBook(m)
        }.apply { setSheets(sheetFor(idx), if (count > 1) sheetFor((idx + 1) % count) else null, idx, count) }
    }

    /**
     * めくれるパネル [m] をめくりきったら、そのパネルだけを次の布で作り直し、新しい布が縫い上がるように出す
     * （ほかの部品は作り直さない）。
     */
    private fun turnBook(m: Module) {
        val i = (0 until grid.childCount).indexOfFirst { (grid.getChildAt(it).layoutParams as? ModuleGrid.Params)?.module?.id == m.id }
        if (i < 0) { rebuildHome(); return }
        val current = store.modules(page).firstOrNull { it.id == m.id } ?: m
        val view = bookView(current)
        view.setOnLongClickListener { enterEdit(); true }
        grid.removeViewAt(i)
        grid.addView(view, i, ModuleGrid.Params(current))
        Sewing.start(listOf(view), grid, root, fade = false)
    }

    /** 部品 [m] の画面の部品を作る。[inPatch] なら、布のない部品（時計・カレンダー・天気など）をワッペンに載せる。 */
    private fun viewFor(m: Module, inPatch: Boolean = false): View? {
        val view: View = when (m.type) {
            ModuleType.CLOCK -> ClockView(this)
            ModuleType.ANALOG -> AnalogClockView(this)
            ModuleType.WIDGET -> widgetView(m) ?: return null
            ModuleType.BOOK -> bookView(m)
            ModuleType.ALARM -> NextAlarmView(this)
            ModuleType.EVENTS -> MonthEventsView(this)
            ModuleType.AGENDA -> AgendaView(this)
            ModuleType.BATTERY -> BatteryPatch(this)
            // アンインストールされたアプリ（や一時的に使えない仕事用のアプリ）は表示しないだけで、配置は残す
            ModuleType.APP -> m[Module.P_APP]?.let { repo.find(it) }?.let { app ->
                PadView(this, app, repo, second = m[Module.P_LONG_APP]?.let { repo.find(it) }, peel = peelFrom, labelMode = padLabelFor(m)) { enterEdit() }
            } ?: return null
            ModuleType.FOLDER -> FolderPouch(this, m[Module.P_NAME] ?: "フォルダ",
                m.folderApps.mapNotNull { repo.find(it)?.component?.packageName },
                onOpen = { v -> showFolder(m, v) }, onLongPress = { enterEdit() })
            ModuleType.TIME -> TimeView(this, Align.parse(m[Module.P_ALIGN], "mr"), m[Module.P_SECONDS] == "1")
            ModuleType.DATE -> DateView(this, Align.parse(m[Module.P_ALIGN], "ml"), m[Module.P_STYLE])
            ModuleType.TITLE -> TitleView(this, m[Module.P_TEXT] ?: "${page + 1}", Align.parse(m[Module.P_ALIGN], "bl"))
            ModuleType.CALENDAR -> DotCalendarView(this, m[Module.P_MONDAY] == "1",
                hasSwipe = { !m[Module.gestureKey(it)].isNullOrEmpty() },
                onSwipe = { dir -> runGesture(m[Module.gestureKey(dir)]) })
            ModuleType.MEDIA -> MediaView(this) { showBadgeSheet() }
            ModuleType.SLIDER -> sliderFor(m)
            ModuleType.WEATHER -> WeatherView(this, m[Module.P_CITY], m[Module.P_LAT]?.toDoubleOrNull(), m[Module.P_LON]?.toDoubleOrNull(),
                showMinMax = m[Module.P_MINMAX] != "0") {
                val app = m[Module.P_TAP_APP]?.let { repo.find(it) }
                when {
                    app != null -> repo.launch(app, grid)
                    m[Module.P_LAT] == null -> {
                        enterEdit()
                        activeBoard = boardHolding(m)
                        weatherPlaceSheet(m)
                    }
                    else -> {
                        WeatherRepo.refresh(m[Module.P_LAT]!!.toDouble(), m[Module.P_LON]!!.toDouble(), force = true)
                        toast("${m[Module.P_CITY] ?: ""} の天気を取り直しています")
                    }
                }
            }
        }
        if (m.type !in setOf(ModuleType.APP, ModuleType.FOLDER, ModuleType.SLIDER)) view.setOnLongClickListener { enterEdit(); true }
        val framed = isFramed(m, inPatch)
        // ワッペンの絵を自分で描く部品は、枠を描くかどうかだけ伝える（描かなければ中身が大きくなる）
        (view as? PatchView)?.framed = framed
        (view as? ZipperFader)?.framed = framed
        // ほかのアプリのウィジェットは、大きさはそのままで、縁の少し内側をステッチで縫う
        if (view is WidgetCell) { view.stitched = framed; return view }
        if ((m.type !in BARE && m.type != ModuleType.WIDGET) || !framed) return view
        // 布のない部品（時計・カレンダー・天気など）は、ワッペンに載せる
        return PatchLayout(this, PatchPainter.Shape.Round(22f)).apply {
            val p = dpi(10f)
            setPadding(p, p, p, p)
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnLongClickListener { enterEdit(); true }
        }
    }

    /** パッド [m] のアプリ名の出し方（パッドで選んでいればそれ、なければ全体の設定）。 */
    private fun padLabelFor(m: Module): PadLabel = when (m[Module.P_LABEL]) {
        "hide" -> PadLabel.NONE
        "show" -> store.padLabel.takeIf { it != PadLabel.NONE } ?: PadLabel.TWO
        else -> store.padLabel
    }

    /**
     * 部品 [m] にステッチの枠を付けるか。自分で選んでいればそれ。選んでいなければ、
     * 布のない部品は片手の画面（[inPatch]）でだけ付け、ワッペンの部品はいつも付ける。
     */
    private fun isFramed(m: Module, inPatch: Boolean): Boolean =
        m[Module.P_FRAME]?.let { it == "1" } ?: when (m.type) {
            in BARE -> inPatch
            // ほかのアプリのウィジェットは、最初は枠なし（選べば、フェルトのワッペンに載せて縁を縫う）
            ModuleType.WIDGET -> false
            else -> true
        }

    /** 枠を付け外しできる部品なら、その切り替えのボタン。 */
    private fun frameItem(m: Module): Pair<String, () -> Unit>? {
        if (m.type !in FRAMEABLE) return null
        val now = isFramed(m, true)
        val label = when {
            m.type == ModuleType.WIDGET && now -> "縁のステッチをなくす"
            m.type == ModuleType.WIDGET -> "縁にステッチを縫う（大きさはそのまま）"
            now -> "ステッチの枠をなくす（中身を大きく）"
            else -> "ステッチの枠を付ける"
        }
        return label to {
            board().replace(m.with(Module.P_FRAME, if (now) "0" else "1"))
            done()
        }
    }

    // ------------------------------------------------------------------
    // アプリのウィジェット
    // ------------------------------------------------------------------

    /** ウィジェットの部品の画面。ウィジェットのアプリが消えていれば null。 */
    private fun widgetView(m: Module): View? {
        val id = m[Module.P_WIDGET]?.toIntOrNull() ?: return null
        val info = android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return null
        val host = runCatching { AppWidgets.host(this).createView(this, id, info) }.getOrNull() ?: return null
        return WidgetCell(this, info.minWidth, info.minHeight) { enterEdit() }.apply {
            addView(host, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** マス ([col], [row]) に置くウィジェットを選ぶ。 */
    private fun pickWidget(col: Int, row: Int) {
        val am = android.appwidget.AppWidgetManager.getInstance(this)
        val pm = packageManager
        val providers = am.installedProviders.map { info ->
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)) }.getOrDefault(info.provider.packageName)
            info to "$app：${info.loadLabel(pm)}"
        }.sortedBy { it.second.lowercase() }
        if (providers.isEmpty()) {
            toast("置けるウィジェットが見つかりませんでした")
            return
        }
        sheet?.setBody(actionList(this, providers.map { (info, label) ->
            label to {
                val id = AppWidgets.host(this).allocateAppWidgetId()
                pendingWidget = id
                pendingCell = col to row
                closeSheet()
                if (am.bindAppWidgetIdIfAllowed(id, info.provider)) configureWidget()
                else {
                    // 初めてのときは、Android が「FlipHome にウィジェットを許可するか」を確認する
                    @Suppress("DEPRECATION")
                    runCatching {
                        startActivityForResult(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), REQUEST_BIND_WIDGET)
                    }.onFailure { cancelWidget() }
                }
            }
        }, note = "置いたあとは、右下のつまみで大きさを変えられます。"))
    }

    /** 初期設定が必要なウィジェットなら、その画面を開く。不要ならそのまま置く。 */
    private fun configureWidget() {
        val id = pendingWidget
        val info = android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        val optional = info != null && (info.widgetFeatures and android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info?.configure != null && !optional) {
            runCatching { AppWidgets.host(this).startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null) }
                .onFailure { finishWidget() }
        } else finishWidget()
    }

    /** 選んだウィジェットを、覚えておいたマスに置く（入らなければ、入るところまで小さくする）。 */
    private fun finishWidget() {
        val id = pendingWidget
        val (col, row) = pendingCell ?: (0 to 0)
        pendingWidget = -1
        pendingCell = null
        val placed = board().placeAt(ModuleType.WIDGET, col, row, mapOf(Module.P_WIDGET to id.toString()))
        if (placed == null) {
            runCatching { AppWidgets.host(this).deleteAppWidgetId(id) }
            toast("ここには置けません")
        }
        rebuildHome()
    }

    private fun cancelWidget() {
        if (pendingWidget != -1) runCatching { AppWidgets.host(this).deleteAppWidgetId(pendingWidget) }
        pendingWidget = -1
        pendingCell = null
    }

    /** 部品 [m] が置いてある盤（今のページのマス目）。 */
    private fun boardHolding(m: Module): Board {
        return store.gridBoard(page)
    }

    private fun refreshModules() {
        for (g in listOf(grid)) for (i in 0 until g.childCount) {
            when (val v = g.getChildAt(i)) {
                is AgendaView -> v.reload()
                is MonthEventsView -> v.reload()
                is BatteryPatch -> v.refresh()
            }
        }
    }

    /** 部品に割り当てた動作を行う（通知を開く・アプリ一覧を開く・アプリを開く）。 */
    private fun runGesture(action: String?) {
        when {
            action.isNullOrEmpty() -> {}
            action == "shade" -> expandNotifications()
            action == "drawer" -> openDrawer()
            action.startsWith("app:") -> repo.find(action.removePrefix("app:"))?.let { repo.launch(it, grid) }
        }
    }

    private fun gestureLabel(action: String?): String = when {
        action.isNullOrEmpty() -> "なし"
        action == "shade" -> "通知を開く"
        action == "drawer" -> "アプリ一覧を開く"
        action.startsWith("app:") -> repo.find(action.removePrefix("app:"))?.let { repo.label(it) } ?: "（使えないアプリ）"
        else -> "なし"
    }

    private fun sliderFor(m: Module): View {
        val function = m[Module.P_FUNCTION] ?: "volume"
        val label = when (function) { "brightness" -> "BRT"; "app" -> "APP"; else -> "VOL" }
        return ZipperFader(this, function, label,
            onEnd = { top -> (if (top) m[Module.P_TOP_APP] else m[Module.P_BOTTOM_APP])?.let { repo.find(it) }?.let { repo.launch(it, grid) } },
            needPermission = { showBrightnessSheet() },
        ).apply { setOnLongClickListener { enterEdit(); true } }
    }

    private fun showBrightnessSheet() = openSheet("明るさのフェーダー") { s ->
        s.setTabs(emptyList(), 0) {}
        s.setBody(actionList(this, listOf(
            "「システム設定の変更」の画面を開く" to {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
                closeSheet()
            },
        ), note = "画面の明るさを変えるには、「システム設定の変更」で FlipHome を許可してください。\n" +
            "明るさと、明るさの自動調整のオン・オフだけを変えます。"))
    }

    // ------------------------------------------------------------------
    // ページ
    // ------------------------------------------------------------------

    /** [target] のページへ移る。今のページを横にすべらせて片付け、新しいページを縫い上げ、巾着を少し揺らす。 */
    private fun goToPage(target: Int) {
        val count = store.home.pages.size
        val t = target.coerceIn(0, count - 1)
        if (t == page) {
            grid.animate().translationX(0f).setDuration(160).start()
            return
        }
        val dir = if (t > page) -1 else 1
        val w = grid.width.toFloat()
        grid.animate().translationX(dir * w).setDuration(150).withEndAction {
            page = t
            rebuildHome()
            grid.translationX = -dir * w
            grid.translationX = 0f
            // 新しいページの部品を、ミシンで縫い上げるように出す
            Sewing.start((0 until grid.childCount).map { grid.getChildAt(it) } + dock, grid, root)
            swayPouches(dir)
        }.start()
    }

    /** ページを移ると、巾着が慣性で少し揺れる。 */
    private fun swayPouches(dir: Int) {
        for (i in 0 until grid.childCount) {
            val pouch = grid.getChildAt(i) as? FolderPouch ?: continue
            android.animation.ValueAnimator.ofFloat(0f, -dir * 12f, dir * 6f, -dir * 2f, 0f).apply {
                duration = 900
                addUpdateListener { pouch.sway = it.animatedValue as Float }
                start()
            }
        }
    }

    // ------------------------------------------------------------------
    // 巾着（フォルダ）
    // ------------------------------------------------------------------

    private fun showFolder(m: Module, anchor: View) {
        popover?.dismiss()
        val apps = m.folderApps.mapNotNull { repo.find(it) }
        if (apps.isEmpty()) {
            toast("巾着は空です。長押しして編集モードから、アプリを入れてください")
            return
        }
        val pads = apps.map { app -> PadView(this, app, repo, framed = false) { } }
        val p = FolderPopover(this, m[Module.P_NAME] ?: "フォルダ", anchor, pads) { popover = null }
        popover = p
        p.show(root)
    }

    // ------------------------------------------------------------------
    // 編集モード
    // ------------------------------------------------------------------

    private fun enterEdit() {
        if (editing) return
        editing = true
        popover?.dismiss()
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        grid.editing = true
        dock.editing = true
        rebuildHome()
        editBar.visibility = View.VISIBLE
        editBar.alpha = 0f
        editBar.animate().alpha(1f).setDuration(150).start()
    }

    private fun exitEdit() {
        editing = false
        grid.editing = false
        dock.editing = false
        rebuildHome()
        editBar.animate().alpha(0f).setDuration(150).withEndAction { editBar.visibility = View.GONE }.start()
    }

    /** 編集モードの間、画面の上に出す帯。説明と「…」「完了」。 */
    private fun makeEditBar(): View = PatchLayout(this, PatchPainter.Shape.Round(40f)).apply {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = "空いたマスをタップして追加\nドラッグで移動・× で外す"
            setTextColor(Thread.COLOR)
            textSize = 11.5f
            setShadowLayer(dp(0.6f), 0f, dp(1f), Thread.SHADOW)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Pill(context, "…").apply { setOnClickListener { showEditMenu(this) } },
            LinearLayout.LayoutParams(dpi(56f), dpi(46f)))
        row.addView(Pill(context, "完了", selected = true).apply { setOnClickListener { exitEdit() } },
            LinearLayout.LayoutParams(dpi(86f), dpi(46f)))
        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 帯そのものへのタップは、後ろのマス目に通さない
        isClickable = true
        val p = dpi(9f)
        setPadding(dpi(22f), p, p, p)
    }

    private fun showEditMenu(anchorView: View) = PopupMenu(this, anchorView).apply {
        val home = store.home
        menu.add("ページを足す").setOnMenuItemClickListener {
            page = store.addPage(page)
            rebuildHome()
            toast("${page + 1} ページ目を足しました。空いたマスをタップして、部品やウィジェットを置けます")
            true
        }
        if (home.pages.size > 1) {
            menu.add("このページを外す（${page + 1} ページ目）").setOnMenuItemClickListener {
                confirm("${page + 1} ページ目と、そこに置いたものを外します。") {
                    store.modules(page).mapNotNull { it[Module.P_WIDGET]?.toIntOrNull() }
                        .forEach { runCatching { AppWidgets.host(this@HomeActivity).deleteAppWidgetId(it) } }
                    store.removePage(page)
                    page = (page - 1).coerceAtLeast(0)
                    rebuildHome()
                }
                true
            }
        }
        gridMenus(home)
        val bookPeelMenu = menu.addSubMenu("めくれるパネルをめくる向き（いま：${store.bookPeel.label}）")
        for (p in PeelFrom.entries) bookPeelMenu.add(p.label).setOnMenuItemClickListener { store.bookPeel = p; rebuildHome(); true }
        val peelMenu = menu.addSubMenu("裏地をめくる向き（いま：${store.peel.label}）")
        for (p in PeelFrom.entries) {
            peelMenu.add(p.label).setOnMenuItemClickListener { store.peel = p; rebuildHome(); true }
        }
        menu.add(if (store.traceColored) "なぞる刺繍の糸：アイコンの色（生成りにする）" else "なぞる刺繍の糸：生成り（アイコンの色にする）").setOnMenuItemClickListener {
            store.traceColored = !store.traceColored
            StitchIcons.colored = store.traceColored
            rebuildHome()
            if (drawer.visibility == View.VISIBLE) drawer.rebuild()
            true
        }
        val labelMenu = menu.addSubMenu("パッドのアプリ名（いま：${store.padLabel.label}）")
        for (l in PadLabel.entries) labelMenu.add(l.label).setOnMenuItemClickListener { store.padLabel = l; rebuildHome(); true }
        val threadMenu = menu.addSubMenu("糸の太さ（いま：${StitchStyle.label(store.thread)}）")
        for ((scale, name) in StitchStyle.CHOICES) {
            threadMenu.add(name).setOnMenuItemClickListener {
                store.thread = scale
                // 糸はあちこちで使っているので、画面をまるごと作り直す
                recreate()
                true
            }
        }
        menu.add(if (Badges.enabled(this@HomeActivity)) "通知の印：使っています（設定）" else "通知の印を使う…").setOnMenuItemClickListener {
            showBadgeSheet()
            true
        }
        if (LockArt.enabled(this@HomeActivity)) {
            menu.add("ロック画面の刺繍の壁紙をやめる").setOnMenuItemClickListener {
                LockArt.disable(this@HomeActivity)
                toast("ロック画面の壁紙を、ホーム画面と同じ壁紙に戻しました")
                true
            }
        } else {
            menu.add("ロック画面の壁紙を刺繍にする（毎日自動で更新）").setOnMenuItemClickListener {
                toast("ステッチの壁紙を描いています…")
                LockArt.enable(this@HomeActivity) { ok ->
                    toast(if (ok) "ロック画面の壁紙を設定しました。日付が変わるたびに描き直します" else "ロック画面の壁紙を設定できませんでした")
                }
                true
            }
        }
        if (LockArt.enabled(this@HomeActivity)) {
            val home = LockArt.alsoHome(this@HomeActivity)
            menu.add(if (home) "ホーム画面の壁紙にも設定する：オン（オフにする）" else "ロック画面が切り替わらないとき：ホーム画面の壁紙にも設定する").setOnMenuItemClickListener {
                LockArt.setAlsoHome(this@HomeActivity, !home)
                toast(if (!home) "ロック画面とホーム画面の両方に、今日の絵を設定しました" else "次からはロック画面だけに設定します")
                true
            }
        }
        menu.add("ロック画面の壁紙の状態を調べる").setOnMenuItemClickListener {
            android.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle("ロック画面の壁紙")
                .setMessage(LockArt.describe(this@HomeActivity) +
                    "\n\nロック画面がほかのアプリの「動く壁紙」になっていると、FlipHome の絵より手前に出ます。" +
                    "Galaxy の「壁紙とスタイル」でロック画面に静止画を選び直してから、もう一度「刺繍にする」を押してください。")
                .setPositiveButton("閉じる", null)
                .show()
            true
        }
        menu.add("ロック画面の壁紙を保存して自分で設定する…").setOnMenuItemClickListener {
            toast("ステッチの壁紙を描いています…")
            LockArt.saveAndOpen(this@HomeActivity) { err ->
                if (err != null) toast(err)
                else toast("開いた画面で「ロック画面」を選んで設定してください（ギャラリーの Pictures/FlipHome にも保存しました）")
            }
            true
        }
        menu.add("MPC の配置を読み込む…").setOnMenuItemClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(pick, REQUEST_IMPORT) }
            true
        }
        menu.add("アプリ一覧を開く").setOnMenuItemClickListener { exitEdit(); openDrawer(); true }
        menu.add("ホームアプリを切り替える").setOnMenuItemClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            true
        }
        menu.add("FlipHome ${versionName()}").isEnabled = false
    }.show()

    /** マス目の設定（列の数、下段のポケット）。行の数は画面の高さから決まる。 */
    private fun PopupMenu.gridMenus(home: Home) {
        val cols = menu.addSubMenu("横の列の数（いま ${home.cols}）")
        for (c in LayoutStore.MIN_COLS..LayoutStore.MAX_COLS) {
            cols.add("$c 列").setOnMenuItemClickListener { store.setGrid(c, home.rows); rebuildHome(); true }
        }
        menu.add(if (home.pocket) "下段のポケットを隠す" else "下段のポケットを出す").setOnMenuItemClickListener {
            store.setPocket(!home.pocket)
            rebuildHome()
            true
        }
        zipMenu(home)
    }

    /** 下段のポケットの口のファスナー（アプリ一覧）を付ける・外す。 */
    private fun PopupMenu.zipMenu(home: Home) {
        if (!home.pocket) return
        menu.add(if (store.dockZip) "下段のファスナー（アプリ一覧）を外す" else "下段のファスナー（アプリ一覧）を付ける").setOnMenuItemClickListener {
            store.dockZip = !store.dockZip
            rebuildHome()
            true
        }
    }

    private fun confirm(message: String, ok: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("消す") { _, _ -> ok() }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun showBadgeSheet() = openSheet("通知へのアクセス") { s ->
        s.setTabs(emptyList(), 0) {}
        s.setBody(actionList(this, listOf(
            "通知へのアクセスの設定を開く" to { Badges.openSettings(this) },
        ), note = "通知が来ているアプリのパッドに糸の玉の印を付け、再生中の曲を表示します。\n" +
            "そのために「通知へのアクセス」で FlipHome を許可してください。\n" +
            "使うのは「どのアプリに通知が来ているか」と「再生中の曲」だけで、通知の中身は読みません。保存も、外へ送ることもしません。"))
    }

    // ------------------------------------------------------------------
    // 設定のパネル（ボトムシート）
    // ------------------------------------------------------------------

    private fun openSheet(title: String, build: (Sheet) -> Unit) {
        sheet?.dismiss()
        val s = Sheet(this, insetBottom) { if (sheet != null && sheet?.parent == null) sheet = null }
        s.setTitle(title)
        build(s)
        sheet = s
        s.show(root)
    }

    private fun closeSheet() {
        sheet?.dismiss()
        sheet = null
    }

    private fun done() {
        closeSheet()
        rebuildHome()
    }

    /** 空いたマスをタップしたとき：アプリか部品を選んで置く。 */
    private fun showCellSheet(col: Int, row: Int) = openSheet("マスに置く") { s ->
        fun appTab() = s.setBody(AppPicker(this, repo) { app ->
            board().placeAt(ModuleType.APP, col, row, mapOf(Module.P_APP to app.key))
            done()
        })
        fun moduleTab() = s.setBody(modulePicker(this) { type ->
            if (type == ModuleType.WIDGET) {
                pickWidget(col, row)
                return@modulePicker
            }
            val placed = board().placeAt(type, col, row, if (type == ModuleType.FOLDER) mapOf(Module.P_NAME to "フォルダ") else emptyMap())
            if (placed == null) {
                toast("ここには置けません")
                done()
            } else if (type == ModuleType.FOLDER) {
                rebuildHome()
                folderSheet(placed)
            } else if (type == ModuleType.WEATHER) {
                rebuildHome()
                weatherPlaceSheet(placed)
            } else {
                done()
            }
        })
        s.setTabs(listOf("アプリ", "部品"), 0) { if (it == 0) appTab() else moduleTab() }
        appTab()
    }

    /** 置いてある部品をタップしたとき。 */
    private fun showModuleSheet(m: Module) {
        when (m.type) {
            ModuleType.APP -> padSheet(m)
            ModuleType.FOLDER -> folderSheet(m)
            ModuleType.BOOK -> bookSheet(m)
            ModuleType.TIME, ModuleType.DATE, ModuleType.TITLE, ModuleType.CALENDAR, ModuleType.SLIDER, ModuleType.MEDIA, ModuleType.WEATHER -> instrumentSheet(m)
            else -> openSheet(m.type.label) { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(actionList(this, listOfNotNull(frameItem(m)) + pageMoveItems(m) + listOf("外す" to { board().remove(m); done() }),
                    note = "大きさは右下のつまみ、場所はドラッグで変えられます。"))
            }
        }
    }

    /** 時刻・日付・見出し・カレンダー・フェーダー・再生中の曲の設定。 */
    private fun instrumentSheet(m: Module) = openSheet(m.type.label) { s ->
        s.setTabs(emptyList(), 0) {}
        fun save(new: Module) { board().replace(new); done() }
        fun alignItem(default: String) = "寄せ方（いま：${Align.CHOICES.firstOrNull { it.first == (m[Module.P_ALIGN] ?: default) }?.second ?: "真ん中"}）" to {
            s.setBody(actionList(this, Align.CHOICES.map { (key, name) -> name to { save(m.with(Module.P_ALIGN, key)) } }))
        }
        fun appChooser(key: String) = { s.setBody(AppPicker(this, repo) { app -> save(m.with(key, app.key)) }) }
        val items: List<Pair<String, () -> Unit>> = when (m.type) {
            ModuleType.TIME -> listOf(
                (if (m[Module.P_SECONDS] == "1") "秒を隠す" else "秒を出す") to { save(m.with(Module.P_SECONDS, if (m[Module.P_SECONDS] == "1") null else "1")) },
                alignItem("mr"),
            )
            ModuleType.DATE -> listOf(
                (if (m[Module.P_STYLE] == "dot") "「11月2日（月）」の形にする" else "「11.02 MON」の形にする") to {
                    save(m.with(Module.P_STYLE, if (m[Module.P_STYLE] == "dot") null else "dot"))
                },
                alignItem("ml"),
            )
            ModuleType.TITLE -> listOf(
                "文字を変える（いま：${m[Module.P_TEXT] ?: "${page + 1}"}）" to {
                    s.setBody(renameBody(this, m[Module.P_TEXT] ?: "", null) { t -> save(m.with(Module.P_TEXT, t?.trim()?.ifEmpty { null })) })
                },
                alignItem("bl"),
            )
            ModuleType.CALENDAR -> listOf(
                (if (m[Module.P_MONDAY] == "1") "週の始まりを日曜にする" else "週の始まりを月曜にする") to {
                    save(m.with(Module.P_MONDAY, if (m[Module.P_MONDAY] == "1") null else "1"))
                },
            ) + listOf("up" to "上", "down" to "下", "left" to "左", "right" to "右").map { (dir, name) ->
                "${name}へスワイプ（いま：${gestureLabel(m[Module.gestureKey(dir)])}）" to {
                    val key = Module.gestureKey(dir)
                    s.setBody(actionList(this, listOf(
                        "なし" to { save(m.with(key, null)) },
                        "通知を開く" to { save(m.with(key, "shade")) },
                        "アプリ一覧を開く" to { save(m.with(key, "drawer")) },
                        "アプリを開く…" to { s.setBody(AppPicker(this, repo) { app -> save(m.with(key, "app:${app.key}")) }) },
                    )))
                }
            }
            ModuleType.SLIDER -> {
                val f = m[Module.P_FUNCTION] ?: "volume"
                listOf(
                    "音量にする" to { save(m.with(Module.P_FUNCTION, "volume")) },
                    "明るさにする" to { save(m.with(Module.P_FUNCTION, "brightness")) },
                    "アプリを開くバネにする" to { save(m.with(Module.P_FUNCTION, "app")) },
                ).filter { !it.first.startsWith(when (f) { "brightness" -> "明るさ"; "app" -> "アプリ"; else -> "音量" }) } + if (f == "app") listOf(
                    "上端で開くアプリ（いま：${m[Module.P_TOP_APP]?.let { repo.find(it) }?.let { repo.label(it) } ?: "なし"}）" to appChooser(Module.P_TOP_APP),
                    "下端で開くアプリ（いま：${m[Module.P_BOTTOM_APP]?.let { repo.find(it) }?.let { repo.label(it) } ?: "なし"}）" to appChooser(Module.P_BOTTOM_APP),
                ) else emptyList()
            }
            ModuleType.MEDIA -> listOf("通知へのアクセスの設定" to { showBadgeSheet() })
            ModuleType.WEATHER -> listOf(
                "地点を選ぶ（いま：${m[Module.P_CITY] ?: "未設定"}）" to { s.setBody(PlacePicker(this) { p -> save(m.with(Module.P_CITY, p.name).with(Module.P_LAT, p.lat.toString()).with(Module.P_LON, p.lon.toString())) }) },
                (if (m[Module.P_MINMAX] == "0") "最高・最低を出す" else "最高・最低を隠す") to { save(m.with(Module.P_MINMAX, if (m[Module.P_MINMAX] == "0") null else "0")) },
                "タップで開くアプリ（いま：${m[Module.P_TAP_APP]?.let { repo.find(it) }?.let { repo.label(it) } ?: "なし（天気を取り直す）"}）" to appChooser(Module.P_TAP_APP),
            ) + listOfNotNull(m[Module.P_TAP_APP]?.let { "タップで開くアプリをやめる" to { save(m.with(Module.P_TAP_APP, null)) } })
            else -> emptyList()
        }
        s.setBody(actionList(this, items + listOfNotNull(frameItem(m)) + pageMoveItems(m) + listOf("外す" to { board().remove(m); done() }),
            note = "大きさは右下のつまみ、場所はドラッグで変えられます。"))
    }

    /** 天気の地点を選ぶパネルを、すぐに開く（置いた直後や、地点がまだないときにタップしたとき）。 */
    private fun weatherPlaceSheet(m: Module) = openSheet("天気の地点") { s ->
        s.setTabs(emptyList(), 0) {}
        s.setBody(PlacePicker(this) { p ->
            board().replace(m.with(Module.P_CITY, p.name).with(Module.P_LAT, p.lat.toString()).with(Module.P_LON, p.lon.toString()))
            done()
        })
    }

    /** 別のページへ移す項目（ページが 2 つ以上あるとき）。 */
    private fun pageMoveItems(m: Module): List<Pair<String, () -> Unit>> {
        // めくれるパネルの中のアプリは、ページを移せない（パネルごと移す）
        if (activeBoard?.inBook == true) return emptyList()
        val count = store.home.pages.size
        return (0 until count).filter { it != page }.map { p ->
            "${p + 1} ページ目へ移す" to {
                if (store.moveToPage(page, m, p)) done() else toast("${p + 1} ページ目に空きがありません")
            }
        }
    }

    /** アプリのパッドの設定。 */
    private fun padSheet(m: Module) {
        val app = m[Module.P_APP]?.let { repo.find(it) } ?: return
        val second = m[Module.P_LONG_APP]?.let { repo.find(it) }
        openSheet(repo.label(app)) { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOf(
                "アプリを変える" to {
                    s.setBody(AppPicker(this, repo) { new -> board().replace(m.with(Module.P_APP, new.key)); done() })
                },
                (if (second == null) "裏地のアプリを決める" else "裏地のアプリ（いま：${repo.label(second)}）") to {
                    s.setBody(AppPicker(this, repo) { new -> board().replace(m.with(Module.P_LONG_APP, new.key)); done() })
                },
            ) + listOfNotNull(
                second?.let { "裏地のアプリをやめる" to { board().replace(m.with(Module.P_LONG_APP, null)); done() } },
                // このパッドだけ、名前を隠す・出す
                if (padLabelFor(m) == PadLabel.NONE) "このパッドのアプリ名を出す" to { board().replace(m.with(Module.P_LABEL, "show")); done() }
                else "このパッドのアプリ名を隠す（アイコンを大きく）" to { board().replace(m.with(Module.P_LABEL, "hide")); done() },
            ) + appItems(app, s) + pageMoveItems(m) + listOf(
                "外す" to { board().remove(m); done() },
            ), note = "裏地のアプリを決めると、パッドの端が少しめくれます。そこをつまんでめくると裏地のアプリが見え、めくりきって離すと開きます。めくる向きは「…」→「裏地をめくる向き」で変えられます。"))
        }
    }

    /** 名前・アイコン・アプリ情報（パッドと下段で共通）。 */
    private fun appItems(app: AppEntry, s: Sheet): List<Pair<String, () -> Unit>> = listOf(
        "名前を変える（いま：${repo.label(app)}）" to {
            s.setBody(renameBody(this, repo.label(app), app.label.takeIf { store.nameOf(app.key) != null }) { name ->
                store.setName(app.key, name?.takeIf { it != app.label })
                done()
                if (drawer.visibility == View.VISIBLE) drawer.rebuild()
            })
        },
        "アイコンの描き方（いま：${iconModeLabel(app)}）" to {
            s.setBody(iconModePicker(this, repo, app) { traced -> setIconMode(app, traced) })
        },
        "アイコンの絵を変える（いま：${repo.genre(app).label}）" to {
            s.setBody(genrePicker(this, store.genreOf(app.key)) { g ->
                store.setGenre(app.key, g)
                repo.forgetGuesses(app)
                done()
                if (drawer.visibility == View.VISIBLE) drawer.rebuild()
            })
        },
        "ジャンル（アプリ一覧の巾着）など、まとめて設定（いま：${repo.shelf(app).label}）" to { showAppSettings(app) },
        "アプリ情報・アンインストール" to { repo.openDetails(app) },
    )

    /** めくれるパネルの設定（布を足す・外す、めくる向き、ページを移す、外す）。 */
    private fun bookSheet(m: Module) {
        val sheets = m.sheets
        val idx = (bookPages[m.id] ?: 0).coerceIn(0, sheets.size - 1)
        openSheet("めくれるパネル（${idx + 1} / ${sheets.size} 枚目）") { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOfNotNull(
                "布を足す（今の布の次に）" to {
                    board().replace(m.withSheets(sheets.toMutableList().apply { add(idx + 1, Page(emptyList())) }))
                    bookPages[m.id] = idx + 1
                    done()
                },
                if (sheets.size > 1) "この布を外す（${idx + 1} 枚目と、中のアプリ）" to {
                    confirm("${idx + 1} 枚目の布と、そこに置いたアプリを外します。") {
                        board().replace(m.withSheets(sheets.filterIndexed { i, _ -> i != idx }))
                        bookPages[m.id] = (idx - 1).coerceAtLeast(0)
                        done()
                    }
                } else null,
                "めくる向き（いま：${store.bookPeel.label}）" to {
                    s.setBody(actionList(this, PeelFrom.entries.map { p -> p.label to { store.bookPeel = p; done() } }))
                },
            ) + pageMoveItems(m) + listOf("外す（中のアプリも）" to { board().remove(m); done() }),
                note = "中のアプリは、編集モードで布の上を触って並べ替えます（空いたマスをタップで追加）。大きさは右下のつまみで変えられ、中のマスの数も合わせて変わります。"))
        }
    }

    /** アプリを今のページに置く。めくれるパネルがあれば、その今の布（なければ次の布から順に）の空いたマスに入れる。 */
    private fun addToHome(app: AppEntry): Boolean {
        val props = mapOf(Module.P_APP to app.key)
        for (b in store.modules(page).filter { it.type == ModuleType.BOOK }) {
            val count = b.sheets.size
            val start = bookPages[b.id] ?: 0
            for (k in 0 until count) {
                val i = (start + k) % count
                if (store.bookBoard(page, b.id, i).add(ModuleType.APP, props) != null) return true
            }
        }
        return store.add(page, ModuleType.APP, props) != null
    }

    /** 巾着（フォルダ）の設定。 */
    private fun folderSheet(m: Module) {
        val name = m[Module.P_NAME] ?: "フォルダ"
        openSheet("巾着：$name") { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOf(
                "中のアプリを選ぶ（いま ${m.folderApps.size} 個）" to {
                    s.setBody(AppMultiPicker(this, repo, m.folderApps) { keys ->
                        board().replace(m.with(Module.P_APPS, keys.joinToString("\n")))
                        done()
                    })
                },
                "名前を変える" to {
                    s.setBody(renameBody(this, name, null) { new ->
                        board().replace(m.with(Module.P_NAME, new?.trim()?.ifEmpty { null } ?: "フォルダ"))
                        done()
                    })
                },
            ) + pageMoveItems(m) + listOf("外す" to { board().remove(m); done() }),
                note = "ホーム画面で巾着をタップすると、口が開いて中のアプリが出てきます。"))
        }
    }

    /** 下段の枠をタップしたとき。 */
    private fun showDockSheet(index: Int) {
        val key = store.home.dock.getOrNull(index)
        val drawerButton = listOf("アプリ一覧のボタンを置く" to { store.setDockSlot(index, LayoutStore.DRAWER_KEY); done() })
        if (key == LayoutStore.DRAWER_KEY) {
            openSheet("アプリ一覧のボタン") { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(actionList(this, listOf(
                    "アプリに変える" to { s.setBody(AppPicker(this, repo) { new -> store.setDockSlot(index, new.key); done() }) },
                    "外す" to { store.setDockSlot(index, null); done() },
                ), note = "タップすると、アプリ一覧が開きます。"))
            }
            return
        }
        val app = key?.let { repo.find(it) }
        if (app == null) {
            openSheet("下段に置く") { s ->
                // 「アプリ」と「アプリ一覧のボタン」から選ぶ
                fun show(tab: Int) = s.setBody(if (tab == 0) AppPicker(this, repo) { picked -> store.setDockSlot(index, picked.key); done() }
                    else actionList(this, drawerButton, note = "ほかのアプリと同じ大きさのボタンです。タップすると、アプリ一覧が開きます。"))
                s.setTabs(listOf("アプリ", "アプリ一覧のボタン"), 0) { show(it) }
                show(0)
            }
            return
        }
        openSheet(repo.label(app)) { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOf(
                "アプリを変える" to { s.setBody(AppPicker(this, repo) { new -> store.setDockSlot(index, new.key); done() }) },
            ) + drawerButton.map { (_, act) -> "アプリ一覧のボタンに変える" to act } + appItems(app, s) + listOf("外す" to { store.setDockSlot(index, null); done() })))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // アプリ一覧
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 下段のファスナーと、アプリ一覧の開閉をつなぐ
    // ------------------------------------------------------------------

    /** アプリ一覧を、下段のファスナーから出しているか（ファスナーの上まで、下からせり上がる形）。 */
    private var zipDrawer = false

    private val zipLink = object : ZipperTrack.Listener {
        override fun onZip(value: Float) {
            if (value <= 0f && !zipDrawer) return
            if (!zipDrawer) beginZipDrawer()
            placeZipDrawer(value)
        }

        override fun onZipSettled(open: Boolean) {
            if (!zipDrawer) return
            if (open) {
                placeZipDrawer(1f)
            } else {
                endZipDrawer()
            }
        }
    }

    /** アプリ一覧を切って出す線（画面の中の位置）。ファスナーは隠さない。 */
    private fun zipLineY(): Int {
        if (dock.zipper == null) return root.height
        val loc = IntArray(2); dock.getLocationInWindow(loc)
        val r = IntArray(2); root.getLocationInWindow(r)
        // ポケットの上の縁（布の端）の線。アプリ一覧は、ポケットの口の奥から出てくるように、この線で切って出す
        return (loc[1] - r[1] + dock.mouthY).toInt()
    }

    /** ファスナーから出すアプリ一覧の用意：ファスナーの上までの大きさにし、下から出す。 */
    private fun beginZipDrawer() {
        zipDrawer = true
        drawer.animate().cancel()
        drawer.reset()
        drawer.fromRight = false
        if (!drawer.searchAtBottom) drawer.searchAtBottom = true
        val h = zipLineY()
        drawer.layoutParams = (drawer.layoutParams as FrameLayout.LayoutParams).apply { height = h; gravity = Gravity.TOP }
        // 下はファスナーが続くので、ナビゲーションバーの分は空けない
        drawer.applyInsets(drawerInsetTop, 0)
        drawer.visibility = View.VISIBLE
        drawer.translationY = h.toFloat()
        // アプリ一覧を指で下げたら、ファスナーも閉じていく。離したら、近いほうへ
        drawer.onPull = { f -> dock.zipper?.snapTo(1f - f) }
        drawer.onPullEnd = { close -> dock.zipper?.let { z -> if (close) z.close() else z.reopen() } }
    }

    /** 開き具合 [value] に合わせて、アプリ一覧をファスナーの線からせり上げる（線より下は見せない）。 */
    private fun placeZipDrawer(value: Float) {
        val h = drawer.layoutParams.height.takeIf { it > 0 } ?: zipLineY()
        val ty = h * (1f - value)
        drawer.translationY = ty
        drawer.clipBounds = android.graphics.Rect(0, 0, root.width, (h - ty).toInt().coerceAtLeast(0))
    }

    /** ファスナーを閉じきったら、アプリ一覧を片付けて、いつもの大きさに戻す。 */
    private fun endZipDrawer() {
        zipDrawer = false
        drawer.hideKeyboard()
        drawer.visibility = View.GONE
        drawer.clipBounds = null
        drawer.translationY = 0f
        drawer.onPull = null
        drawer.onPullEnd = null
        drawer.layoutParams = (drawer.layoutParams as FrameLayout.LayoutParams).apply { height = FrameLayout.LayoutParams.MATCH_PARENT }
        drawer.applyInsets(drawerInsetTop, drawerInsetBottom)
    }

    private var drawerInsetTop = 0
    private var drawerInsetBottom = 0

    fun openDrawer() {
        if (zipDrawer) return
        // 右から出す。検索欄は親指の届く下
        val side = true
        drawer.fromRight = side
        if (drawer.searchAtBottom != side) drawer.searchAtBottom = side
        if (drawer.visibility != View.VISIBLE) {
            drawer.reset()
            drawer.visibility = View.VISIBLE
            if (side) drawer.translationX = root.width.toFloat() else drawer.translationY = root.height.toFloat()
        }
        drawer.animate().translationX(0f).translationY(0f).setDuration(220).withEndAction(null).start()
    }

    private fun closeDrawer() {
        // ファスナーから出していたら、ファスナーを閉じる（アプリ一覧もいっしょに沈む）
        if (zipDrawer) {
            val z = dock.zipper
            if (z == null) { endZipDrawer(); return }
            val h = drawer.layoutParams.height.takeIf { it > 0 } ?: 1
            // 指で下げていたら、その位置から閉じる
            z.snapTo(1f - drawer.translationY / h)
            z.close()
            return
        }
        drawer.hideKeyboard()
        val a = drawer.animate().setDuration(200)
        if (drawer.fromRight) a.translationX(root.width.toFloat()) else a.translationY(root.height.toFloat())
        a.withEndAction { drawer.visibility = View.GONE }.start()
    }

    /** アプリ一覧でアプリを長押ししたとき。 */
    /** アプリ一覧でアプリを長押ししたとき：そのアプリの設定（名前・アイコン・ジャンル・置き場所）を 1 枚で。 */
    private fun showAppSettings(app: AppEntry) {
        val s = sheet?.takeIf { it.parent != null && it.tag == APP_SETTINGS }
        if (s != null) {
            fillAppSettings(s, app)
            return
        }
        openSheet(repo.label(app)) { sh ->
            sh.tag = APP_SETTINGS
            sh.setTabs(emptyList(), 0) {}
            fillAppSettings(sh, app)
        }
    }

    private fun fillAppSettings(s: Sheet, app: AppEntry) {
        s.setTitle(repo.label(app))
        s.setBody(AppSettingsView(this, repo, store, app,
            onChanged = {
                s.setTitle(repo.label(app))
                rebuildHome()
                if (drawer.visibility == View.VISIBLE) drawer.rebuild()
            },
            onNavigate = { other -> fillAppSettings(s, other) },
            actions = { placeActions(app, s) }))
    }

    /** アプリの置き場所などのボタン（パネル／ホーム・下段・アプリ情報）。 */
    private fun placeActions(app: AppEntry, s: Sheet): List<Pair<String, () -> Unit>> {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val onPage = store.pageOfApp(app.key)
        if (onPage != null) {
            items += "ホームから外す（${onPage + 1} ページ目）" to {
                store.removeApp(app.key)
                rebuildHome()
                fillAppSettings(s, app)
            }
        } else {
            items += "ホームに追加（${page + 1} ページ目）" to {
                if (addToHome(app)) {
                    rebuildHome()
                    toast("${repo.label(app)} をホームに置きました")
                    fillAppSettings(s, app)
                } else toast("このページに空いているマスがありません")
            }
        }
        val slots = store.home.dock
        if (app.key !in slots) {
            items += "下段に置く…" to {
                s.setBody(actionList(this, slots.mapIndexed { i, key ->
                    val now = when (key) {
                        null -> "空き"
                        LayoutStore.DRAWER_KEY -> "アプリ一覧のボタン"
                        else -> repo.find(key)?.let { a -> repo.label(a) } ?: "空き"
                    }
                    "${i + 1} 番目（いま：$now）" to { store.setDockSlot(i, app.key); rebuildHome(); fillAppSettings(s, app) }
                } + listOf("やめる" to { fillAppSettings(s, app) })))
            }
        }
        items += "アプリを開く" to { closeSheet(); repo.launch(app, root) }
        items += "アプリ情報・アンインストール" to { repo.openDetails(app) }
        return items
    }

    private fun iconModeLabel(app: AppEntry) = if (repo.traced(app)) "アプリのアイコンをなぞる" else "ジャンルのアイコン"

    private fun setIconMode(app: AppEntry, traced: Boolean) {
        store.setIconTraced(app.key, traced)
        done()
        if (drawer.visibility == View.VISIBLE) drawer.rebuild()
    }

    private fun versionName() =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    // ------------------------------------------------------------------
    // ホームアプリとしての設定
    // ------------------------------------------------------------------

    private fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    private fun makeBanner(): View = PatchLayout(this, PatchPainter.Shape.Round(40f)).apply {
        addView(TextView(context).apply {
            text = "タップして、ホームアプリに設定"
            setTextColor(Thread.COLOR)
            textSize = 14f
            gravity = Gravity.CENTER
            setShadowLayer(dp(0.6f), 0f, dp(1f), Thread.SHADOW)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setOnClickListener {
            val rm = getSystemService(RoleManager::class.java)
            val intent = if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else {
                Intent(Settings.ACTION_HOME_SETTINGS)
            }
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(intent, REQUEST_HOME) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } }
        }
    }

    /** 通知を開く（公開 API ではないが、多くのホームアプリが使っている方法）。 */
    @SuppressLint("WrongConstant")
    fun expandNotifications() {
        runCatching {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BIND_WIDGET || requestCode == REQUEST_CONFIGURE_WIDGET) {
            if (pendingWidget == -1) return
            when {
                resultCode != RESULT_OK -> cancelWidget()
                requestCode == REQUEST_BIND_WIDGET -> configureWidget()
                else -> finishWidget()
            }
            return
        }
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || !ready) return
        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                // 配置ファイルは数百 KB 程度。大きすぎるファイルは読まない
                val bytes = input.readBytes()
                require(bytes.size < 4 * 1024 * 1024) { "ファイルが大きすぎます" }
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull() ?: return toast("ファイルを読めませんでした")
        val result = runCatching { MpcImport.read(text, repo) { store.newId() } }
            .getOrElse { return toast("MPC の配置として読めませんでした（${it.message}）") }
        val pages = result.home.pages.size
        val note = buildString {
            append("${pages} ページ、${result.placed} 個の部品を読み込み、今の配置と置き換えます。")
            append("（${result.home.cols} 列 × ${result.home.rows} 行、下段のポケットは隠します）")
            if (result.skipped.isNotEmpty()) append("\n\n見つからず飛ばすもの：${result.skipped.take(12).joinToString("、")}")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("MPC の配置を読み込む")
            .setMessage(note)
            .setPositiveButton("置き換える") { _, _ ->
                store.home = result.home.copy(dock = store.home.dock)
                result.names.forEach { (k, v) -> store.setName(k, v) }
                page = 0
                rebuildHome()
                toast("MPC の配置を読み込みました")
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    companion object {
        private const val REQUEST_HOME = 21
        private const val REQUEST_IMPORT = 31
        private const val REQUEST_BIND_WIDGET = 41
        private const val REQUEST_CONFIGURE_WIDGET = 42

        /** 布のない部品（ワッペンに載せるかどうかを選べる）。 */
        private val BARE = setOf(ModuleType.CLOCK, ModuleType.TIME, ModuleType.DATE, ModuleType.TITLE, ModuleType.CALENDAR, ModuleType.WEATHER)

        /** ステッチの枠を付け外しできる部品。 */
        private val FRAMEABLE = BARE + setOf(ModuleType.AGENDA, ModuleType.BATTERY, ModuleType.MEDIA, ModuleType.SLIDER,
            ModuleType.WIDGET, ModuleType.ALARM, ModuleType.EVENTS)

        /** アプリの設定のパネルに付ける目印（前後のアプリへ移るとき、同じパネルの中身だけ替える）。 */
        private const val APP_SETTINGS = "appSettings"
    }

    // ------------------------------------------------------------------
    // 画面全体：布地の背景と、上下左右のスワイプ
    // ------------------------------------------------------------------

    /**
     * ホーム画面のいちばん外側。布地を敷き、スワイプを拾う。
     * 上へ：指についてアプリ一覧がせり上がる。下へ：通知を開く。左右へ：ページを移る（マス目が指についてくる）。
     * 編集モードやパネルを開いている間は、スワイプを拾わない。
     */
    private inner class HomeRoot(context: Context) : FrameLayout(context) {
        private val fabric = Fabric.paint(context, Cloth.BASE)
        private val seam = context.stitch(1.4f)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0: まだ, 1: 上へ（一覧）, 2: 下へ（通知）, 3: 左右（ページ）, 4: 最後のページで左へ（右からアプリ一覧）
        private var velocity: VelocityTracker? = null

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPaint(fabric)
            // 画面全体をステッチでぐるりと囲む
            drawScreenSeam(canvas, seam)
        }

        private fun swipesAllowed() = !editing && sheet == null && popover == null &&
            !(drawer.visibility == View.VISIBLE && drawer.translationY == 0f)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!swipesAllowed()) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    mode = 0
                    longPressCancelled = false
                    AppWidgets.touchClaimed = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    // 指が少しでも動いたら、下の部品の長押しを取り消す（ゆっくりスワイプしたときに、編集モードに入らないように）
                    if (!longPressCancelled && hypot(ev.x - downX, ev.y - downY) > slop) {
                        longPressCancelled = true
                        cancelLongPresses(this)
                    }
                    detect(ev)
                }
            }
            return mode != 0
        }

        private var longPressCancelled = false

        /** [v] と、その中の部品の長押しの待ち時間を取り消す。 */
        private fun cancelLongPresses(v: View) {
            v.cancelLongPress()
            if (v is WidgetCell) v.cancelPendingLongPress()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) cancelLongPresses(v.getChildAt(i))
        }

        /** はっきり動いたら、上・下・左右のどれかを決める。 */
        private fun detect(ev: MotionEvent) {
            if (mode != 0) return
            val dx = ev.x - downX
            val dy = ev.y - downY
            // ウィジェットの中の一覧が指を使っているときは、上下の動きはウィジェットに任せる（左右はページの切り替え）
            if (abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f && !AppWidgets.touchClaimed) {
                mode = if (dy < 0) 1 else 2
                if (mode == 1) {
                    drawer.reset()
                    drawer.fromRight = false
                    if (!drawer.searchAtBottom) drawer.searchAtBottom = true
                    drawer.visibility = View.VISIBLE
                    drawer.translationY = height.toFloat()
                }
            } else if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.3f) {
                val last = store.home.pages.size - 1
                // 左右でページを移る。最後のページでさらに左へスワイプすると、右からアプリ一覧が出てくる
                if (dx < 0 && page == last) {
                    mode = 4
                    drawer.reset()
                    drawer.fromRight = true
                    if (!drawer.searchAtBottom) drawer.searchAtBottom = true
                    drawer.visibility = View.VISIBLE
                    drawer.translationX = width.toFloat()
                } else if (store.home.pages.size > 1) {
                    mode = 3
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!swipesAllowed()) return false
            velocity?.addMovement(ev)
            val dx = ev.x - downX
            val dy = ev.y - downY
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    detect(ev)
                    when (mode) {
                        1 -> drawer.translationY = (height + dy).coerceIn(0f, height.toFloat())
                        4 -> drawer.translationX = (width + dx).coerceIn(0f, width.toFloat())
                        3 -> {
                            // 端のページでは、それ以上めくれないことが分かるよう重くする
                            val atEdge = (dx > 0 && page == 0) || (dx < 0 && page == store.home.pages.size - 1)
                            grid.translationX = if (atEdge) dx * 0.25f else dx
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    when (mode) {
                        1 -> if (-dy > height * 0.15f || vy < -dp(800f)) openDrawer() else closeDrawer()
                        2 -> if (dy > dp(40f)) expandNotifications()
                        4 -> if (-dx > width * 0.15f || vx < -dp(700f)) openDrawer() else closeDrawer()
                        3 -> when {
                            dx < -width * 0.18f || vx < -dp(700f) -> goToPage(page + 1)
                            dx > width * 0.18f || vx > dp(700f) -> goToPage(page - 1)
                            else -> grid.animate().translationX(0f).setDuration(160).start()
                        }
                    }
                    mode = 0
                }
            }
            return true
        }
    }
}
