package com.stockpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 黄金向量一致性测试
 *
 * 期望值由已验证的 TypeScript 核心生成（stockpilot-app/test/golden.ts），
 * 用于保证 Kotlin 移植版与参考实现逐位一致。CI 会自动运行本测试。
 */
class StrategyTest {

    private fun r2(v: Double): Double = Math.round(v * 100.0).toDouble() / 100.0

    /** 必须与 TS 的 genKlines 完全一致：累加保持全精度，仅存储时 round2 */
    private fun genKlines(n: Int, start: Double, drift: Double, vol: Double): List<Kline> {
        val out = ArrayList<Kline>(n)
        var px = start
        for (i in 0 until n) {
            px *= (1 + drift)
            out.add(Kline("D$i", r2(px * 0.995), r2(px), r2(px * 1.012), r2(px * 0.988), vol))
        }
        return out
    }

    private fun phaseAt(y: Int, mo: Int, d: Int, h: Int, mi: Int): PhaseInfo {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.set(y, mo - 1, d, h, mi, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return Strategy.marketPhase(cal.timeInMillis)
    }

    private fun eq(name: String, expected: Double, actual: Double?, eps: Double = 1e-6) {
        assertTrue("$name 期望=$expected 实际=$actual", actual != null && Math.abs(expected - actual) < eps)
    }

    private fun eqInt(name: String, expected: Int, actual: Int) {
        assertEquals("$name", expected, actual)
    }

    private fun eqStr(name: String, expected: String, actual: String) {
        assertEquals("$name", expected, actual)
    }

    @Test
    fun goldenVectors_matchTsReference() {
        val up = genKlines(140, 10.0, 0.006, 100000.0)
        val down = genKlines(140, 30.0, -0.006, 100000.0)
        val upLast = up[up.size - 1].close
        val upPrev = up[up.size - 2].close

        // 与 TS 的 qIntraday 完全相同的构造方式（注意 down 用例也复用这份 quote）
        val qIntraday = Quote(
            secid = "1.600519", code = "600519", market = "1", name = "T",
            price = upLast, prevClose = upPrev, open = upLast * 0.99,
            high = upLast * 1.02, low = upLast * 0.985,
            volume = 100000.0, amount = upLast * 100000.0 * 100 * 0.97,
            volumeRatio = 1.8, speed = 0.6, mainNet = 5e7, mainPct = 6.2,
            changePct = 2.5, turnover = 3.1
        )
        val intraday = phaseAt(2026, 9, 14, 14, 0)
        val auction = phaseAt(2026, 9, 14, 9, 20)

        // ---------- 用例 1：上升趋势 + 盘中 ----------
        val s1 = Strategy.sessionAnalyze(up, qIntraday, intraday)
        eqInt("s1.score", 83, s1.score)
        eqInt("s1.baseScore", 61, s1.baseScore)
        eqInt("s1.sessionDelta", 22, s1.sessionDelta)
        eqStr("s1.action", "买入", s1.action)
        eqStr("s1.trend", "多头", s1.trend)
        assertTrue("s1.uptrend", s1.uptrend)
        eq("s1.entry", 23.11, s1.entry)
        eq("s1.stop", 21.84, s1.stop)
        eq("s1.target", 25.65, s1.target)
        eq("s1.rsi", 100.0, s1.rsi)
        eq("s1.macd", 0.0413, s1.macd)
        eq("s1.volRatio", 1.0, s1.volRatio)
        eq("s1.avg", 22.42, s1.metrics.avg)
        eq("s1.position", 0.43, s1.metrics.position)
        eqStr("s1.points", "分时|6,量能|5,涨速|4,资金|7",
            s1.sessionPoints.joinToString(",") { it.tag + "|" + it.impact })

        // ---------- 用例 2：上升趋势 + 集合竞价（看跳空） ----------
        val qGap = Quote("1.600519", "600519", "1", prevClose = 100.0, open = 103.0, price = 103.0)
        val s2 = Strategy.sessionAnalyze(up, qGap, auction)
        eqInt("s2.score", 71, s2.score)
        eqInt("s2.baseScore", 61, s2.baseScore)
        eqInt("s2.sessionDelta", 10, s2.sessionDelta)
        eqStr("s2.action", "买入", s2.action)
        eq("s2.entry", 103.0, s2.entry)
        eq("s2.target", 265.32, s2.target)
        eqStr("s2.points", "竞价|6,趋势|4",
            s2.sessionPoints.joinToString(",") { it.tag + "|" + it.impact })

        // ---------- 用例 3：下跌趋势 + 盘中（验证趋势门禁：不得给"买入"） ----------
        val s3 = Strategy.sessionAnalyze(down, qIntraday, intraday)
        eqInt("s3.score", 78, s3.score)
        eqInt("s3.baseScore", 56, s3.baseScore)
        eqInt("s3.sessionDelta", 22, s3.sessionDelta)
        eqStr("s3.action", "逢低关注", s3.action)
        eqStr("s3.trend", "空头", s3.trend)
        assertTrue("s3.uptrend=false", !s3.uptrend)
        eq("s3.stop", 13.69, s3.stop)

        // ---------- 用例 4：无实时报价（纯日线） ----------
        val s4 = Strategy.analyze(up, null)
        eqInt("s4.score", 61, s4.score)
        eqStr("s4.action", "逢低关注", s4.action)
        eqStr("s4.trend", "多头", s4.trend)
        assertTrue("s4.uptrend", s4.uptrend)
        eq("s4.entry", 23.11, s4.entry)
    }

    @Test
    fun marketPhase_boundaries() {
        eqStr("08:50", "pre", phaseAt(2026, 9, 14, 8, 50).phase)
        eqStr("09:20", "auction", phaseAt(2026, 9, 14, 9, 20).phase)
        eqStr("10:30", "intraday", phaseAt(2026, 9, 14, 10, 30).phase)
        eqStr("12:00", "lunch", phaseAt(2026, 9, 14, 12, 0).phase)
        eqStr("15:10", "post", phaseAt(2026, 9, 14, 15, 10).phase)
        eqStr("17:00", "after", phaseAt(2026, 9, 14, 17, 0).phase)
        // 2026-09-13 是周日
        eqStr("周日", "closed", phaseAt(2026, 9, 13, 10, 0).phase)
    }

    @Test
    fun trendGate_blocksBuyOnDowntrend() {
        val down = genKlines(140, 30.0, -0.006, 100000.0)
        val sig = Strategy.analyze(down, null)
        assertTrue("下跌趋势不得给出买入，实际=" + sig.action, sig.action != "买入")
    }

    @Test
    fun sectorHotness_monotonicInChange() {
        val a = Strategy.sectorHotness(5.0, 1e8, 3.0)
        val b = Strategy.sectorHotness(1.0, 1e8, 3.0)
        assertTrue("涨幅越高热度越高", a > b)
    }
}
