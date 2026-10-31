package com.shortdrama.assist.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDeciderTest {

    private val decider = OcrDecider(cooldownMs = 3_000L)

    private fun line(text: String, x: Int = 100, y: Int = 1_500) = OcrLine(
        text = text,
        left = x - 40,
        top = y - 20,
        right = x + 40,
        bottom = y + 20,
    )

    private fun snapshot(vararg texts: String): OcrSnapshot =
        OcrSnapshot(texts.map { line(it) }, texts.joinToString(" "))

    @Test
    fun `end card swipes up`() {
        val decision = decider.decide(
            snapshot("第24集", "上滑继续观看短剧", "选集"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("end-card", decision.note)
        assertTrue(decision.action is OcrAction.Swipe)
    }

    @Test
    fun `landscape end card swipes up`() {
        val decision = decider.decide(
            snapshot("广告", "上滑继续观看剧集"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("end-card", decision.note)
        assertEquals("上滑继续观看剧集", decision.keyword)
        assertTrue(decision.action is OcrAction.Swipe)
    }

    @Test
    fun `end card tolerates spaces inserted by ocr`() {
        val decision = decider.decide(
            snapshot("第24集", "上滑继续 观看短剧", "选集"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("end-card", decision.note)
        assertTrue(decision.action is OcrAction.Swipe)
    }

    @Test
    fun `playing page stays still`() {
        val decision = decider.decide(
            snapshot("第24集", "选集", "全屏观看", "倍速"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("unknown", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }

    @Test
    fun `unknown page stays still`() {
        val decision = decider.decide(
            snapshot("一骑踏皇城", "作者声明：内容由AI生成"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("unknown", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }

    @Test
    fun `ad without the two end keywords does nothing`() {
        val decision = decider.decide(
            snapshot("广告", "跳过 5s", "查看详情"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("unknown", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }

    @Test
    fun `end card exposes matched keyword`() {
        val decision = decider.decide(
            snapshot("第24集", "上滑继续观看短剧", "选集"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("上滑继续观看短剧", decision.keyword)
    }

    @Test
    fun `slider word does nothing`() {
        val decision = decider.decide(
            snapshot("请完成安全验证", "拖动滑块"),
            now = 10_000L,
            lastActionAt = 0L,
        )
        assertEquals("unknown", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }

    @Test
    fun `cooldown blocks repeated swipe`() {
        val decision = decider.decide(
            snapshot("上滑继续观看短剧"),
            now = 12_000L,
            lastActionAt = 10_000L,
        )
        assertEquals("cooldown", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }

    @Test
    fun `empty ocr does nothing`() {
        val decision = decider.decide(OcrSnapshot.EMPTY, now = 10_000L, lastActionAt = 0L)
        assertEquals("empty-ocr", decision.note)
        assertEquals(OcrAction.None, decision.action)
    }
}
