package com.shortdrama.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shortdrama.assist.log.LogDb
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 开发者模式日志。
 *
 * - 始终写入 logcat，方便 adb 调试；
 * - 开启“开发者模式”后，额外写本机 SQLite（[LogDb]），重启后依然在；
 * - 内存里同时留一份最近 [MAX_ENTRIES] 条的环形缓冲，给悬浮日志和即时查看用。
 */
object DevLog {

    const val MAX_ENTRIES = 800
    private const val PREFS = "sd_dev_log"
    private const val KEY_ENABLED = "dev_log_enabled"
    private const val MAX_MESSAGE = 2_000
    private const val EXPORT_LIMIT = 5_000

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
                LogDb.init(app)
                LogDb.prune()
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
            val at = System.currentTimeMillis()
            val text = if (value) "[app] 开发者日志已开启" else "[app] 开发者日志已关闭"
            appendLocked(at, 'I', "DevLog", text)
            LogDb.append(at, 'I', "DevLog", text)
        }
        DevLogOverlay.sync(app)
    }

    /** 清空内存缓冲与数据库。 */
    fun clear() {
        synchronized(lock) { entries.clear() }
        LogDb.clear()
    }

    /** 数据库里一共有多少条（可按级别/关键字过滤）。 */
    fun rowCount(minLevel: Char? = null, keyword: String? = null): Long =
        LogDb.count(minLevel, keyword)

    /** 数据库文件占用（含 -wal / -shm）。 */
    fun dbBytes(): Long = LogDb.fileBytes()

    /**
     * 从数据库按时间倒序读。
     * @param minLevel 只取该级别及以上（D < I < W < E），null 表示全部。
     * @param keyword 对 tag/message 模糊匹配，null 或空表示不过滤。
     */
    fun query(
        limit: Int,
        offset: Int = 0,
        minLevel: Char? = null,
        keyword: String? = null,
    ): List<Entry> = LogDb.query(limit, offset, minLevel, keyword)
        .map { Entry(it.at, it.level, it.tag, it.message) }

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
        val at = System.currentTimeMillis()
        synchronized(lock) { appendLocked(at, level, tag, full) }
        LogDb.append(at, level, tag, full)
    }

    private fun appendLocked(
        at: Long,
        level: Char,
        tag: String,
        message: String,
    ) {
        entries.addLast(Entry(at, level, tag, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun exportText(context: Context): String {
        init(context)
        // 导出走数据库：重启前的日志也要带上；库里还是空的（刚开启/刚清过）退回内存
        val dbRows = rowCount()
        val list = if (dbRows > 0) query(limit = EXPORT_LIMIT) else snapshot()
        return buildString {
            appendLine("续播 开发者日志")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine(
                if (dbRows > 0) "条数: ${list.size} / $dbRows（本机 SQLite 共 $dbRows 条）"
                else "条数: ${list.size} / $MAX_ENTRIES（内存）",
            )
            appendLine("----------------------------------------")
            if (list.isEmpty()) {
                appendLine("（暂无日志）")
            } else {
                list.forEach { appendLine(it.line()) }
            }
        }
    }

    fun share(context: Context) {
        shareText(context, exportText(context))
    }

    /** 分享已导出好的文本（调用方在 IO 线程导出，避免主线程读库）。 */
    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "续播开发者日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "分享开发者日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
