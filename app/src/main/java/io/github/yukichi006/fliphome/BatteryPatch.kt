package io.github.yukichi006.fliphome

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Bundle
import android.util.SizeF
import android.text.TextUtils

/**
 * 本体と、接続中の Bluetooth 機器の電池残量を、大きな数字（%）と、サテン刺繍のように糸で埋めた棒で表すワッペン。
 * 20% 以下なら「残りわずか」、充電中なら「充電中」と添える。
 *
 * 残量の読み方は FlipCover と同じ：機器が Android に報告している値を使い、
 * 報告しない Bluetooth LE 機器（Galaxy Fit など）は [BleBattery] で直接問い合わせる。
 * 読むには「付近のデバイス」の許可が要る。許可がなければ本体だけを表示し、タップで許可を求める。
 *
 * Galaxy Fit3 などは、残量を Samsung のアプリにしか送らず、上のどちらでも読めない。
 * [sourceWidget]（Galaxy の「バッテリー」ウィジェットの番号）があれば、そのウィジェットを見えないまま動かし、
 * 表示されている残量を [WidgetBattery] で読み取って足す。
 */
@SuppressLint("ViewConstructor")
class BatteryPatch(context: Context, private val sourceWidget: Int? = null) : PatchView(context) {
    /** 1 行。[age] は、少し前に読んだ残量なら「2時間前」など。 */
    private class Row(val name: String, val level: Int, val charging: Boolean, val age: String? = null)

    private var rows = emptyList<Row>()
    private var phoneLevel = -1
    private var phoneCharging = false
    private val btLevels = mutableMapOf<String, Int>()

    /** 残量を読み取るために、見えないまま動かしている Galaxy の「バッテリー」ウィジェット。 */
    private var source: AppWidgetHostView? = null

    /** ウィジェットから最後に読み取った行（診断用）。 */
    private var widgetEntries = emptyList<WidgetBattery.Entry>()

    private val title = context.threadText(10.5f, color = Thread.DIM).apply { letterSpacing = 0.2f }
    private val name = context.threadText(11.5f)
    private val big = context.threadText(22f, bold = true).apply {
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val unit = context.threadText(10f, bold = true).apply { textAlign = Paint.Align.RIGHT }
    private val small = context.threadText(9.5f, color = Thread.DIM)
    private val trackStitch = context.stitch(0.9f, Thread.DIM)
    private val satin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Thread.COLOR
        setShadowLayer(context.dp(0.6f), 0f, context.dp(1f), Thread.SHADOW)
    }
    private val satinLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30000000
        strokeWidth = context.dp(0.8f)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> readPhone(intent)
                ACTION_BT_BATTERY -> {
                    val device = intent.btDevice() ?: return
                    btLevels[device.address] = intent.getIntExtra(EXTRA_BT_BATTERY, -1)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> intent.btDevice()?.let {
                    btLevels.remove(it.address)
                    BleBattery.forget(it)
                }
            }
            refresh()
        }
    }

    private val onBleLevel: () -> Unit = { refresh() }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, POLL_MS)
        }
    }

    init {
        setOnClickListener {
            if (!canReadBluetooth()) {
                (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(ACTION_BT_BATTERY)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Bluetooth の残量の通知はシステムの Bluetooth から届くので外部からの受け取りを許すが、
        // 送り手に「付近のデバイス」の権限を求め、ほかのアプリが偽の残量を送り込みにくくする
        context.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH_CONNECT, null, Context.RECEIVER_EXPORTED)
            ?.let { readPhone(it) }
        BleBattery.addListener(onBleLevel)
        attachSource()
        post(poll)
    }

    /** 残量を読むためのウィジェットを、画面には出さずに用意する。 */
    private fun attachSource() {
        val id = sourceWidget ?: return
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return
        source = runCatching { AppWidgets.host(context).createView(context, id, info) }.getOrNull()
        sizeSource()
    }

    /**
     * 見えないウィジェットに大きさを伝え、その大きさで並べさせる。小さいと機器を減らして出すウィジェットがあるので、
     * 少なくとも 2×2 ほどの大きさにする。
     */
    private fun sizeSource() {
        val v = source ?: return
        val d = resources.displayMetrics.density
        val w = maxOf(width, (SOURCE_DP * d).toInt())
        val h = maxOf(height, (SOURCE_DP * d).toInt())
        runCatching { v.updateAppWidgetSize(Bundle(), listOf(SizeF(w / d, h / d))) }
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeSource()
        refresh()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        removeCallbacks(poll)
        BleBattery.removeListener(onBleLevel)
        source = null
        super.onDetachedFromWindow()
    }

    private fun canReadBluetooth() = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun readPhone(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) phoneLevel = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        phoneCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun refresh() {
        val list = mutableListOf<Row>()
        if (phoneLevel >= 0) list += Row("本体", phoneLevel, phoneCharging)
        val bt = bluetoothRows()
        list += bt
        list += widgetRows(bt)
        rows = list.take(MAX_ROWS)
        invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothRows(): List<Row> {
        if (!canReadBluetooth()) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val bonded = runCatching { manager.adapter?.bondedDevices }.getOrNull().orEmpty()
        val leDevices = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull().orEmpty()
        val leConnected = leDevices.map { it.address }.toSet()
        return (bonded + leDevices).distinctBy { it.address }.mapNotNull { d ->
            val reported = readBtLevel(d).takeIf { it in 0..100 } ?: btLevels[d.address]?.takeIf { it in 0..100 }
            val le = d.address in leConnected ||
                d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL
            val level = reported ?: if (le && (d.address in leConnected || isConnected(d))) {
                BleBattery.request(context, d)
                BleBattery.level(d)
            } else null
            level ?: return@mapNotNull null
            val n = runCatching { d.alias ?: d.name }.getOrNull() ?: d.address
            Row(n, level, false, if (reported == null) WidgetBattery.ageText(BleBattery.readAt(d)) else null)
        }.sortedBy { it.name }
    }

    /**
     * 見えないウィジェットから読み取った残量のうち、まだ出していない機器の行。
     * 本体の行と、すでに Android から読めている機器（Buds など）は重ねて出さない。
     */
    private fun widgetRows(shown: List<Row>): List<Row> {
        val v = source ?: return emptyList()
        widgetEntries = runCatching { WidgetBattery.read(v) }.getOrDefault(emptyList())
        return WidgetBattery.merge(widgetEntries, shown.map { it.name to it.level }, unnamedCandidates(), android.os.Build.MODEL).map { Row(it.first, it.second, it.third) }
    }

    /** 名前の書かれていない行に当てる名前の候補：つながっているのに、残量を読めていない機器。 */
    @SuppressLint("MissingPermission")
    private fun unnamedCandidates(): List<String> {
        if (!canReadBluetooth()) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val bonded = runCatching { manager.adapter?.bondedDevices }.getOrNull().orEmpty()
        return bonded.filter { isConnected(it) && readBtLevel(it) !in 0..100 && btLevels[it.address] == null && BleBattery.level(it) == null }
            .mapNotNull { runCatching { it.alias ?: it.name }.getOrNull() }
            .sorted()
    }

    /** 診断用の説明：見えないウィジェットの中身と、読み取った結果、Bluetooth で直接問い合わせた様子。 */
    @SuppressLint("MissingPermission")
    fun diagnosis(): String = buildString {
        append("■ Galaxy の「バッテリー」から読んだ行\n")
        val v = source
        if (v == null) append(if (sourceWidget == null) "（使っていません）\n" else "（ウィジェットを用意できませんでした）\n")
        else {
            if (widgetEntries.isEmpty()) append("（読み取れた行はありません）\n")
            widgetEntries.forEachIndexed { i, e -> append("${i + 1}. ${e.name ?: "名前なし"}：${e.level}%${if (e.charging) "（充電中）" else ""}\n") }
        }
        append("\n■ Bluetooth で直接問い合わせた様子\n")
        val manager = context.getSystemService(BluetoothManager::class.java)
        if (!canReadBluetooth() || manager == null) append("（「付近のデバイス」の許可がありません）\n")
        else {
            val bonded = runCatching { manager.adapter?.bondedDevices }.getOrNull().orEmpty()
            val le = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull().orEmpty()
            for (d in (bonded + le).distinctBy { it.address }) {
                val n = runCatching { d.alias ?: d.name }.getOrNull() ?: d.address
                val connected = d in le || isConnected(d)
                if (!connected) continue
                val reported = readBtLevel(d).takeIf { it in 0..100 }
                append("・$n：${reported?.let { "Android から $it%" } ?: BleBattery.status(d) ?: "まだ問い合わせていません"}\n")
            }
        }
        if (v != null) append("\n■ ウィジェットの中身\n").append(WidgetBattery.dump(v))
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        // 細いワッペンでも見出しが収まるよう、幅に合わせて文字を小さくする
        title.textSize = context.sp(10.5f)
        title.textSize = (title.textSize * area.width() / title.measureText("BATTERY")).coerceAtMost(context.sp(10.5f))
        var y = area.top + title.textSize
        canvas.drawText("BATTERY", area.left, y, title)
        val shown = rows.ifEmpty { return }
        // 残りの高さを行の数で分ける。行ごとに「名前と大きな %」と「残量の棒」
        val rowH = ((area.bottom - y) / shown.size.coerceAtLeast(1)).coerceAtMost(context.dp(64f))
        val barH = (rowH * 0.2f).coerceIn(context.dp(6f), context.dp(11f))
        for (row in shown) {
            val top = y + context.dp(4f)
            // 大きな % （行の高さと幅に合わせる）
            big.textSize = (rowH - barH - context.dp(12f)).coerceIn(context.sp(12f), context.sp(26f))
            val num = "${row.level}"
            unit.textSize = big.textSize * 0.45f
            val numW = big.measureText(num)
            val unitW = unit.measureText("%")
            val maxNumW = area.width() * 0.62f
            if (numW + unitW > maxNumW) big.textSize *= maxNumW / (numW + unitW)
            val baseline = top + big.textSize * 0.92f
            canvas.drawText("%", area.right, baseline, unit)
            canvas.drawText(num, area.right - unit.measureText("%") - context.dp(1f), baseline, big)
            // 名前（低いとき・充電中はひとこと添える）
            val note = when {
                row.charging -> "充電中"
                row.age != null -> if (row.level <= LOW) "残りわずか・${row.age}" else row.age
                row.level <= LOW -> "残りわずか"
                else -> null
            }
            val nameW = area.width() - big.measureText(num) - unit.measureText("%") - context.dp(8f)
            val barTop = baseline + context.dp(6f)
            // ひとことは名前の下に。下に入る高さがなければ、名前のあとに添える（棒に重ならないように）
            val noteBelow = note != null && top + name.textSize + small.textSize * 1.35f <= barTop - context.dp(2f)
            val shownName = if (note != null && !noteBelow) "${row.name}・$note" else row.name
            val t = TextUtils.ellipsize(shownName, name, nameW, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, area.left, top + name.textSize, name)
            if (note != null && noteBelow) {
                val n = TextUtils.ellipsize(note, small, nameW, TextUtils.TruncateAt.END)
                canvas.drawText(n, 0, n.length, area.left, top + name.textSize + small.textSize * 1.35f, small)
            }
            // 残量の棒：外枠は細いステッチ、残っている分はサテン刺繍のように糸で埋める
            val track = RectF(area.left, barTop, area.right, barTop + barH)
            canvas.drawRoundRect(track, barH / 2, barH / 2, trackStitch)
            val fillW = (track.width() * row.level / 100f).coerceAtLeast(if (row.level > 0) barH else 0f)
            if (fillW > 0f) {
                val fill = RectF(track.left, track.top, track.left + fillW, track.bottom)
                canvas.drawRoundRect(fill, barH / 2, barH / 2, satin)
                // 斜めの糸目
                canvas.save()
                canvas.clipRect(fill)
                var x = fill.left - barH
                while (x < fill.right) {
                    canvas.drawLine(x, fill.bottom, x + barH, fill.top, satinLine)
                    x += context.dp(3f)
                }
                canvas.restore()
            }
            y = track.bottom + context.dp(4f)
        }
    }

    companion object {
        const val REQUEST = 12
        private const val MAX_ROWS = 4
        /** 見えないウィジェットに伝える、少なくともの大きさ（dp）。 */
        private const val SOURCE_DP = 180f
        /** これ以下なら「残りわずか」と添える（%）。 */
        private const val LOW = 20
        private const val POLL_MS = 60_000L
        private const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

        private fun readBtLevel(device: BluetoothDevice): Int =
            runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int }
                .getOrDefault(-1)

        private fun isConnected(device: BluetoothDevice): Boolean =
            runCatching { BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean }
                .getOrDefault(false)

        private fun Intent.btDevice(): BluetoothDevice? =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
    }
}
