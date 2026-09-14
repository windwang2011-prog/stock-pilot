package com.stockpilot.app.core

// 数据模型（对应 core/types.ts）

data class Kline(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

data class Quote(
    val secid: String,
    val code: String,
    val market: String,
    val name: String? = null,
    val price: Double? = null,
    val change: Double? = null,
    val changePct: Double? = null,
    val prevClose: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    val amplitude: Double? = null,
    val turnover: Double? = null,
    val volumeRatio: Double? = null,
    val speed: Double? = null,
    val mainNet: Double? = null,
    val mainPct: Double? = null,
    val avg: Double? = null,
    // 以下三项仅指数节点有效：该市场/板块的上涨、下跌、平盘家数（f104/f105/f106）
    val advanceCount: Int? = null,
    val declineCount: Int? = null,
    val flatCount: Int? = null
)

data class SessionPoint(val tag: String, val text: String, val impact: Int)

data class Metrics(
    val gap: Double?,
    val avg: Double?,
    val position: Double?,
    val volRatio: Double?,
    val speed: Double?,
    val mainNet: Double?,
    val mainPct: Double?,
    val turnover: Double?,
    val amplitude: Double?,
    val changePct: Double?
)

data class PhaseInfo(val phase: String, val label: String, val note: String)

data class BaseSignal(
    val score: Int,
    val action: String,
    val trend: String,
    val uptrend: Boolean,
    val belowMA20: Boolean,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val rsi: Double?,
    val macd: Double,
    val volRatio: Double,
    val reasons: List<String>
)

data class Signal(
    val score: Int,
    val baseScore: Int,
    val sessionDelta: Int,
    val action: String,
    val trend: String,
    val uptrend: Boolean,
    val belowMA20: Boolean,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val rsi: Double?,
    val macd: Double,
    val volRatio: Double,
    val phase: String,
    val phaseLabel: String,
    val sessionSummary: String,
    val sessionPoints: List<SessionPoint>,
    val reasons: List<String>,
    val metrics: Metrics
)

data class StockRef(val secid: String, val code: String, val name: String)
