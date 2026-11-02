package io.github.yukichi006.fliphome

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * アプリ全体の入口。不具合の記録係（[Diagnostics]）をいちばん最初に動かす。
 */
class FlipHomeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}

/**
 * 不具合の記録係。
 * - アプリが落ちたら、そのときのエラーの内容をファイルに書く
 * - 画面の処理が [HANG_MS] 以上止まったら（固まったら）、そのとき何をしていたかをファイルに書く
 * 記録はこの端末のアプリ専用の場所にだけ保存し、外へは送らない（インターネットの権限もない）。
 * 次に開いたとき、[HomeActivity] が記録を画面に出し、コピーできるようにする。
 */
object Diagnostics {
    private const val FILE = "diagnostics.txt"
    private const val HANG_MS = 4_000L

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = java.lang.Thread.getDefaultUncaughtExceptionHandler()
        java.lang.Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "落ちました（スレッド: ${thread.name}）", stackText(error)) }
            previous?.uncaughtException(thread, error)
        }
        watchMainThread(app)
    }

    /** 画面の処理（メインスレッド）が止まっていないかを、別のスレッドから見張る。 */
    private fun watchMainThread(app: Context) {
        val main = Handler(Looper.getMainLooper())
        java.lang.Thread({
            var reported = false
            while (true) {
                val answered = AtomicBoolean(false)
                val sentAt = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                java.lang.Thread.sleep(HANG_MS)
                if (!answered.get() && !reported) {
                    reported = true
                    val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                    val waited = SystemClock.uptimeMillis() - sentAt
                    runCatching { write(app, "画面の処理が ${waited / 1000} 秒以上止まりました", trace) }
                }
                if (answered.get()) reported = false
            }
        }, "fliphome-watchdog").apply { isDaemon = true }.start()
    }

    private fun stackText(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    @Synchronized
    private fun write(context: Context, title: String, body: String) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
        val text = buildString {
            append("■ $title\n")
            append("日時: $time／FlipHome $version／${Build.MANUFACTURER} ${Build.MODEL}／Android ${Build.VERSION.RELEASE}\n")
            append(body.take(12_000))
            append("\n\n")
        }
        // 古い記録が増えすぎないよう、最新のものを先頭にして 3 万文字までにする
        val old = runCatching { file(context).readText() }.getOrDefault("")
        file(context).writeText((text + old).take(30_000))
    }

    fun read(context: Context): String? = runCatching { file(context).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * 記録があれば、それを表示するだけの簡単な画面を出して true を返す。
     * ホーム画面の組み立てで固まる場合にも見られるよう、ここでは布地などの凝った描画はしない。
     */
    fun showIfAny(activity: Activity, onContinue: () -> Unit): Boolean {
        val text = read(activity) ?: return false
        val pad = activity.dpi(16f)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Cloth.BASE)
            setPadding(pad, pad * 3, pad, pad * 2)
        }
        column.addView(TextView(activity).apply {
            this.text = "前回、FlipHome で問題が起きました。\n下の「コピー」を押して、内容を Claude に貼り付けてください。"
            setTextColor(Thread.COLOR)
            textSize = 15f
        })
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, click: () -> Unit) = Button(activity).apply {
            this.text = label
            setOnClickListener { click() }
        }
        buttons.addView(button("コピー") {
            activity.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("FlipHome の記録", text))
            Toast.makeText(activity, "コピーしました", Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button("消してホームへ") {
            clear(activity)
            onContinue()
        })
        column.addView(buttons)
        column.addView(ScrollView(activity).apply {
            addView(TextView(activity).apply {
                this.text = text
                setTextColor(Color.rgb(200, 200, 190))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.fitsSystemWindows = true
        activity.setContentView(column)
        column.visibility = View.VISIBLE
        return true
    }
}
