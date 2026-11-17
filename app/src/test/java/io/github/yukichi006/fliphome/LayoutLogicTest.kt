package io.github.yukichi006.fliphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * 配置の計算と保存（画面を使わない部分）のテスト。
 * ページのマス目・めくれるパネルの布に部品を置く、保存して読み戻す、大きさを変えたときに並べ直す、など。
 */
@RunWith(RobolectricTestRunner::class)
class LayoutLogicTest {
    private fun app(key: String) = mapOf(Module.P_APP to key)

    private fun storeWithBook(w: Int, h: Int, sheets: List<Page> = listOf(Page(emptyList()))): LayoutStore {
        val store = T.freshStore()
        val book = Module(7, ModuleType.BOOK, 0, 0, w, h).withSheets(sheets)
        store.home = Home(5, 8, listOf(Page(listOf(book))), pocket = false, dock = List(LayoutStore.DOCK_SLOTS) { null })
        return store
    }

    private fun book(store: LayoutStore) = store.modules(0).first { it.id == 7L }

    @Test
    fun `ページの空いたマスに、アプリを置ける`() {
        val store = T.freshStore()
        store.home = Home(5, 8, listOf(Page(emptyList())), pocket = false, dock = List(LayoutStore.DOCK_SLOTS) { null })
        val placed = store.gridBoard(0).placeAt(ModuleType.APP, 3, 5, app("a"))
        assertNotNull(placed)
        assertEquals(listOf(3 to 5), store.modules(0).map { it.col to it.row })
    }

    @Test
    fun `マス目の外や、ほかの部品と重なる所には置けない`() {
        val store = T.freshStore()
        store.home = Home(5, 8, listOf(Page(listOf(Module(1, ModuleType.APP, 2, 2, 1, 1, app("a"))))), pocket = false, dock = List(LayoutStore.DOCK_SLOTS) { null })
        val board = store.gridBoard(0)
        assertNull("重なる所", board.placeAt(ModuleType.APP, 2, 2, app("b")))
        assertNull("右の外", board.placeAt(ModuleType.APP, 5, 0, app("b")))
        assertNull("下の外", board.placeAt(ModuleType.APP, 0, 8, app("b")))
        assertEquals(1, store.modules(0).size)
    }

    @Test
    fun `めくれるパネルの布の、どのマスにもアプリを置ける`() {
        for ((w, h) in listOf(2 to 2, 3 to 4, 3 to 5, 4 to 3, 5 to 2, 3 to 6)) {
            for (r in 0 until h) for (c in 0 until w) {
                val store = storeWithBook(w, h)
                val placed = store.bookBoard(0, 7, 0).placeAt(ModuleType.APP, c, r, app("a"))
                assertNotNull("${w}×$h のパネルのマス ($c, $r)", placed)
                assertEquals("${w}×$h のパネルのマス ($c, $r)", listOf(c to r), book(store).sheets[0].modules.map { it.col to it.row })
            }
        }
    }

    @Test
    fun `パネルの布をアプリで埋め尽くせる`() {
        val store = storeWithBook(3, 4)
        for (r in 0 until 4) for (c in 0 until 3) assertNotNull("($c, $r)", store.bookBoard(0, 7, 0).placeAt(ModuleType.APP, c, r, app("a$c$r")))
        assertEquals(12, book(store).sheets[0].modules.size)
        assertNull("もう空きがない", store.bookBoard(0, 7, 0).add(ModuleType.APP, app("x")))
    }

    @Test
    fun `パネルの 2 枚目の布に置いても、1 枚目は変わらない`() {
        val first = Page(listOf(Module(8, ModuleType.APP, 0, 0, 1, 1, app("a"))))
        val store = storeWithBook(3, 4, listOf(first, Page(emptyList())))
        assertNotNull(store.bookBoard(0, 7, 1).placeAt(ModuleType.APP, 2, 3, app("b")))
        val sheets = book(store).sheets
        assertEquals(listOf("a"), sheets[0].modules.map { it[Module.P_APP] })
        assertEquals(listOf("b"), sheets[1].modules.map { it[Module.P_APP] })
    }

    @Test
    fun `置いた配置は、保存して読み戻しても同じ`() {
        val store = storeWithBook(3, 4)
        store.bookBoard(0, 7, 0).placeAt(ModuleType.APP, 1, 2, app("a"))
        store.gridBoard(0).placeAt(ModuleType.FOLDER, 4, 7, mapOf(Module.P_NAME to "袋", Module.P_APPS to "x\ny"))
        // 別の入れ物で読み直す（アプリを開き直したのと同じ）
        val again = LayoutStore(T.app)
        assertEquals(store.home, again.home)
        assertEquals(listOf("x", "y"), again.modules(0).first { it.type == ModuleType.FOLDER }.folderApps)
        assertEquals(listOf(1 to 2), again.modules(0).first { it.type == ModuleType.BOOK }.sheets[0].modules.map { it.col to it.row })
    }

    @Test
    fun `パネルを小さくしても、中のアプリはなくならない`() {
        val apps = (0 until 12).map { i -> Module(100L + i, ModuleType.APP, i % 3, i / 3, 1, 1, app("a$i")) }
        val store = storeWithBook(3, 4, listOf(Page(apps)))
        val old = book(store)
        val small = store.reflowBook(old, old.copy(w = 2, h = 2))
        val after = small.sheets.flatMap { it.modules }
        assertEquals("アプリの数", 12, after.size)
        assertEquals("同じアプリ", apps.map { it[Module.P_APP] }.toSet(), after.map { it[Module.P_APP] }.toSet())
        // どの布でも、アプリはパネルの中に収まり、重ならない
        for (sheet in small.sheets) {
            assertTrue(sheet.modules.all { it.col in 0..1 && it.row in 0..1 })
            assertEquals(sheet.modules.size, sheet.modules.map { it.col to it.row }.toSet().size)
        }
    }

    @Test
    fun `アプリを外すと、パネルの中からも外れる`() {
        val store = storeWithBook(3, 4, listOf(Page(listOf(Module(8, ModuleType.APP, 0, 0, 1, 1, app("a")), Module(9, ModuleType.APP, 1, 0, 1, 1, app("b"))))))
        assertEquals(0, store.pageOfApp("a"))
        store.removeApp("a")
        assertNull(store.pageOfApp("a"))
        assertEquals(listOf("b"), book(store).sheets[0].modules.map { it[Module.P_APP] })
    }

    @Test
    fun `縫い付けメモは、保存して読み戻しても同じ`() {
        val list = listOf(PinTask("牛乳を買う"), PinTask("\"引用\" と 改行\n も"), PinTask("済み", done = true))
        assertEquals(list, PinTask.parse(PinTask.toJson(list)))
        assertEquals(emptyList<PinTask>(), PinTask.parse(null))
        assertEquals(emptyList<PinTask>(), PinTask.parse("こわれた文字"))
    }

    // ---- なぞる刺繍：アイコンの外枠の見分け ----

    private fun closed(points: List<Pair<Float, Float>>): FloatArray = (points + points.first()).flatMap { listOf(it.first, it.second) }.toFloatArray()

    @Test
    fun `丸や角の丸い四角は、アイコンの外枠とみなす`() {
        val circle = closed((0 until 100).map { t -> val a = t / 100.0 * 2 * Math.PI; (64 + 60 * cos(a)).toFloat() to (64 + 60 * sin(a)).toFloat() })
        assertTrue("丸", StitchIcons.isFrame(circle))
        val square = closed(listOf(6f to 4f, 122f to 4f, 124f to 6f, 124f to 122f, 122f to 124f, 6f to 124f, 4f to 122f, 4f to 6f))
        assertTrue("角の丸い四角", StitchIcons.isFrame(square))
    }

    @Test
    fun `大きく書いた文字（PayPay の P）は、外枠とみなさない`() {
        // 左端から右寄りまでの横棒と、下端までの縦棒でできた、すかすかの形（端から端まで広がるが、中は詰まっていない）
        val p = closed(listOf(2f to 16f, 110f to 16f, 110f to 80f, 50f to 80f, 40f to 126f, 28f to 126f, 40f to 30f, 2f to 30f))
        assertFalse(StitchIcons.isFrame(p))
    }

    @Test
    fun `小さな形は、外枠とみなさない`() {
        val small = closed(listOf(40f to 40f, 90f to 40f, 90f to 90f, 40f to 90f))
        assertFalse(StitchIcons.isFrame(small))
    }
}
