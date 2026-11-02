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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * ホーム画面。上から、マス目に並べた部品（時計・予定・電池・アプリ）と、下段のポケット（よく使うアプリ）。
 *
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
    private lateinit var dock: PocketDock
    private lateinit var drawer: DrawerView
    private lateinit var banner: View
    private lateinit var editBar: View
    private lateinit var anchor: View

    /** ホーム画面を組み立て終えたか（不具合の記録を見せているときは false）。 */
    private var ready = false
    private var editing = false
    private var sheet: Sheet? = null
    private var insetTop = 0
    private var insetBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        // 前回落ちた・固まった記録があれば、まずそれを見せる（ホーム画面は組み立てない）
        if (Diagnostics.showIfAny(this) { recreate() }) return
        ready = true

        store = LayoutStore(this)
        repo = AppRepository(this)
        repo.start()
        if (store.dock == null) store.dock = repo.defaultDock().map { it.key }

        root = HomeRoot(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = makeBanner()
        grid = ModuleGrid(this).apply {
            onRowsChanged = { store.rows = it }
            setOnLongClickListener { enterEdit(); true }
            onEmptyTap = { c, r -> showCellSheet(c, r) }
            onModuleTap = { m -> showModuleSheet(m) }
            onRemove = { m -> store.remove(m); rebuildHome() }
            onChange = { old, new -> store.replace(old, new); rebuildHome() }
        }
        dock = PocketDock(this).apply {
            setOnLongClickListener { enterEdit(); true }
            onSlotTap = { i -> showDockSheet(i) }
            onSlotRemove = { i -> store.setDockSlot(i, null); rebuildHome() }
        }
        val side = dpi(8f)
        column.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(70f)).apply {
            setMargins(side, dpi(4f), side, 0)
        })
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(side, 0, side, 0)
        })
        column.addView(dock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(128f)).apply {
            setMargins(side, 0, side, dpi(4f))
        })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        editBar = makeEditBar()
        editBar.visibility = View.GONE
        root.addView(editBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpi(70f), Gravity.TOP).apply {
            setMargins(dpi(6f), 0, dpi(6f), 0)
        })

        drawer = DrawerView(this, repo, onMenu = { app, v -> showDrawerMenu(app, v) }, onClose = { closeDrawer() })
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // メニューを指の位置などに出すための目印
        anchor = View(this)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            insetTop = bars.top
            insetBottom = bars.bottom
            column.setPadding(0, bars.top, 0, bars.bottom)
            (editBar.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            editBar.requestLayout()
            drawer.applyInsets(bars.top, max(bars.bottom, ime.bottom))
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!ready) return
        // ホーム画面にいるときにホームボタンを押したら、開いているものを閉じて元に戻す
        sheet?.dismiss()
        if (editing) exitEdit()
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ready) return
        when {
            sheet != null -> sheet?.dismiss()
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

    private fun rebuildHome() {
        grid.removeAllViews()
        for (m in store.modules) {
            val view: View = when (m.type) {
                ModuleType.CLOCK -> ClockView(this)
                ModuleType.AGENDA -> AgendaView(this)
                ModuleType.BATTERY -> BatteryPatch(this)
                // アンインストールされたアプリ（や一時的に使えない仕事用のアプリ）は表示しないだけで、配置は残す
                ModuleType.APP -> repo.find(m.arg)?.let { app -> cellFor(app) } ?: continue
            }
            if (m.type != ModuleType.APP) view.setOnLongClickListener { enterEdit(); true }
            grid.addModule(view, m)
        }
        dock.setCells(store.dock.orEmpty().map { key -> key?.let { repo.find(it) }?.let { cellFor(it) } })
        grid.editing = editing
        dock.editing = editing
    }

    private fun cellFor(app: AppEntry) = AppCell(this, app, repo, repo.genre(app)) { enterEdit() }

    private fun refreshModules() {
        for (i in 0 until grid.childCount) {
            when (val v = grid.getChildAt(i)) {
                is AgendaView -> v.reload()
                is BatteryPatch -> v.refresh()
            }
        }
    }

    // ------------------------------------------------------------------
    // 編集モード
    // ------------------------------------------------------------------

    private fun enterEdit() {
        if (editing) return
        editing = true
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        grid.editing = true
        dock.editing = true
        editBar.visibility = View.VISIBLE
        editBar.alpha = 0f
        editBar.animate().alpha(1f).setDuration(150).start()
    }

    private fun exitEdit() {
        editing = false
        grid.editing = false
        dock.editing = false
        editBar.animate().alpha(0f).setDuration(150).withEndAction { editBar.visibility = View.GONE }.start()
    }

    /** 編集モードの間、画面の上に出す帯。説明と「完了」「…」。 */
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
        menu.add("アプリ一覧を開く").setOnMenuItemClickListener { exitEdit(); openDrawer(); true }
        menu.add("ホームアプリを切り替える").setOnMenuItemClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            true
        }
        menu.add("FlipHome ${versionName()}").isEnabled = false
    }.show()

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

    /** 空いたマスをタップしたとき：アプリか部品を選んで置く。 */
    private fun showCellSheet(col: Int, row: Int) = openSheet("マスに置く") { s ->
        fun appTab() = s.setBody(AppPicker(this, repo) { app ->
            store.placeAt(ModuleType.APP, col, row, app.key)
            closeSheet()
            rebuildHome()
        })
        fun moduleTab() = s.setBody(modulePicker(this) { type ->
            if (store.placeAt(type, col, row) == null) toast("ここには置けません")
            closeSheet()
            rebuildHome()
        })
        s.setTabs(listOf("アプリ", "部品"), 0) { if (it == 0) appTab() else moduleTab() }
        appTab()
    }

    /** 置いてある部品をタップしたとき。 */
    private fun showModuleSheet(m: Module) {
        if (m.type != ModuleType.APP) {
            openSheet(m.type.label) { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(actionList(this, listOf("ホームから外す" to { store.remove(m); closeSheet(); rebuildHome() }),
                    note = "大きさは右下のつまみ、場所はドラッグで変えられます。"))
            }
            return
        }
        val app = repo.find(m.arg) ?: return
        appSheet(app.label, app,
            change = { new -> store.replace(m, m.copy(arg = new.key)) },
            remove = { store.remove(m) })
    }

    /** 下段の枠をタップしたとき。 */
    private fun showDockSheet(index: Int) {
        val app = store.dock?.getOrNull(index)?.let { repo.find(it) }
        if (app == null) {
            openSheet("下段に置くアプリ") { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(AppPicker(this, repo) { picked ->
                    store.setDockSlot(index, picked.key)
                    closeSheet()
                    rebuildHome()
                })
            }
            return
        }
        appSheet(app.label, app,
            change = { new -> store.setDockSlot(index, new.key) },
            remove = { store.setDockSlot(index, null) })
    }

    /** アプリのパッドの設定：アプリを変える・アイコンを変える・アプリ情報・外す。 */
    private fun appSheet(title: String, app: AppEntry, change: (AppEntry) -> Unit, remove: () -> Unit) = openSheet(title) { s ->
        s.setTabs(emptyList(), 0) {}
        fun actions() = s.setBody(actionList(this, listOf(
            "アプリを変える" to {
                s.setBody(AppPicker(this, repo) { new -> change(new); closeSheet(); rebuildHome() })
            },
            "アイコンを変える（いま：${repo.genre(app).label}）" to {
                s.setBody(genrePicker(this, store.genreOf(app.key)) { g ->
                    store.setGenre(app.key, g)
                    closeSheet()
                    rebuildHome()
                })
            },
            "アプリ情報・アンインストール" to { repo.openDetails(app) },
            "外す" to { remove(); closeSheet(); rebuildHome() },
        )))
        actions()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // アプリ一覧
    // ------------------------------------------------------------------

    fun openDrawer() {
        if (drawer.visibility != View.VISIBLE) {
            drawer.reset()
            drawer.visibility = View.VISIBLE
            drawer.translationY = root.height.toFloat()
        }
        drawer.animate().translationY(0f).setDuration(220).withEndAction(null).start()
    }

    private fun closeDrawer() {
        drawer.hideKeyboard()
        drawer.animate().translationY(root.height.toFloat()).setDuration(200)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    /** アプリ一覧でアプリを長押ししたとき。 */
    private fun showDrawerMenu(app: AppEntry, view: View) = PopupMenu(this, view, Gravity.CENTER).apply {
        val onHome = store.has(ModuleType.APP, app.key)
        if (onHome) {
            menu.add("ホームから外す").setOnMenuItemClickListener {
                store.modules.filter { it.type == ModuleType.APP && it.arg == app.key }.forEach { store.remove(it) }
                rebuildHome()
                true
            }
        } else {
            menu.add("ホームに追加").setOnMenuItemClickListener {
                if (store.add(ModuleType.APP, app.key)) {
                    rebuildHome()
                    toast("${app.label} をホームに置きました")
                } else {
                    toast("ホームに空いているマスがありません")
                }
                true
            }
        }
        val slots = store.dock ?: List(LayoutStore.DOCK_SLOTS) { null }
        if (app.key !in slots) {
            val sub = menu.addSubMenu("下段に置く")
            slots.forEachIndexed { i, key ->
                val now = key?.let { repo.find(it)?.label } ?: "空き"
                sub.add("${i + 1} 番目（$now）").setOnMenuItemClickListener {
                    store.setDockSlot(i, app.key)
                    rebuildHome()
                    true
                }
            }
        }
        menu.add("アプリ情報・アンインストール").setOnMenuItemClickListener { repo.openDetails(app); true }
    }.show()

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

    companion object {
        private const val REQUEST_HOME = 21
    }

    // ------------------------------------------------------------------
    // 画面全体：布地の背景と、上下のスワイプ
    // ------------------------------------------------------------------

    /**
     * ホーム画面のいちばん外側。布地を敷き、縦のスワイプを拾う。
     * 上へ：指についてアプリ一覧がせり上がり、離した位置と速さで開くか戻るかを決める。下へ：通知を開く。
     * 編集モードやパネルを開いている間は、スワイプを拾わない。
     */
    private inner class HomeRoot(context: Context) : FrameLayout(context) {
        private val fabric = Fabric.paint(context, Cloth.BASE)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0: まだ, 1: 上へ（一覧）, 2: 下へ（通知）
        private var velocity: VelocityTracker? = null

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) = canvas.drawPaint(fabric)

        private fun swipesAllowed() = !editing && sheet == null &&
            !(drawer.visibility == View.VISIBLE && drawer.translationY == 0f)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!swipesAllowed()) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    mode = 0
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    detect(ev)
                }
            }
            return mode != 0
        }

        /** 縦にはっきり動いたら、上へか下へかを決める。 */
        private fun detect(ev: MotionEvent) {
            if (mode != 0) return
            val dy = ev.y - downY
            if (abs(dy) > slop * 1.5f && abs(dy) > abs(ev.x - downX) * 1.3f) {
                mode = if (dy < 0) 1 else 2
                if (mode == 1) {
                    drawer.reset()
                    drawer.visibility = View.VISIBLE
                    drawer.translationY = height.toFloat()
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!swipesAllowed()) return false
            velocity?.addMovement(ev)
            val dy = ev.y - downY
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (detect(ev).let { mode == 1 }) drawer.translationY = (height + dy).coerceIn(0f, height.toFloat())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    when (mode) {
                        1 -> if (-dy > height * 0.15f || vy < -dp(800f)) openDrawer() else closeDrawer()
                        2 -> if (dy > dp(40f)) expandNotifications()
                    }
                    mode = 0
                }
            }
            return true
        }
    }
}
