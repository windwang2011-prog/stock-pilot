package com.stockpilot.app.core

import java.util.Locale

/** 资金流向（单位：元；正数=净流入，负数=净流出） */
data class FundFlow(
    val main: Double,
    val small: Double,
    val medium: Double,
    val large: Double,
    val superLarge: Double,
    val mainPct: Double
)

/** 单个市场的成交与资金 */
data class MarketAmount(
    val name: String,
    val secid: String,
    val amount: Double = 0.0,      // 成交额（元）
    val volume: Double = 0.0,      // 成交量（手）
    val main: Double = 0.0,        // 主力净额（元）
    val superLarge: Double = 0.0,  // 超大单净额
    val large: Double = 0.0,       // 大单净额
    val medium: Double = 0.0,      // 中单净额
    val small: Double = 0.0,       // 小单净额
    val mainPct: Double = 0.0      // 主力净占比 %
)

/**
 * 某日全市场成交与资金快照（沪市 + 深市 + 北交所，三者互不重叠）。
 * 说明：创业板指、科创50 是深/沪的子集，不可再计入，否则重复计算。
 */
data class MarketSnapshot(val date: String, val markets: List<MarketAmount>) {

    val hasData: Boolean get() = markets.any { it.amount > 0 || it.main != 0.0 }

    val amountTotal: Double get() = sum { it.amount }
    val volumeTotal: Double get() = sum { it.volume }
    val mainTotal: Double get() = sum { it.main }
    val superLargeTotal: Double get() = sum { it.superLarge }
    val largeTotal: Double get() = sum { it.large }
    val mediumTotal: Double get() = sum { it.medium }
    val smallTotal: Double get() = sum { it.small }

    private fun sum(f: (MarketAmount) -> Double): Double {
        var t = 0.0
        for (m in markets) t += f(m)
        return t
    }

    fun byName(n: String): MarketAmount? = markets.firstOrNull { it.name == n }
}

/**
 * 全市场成交与资金统计（供报告「大盘概览」使用）。
 *
 * 数据来源（均已实测验证）：
 *   成交额/成交量：指数节点 1.000001（沪市）、0.399001（深市）、0.899050（北交所）的 f6 / f5
 *   资金流：fflow/daykline，主力=f52、小单=f53、中单=f54、大单=f55、超大单=f56、主力净占比=f57
 *          （校验：主力 = 大单 + 超大单）
 * 注意：指数资金流接口只返回当日，跨日对比依赖本地快照。
 */
object MarketStat {

    /** 三个互不重叠的市场口径（名称、secid、指数名） */
    val MARKETS: List<Triple<String, String, String>> = listOf(
        Triple("沪市", "1.000001", "上证指数"),
        Triple("深市", "0.399001", "深证成指"),
        Triple("北交所", "0.899050", "北证50")
    )

    fun secids(): List<String> = MARKETS.map { it.second }

    // ---------------- 格式化 ----------------

    /** 金额按 万亿 / 亿 / 万 展示（取绝对值，正负由调用方用措辞表达） */
    fun yi(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1e12 -> String.format(Locale.US, "%,.2f", a / 1e12) + "万亿"
            a >= 1e8 -> String.format(Locale.US, "%,.2f", a / 1e8) + "亿"
            a >= 1e4 -> String.format(Locale.US, "%,.1f", a / 1e4) + "万"
            else -> String.format(Locale.US, "%,.0f", a)
        }
    }

    /** 金额带正负号（+1.20亿 / -3.40亿） */
    fun signedYi(v: Double): String =
        (if (v > 0) "+" else if (v < 0) "-" else "") + yi(v)

    /** 成交量（手）按 亿手 / 万手 展示 */
    fun hand(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1e8 -> String.format(Locale.US, "%,.2f", a / 1e8) + "亿手"
            a >= 1e4 -> String.format(Locale.US, "%,.1f", a / 1e4) + "万手"
            else -> String.format(Locale.US, "%,.0f", a) + "手"
        }
    }

    /** 百分比带正负号（一位小数） */
    fun signedPct(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "--"
        val sign = if (v > 0) "+" else if (v < 0) "-" else ""
        return sign + String.format(Locale.US, "%.1f", Math.abs(v)) + "%"
    }

    /** 百分比（两位小数，不带正负号处理） */
    fun pct(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "--"
        return String.format(Locale.US, "%.1f", v) + "%"
    }

    private fun perMarketAmount(s: MarketSnapshot): String =
        s.markets.joinToString(" / ") { it.name + " " + yi(it.amount) }

    private fun perMarketVolume(s: MarketSnapshot): String =
        s.markets.joinToString(" / ") { it.name + " " + hand(it.volume) }

    private fun perMarketMain(s: MarketSnapshot): String =
        s.markets.joinToString(" / ") { it.name + " " + signedYi(it.main) + "(" + signedPct(it.mainPct) + ")" }

    // ---------------- 报告行 ----------------

    /**
     * 生成「成交与资金」明细行。
     * @param cur  当日快照
     * @param prev 上一交易日的快照（可为 null，此时不显示环比）
     */
    fun lines(cur: MarketSnapshot?, prev: MarketSnapshot?): List<String> {
        if (cur == null || !cur.hasData) return emptyList()
        val out = ArrayList<String>()
        val hasPrev = prev != null && prev.hasData

        // ① 成交额
        val a = StringBuilder("- 全市场成交额 ").append(yi(cur.amountTotal))
            .append("（").append(perMarketAmount(cur)).append("）")
        if (hasPrev && prev.amountTotal > 0) {
            val d = cur.amountTotal - prev.amountTotal
            a.append("；较上一交易日 ").append(signedYi(d))
                .append("（").append(signedPct(d / prev.amountTotal * 100)).append("）")
        }
        out.add(a.toString())

        // ② 成交量
        out.add("- 全市场成交量 " + hand(cur.volumeTotal) + "（" + perMarketVolume(cur) + "）")

        // ③ 主力资金进出（分市场）
        val inflow = cur.mainTotal >= 0
        val m = StringBuilder("- 全市场主力资金净")
            .append(if (inflow) "流入 " else "流出 ")
            .append(yi(cur.mainTotal))
            .append("（").append(perMarketMain(cur)).append("）")
        if (hasPrev) {
            m.append("；较上一交易日 ").append(signedYi(cur.mainTotal - prev.mainTotal))
        }
        out.add(m.toString())

        // ④ 资金结构
        out.add(
            "- 资金结构（全市场）：超大单 " + signedYi(cur.superLargeTotal) +
                    "｜大单 " + signedYi(cur.largeTotal) +
                    "｜中单 " + signedYi(cur.mediumTotal) +
                    "｜小单 " + signedYi(cur.smallTotal)
        )

        // ⑤ 全市场资金净流入/流出家数（用板块口径近似，避免抓取全部个股）
        val single = cur.byName("沪市")
        if (single != null && single.mainPct != 0.0) {
            out.add("- 沪市主力净占比 " + signedPct(single.mainPct) + "，可作为大盘资金力度的参考")
        }
        return out
    }

    /**
     * 主力资金连续净流入 / 净流出天数（含当日；需要连续日期的快照）。
     * @param prevDays 历史快照（不含当日），顺序不限
     */
    fun mainStreak(cur: MarketSnapshot?, prevDays: List<MarketSnapshot>): String? {
        if (cur == null || !cur.hasData) return null
        val curIn = cur.mainTotal >= 0
        var days = 1
        for (s in prevDays.sortedByDescending { it.date }) {
            if (!s.hasData) break
            if ((s.mainTotal >= 0) == curIn) days++ else break
        }
        return if (days >= 2) "连续 " + days + " 日主力净" + (if (curIn) "流入" else "流出") else null
    }
}
