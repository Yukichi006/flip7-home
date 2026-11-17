package io.github.yukichi006.fliphome

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ホーム画面を実際に組み立てて、指で操作するテスト。
 * 編集モードで、めくれるパネルの中の空いたマスをタップ →「部品」→「巾着」と選ぶと、タップしたそのマスに置かれるか。
 * （アプリを選ぶ場合も、置き先のマスと盤は同じ仕組みで決まる。テストの中にはアプリが入っていないので、巾着で確かめる）
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class PanelTouchTest {
    private val cols = 3
    private val rows = 4

    /** パネル（[cols]×[rows]）を 1 ページ目の左上に置いたホーム画面を開き、編集モードに入る。 */
    private fun openEditing(sheets: List<Page> = listOf(Page(emptyList()))): HomeActivity {
        val store = T.freshStore()
        store.home = Home(5, 8, listOf(Page(listOf(Module(7, ModuleType.BOOK, 0, 0, cols, rows).withSheets(sheets)))),
            pocket = false, dock = List(LayoutStore.DOCK_SLOTS) { null })
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        T.idle()
        // ページのマス目（パネルの布の中のマス目ではないほう）を長押しして、編集モードに入る
        val page = T.all(activity.window.decorView).filterIsInstance<ModuleGrid>().first { it.parent !is SheetView }
        assertTrue("長押しで編集モードに入る", page.performLongClick())
        T.idle()
        return activity
    }

    /** いちばん上の布の中のマス目。 */
    private fun sheetGrid(activity: HomeActivity): ModuleGrid {
        val panel = T.all(activity.window.decorView).filterIsInstance<BookPanel>().single()
        val top = panel.getChildAt(panel.childCount - 1) as SheetView
        return top.grid
    }

    private fun book(activity: HomeActivity) = LayoutStore(activity).modules(0).first { it.type == ModuleType.BOOK }

    /** 布のマス ([c], [r]) の真ん中をタップし、出てきたパネルで「部品」→「巾着」を選ぶ。 */
    private fun placePouchAt(activity: HomeActivity, c: Int, r: Int) {
        val grid = sheetGrid(activity)
        assertTrue("布のマス目に大きさがある（${grid.width}×${grid.height}）", grid.width > 0 && grid.height > 0)
        T.tap(activity, grid, (c + 0.5f) * grid.width / cols, (r + 0.5f) * grid.height / rows)
        val root: View = activity.window.decorView
        val parts = T.byText(root, "部品")
        assertNotNull("マス ($c, $r) をタップすると、置くものを選ぶパネルが出る", parts)
        parts!!.performClick()
        T.idle()
        val pouch = T.byText(root, "巾着")
        assertNotNull("「部品」の一覧に巾着がある", pouch)
        pouch!!.performClick()
        T.idle()
    }

    @Test
    fun `パネルの中の空いたマスをタップすると、そのマスに置かれる`() {
        for (r in 0 until rows) for (c in 0 until cols) {
            val activity = openEditing()
            placePouchAt(activity, c, r)
            val inside = book(activity).sheets[0].modules
            assertEquals("マス ($c, $r) をタップしたとき、パネルの布に置かれた部品", listOf(ModuleType.FOLDER), inside.map { it.type })
            assertEquals("マス ($c, $r) をタップしたとき、置かれたマス", c to r, inside[0].col to inside[0].row)
            assertEquals("ページのマス目には、パネルのほかに何も増えない", 1, LayoutStore(activity).modules(0).size)
            activity.finish()
        }
    }

    @Test
    fun `すでにアプリがある布でも、空いたマスに置ける`() {
        val existing = Page(listOf(Module(8, ModuleType.FOLDER, 0, 0, 1, 1, mapOf(Module.P_NAME to "前からある袋"))))
        for ((c, r) in listOf(1 to 0, 2 to 1, 0 to 3, 2 to 3)) {
            val activity = openEditing(listOf(existing))
            placePouchAt(activity, c, r)
            val inside = book(activity).sheets[0].modules
            assertEquals("マス ($c, $r) に置いたあとの数", 2, inside.size)
            assertTrue("マス ($c, $r) に置かれている", inside.any { it.col == c && it.row == r })
            activity.finish()
        }
    }

    @Test
    fun `ページの空いたマスをタップすると、ページのそのマスに置かれる`() {
        val activity = openEditing()
        val page = T.all(activity.window.decorView).filterIsInstance<ModuleGrid>().first { it.parent !is SheetView }
        val store = LayoutStore(activity)
        val (c, r) = 4 to 5
        T.tap(activity, page, (c + 0.5f) * page.width / store.home.cols, (r + 0.5f) * page.height / store.home.rows)
        T.byText(activity.window.decorView, "部品")!!.performClick()
        T.idle()
        T.byText(activity.window.decorView, "巾着")!!.performClick()
        T.idle()
        val placed = LayoutStore(activity).modules(0).filter { it.type == ModuleType.FOLDER }
        assertEquals(listOf(c to r), placed.map { it.col to it.row })
        assertEquals("パネルの中には入らない", 0, book(activity).sheets[0].modules.size)
    }
}
