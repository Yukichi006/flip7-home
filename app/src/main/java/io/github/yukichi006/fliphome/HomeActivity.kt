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
 * - 何もない所を長押し：部品の追加、ホームアプリの切り替え
 * - アプリや部品を長押し：外す・アプリ情報など
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
    private lateinit var anchor: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()

        store = LayoutStore(this)
        repo = AppRepository(this)
        repo.start()
        if (store.dock == null) store.dock = repo.defaultDock().map { it.key }

        root = HomeRoot(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = makeBanner()
        grid = ModuleGrid(this).apply {
            onRowsChanged = { store.rows = it }
            setOnLongClickListener { showHomeMenu(); true }
        }
        dock = PocketDock(this)
        val side = dpi(8f)
        column.addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(70f)).apply {
            setMargins(side, dpi(4f), side, 0)
        })
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(side, 0, side, 0)
        })
        column.addView(dock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(136f)).apply {
            setMargins(side, 0, side, dpi(4f))
        })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawer = DrawerView(this, repo, onMenu = { app, v -> showDrawerMenu(app, v) }, onClose = { closeDrawer() })
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 何もない所を長押ししたとき、メニューを指の位置に出すための目印
        anchor = View(this)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            column.setPadding(0, bars.top, 0, bars.bottom)
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
        // 暗い布の上なので、ステータスバーの文字は白にする
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onResume() {
        super.onResume()
        banner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
        refreshModules()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ホーム画面にいるときにホームボタンを押したら、アプリ一覧を閉じる
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
        // ホーム画面そのものは戻るボタンで閉じない
    }

    override fun onDestroy() {
        repo.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
                ModuleType.APP -> repo.find(m.arg)?.let { app ->
                    AppCell(this, app, repo) { cell -> showPadMenu(cell, m) }
                } ?: continue
            }
            if (m.type != ModuleType.APP) view.setOnLongClickListener { showModuleMenu(it, m); true }
            grid.addModule(view, m)
        }
        dock.setCells(store.dock.orEmpty().mapNotNull { repo.find(it) }.map { app ->
            AppCell(this, app, repo) { cell -> showDockMenu(cell) }
        })
    }

    private fun refreshModules() {
        for (i in 0 until grid.childCount) {
            when (val v = grid.getChildAt(i)) {
                is AgendaView -> v.reload()
                is BatteryPatch -> v.refresh()
            }
        }
    }

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

    // ------------------------------------------------------------------
    // メニュー
    // ------------------------------------------------------------------

    private fun popup(anchorView: View, build: PopupMenu.() -> Unit) {
        PopupMenu(this, anchorView, Gravity.CENTER).apply(build).show()
    }

    /** ホーム画面のアプリのパッドを長押ししたとき。 */
    private fun showPadMenu(cell: AppCell, module: Module) = popup(cell) {
        menu.add("ホームから外す").setOnMenuItemClickListener { store.remove(module); rebuildHome(); true }
        appItems(cell.app)
    }

    private fun showDockMenu(cell: AppCell) = popup(cell) {
        menu.add("下段から外す").setOnMenuItemClickListener {
            store.dock = store.dock.orEmpty() - cell.app.key
            rebuildHome()
            true
        }
        appItems(cell.app)
    }

    private fun showModuleMenu(view: View, module: Module) = popup(view) {
        menu.add("ホームから外す").setOnMenuItemClickListener { store.remove(module); rebuildHome(); true }
    }

    private fun showDrawerMenu(app: AppEntry, view: View) = popup(view) {
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
                    Toast.makeText(this@HomeActivity, "${app.label} をホームに置きました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@HomeActivity, "ホームに空いているマスがありません", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        val dockKeys = store.dock.orEmpty()
        if (app.key !in dockKeys) {
            val sub = menu.addSubMenu("下段に置く")
            if (dockKeys.size < PocketDock.MAX) {
                sub.add("右端に足す").setOnMenuItemClickListener {
                    store.dock = dockKeys + app.key
                    rebuildHome()
                    true
                }
            }
            dockKeys.forEachIndexed { i, key ->
                val name = repo.find(key)?.label ?: "（使えないアプリ）"
                sub.add("$name と入れ替える").setOnMenuItemClickListener {
                    store.dock = dockKeys.toMutableList().also { it[i] = app.key }
                    rebuildHome()
                    true
                }
            }
        }
        appItems(app)
    }

    private fun PopupMenu.appItems(app: AppEntry) {
        // アンインストールは、アプリ情報の画面のボタンから行う（ほかのアプリを消す権限は持たない）
        menu.add("アプリ情報・アンインストール").setOnMenuItemClickListener { repo.openDetails(app); true }
    }

    /** 何もない所を長押ししたとき。 */
    private fun showHomeMenu() {
        anchor.x = root.lastTouchX
        anchor.y = root.lastTouchY
        popup(anchor) {
            val add = menu.addSubMenu("部品を追加")
            for (type in listOf(ModuleType.CLOCK, ModuleType.AGENDA, ModuleType.BATTERY)) {
                add.add(type.label).setOnMenuItemClickListener {
                    if (store.add(type)) rebuildHome()
                    else Toast.makeText(this@HomeActivity, "${type.w}×${type.h} マスの空きがありません", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            menu.add("アプリ一覧").setOnMenuItemClickListener { openDrawer(); true }
            menu.add("ホームアプリを切り替える").setOnMenuItemClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                true
            }
            menu.add("FlipHome ${versionName()}").isEnabled = false
        }
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

    companion object {
        private const val REQUEST_HOME = 21
    }

    // ------------------------------------------------------------------
    // 画面全体：布地の背景と、上下のスワイプ
    // ------------------------------------------------------------------

    /**
     * ホーム画面のいちばん外側。布地を敷き、縦のスワイプを拾う。
     * 上へ：指についてアプリ一覧がせり上がり、離した位置と速さで開くか戻るかを決める。下へ：通知を開く。
     */
    private inner class HomeRoot(context: Context) : FrameLayout(context) {
        private val fabric = Fabric.paint(context, Cloth.BASE)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0: まだ, 1: 上へ（一覧）, 2: 下へ（通知）
        private var velocity: VelocityTracker? = null
        var lastTouchX = 0f
        var lastTouchY = 0f

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) = canvas.drawPaint(fabric)

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (drawer.visibility == View.VISIBLE && drawer.translationY == 0f) return false
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
            velocity?.addMovement(ev)
            val dy = ev.y - downY
            when (ev.actionMasked) {
                // 何もない所から触り始めたときは、ここで初めて動きを見る
                MotionEvent.ACTION_DOWN -> if (velocity == null) velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
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
