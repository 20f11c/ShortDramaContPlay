package com.shortdrama.assist.rules

/**
 * 规则数据层模型。
 *
 * 规则包是纯数据（见 docs/rules-schema.md）：这里只负责「拿进来、校验、展示」，
 * 不含任何匹配逻辑，也不允许出现可执行内容。
 */

/**
 * 一条订阅源，对应 UI 订阅列表的一行。
 *
 * 失败信息不回滚已有字段：`version` / `ruleCount` 表示**最近一次成功**的结果，
 * `lastError` 单独记录失败原因，这样「更新失败 · 沿用缓存」才有东西可展示。
 */
data class RuleSubscription(
    val id: String,
    /** 拉取地址。官方源的 CDN 地址在 [RuleStore] 里有 raw 回退，其余域名原样使用。 */
    val url: String,
    val name: String,
    val enabled: Boolean = true,
    /** 启动 / 手动刷新时是否顺带拉取。 */
    val autoUpdate: Boolean = true,
    /** 最近一次成功拉到的规则包版本，仅展示。 */
    val version: String? = null,
    val ruleCount: Int = 0,
    /** 规则包要求的最低 App 版本；高于当前版本时整包拒收（旧 App 不会被新规则带崩）。 */
    val minAppVersion: String? = null,
    /** 规则包里带 signature 字段即为 true，只作来源标识，不做密码学校验。 */
    val signed: Boolean = false,
    val lastCheckedAt: Long = 0L,
    /** 最近一次失败原因，成功后清空。UI 折行显示，不要塞长堆栈。 */
    val lastError: String? = null,
)

/**
 * 一条规则：在什么条件下、对什么节点、做什么动作。
 *
 * 字段默认值就是「没有声明」时的语义：负数表示不约束，[region] = "any" 表示不限区域。
 */
data class RuleSpec(
    val id: String,
    /** pageText | viewId | className | clickText | sparse | adBadge */
    val type: String,
    /** swipeUp | swipeDown | click | back | wait | none */
    val action: String,
    /** 文案 / 资源 ID / 类名的「包含任一」集合。 */
    val any: List<String> = emptyList(),
    /** 正则判据。这里放原始串，编译由引擎负责（便于缓存序列化与日志复现）。 */
    val regex: List<String> = emptyList(),
    /** 类名子串判据。 */
    val contains: List<String> = emptyList(),
    /** 整页文案长度上限，用来把「正剧播放器」和「几乎没有文案的广告页」区分开。 */
    val maxPageTextLen: Int = -1,
    /** 节点数上限，稀疏树兜底用。 */
    val maxNodes: Int = -1,
    /** 命中节点相对屏幕的尺寸上限，用来排除整页创意容器。 */
    val maxWidthRatio: Float = -1f,
    val maxHeightRatio: Float = -1f,
    /** any | topRight | topLeft */
    val region: String = "any",
    /** true = 不受动作冷却限制（片尾续播用）。 */
    val immediate: Boolean = false,
    val cooldownMs: Long = 1500L,
    /** 条件需持续成立多久才算命中，0 = 立即命中。 */
    val confirmMs: Long = 0L,
    val note: String = "",
)

/**
 * 解析后的规则包。
 *
 * [warnings] 记录被丢弃的规则（含 id）和降级项：包整体可用，但不完整。
 * 解析失败返回 null，调用方继续用旧缓存。
 */
data class RulePackage(
    val schemaVersion: Int,
    val name: String,
    val packageName: String,
    val version: String,
    val minAppVersion: String?,
    val rules: List<RuleSpec>,
    val signed: Boolean,
    val warnings: List<String> = emptyList(),
    /** 本地缓存写入时间；从磁盘读出来时由 `<subId>.meta` 补齐。 */
    val fetchedAt: Long = 0L,
)
