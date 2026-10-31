package com.shortdrama.assist.ocr

/**
 * OCR 的一行文字及其在**送给识别器的位图**里的坐标。
 *
 * 注意：坐标不是屏幕坐标。服务会把缩略图按比例放大回屏幕后再点击，
 * 这样不同分辨率/DPI 都不需要单独写死坐标。
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class OcrSnapshot(
    val lines: List<OcrLine>,
    val fullText: String,
) {
    companion object {
        val EMPTY = OcrSnapshot(emptyList(), "")
    }
}

/**
 * 一次判定只允许产生一个动作。未知页面必须是 [None]，绝不允许“猜着滑”。
 */
sealed interface OcrAction {
    data object None : OcrAction
    data class Swipe(val reason: String) : OcrAction
}

data class OcrDecision(
    val action: OcrAction,
    val note: String,
    /** 命中的关键文案，用于标准化日志；未知/空页面为 null。 */
    val keyword: String? = null,
)

/**
 * 纯逻辑判定器：不依赖 Android，可以用 JVM 单测覆盖。
 *
 * 只认两种页面文案：
 *  - 横屏全屏广告结束页：`上滑继续观看剧集`
 *  - 竖屏广告结束页：`上滑继续观看短剧`
 *
 * 其它任何文案都不动作，避免误触正片、广告详情、验证页。
 */
class OcrDecider(
    private val cooldownMs: Long = 3_000L,
) {

    fun decide(snapshot: OcrSnapshot, now: Long, lastActionAt: Long): OcrDecision {
        // OCR 有时会在汉字之间插空格；判定前统一去掉空白，避免“上滑继续 观看”漏判。
        val text = snapshot.fullText.replace(WHITESPACE, "")
        if (text.isBlank()) {
            return OcrDecision(OcrAction.None, "empty-ocr")
        }

        val endKeyword = END_WORDS.firstOrNull { text.contains(it) }
        if (endKeyword != null) {
            return if (now - lastActionAt < cooldownMs) {
                OcrDecision(OcrAction.None, "cooldown", endKeyword)
            } else {
                OcrDecision(OcrAction.Swipe("end-card"), "end-card", endKeyword)
            }
        }

        return OcrDecision(OcrAction.None, "unknown")
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val END_WORDS = listOf(
            "上滑继续观看剧集",
            "上滑继续观看短剧",
        )
    }
}
