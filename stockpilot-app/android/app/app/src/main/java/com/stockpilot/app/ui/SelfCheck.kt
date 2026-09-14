package com.stockpilot.app.ui

import com.stockpilot.app.core.Kline
import com.stockpilot.app.core.PhaseInfo
import com.stockpilot.app.core.Quote
import com.stockpilot.app.core.Strategy

/**
 * 引擎自检：在真机上用固定输入跑一遍策略，确认与 CI 黄金向量一致。
 * 返回 (通过数, 总数)。
 */
object SelfCheck {

    private fun r2(v: Double): Double = Math.round(v * 100.0).toDouble() / 100.0

    private fun genKlines(n: Int, start: Double, drift: Double, vol: Double): List<Kline> {
        val out = ArrayList<Kline>(n)
        var px = start
        for (i in 0 until n) {
            px *= (1 + drift)
            out.add(Kline("D$i", r2(px * 0.995), r2(px), r2(px * 1.012), r2(px * 0.988), vol))
        }
        return out
    }

    fun run(): Pair<Int, Int> {
        var total = 0
        var pass = 0
        fun check(expected: Any, actual: Any) {
            total++
            val ok = if (expected is Number && actual is Number) {
                Math.abs(expected.toDouble() - actual.toDouble()) < 1e-6
            } else {
                expected.toString() == actual.toString()
            }
            if (ok) pass++
        }

        val up = genKlines(140, 10.0, 0.006, 100000.0)
        val down = genKlines(140, 30.0, -0.006, 100000.0)
        val upLast = up[up.size - 1].close
        val upPrev = up[up.size - 2].close

        val q = Quote(
            secid = "1.600519", code = "600519", market = "1", name = "T",
            price = upLast, prevClose = upPrev, open = upLast * 0.99,
            high = upLast * 1.02, low = upLast * 0.985,
            volume = 100000.0, amount = upLast * 100000.0 * 100 * 0.97,
            volumeRatio = 1.8, speed = 0.6, mainNet = 5e7, mainPct = 6.2,
            changePct = 2.5, turnover = 3.1
        )
        val fixed = PhaseInfo("intraday", "盘中", "上午交易时段")

        val s1 = Strategy.sessionAnalyze(up, q, fixed)
        check(83, s1.score)
        check(61, s1.baseScore)
        check(22, s1.sessionDelta)
        check("多头", s1.trend)
        check(23.11, s1.entry)
        check(21.84, s1.stop)
        check(25.65, s1.target)
        check("分时|6,量能|5,涨速|4,资金|7",
            s1.sessionPoints.joinToString(",") { it.tag + "|" + it.impact })

        val s3 = Strategy.sessionAnalyze(down, q, fixed)
        check("逢低关注", s3.action)
        check("空头", s3.trend)

        check(true, Strategy.sectorHotness(5.0, 1e8, 3.0) > Strategy.sectorHotness(1.0, 1e8, 3.0))

        return Pair(pass, total)
    }
}
