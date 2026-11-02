package io.github.yukichi006.fliphome

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知が来ているアプリを知るための窓口（通知へのアクセスを許可したときだけ動く）。
 * 使うのは「どのアプリに、いくつ通知が来ているか」だけで、通知の中身（題名・本文）は読まず、保存もしない。
 */
class BadgeListener : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onListenerDisconnected() { Badges.update(emptyMap()) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val list = runCatching { activeNotifications }.getOrNull().orEmpty()
        val counts = list.asSequence()
            // 常に出ている通知（音楽の再生中など）と、まとめ役の通知は数えない
            .filter { it.isClearable && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { it.packageName != packageName }
            .groupingBy { it.packageName }.eachCount()
        Badges.update(counts)
    }
}

/** アプリごとの通知の数。ホーム画面のパッドが、これを見て糸の玉（印）を付ける。 */
object Badges {
    private val main = Handler(Looper.getMainLooper())
    private var counts: Map<String, Int> = emptyMap()
    private val listeners = mutableSetOf<() -> Unit>()

    fun count(pkg: String): Int = counts[pkg] ?: 0

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun update(new: Map<String, Int>) = main.post {
        if (new == counts) return@post
        counts = new
        listeners.toList().forEach { it() }
    }

    /** 通知へのアクセスが許可されているか。 */
    fun enabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, BadgeListener::class.java)) == true

    /** 通知へのアクセスを許可する設定画面を開く（FlipHome の項目を直接開けなければ一覧を開く）。 */
    fun openSettings(context: Context) {
        val component = ComponentName(context, BadgeListener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
