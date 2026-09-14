package com.stockpilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockpilot.app.core.Kline
import com.stockpilot.app.core.PhaseInfo
import com.stockpilot.app.core.Quote
import com.stockpilot.app.core.Strategy

/**
 * M3 第一步：策略引擎上机自检
 *
 * 界面暂为自检页 —— 用与 CI 中 StrategyTest 相同的合成数据在**真机**上跑一遍，
 * 确认 Kotlin 移植版在鸿蒙（卓易通）环境下计算结果与参考实现一致。
 * 下一步将接入真实行情、自选列表、后台盯盘服务与盘后报告。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SelfCheckScreen()
                }
            }
        }
    }
}

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

private class CheckLine(val name: String, val ok: Boolean, val detail: String)

private fun runSelfCheck(): List<CheckLine> {
    val res = ArrayList<CheckLine>()
    fun check(name: String, expected: Any, actual: Any) {
        val ok = if (expected is Number && actual is Number) {
            Math.abs(expected.toDouble() - actual.toDouble()) < 1e-6
        } else {
            expected.toString() == actual.toString()
        }
        res.add(CheckLine(name, ok, "期望 $expected / 实际 $actual"))
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

    // 自检使用**固定的盘中阶段**，保证任何时刻运行结果都一致（与 CI 黄金向量对齐）
    val fixedPhase = PhaseInfo("intraday", "盘中", "上午交易时段")

    val sig1 = Strategy.sessionAnalyze(up, q, fixedPhase)
    check("综合评分", 83, sig1.score)
    check("日线基础分", 61, sig1.baseScore)
    check("分时增量", 22, sig1.sessionDelta)
    check("日线趋势", "多头", sig1.trend)
    check("入场价", 23.11, sig1.entry)
    check("止损价", 21.84, sig1.stop)
    check("目标价", 25.65, sig1.target)
    check("分时要点", "分时|6,量能|5,涨速|4,资金|7",
        sig1.sessionPoints.joinToString(",") { it.tag + "|" + it.impact })

    val sig3 = Strategy.sessionAnalyze(down, q, fixedPhase)
    check("下跌趋势不得买入", "逢低关注", sig3.action)
    check("下跌趋势识别", "空头", sig3.trend)

    check("板块热度", true, Strategy.sectorHotness(5.0, 1e8, 3.0) > Strategy.sectorHotness(1.0, 1e8, 3.0))

    return res
}

@Composable
fun SelfCheckScreen() {
    val phase = remember { Strategy.marketPhase() }
    val checks = remember { runSelfCheck() }
    val passCount = checks.count { it.ok }
    val allPass = passCount == checks.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("StockPilot", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("移动端 · 策略引擎自检", fontSize = 13.sp, color = Color(0xFF666666))
        Spacer(Modifier.height(16.dp))

        Text("市场阶段：${phase.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("（${phase.note}）", fontSize = 12.sp, color = Color(0xFF888888))
        Spacer(Modifier.height(16.dp))

        Text(
            if (allPass) "引擎自检：全部通过 ✅（$passCount/${checks.size}）"
            else "引擎自检：存在失败 ❌（$passCount/${checks.size}）",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (allPass) Color(0xFFC0392B) else Color(0xFF15803D)
        )
        Spacer(Modifier.height(10.dp))

        for (c in checks) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(if (c.ok) "✅ " else "❌ ", fontSize = 12.sp)
                Text(c.name + "  ", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(c.detail, fontSize = 11.sp, color = Color(0xFF777777))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("后续版本将接入：", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("· 实时行情与自选管理", fontSize = 12.sp, color = Color(0xFF555555))
        Text("· 盘前/盘中/盘后分时段推荐", fontSize = 12.sp, color = Color(0xFF555555))
        Text("· 后台盯盘服务 + 买卖信号通知", fontSize = 12.sp, color = Color(0xFF555555))
        Text("· 盘后自动汇总报告", fontSize = 12.sp, color = Color(0xFF555555))
        Spacer(Modifier.height(16.dp))
        Text(
            "本工具为技术指标分析，仅供学习研究，不构成投资建议。",
            fontSize = 11.sp, color = Color(0xFF999999)
        )
    }
}
