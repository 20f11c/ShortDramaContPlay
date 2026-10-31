package com.shortdrama.assist.rules

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 触发次数统计（最近 7 天）。
 *
 * 存储形状（同一个 SharedPreferences，键都是扁平的）：
 * - `d:<yyyy-MM-dd>:<pkg>` → 当天次数（Int）
 * - `l:<pkg>` → 包名对应的显示名
 * - `last` → 最近一次触发的毫秒时间戳
 *
 * 为什么按「日期 + 包名」散着存：只要读一次 `all` 就能算出今日总数和周内排行，
 * 不必维护嵌套结构，也就不存在跨天时把整个对象的日期挪一遍的问题。
 */
object StatsStore {

    private const val PREFS = "sd_stats"
    private const val KEY_LAST = "last"
    private const val DAY_PREFIX = "d:"
    private const val LABEL_PREFIX = "l:"
    private const val WINDOW_DAYS = 7

    data class Row(val packageName: String, val label: String, val count: Int)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记一次触发。静默失败：统计丢一条也比让无障碍服务崩掉好。 */
    fun bump(ctx: Context, packageName: String, label: String) {
        runCatching {
            val p = prefs(ctx)
            val day = dayKey(System.currentTimeMillis())
            val key = DAY_PREFIX + day + ":" + packageName
            val next = p.getInt(key, 0) + 1
            val editor = p.edit()
                .putInt(key, next)
                .putLong(KEY_LAST, System.currentTimeMillis())
            if (label.isNotBlank()) editor.putString(LABEL_PREFIX + packageName, label)
            editor.apply()
            prune(p)
        }
    }

    /** 最近 7 天（含今天），按次数降序；同名次数相同时按包名排序，避免每次刷新顺序抖动。 */
    fun rows(ctx: Context): List<Row> = runCatching {
        val p = prefs(ctx)
        val valid = recentDayKeys()
        val counts = HashMap<String, Int>()
        for ((key, value) in p.all) {
            if (!key.startsWith(DAY_PREFIX)) continue
            val rest = key.substring(DAY_PREFIX.length)
            val day = rest.substringBefore(':')
            if (day !in valid) continue
            val pkg = rest.substringAfter(':', missingDelimiterValue = "")
            if (pkg.isEmpty()) continue
            counts[pkg] = (counts[pkg] ?: 0) + ((value as? Number)?.toInt() ?: 0)
        }
        counts.entries
            .map { (pkg, count) ->
                Row(pkg, p.getString(LABEL_PREFIX + pkg, null) ?: pkg, count)
            }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.packageName })
    }.getOrDefault(emptyList())

    fun totalToday(ctx: Context): Int = runCatching {
        val prefix = DAY_PREFIX + dayKey(System.currentTimeMillis()) + ":"
        prefs(ctx).all.entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { (it.value as? Number)?.toInt() ?: 0 }
    }.getOrDefault(0)

    fun lastAt(ctx: Context): Long = runCatching {
        prefs(ctx).getLong(KEY_LAST, 0L)
    }.getOrDefault(0L)

    // -------------------------------------------------------------- 时间键

    /**
     * 最近 7 天的日期键集合。
     *
     * 用 Calendar 逐天回退而不是「now - n*86400000」：夏令时地区按毫秒退会让某一天重复或缺失。
     */
    private fun recentDayKeys(): Set<String> {
        val cal = Calendar.getInstance()
        val set = HashSet<String>(WINDOW_DAYS)
        repeat(WINDOW_DAYS) {
            set += dayKey(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return set
    }

    /** 本地日期。SimpleDateFormat 不是线程安全的，每次新建（一天调几次，代价可忽略）。 */
    private fun dayKey(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    /**
     * 清掉过期的计数键。
     *
     * 不清的话 prefs 会随使用天数无限增长 —— 每周统计只读 7 天，
     * 更早的键除了占地方没有任何用处。
     */
    private fun prune(p: SharedPreferences) {
        val valid = recentDayKeys()
        val stale = p.all.keys.filter { key ->
            key.startsWith(DAY_PREFIX) &&
                key.removePrefix(DAY_PREFIX).substringBefore(':') !in valid
        }
        if (stale.isEmpty()) return
        val editor = p.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }
}
