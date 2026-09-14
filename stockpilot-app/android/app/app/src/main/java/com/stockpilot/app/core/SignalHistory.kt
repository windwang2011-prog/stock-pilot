package com.stockpilot.app.core

/**
 * 推荐信号历史与「连续 N 天同一建议」统计。
 *
 * 每次生成报告时把当日推荐快照存一份；统计时从最新一天往前回溯，
 * 只要动作一致就累加天数。快照序列本身就是「有记录的交易日」，
 * 因此天数 = 连续交易日数（中间某天没被推荐/没记录则中断）。
 */
data class SignalItem(
    val secid: String,
    val name: String,
    val action: String,
    val score: Int,
    val price: Double?
)

data class SignalSnapshot(val date: String, val items: List<SignalItem>)

data class Streak(
    val secid: String,
    val name: String,
    val action: String,
    val days: Int,
    val score: Int,
    val price: Double?,
    val startPrice: Double?
) {
    /** 连续期间的价格变化（%） */
    val gainPct: Double?
        get() {
            val s = startPrice ?: return null
            val e = price ?: return null
            if (s <= 0) return null
            return (e / s - 1) * 100
        }

    /** 展示文案，如「连续 3 天建议买入」 */
    val label: String
        get() = if (days <= 1) "今日首次" + action else "连续 " + days + " 天建议" + action
}

object SignalHistory {

    /** 动作排序权重：买入类在前，卖出类在后 */
    fun actionRank(action: String): Int = when (action) {
        "买入" -> 0
        "逢低关注" -> 1
        "持有观望" -> 2
        "减仓" -> 3
        "卖出" -> 4
        else -> 5
    }

    /**
     * 计算截至最新一天，每只股票「连续同一动作」的天数。
     * @param history 快照列表（顺序不限，内部会按日期排序）
     */
    fun streaks(history: List<SignalSnapshot>): Map<String, Streak> {
        if (history.isEmpty()) return emptyMap()
        val snap = history.sortedBy { it.date }
        val last = snap[snap.size - 1]
        val out = HashMap<String, Streak>()

        for (item in last.items) {
            var days = 0
            var startPrice: Double? = null
            for (i in snap.indices.reversed()) {
                val rec = snap[i].items.firstOrNull { it.secid == item.secid }
                if (rec == null || rec.action != item.action) break
                days++
                if (rec.price != null && rec.price > 0) startPrice = rec.price
            }
            if (days == 0) continue
            out[item.secid] = Streak(
                secid = item.secid,
                name = item.name,
                action = item.action,
                days = days,
                score = item.score,
                price = item.price,
                startPrice = startPrice
            )
        }
        return out
    }

    /** 连续天数 ≥ minDays 的股票，按（动作优先 → 天数多 → 评分高）排序 */
    fun ranked(streaks: Map<String, Streak>, minDays: Int = 2): List<Streak> =
        streaks.values
            .filter { it.days >= minDays }
            .sortedWith(
                compareBy<Streak>(
                    { actionRank(it.action) },
                    { -it.days },
                    { -it.score }
                )
            )

    /** 统计快照覆盖的交易日数量 */
    fun tradingDays(history: List<SignalSnapshot>): Int = history.size
}
