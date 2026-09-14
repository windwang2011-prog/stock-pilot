package com.stockpilot.app.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 盯盘引擎：交易时段判定 + 批量扫描 + 通知决策（动作变化去重）+ 每日报告组装。
 *
 * 注意：scan() / analyzeList() 为阻塞调用，请在 IO 线程或服务后台线程上执行。
 */
class Engine(private val ds: DataSource, private val store: Store) {

    /** K 线并发拉取线程池（串行 24 只要 5~8 秒，并发后约 1 秒） */
    private val pool = Executors.newFixedThreadPool(6)

    /** 是否处于需盯盘的时段（集合竞价 / 盘中 / 午间） */
    fun shouldScan(nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = Strategy.marketPhase(nowMs)
        return p.phase == "auction" || p.phase == "intraday" || p.phase == "lunch"
    }

    /**
     * 是否可生成当日报告。
     * 收盘后（15:30 起）到当天结束都可以生成——若手机在 15:30 未开机，
     * 之后启动服务或手动点击也会补生当天报告。
     */
    fun isReportWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = Strategy.marketPhase(nowMs)
        if (p.phase != "post" && p.phase != "after") return false
        return bjHourMinute(nowMs) >= 1530
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

    private fun timeLabel(nowMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    // ================= 分析 =================

    /** 纯分析：不做通知去重、不修改通知状态（供报告 / 推荐使用） */
    fun analyzeList(stocks: List<StockRef>, nowMs: Long = System.currentTimeMillis()): List<WatchResult> {
        if (stocks.isEmpty()) return emptyList()
        val phase = Strategy.marketPhase(nowMs)

        val quotes = try {
            ds.getQuotes(stocks.map { it.secid })
        } catch (e: Exception) {
            emptyList()
        }
        val qmap = HashMap<String, Quote>()
        for (q in quotes) qmap[q.secid] = q

        val futures = stocks.map { st ->
            pool.submit(Callable<WatchResult?> { analyzeOne(st, phase, qmap) })
        }
        val out = ArrayList<WatchResult>()
        for (f in futures) {
            try {
                val r = f.get()
                if (r != null) out.add(r)
            } catch (e: Exception) {
                // 单只失败不影响整体
            }
        }
        return out.sortedByDescending { it.signal.score }
    }

    private fun analyzeOne(st: StockRef, phase: PhaseInfo, qmap: Map<String, Quote>): WatchResult? {
        return try {
            val klines = ds.getKlines(st.secid, 120, 101)
            if (klines.isEmpty()) return null
            val q = qmap[st.secid]
            val sig = Strategy.sessionAnalyze(klines, q, phase)
            WatchResult(
                stock = st,
                signal = sig,
                price = q?.price ?: klines[klines.size - 1].close,
                changePct = q?.changePct ?: 0.0,
                mainNet = sig.metrics.mainNet,
                notify = false,
                notifyTitle = "",
                notifyBody = ""
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 扫描一批自选股，返回含通知决策的结果（按评分降序） */
    fun scan(stocks: List<StockRef>, nowMs: Long = System.currentTimeMillis()): List<WatchResult> {
        val analyzed = analyzeList(stocks, nowMs)
        if (analyzed.isEmpty()) return analyzed

        val lastActions = store.loadLastActions()
        val out = ArrayList<WatchResult>(analyzed.size)
        for (r in analyzed) {
            val secid = r.stock.secid
            val prev = lastActions[secid]
            val action = r.signal.action
            val notify = prev != action && (action == "买入" || action == "卖出")
            lastActions[secid] = action
            out.add(
                if (notify) r.copy(
                    notify = true,
                    notifyTitle = r.stock.name + "(" + r.stock.code + ") 信号：" + action,
                    notifyBody = r.signal.phaseLabel + " · 评分 " + r.signal.score + "｜" + r.signal.sessionSummary
                ) else r
            )
        }
        store.saveLastActions(lastActions)
        return out
    }

    // ================= 报告 =================

    /**
     * 生成并保存每日报告。
     * 报告素材包含：大盘指数 → 热点板块 → 板块龙头 → 推荐个股（含连续性）→ 自选股。
     * @param watchResults 自选股分析结果（可为空，空则报告只含全市场部分）
     */
    fun buildAndSaveReport(
        watchResults: List<WatchResult>,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val date = today(nowMs)
        val input = collectReportInput(date, nowMs, watchResults)
        val text = Report.build(input)
        store.appendReport(date, text)

        // 记录当日推荐快照，用于后续「连续 N 天建议买入」统计
        if (input.recos.isNotEmpty()) {
            store.appendSignalSnapshot(
                SignalSnapshot(
                    date = date,
                    items = input.recos.map {
                        SignalItem(
                            secid = it.stock.secid,
                            name = it.stock.name,
                            action = it.signal.action,
                            score = it.signal.score,
                            price = it.price
                        )
                    }
                )
            )
        }
        store.lastReportDate = date
        return text
    }

    /** 组装报告素材 */
    private fun collectReportInput(
        date: String,
        nowMs: Long,
        watch: List<WatchResult>
    ): ReportInput {
        val notes = ArrayList<String>()

        // ① 大盘指数
        var indexQuotes: Map<String, Quote> = emptyMap()
        try {
            indexQuotes = ds.getQuotes(MarketIndex.secids()).associateBy { it.secid }
        } catch (e: Exception) {
            notes.add("大盘指数获取失败（" + (e.message ?: "网络异常") + "）")
        }

        // ② 热点板块（过滤掉「昨日涨停/融资融券」等风格类伪板块）
        var sectors: List<Sector> = emptyList()
        try {
            sectors = MarketIndex.realSectors(ds.getHotSectors())
        } catch (e: Exception) {
            notes.add("热点板块获取失败")
        }

        // ③ 板块龙头 + 推荐个股：取热度最高的 5 个板块，各取涨幅前 6 只
        val recos = ArrayList<WatchResult>()
        val leaders = ArrayList<LeaderRow>()
        if (sectors.isNotEmpty()) {
            val hot = sectors
                .sortedByDescending { Strategy.sectorHotness(it.changePct, it.mainFund, it.turnover) }
                .take(5)
            val picks = ArrayList<StockRef>()
            val sectorOf = HashMap<String, Pair<String, Double>>()   // secid -> (板块名, 板块涨幅)
            val seen = HashSet<String>()
            for (s in hot) {
                val briefs = try {
                    ds.getSectorStocks(s.code)
                } catch (e: Exception) {
                    emptyList()
                }
                for (b in briefs.sortedByDescending { it.changePct }.take(6)) {
                    if (seen.add(b.secid)) {
                        picks.add(StockRef(b.secid, b.code, b.name))
                        sectorOf[b.secid] = Pair(s.name, s.changePct)
                    }
                }
            }
            val scanned = analyzeList(picks.take(24), nowMs)
            recos.addAll(scanned)

            for (s in hot) {
                val inSector = scanned
                    .filter { sectorOf[it.stock.secid]?.first == s.name }
                    .sortedByDescending { it.changePct }
                    .take(3)
                for (r in inSector) leaders.add(LeaderRow(s.name, s.changePct, r))
            }
        }
        if (recos.isEmpty()) notes.add("推荐个股数据不足，报告已降级")
        if (indexQuotes.isEmpty() && sectors.isEmpty()) notes.add("行情接口整体不可用，请检查网络后重新生成")

        // ④ 连续性统计
        val history = store.loadSignalHistory()
        val streaks = SignalHistory.streaks(history)

        return ReportInput(
            date = date,
            timeLabel = timeLabel(nowMs),
            phase = Strategy.marketPhase(nowMs),
            indexQuotes = indexQuotes,
            sectors = sectors,
            leaders = leaders,
            recos = recos,
            streaks = streaks,
            watch = watch,
            historyDays = SignalHistory.tradingDays(history),
            notes = notes
        )
    }

    // ================= 美股 =================

    /** 是否进入"美股盘后报告"窗口（北京时间 05:30-09:00，且当天不是周日） */
    fun isUsReportWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        // 周日（北京时间）没有新的美股收盘数据，跳过
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) return false
        val hm = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        return hm >= 530 && hm < 900
    }

    /** 拉取美股盘面行情（指数 + 行业ETF + 龙头 + 中概） */
    fun fetchUsQuotes(): Map<String, Quote> {
        val secids = UsMarket.all().map { it.secid }
        val qs = try {
            ds.getQuotes(secids)
        } catch (e: Exception) {
            emptyList()
        }
        val map = HashMap<String, Quote>()
        for (q in qs) map[q.secid] = q
        return map
    }

    /** 生成并保存美股盘面热点报告，返回报告文本 */
    fun buildAndSaveUsReport(nowMs: Long = System.currentTimeMillis()): String {
        val date = today(nowMs)
        val text = UsMarket.buildReport(date, fetchUsQuotes())
        store.appendUsReport(date, text)
        store.lastUsReportDate = date
        return text
    }
}
