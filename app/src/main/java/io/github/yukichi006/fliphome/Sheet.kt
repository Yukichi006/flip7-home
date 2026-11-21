package io.github.yukichi006.fliphome

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 下からせり上がるパネル（ボトムシート）。後ろは暗くし、暗い所のタップや戻るボタンで閉じる。
 * 中身は [setBody] で差し替える。上に見出しと、必要ならタブ（[setTabs]）を置く。
 */
@SuppressLint("ViewConstructor")
class Sheet(context: Context, private val bottomInset: Int, private val onClosed: () -> Unit) : FrameLayout(context) {
    private val scrim = View(context).apply {
        setBackgroundColor(0x99000000.toInt())
        setOnClickListener { dismiss() }
    }
    private val panel = PatchLayout(context, PatchPainter.Shape.Round(26f))
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = TextView(context).apply {
        setTextColor(Thread.COLOR)
        textSize = 19f
        paint.isFakeBoldText = true
        setShadowLayer(dp(0.6f), 0f, dp(1f), Thread.SHADOW)
    }
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val body = FrameLayout(context)
    private var closing = false

    init {
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(4f), dpi(2f), 0, dpi(8f))
        })
        column.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.isClickable = true
        addView(panel)
    }

    fun setTitle(text: String) { title.text = text }

    /** タブを並べる。[selected] 番目を選んだ状態にし、押されたら [onSelect]。 */
    fun setTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        tabs.removeAllViews()
        labels.forEachIndexed { i, label ->
            tabs.addView(Pill(context, label, i == selected).apply {
                setOnClickListener { setTabs(labels, i, onSelect); onSelect(i) }
            }, LinearLayout.LayoutParams(0, dpi(50f), 1f))
        }
        tabs.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setBody(view: View) {
        body.removeAllViews()
        body.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show(parent: ViewGroup) {
        // パネルは画面の高さの 3/4 ほど。下はナビゲーションバーの分だけ空ける
        panel.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (parent.height * 0.74f).toInt(), Gravity.BOTTOM).apply {
            setMargins(dpi(4f), 0, dpi(4f), bottomInset)
        }
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.translationY = parent.height.toFloat()
        panel.animate().translationY(0f).setDuration(220).start()
    }

    fun dismiss() {
        if (closing) return
        closing = true
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        scrim.animate().alpha(0f).setDuration(160).start()
        panel.animate().translationY(height.toFloat()).setDuration(180).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onClosed()
        }.start()
    }
}

/**
 * 角の丸い押しボタン。選んでいるもの（[selected]）は糸の色で塗り、そうでないものは布にステッチで縁取る。
 * 文字は見切れないように、幅に入るまで小さくし、それでも入らなければ終わりを「…」にする。
 * 「名前\n説明」のように改行を入れると、2 行目を小さく薄い文字で添える。
 */
@SuppressLint("ViewConstructor")
class Pill(context: Context, private val text: String, private val selected: Boolean = false) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Thread.COLOR else Cloth.FELT }
    private val stitch = context.stitch(1.0f, if (selected) Cloth.BASE else Thread.DIM)
    private val label = context.threadText(14f, bold = selected, color = if (selected) Cloth.BASE else Thread.COLOR).apply {
        textAlign = Paint.Align.CENTER
        if (selected) clearShadowLayer()
    }
    private val note = context.threadText(11f, color = if (selected) Cloth.BASE else Thread.DIM).apply {
        textAlign = Paint.Align.CENTER
        if (selected) clearShadowLayer()
    }
    private val lines = text.split("\n", limit = 2)
    private val baseSize = label.textSize
    private val noteSize = note.textSize
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        label.textSize = baseSize
        val w = (label.measureText(lines[0]) + context.dp(36f)).toInt()
        val h = context.dpi(if (lines.size > 1) 62f else 50f)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** [t] を幅 [maxW] に入れる：[paint] の文字を [min] まで小さくし、それでも入らなければ終わりを「…」に。 */
    private fun fit(t: String, paint: android.text.TextPaint, size: Float, min: Float, maxW: Float): CharSequence {
        paint.textSize = size
        val w = paint.measureText(t)
        if (w > maxW) paint.textSize = (size * maxW / w).coerceAtLeast(min)
        return TextUtils.ellipsize(t, paint, maxW, TextUtils.TruncateAt.END)
    }

    override fun onDraw(canvas: Canvas) {
        val m = context.dp(4f)
        rect.set(m, m, width - m, height - m)
        val r = rect.height() / 2
        canvas.drawRoundRect(rect, r, r, fill)
        rect.inset(context.dp(3.5f), context.dp(3.5f))
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stitch)
        // 文字は、内側の縫い目の丸い角にかからない幅に収める
        val maxW = width - rect.height() - context.dp(8f)
        val first = fit(lines[0], label, baseSize, context.sp(10f), maxW)
        if (lines.size == 1) {
            canvas.drawText(first, 0, first.length, width / 2f, height / 2f + label.textSize * 0.35f, label)
        } else {
            val second = fit(lines[1], note, noteSize, context.sp(9f), maxW)
            val total = label.textSize + note.textSize * 1.25f
            val top = (height - total) / 2f
            canvas.drawText(first, 0, first.length, width / 2f, top + label.textSize * 0.85f, label)
            canvas.drawText(second, 0, second.length, width / 2f, top + label.textSize + note.textSize * 1.05f, note)
        }
    }
}

/** 縦に並べた押しボタンの一覧（「アプリを変える」「外す」など）。 */
fun actionList(context: Context, items: List<Pair<String, () -> Unit>>, note: String? = null): View {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(6f), 0, 0)
    }
    for ((label, action) in items) {
        column.addView(Pill(context, label).apply { setOnClickListener { action() } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(if ('\n' in label) 66f else 54f)))
    }
    if (note != null) {
        column.addView(TextView(context).apply {
            text = note
            setTextColor(Thread.DIM)
            textSize = 13f
            setPadding(context.dpi(8f), context.dpi(12f), context.dpi(8f), 0)
        })
    }
    return ScrollView(context).apply { addView(column) }
}

/**
 * まとまりごとに見出しを付けた、押しボタンの一覧（「ホーム画面の設定」など）。見出しは糸の文字と細い縫い目。
 * [sections] は（見出し, ボタンの一覧）の並び。ボタンの名前に改行を入れると、2 行目を小さく添える。
 */
fun sectionedList(context: Context, sections: List<Pair<String, List<Pair<String, () -> Unit>>>>, note: String? = null): View {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(2f), 0, context.dpi(12f))
    }
    for ((title, items) in sections) {
        if (items.isEmpty()) continue
        column.addView(object : View(context) {
            private val text = context.threadText(12.5f, bold = true, color = Thread.DIM).apply { letterSpacing = 0.12f }
            private val seam = context.stitch(0.9f, Thread.FAINT)
            override fun onDraw(canvas: Canvas) {
                val y = height * 0.62f
                canvas.drawText(title, context.dp(10f), y + text.textSize * 0.35f, text)
                val x = context.dp(10f) + text.measureText(title) + context.dp(10f)
                canvas.drawLine(x, y, width - context.dp(10f), y, seam)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(38f)))
        for ((label, action) in items) {
            column.addView(Pill(context, label).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(if ('\n' in label) 64f else 52f)))
        }
    }
    if (note != null) {
        column.addView(TextView(context).apply {
            text = note
            setTextColor(Thread.DIM)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, context.dpi(14f), 0, 0)
        })
    }
    return ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(column) }
}

/** 置ける部品（時計・今日の予定・電池）の一覧。 */
fun modulePicker(context: Context, onPick: (ModuleType) -> Unit): View =
    actionList(
        context,
        ModuleType.entries.filter { it.addable }.map { t -> "${t.label}\n${t.note}" to { onPick(t) } },
        note = "置いたあとは、ドラッグで移動、右下のつまみで大きさを変えられます。",
    )

/**
 * アプリを選ぶ一覧。上に検索欄（ひらがな・カタカナを区別しない）、その下にアプリを名前順に並べる。
 */
@SuppressLint("ViewConstructor")
class AppPicker(context: Context, private val repo: AppRepository, private val onPick: (AppEntry) -> Unit) : LinearLayout(context) {
    private var shown: List<AppEntry> = repo.apps
    private val search = EditText(context).apply {
        background = null
        setTextColor(Thread.COLOR)
        setHintTextColor(Thread.DIM)
        hint = "アプリを検索"
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(dpi(8f), 0, dpi(8f), 0)
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? AppRowView) ?: AppRowView(context, repo)).also { it.bind(shown[position]) }
    }

    init {
        orientation = VERTICAL
        val patch = PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
            addView(search, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        addView(patch, LayoutParams(LayoutParams.MATCH_PARENT, dpi(60f)).apply { topMargin = dpi(6f) })
        addView(ListView(context).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            cacheColorHint = 0
            isVerticalScrollBarEnabled = false
            adapter = this@AppPicker.adapter
            setOnItemClickListener { _, _, pos, _ -> onPick(shown[pos]) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = Index.normalize(s.toString().trim())
                shown = if (q.isEmpty()) repo.apps else repo.apps.filter { Index.normalize(repo.label(it)).contains(q) || Index.normalize(it.label).contains(q) }
                adapter.notifyDataSetChanged()
            }
        })
    }
}

/** アプリ 1 行：ジャンルの刺繍アイコンと名前。アプリ一覧と、アプリを選ぶ一覧で使う。 */
@SuppressLint("ViewConstructor")
class AppRowView(context: Context, private val repo: AppRepository) : View(context) {
    private var app: AppEntry? = null
    private val iconPx = context.dpi(34f)
    private val label = context.threadText(15f)

    init { layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, context.dpi(56f)) }

    fun bind(entry: AppEntry) {
        app = entry
        contentDescription = entry.label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val a = app ?: return
        canvas.drawBitmap(repo.icon(a, iconPx) { invalidate() }, context.dp(4f), (height - iconPx) / 2f, null)
        val x = iconPx + context.dp(18f)
        val t = TextUtils.ellipsize(repo.label(a), label, width - x, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, x, height / 2f + label.textSize * 0.35f, label)
    }
}

/** アイコン（ジャンル）を選ぶマス目。いちばん上の「自動」は、アプリから推し量ったものに戻す。 */
fun genrePicker(context: Context, current: Genre?, onPick: (Genre?) -> Unit): View {
    val items: List<Genre?> = listOf<Genre?>(null) + Genre.entries
    val iconPx = context.dpi(36f)
    return GridView(context).apply {
        numColumns = 4
        verticalSpacing = context.dpi(4f)
        selector = android.graphics.drawable.ColorDrawable(0)
        isVerticalScrollBarEnabled = false
        setPadding(0, context.dpi(8f), 0, 0)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val g = items[position]
                return object : View(context) {
                    private val text = context.threadText(10.5f).apply { textAlign = Paint.Align.CENTER }
                    private val ring = context.stitch(1.2f)
                    init { layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, context.dpi(84f)) }
                    override fun onDraw(canvas: Canvas) {
                        val top = context.dp(8f)
                        if (g != null) canvas.drawBitmap(GenreIcons.bitmap(g, iconPx), (width - iconPx) / 2f, top, null)
                        else canvas.drawText("自動", width / 2f, top + iconPx * 0.62f, context.threadText(14f).apply { textAlign = Paint.Align.CENTER })
                        canvas.drawText(g?.label ?: "（おまかせ）", width / 2f, top + iconPx + context.dp(18f), text)
                        if (g == current) {
                            val r = RectF(context.dp(4f), context.dp(2f), width - context.dp(4f), height - context.dp(2f))
                            canvas.drawRoundRect(r, context.dp(14f), context.dp(14f), ring)
                        }
                    }
                }
            }
        }
        setOnItemClickListener { _, _, pos, _ -> onPick(items[pos]) }
    }
}

/**
 * 縫い付けメモの付箋を書く欄。上から「ジャンル」「納期（横に滑らせるつまみ）」「文字」「留めるボタン」の順
 * （キーボードが下半分をふさぐので、選ぶものを上に置く）。
 * [initial] を渡すと書き直す欄になる（中身を入れておき、ボタンは「書き直す」だけ。キーボードは自動では出さない）。
 * 書けたら [onPin] に、付箋と「続けて書くか」を渡す。ジャンルと納期は、続けて書くときもそのまま残す。
 */
fun taskInput(context: Context, initial: PinTask? = null, onPin: (PinTask, Boolean) -> Unit): View {
    val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(6f), 0, context.dpi(12f))
    }
    scroll.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    var genre = initial?.genre ?: 0
    var due: LocalDate? = initial?.dueDate
    fun caption(t: String) = TextView(context).apply {
        text = t
        setTextColor(Thread.DIM)
        textSize = 12.5f
        setPadding(context.dpi(6f), 0, 0, context.dpi(4f))
    }

    // ジャンル：布の色の札を並べる（選んだものに白い糸の縁）
    column.addView(caption("ジャンル（付箋の色）"))
    val genres = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun drawGenres() {
        genres.removeAllViews()
        PinGenre.ALL.forEachIndexed { i, g ->
            genres.addView(GenreChip(context, g, i == genre).apply { setOnClickListener { genre = i; drawGenres() } },
                LinearLayout.LayoutParams(0, context.dpi(44f), 1f))
        }
    }
    drawGenres()
    column.addView(genres, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // 納期：「今日」「今週」「今月」「なし」を、横に滑らせるつまみで選ぶ。見出しに、決まった日を添える
    val dueCaption = caption("").apply { setPadding(context.dpi(6f), context.dpi(10f), 0, context.dpi(4f)) }
    fun showDue() {
        val d = due
        dueCaption.text = if (d == null) "納期（近い順に上へ並ぶ）"
        else "納期（近い順に上へ並ぶ）：${d.monthValue}/${d.dayOfMonth}（${"月火水木金土日"[d.dayOfWeek.value - 1]}）まで"
    }
    showDue()
    column.addView(dueCaption)
    column.addView(DueSlider(context, PinTask.DUE_STOPS, PinTask.stopOf(due, LocalDate.now())) { stop ->
        due = PinTask.dueAt(stop, LocalDate.now())
        showDue()
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(52f)))

    val edit = EditText(context).apply {
        background = null
        setTextColor(Thread.COLOR)
        setHintTextColor(Thread.DIM)
        hint = "例：牛乳を買う"
        textSize = 17f
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(context.dpi(8f), 0, context.dpi(8f), 0)
        initial?.let { setText(it.text); setSelection(it.text.length) }
    }
    fun pin(keep: Boolean) {
        val t = edit.text.toString().trim()
        if (t.isEmpty()) return
        edit.setText("")
        onPin(PinTask(t, genre = genre, due = due?.toEpochDay()), keep)
    }
    column.addView(PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
        addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(60f)).apply { topMargin = context.dpi(12f) })
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    if (initial == null) {
        row.addView(Pill(context, "続けて書く").apply { setOnClickListener { pin(true) } },
            LinearLayout.LayoutParams(0, context.dpi(54f), 1f))
        row.addView(Pill(context, "留める", selected = true).apply { setOnClickListener { pin(false) } },
            LinearLayout.LayoutParams(0, context.dpi(54f), 1f))
    } else {
        row.addView(Pill(context, "書き直す", selected = true).apply { setOnClickListener { pin(false) } },
            LinearLayout.LayoutParams(0, context.dpi(54f), 1f))
    }
    column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(8f) })
    column.addView(TextView(context).apply {
        text = "付箋をつまんで引くと、はがれて完了。長押しで書き直せます。書いたことはスマホの中だけに保存します。"
        setTextColor(Thread.DIM)
        textSize = 12.5f
        setPadding(context.dpi(8f), context.dpi(12f), context.dpi(8f), 0)
    })
    edit.setOnEditorActionListener { _, _, _ -> pin(false); true }
    if (initial == null) {
        edit.requestFocus()
        edit.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, 0) }
    }
    return scroll
}

/** 付箋のジャンルを選ぶ札。その布の色で塗り、名前を墨色で書く。選んでいるものは、白い糸で縁を縫い、少し大きく見せる。 */
@SuppressLint("ViewConstructor")
class GenreChip(context: Context, private val genre: PinGenre, private val selected: Boolean) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = genre.color
        alpha = if (selected) 255 else 150
    }
    private val seam = stitchPaint(context.dp(0.9f), context.dp(2.4f), context.dp(1.8f), 0x55000000)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = Thread.COLOR
    }
    private val label = context.threadText(12.5f, bold = selected, color = 0xFF1A1A1B.toInt()).apply {
        clearShadowLayer()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = genre.name
    }

    override fun onDraw(canvas: Canvas) {
        val m = context.dp(if (selected) 2f else 4f)
        rect.set(m, m, width - m, height - m)
        val r = context.dp(5f)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(RectF(rect).apply { inset(context.dp(3f), context.dp(3f)) }, r * 0.6f, r * 0.6f, seam)
        if (selected) canvas.drawRoundRect(RectF(rect).apply { inset(-context.dp(1f), -context.dp(1f)) }, r, r, ring)
        val name = TextUtils.ellipsize(genre.name, label, rect.width() - context.dp(6f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, rect.centerX(), rect.centerY() + label.textSize * 0.36f, label)
    }
}

/**
 * 納期を選ぶ横長のつまみ（左から「今日」「今週」「今月」「なし」）。フェルトの溝の上を、生成りのつまみが滑る。
 * 指を置いた所へつまみが来て、横になぞるとついてくる（段をまたぐたびに軽く震える）。離すといちばん近い段に収まり、
 * 段が変わったら [onPick] にその番号を渡す。文字は段ごとに幅を等分するので、見切れない。
 */
@SuppressLint("ViewConstructor")
class DueSlider(context: Context, private val labels: List<String>, initial: Int, private val onPick: (Int) -> Unit) : View(context) {
    private var index = initial.coerceIn(0, labels.size - 1)
    /** つまみの位置（段の番号。なぞっている間は半端な値になる）。 */
    private var pos = index.toFloat()
    private var tick = index
    private var anim: ValueAnimator? = null
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cloth.FELT }
    private val grooveSeam = context.stitch(1.0f, Thread.DIM)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(2f), 0f, context.dp(1f), 0x88000000.toInt())
    }
    private val knobSeam = context.stitch(1.0f, Cloth.BASE)
    private val label = context.threadText(14f, color = Thread.DIM).apply { textAlign = Paint.Align.CENTER }
    private val knobLabel = context.threadText(14f, bold = true, color = Cloth.BASE).apply {
        textAlign = Paint.Align.CENTER
        clearShadowLayer()
    }
    private val rect = RectF()

    init {
        isClickable = true
        describe()
    }

    private fun describe() { contentDescription = "納期：${labels[index]}" }

    /** 段を並べる幅（溝の内側）。 */
    private fun left() = context.dp(7f)
    private fun segW() = (width - 2 * left()) / labels.size
    private fun posAt(x: Float) = ((x - left()) / segW() - 0.5f).coerceIn(0f, labels.size - 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 縦のスクロールや、パネルを閉じる動きに取られないようにする
                parent?.requestDisallowInterceptTouchEvent(true)
                anim?.cancel()
                follow(e.x)
            }
            MotionEvent.ACTION_MOVE -> follow(e.x)
            MotionEvent.ACTION_UP -> {
                follow(e.x)
                settle(pos.roundToInt())
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> settle(index)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun follow(x: Float) {
        pos = posAt(x)
        val near = pos.roundToInt()
        if (near != tick) {
            tick = near
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    /** つまみを段 [i] に収める（少し滑らせて）。段が変わっていれば知らせる。 */
    private fun settle(i: Int) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = 140
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        tick = i
        if (i != index) {
            index = i
            describe()
            onPick(i)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val m = context.dp(4f)
        rect.set(m, m, width - m, height - m)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, groove)
        rect.inset(context.dp(3.5f), context.dp(3.5f))
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, grooveSeam)
        val seg = segW()
        val mid = height / 2f
        for (i in labels.indices) {
            val t = TextUtils.ellipsize(labels[i], label, seg - context.dp(8f), TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, left() + seg * (i + 0.5f), mid + label.textSize * 0.35f, label)
        }
        // つまみ：段ひとつ分の幅の、生成りの布
        val cx = left() + seg * (pos + 0.5f)
        val half = seg / 2f - context.dp(1f)
        rect.set(cx - half, m + context.dp(2f), cx + half, height - m - context.dp(2f))
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, knob)
        rect.inset(context.dp(3f), context.dp(3f))
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, knobSeam)
        val name = labels[pos.roundToInt().coerceIn(0, labels.size - 1)]
        val t = TextUtils.ellipsize(name, knobLabel, seg - context.dp(8f), TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, cx, mid + knobLabel.textSize * 0.35f, knobLabel)
    }
}

/** 名前を入力する欄と「保存」「元に戻す」。[original] は付け替える前の名前（元に戻すと null を渡す）。 */
fun renameBody(context: Context, current: String, original: String?, onSave: (String?) -> Unit): View {
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dpi(10f), 0, 0)
    }
    val edit = EditText(context).apply {
        background = null
        setTextColor(Thread.COLOR)
        setHintTextColor(Thread.DIM)
        textSize = 17f
        isSingleLine = true
        setText(current)
        setSelection(current.length)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setPadding(context.dpi(8f), 0, context.dpi(8f), 0)
    }
    column.addView(PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
        addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(64f)))
    column.addView(Pill(context, "保存", selected = true).apply { setOnClickListener { onSave(edit.text.toString()) } },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(54f)).apply { topMargin = context.dpi(10f) })
    if (original != null) {
        column.addView(Pill(context, "元の名前（$original）に戻す").apply { setOnClickListener { onSave(null) } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(54f)))
    }
    edit.setOnEditorActionListener { _, _, _ -> onSave(edit.text.toString()); true }
    edit.requestFocus()
    edit.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, 0) }
    return column
}

/**
 * アプリを何個でも選べる一覧（フォルダの中身を選ぶ）。選んだものには糸の印（✓）を付ける。
 * 下の「決定」で、選んだ順に [onDone] に渡す。
 */
@SuppressLint("ViewConstructor")
class AppMultiPicker(
    context: Context,
    private val repo: AppRepository,
    initial: List<String>,
    private val onDone: (List<String>) -> Unit,
) : LinearLayout(context) {
    private val picked = initial.toMutableList()
    private val apps = repo.apps
    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? CheckRow) ?: CheckRow(context)).also { it.bind(apps[position], apps[position].key in picked) }
    }

    private inner class CheckRow(context: Context) : FrameLayout(context) {
        private val row = AppRowView(context, repo)
        private var checked = false
        private val mark = context.stitch(1.6f)
        private val box = context.stitch(1.0f, Thread.DIM)
        init {
            setWillNotDraw(false)
            addView(row)
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, context.dpi(56f))
        }
        fun bind(app: AppEntry, on: Boolean) { row.bind(app); checked = on; invalidate() }
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val s = dp(22f)
            val r = RectF(width - s - dp(10f), (height - s) / 2f, width - dp(10f), (height + s) / 2f)
            canvas.drawRoundRect(r, dp(6f), dp(6f), box)
            if (checked) {
                val p = android.graphics.Path().apply {
                    moveTo(r.left + s * 0.2f, r.centerY()); lineTo(r.left + s * 0.42f, r.bottom - s * 0.2f); lineTo(r.right - s * 0.15f, r.top + s * 0.15f)
                }
                canvas.drawPath(p, mark)
            }
        }
    }

    init {
        orientation = VERTICAL
        addView(ListView(context).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            cacheColorHint = 0
            isVerticalScrollBarEnabled = false
            adapter = this@AppMultiPicker.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val key = apps[pos].key
                if (key in picked) picked.remove(key) else picked.add(key)
                this@AppMultiPicker.adapter.notifyDataSetChanged()
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(Pill(context, "決定", selected = true).apply { setOnClickListener { onDone(picked.toList()) } },
            LayoutParams(LayoutParams.MATCH_PARENT, dpi(56f)))
    }
}

/**
 * アイコンの描き方を選ぶ：左は「ジャンルのアイコン」、右は「アプリのアイコンをなぞる」（アプリ本来のアイコンの形を読み取って、輪郭を縫う）。
 * どちらも、そのアプリでの実際の絵を見せる。今の描き方は縫い目の輪で囲む。
 */
fun iconModePicker(context: Context, repo: AppRepository, app: AppEntry, onPick: (traced: Boolean) -> Unit): View {
    val iconPx = context.dpi(72f)
    val current = repo.traced(app)
    fun tile(traced: Boolean) = object : View(context) {
        private val title = context.threadText(14f, bold = true).apply { textAlign = Paint.Align.CENTER }
        private val note = context.threadText(11f, color = Thread.DIM).apply { textAlign = Paint.Align.CENTER }
        private val ring = context.stitch(1.2f)
        private val patch = PatchPainter(context, PatchPainter.Shape.Round(18f))
        private var bitmap: android.graphics.Bitmap? = null

        init {
            isClickable = true
            setOnClickListener { onPick(traced) }
            if (traced) {
                bitmap = StitchIcons.cached(app, iconPx)
                if (bitmap == null) StitchIcons.load(context, app, iconPx) { bitmap = it; invalidate() }
            } else bitmap = GenreIcons.bitmap(repo.genre(app), iconPx)
        }

        override fun onDraw(canvas: Canvas) {
            patch.draw(canvas, width, height)
            val top = context.dp(28f)
            val b = bitmap
            if (b != null) canvas.drawBitmap(b, (width - iconPx) / 2f, top, null)
            else canvas.drawText("なぞっています…", width / 2f, top + iconPx / 2f, note)
            canvas.drawText(if (traced) "アプリのアイコンをなぞる" else "ジャンルのアイコン", width / 2f, top + iconPx + context.dp(30f), title)
            canvas.drawText(if (traced) "アプリ本来のアイコンの形を縫う" else "いま：${repo.genre(app).label}", width / 2f, top + iconPx + context.dp(50f), note)
            if (traced == current) {
                val r = RectF(context.dp(12f), context.dp(12f), width - context.dp(12f), height - context.dp(12f))
                canvas.drawRoundRect(r, context.dp(14f), context.dp(14f), ring)
            }
        }
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, context.dpi(12f), 0, 0)
        addView(tile(false), LinearLayout.LayoutParams(0, context.dpi(220f), 1f))
        addView(tile(true), LinearLayout.LayoutParams(0, context.dpi(220f), 1f))
    }
}
