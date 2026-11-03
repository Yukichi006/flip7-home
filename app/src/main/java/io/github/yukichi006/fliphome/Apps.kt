package io.github.yukichi006.fliphome

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.widget.Toast
import java.text.Collator
import java.util.Locale

/** ランチャーに出るアプリ 1 つ（仕事用プロファイルのアプリも含む）。 */
class AppEntry(val info: LauncherActivityInfo, val serial: Long) {
    val component: ComponentName = info.componentName
    val user: UserHandle = info.user
    val label: String = info.label?.toString().orEmpty().ifEmpty { component.packageName }

    /** 保存に使う、アプリを区別する文字列。 */
    val key = "${component.flattenToString()}#$serial"

    /** アプリが更新されたら刺繍アイコンを作り直すための目印。 */
    val updatedAt: Long = runCatching { info.applicationInfo.sourceDir.let { java.io.File(it).lastModified() } }.getOrDefault(0L)

    /** アプリ一覧の見出し（A〜Z、あ〜わ、他）。 */
    val group: Int = Index.groupOf(label)
}

/** アプリ一覧の見出し。アルファベット、五十音の行、それ以外（漢字・数字など）の順に並べる。 */
object Index {
    val LABELS: List<String> = ('A'..'Z').map { it.toString() } + listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ", "他")
    private const val KANA = 26
    val OTHER = LABELS.size - 1

    // 各行の最後のひらがな（お・ご・ぞ・ど・の・ぽ・も・よ・ろ・ん）
    private val ROW_ENDS = charArrayOf('お', 'ご', 'ぞ', 'ど', 'の', 'ぽ', 'も', 'よ', 'ろ', 'ん')

    fun groupOf(label: String): Int {
        var c = label.trim().firstOrNull() ?: return OTHER
        // 全角英字は半角に
        if (c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') c = (c.code - 0xFEE0).toChar()
        c = c.uppercaseChar()
        if (c in 'A'..'Z') return c - 'A'
        // カタカナはひらがなに
        if (c in 'ァ'..'ヶ') c = (c.code - 0x60).toChar()
        if (c == 'ゔ') return KANA
        if (c in 'ぁ'..'ん') return KANA + ROW_ENDS.indexOfFirst { c <= it }
        return OTHER
    }

    /** 検索で、ひらがな・カタカナ、大文字・小文字の違いを無視するための形。 */
    fun normalize(s: String): String = buildString(s.length) {
        for (ch in s.lowercase(Locale.ROOT)) append(if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch)
    }
}

/**
 * インストール済みのアプリの一覧を読み、追加・削除・更新があれば知らせる。
 */
private const val MAX_FREQUENT = 12

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)!!
    private val userManager = context.getSystemService(UserManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    var apps: List<AppEntry> = emptyList()
        private set
    private val store = LayoutStore(context)
    private val guessed = mutableMapOf<String, Genre>()

    /** 画面に出すアプリ名。自分で付け替えていればそれ、なければアプリ本来の名前。 */
    fun label(app: AppEntry): String = store.nameOf(app.key) ?: app.label

    /** アプリのアイコンにするジャンル。自分で選び直していればそれ、なければ推し量ったもの。 */
    fun genre(app: AppEntry): Genre =
        store.genreOf(app.key) ?: guessed.getOrPut(app.key) { Genre.guess(context, app) }

    /**
     * アプリのアイコン（一辺 [px]）。「アプリのアイコンをなぞる」を選んだアプリは、なぞった刺繍を返す。
     * まだ作っている途中なら、ひとまずジャンルのアイコンを返し、できたら [onReady] を呼ぶ（描き直してもらう）。
     */
    fun icon(app: AppEntry, px: Int, onReady: () -> Unit): Bitmap {
        if (store.iconTraced(app.key)) {
            StitchIcons.cached(app, px)?.let { return it }
            StitchIcons.load(context, app, px) { onReady() }
        }
        return GenreIcons.bitmap(genre(app), px)
    }

    fun traced(app: AppEntry) = store.iconTraced(app.key)

    private val guessedShelf = mutableMapOf<String, Shelf>()

    /** アプリ一覧の巾着の分類。自分で選んでいればそれ、なければ推し量ったもの。 */
    fun shelf(app: AppEntry): Shelf = store.shelfOf(app.key) ?: guessedShelf.getOrPut(app.key) { Shelf.guess(app, genre(app)) }

    /** 分類を推し量り直す（ジャンルを変えたときなど）。 */
    fun forgetGuesses(app: AppEntry) {
        guessedShelf.remove(app.key)
    }

    /**
     * 「よく使う」の巾着に入れるアプリ（いちばん多くて 12 個）。自分で「よく使う」に入れたアプリと、FlipHome から開いた回数の多いアプリ。
     * まだあまり開いていないうちは、ホーム画面と下段に置いたアプリで埋める。
     */
    fun frequent(): List<AppEntry> {
        val pinned = apps.filter { store.shelfOf(it.key) == Shelf.FREQUENT }
        val used = apps.filter { store.launches(it.key) >= 2 && it !in pinned }.sortedByDescending { store.launches(it.key) }
        val list = (pinned + used).toMutableList()
        if (list.size < MAX_FREQUENT && apps.sumOf { store.launches(it.key) } < 30) {
            val home = store.home
            val placed = (home.dock.filterNotNull() + store.thumb.sheets.firstOrNull()?.modules.orEmpty().mapNotNull { it[Module.P_APP] } +
                home.pages.firstOrNull()?.modules.orEmpty().mapNotNull { it[Module.P_APP] })
            for (key in placed) if (list.size < MAX_FREQUENT) find(key)?.let { if (it !in list) list += it }
        }
        return list.take(MAX_FREQUENT)
    }
    private var byKey: Map<String, AppEntry> = emptyMap()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
    }

    private val reload = Runnable {
        load()
        listeners.toList().forEach { it() }
    }

    private fun changed() {
        // 更新のときは削除と追加が続けて届くので、まとめて 1 回だけ読み直す
        main.removeCallbacks(reload)
        main.postDelayed(reload, 300)
    }

    fun start() {
        load()
        launcherApps.registerCallback(callback, main)
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        main.removeCallbacks(reload)
    }

    fun addListener(l: () -> Unit) { listeners += l }

    fun find(key: String): AppEntry? = byKey[key]

    /** パッケージ名でアプリを探す（最初の起動で、電話やカメラを下段に置くため）。 */
    fun findPackage(pkg: String?): AppEntry? = pkg?.let { p -> apps.firstOrNull { it.component.packageName == p } }

    private fun load() {
        val collator = Collator.getInstance(Locale.JAPANESE)
        // 自分のプロファイルが取れないことはまずないが、念のため自分だけでも並べる
        val profiles = runCatching { launcherApps.profiles }.getOrNull().orEmpty().ifEmpty { listOf(android.os.Process.myUserHandle()) }
        val list = profiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                .filter { it.componentName.packageName != context.packageName }
                .map { AppEntry(it, serial) }
        }
        apps = list.sortedWith(compareBy<AppEntry> { it.group }.thenComparator { a, b -> collator.compare(a.label, b.label) })
        byKey = apps.associateBy { it.key }
        guessed.clear()
    }

    /** よく使う標準アプリ（電話・メッセージ・カメラ・設定）。最初の起動で下段に置く。 */
    fun defaultDock(): List<AppEntry> {
        val pm = context.packageManager
        fun pkgOf(intent: Intent) = runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
            ?.takeIf { it != "android" }
        return listOfNotNull(
            findPackage(pkgOf(Intent(Intent.ACTION_DIAL))),
            findPackage(runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()),
            findPackage(pkgOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))),
            findPackage(pkgOf(Intent(Settings.ACTION_SETTINGS))),
        ).distinctBy { it.key }
    }

    fun launch(app: AppEntry, from: View?) {
        val bounds = from?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        val options = from?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
        runCatching { launcherApps.startMainActivity(app.component, app.user, bounds, options) }
            .onSuccess { store.countLaunch(app.key) }
            .onFailure { Toast.makeText(context, "${app.label} を開けませんでした", Toast.LENGTH_SHORT).show() }
    }

    fun openDetails(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }
}
