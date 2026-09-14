package com.stockpilot.app.core

import java.util.Calendar
import java.util.TimeZone

/**
 * 策略引擎（Kotlin 移植版）
 *
 * 与 stockpilot-app/core/strategy.ts **逐位对应**，由 CI 中的 StrategyTest
 * 用同一组「黄金向量」做一致性校验，防止移植偏差。
 */
object Strategy {

    private fun round2(v: Double): Double = Math.round(v * 100.0).toDouble() / 100.0
    private fun round4(v: Double): Double = Math.round(v * 10000.0).toDouble() / 10000.0

    // ---------------- 基础指标 ----------------
    private fun ema(arr: List<Double>, period: Int): List<Double> {
        val k = 2.0 / (period + 1)
        val res = ArrayList<Double>(arr.size)
        var prev = 0.0
        for (i in arr.indices) {
            val v = arr[i]
            if (i == 0) { prev = v; res.add(v) } else { prev = v * k + prev * (1 - k); res.add(prev) }
        }
        return res
    }

    private fun ma(arr: List<Double>, period: Int): List<Double?> {
        val res = ArrayList<Double?>(arr.size)
        for (i in arr.indices) {
            if (i < period - 1) { res.add(null); continue }
            var sum = 0.0
            for (j in (i - period + 1)..i) sum += arr[j]
            res.add(sum / period)
        }
        return res
    }

    private fun rsi(closes: List<Double>, period: Int): List<Double?> {
        val res = ArrayList<Double?>(closes.size)
        var gain = 0.0
        var loss = 0.0
        for (i in closes.indices) {
            if (i == 0) { res.add(null); continue }
            val diff = closes[i] - closes[i - 1]
            if (i <= period) {
                if (diff >= 0) gain += diff else loss -= diff
                if (i == period) {
                    gain /= period; loss /= period
                    res.add(if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss))
                } else res.add(null)
            } else {
                val g = if (diff >= 0) diff else 0.0
                val l = if (diff < 0) -diff else 0.0
                gain = (gain * (period - 1) + g) / period
                loss = (loss * (period - 1) + l) / period
                res.add(if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss))
            }
        }
        return res
    }

    private class Ind(
        val closes: List<Double>,
        val ma5: List<Double?>, val ma10: List<Double?>, val ma20: List<Double?>, val ma60: List<Double?>,
        val dif: List<Double>, val dea: List<Double>, val macd: List<Double>,
        val rsi14: List<Double?>, val volumes: List<Double>, val volMa5: List<Double?>
    )

    private fun computeIndicators(klines: List<Kline>): Ind {
        val closes = klines.map { it.close }
        val volumes = klines.map { it.volume }
        val ema12 = ema(closes, 12)
        val ema26 = ema(closes, 26)
        val dif = closes.indices.map { ema12[it] - ema26[it] }
        val dea = ema(dif, 9)
        val macd = dif.indices.map { (dif[it] - dea[it]) * 2 }
        return Ind(
            closes,
            ma(closes, 5), ma(closes, 10), ma(closes, 20), ma(closes, 60),
            dif, dea, macd,
            rsi(closes, 14),
            volumes, ma(volumes, 5)
        )
    }

    fun calcATR(klines: List<Kline>, period: Int = 14): Double {
        if (klines.size < 2) return 0.0
        val trs = ArrayList<Double>()
        for (i in 1 until klines.size) {
            val h = klines[i].high
            val l = klines[i].low
            val pc = klines[i - 1].close
            trs.add(Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc))))
        }
        val slice = trs.takeLast(period)
        var sum = 0.0
        for (t in slice) sum += t
        val atr = sum / slice.size
        return if (atr != 0.0) atr else klines[klines.size - 1].close * 0.03
    }

    // ---------------- 日线技术面 ----------------
    fun analyze(klines: List<Kline>, quotePrice: Double?): BaseSignal {
        val ind = computeIndicators(klines)
        val n = klines.size
        val reasons = ArrayList<String>()
        var score = 50
        val last = n - 1

        val price = quotePrice ?: ind.closes[last]
        val prevPrice = ind.closes[last - 1]
        val c = ind.closes[last]
        val m5 = ind.ma5[last]; val m10 = ind.ma10[last]; val m20 = ind.ma20[last]; val m60 = ind.ma60[last]
        val m20Prev = ind.ma20[last - 1]
        val d = ind.dif[last]; val dea = ind.dea[last]
        val macdBar = ind.macd[last]; val macdPrev = ind.macd[last - 1]
        val r = ind.rsi14[last]
        val vol = ind.volumes[last]; val volM = ind.volMa5[last]
        val volRatio = if (volM != null && volM != 0.0) vol / volM else 1.0

        if (m5 != null && m10 != null && m20 != null && m60 != null) {
            if (c > m5 && m5 > m10 && m10 > m20 && m20 > m60) {
                score += 10; reasons.add("均线多头排列（价>MA5>MA10>MA20>MA60）")
            } else if (c > m20 && m20 > m60) {
                score += 5; reasons.add("价格站上 MA20，中期趋势向上")
            } else if (c < m20) {
                score -= 8; reasons.add("价格跌破 MA20，趋势偏弱")
            }
        }

        val m5p = ind.ma5[last - 1]
        val m20p = ind.ma20[last - 1]
        if (m5 != null && m5p != null && m20 != null && m20p != null) {
            if (m5p <= m20p && m5 > m20) { score += 12; reasons.add("MA5 上穿 MA20 金叉（买入信号）") }
            if (m5p >= m20p && m5 < m20) { score -= 12; reasons.add("MA5 下穿 MA20 死叉（卖出信号）") }
        }

        if (d > dea) { score += 6; reasons.add("MACD 金叉区域（DIF>DEA）") }
        else { score -= 6; reasons.add("MACD 死叉区域（DIF<DEA）") }
        if (macdBar > 0 && macdBar > macdPrev) { score += 5; reasons.add("MACD 红柱放大，动能增强") }
        else if (macdBar < 0 && macdBar < macdPrev) { score -= 5; reasons.add("MACD 绿柱放大，动能走弱") }

        if (r != null) {
            if (r < 30) { score += 8; reasons.add("RSI=" + fmt0(r) + " 超卖，有反弹需求") }
            else if (r > 70) { score -= 10; reasons.add("RSI=" + fmt0(r) + " 超买，注意回落") }
            else if (r >= 45 && r <= 60) { score += 3; reasons.add("RSI=" + fmt0(r) + " 健康区间") }
        }

        if (volRatio > 1.5) { score += 4; reasons.add("放量（量比 " + f2(volRatio) + "），资金活跃") }
        else if (volRatio < 0.6) { score -= 2; reasons.add("缩量（量比 " + f2(volRatio) + "）") }

        val dayChg = if (prevPrice != 0.0) (price - prevPrice) / prevPrice else 0.0
        if (dayChg > 0.03 && volRatio > 1.2) { score += 4; reasons.add("放量上涨，强势") }
        if (dayChg < -0.03) { score -= 4; reasons.add("当日明显下跌") }

        score = Math.max(0, Math.min(100, Math.round(score.toDouble()).toInt()))

        val uptrend = (m20 != null && m60 != null && m20Prev != null && c > m20 && m20 > m60 && m20 >= m20Prev)
        val belowMA20 = m20 != null && c < m20
        val extended = if (m20 != null) c > m20 * 1.12 else false
        val illiquid = volRatio < 0.4

        var action: String
        if (score >= 70) action = "买入"
        else if (score >= 55) action = "逢低关注"
        else if (score >= 40) action = "持有观望"
        else if (score >= 25) action = "减仓"
        else action = "卖出"

        if (action == "买入" && !uptrend) { action = "持有观望"; reasons.add("未处于多头趋势（需价>MA20>MA60 且 MA20 向上），抑制买入") }
        if (action == "买入" && extended) { action = "逢低关注"; reasons.add("股价偏离 MA20 过远（>12%），追高规避") }
        if (action == "买入" && illiquid) { action = "逢低关注"; reasons.add("量能过低（量比<0.4），流动性不足暂缓") }

        val atr = calcATR(klines, 14)
        val entry = price
        val stop = if (m20 != null) Math.min(m20, price - atr * 1.5) else price * 0.93
        val target = entry + (entry - stop) * 2
        val trend = if (m20 != null && m60 != null) (if (c > m20 && m20 > m60) "多头" else (if (c < m20) "空头" else "震荡")) else "震荡"

        return BaseSignal(
            score = score, action = action, trend = trend, uptrend = uptrend, belowMA20 = belowMA20,
            entry = round2(entry), stop = round2(stop), target = round2(target),
            rsi = if (r != null) round2(r) else null,
            macd = round4(macdBar), volRatio = round2(volRatio),
            reasons = reasons
        )
    }

    // ---------------- 市场阶段（北京时间） ----------------
    fun marketPhase(nowMs: Long = System.currentTimeMillis()): PhaseInfo {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = nowMs
        val day = cal.get(Calendar.DAY_OF_WEEK)          // SUNDAY=1 ... SATURDAY=7
        val hm = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        if (day == Calendar.SUNDAY || day == Calendar.SATURDAY) {
            return PhaseInfo("closed", "休市", "周末休市")
        }
        return when {
            hm < 915 -> PhaseInfo("pre", "盘前", "开盘前，以昨日技术面为主")
            hm < 930 -> PhaseInfo("auction", "集合竞价", "9:15-9:25 竞价撮合，关注开盘强弱")
            hm < 1130 -> PhaseInfo("intraday", "盘中", "上午交易时段")
            hm < 1300 -> PhaseInfo("lunch", "午间休市", "午间休市，参考上午表现")
            hm < 1500 -> PhaseInfo("intraday", "盘中", "下午交易时段")
            hm < 1530 -> PhaseInfo("post", "盘后", "盘后固定价交易时段")
            else -> PhaseInfo("after", "收盘后", "收盘后复盘，参考全天资金与位置")
        }
    }

    // ---------------- 分时段综合分析 ----------------
    fun sessionAnalyze(klines: List<Kline>, quote: Quote?, phaseInfo: PhaseInfo? = null): Signal {
        val q = quote ?: Quote("", "", "")
        val info = phaseInfo ?: marketPhase()
        val phase = info.phase
        val base = analyze(klines, q.price)

        val points = ArrayList<SessionPoint>()
        var delta = 0
        fun add(tag: String, text: String, impact: Int) {
            delta += impact
            points.add(SessionPoint(tag, text, impact))
        }

        val price = q.price ?: base.entry
        val prevClose = q.prevClose
        val open = q.open
        val high = q.high
        val low = q.low
        val volume = q.volume
        val amount = q.amount
        val volRatio = q.volumeRatio
        val speed = q.speed
        val mainNet = q.mainNet
        val mainPct = q.mainPct
        val changePct = q.changePct
        val turnover = q.turnover
        val amplitude = q.amplitude

        var avg = q.avg
        if (avg == null && amount != null && volume != null && volume > 0) avg = amount / (volume * 100)
        val pos = if (high != null && low != null && high > low) (price - low) / (high - low) else null
        val gap = if (prevClose != null && open != null && prevClose != 0.0) (open - prevClose) / prevClose * 100 else null

        if ((phase == "pre" || phase == "auction") && gap != null) {
            when {
                gap >= 1 && gap <= 5 -> add("竞价", "高开 " + f2(gap) + "%，竞价偏强", 6)
                gap > 5 -> add("竞价", "大幅高开 " + f2(gap) + "%，警惕高开低走", -4)
                gap <= -3 -> add("竞价", "低开 " + f2(gap) + "%，短线偏弱", -6)
                gap <= -1 -> add("竞价", "小幅低开 " + f2(gap) + "%", -2)
            }
        }

        if (phase == "intraday" || phase == "lunch" || phase == "post" || phase == "after") {
            val cp = changePct ?: 0.0
            if (avg != null && avg != 0.0 && price != 0.0) {
                val dAvg = (price - avg) / avg * 100
                if (dAvg > 0.5) add("分时", "现价高于当日均价 " + f2(dAvg) + "%，多头占优", 6)
                else if (dAvg < -0.5) add("分时", "现价低于当日均价 " + f2(-dAvg) + "%，走势偏弱", -6)
            }
            if (volRatio != null) {
                if (volRatio >= 1.5 && cp > 0) add("量能", "量比 " + f2(volRatio) + "，放量上涨", 5)
                else if (volRatio >= 2 && cp < 0) add("量能", "量比 " + f2(volRatio) + "，放量下跌", -6)
                else if (volRatio < 0.6) add("量能", "量比 " + f2(volRatio) + "，交投清淡", -2)
            }
            if (speed != null) {
                if (speed >= 0.5) add("涨速", "涨速 +" + f2(speed) + "%/分，快速拉升", 4)
                else if (speed <= -0.5) add("涨速", "涨速 " + f2(speed) + "%/分，快速回落", -4)
            }
            if (pos != null) {
                if (pos >= 0.7) add("位置", "处于当日振幅上沿(" + f0(pos * 100) + "%)，收盘/现价强势", 4)
                else if (pos <= 0.3) add("位置", "处于当日振幅下沿(" + f0(pos * 100) + "%)，弱势", -4)
            }
            if (prevClose != null && high != null && (high - price) / prevClose > 0.03 && cp < 2) {
                add("形态", "自当日高点回落超3%，警惕冲高回落", -5)
            }
            if (mainNet != null) {
                val yi = mainNet / 1e8
                val pctTxt = if (mainPct != null) "（占成交额 " + f1(mainPct) + "%）" else ""
                if (mainNet > 0 && (mainPct == null || mainPct > 3)) add("资金", "主力净流入 " + f2(yi) + " 亿" + pctTxt, 7)
                else if (mainNet < 0 && (mainPct == null || mainPct < -3)) add("资金", "主力净流出 " + f2(Math.abs(yi)) + " 亿" + pctTxt, -7)
            }
            if (turnover != null && turnover > 15) add("换手", "换手率 " + f1(turnover) + "%，交投活跃", 2)
        }

        if ((phase == "pre" || phase == "auction") && base.uptrend) {
            add("趋势", "日线维持多头趋势，盘前形态占优", 4)
        }

        val score = Math.max(0, Math.min(100, Math.round((base.score + delta).toDouble()).toInt()))
        var action: String
        if (score >= 70) action = "买入"
        else if (score >= 55) action = "逢低关注"
        else if (score >= 40) action = "持有观望"
        else if (score >= 25) action = "减仓"
        else action = "卖出"

        // 与日线门禁保持一致
        if (action == "买入" && (!base.uptrend || base.belowMA20)) action = "逢低关注"

        val sessionSummary = if (points.isNotEmpty()) points.joinToString("；") { it.text } else "暂无分时特征"

        val metrics = Metrics(
            gap = if (gap != null) round2(gap) else null,
            avg = if (avg != null) round2(avg) else null,
            position = if (pos != null) round2(pos) else null,
            volRatio = volRatio, speed = speed, mainNet = mainNet, mainPct = mainPct,
            turnover = turnover, amplitude = amplitude, changePct = changePct
        )

        val reasons = points.map { "【" + it.tag + "】" + it.text }.toMutableList()
        reasons.addAll(base.reasons)

        return Signal(
            score = score, baseScore = base.score, sessionDelta = delta, action = action,
            trend = base.trend, uptrend = base.uptrend, belowMA20 = base.belowMA20,
            entry = base.entry, stop = base.stop, target = base.target,
            rsi = base.rsi, macd = base.macd, volRatio = base.volRatio,
            phase = phase, phaseLabel = info.label,
            sessionSummary = sessionSummary, sessionPoints = points,
            reasons = reasons, metrics = metrics
        )
    }

    // ---------------- 板块热度 ----------------
    fun sectorHotness(changePct: Double, mainFund: Double, turnover: Double): Double {
        var score = changePct * 3
        score += Math.min(20.0, mainFund / 1e8 * 0.5)
        score += Math.min(15.0, turnover * 1.5)
        return Math.round(score * 10.0).toDouble() / 10.0
    }

    // ---- 格式化（与 TS 的 toFixed 行为保持一致）----
    private fun fmt0(v: Double): String = Math.round(v).toString()
    private fun f0(v: Double): String = Math.round(v).toString()
    private fun f1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun f2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
