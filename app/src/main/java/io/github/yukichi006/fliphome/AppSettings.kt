package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * アプリ 1 つの設定を、1 枚にまとめたもの（アプリ一覧でアプリを長押ししたときに出す）。
 * 上から：アプリのアイコンと名前・前後のアプリへ移るボタン、名前、ジャンル（アプリ一覧の巾着）、アイコンの描き方、
 * アイコンの絵、置き場所などのボタン。
 *
 * 名前・ジャンル・アイコンは、押したらすぐに変わり、パネルは閉じない（続けてほかの設定もできる）。
 * 「前のアプリ」「次のアプリ」で、あいうえお順に隣のアプリの設定へ移れるので、ジャンルの振り分けを続けてできる。
 */
@SuppressLint("ViewConstructor")
class AppSettingsView(
    context: Context,
    private val repo: AppRepository,
    private val store: LayoutStore,
    private val app: AppEntry,
    /** 何かを変えたとき（ホーム画面とアプリ一覧を描き直してもらう）。 */
    private val onChanged: () -> Unit,
    /** 前後のアプリへ移るとき。 */
    private val onNavigate: (AppEntry) -> Unit,
    /** 置き場所などのボタン（パネルに追加・下段に置く・アプリ情報など）。 */
    private val actions: () -> List<Pair<String, () -> Unit>>,
) : ScrollView(context) {
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(4f), 0, context.dpi(16f))
    }

    init {
        isVerticalScrollBarEnabled = false
        addView(column)
        render()
    }

    /** 中身を作り直す（スクロールの位置はそのまま）。 */
    private fun render() {
        val y = scrollY
        column.removeAllViews()
        column.addView(Header(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(96f)))
        navRow()
        section("名前")
        nameRow()
        section("ジャンル（アプリ一覧の巾着）")
        val shelf = store.shelfOf(app.key)
        grid(listOf(Choice("おまかせ", null, shelf == null) { store.setShelf(app.key, null) }) +
            Shelf.entries.map { sh -> Choice(sh.label, sh.icon, shelf == sh) { store.setShelf(app.key, sh) } })
        section("アイコンの描き方")
        iconRow()
        section("アイコンの絵")
        val genre = store.genreOf(app.key)
        grid(listOf(Choice("おまかせ", null, genre == null) { store.setGenre(app.key, null) }) +
            Genre.entries.map { g -> Choice(g.label, g, genre == g) { store.setGenre(app.key, g) } }, small = true)
        section("置き場所など")
        for ((label, act) in actions()) {
            column.addView(Pill(context, label).apply { setOnClickListener { act() } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(54f)))
        }
        post { scrollTo(0, y) }
    }

    private fun changed() {
        render()
        onChanged()
    }

    private fun section(title: String) {
        column.addView(TextView(context).apply {
            text = title
            setTextColor(Thread.DIM)
            textSize = 12.5f
            letterSpacing = 0.08f
            setPadding(context.dpi(8f), context.dpi(16f), context.dpi(8f), context.dpi(4f))
        })
    }

    /** 前後のアプリ（あいうえお順）へ移るボタン。 */
    private fun navRow() {
        val apps = repo.apps
        val i = apps.indexOfFirst { it.key == app.key }
        val prev = apps.getOrNull(i - 1)
        val next = apps.getOrNull(i + 1)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Pill(context, prev?.let { "‹ ${repo.label(it)}" } ?: "‹ （最初）").apply {
            isEnabled = prev != null
            alpha = if (prev != null) 1f else 0.4f
            setOnClickListener { prev?.let(onNavigate) }
        }, LinearLayout.LayoutParams(0, context.dpi(50f), 1f))
        row.addView(Pill(context, next?.let { "${repo.label(it)} ›" } ?: "（最後） ›").apply {
            isEnabled = next != null
            alpha = if (next != null) 1f else 0.4f
            setOnClickListener { next?.let(onNavigate) }
        }, LinearLayout.LayoutParams(0, context.dpi(50f), 1f))
        column.addView(row)
    }

    /** 名前の欄と「保存」（付け替えていれば「元に戻す」も）。 */
    private fun nameRow() {
        val edit = EditText(context).apply {
            background = null
            setTextColor(Thread.COLOR)
            setHintTextColor(Thread.DIM)
            textSize = 16f
            isSingleLine = true
            setText(repo.label(app))
            hint = app.label
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(context.dpi(8f), 0, context.dpi(8f), 0)
        }
        fun save(name: String?) {
            store.setName(app.key, name?.trim()?.takeIf { it.isNotEmpty() && it != app.label })
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edit.windowToken, 0)
            changed()
        }
        edit.setOnEditorActionListener { _, _, _ -> save(edit.text.toString()); true }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
            addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(0, context.dpi(60f), 1f))
        row.addView(Pill(context, "保存", selected = true).apply { setOnClickListener { save(edit.text.toString()) } },
            LinearLayout.LayoutParams(context.dpi(96f), context.dpi(60f)))
        column.addView(row)
        if (store.nameOf(app.key) != null) {
            column.addView(Pill(context, "元の名前（${app.label}）に戻す").apply { setOnClickListener { save(null) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(50f)))
        }
    }

    /** 「ジャンルのアイコン」「アプリのアイコンをなぞる」の切り替え。 */
    private fun iconRow() {
        val traced = repo.traced(app)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ジャンルのアイコン" to false, "アイコンをなぞる" to true).forEach { (label, t) ->
            row.addView(Pill(context, label, selected = traced == t).apply {
                setOnClickListener { if (traced != t) { store.setIconTraced(app.key, t); changed() } }
            }, LinearLayout.LayoutParams(0, context.dpi(50f), 1f))
        }
        column.addView(row)
    }

    /** いちばん上：今のアイコン（大きめ）と名前、今のジャンル。 */
    private inner class Header : View(context) {
        private val name = context.threadText(18f, bold = true)
        private val sub = context.threadText(12f, color = Thread.DIM)
        private val patch = PatchPainter(context, PatchPainter.Shape.Round(20f))
        private val iconPx = context.dpi(54f)

        override fun onDraw(canvas: Canvas) {
            patch.draw(canvas, width, height)
            val x0 = patch.contentPadding
            canvas.drawBitmap(repo.icon(app, iconPx) { invalidate() }, x0, (height - iconPx) / 2f, null)
            val tx = x0 + iconPx + context.dp(14f)
            val t = TextUtils.ellipsize(repo.label(app), name, width - tx - x0, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, tx, height / 2f - context.dp(2f), name)
            val g = repo.shelf(app).label + if (store.shelfOf(app.key) == null) "（おまかせ）" else ""
            canvas.drawText("ジャンル：$g", tx, height / 2f + sub.textSize + context.dp(4f), sub)
        }
    }

    /** 選べるもの 1 つ（名前・絵・今選んでいるか・押したとき）。 */
    private class Choice(val label: String, val icon: Genre?, val selected: Boolean, val pick: () -> Unit)

    private fun grid(items: List<Choice>, small: Boolean = false) {
        column.addView(ChoiceGrid(items, small), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 選べるもののマス目（4 列）。いちばん上の「おまかせ」は、アプリから推し量ったものに戻す。押したらすぐに変わる。 */
    private inner class ChoiceGrid(items: List<Choice>, small: Boolean) : ViewGroup(context) {
        private val tileH = context.dpi(if (small) 74f else 80f)

        init {
            for (c in items) addView(Tile(c, small))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cw = w / COLS
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, tileH * ((childCount + COLS - 1) / COLS))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (i % COLS) * c.measuredWidth
                val y = (i / COLS) * c.measuredHeight
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        private inner class Tile(private val c: Choice, small: Boolean) : View(context) {
            private val text = context.threadText(if (small) 10f else 10.5f).apply { textAlign = Paint.Align.CENTER }
            private val auto = context.threadText(14f).apply { textAlign = Paint.Align.CENTER }
            private val ring = context.stitch(1.2f)
            private val iconPx = context.dpi(if (small) 30f else 34f)

            init {
                isClickable = true
                contentDescription = c.label
                setOnClickListener {
                    if (!c.selected) {
                        c.pick()
                        repo.forgetGuesses(app)
                        changed()
                    }
                }
            }

            override fun onDraw(canvas: Canvas) {
                val top = context.dp(8f)
                val g = c.icon
                if (g != null) canvas.drawBitmap(GenreIcons.bitmap(g, iconPx), (width - iconPx) / 2f, top, null)
                else canvas.drawText("自動", width / 2f, top + iconPx * 0.62f, auto)
                val t = TextUtils.ellipsize(c.label, text, width - context.dp(6f), TextUtils.TruncateAt.END)
                canvas.drawText(t, 0, t.length, width / 2f, top + iconPx + context.dp(18f), text)
                if (c.selected) {
                    val r = RectF(context.dp(3f), context.dp(2f), width - context.dp(3f), height - context.dp(2f))
                    canvas.drawRoundRect(r, context.dp(14f), context.dp(14f), ring)
                }
            }
        }
    }

    companion object {
        private const val COLS = 4
    }
}
