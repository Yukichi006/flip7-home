package io.github.yukichi006.fliphome

import android.content.pm.ApplicationInfo

/**
 * アプリ一覧の「巾着」の分け方（分類）。アイコンの絵のジャンル（[Genre]）とは別に、少なめの分類にまとめる。
 * [icon] は巾着に刺繍する絵。
 */
enum class Shelf(val label: String, val icon: Genre) {
    FREQUENT("よく使う", Genre.STAR),
    WORK("仕事", Genre.TOOL),
    CONTACT("連絡", Genre.CHAT),
    TRANSIT("交通・おでかけ", Genre.TRAIN),
    PAY("支払い・お金", Genre.WALLET),
    HOME("家庭・くらし", Genre.HOME),
    PHOTO("写真・カメラ", Genre.CAMERA),
    SHOP("買い物・食べ物", Genre.SHOP),
    FUN("音楽・動画・本", Genre.MUSIC),
    GAME("ゲーム", Genre.GAME),
    TOOLS("AI・ツール", Genre.AI),
    SYSTEM("既定アプリ", Genre.SETTINGS),
    OTHER("その他", Genre.OTHER),
    ;

    companion object {
        /** 仕事（オリィ研究所まわり）のアプリの手がかり（パッケージ名かアプリ名に含まれる語。「=」で始まる語は区切りの単位で）。 */
        private val WORK_WORDS = listOf(
            "orihime", "=ory", "slack", "notion", "freee", "smarthr", "gallup", "bizreach", "ビズリーチ", "経費",
            "com.google.android.apps.docs", "=meet", "meetings", "teams", "zoom", "office", "onedrive", "outlook",
            "kintone", "backlog", "jobcan", "chatwork", "lark", "=box", "dropbox", "salesforce", "hubspot",
        )

        /** 家庭・くらしのアプリの手がかり。 */
        private val HOME_WORDS = listOf(
            "smartthings", "life360", "between", "pet", "ペット", "家計", "kakeibo", "zaim", "moneyforward", "kids", "baby",
            "mamari", "ゴミ", "gomi", "switchbot", "nature", "alexa", "google.android.apps.chromecast", "family", "ファミリー",
            "panasonic", "daikin", "sharp", "hitachi", "toshiba", "irobot", "roomba", "bosai", "防災", "school", "学校",
        )

        private fun hit(words: List<String>, tokens: Set<String>, haystack: String) =
            words.any { w -> if (w.startsWith("=")) w.substring(1) in tokens else w in haystack }

        /**
         * アプリの分類を推し量る。仕事（名前の手がかりがあるもの）・写真カメラ・交通・支払い・家庭は、端末に最初から入っている
         * アプリでもそちらを優先し、それ以外の最初から入っているアプリは「既定アプリ」にまとめる。
         */
        fun guess(app: AppEntry, genre: Genre): Shelf {
            val pkg = app.component.packageName.lowercase()
            val label = app.label.lowercase()
            val tokens = (pkg.split('.', '_', '-') + label.split(' ', '　', '・', '-')).toSet()
            val haystack = "$pkg $label"
            if (hit(WORK_WORDS, tokens, haystack)) return WORK
            when (genre) {
                Genre.CAMERA, Genre.PHOTO -> return PHOTO
                Genre.MAP, Genre.TRAIN, Genre.PLANE, Genre.POWER -> return TRANSIT
                Genre.WALLET, Genre.BANK -> return PAY
                Genre.HOME, Genre.HEALTH, Genre.WEATHER -> return HOME
                else -> {}
            }
            if (hit(HOME_WORDS, tokens, haystack)) return HOME
            val flags = app.info.applicationInfo.flags
            if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return SYSTEM
            return when (genre) {
                // 仕事のアイコンのアプリ（電卓や翻訳など）は、最初から入っているものは既定アプリ、自分で入れたものは仕事
                Genre.TOOL -> WORK
                Genre.PHONE, Genre.MESSAGE, Genre.CHAT, Genre.MAIL -> CONTACT
                Genre.SHOP, Genre.FOOD, Genre.STORE -> SHOP
                Genre.MUSIC, Genre.VIDEO, Genre.READ -> FUN
                Genre.GAME -> GAME
                Genre.AI, Genre.NOTE, Genre.CALENDAR, Genre.CLOCK, Genre.FILES, Genre.DOWNLOAD, Genre.SETTINGS, Genre.BROWSER -> TOOLS
                else -> OTHER
            }
        }
    }
}
