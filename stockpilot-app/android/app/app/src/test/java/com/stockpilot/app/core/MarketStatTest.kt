package com.stockpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全市场成交与资金统计测试 */
class MarketStatTest {

    private fun mk(
        name: String, secid: String, amount: Double, volume: Double,
        main: Double, superLarge: Double, large: Double, medium: Double,
        small: Double, mainPct: Double
    ) = MarketAmount(name, secid, amount, volume, main, superLarge, large, medium, small, mainPct)

    // 沪市
    private val sh = mk(
        "沪市", "1.000001", 779281246497.2, 458888916.0,
        -9923842048.0, -4334002176.0, -5589839872.0, -534118400.0, 10457964544.0, -1.27
    )

    // 深市（主力 = 大单 -3,555,000,000 + 超大单 -3,053,000,000，与接口口径一致）
    private val sz = mk(
        "深市", "0.399001", 849894305507.7, 508969614.0,
        -6608000000.0, -3053000000.0, -3555000000.0, -523000000.0, 7130000000.0, -0.78
    )

    // 北交所
    private val bj = mk(
        "北交所", "0.899050", 13950502717.0, 6492158.0,
        183000000.0, 109000000.0, 74000000.0, -144000000.0, -179000000.0, 1.31
    )

    private val cur = MarketSnapshot("2026-09-14", listOf(sh, sz, bj))

    private fun snapWithMain(date: String, main: Double) = MarketSnapshot(
        date,
        listOf(mk("沪市", "1.000001", 1e11, 1e8, main, main, 0.0, 0.0, 0.0, -0.5))
    )

    // ---------------- 合计 ----------------

    @Test
    fun `三市场合计成交额与资金`() {
        assertEquals(779281246497.2 + 849894305507.7 + 13950502717.0, cur.amountTotal, 1.0)
        assertEquals(458888916.0 + 508969614.0 + 6492158.0, cur.volumeTotal, 1.0)
        assertEquals(-9923842048.0 - 6607000000.0 + 183000000.0, cur.mainTotal, 1.0)
        assertTrue(cur.hasData)
    }

    @Test
    fun `主力净额等于大单加超大单`() {
        assertEquals(cur.mainTotal, cur.largeTotal + cur.superLargeTotal, 1.0)
    }

    @Test
    fun `空快照视为无数据`() {
        val empty = MarketSnapshot("2026-09-14", emptyList())
        assertTrue(!empty.hasData)
        assertTrue(MarketStat.lines(empty, null).isEmpty())
        assertTrue(MarketStat.lines(null, null).isEmpty())
        assertNull(MarketStat.mainStreak(empty, emptyList()))
    }

    // ---------------- 格式化 ----------------

    @Test
    fun `金额格式化`() {
        assertEquals("1.64万亿", MarketStat.yi(1643126054721.9))
        assertEquals("7,792.81亿", MarketStat.yi(779281246497.2))
        assertEquals("+123.46亿", MarketStat.signedYi(12345678900.0))
        assertEquals("-99.24亿", MarketStat.signedYi(-9923842048.0))
        assertEquals("0", MarketStat.signedYi(0.0))
    }

    @Test
    fun `成交量格式化`() {
        assertEquals("9.74亿手", MarketStat.hand(974350688.0))
        assertEquals("649.2万手", MarketStat.hand(6492158.0))
        assertEquals("3,200手", MarketStat.hand(3200.0))
    }

    @Test
    fun `百分比格式化为一位小数并带符号`() {
        assertEquals("-1.3%", MarketStat.signedPct(-1.27))
        assertEquals("+1.3%", MarketStat.signedPct(1.31))
        assertEquals("0.0%", MarketStat.signedPct(0.0))
        assertEquals("8.8%", MarketStat.pct(8.816))
    }

    // ---------------- 明细行 ----------------

    @Test
    fun `明细行含各市场成交额与资金进出`() {
        val lines = MarketStat.lines(cur, null)
        assertEquals(5, lines.size)

        val amount = lines[0]
        assertTrue(amount.contains("全市场成交额 1.64万亿"))
        assertTrue(amount.contains("沪市 7,792.81亿"))
        assertTrue(amount.contains("深市 8,498.94亿"))
        assertTrue(amount.contains("北交所 139.51亿"))
        assertTrue(!amount.contains("较上一交易日"))

        val volume = lines[1]
        assertTrue(volume.contains("全市场成交量 9.74亿手"))

        val main = lines[2]
        assertTrue(main.contains("全市场主力资金净流出 163.49亿"))
        assertTrue(main.contains("沪市 -99.24亿(-1.3%)"))
        assertTrue(main.contains("深市 -66.08亿"))

        val struct = lines[3]
        assertTrue(struct.contains("超大单 -72.78亿"))
        assertTrue(struct.contains("大单 -90.71亿"))
        assertTrue(struct.contains("小单 +174.09亿"))
    }

    @Test
    fun `有上一交易日快照时给出环比`() {
        val prev = MarketSnapshot(
            "2026-09-11",
            listOf(
                mk("沪市", "1.000001", 7000e8, 400e6, 30e8, 30e8, 0.0, 0.0, 0.0, 0.5),
                mk("深市", "0.399001", 8000e8, 450e6, 20e8, 20e8, 0.0, 0.0, 0.0, 0.4),
                mk("北交所", "0.899050", 100e8, 5e6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            )
        )
        val lines = MarketStat.lines(cur, prev)
        assertTrue(lines[0].contains("较上一交易日"))
        assertTrue(lines[2].contains("较上一交易日"))
        // 上一交易日主力 +50 亿，今日 -163.49 亿 -> 环比 -213.49 亿
        assertTrue(lines[2].contains("-213.49亿"))
    }

    // ---------------- 资金连续性 ----------------

    @Test
    fun `连续净流出天数统计`() {
        val prevDays = listOf(
            snapWithMain("2026-09-10", -20e8),
            snapWithMain("2026-09-11", -50e8)
        )
        // 当日为净流出，加上前两日同向 -> 连续 3 日
        assertEquals("连续 3 日主力净流出", MarketStat.mainStreak(cur, prevDays))
    }

    @Test
    fun `方向变化时不算连续`() {
        val prevDays = listOf(snapWithMain("2026-09-11", 5e8))
        assertNull(MarketStat.mainStreak(cur, prevDays))
    }

    @Test
    fun `净流入方向同样统计`() {
        val flowIn = MarketSnapshot("2026-09-14", listOf(mk("沪市", "1.000001", 1e11, 1e8, 50e8, 50e8, 0.0, 0.0, 0.0, 1.0)))
        val prevDays = listOf(snapWithMain("2026-09-11", 30e8))
        assertEquals("连续 2 日主力净流入", MarketStat.mainStreak(flowIn, prevDays))
    }
}
