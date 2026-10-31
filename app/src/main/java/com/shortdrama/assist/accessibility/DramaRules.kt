package com.shortdrama.assist.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 检测规则引擎：按优先级匹配片尾提示和广告特征。
 *
 * 优先级（从高到低）：
 * 1. 片尾待续播 → 上滑续播（列表页不做，避免首页"继续观看"栏目误触发）
 * 2. 正剧保护：有播放控件/集数文案时，一律不做广告动作
 * 3. 直播广告渲染视图 → 上滑
 * 4. TextureView 广告 → 上滑
 * 5. 广告容器 ID → 上滑
 * 6. 立即领取 / 商城卡片 → 点击（过大的可点击区域一律拒绝）
 * 7. 广告角标 → 上滑
 * 8. 跳过/关闭按钮 → 点击
 * 9. 稀疏树兜底 → 上滑（由 Detector 再做 800ms 持久性确认）
 */
object DramaRules {

    const val HONGUO_PACKAGE = "com.phoenix.read"

    // ---- 动作原因（同时用于日志与 UI 展示）----
    const val REASON_END_CARD = "片尾待续播"
    const val REASON_LIVE_AD = "直播广告(渲染视图)"
    const val REASON_TEXTURE_AD = "试玩广告(TextureView)"
    const val REASON_AD_CONTAINER = "广告容器"
    const val REASON_AD_BADGE = "穿山甲广告(角标)"
    const val REASON_CLICK_RULE = "点击规则"
    const val REASON_SPARSE_AD = "疑似广告(稀疏树)"
    const val REASON_ORANGE_AD = "横屏广告(橙色按钮)"

    // ---- 广告渲染视图 ----
    const val AD_RENDER_VIEW_CLASS = "TextureView"
    private const val LIVE_AD_RENDER_VIEW_ID = "ttlive_player_render_view"

    /** 穿山甲广告容器的 viewId 尾巴（两个都出现才算） */
    private val AD_CONTAINER_IDS = setOf("ay0", "i0t")

    /** 片尾提示关键词 */
    private val END_CARD_KEYWORDS = listOf(
        "待续播", "下一集", "即将播放", "精彩继续",
        "下集预告", "更多好剧", "继续观看", "自动播放",
    )

    /**
     * 列表/首页特征词。
     *
     * 首页有"继续观看"栏目名、详情页有"下一集"按钮，这些都不是片尾。
     * 真正的片尾是全屏播放器上的浮层，不会同时出现这些列表词汇。
     */
    private val LISTING_KEYWORDS = listOf(
        "搜索", "首页", "推荐", "我的", "历史", "追剧", "频道", "榜单",
    )

    /** 正剧播放控件（出现即视为用户在正常看剧，禁止任何广告动作） */
    private val PLAYBACK_KEYWORDS = listOf(
        "倍速", "选集", "暂停", "播放", "进度条",
        "弹幕", "评论", "点赞", "收藏", "分享",
    )
    private val EPISODE_PATTERN = Regex("第.{1,3}集")

    /** 上滑锁 */
    private val SWIPE_LOCK_KEYWORDS = listOf("后可继续上滑", "秒后继续", "滑动解锁")

    /** 倒计时 */
    private val COUNTDOWN_PATTERN = Regex("""(\d+)\s*[sS秒]""")

    /** 跳过 / 关闭类按钮文案 */
    private val SKIP_KEYWORDS = listOf("跳过", "关闭", "知道了", "我知道了")

    /** 支持的包名 */
    fun supportedPackagesList(): List<String> = listOf(HONGUO_PACKAGE)

    // ---------------------------------------------------------------- match

    fun match(
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        frameW: Int,
        frameH: Int,
        fgPkg: String,
    ): DramaAction? {
        if (fgPkg != HONGUO_PACKAGE) return null

        // 横屏时播放器控件自动隐藏、读不到文字，正剧保护会失效，
        // 渲染视图类判据（SDK 残留视图）也会误命中 —— 所以横屏一律不启用。
        val landscape = frameW > frameH

        // === 1. 片尾待续播（最高优先级，但排除列表页）===
        if (hasEndCard(pageText) && !isListingPage(pageText)) {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_END_CARD, immediate = true)
        }

        // === 2. 正剧保护：有播放控件就当作用户在看剧 ===
        if (hasPlaybackControls(pageText)) return null

        // === 2.5 横屏：只允许点「跳过/关闭」，不做任何滑动 ===
        // 横屏播放器控件自动隐藏、读不到文字，正剧保护与各种广告判据都会失真，
        // 而横屏里"上滑"不是退出广告的手势。未知情况不动。
        if (landscape) {
            findSkipButton(nodes, frameW, frameH)?.let {
                return DramaAction(DramaActionType.CLICK, it, REASON_CLICK_RULE)
            }
            return null
        }

        // === 3. 广告渲染视图 ===
        if (hasAdRenderView(nodes)) {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_LIVE_AD, immediate = true)
        }

        // === 4. TextureView 广告 ===
        if (hasAdRenderSurface(nodes, pageText)) {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_TEXTURE_AD, immediate = true)
        }

        // === 5. 广告容器 ===
        if (hasAdItemContainer(nodes)) {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_AD_CONTAINER, immediate = true)
        }

        // === 6. 广告角标 ===
        findAdBadge(nodes, frameW, frameH)?.let {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_AD_BADGE)
        }

        // === 7. 跳过 / 关闭 ===
        findSkipButton(nodes, frameW, frameH)?.let {
            return DramaAction(DramaActionType.CLICK, it, REASON_CLICK_RULE)
        }

        // === 8. 稀疏树兜底 ===
        if (looksLikeSparseAdItem(nodes, pageText, frameW, frameH)) {
            return DramaAction(DramaActionType.SWIPE_UP, null, REASON_SPARSE_AD)
        }

        return null
    }

    // ------------------------------------------------------------- helpers

    /** 片尾提示 */
    fun hasEndCard(text: String): Boolean = END_CARD_KEYWORDS.any { text.contains(it) }

    /** 是否是首页/列表/详情之类的非播放页 */
    fun isListingPage(text: String): Boolean = LISTING_KEYWORDS.any { text.contains(it) }

    /** 正剧播放控件 */
    fun hasPlaybackControls(text: String): Boolean {
        if (PLAYBACK_KEYWORDS.any { text.contains(it) }) return true
        if (EPISODE_PATTERN.containsMatchIn(text)) return true
        return false
    }

    /** 倒计时 */
    fun hasCountdown(text: String): Boolean = remainingCountdownSeconds(text) != null

    /** 解析剩余倒计时秒数 */
    fun remainingCountdownSeconds(text: String): Int? =
        COUNTDOWN_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** 上滑锁（"3秒后可继续上滑"之类） */
    fun hasSwipeLock(text: String): Boolean = SWIPE_LOCK_KEYWORDS.any { text.contains(it) }

    /** "即将进入广告"前置提示 */
    fun hasUpcomingAd(text: String): Boolean =
        text.contains("即将播放广告") || text.contains("秒后进入广告")

    /** 直播类广告的渲染视图 */
    fun hasAdRenderView(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.any { it.viewIdResourceName?.contains(LIVE_AD_RENDER_VIEW_ID) == true }

    /**
     * TextureView 广告。
     *
     * 单纯"存在无文字的 TextureView"不够：正剧播放器本身也可能是 TextureView。
     * 因此额外要求整页几乎没有可读文案（真播放页至少有剧名），
     * 减少把正剧播放误判成试玩广告的概率。
     */
    fun hasAdRenderSurface(nodes: List<AccessibilityNodeInfo>, pageText: String): Boolean {
        if (pageText.length >= 24) return false
        return nodes.any {
            it.className?.toString()?.contains(AD_RENDER_VIEW_CLASS) == true &&
                it.text.isNullOrEmpty() &&
                it.contentDescription.isNullOrEmpty()
        }
    }

    /** 广告容器 ID 组合（穿山甲是两个 id 同时出现） */
    fun hasAdItemContainer(nodes: List<AccessibilityNodeInfo>): Boolean {
        val ids = nodes.mapNotNull { it.viewIdResourceName }.toSet()
        return AD_CONTAINER_IDS.all { id -> ids.any { it.endsWith(id) } }
    }

    /**
     * 面积过大的可点击节点一律不点。
     *
     * 这类节点通常是整页广告创意容器，点下去会进落地页或直播间，
     * 比"什么都不做"糟糕得多。
     */
    fun isOversizedClickTarget(
        node: AccessibilityNodeInfo,
        frameW: Int,
        frameH: Int,
    ): Boolean {
        if (frameW <= 0 || frameH <= 0) return false
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (_: Throwable) {
            return true
        }
        val area = r.width().toLong() * r.height().toLong()
        val screen = frameW.toLong() * frameH.toLong()
        return area * 10 >= screen * 6
    }

    /**
     * 找到可点击节点：先看自己，再向上找最多 3 层父节点。
     *
     * 文案往往挂在 TextView 上，而 clickable 在外层容器上，只判断自身会漏掉。
     */
    private fun clickableAncestor(
        node: AccessibilityNodeInfo?,
        frameW: Int,
        frameH: Int,
    ): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth <= 3) {
            if (cur.isClickable && cur.isEnabled && !isOversizedClickTarget(cur, frameW, frameH)) {
                return cur
            }
            cur = try {
                cur.parent
            } catch (_: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    /** 文案匹配到就尝试拿到可点击祖先 */
    private fun findClickable(
        nodes: List<AccessibilityNodeInfo>,
        frameW: Int,
        frameH: Int,
        vararg keywords: String,
    ): AccessibilityNodeInfo? {
        for (node in nodes) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (keywords.any { text.contains(it) || desc.contains(it) }) {
                clickableAncestor(node, frameW, frameH)?.let { return it }
            }
        }
        return null
    }

    /** 右上角的"广告"角标 */
    private fun findAdBadge(
        nodes: List<AccessibilityNodeInfo>,
        frameW: Int,
        frameH: Int,
    ): AccessibilityNodeInfo? {
        if (frameW <= 0 || frameH <= 0) return null
        for (node in nodes) {
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            if (!desc.contains("广告") && !text.contains("广告")) continue
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (_: Throwable) {
                continue
            }
            if (rect.right > frameW * 0.6 && rect.top < frameH * 0.3) return node
        }
        return null
    }

    /** 跳过 / 关闭按钮：只接受小面积可点击区域 */
    private fun findSkipButton(
        nodes: List<AccessibilityNodeInfo>,
        frameW: Int,
        frameH: Int,
    ): AccessibilityNodeInfo? {
        if (frameW <= 0 || frameH <= 0) return null
        // 用屏幕比例而不是写死像素，避免不同分辨率下判据失效
        val maxW = frameW * 0.28f
        val maxH = frameH * 0.10f
        for (node in nodes) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (!SKIP_KEYWORDS.any { text.contains(it) || desc.contains(it) }) continue
            val target = clickableAncestor(node, frameW, frameH) ?: continue
            val rect = Rect()
            try {
                target.getBoundsInScreen(rect)
            } catch (_: Throwable) {
                continue
            }
            if (rect.width() <= maxW && rect.height() <= maxH) return target
        }
        return null
    }

    /**
     * 稀疏树广告判定：节点少 + 文案少 + 无播放控件。
     *
     * 这条规则天然容易误判，所以它的调用方必须再做持久性确认
     * （见 [DramaDetector] 里的 SPARSE_AD_CONFIRM_MS）。
     */
    fun looksLikeSparseAdItem(
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        frameW: Int,
        frameH: Int,
    ): Boolean {
        if (nodes.size > 50) return false
        if (pageText.length > 20) return false
        if (hasPlaybackControls(pageText)) return false
        if (isListingPage(pageText)) return false
        if (frameW < 500 || frameH < 500) return false
        return true
    }

    /** 拼接整页文本 */
    fun pageTextOf(nodes: List<AccessibilityNodeInfo>): String {
        val sb = StringBuilder()
        for (node in nodes) {
            node.text?.let { sb.append(it).append(' ') }
            node.contentDescription?.let { sb.append(it).append(' ') }
        }
        return sb.toString()
    }
}

/** 动作类型 */
enum class DramaActionType {
    CLICK,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    BACK,
}

/** 匹配到的动作 */
data class DramaAction(
    val type: DramaActionType,
    val node: AccessibilityNodeInfo?,
    val reason: String,
    val immediate: Boolean = false,
)
