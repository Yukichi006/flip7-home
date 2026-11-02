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
import kotlin.math.max

/**
 * ホーム画面。上から、マス目に並べた部品（パッド・ノブ・時計・予定・電池）、ページの点、下段のポケット。
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
            onEmptyTap = { c, r -> showCellSheet(c, r) }
            onModuleTap = { m -> showModuleSheet(m) }
            onRemove = { m -> store.remove(page, m); rebuildHome() }
            onChange = { new -> store.replace(page, new); rebuildHome() }
        }
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
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(side, dpi(4f), side, 0)
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

        drawer = DrawerView(this, repo, onMenu = { app, v -> showDrawerMenu(app, v) }, onClose = { closeDrawer() })
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
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

    override fun onStop() {
        super.onStop()
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
        val home = store.home
        page = page.coerceIn(0, home.pages.size - 1)
        grid.configure(home.cols, home.rows)
        grid.removeAllViews()
        for (m in store.modules(page)) {
            val view: View = when (m.type) {
                ModuleType.CLOCK -> ClockView(this)
                ModuleType.AGENDA -> AgendaView(this)
                ModuleType.BATTERY -> BatteryPatch(this)
                // アンインストールされたアプリ（や一時的に使えない仕事用のアプリ）は表示しないだけで、配置は残す
                ModuleType.APP -> m[Module.P_APP]?.let { repo.find(it) }?.let { app ->
                    PadView(this, app, repo, second = m[Module.P_LONG_APP]?.let { repo.find(it) }) { enterEdit() }
                } ?: continue
                ModuleType.FOLDER -> FolderKnob(this, m[Module.P_NAME] ?: "フォルダ",
                    m.folderApps.mapNotNull { repo.find(it)?.component?.packageName },
                    onOpen = { v -> showFolder(m, v) }, onLongPress = { enterEdit() })
            }
            if (m.type !in setOf(ModuleType.APP, ModuleType.FOLDER)) view.setOnLongClickListener { enterEdit(); true }
            grid.addModule(view, m)
        }
        dots.count = home.pages.size
        dots.current = page
        dots.visibility = if (home.pages.size > 1) View.VISIBLE else View.GONE
        dock.visibility = if (home.pocket) View.VISIBLE else View.GONE
        dock.setCells(home.dock.map { key -> key?.let { repo.find(it) }?.let { app -> PadView(this, app, repo, framed = false) { enterEdit() } } })
        grid.editing = editing
        dock.editing = editing
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
    // ページ
    // ------------------------------------------------------------------

    /** [target] のページへ移る。横にすべらせて入れ替え、ノブを慣性で少し回す。 */
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
            grid.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            spinKnobs(dir)
        }.start()
    }

    private fun spinKnobs(dir: Int) {
        for (i in 0 until grid.childCount) {
            val knob = grid.getChildAt(i) as? FolderKnob ?: continue
            android.animation.ValueAnimator.ofFloat(0f, -dir * 140f).apply {
                duration = 700
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { knob.spin = it.animatedValue as Float }
                start()
            }
        }
    }

    // ------------------------------------------------------------------
    // ノブ（フォルダ）
    // ------------------------------------------------------------------

    private fun showFolder(m: Module, anchor: View) {
        popover?.dismiss()
        val apps = m.folderApps.mapNotNull { repo.find(it) }
        if (apps.isEmpty()) {
            toast("フォルダは空です。長押しして編集モードから、アプリを入れてください")
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
        menu.add("ページを追加").setOnMenuItemClickListener {
            page = store.addPage(page)
            rebuildHome()
            toast("${page + 1} ページ目を追加しました")
            true
        }
        if (home.pages.size > 1) {
            menu.add("このページを消す（${page + 1} ページ目）").setOnMenuItemClickListener {
                confirm("${page + 1} ページ目と、そこに置いたものを消します。") {
                    store.removePage(page)
                    page = (page - 1).coerceAtLeast(0)
                    rebuildHome()
                }
                true
            }
        }
        val cols = menu.addSubMenu("横の列の数（いま ${home.cols}）")
        for (c in LayoutStore.MIN_COLS..LayoutStore.MAX_COLS) {
            cols.add("$c 列").setOnMenuItemClickListener { store.setGrid(c, home.rows); rebuildHome(); true }
        }
        val rows = menu.addSubMenu("縦の行の数（いま ${home.rows}）")
        for (r in LayoutStore.MIN_ROWS..LayoutStore.MAX_ROWS) {
            rows.add("$r 行").setOnMenuItemClickListener { store.setGrid(home.cols, r); rebuildHome(); true }
        }
        menu.add(if (home.pocket) "下段のポケットを隠す" else "下段のポケットを出す").setOnMenuItemClickListener {
            store.setPocket(!home.pocket)
            rebuildHome()
            true
        }
        menu.add(if (Badges.enabled(this@HomeActivity)) "通知の印：使っています（設定）" else "通知の印を使う…").setOnMenuItemClickListener {
            showBadgeSheet()
            true
        }
        menu.add("アプリ一覧を開く").setOnMenuItemClickListener { exitEdit(); openDrawer(); true }
        menu.add("ホームアプリを切り替える").setOnMenuItemClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            true
        }
        menu.add("FlipHome ${versionName()}").isEnabled = false
    }.show()

    private fun confirm(message: String, ok: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("消す") { _, _ -> ok() }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun showBadgeSheet() = openSheet("通知の印") { s ->
        s.setTabs(emptyList(), 0) {}
        s.setBody(actionList(this, listOf(
            "通知へのアクセスの設定を開く" to { Badges.openSettings(this) },
        ), note = "通知が来ているアプリのパッドに、糸の玉の印を付けます。\n" +
            "そのために「通知へのアクセス」で FlipHome を許可してください。\n" +
            "使うのは「どのアプリに通知が来ているか」だけで、通知の中身は読みません。保存も、外へ送ることもしません。"))
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
            store.placeAt(page, ModuleType.APP, col, row, mapOf(Module.P_APP to app.key))
            done()
        })
        fun moduleTab() = s.setBody(modulePicker(this) { type ->
            val placed = store.placeAt(page, type, col, row, if (type == ModuleType.FOLDER) mapOf(Module.P_NAME to "フォルダ") else emptyMap())
            if (placed == null) {
                toast("ここには置けません")
                done()
            } else if (type == ModuleType.FOLDER) {
                rebuildHome()
                folderSheet(placed)
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
            else -> openSheet(m.type.label) { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(actionList(this, pageMoveItems(m) + listOf("外す" to { store.remove(page, m); done() }),
                    note = "大きさは右下のつまみ、場所はドラッグで変えられます。"))
            }
        }
    }

    /** 別のページへ移す項目（ページが 2 つ以上あるとき）。 */
    private fun pageMoveItems(m: Module): List<Pair<String, () -> Unit>> {
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
                    s.setBody(AppPicker(this, repo) { new -> store.replace(page, m.with(Module.P_APP, new.key)); done() })
                },
                (if (second == null) "下に引いて開くアプリを決める" else "下に引いて開くアプリ（いま：${repo.label(second)}）") to {
                    s.setBody(AppPicker(this, repo) { new -> store.replace(page, m.with(Module.P_LONG_APP, new.key)); done() })
                },
            ) + listOfNotNull(
                second?.let { "下に引いて開くアプリをやめる" to { store.replace(page, m.with(Module.P_LONG_APP, null)); done() } },
            ) + appItems(app, s) + pageMoveItems(m) + listOf(
                "外す" to { store.remove(page, m); done() },
            ), note = "下に引いて開くアプリを決めると、パッドを下に引っ張ったときにそのアプリが開きます。"))
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
        "アイコンを変える（いま：${repo.genre(app).label}）" to {
            s.setBody(genrePicker(this, store.genreOf(app.key)) { g -> store.setGenre(app.key, g); done() })
        },
        "アプリ情報・アンインストール" to { repo.openDetails(app) },
    )

    /** ノブ（フォルダ）の設定。 */
    private fun folderSheet(m: Module) {
        val name = m[Module.P_NAME] ?: "フォルダ"
        openSheet("ノブ：$name") { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOf(
                "中のアプリを選ぶ（いま ${m.folderApps.size} 個）" to {
                    s.setBody(AppMultiPicker(this, repo, m.folderApps) { keys ->
                        store.replace(page, m.with(Module.P_APPS, keys.joinToString("\n")))
                        done()
                    })
                },
                "名前を変える" to {
                    s.setBody(renameBody(this, name, null) { new ->
                        store.replace(page, m.with(Module.P_NAME, new?.trim()?.ifEmpty { null } ?: "フォルダ"))
                        done()
                    })
                },
            ) + pageMoveItems(m) + listOf("外す" to { store.remove(page, m); done() }),
                note = "ホーム画面でノブをタップすると、中のアプリが吹き出しで開きます。"))
        }
    }

    /** 下段の枠をタップしたとき。 */
    private fun showDockSheet(index: Int) {
        val app = store.home.dock.getOrNull(index)?.let { repo.find(it) }
        if (app == null) {
            openSheet("下段に置くアプリ") { s ->
                s.setTabs(emptyList(), 0) {}
                s.setBody(AppPicker(this, repo) { picked -> store.setDockSlot(index, picked.key); done() })
            }
            return
        }
        openSheet(repo.label(app)) { s ->
            s.setTabs(emptyList(), 0) {}
            s.setBody(actionList(this, listOf(
                "アプリを変える" to { s.setBody(AppPicker(this, repo) { new -> store.setDockSlot(index, new.key); done() }) },
            ) + appItems(app, s) + listOf("外す" to { store.setDockSlot(index, null); done() })))
        }
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
        val onPage = store.pageOfApp(app.key)
        if (onPage != null) {
            menu.add("ホームから外す（${onPage + 1} ページ目）").setOnMenuItemClickListener {
                store.removeApp(app.key)
                rebuildHome()
                true
            }
        } else {
            menu.add("ホームに追加（${page + 1} ページ目）").setOnMenuItemClickListener {
                if (store.add(page, ModuleType.APP, mapOf(Module.P_APP to app.key)) != null) {
                    rebuildHome()
                    toast("${repo.label(app)} をホームに置きました")
                } else {
                    toast("このページに空いているマスがありません")
                }
                true
            }
        }
        val slots = store.home.dock
        if (app.key !in slots) {
            val sub = menu.addSubMenu("下段に置く")
            slots.forEachIndexed { i, key ->
                val now = key?.let { repo.find(it)?.let { a -> repo.label(a) } } ?: "空き"
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
    // 画面全体：布地の背景と、上下左右のスワイプ
    // ------------------------------------------------------------------

    /**
     * ホーム画面のいちばん外側。布地を敷き、スワイプを拾う。
     * 上へ：指についてアプリ一覧がせり上がる。下へ：通知を開く。左右へ：ページを移る（マス目が指についてくる）。
     * 編集モードやパネルを開いている間は、スワイプを拾わない。
     */
    private inner class HomeRoot(context: Context) : FrameLayout(context) {
        private val fabric = Fabric.paint(context, Cloth.BASE)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var mode = 0 // 0: まだ, 1: 上へ（一覧）, 2: 下へ（通知）, 3: 左右（ページ）
        private var velocity: VelocityTracker? = null

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) = canvas.drawPaint(fabric)

        private fun swipesAllowed() = !editing && sheet == null && popover == null &&
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

        /** はっきり動いたら、上・下・左右のどれかを決める。 */
        private fun detect(ev: MotionEvent) {
            if (mode != 0) return
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                mode = if (dy < 0) 1 else 2
                if (mode == 1) {
                    drawer.reset()
                    drawer.visibility = View.VISIBLE
                    drawer.translationY = height.toFloat()
                }
            } else if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.3f && store.home.pages.size > 1) {
                mode = 3
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
