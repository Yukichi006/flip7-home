package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import kotlin.math.abs

/**
 * アプリ一覧（ドロワー）。上に縫い付けたテープ風の検索欄、左にアプリの一覧、右に見出しの索引を置く。
 * 一覧のいちばん上で下へ引っ張ると閉じる。
 *
 * 検索欄のそばの切り替えで、「あいうえお順」の一覧と、「ジャンル」（[Shelf]）ごとの巾着を選べる。
 * 巾着をタップすると、口が開いて中のアプリが出てくる（ホーム画面の巾着と同じ）。
 */
@SuppressLint("ViewConstructor")
class DrawerView(
    context: Context,
    private val repo: AppRepository,
    private val onMenu: (AppEntry, View) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private sealed interface Row
    private class Header(val group: Int) : Row
    private class Item(val app: AppEntry) : Row

    private var rows: List<Row> = emptyList()
    private val fabric = Fabric.paint(context, Cloth.BASE)

    val search = EditText(context).apply {
        background = null
        setTextColor(Thread.COLOR)
        setHintTextColor(Thread.DIM)
        hint = "アプリを検索"
        textSize = 15f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO
        setPadding(context.dpi(8f), 0, context.dpi(8f), 0)
        setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val searchPatch = PatchLayout(context, PatchPainter.Shape.Round(40f)).apply {
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
    }

    private val rail = IndexRail(context) { group -> jumpTo(group) }
    private val adapter = Adapter()
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(0)
        cacheColorHint = 0
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        adapter = this@DrawerView.adapter
        setOnItemClickListener { _, view, pos, _ -> (rows[pos] as? Item)?.let { repo.launch(it.app, view) } }
        setOnItemLongClickListener { _, view, pos, _ ->
            (rows[pos] as? Item)?.let { onMenu(it.app, view); true } ?: false
        }
        setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {
                // 一覧を動かし始めたらキーボードを閉じる
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) = rail.setCurrent(groupAt(first))
        })
    }

    private var insetTop = 0
    private var insetBottom = 0

    // ---------------- ジャンルごとの巾着 ----------------

    private val prefs = context.getSharedPreferences("home", 0)

    /** ジャンルごとの巾着で見せるか（false なら、あいうえお順の一覧）。 */
    private var byGenre = prefs.getBoolean(KEY_BY_GENRE, false)

    private val modeBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val pouches = PouchGrid(context)
    private val pouchHolder = FrameLayout(context).apply {
        addView(pouches, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }
    private val genreScroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(pouchHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ -> hideKeyboard() }
    }
    private var popover: FolderPopover? = null

    init {
        setWillNotDraw(false)
        addView(searchPatch)
        addView(list)
        addView(genreScroll)
        addView(rail)
        addView(modeBar)
        buildModeBar()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        search.setOnEditorActionListener { _, _, _ ->
            (rows.firstOrNull { it is Item } as? Item)?.let { repo.launch(it.app, search) }
            true
        }
        applyInsets(0, 0)
    }

    private val seam = context.stitch(1.4f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(fabric)
        drawScreenSeam(canvas, seam)
    }

    /**
     * 下へ引いたときに、自分で動かずにこれを呼ぶ（引いた割合 0〜1）。下段のファスナーから出しているときに、ファスナーといっしょに動かすため。
     * 離したら [onPullEnd]（閉じるなら true）。
     */
    var onPull: ((Float) -> Unit)? = null
    var onPullEnd: ((Boolean) -> Unit)? = null

    /** 右から出てきて、右へスワイプで閉じる。false なら下から出て、下へ引いて閉じる。 */
    var fromRight = false

    /** 検索欄を下に置く（親指が届くように）。 */
    var searchAtBottom = false
        set(value) {
            field = value
            applyInsets(insetTop, insetBottom)
        }

    fun applyInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        val side = context.dpi(10f)
        val searchH = context.dpi(64f)
        val railW = context.dpi(46f)
        val gap = context.dpi(6f)
        val barH = context.dpi(50f)
        if (searchAtBottom) {
            searchPatch.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, searchH, Gravity.BOTTOM).apply {
                setMargins(side, 0, side, bottom + gap)
            }
            // 切り替えは検索欄のすぐ上（親指の届く所）
            modeBar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM).apply {
                setMargins(side, 0, side, bottom + gap + searchH)
            }
            val listBottom = bottom + gap + searchH + barH + context.dpi(4f)
            genreScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(side, top + gap, side, listBottom)
            }
            // 巾着が少ないときは、下（親指の届く所）に寄せる
            pouches.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            list.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(side + gap, top + gap, side + railW + context.dpi(4f), listBottom)
            }
            list.setPadding(0, 0, 0, context.dpi(12f))
            rail.layoutParams = LayoutParams(railW, LayoutParams.MATCH_PARENT, Gravity.END).apply {
                setMargins(0, top + gap, side, listBottom)
            }
        } else {
            val listTop = top + gap + searchH + barH + context.dpi(4f)
            searchPatch.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, searchH).apply {
                setMargins(side, top + gap, side, 0)
            }
            modeBar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, barH).apply {
                setMargins(side, top + gap + searchH, side, 0)
            }
            genreScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(side, listTop, side, 0)
            }
            genreScroll.setPadding(0, 0, 0, bottom + context.dpi(12f))
            genreScroll.clipToPadding = false
            pouches.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
            list.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(side + gap, listTop, side + railW + context.dpi(4f), 0)
            }
            list.setPadding(0, 0, 0, bottom + context.dpi(12f))
            rail.layoutParams = LayoutParams(railW, LayoutParams.MATCH_PARENT, Gravity.END).apply {
                setMargins(0, listTop, side, bottom + context.dpi(10f))
            }
        }
        requestLayout()
    }

    /** アプリの一覧が変わったとき、検索の文字が変わったときに並べ直す。 */
    fun rebuild() {
        val query = Index.normalize(search.text.toString().trim())
        rows = if (query.isEmpty()) {
            buildList {
                var last = -1
                for (app in repo.apps) {
                    if (app.group != last) { add(Header(app.group)); last = app.group }
                    add(Item(app))
                }
            }
        } else {
            repo.apps.filter { Index.normalize(repo.label(it)).contains(query) || Index.normalize(it.label).contains(query) }.map { Item(it) }
        }
        rail.setPresent(repo.apps.map { it.group }.toSet())
        val pouchMode = byGenre && query.isEmpty()
        rail.visibility = if (query.isEmpty() && !pouchMode) View.VISIBLE else View.INVISIBLE
        list.visibility = if (pouchMode) View.INVISIBLE else View.VISIBLE
        genreScroll.visibility = if (pouchMode) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        if (pouchMode) buildPouches()
    }

    /** 「あいうえお順」「ジャンル」の切り替え。 */
    private fun buildModeBar() {
        modeBar.removeAllViews()
        listOf("あいうえお順" to false, "ジャンル" to true).forEach { (label, genre) ->
            modeBar.addView(Pill(context, label, byGenre == genre).apply {
                setOnClickListener {
                    if (byGenre == genre) return@setOnClickListener
                    byGenre = genre
                    prefs.edit().putBoolean(KEY_BY_GENRE, genre).apply()
                    dismissPopover()
                    buildModeBar()
                    rebuild()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /**
     * アプリを分類（[Shelf]）ごとの巾着にまとめる。いちばん初めは「よく使う」（開いた回数の多いアプリ。ほかの巾着にも入る）。
     */
    private fun buildPouches() {
        pouches.removeAllViews()
        fun add(shelf: Shelf, apps: List<AppEntry>) {
            if (apps.isEmpty()) return
            pouches.addView(FolderPouch(context, "${shelf.label} ${apps.size}", apps.map { it.component.packageName },
                onOpen = { v -> openPouch(shelf.label, apps, v) }, onLongPress = {}, genre = shelf.icon))
        }
        add(Shelf.FREQUENT, repo.frequent())
        val groups = repo.apps.groupBy { repo.shelf(it) }.toSortedMap(compareBy { it.ordinal })
        for ((shelf, apps) in groups) if (shelf != Shelf.FREQUENT) add(shelf, apps)
    }

    private fun openPouch(title: String, apps: List<AppEntry>, anchor: View) {
        popover?.dismiss()
        hideKeyboard()
        val pads = apps.map { app ->
            PadView(context, app, repo, framed = false) { popover?.dismiss(); onMenu(app, this) }
        }
        popover = FolderPopover(context, title, anchor, pads) { popover = null }.also { it.show(this) }
    }

    /** 開いている巾着を閉じる。閉じたら true。 */
    fun dismissPopover(): Boolean {
        val p = popover ?: return false
        p.dismiss()
        return true
    }

    /** 開くたびに、検索を空にして一覧の先頭に戻す。 */
    fun reset() {
        popover?.let { removeView(it); popover = null }
        if (search.text.isNotEmpty()) search.setText("") else rebuild()
        genreScroll.scrollTo(0, if (searchAtBottom) Int.MAX_VALUE / 2 else 0)
        list.setSelection(0)
        translationY = 0f
        translationX = 0f
    }

    fun hideKeyboard() {
        search.clearFocus()
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun groupAt(pos: Int): Int = when (val r = rows.getOrNull(pos)) {
        is Header -> r.group
        is Item -> r.app.group
        null -> -1
    }

    private fun jumpTo(group: Int) {
        // その見出しがなければ、次にある見出しへ
        val pos = rows.indexOfFirst { it is Header && it.group >= group }
        if (pos >= 0) list.setSelection(pos)
    }

    // ---------------- 下へ引っ張って閉じる ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                velocity?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val onRail = ev.x >= rail.left
                if (fromRight) {
                    // 右へスワイプで閉じる（索引をなぞっているときは除く）
                    if (!onRail && dx > slop && dx > abs(dy) * 1.5f) {
                        dragging = true
                        hideKeyboard()
                    }
                } else if (!onRail && dy > slop && dy > abs(dx) * 1.5f &&
                    !(if (genreScroll.visibility == View.VISIBLE) genreScroll else list).canScrollVertically(-1)) {
                    dragging = true
                    hideKeyboard()
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val pull = onPull
                if (fromRight) translationX = (ev.rawX - downX).coerceAtLeast(0f)
                else if (pull != null) pull(((ev.rawY - downY) / height).coerceIn(0f, 1f))
                else translationY = (ev.rawY - downY).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val v = if (fromRight) velocity?.xVelocity ?: 0f else velocity?.yVelocity ?: 0f
                val moved = if (fromRight) translationX / width else translationY / height
                val close = dragging && (moved > 0.2f || v > context.dp(900f))
                val pullEnd = onPullEnd
                if (dragging && !fromRight && pullEnd != null) pullEnd(close)
                else if (close) onClose()
                else animate().translationX(0f).translationY(0f).setDuration(180).start()
                dragging = false
            }
        }
        return true
    }

    /** 巾着を 4 列に並べる入れ物。 */
    private class PouchGrid(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cw = w / COLS
            val ch = (cw * 1.12f).toInt()
            for (i in 0 until childCount) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(w, ch * ((childCount + COLS - 1) / COLS) + context.dpi(8f))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (i % COLS) * c.measuredWidth
                val y = (i / COLS) * c.measuredHeight
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }

        companion object { const val COLS = 4 }
    }

    // ---------------- 一覧の中身 ----------------

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Header -> ((convertView as? HeaderRow) ?: HeaderRow(context)).also { it.bind(row.group) }
                is Item -> ((convertView as? AppRowView) ?: AppRowView(context, repo)).also { it.bind(row.app) }
            }
    }

    /** 見出し：文字と、そこから右へ伸びる細い縫い目。 */
    private class HeaderRow(context: Context) : View(context) {
        private var text = ""
        private val paint = context.threadText(13f, color = Thread.DIM).apply { letterSpacing = 0.15f }
        private val seam = context.stitch(1.0f, Thread.DIM)

        init { layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, context.dpi(34f)) }

        fun bind(group: Int) {
            text = Index.LABELS.getOrElse(group) { "" }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val y = height * 0.62f
            canvas.drawText(text, 0f, y + paint.textSize * 0.35f, paint)
            val x = paint.measureText(text) + context.dp(10f)
            canvas.drawLine(x, y, width - context.dp(4f), y, seam)
        }
    }

}

private const val KEY_BY_GENRE = "drawerByGenre"

/**
 * 右端の索引（A〜Z、あ〜わ、他）を縦に並べた細長いワッペン。なぞると、その見出しへ一覧を動かす。
 * アプリのない見出しは薄く描く。
 */
@SuppressLint("ViewConstructor")
class IndexRail(context: Context, private val onPick: (Int) -> Unit) : PatchView(context, PatchPainter.Shape.Round(40f)) {
    private var present: Set<Int> = emptySet()
    private var current = -1
    private var touching = -1
    private val text = context.threadText(10.5f).apply { textAlign = Paint.Align.CENTER }
    private var area = RectF()

    fun setPresent(groups: Set<Int>) { present = groups; invalidate() }

    fun setCurrent(group: Int) {
        if (group != current) { current = group; invalidate() }
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        this.area = area
        val n = Index.LABELS.size
        val step = area.height() / n
        text.textSize = (step * 0.78f).coerceAtMost(context.sp(11f))
        for (i in 0 until n) {
            text.alpha = when {
                i == touching -> 255
                i !in present -> 70
                i == current -> 255
                else -> 190
            }
            text.isFakeBoldText = i == touching || i == current
            val y = area.top + step * (i + 0.5f) + text.textSize * 0.35f
            canvas.drawText(Index.LABELS[i], area.centerX(), y, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((event.y - area.top) / area.height() * Index.LABELS.size).toInt().coerceIn(0, Index.LABELS.size - 1)
                if (i != touching) {
                    touching = i
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    onPick(i)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = -1
                invalidate()
            }
        }
        return true
    }
}
