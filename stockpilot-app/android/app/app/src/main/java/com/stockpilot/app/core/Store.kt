package com.stockpilot.app.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：自选列表、上次动作（用于通知去重）、历史报告、设置项。
 * 用 SharedPreferences + JSON 实现，避免引入 Room/KSP（降低编译复杂度）。
 */
class Store(ctx: Context) {

    private val sp: SharedPreferences = ctx.getSharedPreferences("stockpilot", Context.MODE_PRIVATE)

    // ---------------- 自选 ----------------
    fun loadWatchlist(): MutableList<StockRef> {
        val s = sp.getString(KEY_WATCH, null) ?: return ArrayList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<StockRef>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(StockRef(o.optString("secid"), o.optString("code"), o.optString("name")))
            }
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun saveWatchlist(list: List<StockRef>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("secid", s.secid).put("code", s.code).put("name", s.name))
        }
        sp.edit().putString(KEY_WATCH, arr.toString()).apply()
    }

    // ---------------- 上次动作（通知去重） ----------------
    fun loadLastActions(): MutableMap<String, String> {
        val s = sp.getString(KEY_LAST_ACTION, null) ?: return HashMap()
        return try {
            val o = JSONObject(s)
            val out = HashMap<String, String>()
            for (k in o.keys()) out[k] = o.optString(k)
            out
        } catch (e: Exception) {
            HashMap()
        }
    }

    fun saveLastActions(m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        sp.edit().putString(KEY_LAST_ACTION, o.toString()).apply()
    }

    // ---------------- 报告（A股盘后 / 美股盘后） ----------------
    fun appendReport(date: String, text: String) = appendList(KEY_REPORTS, date, text)

    fun loadReports(): List<Pair<String, String>> = loadList(KEY_REPORTS)

    fun appendUsReport(date: String, text: String) = appendList(KEY_US_REPORTS, date, text)

    fun loadUsReports(): List<Pair<String, String>> = loadList(KEY_US_REPORTS)

    private fun appendList(key: String, date: String, text: String) {
        val list = loadList(key).toMutableList()
        list.removeAll { it.first == date }
        list.add(0, Pair(date, text))
        val arr = JSONArray()
        for ((d, t) in list.take(60)) {
            arr.put(JSONObject().put("date", d).put("text", t))
        }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun loadList(key: String): List<Pair<String, String>> {
        val s = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Pair(o.optString("date"), o.optString("text")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- 推荐信号历史（用于「连续 N 天建议买入」统计） ----------------
    fun loadSignalHistory(): List<SignalSnapshot> {
        val s = sp.getString(KEY_SIGNALS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<SignalSnapshot>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val itemsArr = o.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<SignalItem>()
                for (j in 0 until itemsArr.length()) {
                    val iObj = itemsArr.optJSONObject(j) ?: continue
                    val secid = iObj.optString("secid")
                    if (secid.isEmpty()) continue
                    items.add(
                        SignalItem(
                            secid = secid,
                            name = iObj.optString("name"),
                            action = iObj.optString("action"),
                            score = iObj.optInt("score", 0),
                            price = if (iObj.has("price") && !iObj.isNull("price"))
                                iObj.optDouble("price") else null
                        )
                    )
                }
                out.add(SignalSnapshot(o.optString("date"), items))
            }
            out.sortedBy { it.date }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 写入当日快照（同日覆盖），最多保留最近 40 个交易日 */
    fun appendSignalSnapshot(snap: SignalSnapshot) {
        if (snap.items.isEmpty()) return
        val list = loadSignalHistory().filter { it.date != snap.date }.toMutableList()
        list.add(snap)
        val arr = JSONArray()
        for (day in list.sortedBy { it.date }.takeLast(40)) {
            val items = JSONArray()
            for (si in day.items) {
                val o = JSONObject()
                    .put("secid", si.secid)
                    .put("name", si.name)
                    .put("action", si.action)
                    .put("score", si.score)
                if (si.price != null) o.put("price", si.price)
                items.put(o)
            }
            arr.put(JSONObject().put("date", day.date).put("items", items))
        }
        sp.edit().putString(KEY_SIGNALS, arr.toString()).apply()
    }

    // ---------------- 报告涉及个股（供「＋关注」入口，按日期保存） ----------------
    fun appendReportStocks(date: String, rows: List<ReportStock>) {
        if (rows.isEmpty()) return
        val all = loadReportStocksAll().filter { it.first != date }.toMutableList()
        all.add(Pair(date, rows))
        val arr = JSONArray()
        for (entry in all.sortedBy { it.first }.takeLast(20)) {
            val items = JSONArray()
            for (r in entry.second) {
                val o = JSONObject()
                    .put("secid", r.secid)
                    .put("code", r.code)
                    .put("name", r.name)
                    .put("group", r.group)
                    .put("score", r.score)
                    .put("action", r.action)
                    .put("changePct", r.changePct)
                if (r.price != null) o.put("price", r.price)
                items.put(o)
            }
            arr.put(JSONObject().put("date", entry.first).put("items", items))
        }
        sp.edit().putString(KEY_REPORT_STOCKS, arr.toString()).apply()
    }

    fun loadReportStocks(date: String): List<ReportStock> =
        loadReportStocksAll().firstOrNull { it.first == date }?.second ?: emptyList()

    private fun loadReportStocksAll(): List<Pair<String, List<ReportStock>>> {
        val s = sp.getString(KEY_REPORT_STOCKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<Pair<String, List<ReportStock>>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val itemsArr = o.optJSONArray("items") ?: JSONArray()
                val rows = ArrayList<ReportStock>()
                for (j in 0 until itemsArr.length()) {
                    val r = itemsArr.optJSONObject(j) ?: continue
                    val secid = r.optString("secid")
                    if (secid.isEmpty()) continue
                    rows.add(
                        ReportStock(
                            secid = secid,
                            code = r.optString("code"),
                            name = r.optString("name"),
                            group = r.optString("group"),
                            score = r.optInt("score", 0),
                            action = r.optString("action"),
                            price = if (r.has("price") && !r.isNull("price"))
                                r.optDouble("price") else null,
                            changePct = r.optDouble("changePct", 0.0)
                        )
                    )
                }
                out.add(Pair(o.optString("date"), rows))
            }
            out.sortedBy { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- 设置 ----------------
    var scanIntervalMin: Int
        get() = sp.getInt(KEY_INTERVAL, 5).coerceIn(1, 60)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceIn(1, 60)).apply()

    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    var lastReportDate: String
        get() = sp.getString(KEY_LAST_REPORT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_REPORT, v).apply()

    var lastUsReportDate: String
        get() = sp.getString(KEY_LAST_US_REPORT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_US_REPORT, v).apply()

    var watchEnabled: Boolean
        get() = sp.getBoolean(KEY_WATCH_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_WATCH_ENABLED, v).apply()

    companion object {
        private const val KEY_WATCH = "watchlist"
        private const val KEY_SIGNALS = "signalHistory"
        private const val KEY_REPORT_STOCKS = "reportStocks"
        private const val KEY_LAST_ACTION = "lastAction"
        private const val KEY_REPORTS = "reports"
        private const val KEY_US_REPORTS = "usReports"
        private const val KEY_LAST_US_REPORT = "lastUsReportDate"
        private const val KEY_INTERVAL = "scanIntervalMin"
        private const val KEY_NOTIFY = "notifyEnabled"
        private const val KEY_LAST_REPORT = "lastReportDate"
        private const val KEY_WATCH_ENABLED = "watchEnabled"
    }
}
