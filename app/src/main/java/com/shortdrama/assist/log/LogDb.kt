package com.shortdrama.assist.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开发者日志的落地层：本机 SQLite。
 *
 * 为什么不用文件：日志会跨重启保留，还要按级别/关键字翻页查；写文件再自己解析
 * 索引得不偿失，直接用 SQLite。读写在单线程 executor 上排队，绝不碰主线程。
 *
 * - 表 `logs`：`_id` 自增、`at` 毫秒时间戳、`level` 单字符（D/I/W/E）、`tag`、`message`。
 * - 只保留最近 [MAX_ROWS] 条与 [MAX_AGE_DAYS] 天，写入累计到阈值就顺手清理一次，
 *   避免这个库无限长大把用户存储吃掉。
 */
object LogDb {

    const val DB_NAME = "dev_log.db"
    const val MAX_ROWS = 20_000
    const val MAX_AGE_DAYS = 7

    /** 一行日志（与 DevLog.Entry 解耦，避免日志层互相依赖）。 */
    data class Row(
        val at: Long,
        val level: Char,
        val tag: String,
        val message: String,
    )

    private const val PACKAGE_NAME = "logs"
    private const val COL_AT = "at"
    private const val COL_LEVEL = "level"
    private const val COL_TAG = "tag"
    private const val COL_MESSAGE = "message"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sd-log-db").apply { isDaemon = true }
    }

    private val pending = ArrayList<Row>()
    private val pendingLock = Any()
    private val pruneScheduled = AtomicBoolean(false)
    private var insertsSincePrune = 0

    @Volatile
    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) helper = Helper(context.applicationContext)
            }
        }
    }

    /** 异步写一条；同一批在一个事务里提交，避免每条都 fsync。 */
    fun append(at: Long, level: Char, tag: String, message: String) {
        val row = Row(at, level, tag, message)
        val flushNow = synchronized(pendingLock) {
            pending.add(row)
            pending.size >= 16
        }
        if (flushNow) flush() else scheduleFlush()
    }

    private fun scheduleFlush() {
        executor.execute { flush() }
    }

    private fun flush() {
        val batch: List<Row>
        synchronized(pendingLock) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
        }
        val db = writable() ?: return
        runCatching {
            db.beginTransaction()
            try {
                val cv = ContentValues()
                batch.forEach { row ->
                    cv.clear()
                    cv.put(COL_AT, row.at)
                    cv.put(COL_LEVEL, row.level.toString())
                    cv.put(COL_TAG, row.tag)
                    cv.put(COL_MESSAGE, row.message)
                    db.insert(PACKAGE_NAME, null, cv)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        insertsSincePrune += batch.size
        if (insertsSincePrune >= 200) {
            insertsSincePrune = 0
            prune()
        }
    }

    /**
     * 按时间倒序查。
     * @param minLevel 只取该级别及以上（D < I < W < E），null 表示全部。
     * @param keyword 对 message/tag 做 LIKE 模糊匹配，null/空表示不过滤。
     */
    fun query(
        limit: Int,
        offset: Int = 0,
        minLevel: Char? = null,
        keyword: String? = null,
    ): List<Row> {
        flush()
        val db = readable() ?: return emptyList()
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (minLevel != null && minLevel != 'D') {
            val levels = severityAtLeast(minLevel)
            where.add("$COL_LEVEL IN (${levels.joinToString(",") { "?" }})")
            args.addAll(levels)
        }
        if (!keyword.isNullOrBlank()) {
            where.add("($COL_MESSAGE LIKE ? OR $COL_TAG LIKE ?)")
            val like = "%" + keyword.trim() + "%"
            args.add(like)
            args.add(like)
        }
        val clause = if (where.isEmpty()) null else where.joinToString(" AND ")
        return runCatching {
            db.query(
                PACKAGE_NAME,
                arrayOf(COL_AT, COL_LEVEL, COL_TAG, COL_MESSAGE),
                clause,
                if (args.isEmpty()) null else args.toTypedArray(),
                null,
                null,
                "$COL_AT DESC, _id DESC",
                "$offset,$limit",
            ).use { cursor ->
                val list = ArrayList<Row>(cursor.count)
                while (cursor.moveToNext()) {
                    list.add(
                        Row(
                            at = cursor.getLong(0),
                            level = cursor.getString(1)?.firstOrNull() ?: 'I',
                            tag = cursor.getString(2) ?: "",
                            message = cursor.getString(3) ?: "",
                        ),
                    )
                }
                list
            }
        }.getOrDefault(emptyList())
    }

    fun count(minLevel: Char? = null, keyword: String? = null): Long {
        flush()
        val db = readable() ?: return 0
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (minLevel != null && minLevel != 'D') {
            val levels = severityAtLeast(minLevel)
            where.add("$COL_LEVEL IN (${levels.joinToString(",") { "?" }})")
            args.addAll(levels)
        }
        if (!keyword.isNullOrBlank()) {
            where.add("($COL_MESSAGE LIKE ? OR $COL_TAG LIKE ?)")
            val like = "%" + keyword.trim() + "%"
            args.add(like)
            args.add(like)
        }
        val clause = if (where.isEmpty()) null else where.joinToString(" AND ")
        return runCatching {
            db.rawQuery(
                "SELECT COUNT(*) FROM $PACKAGE_NAME" + (clause?.let { " WHERE $it" } ?: ""),
                if (args.isEmpty()) null else args.toTypedArray(),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        }.getOrDefault(0L)
    }

    fun clear() {
        synchronized(pendingLock) { pending.clear() }
        executor.execute {
            val db = writable() ?: return@execute
            runCatching { db.delete(PACKAGE_NAME, null, null) }
            // delete 只是把页标记成空闲，文件不会变小；用户点“清空”就该看到占用归零
            runCatching { db.execSQL("VACUUM") }
            runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
    }

    /** 保留策略：先按天数、再按条数裁剪。 */
    fun prune() {
        if (!pruneScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                val db = writable() ?: return@execute
                val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
                runCatching {
                    db.delete(PACKAGE_NAME, "$COL_AT < ?", arrayOf(cutoff.toString()))
                }
                runCatching {
                    db.execSQL(
                        "DELETE FROM $PACKAGE_NAME WHERE _id NOT IN " +
                            "(SELECT _id FROM $PACKAGE_NAME ORDER BY $COL_AT DESC, _id DESC LIMIT $MAX_ROWS)",
                    )
                }
                runCatching { db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
            } finally {
                pruneScheduled.set(false)
            }
        }
    }

    fun fileBytes(): Long {
        val ctxHelper = helper ?: return 0
        var total = 0L
        // -shm 只是 WAL 的共享内存索引，固定 32KB 且会自建，算进占用会误导用户
        listOf("", "-wal").forEach { suffix ->
            total += runCatching { File(ctxHelper.databasePath() + suffix).length() }.getOrDefault(0L)
        }
        return total
    }

    private fun severityAtLeast(minLevel: Char): List<String> = when (minLevel) {
        'E' -> listOf("E")
        'W' -> listOf("W", "E")
        else -> listOf("I", "W", "E")
    }

    private fun readable(): SQLiteDatabase? =
        runCatching { helper?.readableDatabase }.getOrNull()

    private fun writable(): SQLiteDatabase? =
        runCatching { helper?.writableDatabase }.getOrNull()

    private class Helper(private val appContext: Context) :
        SQLiteOpenHelper(appContext, DB_NAME, null, 1) {
        fun databasePath(): String = appContext.getDatabasePath(DB_NAME).absolutePath

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $PACKAGE_NAME (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_AT INTEGER NOT NULL, " +
                    "$COL_LEVEL TEXT NOT NULL, " +
                    "$COL_TAG TEXT NOT NULL, " +
                    "$COL_MESSAGE TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_logs_at ON $PACKAGE_NAME($COL_AT DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 1) onCreate(db)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // 日志写入频率比读高，WAL 下读写不互相阻塞
            runCatching { db.enableWriteAheadLogging() }
        }
    }
}
