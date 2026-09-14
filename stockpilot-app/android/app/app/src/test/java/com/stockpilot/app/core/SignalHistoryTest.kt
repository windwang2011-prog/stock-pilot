package com.stockpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「连续 N 天同一建议」统计逻辑测试 */
class SignalHistoryTest {

    private fun item(secid: String, name: String, action: String, score: Int, price: Double?) =
        SignalItem(secid, name, action, score, price)

    private fun snap(date: String, vararg items: SignalItem) = SignalSnapshot(date, items.toList())

    @Test
    fun `连续三天建议买入`() {
        val h = listOf(
            snap("2026-09-10", item("1.600519", "贵州茅台", "买入", 80, 10.0)),
            snap("2026-09-11", item("1.600519", "贵州茅台", "买入", 82, 10.5)),
            snap("2026-09-12", item("1.600519", "贵州茅台", "买入", 85, 11.0))
        )
        val s = SignalHistory.streaks(h)
        val a = s["1.600519"]
        assertEquals(3, a?.days)
        assertEquals("买入", a?.action)
        assertEquals(85, a?.score)
        assertEquals("连续 3 天建议买入", a?.label)
        // 区间涨幅 = 11.0 / 10.0 - 1 = 10%
        assertEquals(10.0, a?.gainPct ?: 0.0, 1e-6)
    }

    @Test
    fun `动作变化则重新计数`() {
        val h = listOf(
            snap("2026-09-10", item("1.000001", "A", "买入", 70, 5.0)),
            snap("2026-09-11", item("1.000001", "A", "买入", 72, 5.2)),
            snap("2026-09-12", item("1.000001", "A", "卖出", 20, 5.5))
        )
        val s = SignalHistory.streaks(h)
        assertEquals(1, s["1.000001"]?.days)
        assertEquals("卖出", s["1.000001"]?.action)
        assertEquals("今日首次卖出", s["1.000001"]?.label)
    }

    @Test
    fun `中间缺失一天会中断连续`() {
        val h = listOf(
            snap("2026-09-10", item("0.000002", "B", "买入", 70, 8.0)),
            snap("2026-09-11", item("1.600000", "其他", "买入", 60, 9.0)),   // B 未被推荐
            snap("2026-09-12", item("0.000002", "B", "买入", 71, 8.4))
        )
        val s = SignalHistory.streaks(h)
        assertEquals(1, s["0.000002"]?.days)
    }

    @Test
    fun `不同动作分别统计且买入优先排序`() {
        val h = listOf(
            snap("2026-09-10",
                item("1.600519", "茅台", "买入", 80, 10.0),
                item("1.300750", "宁德", "卖出", 20, 20.0)),
            snap("2026-09-11",
                item("1.600519", "茅台", "买入", 81, 10.2),
                item("1.300750", "宁德", "卖出", 18, 19.5))
        )
        val s = SignalHistory.streaks(h)
        val ranked = SignalHistory.ranked(s, 2)
        assertEquals(2, ranked.size)
        // 买入动作排在卖出之前
        assertEquals("买入", ranked[0].action)
        assertEquals("卖出", ranked[1].action)
    }

    @Test
    fun `低于阈值不入榜`() {
        val h = listOf(snap("2026-09-12", item("1.600519", "茅台", "买入", 80, 10.0)))
        val s = SignalHistory.streaks(h)
        assertEquals(1, s["1.600519"]?.days)
        assertTrue(SignalHistory.ranked(s, 2).isEmpty())
    }

    @Test
    fun `无价格时不计算区间涨幅`() {
        val h = listOf(
            snap("2026-09-11", item("1.600519", "茅台", "买入", 80, null)),
            snap("2026-09-12", item("1.600519", "茅台", "买入", 80, null))
        )
        val s = SignalHistory.streaks(h)
        assertEquals(2, s["1.600519"]?.days)
        assertNull(s["1.600519"]?.gainPct)
    }

    @Test
    fun `按日期排序后再统计`() {
        val h = listOf(
            snap("2026-09-12", item("1.600519", "茅台", "买入", 80, 10.0)),
            snap("2026-09-10", item("1.600519", "茅台", "买入", 80, 10.0)),
            snap("2026-09-11", item("1.600519", "茅台", "买入", 80, 10.0))
        )
        assertEquals(3, SignalHistory.streaks(h)["1.600519"]?.days)
        assertEquals(3, SignalHistory.tradingDays(h))
    }

    @Test
    fun `空历史返回空结果`() {
        assertTrue(SignalHistory.streaks(emptyList()).isEmpty())
        assertEquals(0, SignalHistory.tradingDays(emptyList()))
    }
}
