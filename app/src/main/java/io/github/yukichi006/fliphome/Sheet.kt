package io.github.yukichi006.fliphome

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
 */
@SuppressLint("ViewConstructor")
class Pill(context: Context, private val text: String, private val selected: Boolean = false) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) Thread.COLOR else Cloth.FELT }
    private val stitch = context.stitch(1.0f, if (selected) Cloth.BASE else Thread.DIM)
    private val label = context.threadText(14f, bold = selected, color = if (selected) Cloth.BASE else Thread.COLOR).apply {
        textAlign = Paint.Align.CENTER
        if (selected) clearShadowLayer()
    }
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (label.measureText(text) + context.dp(36f)).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(context.dpi(50f), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val m = context.dp(4f)
        rect.set(m, m, width - m, height - m)
        val r = rect.height() / 2
        canvas.drawRoundRect(rect, r, r, fill)
        rect.inset(context.dp(3.5f), context.dp(3.5f))
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stitch)
        canvas.drawText(text, width / 2f, height / 2f + label.textSize * 0.35f, label)
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
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(54f)))
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

/** 置ける部品（時計・今日の予定・電池）の一覧。 */
fun modulePicker(context: Context, onPick: (ModuleType) -> Unit): View =
    actionList(
        context,
        ModuleType.entries.filter { it != ModuleType.APP }.map { t -> "${t.label}（${t.note}）" to { onPick(t) } },
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
                shown = if (q.isEmpty()) repo.apps else repo.apps.filter { Index.normalize(it.label).contains(q) }
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
        canvas.drawBitmap(GenreIcons.bitmap(repo.genre(a), iconPx), context.dp(4f), (height - iconPx) / 2f, null)
        val x = iconPx + context.dp(18f)
        val t = TextUtils.ellipsize(a.label, label, width - x, TextUtils.TruncateAt.END)
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
