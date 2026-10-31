package com.shortdrama.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 开发者模式日志。
 *
 * - 始终写入 logcat，方便 adb 调试；
 * - 开启“开发者模式”后，额外写入内存环形缓冲，供 App 内查看/复制/分享；
 * - 缓冲只在本进程内，最多 [MAX_ENTRIES] 条，重启 App 后清空。
 */
object DevLog {

    const val MAX_ENTRIES = 800
    private const val PREFS = "sd_dev_log"
    private const val KEY_ENABLED = "dev_log_enabled"
    private const val MAX_MESSAGE = 2_000

    data class Entry(
        val at: Long,
        val level: Char,
        val tag: String,
        val message: String,
    ) {
        fun line(): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(at))
            return "$time ${level}/$tag: $message"
        }
    }

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var enabled = false

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext == null) {
                appContext = app
                enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false)
            }
        }
    }

    fun isEnabled(context: Context? = null): Boolean {
        if (context != null) init(context)
        return enabled
    }

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        val app = context.applicationContext
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, value).apply()
            appendLocked('I', "DevLog", if (value) "[app] 开发者日志已开启" else "[app] 开发者日志已关闭")
        }
        DevLogOverlay.sync(app)
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun d(tag: String, message: String) = log('D', tag, message, null)

    fun i(tag: String, message: String) = log('I', tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log('W', tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log('E', tag, message, throwable)

    /**
     * 标准化事件日志：`[分类] 中文描述`。
     * 分类固定使用小写英文：app / a11y / rule / ad / end / play / verify / unknown / action / error。
     */
    fun event(category: String, message: String, tag: String = "SDLog") =
        i(tag, "[$category] $message")

    fun log(level: Char, tag: String, message: String, throwable: Throwable?) {
        val full = buildString {
            append(message.take(MAX_MESSAGE))
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString().take(MAX_MESSAGE))
            }
        }
        val priority = when (level) {
            'E' -> Log.ERROR
            'W' -> Log.WARN
            'D' -> Log.DEBUG
            else -> Log.INFO
        }
        Log.println(priority, tag, full)
        if (!enabled) return
        synchronized(lock) { appendLocked(level, tag, full) }
    }

    private fun appendLocked(level: Char, tag: String, message: String) {
        entries.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun exportText(context: Context): String {
        init(context)
        val list = snapshot()
        return buildString {
            appendLine("续播 开发者日志")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("条数: ${list.size} / $MAX_ENTRIES")
            appendLine("----------------------------------------")
            if (list.isEmpty()) {
                appendLine("（暂无日志）")
            } else {
                list.forEach { appendLine(it.line()) }
            }
        }
    }

    fun share(context: Context) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "续播开发者日志")
            putExtra(Intent.EXTRA_TEXT, exportText(context))
        }
        val chooser = Intent.createChooser(send, "分享开发者日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
