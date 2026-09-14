package com.stockpilot.app.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 盯盘引擎：交易时段判定 + 批量扫描 + 通知决策（动作变化去重）。
 * 注意：scan() 为阻塞调用，请在 IO 线程上执行（UI 用 Dispatchers.IO，服务用后台线程）。
 */
class Engine(private val ds: DataSource, private val store: Store) {

    /** 是否处于需盯盘的时段（集合竞价 / 盘中 / 午间） */
    fun shouldScan(nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = Strategy.marketPhase(nowMs)
        return p.phase == "auction" || p.phase == "intraday" || p.phase == "lunch"
    }

    /** 是否到达盘后汇总时间（15:30-16:00 北京时间） */
    fun isReportWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = Strategy.marketPhase(nowMs)
        if (p.phase != "post" && p.phase != "after") return false
        val hm = bjHourMinute(nowMs)
        return hm >= 1530 && hm < 1600
    }

    /** 今天（北京时间）yyyy-MM-dd */
    fun today(nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun bjHourMinute(nowMs: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        return cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
    }

    /** 扫描一批自选股，返回含通知决策的结果（按评分降序） */
    fun scan(stocks: List<StockRef>, nowMs: Long = System.currentTimeMillis()): List<WatchResult> {
        if (stocks.isEmpty()) return emptyList()
        val phase = Strategy.marketPhase(nowMs)

        val quotes = try {
            ds.getQuotes(stocks.map { it.secid })
        } catch (e: Exception) {
            emptyList()
        }
        val qmap = HashMap<String, Quote>()
        for (q in quotes) qmap[q.secid] = q

        val lastActions = store.loadLastActions()
        val out = ArrayList<WatchResult>()

        for (st in stocks) {
            try {
                val klines = ds.getKlines(st.secid, 120, 101)
                if (klines.isEmpty()) continue
                val q = qmap[st.secid]
                val sig = Strategy.sessionAnalyze(klines, q, phase)
                val price = q?.price ?: klines[klines.size - 1].close
                val chg = q?.changePct ?: 0.0

                val prev = lastActions[st.secid]
                val notify = prev != sig.action && (sig.action == "买入" || sig.action == "卖出")
                lastActions[st.secid] = sig.action

                out.add(
                    WatchResult(
                        stock = st,
                        signal = sig,
                        price = price,
                        changePct = chg,
                        mainNet = sig.metrics.mainNet,
                        notify = notify,
                        notifyTitle = st.name + "(" + st.code + ") 信号：" + sig.action,
                        notifyBody = sig.phaseLabel + " · 评分 " + sig.score + "｜" + sig.sessionSummary
                    )
                )
            } catch (e: Exception) {
                // 单只失败不影响整体
            }
        }
        store.saveLastActions(lastActions)
        return out.sortedByDescending { it.signal.score }
    }

    /** 生成并保存盘后报告，返回报告文本 */
    fun buildAndSaveReport(results: List<WatchResult>, nowMs: Long = System.currentTimeMillis()): String {
        val date = today(nowMs)
        val text = Report.buildDaily(date, results, Strategy.marketPhase(nowMs))
        store.appendReport(date, text)
        store.lastReportDate = date
        return text
    }
}
