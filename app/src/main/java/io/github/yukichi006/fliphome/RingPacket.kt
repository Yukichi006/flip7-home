package io.github.yukichi006.fliphome

/**
 * スマートリング（evofit、中身は Jieli のチップ）から届く値を読み解く。画面や Android の機能は使わない。
 *
 * 診断（[BleInspector]）で分かったこと（0.36.1）：
 * - 独自のサービス `be940000-…` の値は、次の形の「包み」で届く
 *   `[種類 1 バイト][番号 1 バイト][全体の長さ 2 バイト（小さい桁が先）][中身…][CRC 2 バイト（小さい桁が先）]`
 *   CRC は CRC-16/CCITT-FALSE（例：`02 26 07 00 fc 5b ee` は、種類 02・番号 26・中身 fc）
 * - 心拍の値（2a37）と、歩数の値（fea1、WeChat の歩数の形）は、決まった形で 1 秒ごとに届く
 * - 残量は「機器の情報」の包み（種類 02・番号 00）に入っている（0.36.2 の診断で、専用アプリを開いたときに届いた）。
 *   中身は `[機器の番号 2][版 2（小・大）][電池の状態 1][残量 % 1]…`。版が専用アプリの表示（2.18）と一致したので、並びは確か
 */
object RingPacket {
    class Packet(val type: Int, val cmd: Int, val payload: ByteArray, val crcOk: Boolean)

    /** 機器の情報（残量・ファームウェアの版）。 */
    class DeviceInfo(val level: Int, val version: String)

    /** 「機器の情報」の包みなら、その中身。ほかの包みや、壊れた包みなら null。 */
    fun deviceInfo(v: ByteArray): DeviceInfo? {
        val p = parse(v) ?: return null
        if (!p.crcOk || p.type != 0x02 || p.cmd != 0x00 || p.payload.size < 6) return null
        val b = p.payload.map { it.toInt() and 0xFF }
        val level = b[5].takeIf { it in 0..100 } ?: return null
        return DeviceInfo(level, "${b[3]}.${b[2]}")
    }

    /** CRC-16/CCITT-FALSE（多項式 0x1021、初期値 0xFFFF）。 */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = 0xFFFF
        for (i in from until to) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
            c = c and 0xFFFF
        }
        return c
    }

    /** 包みとして読めれば、その中身。長さが合わなければ null。 */
    fun parse(v: ByteArray): Packet? {
        if (v.size < 6) return null
        val len = (v[2].toInt() and 0xFF) or ((v[3].toInt() and 0xFF) shl 8)
        if (len != v.size) return null
        val crc = (v[v.size - 2].toInt() and 0xFF) or ((v[v.size - 1].toInt() and 0xFF) shl 8)
        return Packet(v[0].toInt() and 0xFF, v[1].toInt() and 0xFF, v.copyOfRange(4, v.size - 2), crc == crc16(v, 0, v.size - 2))
    }

    /** 値 [value]（[uuid] は 4 桁か長い番号）を、人が読める説明にする。読み方が分からなければ null。 */
    fun explain(uuid: String, value: ByteArray): String? {
        val u = value.map { it.toInt() and 0xFF }
        return when {
            uuid == "2a37" && u.size >= 2 -> {
                // 心拍：先頭の印の 1 ビット目が立っていれば 2 バイト、そうでなければ 1 バイト
                val bpm = if (u[0] and 1 != 0 && u.size >= 3) u[1] or (u[2] shl 8) else u[1]
                "心拍 $bpm"
            }
            uuid == "fea1" && u.size >= 4 && u[0] and 1 != 0 -> buildString {
                // WeChat の歩数の形：印のあとに、歩数・距離（m）・消費（kcal）が 3 バイトずつ（小さい桁が先）
                fun n3(i: Int) = if (u.size >= i + 3) u[i] or (u[i + 1] shl 8) or (u[i + 2] shl 16) else null
                append("歩数 ${n3(1)}")
                if (u[0] and 2 != 0) n3(4)?.let { append("・距離 ${it}m") }
                if (u[0] and 4 != 0) n3(7)?.let { append("・消費 ${it}kcal") }
            }
            uuid.startsWith("be94") && deviceInfo(value) != null -> deviceInfo(value)!!.let { "機器の情報：残量 ${it.level}%・版 ${it.version}" }
            uuid.startsWith("be94") -> parse(value)?.let { p ->
                val body = p.payload.joinToString(" ") { "%02x".format(it) }.ifEmpty { "なし" }
                "包み 種類%02x 番号%02x 中身[%s]%s".format(p.type, p.cmd, body, if (p.crcOk) "" else "（CRC が合わない）")
            }
            else -> null
        }
    }
}
