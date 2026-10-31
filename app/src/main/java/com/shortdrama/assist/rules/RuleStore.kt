package com.shortdrama.assist.rules

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 规则包的拉取、解析与磁盘缓存。
 *
 * 这一层的铁律：**任何失败都不抛给调用方**。拉不到就用上一次的缓存，
 * 连缓存都没有就返回空规则 —— 最坏情况是「不自动操作」，不是「App 崩了」。
 * 因为要跑网络，[fetch] 是同步阻塞的，后台线程由调用方起（项目没有 coroutines 依赖）。
 */
object RuleStore {

    /** 一个规则包只服务一个 App，所以 id 直接用包名，文件名也一样（rules/<包名>.json）。 */
    const val OFFICIAL_ID = "com.phoenix.read"
    const val OFFICIAL_URL_PRIMARY =
        "https://cdn.jsdelivr.net/gh/20f11c/ShortDramaContPlay@main/rules/com.phoenix.read.json"
    const val OFFICIAL_URL_FALLBACK =
        "https://raw.githubusercontent.com/20f11c/ShortDramaContPlay/main/rules/com.phoenix.read.json"

    private const val CACHE_DIR = "rules"
    private const val BUILT_IN_PATH = "rules/com.phoenix.read.json"
    private const val META_SUFFIX = ".meta"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_RULES = 200
    private const val TIMEOUT_MS = 10_000
    private const val DEFAULT_COOLDOWN_MS = 1500L

    private val TYPES = setOf("pageText", "viewId", "className", "clickText", "sparse", "adBadge")
    private val ACTIONS = setOf("swipeUp", "swipeDown", "click", "back", "wait", "none")
    private val REGIONS = setOf("any", "topRight", "topLeft")

    // ------------------------------------------------------------- public

    /**
     * 解析一段规则包 JSON。外部（UI、内置资产）需要自己拿到原始文本时用这个。
     * 解析失败返回 null，不抛异常。
     */
    fun parse(raw: String): RulePackage? = parsePackage(raw)

    /**
     * 打包进 APK 的内置规则（`assets/rules/com.phoenix.read.json`）。
     *
     * 存在的意义是「第一次装上、还没联网」时也能立刻跑起来：没有它，
     * 首启拉不到规则就是完全静止，用户只会觉得这 App 坏了。
     */
    fun builtIn(ctx: Context): RulePackage? = runCatching {
        val raw = ctx.assets.open(BUILT_IN_PATH).bufferedReader().use { it.readText() }
        parsePackage(raw)
    }.getOrNull()

    /**
     * 读磁盘缓存。文件不存在、内容坏了、schema 不认识，一律返回 null。
     *
     * [RulePackage.fetchedAt] 由同目录的 `.meta` 补齐：原始文本保持可读，
     * 时间戳单独存，避免为了加个字段去重写整份 JSON。
     */
    fun cached(ctx: Context, subId: String): RulePackage? = runCatching {
        val file = cacheFile(ctx, subId)
        if (!file.isFile) {
            null
        } else {
            parsePackage(file.readText())?.copy(fetchedAt = readFetchedAt(ctx, subId))
        }
    }.getOrNull()

    /**
     * 拉取并解析，成功时写入缓存。
     *
     * 失败用 [Result] 表达而不是异常，调用方据此更新 `lastError` 并继续用旧缓存。
     */
    fun fetch(ctx: Context, sub: RuleSubscription): Result<RulePackage> = runCatching {
        val raw = downloadWithFallback(sub.url)
        val parsed = parsePackage(raw)
            ?: throw IOException("规则包不合法：schemaVersion、必填字段或规则数超限")
        val now = System.currentTimeMillis()
        // 缓存写失败不影响这次结果：数据已经在手上，下次拉不到顶多重下一次。
        runCatching { writeCache(ctx, sub.id, raw, now) }
        parsed.copy(fetchedAt = now)
    }

    /**
     * 当前生效的规则：按订阅顺序拼接，未启用或没有缓存的订阅直接跳过。
     *
     * 顺序有意义 —— 引擎按数组顺序求值、第一条命中即停，所以订阅列表的先后
     * 就是规则优先级。
     */
    fun activeRules(ctx: Context, subs: List<RuleSubscription>): List<RuleSpec> = runCatching {
        val out = ArrayList<RuleSpec>()
        for (sub in subs) {
            if (!sub.enabled) continue
            val pkg = cached(ctx, sub.id) ?: continue
            out.addAll(pkg.rules)
        }
        out
    }.getOrDefault(emptyList())

    fun clearCache(ctx: Context, subId: String) {
        // delete() 对不存在的文件返回 false，本来就不需要额外判断。
        runCatching { cacheFile(ctx, subId).delete() }
        runCatching { metaFile(ctx, subId).delete() }
    }

    // ---------------------------------------------------------- transport

    private fun downloadWithFallback(url: String): String {
        val first = runCatching { download(url) }
        first.getOrNull()?.let { return it }
        // 只有「原样的官方主地址」才回退 raw。用户把地址改过（改名、换源、加参数）之后
        // 还回退到官方文件，会出现「地址写着 A、数据其实来自 B」的假成功，必须让它如实报错。
        if (url != OFFICIAL_URL_PRIMARY) {
            val cause = first.exceptionOrNull()?.message ?: "未知错误"
            throw IOException("下载失败：$cause")
        }
        // jsDelivr 在部分网络下会 404 / 被限流；raw 是同一份文件的镜像，值得再试一次。
        // 内容相同，所以解析失败不回退 —— 换个地址下载同样的字节没有意义。
        return download(OFFICIAL_URL_FALLBACK)
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("非 HTTP(S) 地址")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        // 规则包只有几 KB，不压缩省掉解压逻辑，也避开个别 CDN 无视 Accept-Encoding 回 gzip。
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // 声明了长度就先拒收，省得为超大响应白下载 256KB 以上。
            if (conn.contentLengthLong > MAX_BYTES) throw IOException("规则包超过 256KB")
            return String(readLimited(conn.inputStream), Charsets.UTF_8)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 分块读并计数：chunked 响应没有 Content-Length，只能边读边拦。 */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("规则包超过 256KB")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------- cache

    private fun cacheDir(ctx: Context): File = File(ctx.filesDir, CACHE_DIR)

    private fun cacheFile(ctx: Context, subId: String): File =
        File(cacheDir(ctx), safeName(subId) + ".json")

    private fun metaFile(ctx: Context, subId: String): File =
        File(cacheDir(ctx), safeName(subId) + META_SUFFIX)

    /**
     * 订阅 id 拼进文件名前先洗一遍。
     *
     * id 可能来自导入的订阅文件，带 `../` 会写到缓存目录外面 —— 只保留安全字符最省心。
     */
    private fun safeName(id: String): String {
        val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return if (cleaned.isEmpty()) "_" else cleaned.take(64)
    }

    private fun writeCache(ctx: Context, subId: String, raw: String, at: Long) {
        val dir = cacheDir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("缓存目录不可写")
        cacheFile(ctx, subId).writeText(raw)
        metaFile(ctx, subId).writeText(at.toString())
    }

    private fun readFetchedAt(ctx: Context, subId: String): Long = runCatching {
        val file = metaFile(ctx, subId)
        if (!file.isFile) 0L else file.readText().trim().toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------ parsing

    /**
     * 解析整包。顶层任何一项不合规都返回 null（整包拒收），
     * 单条规则不合规只丢那条并记 warning。
     */
    private fun parsePackage(raw: String): RulePackage? {
        // 静态托管常给 UTF-8 BOM，org.json 会把它当成非法字符。
        val root = runCatching { JSONObject(raw.removePrefix("\uFEFF")) }.getOrNull() ?: return null

        // 不认识的 schemaVersion 一律拒收：新格式可能有本版 App 理解不了的语义。
        if (root.optInt("schemaVersion", -1) != 1) return null
        val name = root.textOrNull("name") ?: return null
        val packageName = root.textOrNull("package") ?: return null
        val version = root.textOrNull("version") ?: return null
        val rules = root.optJSONArray("rules") ?: return null
        if (rules.length() > MAX_RULES) return null

        val warnings = ArrayList<String>()
        val specs = ArrayList<RuleSpec>(rules.length())
        for (i in 0 until rules.length()) {
            val obj = rules.optJSONObject(i)
            if (obj == null) {
                warnings += "rules[$i]: 不是对象，已丢弃"
                continue
            }
            parseRule(obj, warnings)?.let { specs += it }
        }

        return RulePackage(
            schemaVersion = 1,
            name = name,
            packageName = packageName,
            version = version,
            minAppVersion = root.textOrNull("minAppVersion"),
            rules = specs,
            // 签名只作来源标识：规则源对第三方开放，真正的信任是「用户信任他填的 URL」，
            // 所以这里不做密码学校验，有 signature 字段就认为它标了来源。
            signed = !root.isNull("signature"),
            warnings = warnings,
            fetchedAt = 0L,
        )
    }

    /** 解析单条规则，非法返回 null 并把原因（含 id）追加到 [warnings]。 */
    private fun parseRule(obj: JSONObject, warnings: MutableList<String>): RuleSpec? {
        val id = obj.textOrNull("id")
        if (id == null) {
            warnings += "存在缺少 id 的规则，已丢弃"
            return null
        }
        val type = obj.textOrNull("type")
        if (type == null || type !in TYPES) {
            warnings += "$id: type「${type ?: "缺失"}」不支持，已丢弃"
            return null
        }
        val action = obj.textOrNull("action")
        if (action == null || action !in ACTIONS) {
            warnings += "$id: action「${action ?: "缺失"}」不支持，已丢弃"
            return null
        }
        // clickText 的语义就是「点这个节点」，配别的动作没有意义，宁可丢弃也不要替用户猜。
        if (type == "clickText" && action != "click") {
            warnings += "$id: clickText 只能配 click，已丢弃"
            return null
        }

        val anchor = obj.optJSONObject("anchor")
        if (anchor == null) {
            warnings += "$id: 缺少 anchor，已丢弃"
            return null
        }
        // 兜底规则没有阈值就等于「任何页面都像广告」，比不生效危险得多。
        if (type == "sparse" && (!anchor.has("maxNodes") || !anchor.has("maxPageTextLen"))) {
            warnings += "$id: sparse 需要 maxNodes 与 maxPageTextLen，已丢弃"
            return null
        }

        val regexes = anchor.textList("regex")
        // 这里先试编译：坏正则会静默地让规则永不命中，不如丢掉并告诉用户是哪条。
        for (r in regexes) {
            if (runCatching { Regex(r) }.isFailure) {
                warnings += "$id: 正则「$r」编译失败，整条已丢弃"
                return null
            }
        }

        val region = anchor.textOrNull("region") ?: "any"
        val regionOk = region in REGIONS
        if (!regionOk) warnings += "$id: region「$region」不支持，按 any 处理"

        return RuleSpec(
            id = id,
            type = type,
            action = action,
            any = anchor.textList("any"),
            regex = regexes,
            contains = anchor.textList("contains"),
            maxPageTextLen = anchor.optInt("maxPageTextLen", -1),
            maxNodes = anchor.optInt("maxNodes", -1),
            maxWidthRatio = anchor.optDouble("maxWidthRatio", -1.0).toFloat(),
            maxHeightRatio = anchor.optDouble("maxHeightRatio", -1.0).toFloat(),
            region = if (regionOk) region else "any",
            immediate = obj.optBoolean("immediate", false),
            // 负的冷却/确认时长没有语义，夹到 0，免得引擎那边再判一次。
            cooldownMs = obj.optLong("cooldownMs", DEFAULT_COOLDOWN_MS).coerceAtLeast(0L),
            confirmMs = obj.optLong("confirmMs", 0L).coerceAtLeast(0L),
            note = obj.textOrNull("note") ?: "",
        )
    }

    /** 只接受字符串：数字/对象一律当作缺失，避免 `1.0` / `"1.0"` 之类的歧义。 */
    private fun JSONObject.textOrNull(key: String): String? {
        val v = opt(key) as? String
        return if (v.isNullOrBlank()) null else v.trim()
    }

    /** 字符串数组；非字符串元素静默跳过（数组本身坏了不算整条规则坏）。 */
    private fun JSONObject.textList(key: String): List<String> {
        val arr: JSONArray = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i) as? String
            if (!v.isNullOrBlank()) out += v.trim()
        }
        return out
    }
}
