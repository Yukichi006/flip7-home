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
 * 上から：アプリのアイコンと名前・前後のアプリへ移るボタン、名前、アイコンの描き方、ジャンル、置き場所などのボタン。
 *
 * 名前・アイコンの描き方・ジャンルは、押したらすぐに変わり、パネルは閉じない（続けてほかの設定もできる）。
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
        section("アイコンの描き方")
        iconRow()
        section("ジャンル（アイコンと、アプリ一覧の巾着の振り分け）")
        column.addView(GenreGrid(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
            val g = repo.genre(app).label + if (store.genreOf(app.key) == null) "（おまかせ）" else ""
            canvas.drawText("ジャンル：$g", tx, height / 2f + sub.textSize + context.dp(4f), sub)
        }
    }

    /** ジャンルのマス目（4 列）。いちばん上の「おまかせ」は、アプリから推し量ったものに戻す。押したらすぐに変わる。 */
    private inner class GenreGrid : ViewGroup(context) {
        private val items: List<Genre?> = listOf<Genre?>(null) + Genre.entries
        private val current = store.genreOf(app.key)

        init {
            for (g in items) addView(Tile(g))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cw = w / COLS
            val ch = context.dpi(80f)
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, ch * ((childCount + COLS - 1) / COLS))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (i % COLS) * c.measuredWidth
                val y = (i / COLS) * c.measuredHeight
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        private inner class Tile(private val g: Genre?) : View(context) {
            private val text = context.threadText(10.5f).apply { textAlign = Paint.Align.CENTER }
            private val auto = context.threadText(14f).apply { textAlign = Paint.Align.CENTER }
            private val ring = context.stitch(1.2f)
            private val iconPx = context.dpi(34f)

            init {
                isClickable = true
                contentDescription = g?.label ?: "おまかせ"
                setOnClickListener {
                    if (g != current) {
                        store.setGenre(app.key, g)
                        changed()
                    }
                }
            }

            override fun onDraw(canvas: Canvas) {
                val top = context.dp(8f)
                if (g != null) canvas.drawBitmap(GenreIcons.bitmap(g, iconPx), (width - iconPx) / 2f, top, null)
                else canvas.drawText("自動", width / 2f, top + iconPx * 0.62f, auto)
                val label = g?.label ?: "おまかせ"
                val t = TextUtils.ellipsize(label, text, width - context.dp(6f), TextUtils.TruncateAt.END)
                canvas.drawText(t, 0, t.length, width / 2f, top + iconPx + context.dp(18f), text)
                if (g == current) {
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
