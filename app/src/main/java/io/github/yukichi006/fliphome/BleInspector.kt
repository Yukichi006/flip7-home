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
 * Bluetooth LE 機器（スマートリングなど）の中身を調べる診断。残量の読み方を探すために使う。
 *
 * 1. つないで、機器が持っているサービスと値（キャラクタリスティック）を並べる
 * 2. 読める値を読む
 * 3. 機器から届く知らせ（通知）を [LISTEN_MS] のあいだ受け取って並べる。
 *    そのあいだに機器の専用アプリを開くと、専用アプリが残量を問い合わせた答えも、ここに届くことがある
 *
 * **機器へは何も書き込まない**（命令を送ると、機器の設定が変わったり消えたりするおそれがあるため）。
 * 例外は、通知を受け取るかどうかの設定（CCCD）だけ。もともと切ってあった通知は、終わるときに切り直す。
 */
@SuppressLint("MissingPermission")
class BleInspector(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var startedAt = 0L
    private var finished = false
    private var notifications = 0

    /** 値ごとの、最後に届いた通知と、同じ値がくり返し届いた回数。 */
    private val last = mutableMapOf<String, ByteArray>()
    private val repeats = mutableMapOf<String, Int>()
    private var shown = 0

    /** 順番に行う問い合わせ（Bluetooth LE は一度に 1 つしか問い合わせられない）。 */
    private val queue = ArrayDeque<(BluetoothGatt) -> Boolean>()

    /** 診断のために通知を入れたもの（終わるときに切り直す）。 */
    private val enabledByUs = mutableListOf<BluetoothGattDescriptor>()

    private val timeout = Runnable { stop("■ 時間になったので終わります") }

    private fun say(text: String) = main.post { if (!finished || text.startsWith("■")) log(text) }

    private fun elapsed() = "%5.1f秒".format((SystemClock.elapsedRealtime() - startedAt) / 1000f)

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        say("■ ${runCatching { device.alias ?: device.name }.getOrNull() ?: "名前なし"}（${device.address}）につないでいます…")
        gatt = runCatching { device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) { say("■ 接続を始められませんでした"); finished = true; return }
        main.postDelayed(timeout, CONNECT_MS + LISTEN_MS)
    }

    /** 診断を終えて切断する。 */
    fun stop(reason: String = "■ 止めました") {
        if (finished) return
        val g = gatt
        // 診断のために入れた通知を切ってから、切断する
        if (g != null && enabledByUs.isNotEmpty()) {
            queue.clear()
            for (d in enabledByUs.toList()) queue += { gg ->
                gg.setCharacteristicNotification(d.characteristic, false)
                gg.writeDescriptor(d, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            }
            enabledByUs.clear()
            next()
            main.postDelayed({ close(reason) }, 1500)
        } else close(reason)
    }

    private fun close(reason: String) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        val same = repeats.entries.joinToString("、") { "${it.key} ×${it.value}" }
        log("$reason（通知は $notifications 回届きました${if (same.isEmpty()) "" else "。前と同じ値で省いたもの：$same"}）")
        runCatching { gatt?.disconnect(); gatt?.close() }
    }

    private fun next() = main.post {
        val g = gatt ?: return@post
        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            if (runCatching { step(g) }.getOrDefault(false)) return@post
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    say("${elapsed()} つながりました。サービスを探しています…")
                    if (!g.discoverServices()) say("■ サービスを探せませんでした")
                }
                BluetoothProfile.STATE_DISCONNECTED -> main.post { close("■ 切断されました（status=$status）") }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { say("■ サービスを探せませんでした（status=$status）"); return }
            val lines = StringBuilder("${elapsed()} サービスと値の一覧（読=読める 書=書ける 通=通知 示=指示）")
            for (s in g.services) {
                lines.append("\n[").append(short(s.uuid)).append(']').append(knownService(s.uuid))
                for (c in s.characteristics) {
                    lines.append("\n  ").append(short(c.uuid)).append(' ').append(props(c.properties))
                    if (c.isReadable()) queue += { gg -> gg.readCharacteristic(c) }
                    if (c.isNotifiable()) c.getDescriptor(CCCD)?.let { d -> queue += { gg -> gg.readDescriptor(d) } }
                }
            }
            say(lines.toString())
            say("${elapsed()} 読める値を読み、通知を受け取る準備をします…")
            queue += { _ ->
                say("${elapsed()} 準備ができました。${LISTEN_MS / 1000} 秒のあいだ通知を待ちます。いま専用アプリを開くと、残量の答えが届くことがあります")
                false
            }
            next()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val key = short(c.uuid)
            val text = if (status == BluetoothGatt.GATT_SUCCESS) describe(value) + (RingPacket.explain(key, value)?.let { " → $it" } ?: "") else "失敗（status=$status）"
            say("${elapsed()} 読む $key: $text")
            next()
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            val c = d.characteristic
            // Android の中でこのアプリにも通知を配ってもらうよう登録する（機器への書き込みではない）
            g.setCharacteristicNotification(c, true)
            val on = value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
            if (status == BluetoothGatt.GATT_SUCCESS && on) {
                say("${elapsed()} 通知 ${short(c.uuid)}: 専用アプリがすでに受け取っています（そのまま聞きます）")
                next()
            } else {
                // 切ってあった通知だけ、診断のあいだ入れる
                val enable = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (g.writeDescriptor(d, enable) == BluetoothStatusCodes.SUCCESS) enabledByUs += d else next()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD && status != BluetoothGatt.GATT_SUCCESS) {
                say("${elapsed()} 通知 ${short(d.characteristic.uuid)}: 入れられませんでした（status=$status）")
                enabledByUs.remove(d)
            }
            next()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications++
            // 心拍や歩数のように、同じ値を 1 秒ごとに送ってくるものがあるので、前と同じ値は数えるだけにする
            // （残量の答えのような、たまにしか来ない知らせが埋もれないように）
            val key = short(c.uuid)
            if (last[key]?.contentEquals(value) == true) {
                repeats[key] = (repeats[key] ?: 0) + 1
                return
            }
            last[key] = value.copyOf()
            shown++
            if (shown <= MAX_NOTIFICATIONS) say("${elapsed()} 通知 $key: ${describe(value)}${RingPacket.explain(key, value)?.let { " → $it" } ?: ""}")
            else if (shown == MAX_NOTIFICATIONS + 1) say("（変わった通知が多いので、ここから先は数えるだけにします）")
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_MS = 20_000L
        const val LISTEN_MS = 180_000L
        private const val MAX_NOTIFICATIONS = 300

        private fun BluetoothGattCharacteristic.isReadable() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        private fun BluetoothGattCharacteristic.isNotifiable() =
            properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        /** Bluetooth の決まった番号なら 4 桁で、独自の番号ならそのまま出す。 */
        fun short(uuid: UUID): String {
            val s = uuid.toString()
            return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8) else s
        }

        private fun props(p: Int) = buildString {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('読')
            if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) append('書')
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('通')
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append('示')
        }

        private fun knownService(uuid: UUID) = when (short(uuid)) {
            "180f" -> " 電池"
            "180a" -> " 機器の情報"
            "180d" -> " 心拍"
            "1800" -> " 基本"
            "1801" -> " 基本の属性"
            else -> ""
        }

        /** 受け取った値を、16 進と、読める文字なら文字でも出す。 */
        fun describe(value: ByteArray): String {
            if (value.isEmpty()) return "空"
            val hex = value.take(40).joinToString(" ") { "%02x".format(it) } + if (value.size > 40) " …" else ""
            val text = value.toString(Charsets.UTF_8)
            val readable = text.all { it.code in 0x20..0x7e || it.code > 0xa0 } && text.isNotBlank()
            return if (readable) "$hex 「$text」" else hex
        }
    }
}
