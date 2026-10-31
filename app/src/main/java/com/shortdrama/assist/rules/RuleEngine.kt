package com.shortdrama.assist.rules

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.shortdrama.assist.BuildConfig
import com.shortdrama.assist.accessibility.DramaAction
import com.shortdrama.assist.accessibility.DramaActionType
import com.shortdrama.assist.accessibility.DramaRules

/**
 * 声明式规则引擎：把订阅到的 [RuleSpec] 变成一次具体动作。
 *
 * 设计约束（见 docs/rules-schema.md §6）：
 *  - 规则只能描述「看到什么、做什么」，护栏一律留在引擎里，规则包改不动；
 *  - 引擎判断不出来时返回 null，由调用方回退到内置的 [DramaRules]；
 *  - 只读缓存，不发网络请求（拉取由 RuleStore 负责）。
 */
object RuleEngine {

    private const val TAG = "SDRuleEngine"

    /** 同一订阅两次动作之间的最小间隔。 */
    private const val MIN_GAP_MS = 1_500L

    /** 同一订阅每分钟最多动作次数，超出自动停到下一分钟。 */
    private const val MAX_PER_MINUTE = 30

    /** 编译结果缓存时长；[invalidate] 会立刻失效。 */
    private const val CACHE_TTL_MS = 60_000L

    private data class Compiled(
        val subId: String,
        val packageName: String,
        val spec: RuleSpec,
    )

    private var generation = 0
    private var loadedGeneration = -1
    private var loadedAt = 0L
    private var compiled: List<Compiled> = emptyList()

    private val lastActionAt = HashMap<String, Long>()
    private val windowStart = HashMap<String, Long>()
    private val windowCount = HashMap<String, Int>()
    private var lastThrottleLogAt = 0L

    /** ruleId → (首帧文案, 首次见到的时间)，用于 confirmMs 持久确认。 */
    private val confirmSince = HashMap<String, Pair<String, Long>>()

    /** 订阅被增删改或重新拉取后调用，下一次匹配重新编译。 */
    fun invalidate() {
        generation++
    }

    /**
     * 用订阅规则匹配一次动作。返回 null 表示「规则包没命中」或「被护栏拦下」，
     * 调用方应当继续用内置规则兜底。
     */
    fun match(
        ctx: Context,
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        frameW: Int,
        frameH: Int,
        fgPkg: String,
    ): DramaAction? {
        if (frameW <= 0 || frameH <= 0) return null
        val all = compiledRules(ctx)
        if (all.isEmpty()) return null

        val landscape = frameW > frameH
        val hasControls = DramaRules.hasPlaybackControls(pageText)
        val countdown = DramaRules.remainingCountdownSeconds(pageText)
        val listing = DramaRules.isListingPage(pageText)

        for (rule in all) {
            if (rule.packageName != fgPkg && rule.packageName != "*") continue
            val spec = rule.spec

            // 护栏 1：列表页（首页/详情/剧场）不触发任何动作，避免误点导航
            if (listing) continue

            // 护栏 2：正剧保护。只有 immediate 的规则（片尾）能越过它
            if (hasControls && !spec.immediate) continue

            // 护栏 3：倒计时窗口内不滑动（点击「跳过」仍然允许）
            if (countdown != null && spec.action.startsWith("swipe")) continue

            // 护栏 4：横屏只允许点击跳过按钮，不做任何滑动
            if (landscape && spec.action.startsWith("swipe")) continue

            val hit = evaluate(spec, nodes, pageText, frameW, frameH) ?: continue

            // 护栏 5：持久确认。同一段文案必须连续存在够 confirmMs 才算数，
            // 用来吃掉「广告→正剧」那种只闪 400ms 的过渡帧。
            // 稀疏树除外：检测器那边已经有一份 800ms 确认，重复计时会变 1.6s。
            if (spec.confirmMs > 0 && spec.id != "sparse-fallback" &&
                !confirmHeld(spec.id, pageText, spec.confirmMs)
            ) {
                return null
            }

            // 护栏 6：限流。只挡「真正要动手」的那一个，不挡扫描与判据。
            if (!takeToken(rule.subId)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastThrottleLogAt >= 2_000L) {
                    lastThrottleLogAt = now
                    Log.d(TAG, "订阅 ${rule.subId} 触发限流，暂停 ${MIN_GAP_MS}ms")
                }
                return null
            }

            val reason = reasonOf(spec)
            Log.i(TAG, "规则命中: ${spec.id} → ${spec.action} ($reason)")
            return DramaAction(
                type = hit.first,
                node = hit.second,
                reason = reason,
                immediate = spec.immediate,
            )
        }
        return null
    }

    /** 规则 id → 动作原因文案。片尾与稀疏树沿用内置常量，让检测器走同一套补扫逻辑。 */
    private fun reasonOf(spec: RuleSpec): String = when (spec.id) {
        "end-card-continue" -> DramaRules.REASON_END_CARD
        "sparse-fallback" -> DramaRules.REASON_SPARSE_AD
        "live-ad-render-view" -> DramaRules.REASON_LIVE_AD
        "playable-ad-texture" -> DramaRules.REASON_TEXTURE_AD
        "ad-container" -> DramaRules.REASON_AD_CONTAINER
        "ad-badge" -> DramaRules.REASON_AD_BADGE
        else -> spec.note.ifBlank { spec.id }
    }

    private fun evaluate(
        spec: RuleSpec,
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        frameW: Int,
        frameH: Int,
    ): Pair<DramaActionType, AccessibilityNodeInfo?>? {
        val action = actionTypeOf(spec.action) ?: return null
        val matched = when (spec.type) {
            "pageText" -> matchText(spec, pageText)
            "viewId" -> matchViewId(spec, nodes)
            "className" -> matchClassName(spec, nodes, pageText)
            "sparse" -> matchSparse(spec, nodes, pageText)
            "adBadge" -> matchAdBadge(spec, nodes, frameW, frameH)
            "clickText" -> return clickTarget(spec, nodes, frameW, frameH)
                ?.let { action to it }
            else -> false
        }
        if (!matched) return null
        // 滑动手势不需要节点；点击类已经在上面返回
        return action to null
    }

    private fun matchText(spec: RuleSpec, pageText: String): Boolean {
        val anyHit = spec.any.any { it.isNotBlank() && pageText.contains(it) }
        if (anyHit && spec.any.isNotEmpty()) return true
        val regexHit = spec.regex.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(pageText) }.getOrDefault(false)
        }
        if (regexHit && spec.regex.isNotEmpty()) return true
        if (spec.contains.isEmpty() && spec.any.isEmpty() && spec.regex.isEmpty()) return false
        return spec.contains.all { pageText.contains(it) } && spec.contains.isNotEmpty()
    }

    private fun matchViewId(spec: RuleSpec, nodes: List<AccessibilityNodeInfo>): Boolean {
        if (spec.any.isEmpty()) return false
        return nodes.any { node ->
            val id = node.viewIdResourceName ?: return@any false
            spec.any.any { it.isNotBlank() && id.endsWith(it) }
        }
    }

    private fun matchClassName(
        spec: RuleSpec,
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
    ): Boolean {
        if (spec.any.isEmpty()) return false
        // 文案太长说明是内容页（正剧播放器也是 TextureView），不当广告处理
        if (spec.maxPageTextLen >= 0 && pageText.length > spec.maxPageTextLen) return false
        return nodes.any { node ->
            val cls = node.className?.toString() ?: return@any false
            spec.any.any { it.isNotBlank() && cls.contains(it) }
        }
    }

    private fun matchSparse(
        spec: RuleSpec,
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
    ): Boolean {
        if (spec.maxNodes >= 0 && nodes.size > spec.maxNodes) return false
        if (spec.maxPageTextLen >= 0 && pageText.length > spec.maxPageTextLen) return false
        if (DramaRules.hasPlaybackControls(pageText)) return false
        return true
    }

    private fun matchAdBadge(
        spec: RuleSpec,
        nodes: List<AccessibilityNodeInfo>,
        frameW: Int,
        frameH: Int,
    ): Boolean {
        val rect = android.graphics.Rect()
        return nodes.any { node ->
            val text = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
            if (!text.contains("广告")) return@any false
            if (spec.region != "topRight") return@any true
            node.getBoundsInScreen(rect)
            rect.right > frameW * 0.6f && rect.top < frameH * 0.3f
        }
    }

    /** 找到可点的「跳过 / 关闭」类小按钮；尺寸超限或整屏容器一律拒绝。 */
    private fun clickTarget(
        spec: RuleSpec,
        nodes: List<AccessibilityNodeInfo>,
        frameW: Int,
        frameH: Int,
    ): AccessibilityNodeInfo? {
        if (spec.any.isEmpty()) return null
        val maxW = if (spec.maxWidthRatio > 0f) frameW * spec.maxWidthRatio else frameW.toFloat()
        val maxH = if (spec.maxHeightRatio > 0f) frameH * spec.maxHeightRatio else frameH.toFloat()
        val rect = android.graphics.Rect()
        for (node in nodes) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (!spec.any.any { it.isNotBlank() && (text.contains(it) || desc.contains(it)) }) continue
            if (DramaRules.isOversizedClickTarget(node, frameW, frameH)) continue
            node.getBoundsInScreen(rect)
            if (rect.width() > maxW || rect.height() > maxH) continue
            val target = clickableSelfOrAncestor(node, frameW, frameH) ?: continue
            return target
        }
        return null
    }

    private fun clickableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        frameW: Int,
        frameH: Int,
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 3) {
            if (cur.isClickable && cur.isEnabled &&
                !DramaRules.isOversizedClickTarget(cur, frameW, frameH)
            ) {
                return cur
            }
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun actionTypeOf(action: String): DramaActionType? = when (action) {
        "swipeUp" -> DramaActionType.SWIPE_UP
        "swipeDown" -> DramaActionType.SWIPE_DOWN
        "click" -> DramaActionType.CLICK
        "back" -> DramaActionType.BACK
        else -> null // wait / none 不是动作
    }

    // ------------------------------------------------------------ 限流

    private fun takeToken(subId: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastActionAt[subId] ?: 0L
        if (now - last < MIN_GAP_MS) return false
        val start = windowStart[subId] ?: now
        if (now - start >= 60_000L) {
            windowStart[subId] = now
            windowCount[subId] = 0
        }
        val count = (windowCount[subId] ?: 0) + 1
        if (count > MAX_PER_MINUTE) return false
        windowCount[subId] = count
        lastActionAt[subId] = now
        return true
    }

    private fun releaseToken(subId: String) {
        lastActionAt[subId] = 0L
        val count = windowCount[subId] ?: return
        if (count > 0) windowCount[subId] = count - 1
    }

    /** 同一段文案连续保持够时长才算命中；文案一变就重新计时。 */
    private fun confirmHeld(ruleId: String, pageText: String, confirmMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prev = confirmSince[ruleId]
        if (prev == null || prev.first != pageText) {
            confirmSince[ruleId] = pageText to now
            return false
        }
        if (now - prev.second >= confirmMs) {
            confirmSince.remove(ruleId)
            return true
        }
        return false
    }

    // ------------------------------------------------------- 规则编译

    @Synchronized
    private fun compiledRules(ctx: Context): List<Compiled> {
        val now = SystemClock.elapsedRealtime()
        if (loadedGeneration == generation && now - loadedAt < CACHE_TTL_MS) return compiled

        val out = ArrayList<Compiled>()
        runCatching {
            for (sub in SubscriptionStore.load(ctx)) {
                if (!sub.enabled) continue
                // 缓存优先；官方源还没拉过时用打包进 APK 的那份，保证首启可用
                val pkg = RuleStore.cached(ctx, sub.id)
                    ?: (if (sub.id == RuleStore.OFFICIAL_ID) RuleStore.builtIn(ctx) else null)
                    ?: continue
                if (!versionAtLeast(pkg.minAppVersion)) continue
                pkg.rules.forEach { out.add(Compiled(sub.id, pkg.packageName, it)) }
            }
        }
        compiled = out
        loadedGeneration = generation
        loadedAt = now
        if (out.isNotEmpty()) Log.d(TAG, "规则已编译: ${out.size} 条")
        return out
    }

    /** 「1.4.0」这类版本号比较；缺省视为满足。 */
    private fun versionAtLeast(required: String?): Boolean {
        if (required.isNullOrBlank()) return true
        val cur = BuildConfig.VERSION_NAME.split('.').mapNotNull { it.toIntOrNull() }
        val need = required.split('.').mapNotNull { it.toIntOrNull() }
        for (i in need.indices) {
            val a = cur.getOrElse(i) { 0 }
            val b = need[i]
            if (a != b) return a > b
        }
        return true
    }
}
