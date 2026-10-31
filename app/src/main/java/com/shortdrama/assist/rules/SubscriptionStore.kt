package com.shortdrama.assist.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 订阅列表的持久化（SharedPreferences，一个 JSON 数组）。
 *
 * 为什么不用文件：订阅最多几条，改动频繁（开关、改名、删源），
 * SharedPreferences 的原子写入足够，也省掉自己处理半截文件的问题。
 */
object SubscriptionStore {

    private const val PREFS = "sd_rules"
    private const val KEY_SUBS = "subscriptions"
    private const val KEY_SEEDED = "seeded"

    /** 早期版本官方源的 id；只在读取旧数据时用于迁移。 */
    private const val LEGACY_OFFICIAL_ID = "official"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取订阅列表。存储损坏时退回「只有官方源」，用户至少还有一份可用的规则。 */
    fun load(ctx: Context): List<RuleSubscription> = runCatching {
        val raw = prefs(ctx).getString(KEY_SUBS, null) ?: return@runCatching listOf(defaults())
        val arr = JSONArray(raw)
        val list = ArrayList<RuleSubscription>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { obj -> toSubscription(obj)?.let { list += it } }
        }
        // 老版本官方源 id 是 "official"，升级后统一成包名，否则同一条规则会变成两条。
        val migrated = list.map {
            if (it.id == LEGACY_OFFICIAL_ID) {
                it.copy(
                    id = RuleStore.OFFICIAL_ID,
                    url = RuleStore.OFFICIAL_URL_PRIMARY,
                    name = "红果免费短剧",
                )
            } else {
                it
            }
        }
        // 数组里有东西但一条都解不出来 = 数据被写坏了，同样退回默认值。
        if (migrated.isEmpty() && arr.length() > 0) listOf(defaults()) else migrated
    }.getOrElse { listOf(defaults()) }

    fun save(ctx: Context, list: List<RuleSubscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        runCatching {
            prefs(ctx).edit().putString(KEY_SUBS, arr.toString()).apply()
        }
    }

    /** 官方源。id 用包名，与规则文件名一致；UI 靠它认「这条是内置的」。 */
    fun defaults(): RuleSubscription = RuleSubscription(
        id = RuleStore.OFFICIAL_ID,
        url = RuleStore.OFFICIAL_URL_PRIMARY,
        name = "红果免费短剧",
        enabled = true,
        autoUpdate = true,
    )

    /**
     * 首次启动写入官方源。
     *
     * 用单独的 `seeded` 标记而不是「列表为空」，因为用户删光订阅是一个合法状态，
     * 不能每次启动都把它塞回来。
     */
    fun ensureSeeded(ctx: Context): List<RuleSubscription> {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_SEEDED, false)) return load(ctx)
        val seeded = listOf(defaults())
        save(ctx, seeded)
        runCatching { p.edit().putBoolean(KEY_SEEDED, true).apply() }
        return seeded
    }

    /** 按 id 覆盖或追加，保持原有顺序（顺序就是规则优先级）。 */
    fun upsert(ctx: Context, sub: RuleSubscription): List<RuleSubscription> {
        val list = load(ctx)
        val index = list.indexOfFirst { it.id == sub.id }
        val next = if (index >= 0) {
            list.toMutableList().also { it[index] = sub }
        } else {
            list + sub
        }
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, id: String): List<RuleSubscription> {
        val next = load(ctx).filterNot { it.id == id }
        save(ctx, next)
        return next
    }

    // --------------------------------------------------------------- json

    /**
     * 逐字段写出。null 用 [JSONObject.NULL] 占位 —— 直接 put(key, null)
     * 在 org.json 里是「删除该键」，读回来分不清「没写过」和「写了空」。
     */
    private fun toJson(s: RuleSubscription): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("url", s.url)
        put("name", s.name)
        put("enabled", s.enabled)
        put("autoUpdate", s.autoUpdate)
        put("version", s.version ?: JSONObject.NULL)
        put("ruleCount", s.ruleCount)
        put("minAppVersion", s.minAppVersion ?: JSONObject.NULL)
        put("signed", s.signed)
        put("lastCheckedAt", s.lastCheckedAt)
        put("lastError", s.lastError ?: JSONObject.NULL)
    }

    private fun toSubscription(obj: JSONObject): RuleSubscription? {
        val id = obj.textOrNull("id") ?: return null
        val url = obj.textOrNull("url") ?: return null
        return RuleSubscription(
            id = id,
            url = url,
            // 名字只是展示用，丢了就用 id 兜底，不值得为此丢弃整条订阅。
            name = obj.textOrNull("name") ?: id,
            enabled = obj.optBoolean("enabled", true),
            autoUpdate = obj.optBoolean("autoUpdate", true),
            version = obj.textOrNull("version"),
            ruleCount = obj.optInt("ruleCount", 0),
            minAppVersion = obj.textOrNull("minAppVersion"),
            signed = obj.optBoolean("signed", false),
            lastCheckedAt = obj.optLong("lastCheckedAt", 0L),
            lastError = obj.textOrNull("lastError"),
        )
    }

    private fun JSONObject.textOrNull(key: String): String? {
        val v = opt(key) as? String
        return if (v.isNullOrBlank()) null else v
    }
}
