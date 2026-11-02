package io.github.yukichi006.fliphome

import org.json.JSONObject

/**
 * MPC（M.P.C）の「配置の書き出し」ファイル（mpc-layout-*.json）を読み、FlipHome の配置に置き換える。
 *
 * MPC の部品と FlipHome の部品の対応：
 * - パッド（button / app）→ パッド（下に引いて開く 2 つ目のアプリは、裏地のアプリとして引き継ぐ）
 * - ノブ（button / folder）→ 巾着（名前と中のアプリ）
 * - フェーダー（slider）→ ファスナーのフェーダー（音量・明るさ・アプリ）
 * - 時計（clock）→ 時刻（秒つき）、日付（date）→ 日付（11.02 MON の形）
 * - ドットカレンダー（cal）→ 月のカレンダー（スワイプの割り当てと週の始まりも引き継ぐ）
 * - 見出し（title）→ 見出し、再生中の曲（media）→ 再生中の曲
 * - アプリ名の付け替え（appNames）→ 名前の付け替え
 * - 天気（weather）→ 天気（地点・最高最低の表示・タップで開くアプリ）
 */
object MpcImport {
    class Result(val home: Home, val names: Map<String, String>, val placed: Int, val skipped: List<String>)

    /** MPC の部品の、大きさが書かれていないときの大きさ（MPC の画面から読み取ったもの）。 */
    private val DEFAULT_SIZE = mapOf(
        "button" to (1 to 1), "slider" to (1 to 2), "clock" to (2 to 1), "date" to (3 to 2),
        "cal" to (3 to 3), "weather" to (1 to 3), "media" to (4 to 1), "title" to (1 to 3),
    )

    fun read(text: String, repo: AppRepository, newId: () -> Long): Result {
        val root = JSONObject(text)
        require(root.optString("format").startsWith("mcp.layout")) { "MPC の配置ファイルではありません" }
        val state = root.getJSONObject("state")
        // Flip のメイン画面は、MPC の「カバー画面」の配置（縦長・5 列）に当たる
        val screen = state.optJSONObject("cover") ?: state.getJSONObject("main")
        val mondayFirst = state.optBoolean("weekStartMonday", false)
        val skipped = mutableListOf<String>()

        fun appKey(o: JSONObject?): String? {
            o ?: return null
            val pkg = o.optString("packageName")
            val key = "$pkg/${o.optString("activityClass")}#${o.optInt("userSerial", 0)}"
            // アプリの更新で入口の画面の名前が変わっていることがあるので、見つからなければパッケージ名で探す
            val found = repo.find(key) ?: repo.findPackage(pkg)
            if (found == null) skipped += o.optString("label", pkg)
            return found?.key
        }

        val pagesJson = screen.getJSONArray("pages")
        val rows = screen.optInt("pageRows", 9).coerceIn(LayoutStore.MIN_ROWS, LayoutStore.MAX_ROWS)
        var maxCol = 0
        val pages = (0 until pagesJson.length()).map { pi ->
            val ms = pagesJson.getJSONObject(pi).getJSONArray("modules")
            (0 until ms.length()).mapNotNull { mi ->
                val o = ms.getJSONObject(mi)
                val type = o.optString("type")
                val (dw, dh) = DEFAULT_SIZE[type] ?: (1 to 1)
                val col = o.optInt("cellX", 0)
                val row = o.optInt("cellY", 0)
                val w = if (o.isNull("spanX")) dw else o.optInt("spanX", dw)
                val h = if (o.isNull("spanY")) dh else o.optInt("spanY", dh)
                val align = o.optString("align").takeIf { it.isNotEmpty() && it != "null" }
                val slot = o.optJSONObject("slot")
                val props = mutableMapOf<String, String>()
                val ourType: ModuleType = when (type) {
                    "button" -> when (slot?.optString("type")) {
                        "app" -> {
                            props[Module.P_APP] = appKey(slot.optJSONObject("app")) ?: return@mapNotNull null
                            appKey(slot.optJSONObject("longApp"))?.let { props[Module.P_LONG_APP] = it }
                            ModuleType.APP
                        }
                        "folder" -> {
                            props[Module.P_NAME] = slot.optString("name", "フォルダ")
                            val apps = slot.optJSONArray("apps")
                            props[Module.P_APPS] = (0 until (apps?.length() ?: 0)).mapNotNull { appKey(apps!!.getJSONObject(it)) }.joinToString("\n")
                            ModuleType.FOLDER
                        }
                        else -> return@mapNotNull null
                    }
                    "slider" -> {
                        props[Module.P_FUNCTION] = when (slot?.optString("function")) {
                            "brightness" -> "brightness"
                            "volume" -> "volume"
                            else -> "app"
                        }
                        appKey(slot?.optJSONObject("topApp"))?.let { props[Module.P_TOP_APP] = it }
                        appKey(slot?.optJSONObject("bottomApp"))?.let { props[Module.P_BOTTOM_APP] = it }
                        ModuleType.SLIDER
                    }
                    "clock" -> { props[Module.P_SECONDS] = "1"; align?.let { props[Module.P_ALIGN] = it }; ModuleType.TIME }
                    "date" -> { props[Module.P_STYLE] = "dot"; align?.let { props[Module.P_ALIGN] = it }; ModuleType.DATE }
                    "cal" -> {
                        if (mondayFirst) props[Module.P_MONDAY] = "1"
                        val g = o.optJSONObject("gestures")
                        for (dir in listOf("up", "down", "left", "right")) {
                            val a = g?.optJSONObject(dir) ?: continue
                            when (a.optString("type")) {
                                "shade" -> props[Module.gestureKey(dir)] = "shade"
                                "app" -> appKey(a.optJSONObject("app"))?.let { props[Module.gestureKey(dir)] = "app:$it" }
                            }
                        }
                        ModuleType.CALENDAR
                    }
                    "title" -> {
                        val sub = o.optString("subtitle").takeIf { it.isNotEmpty() && it != "null" }
                        val t = o.optString("text").takeIf { it.isNotEmpty() && it != "null" && it.trim().replace(" ", "").any { c -> c != 'l' } }
                        props[Module.P_TEXT] = sub ?: t ?: "${pi + 1}"
                        align?.let { props[Module.P_ALIGN] = it }
                        ModuleType.TITLE
                    }
                    "media" -> ModuleType.MEDIA
                    "weather" -> {
                        o.optJSONObject("weather")?.let { wj ->
                            wj.optString("city").takeIf { it.isNotEmpty() && it != "null" }?.let { props[Module.P_CITY] = it }
                            if (wj.has("lat") && wj.has("lon")) {
                                props[Module.P_LAT] = wj.getDouble("lat").toString()
                                props[Module.P_LON] = wj.getDouble("lon").toString()
                            }
                            if (!wj.optBoolean("showMinMax", true)) props[Module.P_MINMAX] = "0"
                            appKey(wj.optJSONObject("tapApp"))?.let { props[Module.P_TAP_APP] = it }
                        }
                        ModuleType.WEATHER
                    }
                    else -> { skipped += "（部品：$type）"; return@mapNotNull null }
                }
                maxCol = maxOf(maxCol, col + w)
                Module(newId(), ourType, col, row, w, h, props)
            }
        }
        val cols = maxCol.coerceIn(5, LayoutStore.MAX_COLS)
        // 重なる部品やマス目からはみ出す部品は、置かずに飛ばす
        val home0 = Home(cols, rows, emptyList(), pocket = false, dock = List(LayoutStore.DOCK_SLOTS) { null })
        var placed = 0
        val fitted = pages.map { list ->
            val kept = mutableListOf<Module>()
            for (m in list) if (LayoutStore.fits(home0, kept, m)) { kept += m; placed++ }
            Page(kept)
        }

        val names = mutableMapOf<String, String>()
        state.optJSONObject("appNames")?.let { n ->
            for (k in n.keys()) {
                val found = repo.find("$k#0") ?: repo.findPackage(k.substringBefore('/'))
                if (found != null) names[found.key] = n.getString(k)
            }
        }
        return Result(home0.copy(pages = fitted.ifEmpty { listOf(Page(emptyList())) }), names, placed, skipped.distinct())
    }
}
