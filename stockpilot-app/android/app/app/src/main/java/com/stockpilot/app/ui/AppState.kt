package com.stockpilot.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stockpilot.app.core.DataSource
import com.stockpilot.app.core.Engine
import com.stockpilot.app.core.Sector
import com.stockpilot.app.core.StockRef
import com.stockpilot.app.core.Store
import com.stockpilot.app.core.Strategy
import com.stockpilot.app.core.WatchResult
import com.stockpilot.app.service.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 全局状态（在根 Composable 中 remember，切换 Tab 不丢失） */
class AppState(private val ctx: Context) {

    val ds = DataSource()
    val store = Store(ctx)
    val engine = Engine(ds, store)

    var tab by mutableStateOf(0)

    var watchlist by mutableStateOf(store.loadWatchlist())
    var results by mutableStateOf<List<WatchResult>>(emptyList())
    var loading by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    var sectors by mutableStateOf<List<Sector>>(emptyList())
    var recos by mutableStateOf<List<WatchResult>>(emptyList())
    var recoLoading by mutableStateOf(false)

    var reports by mutableStateOf(store.loadReports())
    var reportText by mutableStateOf("")

    var scanInterval by mutableStateOf(store.scanIntervalMin)
    var notifyEnabled by mutableStateOf(store.notifyEnabled)
    var watchEnabled by mutableStateOf(store.watchEnabled)

    var searchKw by mutableStateOf("")
    var searchResults by mutableStateOf<List<StockRef>>(emptyList())

    // ---------------- 自选 ----------------
    suspend fun refreshWatch() {
        if (watchlist.isEmpty()) {
            results = emptyList()
            status = "自选为空，请先添加股票"
            return
        }
        loading = true
        error = null
        try {
            val r = withContext(Dispatchers.IO) { engine.scan(watchlist) }
            results = r
            status = "已更新：" + r.size + " 只"
        } catch (e: Exception) {
            error = "获取失败：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            loading = false
        }
    }

    fun addWatch(ref: StockRef) {
        if (watchlist.any { it.secid == ref.secid }) return
        val list = watchlist.toMutableList()
        list.add(ref)
        watchlist = list
        store.saveWatchlist(list)
        searchResults = emptyList()
        searchKw = ""
    }

    fun removeWatch(secid: String) {
        val list = watchlist.filter { it.secid != secid }.toMutableList()
        watchlist = list
        store.saveWatchlist(list)
        results = results.filter { it.stock.secid != secid }
    }

    suspend fun search() {
        val kw = searchKw.trim()
        if (kw.isEmpty()) {
            searchResults = emptyList()
            return
        }
        try {
            searchResults = withContext(Dispatchers.IO) { ds.searchStocks(kw) }
            if (searchResults.isEmpty()) error = "未找到匹配的股票"
        } catch (e: Exception) {
            error = "搜索失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- 推荐（热点板块优选） ----------------
    suspend fun refreshRecommend() {
        recoLoading = true
        error = null
        try {
            val all = withContext(Dispatchers.IO) {
                val secs = ds.getHotSectors()
                    .sortedByDescending { Strategy.sectorHotness(it.changePct, it.mainFund, it.turnover) }
                    .take(3)
                sectors = secs
                val picks = ArrayList<StockRef>()
                val seen = HashSet<String>()
                for (s in secs) {
                    val briefs = try {
                        ds.getSectorStocks(s.code)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (b in briefs.sortedByDescending { it.changePct }.take(6)) {
                        if (seen.add(b.secid)) picks.add(StockRef(b.secid, b.code, b.name))
                    }
                }
                engine.scan(picks.take(18))
            }
            recos = all
            status = "推荐已更新：" + all.size + " 只"
        } catch (e: Exception) {
            error = "推荐获取失败：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            recoLoading = false
        }
    }

    // ---------------- 报告 ----------------
    suspend fun buildReportNow() {
        if (watchlist.isEmpty()) {
            reportText = "自选为空，无法生成报告"
            return
        }
        try {
            val text = withContext(Dispatchers.IO) {
                val r = engine.scan(watchlist)
                engine.buildAndSaveReport(r)
            }
            reportText = text
            reports = store.loadReports()
        } catch (e: Exception) {
            reportText = "生成失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun loadReport(date: String) {
        reportText = reports.firstOrNull { it.first == date }?.second ?: ""
    }

    // ---------------- 设置 / 服务 ----------------
    fun setInterval(min: Int) {
        scanInterval = min
        store.scanIntervalMin = min
    }

    fun setNotify(on: Boolean) {
        notifyEnabled = on
        store.notifyEnabled = on
    }

    fun startService() {
        try {
            val i = Intent(ctx, WatchService::class.java)
            i.action = WatchService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            watchEnabled = true
            store.watchEnabled = true
            status = "盯盘服务已启动"
        } catch (e: Exception) {
            error = "启动失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopService() {
        try {
            val i = Intent(ctx, WatchService::class.java)
            i.action = WatchService.ACTION_STOP
            ctx.startService(i)
            watchEnabled = false
            store.watchEnabled = false
            status = "盯盘服务已停止"
        } catch (e: Exception) {
            error = "停止失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    fun phaseLabel(): String = Strategy.marketPhase().label

    // ---------------- 系统设置跳转 ----------------
    fun openBatterySettings() {
        try {
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = android.net.Uri.parse("package:" + ctx.packageName)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i)
        } catch (e: Exception) {
            try {
                val i = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                ctx.startActivity(i)
            } catch (e2: Exception) {
                error = "无法打开电池优化设置"
            }
        }
    }

    fun openAppNotificationSettings() {
        try {
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i)
        } catch (e: Exception) {
            error = "无法打开通知设置"
        }
    }
}
