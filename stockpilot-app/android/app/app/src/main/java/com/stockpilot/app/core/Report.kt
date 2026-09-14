package com.stockpilot.app.core

import java.util.Locale

/** 一次盯盘扫描的单只股票结果（含通知决策） */
data class WatchResult(
    val stock: StockRef,
    val signal: Signal,
    val price: Double,
    val changePct: Double,
    val mainNet: Double?,
    val notify: Boolean,
    val notifyTitle: String,
    val notifyBody: String
)

/** 龙头股一行（所属热门板块 + 个股分析结果） */
data class LeaderRow(
    val sectorName: String,
    val sectorChangePct: Double,
    val result: WatchResult
)

/**
 * 报告中列举的个股（用于 App 内「＋关注」入口）。
 * group 形如「推荐」「龙头·PCB」，便于界面分组展示。
 */
data class ReportStock(
    val secid: String,
    val code: String,
    val name: String,
    val group: String,
    val score: Int,
    val action: String,
    val price: Double?,
    val changePct: Double
)

/** 生成一份完整报告所需的全部素材 */
class ReportInput(
    val date: String,
    val timeLabel: String,
    val phase: PhaseInfo,
    val indexQuotes: Map<String, Quote>,
    val sectors: List<Sector>,
    val leaders: List<LeaderRow>,
    val recos: List<WatchResult>,
    val streaks: Map<String, Streak>,
    val watch: List<WatchResult>,
    val historyDays: Int = 0,
    val notes: List<String> = emptyList()
)

/**
 * 每日报告生成。
 *
 * 结构（自大盘到个股，逐层收敛）：
 *   一、大盘概览        —— 指数定调 + 市场宽度 + 资金合计
 *   二、热点板块        —— 领涨/领跌榜 + 资金流榜 + 主线判断
 *   三、龙头个股        —— 按热门板块分组的板块龙头
 *   四、推荐个股        —— 信号连续性排行（连续 N 天建议买入）+ 今日推荐
 *   五、我的关注        —— 自选股逐只分析
 *   六、投资建议        —— 市场 / 板块 / 个股 三层结论 + 风险提示
 */
object Report {

    fun fmtMoney(v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        val a = Math.abs(v)
        val sign = if (v > 0) "+" else if (v < 0) "-" else ""
        return when {
            a >= 1e8 -> sign + String.format(Locale.US, "%.2f", a / 1e8) + "亿"
            a >= 1e4 -> sign + String.format(Locale.US, "%.1f", a / 1e4) + "万"
            else -> sign + String.format(Locale.US, "%.0f", a)
        }
    }

    fun fmtPct(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "--"
        return (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"
    }

    fun fmtPrice(v: Double?): String =
        if (v == null || v.isNaN()) "--" else String.format(Locale.US, "%.2f", v)

    private fun fmt0(ratio: Double): String = String.format(Locale.US, "%.0f%%", ratio * 100)

    // =====================================================================

    fun build(input: ReportInput): String {
        val sb = StringBuilder()
        val recos = input.recos
        val watch = input.watch
        val streaks = SignalHistory.ranked(input.streaks, 2)
        val breadth = MarketIndex.breadth(input.indexQuotes, input.sectors)
        val tone = MarketIndex.tone(input.indexQuotes, breadth)

        // ---------- 抬头 ----------
        sb.append("# StockPilot 每日报告 ").append(input.date).append('\n')
        sb.append("生成时间：").append(input.timeLabel).append("（北京时间）｜市场阶段：")
            .append(input.phase.label).append("（").append(input.phase.note).append("）\n")
        sb.append("自选 ").append(watch.size).append(" 只｜推荐 ").append(recos.size)
            .append(" 只｜龙头 ").append(input.leaders.size).append(" 只｜信号历史 ")
            .append(input.historyDays).append(" 个交易日\n")
        if (input.notes.isNotEmpty()) {
            sb.append("\n⚠ 数据提示：")
            for ((i, n) in input.notes.withIndex()) {
                if (i > 0) sb.append("；")
                sb.append(n)
            }
            sb.append('\n')
        }
        sb.append('\n')

        buildMarket(sb, input, breadth, tone)
        buildSectors(sb, input)
        buildLeaders(sb, input)
        buildRecos(sb, input, streaks)
        buildWatch(sb, input)
        buildAdvice(sb, input, breadth, streaks)

        sb.append("\n> 数据来自公开行情接口（东方财富为主，腾讯备用）。策略基于技术指标与公开资金数据的量化分析，")
        sb.append("不含基本面尽调，仅供参考，不构成投资建议。\n")
        return sb.toString()
    }

    // ---------------- 一、大盘概览 ----------------
    private fun buildMarket(sb: StringBuilder, input: ReportInput, breadth: MarketIndex.Breadth, tone: String) {
        sb.append("## 一、大盘概览\n")
        val lines = MarketIndex.lines(input.indexQuotes)
        if (lines.isEmpty()) sb.append("- 未获取到大盘指数数据\n")
        else for (l in lines) sb.append(l).append('\n')

        sb.append("\n**定调**：").append(tone).append('\n')
        if (breadth.hasMarket) {
            sb.append("**市场宽度**：全市场 ").append(breadth.stockUp).append(" 涨 / ")
                .append(breadth.stockDown).append(" 跌 / ").append(breadth.stockFlat)
                .append(" 平（共 ").append(breadth.stockTotal).append(" 只）｜上涨占比 ")
                .append(fmt0(breadth.marketUpRatio)).append('\n')
        }
        if (breadth.sectorTotal > 0) {
            sb.append("**概念板块**：").append(breadth.sectorUp).append(" 涨 / ")
                .append(breadth.sectorDown).append(" 跌 / ").append(breadth.sectorFlat)
                .append(" 平（共 ").append(breadth.sectorTotal).append(" 个，已剔除风格/指数类）")
                .append("｜主力净流入板块 ").append(breadth.fundInSectors)
                .append(" 个 / 净流出 ").append(breadth.fundOutSectors).append(" 个\n")
        }
        sb.append('\n')
    }

    // ---------------- 二、热点板块 ----------------
    private fun buildSectors(sb: StringBuilder, input: ReportInput) {
        sb.append("## 二、热点板块\n")
        val all = input.sectors
        if (all.isEmpty()) {
            sb.append("- 未获取到板块数据，本次跳过板块分析\n\n")
            return
        }
        val byPct = all.sortedByDescending { it.changePct }
        val byHot = all.sortedByDescending { hotness(it) }

        sb.append("**领涨板块（按涨幅）**\n")
        for (s in byPct.take(8)) sb.append(sectorLine(s)).append('\n')

        sb.append("**资金净流入前列**\n")
        for (s in byPct.sortedByDescending { it.mainFund }.take(5)) sb.append(sectorLine(s)).append('\n')

        sb.append("**资金净流出前列**\n")
        for (s in byPct.sortedBy { it.mainFund }.take(5)) sb.append(sectorLine(s)).append('\n')

        val downCount = minOf(5, byPct.size / 2)
        if (downCount > 0) {
            sb.append("**领跌板块**\n")
            for (s in byPct.takeLast(downCount).reversed()) sb.append(sectorLine(s)).append('\n')
        }

        sb.append("\n**主线判断**：")
        val line = byHot.filter { it.changePct > 0 }.take(2)
        if (line.isEmpty()) {
            sb.append("今日所有热点的板块均未收红，无明显主线，资金以避险为主\n")
        } else {
            sb.append("最强方向为 ").append(line.joinToString("、") { it.name + " " + fmtPct(it.changePct) })
            val fundOk = line.count { it.mainFund > 0 }
            sb.append(
                if (fundOk == line.size) "，且主力资金同步净流入，属于有资金承接的真实主线"
                else if (fundOk == 0) "，但主力资金并未流入，需警惕一日游"
                else "，资金面出现分歧，追高需谨慎"
            )
            sb.append('\n')
        }
        sb.append('\n')
    }

    private fun hotness(s: Sector): Double = Strategy.sectorHotness(s.changePct, s.mainFund, s.turnover)

    private fun sectorLine(s: Sector): String {
        val sb = StringBuilder()
        sb.append("- ").append(s.name).append("  ").append(fmtPct(s.changePct))
            .append("  主力 ").append(fmtMoney(s.mainFund))
        if (s.mainPct != 0.0) sb.append("（净占比 ").append(fmtPct(s.mainPct)).append("）")
        sb.append("  换手 ").append(String.format(Locale.US, "%.2f", s.turnover)).append("%")
        if (s.upCount + s.downCount > 0) {
            sb.append("  内部分化 ").append(s.upCount).append("涨/").append(s.downCount).append("跌")
        }
        if (s.leaderName.isNotEmpty()) {
            sb.append("  领涨 ").append(s.leaderName)
            if (s.leaderCode.isNotEmpty()) sb.append("(").append(s.leaderCode).append(")")
        }
        return sb.toString()
    }

    // ---------------- 三、龙头个股 ----------------
    private fun buildLeaders(sb: StringBuilder, input: ReportInput) {
        sb.append("## 三、龙头个股（按热门板块分组）\n")
        if (input.leaders.isEmpty()) {
            sb.append("- 未获取到龙头个股数据，本次跳过\n\n")
            return
        }
        var current = ""
        for (l in input.leaders) {
            if (l.sectorName != current) {
                current = l.sectorName
                sb.append("【").append(l.sectorName).append(" ").append(fmtPct(l.sectorChangePct)).append("】\n")
            }
            val r = l.result
            sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(")  ")
                .append(fmtPrice(r.price)).append("  ").append(fmtPct(r.changePct))
                .append("  评分 ").append(r.signal.score).append("  ").append(r.signal.action)
            if (r.mainNet != null) sb.append("  主力 ").append(fmtMoney(r.mainNet))
            sb.append('\n')
        }
        sb.append('\n')
    }

    // ---------------- 四、推荐个股 · 连续性排行 ----------------
    private fun buildRecos(sb: StringBuilder, input: ReportInput, streaks: List<Streak>) {
        sb.append("## 四、推荐个股 · 信号连续性排行\n")

        if (streaks.isNotEmpty()) {
            sb.append("**连续看好（同一建议 ≥2 个交易日）**\n")
            for (s in streaks) {
                sb.append("- ").append(s.name).append("(").append(codeOf(s.secid)).append(")  ")
                    .append(s.label)
                val g = s.gainPct
                if (g != null) sb.append("  ｜区间 ").append(fmtPct(g))
                sb.append("  ｜最新评分 ").append(s.score)
                sb.append('\n')
            }
            sb.append('\n')
        } else if (input.historyDays >= 2) {
            sb.append("**连续看好（同一建议 ≥2 个交易日）**\n- 暂无个股连续 2 天以上保持同一建议\n\n")
        } else {
            sb.append("**连续看好（同一建议 ≥2 个交易日）**\n- 信号历史仅 ")
                .append(input.historyDays)
                .append(" 个交易日，连续天数需多累积几天才有参考价值\n\n")
        }

        sb.append("**今日推荐（按综合评分）**\n")
        if (input.recos.isEmpty()) {
            sb.append("- 暂无推荐个股（板块或行情数据不足）\n\n")
            return
        }
        for (r in input.recos.take(15)) {
            val st = input.streaks[r.stock.secid]
            sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(")  ")
                .append(fmtPrice(r.price)).append("  ").append(fmtPct(r.changePct))
                .append("  ｜评分 ").append(r.signal.score)
                .append("  ｜").append(r.signal.action)
                .append("  ｜主力 ").append(fmtMoney(r.mainNet))
            if (st != null && st.days >= 2) sb.append("  ｜").append(st.label)
            sb.append('\n')
            val summary = r.signal.sessionSummary
            if (summary.isNotEmpty()) sb.append("    依据：").append(summary).append('\n')
        }
        sb.append('\n')
    }

    // ---------------- 五、我的关注 ----------------
    private fun buildWatch(sb: StringBuilder, input: ReportInput) {
        sb.append("## 五、我的关注（自选股分析）\n")
        val watch = input.watch
        if (watch.isEmpty()) {
            sb.append("- 未设置自选股。可在 App「自选」页搜索添加，添加后本报告会自动包含自选股分析\n\n")
            return
        }
        val buy = watch.filter { it.signal.action == "买入" }
        val sell = watch.filter { it.signal.action == "卖出" || it.signal.action == "减仓" }
        val buyIds = buy.map { it.stock.secid }.toHashSet()
        val sellIds = sell.map { it.stock.secid }.toHashSet()
        val other = watch.filter { it.stock.secid !in buyIds && it.stock.secid !in sellIds }

        if (buy.isNotEmpty()) {
            sb.append("**买入信号（").append(buy.size).append("）**\n")
            for (r in buy) sb.append(detail(r, true)).append('\n')
            sb.append('\n')
        }
        if (sell.isNotEmpty()) {
            sb.append("**卖出 / 减仓信号（").append(sell.size).append("）**\n")
            for (r in sell) sb.append(detail(r, false)).append('\n')
            sb.append('\n')
        }
        if (other.isNotEmpty()) {
            sb.append("**持有 / 观察（").append(other.size).append("）**\n")
            for (r in other) {
                sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(")  ")
                    .append(fmtPrice(r.price)).append("  ").append(fmtPct(r.changePct))
                    .append("  ｜评分 ").append(r.signal.score)
                    .append("  ｜").append(r.signal.action)
                    .append("  ｜主力 ").append(fmtMoney(r.mainNet)).append('\n')
            }
            sb.append('\n')
        }
    }

    private fun detail(r: WatchResult, withLevels: Boolean): String {
        val sb = StringBuilder()
        sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(")  ")
            .append(fmtPrice(r.price)).append("  ").append(fmtPct(r.changePct))
            .append("  ｜评分 ").append(r.signal.score)
            .append("  ｜").append(r.signal.action)
            .append("  ｜主力 ").append(fmtMoney(r.mainNet)).append('\n')
        sb.append("    依据：").append(r.signal.sessionSummary).append('\n')
        val tech = r.signal.reasons.filter { !it.startsWith("【") }.take(2).joinToString("；")
        if (tech.isNotEmpty()) sb.append("    技术面：").append(tech).append('\n')
        if (withLevels && r.price > 0) {
            sb.append("    参考位：入场 ").append(fmtPrice(r.signal.entry))
                .append("　止损 ").append(fmtPrice(r.signal.stop))
                .append("　目标 ").append(fmtPrice(r.signal.target)).append('\n')
        }
        return sb.toString()
    }

    // ---------------- 六、投资建议 ----------------
    private fun buildAdvice(
        sb: StringBuilder,
        input: ReportInput,
        breadth: MarketIndex.Breadth,
        streaks: List<Streak>
    ) {
        sb.append("## 六、投资建议\n")

        sb.append("**市场层面**\n")
        for (a in marketAdvice(input, breadth)) sb.append("- ").append(a).append('\n')

        sb.append("\n**板块层面**\n")
        for (a in sectorAdvice(input)) sb.append("- ").append(a).append('\n')

        sb.append("\n**个股层面**\n")
        for (a in stockAdvice(input, streaks)) sb.append("- ").append(a).append('\n')

        sb.append("\n**风险提示**\n")
        for (a in riskNotes(input, breadth)) sb.append("- ").append(a).append('\n')
    }

    private fun marketAdvice(input: ReportInput, breadth: MarketIndex.Breadth): List<String> {
        val out = ArrayList<String>()
        val known = MarketIndex.ALL.mapNotNull { input.indexQuotes[it.secid]?.changePct }
        if (known.isEmpty()) {
            out.add("大盘数据缺失，建议先修复网络并重新生成报告，再据此决策")
            return out
        }
        val upCount = known.count { it > 0.01 }
        val marketOk = breadth.hasMarket && breadth.marketUpRatio >= 0.5
        out.add(
            when {
                upCount >= 6 -> "指数普遍上行，仓位可适度积极（参考 6~7 成），优先回踩机会，避免盘中追高"
                upCount >= 4 -> "指数偏强，仓位可中性偏积极（5~6 成），围绕强势板块做右侧跟进"
                upCount >= 2 -> "指数分化，仓位建议中性（4~5 成），只做结构性机会，不做全面加仓"
                marketOk -> "指数偏弱但多数个股上涨，属「指数弱、个股活跃」的结构性行情，仓位建议中性（4~5 成），聚焦强势方向而非全面降仓"
                else -> "指数偏弱，仓位建议降低（3 成以内），以防守、控制回撤为主"
            }
        )
        if (breadth.hasMarket) {
            val r = breadth.marketUpRatio
            out.add(
                "全市场上涨占比 " + fmt0(r) + "（" + breadth.stockUp + " 涨 / " + breadth.stockDown + " 跌），" +
                        when {
                            r >= 0.7 -> "个股普涨，可持股为主，但需注意高位股的兑现节奏"
                            r >= 0.5 -> "涨多跌少、赚钱效应尚可，宜持股、逢强切换"
                            r >= 0.35 -> "下跌家数偏多、赚钱效应不足，建议减少分散持仓，只做强势方向"
                            else -> "个股普跌，建议降低仓位、等待缩量止跌信号"
                        }
            )
        } else if (breadth.sectorTotal > 0) {
            val r = breadth.sectorUpRatio
            out.add(
                "概念板块 " + breadth.sectorUp + " 涨 / " + breadth.sectorDown + " 跌，" + when {
                    r >= 0.7 -> "板块普涨，可持股为主"
                    r >= 0.5 -> "涨多跌少、赚钱效应尚可，宜持股、逢强切换"
                    r >= 0.3 -> "分化明显、资金集中在少数方向，建议减少分散持仓"
                    else -> "普跌、题材轮动加快，建议降低仓位、等待缩量止跌信号"
                }
            )
        }
        if (breadth.fundInSectors + breadth.fundOutSectors > 0) {
            val ratio = breadth.fundInSectors.toDouble() /
                    (breadth.fundInSectors + breadth.fundOutSectors)
            out.add(
                "主力资金：净流入板块 " + breadth.fundInSectors + " 个 / 净流出 " +
                        breadth.fundOutSectors + " 个（" + fmt0(ratio) + " 的板块获资金净流入），" +
                        if (ratio >= 0.5) "资金面偏多，可适度乐观" else "资金面偏谨慎，注意控制仓位"
            )
        }
        return out
    }

    private fun sectorAdvice(input: ReportInput): List<String> {
        val out = ArrayList<String>()
        val all = input.sectors
        if (all.isEmpty()) {
            out.add("板块数据缺失，本次不做板块建议")
            return out
        }
        val byHot = all.sortedByDescending { hotness(it) }.filter { it.changePct > 0 }.take(3)
        if (byHot.isNotEmpty()) {
            out.add(
                "当前主线：" + byHot.joinToString("、") {
                    it.name + "(" + fmtPct(it.changePct) + "，主力 " + fmtMoney(it.mainFund) + ")"
                } + "；建议优先在主线板块内选股，回避无资金承接的跟风板块"
            )
        } else {
            out.add("今日无收红的强势板块，主线真空期，建议降低题材参与度")
        }
        val inflow = all.sortedByDescending { it.mainFund }.take(3).filter { it.mainFund > 0 }
        if (inflow.isNotEmpty()) {
            out.add(
                "资金净流入居前：" + inflow.joinToString("、") { it.name + " " + fmtMoney(it.mainFund) } +
                        "，可作为次日重点观察方向"
            )
        }
        val outflow = all.sortedBy { it.mainFund }.take(3).filter { it.mainFund < 0 }
        if (outflow.isNotEmpty()) {
            out.add(
                "资金净流出居前：" + outflow.joinToString("、") { it.name + " " + fmtMoney(it.mainFund) } +
                        "，相关个股反弹力度通常有限，宜回避"
            )
        }
        return out
    }

    private fun stockAdvice(input: ReportInput, streaks: List<Streak>): List<String> {
        val out = ArrayList<String>()

        val strong = streaks.filter { it.action == "买入" && it.days >= 2 && it.score >= 70 }
        if (strong.isNotEmpty()) {
            out.add(
                "重点关注：" + strong.take(5).joinToString("、") { it.name + "(" + it.label + "，评分 " + it.score + ")" } +
                        " —— 建议在回踩 5/10 日均线时低吸，不追高"
            )
        }

        val dip = input.recos.filter { it.signal.action == "逢低关注" }.take(5)
        if (dip.isNotEmpty()) {
            out.add(
                "可低吸观察：" + dip.joinToString("、") { it.stock.name + "(" + it.signal.score + " 分)" } +
                        " —— 等回调至支撑位再考虑，破位则放弃"
            )
        }

        val weak = input.recos.filter { it.signal.action == "卖出" }.take(5)
        if (weak.isNotEmpty()) {
            out.add(
                "建议回避 / 减仓：" + weak.joinToString("、") { it.stock.name + "(" + it.signal.score + " 分)" } +
                        " —— 均为资金净流出且技术面走弱"
            )
        }

        val watchSell = input.watch.filter { it.signal.action == "卖出" || it.signal.action == "减仓" }
        if (watchSell.isNotEmpty()) {
            out.add(
                "自选提示：自选股 " + watchSell.joinToString("、") { it.stock.name } +
                        " 出现卖出/减仓信号，建议按参考位减仓或离场"
            )
        }
        val watchBuy = input.watch.filter { it.signal.action == "买入" }
        if (watchBuy.isNotEmpty()) {
            out.add(
                "自选提示：自选股 " + watchBuy.joinToString("、") { it.stock.name } +
                        " 为买入信号，可结合上文「参考位」的入场/止损执行"
            )
        }

        if (out.isEmpty()) out.add("当前无明确分级机会，建议以观望为主，等待信号明确")
        return out
    }

    private fun riskNotes(input: ReportInput, breadth: MarketIndex.Breadth): List<String> {
        val out = ArrayList<String>()
        out.add("A股为 T+1 交易，当日买入次日才可卖出；量化信号存在滞后，请结合大盘环境分批执行")
        val weakRatio = if (breadth.hasMarket) breadth.marketUpRatio else breadth.sectorUpRatio
        if (breadth.hasMarket || breadth.sectorTotal > 0) {
            if (weakRatio < 0.3) {
                out.add("当前个股/板块普跌、题材轮动加快，追高风险大，建议等待缩量止跌后再介入")
            }
        }
        val star = input.indexQuotes["1.000688"]?.changePct
        if (star != null && star <= -2.0) out.add("科创50 单日跌幅较大，硬科技与高位成长股波动加剧，注意仓位与止损")
        val cyb = input.indexQuotes["0.399006"]?.changePct
        if (cyb != null && cyb <= -2.0) out.add("创业板指跌幅较大，成长股情绪偏弱，短线不宜重仓")
        if (input.historyDays < 3) {
            out.add("信号历史不足 3 个交易日，「连续 N 天」的统计尚不稳定，需持续运行后再参考")
        }
        out.add("主力资金为公开接口的估算口径，非真实机构持仓；本报告不构成投资建议")
        return out
    }

    private fun codeOf(secid: String): String =
        if (secid.contains('.')) secid.substringAfter('.') else secid

    /**
     * 汇总报告中涉及的个股，供 App 内「＋关注」使用。
     * 推荐个股优先，龙头股补充；按 secid 去重（同一只股票只出现一次）。
     */
    fun stockRows(recos: List<WatchResult>, leaders: List<LeaderRow>): List<ReportStock> {
        val out = ArrayList<ReportStock>()
        val seen = HashSet<String>()

        fun add(r: WatchResult, group: String) {
            val sid = r.stock.secid
            if (sid.isEmpty() || !seen.add(sid)) return
            out.add(
                ReportStock(
                    secid = sid,
                    code = r.stock.code,
                    name = r.stock.name,
                    group = group,
                    score = r.signal.score,
                    action = r.signal.action,
                    price = r.price,
                    changePct = r.changePct
                )
            )
        }

        for (r in recos) add(r, "推荐")
        for (l in leaders) add(l.result, "龙头·" + l.sectorName)
        return out
    }

    /** 用于通知栏的一行摘要 */
    fun summaryLine(results: List<WatchResult>): String {
        if (results.isEmpty()) return "大盘/板块/龙头概览"
        val buy = results.count { it.signal.action == "买入" }
        val sell = results.count { it.signal.action == "卖出" }
        return "自选 ${results.size}｜买入 $buy｜卖出 $sell"
    }
}
