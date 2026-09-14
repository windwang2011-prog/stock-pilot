package com.stockpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每日报告结构与投资建议生成测试 */
class ReportTest {

    private val phase = PhaseInfo("after", "收盘后", "收盘后复盘，参考全天资金与位置")

    /** 指数行情（三地市场节点带上真实口径的涨跌家数） */
    private fun idxQuote(secid: String, chg: Double) = Quote(
        secid = secid,
        code = secid.substringAfter('.'),
        market = secid.substringBefore('.'),
        price = 3000.0,
        changePct = chg,
        advanceCount = when (secid) {
            "1.000001" -> 1330
            "0.399001" -> 1644
            "0.899050" -> 187
            else -> null
        },
        declineCount = when (secid) {
            "1.000001" -> 928
            "0.399001" -> 1179
            "0.899050" -> 141
            else -> null
        },
        flatCount = when (secid) {
            "1.000001" -> 94
            "0.399001" -> 109
            "0.899050" -> 15
            else -> null
        }
    )

    private fun signal(score: Int, action: String) = Signal(
        score = score,
        baseScore = score,
        sessionDelta = 0,
        action = action,
        trend = "多头",
        uptrend = true,
        belowMA20 = false,
        entry = 10.0,
        stop = 9.5,
        target = 11.0,
        rsi = 60.0,
        macd = 0.1,
        volRatio = 1.2,
        phase = "after",
        phaseLabel = "收盘后",
        sessionSummary = "资金·主力净流入 1.00 亿（占成交额 5.0%）；分时·现价高于均价 1.20%",
        sessionPoints = listOf(SessionPoint("资金", "主力净流入 1.00 亿", 7)),
        reasons = listOf("【技术面】价格站上 MA20", "MACD 金叉", "放量上涨"),
        metrics = Metrics(null, null, 0.8, 1.2, 0.3, 1.0e8, 5.0, 3.0, 4.0, 2.5)
    )

    private fun wr(
        secid: String, code: String, name: String,
        score: Int, action: String, price: Double, chg: Double
    ) = WatchResult(
        stock = StockRef(secid, code, name),
        signal = signal(score, action),
        price = price,
        changePct = chg,
        mainNet = 1.0e8,
        notify = false,
        notifyTitle = "",
        notifyBody = ""
    )

    private fun sampleInput(
        watch: List<WatchResult> = emptyList(),
        streaks: Map<String, Streak> = emptyMap(),
        historyDays: Int = 5,
        sectors: List<Sector>? = null,
        indices: Map<String, Quote>? = null
    ): ReportInput {
        val idx = indices ?: MarketIndex.ALL.associate { it.secid to idxQuote(it.secid, 0.5) }
        val sec = sectors ?: listOf(
            Sector("BK1", "培育钻石", 5.21, 3.2e8, 4.5),
            Sector("BK2", "CPO", 4.10, 1.1e8, 3.2),
            Sector("BK3", "银行", -1.20, -2.0e8, 0.8)
        )
        return ReportInput(
            date = "2026-09-14",
            timeLabel = "20:06",
            phase = phase,
            indexQuotes = idx,
            sectors = sec,
            leaders = listOf(LeaderRow("培育钻石", 5.21, wr("0.839725", "839725", "惠丰钻石", 86, "买入", 34.45, 9.98))),
            recos = listOf(
                wr("1.688981", "688981", "中芯国际", 78, "买入", 50.0, 3.10),
                wr("0.300750", "300750", "宁德时代", 52, "逢低关注", 180.0, -1.20)
            ),
            streaks = streaks,
            watch = watch,
            historyDays = historyDays
        )
    }

    @Test
    fun `报告包含完整的六段结构`() {
        val text = Report.build(sampleInput())
        val sections = listOf(
            "## 一、大盘概览",
            "## 二、热点板块",
            "## 三、龙头个股",
            "## 四、推荐个股 · 信号连续性排行",
            "## 五、我的关注",
            "## 六、投资建议"
        )
        for (s in sections) assertTrue("缺少章节: $s", text.contains(s))
    }

    @Test
    fun `大盘与板块数据写入报告`() {
        val text = Report.build(sampleInput())
        assertTrue(text.contains("上证指数"))
        assertTrue(text.contains("**定调**"))
        assertTrue(text.contains("市场宽度"))
        assertTrue(text.contains("全市场 3161 涨 / 2248 跌"))
        assertTrue(text.contains("概念板块"))
        assertTrue(text.contains("培育钻石"))
        assertTrue(text.contains("惠丰钻石"))          // 龙头股
        assertTrue(text.contains("主线判断"))
        assertTrue(text.contains("主力资金：净流入板块"))
    }

    @Test
    fun `连续买入排行出现在推荐章节`() {
        val streaks = mapOf(
            "1.688981" to Streak("1.688981", "中芯国际", "买入", 3, 78, 50.0, 46.0)
        )
        val text = Report.build(sampleInput(streaks = streaks))
        assertTrue(text.contains("连续看好"))
        assertTrue(text.contains("连续 3 天建议买入"))
        assertTrue(text.contains("区间"))               // 区间涨幅
    }

    @Test
    fun `信号历史不足时给出提示`() {
        val text = Report.build(sampleInput(historyDays = 1))
        assertTrue(text.contains("信号历史仅 1 个交易日"))
    }

    @Test
    fun `自选为空时报告仍生成并提示`() {
        val text = Report.build(sampleInput())
        assertTrue(text.contains("未设置自选股"))
        assertTrue(text.contains("## 六、投资建议"))
    }

    @Test
    fun `自选股分析含依据与参考位`() {
        val watch = listOf(
            wr("1.600519", "600519", "贵州茅台", 82, "买入", 1277.96, 2.10),
            wr("0.000858", "000858", "五粮液", 20, "卖出", 130.0, -3.20)
        )
        val text = Report.build(sampleInput(watch = watch))
        assertTrue(text.contains("买入信号（1）"))
        assertTrue(text.contains("卖出 / 减仓信号（1）"))
        assertTrue(text.contains("贵州茅台"))
        assertTrue(text.contains("依据："))
        assertTrue(text.contains("参考位：入场"))
    }

    @Test
    fun `投资建议包含三个层面与风险提示`() {
        val text = Report.build(sampleInput())
        val keys = listOf("**市场层面**", "**板块层面**", "**个股层面**", "**风险提示**")
        for (k in keys) assertTrue("缺少: $k", text.contains(k))
        assertTrue(text.contains("仓位"))
        assertTrue(text.contains("不构成投资建议"))
    }

    @Test
    fun `连续买入高分股进入重点关注`() {
        val streaks = mapOf(
            "1.688981" to Streak("1.688981", "中芯国际", "买入", 3, 78, 50.0, 46.0)
        )
        val text = Report.build(sampleInput(streaks = streaks))
        assertTrue(text.contains("重点关注："))
        assertTrue(text.contains("中芯国际"))
    }

    @Test
    fun `板块与指数全缺失时降级但不崩`() {
        val input = ReportInput(
            date = "2026-09-14",
            timeLabel = "20:06",
            phase = phase,
            indexQuotes = emptyMap(),
            sectors = emptyList(),
            leaders = emptyList(),
            recos = emptyList(),
            streaks = emptyMap(),
            watch = emptyList(),
            historyDays = 0,
            notes = listOf("热点板块获取失败")
        )
        val text = Report.build(input)
        assertTrue(text.contains("数据提示"))
        assertTrue(text.contains("未获取到大盘指数数据"))
        assertTrue(text.contains("跳过板块分析"))
        assertTrue(text.contains("## 六、投资建议"))
        assertTrue(text.contains("大盘数据缺失"))
    }

    @Test
    fun `通知摘要行在无自选时给出概览文案`() {
        assertEquals("大盘/板块/龙头概览", Report.summaryLine(emptyList()))
        val line = Report.summaryLine(
            listOf(
                wr("1.600519", "600519", "茅台", 82, "买入", 100.0, 1.0),
                wr("0.000858", "000858", "五粮液", 20, "卖出", 100.0, -1.0)
            )
        )
        assertEquals("自选 2｜买入 1｜卖出 1", line)
    }

    @Test
    fun `金额与百分比格式化`() {
        assertEquals("+1.50亿", Report.fmtMoney(1.5e8))
        assertEquals("-2.00亿", Report.fmtMoney(-2.0e8))
        assertEquals("+3.20万", Report.fmtMoney(3.2e4))
        assertEquals("+2.50%", Report.fmtPct(2.5))
        assertEquals("-1.20%", Report.fmtPct(-1.2))
        assertEquals("--", Report.fmtPrice(null))
    }
}
