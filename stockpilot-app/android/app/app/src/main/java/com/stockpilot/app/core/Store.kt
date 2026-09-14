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

    // ---------------- 报告 ----------------
    fun appendReport(date: String, text: String) {
        val list = loadReports().toMutableList()
        list.removeAll { it.first == date }
        list.add(0, Pair(date, text))
        val arr = JSONArray()
        for ((d, t) in list.take(60)) {
            arr.put(JSONObject().put("date", d).put("text", t))
        }
        sp.edit().putString(KEY_REPORTS, arr.toString()).apply()
    }

    fun loadReports(): List<Pair<String, String>> {
        val s = sp.getString(KEY_REPORTS, null) ?: return emptyList()
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

    var watchEnabled: Boolean
        get() = sp.getBoolean(KEY_WATCH_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_WATCH_ENABLED, v).apply()

    companion object {
        private const val KEY_WATCH = "watchlist"
        private const val KEY_LAST_ACTION = "lastAction"
        private const val KEY_REPORTS = "reports"
        private const val KEY_INTERVAL = "scanIntervalMin"
        private const val KEY_NOTIFY = "notifyEnabled"
        private const val KEY_LAST_REPORT = "lastReportDate"
        private const val KEY_WATCH_ENABLED = "watchEnabled"
    }
}
