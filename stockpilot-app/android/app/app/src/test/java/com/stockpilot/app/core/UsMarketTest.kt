package com.stockpilot.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** 美股盘面热点报告：结构完整性 + 涨跌方向判定 校验 */
class UsMarketTest {

    private fun put(m: MutableMap<String, Quote>, secid: String, name: String, chg: Double, px: Double) {
        m[secid] = Quote(
            secid = secid,
            code = secid.substringAfter('.'),
            market = secid.substringBefore('.'),
            name = name,
            price = px,
            changePct = chg
        )
    }

    private fun sample(up: Boolean): MutableMap<String, Quote> {
        val m = HashMap<String, Quote>()
        val s = if (up) 1.0 else -1.0
        put(m, "100.DJIA", "道琼斯", 0.98 * s, 52573.29)
        put(m, "100.NDX", "纳斯达克", 0.96 * s, 26333.04)
        put(m, "100.SPX", "标普500", 0.86 * s, 7656.98)
        put(m, "251.SOX", "费城半导体", 1.81 * s, 11824.0)
        put(m, "251.HXC", "中概金龙", 0.40 * s, 5811.71)
        put(m, "105.SMH", "半导体", 1.47 * s, 568.53)
        put(m, "107.XLK", "科技", 1.32 * s, 187.67)
        put(m, "107.XLV", "医疗", -0.18 * s, 165.36)
        put(m, "107.XLE", "能源", 0.32 * s, 65.14)
        put(m, "105.NVDA", "英伟达", -0.03 * s, 218.29)
        put(m, "106.TSM", "台积电", 1.22 * s, 433.24)
        put(m, "105.TSLA", "特斯拉", 0.52 * s, 365.44)
        put(m, "105.JD", "京东", 0.15 * s, 77.81)
        return m
    }

    @Test
    fun buildReport_containsAllSections() {
        val text = UsMarket.buildReport("2026-09-15", sample(true))
        assertTrue("标题缺失", text.contains("美股盘面热点报告 2026-09-15"))
        assertTrue("大盘速览缺失", text.contains("一、大盘速览"))
        assertTrue("板块涨跌榜缺失", text.contains("二、板块涨跌榜"))
        assertTrue("龙头个股缺失", text.contains("三、龙头个股涨跌榜"))
        assertTrue("中概股缺失", text.contains("四、中概股"))
        assertTrue("A股提示缺失", text.contains("五、对 A 股的参考提示"))
        assertTrue("免责声明缺失", text.contains("不构成投资建议"))
        assertTrue("费半缺失", text.contains("费城半导体"))
        assertTrue("板块A股映射缺失", text.contains("A股参考：半导体"))
        assertTrue("龙头映射缺失", text.contains("英伟达"))
    }

    @Test
    fun buildReport_directionDetection() {
        val up = UsMarket.buildReport("2026-09-15", sample(true))
        assertTrue("三涨应判为偏暖", up.contains("风险偏好偏暖"))
        val down = UsMarket.buildReport("2026-09-15", sample(false))
        assertTrue("三跌应判为偏冷", down.contains("风险偏好偏冷"))
    }

    @Test
    fun buildReport_handlesEmptyData() {
        val text = UsMarket.buildReport("2026-09-15", HashMap())
        assertTrue("无数据时应有提示", text.contains("未获取到美股数据"))
    }

    @Test
    fun universe_noDuplicateSecids() {
        val ids = UsMarket.all().map { it.secid }
        assertTrue(
            "存在重复 secid：" + ids.size + " 项 / " + ids.toSet().size + " 唯一",
            ids.size == ids.toSet().size
        )
    }
}
