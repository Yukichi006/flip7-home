package io.github.yukichi006.fliphome

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.util.LruCache

/**
 * アプリのジャンルと、その刺繍アイコン。
 * アイコンは 48×48 のマス目に描いた線画（[path]）を、影つきの点線（ステッチ）で縫って作る。
 * [path] は SVG のパスと同じ書き方（M L H V C S Q Z、小文字は相対）に、円を表す「O 中心x,中心y,半径」を足したもの。
 */
enum class Genre(val label: String, val path: String) {
    PHONE("電話", "M14 9c2-2 5-2 6 1l2 5c1 2 0 3-2 4l-2 1c1 4 5 8 9 9l1-2c1-2 2-3 4-2l5 2c3 1 3 4 1 6l-3 3c-3 2-11 0-17-6S9 15 11 12z"),
    MESSAGE("メッセージ", "M8 12h32v20H22l-8 7v-7H8z M15 20h18 M15 26h11"),
    CHAT("チャット・SNS", "O18,17,6 M7 38c0-7 5-12 11-12s11 5 11 12 O33,19,4.5 M31 27c5 0 10 4 10 10"),
    MAIL("メール", "M7 13h34v23H7z M7 13l17 13 17-13"),
    CAMERA("カメラ", "M7 16h8l3-5h12l3 5h8v22H7z O24,26,7"),
    PHOTO("写真", "M7 11h34v26H7z M7 32l10-10 8 8 6-5 10 9 O32,18,3"),
    BROWSER("ブラウザ", "O24,24,16 M8 24h32 M24 8c-6 6-6 26 0 32 M24 8c6 6 6 26 0 32"),
    MAP("地図", "M24 41C24 41 12 29 12 20C12 13 17 8 24 8C31 8 36 13 36 20C36 29 24 41 24 41z O24,20,4.5"),
    TRAIN("乗り物", "M13 8h22v24H13z M13 21h22 O18,27,1.5 O30,27,1.5 M17 32l-5 8 M31 32l5 8"),
    PLANE("旅行", "M24 6c2 0 3 2 3 4v10l14 8v4l-14-4v8l4 3v3l-7-2-7 2v-3l4-3v-8l-14 4v-4l14-8V10c0-2 1-4 3-4z"),
    MUSIC("音楽", "M18 34V12l18-4v22 O14,34,4.5 O32,30,4.5"),
    VIDEO("動画", "M7 11h34v26H7z M20 18v12l10-6z"),
    CALENDAR("カレンダー", "M8 11h32v28H8z M8 19h32 M16 7v8 M32 7v8"),
    CLOCK("時計", "O24,24,16 M24 14v10l7 5"),
    NOTE("メモ", "M12 8h18l7 7v25H12z M17 20h14 M17 26h14 M17 32h9"),
    TOOL("仕事", "M7 15h34v23H7z M18 15v-5h12v5 M7 25h34"),
    FILES("ファイル", "M6 12h13l4 5h19v21H6z M6 20h36"),
    SHOP("買い物", "M6 10h6l5 20h20l4-14H14 O19,37,3 O34,37,3"),
    STORE("ストア", "M10 15h28l-3 25H13z M18 15c0-4 3-8 6-8s6 4 6 8"),
    WALLET("支払い", "M8 14h30v24H8z M30 22h10v8H30z M12 14l18-6 2 6"),
    BANK("銀行", "M6 18L24 8L42 18z M11 22v12 M19 22v12 M29 22v12 M37 22v12 M6 38h36"),
    AI("AI", "M24 6L27 21L42 24L27 27L24 42L21 27L6 24L21 21Z M38 6v6 M35 9h6"),
    GAME("ゲーム", "M10 17h28c4 0 6 3 6 7l-1 8c-1 4-5 5-8 2l-4-4H17l-4 4c-3 3-7 2-8-2l-1-8c0-4 2-7 6-7z M13 24h8 M17 20v8 O31,23,1.5 O35,27,1.5"),
    READ("読む", "M6 12c6-2 12-2 18 2v24c-6-4-12-4-18-2z M42 12c-6-2-12-2-18 2v24c6-4 12-4 18-2z"),
    WEATHER("天気", "O17,17,6 M17 5v3 M5 17h3 M8.5 8.5l2 2 M25.5 8.5l-2 2 M16 39h20c4 0 7-3 7-7s-3-7-7-7c-1-4-5-6-9-5c-3 1-5 4-5 7c-4 0-6 3-6 6s3 6 6 6z"),
    HEALTH("健康", "M24 40C24 40 7 29 7 18C7 12 11 8 16 8C20 8 23 11 24 13C25 11 28 8 32 8C37 8 41 12 41 18C41 29 24 40 24 40z M12 24h7l3-5 4 9 3-4h7"),
    FOOD("食べ物", "M14 6v12c0 3 2 5 4 5s4-2 4-5V6 M18 6v36 M33 6c-4 2-5 8-5 14h5v22"),
    POWER("電気", "M27 6L13 27h10l-3 15 15-22H25z"),
    DOWNLOAD("ダウンロード", "M24 8v22 M15 22l9 9 9-9 M10 38h28"),
    SETTINGS("設定", "O24,24,6 M24 7v6 M24 35v6 M7 24h6 M35 24h6 M12 12l4 4 M32 32l4 4 M36 12l-4 4 M16 32l-4 4"),
    OTHER("その他", "M10 10h11v11H10z M27 10h11v11H27z M10 27h11v11H10z M27 27h11v11H27z"),
    ;

    companion object {
        /**
         * アプリ名・パッケージ名の手がかりと、ジャンルの対応。上にあるものほど優先する。
         * 「=」で始まる語は、名前の区切り（. や空白）で分けた語と完全に一致したときだけ当てはめる（短い語の誤爆を防ぐ）。
         */
        private val RULES: List<Pair<Genre, List<String>>> = listOf(
            AI to listOf("claude", "chatgpt", "openai", "gemini", "copilot", "perplexity", "bixby", "=bard", "assistant"),
            SETTINGS to listOf("settings", "setting"),
            CAMERA to listOf("camera"),
            PHONE to listOf("dialer", "=phone", "telecom", "contacts", "電話"),
            MESSAGE to listOf("messag", "=sms", "=mms", "メッセージ"),
            CHAT to listOf("=line", "slack", "discord", "whatsapp", "telegram", "messenger", "between", "kakao", "instagram", "twitter", "=x", "facebook", "threads", "bluesky", "mastodon", "tiktok"),
            MAIL to listOf("mail", "outlook", "メール"),
            PHOTO to listOf("photo", "gallery", "album", "写真"),
            BROWSER to listOf("chrome", "browser", "firefox", "=edge", "opera", "brave", "sbrowser", "vivaldi"),
            VIDEO to listOf("youtube", "video", "netflix", "=avod", "tver", "abema", "hulu", "disney", "=tv", "twitch", "niconico", "動画"),
            MUSIC to listOf("music", "spotify", "=audio", "radio", "podcast", "sound", "=fm", "音楽"),
            MAP to listOf("=maps", "=map", "waze", "地図", "マップ"),
            TRAIN to listOf("=jr", "train", "transit", "norikae", "jorudan", "navitime", "=bus", "taxi", "uber", "=go", "乗換", "=access", "=ekinet", "=eki", "jreast", "jr東日本", "jr_central", "exreserve", "taxibell", "=ex"),
            PLANE to listOf("=ana", "=jal", "airline", "flight", "travel", "booking", "airbnb", "=trip", "skyscanner", "jalan", "rakutentravel"),
            WALLET to listOf("paypay", "wallet", "suica", "=pay", "=pasmo", "=ic", "=quicpay", "=edy", "=waon", "=nanaco", "=id"),
            BANK to listOf("bank", "mufg", "smbc", "mizuho", "=card", "銀行", "証券", "sbi"),
            SHOP to listOf("mshop", "shopping", "rakuten", "mercari", "=shop", "yahoo.shp", "zozo", "uniqlo", "ショッピング"),
            STORE to listOf("vending", "galaxyapps", "=store", "playstore"),
            DOWNLOAD to listOf("obtainium", "download", "fdroid", "droidify"),
            CALENDAR to listOf("calendar", "カレンダー"),
            CLOCK to listOf("clock", "alarm", "時計"),
            NOTE to listOf("=note", "notes", "memo", "=keep", "notion", "obsidian", "メモ"),
            WEATHER to listOf("weather", "tenki", "天気"),
            HEALTH to listOf("health", "=fit", "fitness", "sleep", "=step", "=steps", "ヘルス", "=watch", "wearable"),
            FOOD to listOf("food", "=eats", "demaecan", "tabelog", "gourmet", "recipe", "cookpad", "mcdonald", "starbucks", "=menu"),
            READ to listOf("news", "kindle", "=book", "books", "manga", "comic", "smartnews", "nikkei", "ニュース", "マンガ"),
            TOOL to listOf("office", "=word", "excel", "powerpoint", "=docs", "sheets", "=drive", "teams", "zoom", "calculator", "=calc", "translate", "scanner", "=meet", "handy", "bambu"),
            FILES to listOf("files", "myfiles", "filemanager", "folder", "ファイル"),
            POWER to listOf("charge", "chargespot", "battery", "=power", "denki", "電気"),
            GAME to listOf("game", "games", "madness", "puzzle"),
        )

        /** アプリの名前などから、ジャンルを推し量る。分からなければ [OTHER]。 */
        fun guess(context: Context, app: AppEntry): Genre {
            val pkg = app.component.packageName
            defaultApps(context)[pkg]?.let { return it }
            val tokens = (pkg.lowercase().split('.', '_', '-') + app.label.lowercase().split(' ', '　', '・', '-')).toSet()
            val haystack = pkg.lowercase() + " " + app.label.lowercase()
            for ((genre, words) in RULES) {
                for (w in words) {
                    val hit = if (w.startsWith("=")) w.substring(1) in tokens else w in haystack
                    if (hit) return genre
                }
            }
            return when (app.info.applicationInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> GAME
                ApplicationInfo.CATEGORY_AUDIO -> MUSIC
                ApplicationInfo.CATEGORY_VIDEO -> VIDEO
                ApplicationInfo.CATEGORY_IMAGE -> PHOTO
                ApplicationInfo.CATEGORY_SOCIAL -> CHAT
                ApplicationInfo.CATEGORY_NEWS -> READ
                ApplicationInfo.CATEGORY_MAPS -> MAP
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> TOOL
                else -> OTHER
            }
        }

        private var defaults: Map<String, Genre>? = null

        /** 端末で「いつもの電話・メッセージ・カメラ・設定・ブラウザ」になっているアプリ。 */
        private fun defaultApps(context: Context): Map<String, Genre> = defaults ?: run {
            val pm = context.packageManager
            fun pkgOf(intent: Intent) = runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
                ?.takeIf { it != "android" }
            buildMap {
                pkgOf(Intent(Intent.ACTION_DIAL))?.let { put(it, PHONE) }
                runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let { put(it, MESSAGE) }
                pkgOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let { put(it, CAMERA) }
                pkgOf(Intent(Settings.ACTION_SETTINGS))?.let { put(it, SETTINGS) }
                pkgOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")))?.let { put(it, BROWSER) }
            }.also { defaults = it }
        }
    }
}

/** ジャンルのアイコンを、刺繍の絵（ビットマップ）にして返す。一度作ったものは覚えておく。 */
object GenreIcons {
    private val cache = LruCache<String, Bitmap>(80)
    private val paths = mutableMapOf<Genre, Path>()

    fun bitmap(genre: Genre, size: Int): Bitmap = cache.get("$genre:$size:${StitchStyle.scale}") ?: run {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path(path(genre))
        // 48×48 の線画を、影の分の余白を残して一辺 size に広げる
        val inset = size * 0.06f
        path.transform(Matrix().apply {
            setScale((size - inset * 2) / 48f, (size - inset * 2) / 48f)
            postTranslate(inset, inset)
        })
        canvas.drawPath(path, stitchPaint(size * 0.05f, size * 0.105f, size * 0.075f))
        bitmap.also { cache.put("$genre:$size:${StitchStyle.scale}", it) }
    }

    private fun path(genre: Genre): Path = paths.getOrPut(genre) { MiniPath.parse(genre.path) }
}

/**
 * SVG のパスの書き方（M L H V C S Q Z と、その小文字＝相対）と、円「O cx,cy,r」を読んで Path にする。
 */
object MiniPath {
    fun parse(d: String): Path {
        val path = Path()
        val tokens = Regex("[A-Za-z]|-?\\d*\\.?\\d+(?:e-?\\d+)?").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var lastC2x = 0f
        var lastC2y = 0f
        var lastWasCubic = false
        fun num(): Float = tokens[i++].toFloat()
        fun hasNum() = i < tokens.size && !tokens[i][0].isLetter()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            var cubic = false
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + num(); y = oy + num()
                    path.moveTo(x, y); startX = x; startY = y
                    // M のあとに続く座標は L として扱う
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { x = ox + num(); y = oy + num(); path.lineTo(x, y) }
                'H' -> { x = ox + num(); path.lineTo(x, y) }
                'V' -> { y = oy + num(); path.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num()
                    val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastC2x = x2; lastC2y = y2; cubic = true
                }
                'S' -> {
                    val x1 = if (lastWasCubic) 2 * x - lastC2x else x
                    val y1 = if (lastWasCubic) 2 * y - lastC2y else y
                    val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastC2x = x2; lastC2y = y2; cubic = true
                }
                'Q' -> {
                    val x1 = ox + num(); val y1 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.quadTo(x1, y1, x, y)
                }
                'Z' -> { path.close(); x = startX; y = startY }
                'O' -> {
                    val cx = num(); val cy = num(); val r = num()
                    path.addCircle(cx, cy, r, Path.Direction.CW)
                }
                else -> i++
            }
            lastWasCubic = cubic
            // Z のあとに数字が続く（書き間違いの）ときに止まらないよう、読み飛ばす
            if (cmd.uppercaseChar() == 'Z' && hasNum()) i++
        }
        return path
    }
}
