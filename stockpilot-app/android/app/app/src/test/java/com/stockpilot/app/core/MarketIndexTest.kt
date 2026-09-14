package com.stockpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大盘指数与市场宽度测试 */
class MarketIndexTest {

    private fun up(secid: String, chg: Double) = Quote(
        secid = secid, code = secid.substringAfter('.'), market = secid.substringBefore('.'),
        price = 3000.0, changePct = chg
    )

    /** 带市场家数（f104/f105/f106）的指数行情 */
    private fun mkt(secid: String, chg: Double, a: Int, d: Int, f: Int) = Quote(
        secid = secid, code = secid.substringAfter('.'), market = secid.substringBefore('.'),
        price = 3000.0, changePct = chg,
        advanceCount = a, declineCount = d, flatCount = f
    )

    private fun quotesWith(chg: Double, a: Int, d: Int, f: Int): Map<String, Quote> {
        val m = HashMap<String, Quote>()
        for (idx in MarketIndex.ALL) m[idx.secid] = up(idx.secid, chg)
        m["1.000001"] = mkt("1.000001", chg, a, d, f)
        m["0.399001"] = mkt("0.399001", chg, 0, 0, 0)
        m["0.899050"] = mkt("0.899050", chg, 0, 0, 0)
        return m
    }

    @Test
    fun `指数代码覆盖八个且互不重复`() {
        val ids = MarketIndex.secids()
        assertEquals(8, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `全市场家数按三地口径累加且不重复计算`() {
        val quotes = HashMap<String, Quote>()
        quotes["1.000001"] = mkt("1.000001", -0.07, 1330, 928, 94)
        quotes["0.399001"] = mkt("0.399001", -0.64, 1644, 1179, 109)
        quotes["0.899050"] = mkt("0.899050", -0.15, 187, 141, 15)
        // 创业板指/科创50 是深沪子集，不应计入
        quotes["0.399006"] = mkt("0.399006", -1.10, 884, 487, 34)
        quotes["1.000688"] = mkt("1.000688", -1.62, 12, 37, 1)

        val b = MarketIndex.breadth(quotes, emptyList())
        assertEquals(1330 + 1644 + 187, b.stockUp)
        assertEquals(928 + 1179 + 141, b.stockDown)
        assertEquals(94 + 109 + 15, b.stockFlat)
        assertTrue(b.hasMarket)
        assertEquals(b.stockUp.toDouble() / b.stockTotal, b.marketUpRatio, 1e-9)
    }

    @Test
    fun `板块涨跌家数与资金流向板块数`() {
        val sectors = listOf(
            Sector("BK1", "甲", 5.0, 3.2e8, 3.0),
            Sector("BK2", "乙", 1.0, -1.1e8, 2.0),
            Sector("BK3", "丙", -2.0, -2.0e8, 1.0),
            Sector("BK4", "丁", 0.0, 0.0, 0.5)
        )
        val b = MarketIndex.breadth(emptyMap(), sectors)
        assertEquals(2, b.sectorUp)
        assertEquals(1, b.sectorDown)
        assertEquals(1, b.sectorFlat)
        assertEquals(4, b.sectorTotal)
        assertEquals(1, b.fundInSectors)
        assertEquals(2, b.fundOutSectors)
        assertTrue(!b.hasMarket)
    }

    @Test
    fun `全线上涨时定调偏暖`() {
        val quotes = MarketIndex.ALL.associate { it.secid to up(it.secid, 1.2) }
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, emptyList()))
        assertTrue(tone.contains("全线收涨"))
    }

    @Test
    fun `全线下跌时定调偏冷`() {
        val quotes = MarketIndex.ALL.associate { it.secid to up(it.secid, -1.5) }
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, emptyList()))
        assertTrue(tone.contains("全线收跌"))
    }

    @Test
    fun `成长强于权重时提示风格`() {
        val quotes = HashMap<String, Quote>()
        for (idx in MarketIndex.ALL) quotes[idx.secid] = up(idx.secid, 0.2)
        quotes["0.399006"] = up("0.399006", 2.5)   // 创业板指
        quotes["1.000300"] = up("1.000300", 0.1)   // 沪深300
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, emptyList()))
        assertTrue(tone.contains("成长强于权重"))
    }

    @Test
    fun `个股普跌时提示控制仓位`() {
        val quotes = quotesWith(-0.8, 900, 4200, 100)
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, emptyList()))
        assertTrue(tone.contains("全市场"))
        assertTrue(tone.contains("个股普跌"))
    }

    @Test
    fun `回归——指数下跌但板块榜全为上涨时不得报普涨`() {
        // 曾经的缺陷：只用「涨幅榜前 100 个板块」统计宽度，得出"指数全线下跌却板块 93 涨 0 跌、
        // 普涨格局"的自相矛盾结论。现在以全市场家数为准，板块榜的偏差不得影响定调。
        val quotes = quotesWith(-0.8, 900, 4200, 100)
        val allUpSectors = (1..30).map { Sector("BK$it", "概念$it", 2.0, 1e8, 3.0) }
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, allUpSectors))
        assertTrue(tone.contains("全线收跌"))
        assertTrue(tone.contains("个股普跌"))
        assertTrue(!tone.contains("普涨"))
    }

    @Test
    fun `无市场家数时退回板块口径`() {
        val quotes = MarketIndex.ALL.associate { it.secid to up(it.secid, -0.5) }
        val sectors = (1..10).map { Sector("BK$it", "板块$it", -1.0, -1e8, 1.0) }
        val tone = MarketIndex.tone(quotes, MarketIndex.breadth(quotes, sectors))
        assertTrue(tone.contains("概念板块"))
        assertTrue(tone.contains("普跌格局"))
    }

    @Test
    fun `过滤风格类伪板块`() {
        val raw = listOf(
            Sector("BK1", "培育钻石", 4.48, 1.8e8, 5.12),
            Sector("BK2", "昨日连板", 4.94, 3.3e8, 16.24),
            Sector("BK3", "昨日涨停", 3.74, 1.4e8, 13.04),
            Sector("BK4", "融资融券", 1.0, 5e8, 1.5),
            Sector("BK5", "机构重仓", 0.8, 4e8, 1.2),
            Sector("BK6", "MSCI中国", 0.5, 2e8, 0.9),
            Sector("BK7", "东方财富热股", 1.22, -2.8e9, 7.34),
            Sector("BK8", "CPO", 4.10, 1.1e8, 3.2)
        )
        val real = MarketIndex.realSectors(raw)
        assertEquals(2, real.size)
        assertTrue(real.any { it.name == "培育钻石" })
        assertTrue(real.any { it.name == "CPO" })
        assertTrue(MarketIndex.isRealSector("半导体"))
        assertTrue(!MarketIndex.isRealSector("昨日跌停"))
        assertTrue(!MarketIndex.isRealSector("东方财富热股"))
    }

    @Test
    fun `无行情数据时给出兜底文案`() {
        val tone = MarketIndex.tone(emptyMap(), MarketIndex.breadth(emptyMap(), emptyList()))
        assertTrue(tone.contains("数据不足"))
        assertTrue(MarketIndex.lines(emptyMap()).isEmpty())
    }

    @Test
    fun `指数明细行数量与行情数量一致`() {
        val quotes = MarketIndex.ALL.associate { it.secid to up(it.secid, 0.8) }
        val lines = MarketIndex.lines(quotes)
        assertEquals(8, lines.size)
        assertTrue(lines[0].contains("上证指数"))
        assertTrue(lines[0].contains("+0.80%"))
    }
}
