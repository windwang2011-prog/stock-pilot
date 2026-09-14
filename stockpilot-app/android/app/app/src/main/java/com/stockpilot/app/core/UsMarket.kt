package com.stockpilot.app.core

import java.util.Locale

/**
 * 美股盘面热点报告
 *
 * 数据源已实测验证（东方财富美股行情）：
 *   指数  100.DJIA / 100.NDX / 100.SPX / 251.SOX(费半) / 251.HXC(中概金龙)
 *   行业ETF  107.XLK~XLRE（SPDR 系列）、105.SMH(半导体)、105.IBB(生物科技)、107.TAN(太阳能)、107.ARKK(创新)
 *   龙头个股 105.NVDA / 106.TSM 等；中概 105.JD / 106.NIO 等
 *
 * 报告目的：美股隔夜走势 → A 股次日方向的参考（板块映射 + 龙头映射）。
 */
object UsMarket {

    data class Item(val secid: String, val label: String, val aShareHint: String)

    /** 大盘指数 */
    val INDICES = listOf(
        Item("100.DJIA", "道琼斯", ""),
        Item("100.NDX", "纳斯达克", ""),
        Item("100.SPX", "标普500", ""),
        Item("251.SOX", "费城半导体", "半导体风向标"),
        Item("251.HXC", "中概金龙", "中概/港股情绪")
    )

    /** 行业板块（ETF 作为板块代理） */
    val SECTORS = listOf(
        Item("105.SMH", "半导体", "半导体 / 芯片 / 光模块 / PCB"),
        Item("107.TAN", "清洁能源", "光伏 / 储能 / 风电"),
        Item("107.XLK", "科技", "软件 / 云计算 / 数字经济"),
        Item("107.XLF", "金融", "银行 / 券商 / 保险"),
        Item("107.XLV", "医疗", "医药 / 医疗器械"),
        Item("105.IBB", "生物科技", "创新药 / CXO"),
        Item("107.XLE", "能源", "石油 / 煤炭 / 油服"),
        Item("107.XLY", "可选消费", "消费 / 家电 / 汽车"),
        Item("107.XLP", "日常消费", "食品饮料 / 白酒"),
        Item("107.XLI", "工业", "机械 / 工程机械 / 军工"),
        Item("107.XLB", "基础材料", "有色 / 化工 / 钢铁"),
        Item("107.XLU", "公用事业", "电力 / 水务 / 燃气"),
        Item("107.XLC", "通讯服务", "通信 / 传媒 / 游戏"),
        Item("107.XLRE", "房地产", "地产 / 建材"),
        Item("107.ARKK", "创新成长", "成长股 / 题材股情绪"),
        Item("105.QQQ", "纳指100ETF", "科技成长整体"),
        Item("107.SPY", "标普500ETF", "全球风险偏好")
    )

    /** 龙头个股 */
    val LEADERS = listOf(
        Item("105.NVDA", "英伟达", "算力 / AI / 光模块 / PCB"),
        Item("106.TSM", "台积电", "半导体代工链"),
        Item("105.AMD", "超威半导体", "CPU / 算力"),
        Item("105.MU", "美光科技", "存储芯片"),
        Item("105.AVGO", "博通", "芯片 / 网络设备"),
        Item("105.QCOM", "高通", "芯片 / 消费电子"),
        Item("105.INTC", "英特尔", "芯片 / 半导体设备"),
        Item("105.AAPL", "苹果", "苹果产业链 / 消费电子"),
        Item("105.MSFT", "微软", "云计算 / AI 应用"),
        Item("105.GOOGL", "谷歌", "AI / 广告 / 云"),
        Item("105.AMZN", "亚马逊", "云计算 / 跨境电商"),
        Item("105.META", "Meta", "AI / 社交 / 算力"),
        Item("105.TSLA", "特斯拉", "特斯拉产业链 / 锂电 / 汽车零部件")
    )

    /** 中概股 */
    val CHINA = listOf(
        Item("105.JD", "京东", "电商"),
        Item("105.PDD", "拼多多", "电商"),
        Item("105.BIDU", "百度", "AI / 自动驾驶"),
        Item("106.NIO", "蔚来", "新能源车"),
        Item("106.XPEV", "小鹏汽车", "新能源车 / 智驾"),
        Item("105.LI", "理想汽车", "新能源车")
    )

    fun all(): List<Item> = INDICES + SECTORS + LEADERS + CHINA

    private fun pct(v: Double): String =
        (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"

    private fun px(v: Double?): String =
        if (v == null) "--" else String.format(Locale.US, "%,.2f", v)

    private class Ranked(val item: Item, val quote: Quote)

    /**
     * 生成美股盘面热点报告
     * @param date 北京时间日期（如 2026-09-15）
     * @param quotes secid -> Quote
     */
    fun buildReport(date: String, quotes: Map<String, Quote>): String {
        val sb = StringBuilder()
        sb.append("# 美股盘面热点报告 ").append(date).append("（北京时间）\n\n")

        fun collect(items: List<Item>): List<Ranked> =
            items.mapNotNull { item ->
                val q = quotes[item.secid]
                if (q == null) null else Ranked(item, q)
            }

        val idx = collect(INDICES)
        val sec = collect(SECTORS)
        val lead = collect(LEADERS)
        val cn = collect(CHINA)

        // ---------- 一、大盘速览 ----------
        sb.append("## 一、大盘速览\n")
        for (r in idx) {
            sb.append("- ").append(r.item.label).append(' ')
                .append(px(r.quote.price)).append(' ').append(pct(r.quote.changePct ?: 0.0))
            if (r.item.aShareHint.isNotEmpty()) sb.append("　← ").append(r.item.aShareHint)
            sb.append('\n')
        }

        val mainIdx = idx.filter { it.item.secid != "251.HXC" }
        val upCount = mainIdx.count { (it.quote.changePct ?: 0.0) > 0 }
        val sox = idx.firstOrNull { it.item.secid == "251.SOX" }?.quote?.changePct
        val tone = when {
            upCount >= 3 -> "三大指数集体收涨，风险偏好偏暖"
            upCount == 0 -> "三大指数集体收跌，风险偏好偏冷"
            else -> "三大指数涨跌互现，方向不明"
        }
        sb.append("\n**一句话**：").append(tone)
        if (sox != null) {
            sb.append("；费城半导体 ").append(pct(sox))
            sb.append(if (sox >= 1) "，半导体明显走强" else if (sox <= -1) "，半导体明显走弱" else "，半导体平稳")
        }
        sb.append("。\n\n")

        // ---------- 二、板块涨跌榜 ----------
        val secSorted = sec.sortedByDescending { it.quote.changePct ?: 0.0 }
        sb.append("## 二、板块涨跌榜\n")
        sb.append("**领涨**\n")
        for (r in secSorted.take(5)) {
            sb.append("- ").append(r.item.label).append(' ').append(pct(r.quote.changePct ?: 0.0))
                .append("　→ A股参考：").append(r.item.aShareHint).append('\n')
        }
        if (secSorted.size > 5) {
            sb.append("**领跌**\n")
            for (r in secSorted.takeLast(minOf(5, secSorted.size / 2)).reversed()) {
                sb.append("- ").append(r.item.label).append(' ').append(pct(r.quote.changePct ?: 0.0))
                    .append("　→ A股参考：").append(r.item.aShareHint).append('\n')
            }
        }
        sb.append('\n')

        // ---------- 三、龙头个股 ----------
        val leadSorted = lead.sortedByDescending { it.quote.changePct ?: 0.0 }
        sb.append("## 三、龙头个股涨跌榜\n")
        sb.append("**涨幅居前**\n")
        for (r in leadSorted.take(6)) {
            sb.append("- ").append(r.item.label).append(' ').append(pct(r.quote.changePct ?: 0.0))
                .append("　→ ").append(r.item.aShareHint).append('\n')
        }
        sb.append("**跌幅居前**\n")
        for (r in leadSorted.takeLast(minOf(6, leadSorted.size / 2)).reversed()) {
            sb.append("- ").append(r.item.label).append(' ').append(pct(r.quote.changePct ?: 0.0))
                .append("　→ ").append(r.item.aShareHint).append('\n')
        }
        sb.append('\n')

        // ---------- 四、中概股 ----------
        if (cn.isNotEmpty()) {
            sb.append("## 四、中概股（港股/科技情绪参考）\n")
            val cnSorted = cn.sortedByDescending { it.quote.changePct ?: 0.0 }
            for (r in cnSorted) {
                sb.append("- ").append(r.item.label).append(' ').append(pct(r.quote.changePct ?: 0.0))
                    .append("（").append(r.item.aShareHint).append("）\n")
            }
            sb.append('\n')
        }

        // ---------- 五、对 A 股的参考提示 ----------
        sb.append("## 五、对 A 股的参考提示\n")
        val hints = ArrayList<String>()
        val smh = sec.firstOrNull { it.item.secid == "105.SMH" }?.quote?.changePct
        val tan = sec.firstOrNull { it.item.secid == "107.TAN" }?.quote?.changePct
        val xle = sec.firstOrNull { it.item.secid == "107.XLE" }?.quote?.changePct
        val hxc = idx.firstOrNull { it.item.secid == "251.HXC" }?.quote?.changePct
        val nvda = lead.firstOrNull { it.item.secid == "105.NVDA" }?.quote?.changePct
        val tsla = lead.firstOrNull { it.item.secid == "105.TSLA" }?.quote?.changePct

        if (smh != null) hints.add(
            "半导体链：半导体ETF " + pct(smh) +
                    (if (nvda != null) "、英伟达 " + pct(nvda) else "") +
                    " → 关注 A股半导体、芯片设备、光模块、PCB"
        )
        if (tan != null) {
            hints.add(
                "新能源链：太阳能ETF " + pct(tan) +
                        (if (tsla != null) "、特斯拉 " + pct(tsla) else "") +
                        " → 关注 A股光伏、储能、锂电、汽车零部件"
            )
        }
        if (xle != null) hints.add("能源链：能源ETF " + pct(xle) + " → 关注 A股石油、煤炭、油服")
        if (hxc != null) hints.add("中概情绪：中国金龙指数 " + pct(hxc) + " → 参考港股科技与 A股互联网、消费互联网")
        val strong = secSorted.take(2).joinToString("、") { it.item.label + " " + pct(it.quote.changePct ?: 0.0) }
        if (strong.isNotEmpty()) hints.add("隔夜最强方向：" + strong + "，可作为次日 A股题材的优先观察方向")
        val weak = secSorted.takeLast(2).joinToString("、") { it.item.label + " " + pct(it.quote.changePct ?: 0.0) }
        if (weak.isNotEmpty()) hints.add("隔夜最弱方向：" + weak + "，相关 A股方向注意承压")

        if (hints.isEmpty()) sb.append("- 数据不足，暂无提示\n")
        else for (h in hints) sb.append("- ").append(h).append('\n')
        sb.append('\n')

        if (idx.isEmpty() && sec.isEmpty() && lead.isEmpty()) {
            sb.append("> 未获取到美股数据，请检查网络后重新刷新。\n")
        }
        sb.append("> 数据来自公开行情接口，美股板块以行业 ETF 代理，仅供参考，不构成投资建议。")
        return sb.toString()
    }
}
