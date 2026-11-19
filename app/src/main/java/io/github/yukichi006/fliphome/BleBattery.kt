package io.github.yukichi006.fliphome

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Galaxy Fit などの、残量を Android に報告しない Bluetooth LE 機器に直接つなぎ、
 * 標準の「電池サービス」から残量を読む。読んだ値は機器ごとに覚えておき、しばらくは読み直さない。
 * 電池サービスを持たない機器では何も起きない（その機器は表示しない）。
 *
 * スマートリング（evofit など、独自のサービス [RING_SERVICE] を持つもの）は、電池サービスの値が空で読めない。
 * 「機器の情報」の答えに残量が入っているので、つないだままにして答えを聞く（答えは同じ接続のすべてのアプリに配られる）。
 * 0.38.0 から、FlipHome からも「機器の情報」を問い合わせる（持ち主の了承済み。送るのは [RingPacket.DEVICE_INFO_QUERIES] だけ）。
 * ホーム画面が見えているときは前に読んでから [FRESH_VISIBLE_MS]、見えていないときは [FRESH_HIDDEN_MS] たったら問い合わせる。
 * 読めた残量は覚えておき、いつ読んだかも出す。
 */
@SuppressLint("MissingPermission")
object BleBattery {
    private val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** スマートリングの独自のサービスと、問い合わせを書き込む値（答えもここに届く）。 */
    private val RING_SERVICE: UUID = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5")
    private val RING_WRITE: UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5")

    /** ホーム画面が見えているとき・見えていないときに、リングへ問い合わせ直すまでの時間。 */
    const val FRESH_VISIBLE_MS = 10 * 60_000L
    const val FRESH_HIDDEN_MS = 60 * 60_000L

    /** 問い合わせてから、答えを待つ時間（過ぎたら、もう 1 つの形を試す）。 */
    private const val QUERY_WAIT_MS = 6_000L

    /** 通知を受け取るかどうかを機器に伝える設定の場所。 */
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 読み直すまでの間隔。腕時計などの電池を減らさないよう長めにする。 */
    private const val REFRESH_MS = 10 * 60_000L

    /** つながらない・答えがないときに諦めるまでの時間。 */
    private const val TIMEOUT_MS = 20_000L

    /** 読んだ残量と、読んだ時刻（ふつうの時計のミリ秒）。 */
    private class Reading(val level: Int, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val readings = mutableMapOf<String, Reading>()
    private val tried = mutableMapOf<String, Long>()
    private val statuses = mutableMapOf<String, String>()
    private val listeners = mutableSetOf<() -> Unit>()

    /** 答えを聞くために、つないだままにしているリング。 */
    private val held = mutableMapOf<String, BluetoothGatt>()

    /** リングの残量を覚えておく場所（FlipHome を開き直しても、前に読んだ残量を出せるように）。 */
    private const val PREFS = "ble_battery"
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, value) in p.all) {
            if (key == KEY_QUERY) continue
            val parts = (value as? String)?.split(",") ?: continue
            val level = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            readings[key] = Reading(level, at)
            kept += key
        }
    }

    private fun save(context: Context, address: String, r: Reading) {
        kept += address
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(address, "${r.level},${r.at}").apply()
    }

    /** 残量を覚えておく機器（リング）。切断されても忘れず、つなぎ直したら、いつ読んだかを添えて出す。 */
    private val kept = mutableSetOf<String>()

    /** リングへ最後に問い合わせた時刻（elapsedRealtime）。 */
    private val queried = mutableMapOf<String, Long>()

    /** 答えを待っている問い合わせの形（[RingPacket.DEVICE_INFO_QUERIES] の何番目か）。 */
    private val waiting = mutableMapOf<String, Int>()

    /** 答えが返ってきた問い合わせの形（覚えておき、次からはその形だけを送る）。分からなければ -1。 */
    private fun workingQuery(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_QUERY, -1)

    private const val KEY_QUERY = "query_form"

    /**
     * リング [device]（つないだままの [g]）に「機器の情報」を問い合わせる。[form] は問い合わせの形の番号。
     * 答えがなければ、まだ形が分かっていないときに限り、次の形を試す。
     */
    private fun queryRing(context: Context, device: BluetoothDevice, g: BluetoothGatt, form: Int) {
        val app = context.applicationContext
        val c = g.getService(RING_SERVICE)?.getCharacteristic(RING_WRITE) ?: return
        val bytes = RingPacket.DEVICE_INFO_QUERIES.getOrNull(form) ?: return
        queried[device.address] = SystemClock.elapsedRealtime()
        waiting[device.address] = form
        val ok = runCatching { g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS }
            .getOrDefault(false)
        setStatus(device, if (ok) "リングに残量を問い合わせました（形 ${form + 1}）。答えを待っています…" else "リングへの問い合わせを送れませんでした（形 ${form + 1}）")
        main.postDelayed({
            if (waiting[device.address] != form) return@postDelayed
            waiting.remove(device.address)
            val next = form + 1
            if (workingQuery(app) < 0 && next < RingPacket.DEVICE_INFO_QUERIES.size && held[device.address] === g) {
                queryRing(app, device, g, next)
            } else {
                setStatus(device, "問い合わせに答えがありませんでした（形 ${form + 1}）。専用アプリを開くと読めます")
            }
        }, QUERY_WAIT_MS)
    }

    /** つないだままのリングなら、前に読んでから [maxAgeMs] 以上たっていれば問い合わせる。 */
    private fun maybeQueryRing(context: Context, device: BluetoothDevice, maxAgeMs: Long) {
        val g = held[device.address] ?: return
        if (device.address in waiting) return
        val now = SystemClock.elapsedRealtime()
        val readAgo = readings[device.address]?.let { System.currentTimeMillis() - it.at } ?: Long.MAX_VALUE
        val askedAgo = queried[device.address]?.let { now - it } ?: Long.MAX_VALUE
        if (readAgo < maxAgeMs || askedAgo < maxAgeMs) return
        queryRing(context, device, g, workingQuery(context).coerceAtLeast(0))
    }

    /** スマートリングか（リングとしてつないだことがあるか）。 */
    fun isRing(device: BluetoothDevice): Boolean = device.address in kept || device.address in held

    /** 残量を読んだ時刻（ふつうの時計のミリ秒）。まだ読めていなければ null。 */
    fun readAt(device: BluetoothDevice): Long? = readings[device.address]?.at

    /** 覚えている残量。まだ読めていなければ null。 */
    fun level(device: BluetoothDevice): Int? = readings[device.address]?.level

    /** 最後に問い合わせたときの様子（診断用）。まだ問い合わせていなければ null。 */
    fun status(device: BluetoothDevice): String? = statuses[device.address]

    private fun setStatus(device: BluetoothDevice, text: String) = main.post {
        statuses[device.address] = text
        listeners.toList().forEach { it() }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /**
     * しばらく読んでいなければ、[device] に残量を問い合わせる。[force] なら間隔に関係なく問い合わせる。
     * 結果と途中の様子はリスナーに知らせる。
     */
    fun request(context: Context, device: BluetoothDevice, force: Boolean = false, maxAgeMs: Long = FRESH_HIDDEN_MS) {
        load(context)
        // つないだままのリングは、つなぎ直さず、残量が古ければ問い合わせる
        if (device.address in held) {
            maybeQueryRing(context, device, maxAgeMs)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = tried[device.address]
        if (!force && last != null && now - last < REFRESH_MS) return
        tried[device.address] = now
        setStatus(device, "接続しています…")

        var gatt: BluetoothGatt? = null
        var done = false
        val finish = Runnable { runCatching { gatt?.disconnect(); gatt?.close() } }
        val timeout = Runnable {
            if (!done) setStatus(device, "応答がありませんでした（${TIMEOUT_MS / 1000} 秒）")
            finish.run()
        }
        fun end(text: String) {
            done = true
            setStatus(device, text)
            main.removeCallbacks(timeout)
            main.post(finish)
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus(device, "接続しました。サービスを探しています…")
                    if (!g.discoverServices()) end("サービスを探せませんでした")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    main.post { if (held[device.address] === g) held.remove(device.address) }
                    if (!done) end("切断されました（status=$status）")
                    main.post { runCatching { g.close() } }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                // スマートリング：専用アプリの問い合わせの答えを、つないだまま横で聞く
                g.getService(RING_SERVICE)?.let { ring ->
                    done = true
                    main.removeCallbacks(timeout)
                    ring.characteristics.forEach { g.setCharacteristicNotification(it, true) }
                    setStatus(device, "リングにつなぎました")
                    // 答えを受け取る準備が済んでから、問い合わせる
                    main.postDelayed({
                        held[device.address] = g
                        maybeQueryRing(context, device, FRESH_VISIBLE_MS)
                    }, 1_000L)
                    return
                }
                val c = g.getService(SERVICE)?.getCharacteristic(LEVEL)
                if (c == null) {
                    // どんなサービスがあるかを短い番号で並べ、原因を探す手がかりにする
                    val ids = g.services.joinToString(", ") { it.uuid.toString().substring(4, 8) }
                    end("電池サービスがありません（サービス: ${ids.ifEmpty { "なし" }}）")
                } else if (!g.readCharacteristic(c)) {
                    end("電池残量を読めませんでした")
                } else {
                    setStatus(device, "電池残量を読んでいます…")
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                val level = parse(value)
                if (status == BluetoothGatt.GATT_SUCCESS && level != null) {
                    main.post { readings[device.address] = Reading(level, System.currentTimeMillis()) }
                    end("読めました: $level%")
                    return
                }
                // スマートリングなど、読んでも値を返さず、変わったときの通知でだけ残量を送る機器もある。
                // その場合は通知を受け取るように切り替えて待つ
                val raw = hex(value)
                if (subscribe(g, c)) setStatus(device, "読んだ値が使えなかったため（status=$status、値: $raw）通知を待っています…")
                else end("読み取りに失敗しました（status=$status、値: $raw）")
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (c.service?.uuid == RING_SERVICE) {
                    if (RingPacket.isDeviceInfoRefusal(value)) {
                        main.post {
                            val form = waiting[device.address] ?: return@post
                            setStatus(device, "リングが問い合わせの形 ${form + 1} に対応していませんでした")
                        }
                        return
                    }
                    val info = RingPacket.deviceInfo(value) ?: return
                    main.post {
                        val r = Reading(info.level, System.currentTimeMillis())
                        readings[device.address] = r
                        val app = context.applicationContext
                        save(app, device.address, r)
                        // FlipHome の問い合わせへの答えなら、その形を覚えておく
                        val form = waiting.remove(device.address)
                        if (form != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_QUERY, form).apply()
                        setStatus(device, "読めました: ${info.level}%（版 ${info.version}${form?.let { "、問い合わせの形 ${it + 1}" } ?: "、専用アプリの問い合わせ"}）")
                    }
                    return
                }
                if (c.uuid != LEVEL) return
                val level = parse(value)
                if (level != null) {
                    main.post { readings[device.address] = Reading(level, System.currentTimeMillis()) }
                    end("通知で読めました: $level%")
                } else {
                    setStatus(device, "通知の値が使えませんでした（値: ${hex(value)}）。次の通知を待っています…")
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (d.uuid == CCCD && status != BluetoothGatt.GATT_SUCCESS) end("通知を受け取る設定に失敗しました（status=$status）")
            }
        }
        gatt = runCatching {
            device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            setStatus(device, "接続を始められませんでした")
            return
        }
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    /** 電池残量の値（先頭の 1 バイトが 0〜100 の %）。使えない値なら null。 */
    private fun parse(value: ByteArray): Int? = value.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }

    /** 診断用に、受け取った値をそのまま 16 進で並べる。 */
    private fun hex(value: ByteArray) = value.joinToString(" ") { "%02x".format(it) }.ifEmpty { "空" }

    /** 残量が変わったときに通知を送ってもらうよう、機器に頼む。頼めなければ false。 */
    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val cccd = c.getDescriptor(CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        return g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    }

    /** 前に読めた機器が切断されたら、古い残量を出し続けないよう忘れる（リングは、いつ読んだかを添えて出すので覚えておく）。 */
    fun forget(device: BluetoothDevice) {
        if (device.address !in kept) readings.remove(device.address)
        tried.remove(device.address)
        held.remove(device.address)?.let { g -> runCatching { g.close() } }
        waiting.remove(device.address)
    }
}
