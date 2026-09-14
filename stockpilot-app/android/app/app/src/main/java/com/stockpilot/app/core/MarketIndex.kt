package com.stockpilot.app.core

import java.util.Locale

/**
 * A股大盘指数与市场宽度统计。
 *
 * 所有 secid 均已用真实请求实测验证可返回行情（东方财富接口）：
 *   1.000001 上证指数 / 0.399001 深证成指 / 0.399006 创业板指 / 1.000688 科创50
 *   0.899050 北证50 / 1.000300 沪深300 / 1.000905 中证500 / 1.000852 中证1000
 */
object MarketIndex {

    data class Idx(val secid: String, val name: String, val tag: String)

    val ALL = listOf(
        Idx("1.000001", "上证指数", "沪市大盘"),
        Idx("0.399001", "深证成指", "深市大盘"),
        Idx("0.399006", "创业板指", "成长风格"),
        Idx("1.000688", "科创50", "硬科技"),
        Idx("0.899050", "北证50", "小微盘"),
        Idx("1.000300", "沪深300", "权重蓝筹"),
        Idx("1.000905", "中证500", "中盘"),
        Idx("1.000852", "中证1000", "小盘")
    )

    fun secids(): List<String> = ALL.map { it.secid }

    /**
     * 东方财富「概念板块」列表里混有「昨日涨停 / 融资融券 / 机构重仓」等
     * 风格或资金属性类伪板块，它们不是真实行业概念，会污染主线判断，需过滤。
     */
    private val PSEUDO_KEYWORDS = listOf(
        // 涨跌幅/连板类
        "昨日", "涨停", "跌停", "连板", "触板",
        // 股价/市值/股本风格类
        "百元股", "低价股", "高价股", "ST股", "次新", "微盘股", "高送转", "送转",
        "大盘", "中盘", "小盘", "绩优股", "亏损", "微利", "破净", "龙头股", "白马", "蓝筹", "权重",
        // 风格/估值类
        "风格", "成长", "价值", "红利", "低估值", "市盈率", "高股息", "低波",
        // 指数成份类
        "HS300", "上证50", "上证180", "深证100", "深成500", "中证", "成份", "成分",
        "标准普尔", "MSCI", "富时罗素", "标普道琼斯", "AH股",
        // 资金/持股属性类
        "融资融券", "转融券", "沪股通", "深股通", "重仓", "北向", "北上", "外资",
        "QFII", "龙虎榜", "热股", "基金持股", "基金重仓", "社保",
        // 业绩与事件类
        "预盈预增", "预亏预减", "预增", "预减", "中报", "年报", "季报", "业绩",
        "注册制", "北交所概念", "股权激励", "员工持股",
        "举牌", "解禁", "增持", "回购", "减持", "分拆", "反转股"
    )

    fun isRealSector(name: String): Boolean = PSEUDO_KEYWORDS.none { name.contains(it) }

    /** 过滤出真实行业/概念板块 */
    fun realSectors(sectors: List<Sector>): List<Sector> =
        sectors.filter { it.name.isNotEmpty() && isRealSector(it.name) }

    fun pct(v: Double): String =
        (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"

    fun price(v: Double?): String =
        if (v == null || v.isNaN()) "--" else String.format(Locale.US, "%,.2f", v)

    /** 占比取整展示，如 0.5618 -> "56%" */
    private fun fmt0(ratio: Double): String = String.format(Locale.US, "%.0f%%", ratio * 100)

    /**
     * 市场宽度。
     *
     * 个股涨跌家数取「上证指数(沪市) + 深证成指(深市) + 北证50(北交所)」三个指数节点自带的
     * f104/f105/f106（已实测为交易所口径的全市场家数），三者不重叠、不重复计算。
     * 板块涨跌家数来自概念板块全量列表。
     *
     * 注意：不要用「涨幅榜前 100 个板块」统计宽度——那批数据全为上涨板块，会得出
     * 「普涨」的错误结论（曾实测到"指数全线下跌但板块 93 涨 0 跌"的自相矛盾结果）。
     */
    data class Breadth(
        val stockUp: Int,
        val stockDown: Int,
        val stockFlat: Int,
        val sectorUp: Int,
        val sectorDown: Int,
        val sectorFlat: Int,
        val fundInSectors: Int,
        val fundOutSectors: Int
    ) {
        val stockTotal: Int get() = stockUp + stockDown + stockFlat
        val sectorTotal: Int get() = sectorUp + sectorDown + sectorFlat

        /** 是否有全市场家数数据（腾讯备用源不提供） */
        val hasMarket: Boolean get() = stockTotal > 0

        val marketUpRatio: Double
            get() = if (stockTotal == 0) 0.0 else stockUp.toDouble() / stockTotal

        val sectorUpRatio: Double
            get() = if (sectorTotal == 0) 0.0 else sectorUp.toDouble() / sectorTotal
    }

    /** 不重叠的全市场家数节点 */
    private val MARKET_NODES = listOf("1.000001", "0.399001", "0.899050")

    fun breadth(quotes: Map<String, Quote>, sectors: List<Sector>): Breadth {
        var su = 0
        var sd = 0
        var sf = 0
        for (id in MARKET_NODES) {
            val q = quotes[id] ?: continue
            su += q.advanceCount ?: 0
            sd += q.declineCount ?: 0
            sf += q.flatCount ?: 0
        }

        var up = 0
        var down = 0
        var flat = 0
        var fin = 0
        var fout = 0
        for (s in sectors) {
            when {
                s.changePct > 0.01 -> up++
                s.changePct < -0.01 -> down++
                else -> flat++
            }
            if (s.mainFund > 0) fin++ else if (s.mainFund < 0) fout++
        }
        return Breadth(su, sd, sf, up, down, flat, fin, fout)
    }

    /** 一句话定调：指数涨跌 + 风格（成长/权重）+ 市场宽度 */
    fun tone(quotes: Map<String, Quote>, breadth: Breadth): String {
        val known = ALL.mapNotNull { quotes[it.secid]?.changePct }.filter { !it.isNaN() }
        if (known.isEmpty()) return "大盘数据不足，暂无法判断（请检查网络后重新生成）"

        val upCount = known.count { it > 0.01 }
        val downCount = known.count { it < -0.01 }
        val sb = StringBuilder(
            when {
                downCount == 0 -> "主要指数全线收涨，市场情绪偏暖"
                upCount == 0 -> "主要指数全线收跌，市场情绪偏冷"
                upCount > downCount -> "指数多数上涨（" + upCount + "/" + known.size + "），多头占优"
                downCount > upCount -> "指数多数下跌（" + downCount + "/" + known.size + "），空头占优"
                else -> "指数涨跌互现，多空拉锯"
            }
        )

        // 风格：成长（创业板指 / 科创50）vs 权重（沪深300）
        val growth = average(listOfNotNull(chg(quotes, "0.399006"), chg(quotes, "1.000688")))
        val weight = chg(quotes, "1.000300")
        if (growth != null && weight != null) {
            sb.append("；风格上")
            sb.append(
                when {
                    growth - weight >= 0.5 -> "成长强于权重（创业板/科创50 跑赢沪深300），资金偏向题材成长"
                    weight - growth >= 0.5 -> "权重强于成长（沪深300 跑赢创业板/科创50），资金偏向防御与大盘股"
                    else -> "成长与权重基本同步，风格均衡"
                }
            )
        }

        // 市场宽度：优先用全市场真实涨跌家数，其次退回板块涨跌家数
        if (breadth.hasMarket) {
            sb.append("；全市场 ").append(breadth.stockUp).append(" 涨 / ")
                .append(breadth.stockDown).append(" 跌（上涨占比 ")
                .append(fmt0(breadth.marketUpRatio)).append("）")
            sb.append(
                when {
                    breadth.marketUpRatio >= 0.7 -> "，个股普涨，赚钱效应好"
                    breadth.marketUpRatio >= 0.5 -> "，涨多跌少，赚钱效应偏好"
                    breadth.marketUpRatio >= 0.3 -> "，下跌家数偏多，仅局部行情"
                    else -> "，个股普跌，注意控制仓位"
                }
            )
            // 指数与个股背离提示（常见于权重拖累指数、题材活跃的结构性行情）
            if (downCount > upCount && breadth.marketUpRatio >= 0.5) {
                sb.append("；指数走弱但多数个股上涨，属「指数弱、个股活跃」的结构性行情")
            } else if (upCount > downCount && breadth.marketUpRatio < 0.5) {
                sb.append("；指数走强但多数个股下跌，需警惕权重拉指数下的个股分歧")
            }
        } else if (breadth.sectorTotal > 0) {
            sb.append("；概念板块 ").append(breadth.sectorUp).append(" 涨 / ")
                .append(breadth.sectorDown).append(" 跌")
            sb.append(
                when {
                    breadth.sectorUpRatio >= 0.7 -> "，板块普涨格局"
                    breadth.sectorUpRatio >= 0.5 -> "，涨多跌少"
                    breadth.sectorUpRatio >= 0.3 -> "，涨跌分化，结构性行情"
                    else -> "，普跌格局，注意控制仓位"
                }
            )
        }

        val star = chg(quotes, "1.000688")
        if (star != null && star <= -2.0) sb.append("；科创50 跌幅较大，硬科技方向明显承压")

        return sb.toString()
    }

    private fun chg(quotes: Map<String, Quote>, secid: String): Double? =
        quotes[secid]?.changePct?.takeIf { !it.isNaN() }

    private fun average(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size

    /** 给报告用的指数明细行（纯文本，中文不做等宽对齐） */
    fun lines(quotes: Map<String, Quote>): List<String> =
        ALL.mapNotNull { idx ->
            val q = quotes[idx.secid] ?: return@mapNotNull null
            val c = q.changePct ?: 0.0
            "- " + idx.name + "  " + price(q.price) + "  " + pct(c) + "  ·" + idx.tag
        }
}
