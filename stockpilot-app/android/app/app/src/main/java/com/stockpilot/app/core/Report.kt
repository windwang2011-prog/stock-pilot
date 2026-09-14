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

/** 盘后汇总报告生成（对应 core/report.ts） */
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

    fun buildDaily(date: String, results: List<WatchResult>, phase: PhaseInfo): String {
        val buy = results.filter { it.signal.action == "买入" }
        val sell = results.filter { it.signal.action == "卖出" }
        val sorted = results.sortedByDescending { it.changePct }
        val topUp = sorted.take(5)
        val topDown = sorted.takeLast(5).reversed().filter { it !in topUp }

        val sb = StringBuilder()
        sb.append("# StockPilot 盘后汇总 ").append(date).append('\n').append('\n')
        sb.append("市场阶段：").append(phase.label).append("（").append(phase.note).append("）\n")
        sb.append("自选数量：").append(results.size)
            .append("｜买入信号：").append(buy.size)
            .append("｜卖出信号：").append(sell.size).append('\n').append('\n')

        if (buy.isNotEmpty()) {
            sb.append("## 买入信号\n")
            for (r in buy) {
                sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(") 现价 ")
                    .append(String.format(Locale.US, "%.2f", r.price)).append(' ').append(fmtPct(r.changePct))
                    .append("｜评分 ").append(r.signal.score)
                    .append("｜主力 ").append(fmtMoney(r.mainNet)).append('\n')
                sb.append("  依据：").append(r.signal.sessionSummary).append('\n')
                val tech = r.signal.reasons.filter { !it.startsWith("【") }.take(2).joinToString("；")
                if (tech.isNotEmpty()) sb.append("  技术面：").append(tech).append('\n')
            }
            sb.append('\n')
        }

        if (sell.isNotEmpty()) {
            sb.append("## 卖出信号\n")
            for (r in sell) {
                sb.append("- ").append(r.stock.name).append("(").append(r.stock.code).append(") 现价 ")
                    .append(String.format(Locale.US, "%.2f", r.price)).append(' ').append(fmtPct(r.changePct))
                    .append("｜评分 ").append(r.signal.score)
                    .append("｜主力 ").append(fmtMoney(r.mainNet)).append('\n')
                sb.append("  依据：").append(r.signal.sessionSummary).append('\n')
            }
            sb.append('\n')
        }

        sb.append("## 自选表现（涨幅前五）\n")
        for (r in topUp) {
            sb.append("- ").append(r.stock.name).append(' ').append(fmtPct(r.changePct))
                .append("｜评分 ").append(r.signal.score).append('｜').append(r.signal.action).append('\n')
        }
        if (topDown.isNotEmpty()) {
            sb.append('\n').append("## 自选表现（跌幅前五）\n")
            for (r in topDown) {
                sb.append("- ").append(r.stock.name).append(' ').append(fmtPct(r.changePct))
                    .append("｜评分 ").append(r.signal.score).append('｜').append(r.signal.action).append('\n')
            }
        }
        sb.append('\n').append("> 数据来自公开行情接口，策略为技术指标分析，仅供参考，不构成投资建议。")
        return sb.toString()
    }

    /** 用于通知栏的一行摘要 */
    fun summaryLine(results: List<WatchResult>): String {
        val buy = results.count { it.signal.action == "买入" }
        val sell = results.count { it.signal.action == "卖出" }
        return "自选 ${results.size}｜买入 $buy｜卖出 $sell"
    }
}
