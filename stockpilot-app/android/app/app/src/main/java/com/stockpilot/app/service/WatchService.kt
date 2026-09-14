package com.stockpilot.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.stockpilot.app.core.DataSource
import com.stockpilot.app.core.Engine
import com.stockpilot.app.core.Report
import com.stockpilot.app.core.Store
import com.stockpilot.app.core.Strategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台盯盘服务
 *
 * 职责：
 *  1) 交易时段（集合竞价 / 盘中 / 午间）按设定间隔扫描自选股，动作变化时推送通知
 *  2) 收盘后（15:30 起）自动生成每日报告并通知（每天一次）；若那时未开机，之后启动会补生当天报告。
 *     报告含 大盘 / 热点板块 / 龙头 / 推荐连续性 / 自选 / 投资建议，与自选是否为空无关
 *  3) 北京时间 05:30-09:00 生成美股盘面热点报告（周日跳过）
 *  4) 通过常驻通知展示当前状态
 *
 * 实测已确认：在纯血鸿蒙（卓易通）环境下前台服务可长期存活、通知可正常弹出。
 */
class WatchService : Service() {

    private var worker: Thread? = null

    @Volatile
    private var running = false

    private var lastScanAt = 0L

    private lateinit var ds: DataSource
    private lateinit var store: Store
    private lateinit var engine: Engine

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        ds = DataSource()
        store = Store(this)
        engine = Engine(ds, store)
        startForeground(Notifier.FOREGROUND_ID, Notifier.foreground(this, "正在启动…"))
        running = true
        worker = Thread { loop() }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            store.watchEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        store.watchEnabled = true
        return START_STICKY
    }

    private fun loop() {
        while (running) {
            try {
                tick()
            } catch (e: Exception) {
                // 单次失败不影响循环
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        // ⓪ 美股盘后热点报告（北京时间 05:30-09:00，每天一次，与自选无关）
        if (engine.isUsReportWindow(now) && store.lastUsReportDate != engine.today(now)) {
            Notifier.updateForeground(this, "正在生成美股盘面热点报告…")
            try {
                val text = engine.buildAndSaveUsReport(now)
                if (store.notifyEnabled) {
                    Notifier.popup(
                        this, 9002, "StockPilot 美股盘面热点",
                        text.split("\n").take(12).joinToString("\n")
                    )
                }
                Notifier.updateForeground(this, "美股盘面报告已生成 · " + timeFmt.format(Date(now)))
            } catch (e: Exception) {
                Notifier.updateForeground(this, "美股报告生成失败，稍后重试")
            }
            return
        }

        val phase = Strategy.marketPhase(now)
        val stocks = store.loadWatchlist()

        // ① 每日报告（每天一次）——报告包含大盘/热门板块/龙头/推荐连续性 + 自选，
        //    因此与自选是否为空无关；若 15:30 未开机，之后启动会补生当天报告
        if (engine.isReportWindow(now) && store.lastReportDate != engine.today(now)) {
            Notifier.updateForeground(this, "正在生成每日报告…")
            val results = if (stocks.isEmpty()) emptyList() else engine.scan(stocks, now)
            val text = engine.buildAndSaveReport(results, now)
            if (store.notifyEnabled) {
                val head = text.split("\n").take(10).joinToString("\n")
                Notifier.popup(
                    this, 9001,
                    "StockPilot 每日报告（" + Report.summaryLine(results) + "）",
                    head
                )
            }
            Notifier.updateForeground(this, "每日报告已生成 · " + timeFmt.format(Date(now)))
            return
        }

        if (stocks.isEmpty()) {
            Notifier.updateForeground(this, phase.label + " · 自选为空，请先在 App 中添加自选股")
            return
        }

        // ② 交易时段扫描
        if (engine.shouldScan(now)) {
            val intervalMs = store.scanIntervalMin * 60_000L
            if (now - lastScanAt < intervalMs) {
                Notifier.updateForeground(
                    this,
                    phase.label + " · 待扫描（间隔 " + store.scanIntervalMin + " 分钟）· " +
                            timeFmt.format(Date(now))
                )
                return
            }
            lastScanAt = now
            Notifier.updateForeground(this, phase.label + " · 正在扫描 " + stocks.size + " 只…")
            val results = engine.scan(stocks, now)
            if (store.notifyEnabled) {
                for (r in results) {
                    if (r.notify) {
                        Notifier.popup(this, r.stock.secid.hashCode(), r.notifyTitle, r.notifyBody)
                    }
                }
            }
            val hit = results.count { it.notify }
            Notifier.updateForeground(
                this,
                phase.label + " · 已扫描 " + results.size + " 只" + (if (hit > 0) "，新信号 " + hit + " 条" else "") +
                        " · " + timeFmt.format(Date(now))
            )
        } else {
            Notifier.updateForeground(this, phase.label + " · 非交易时段，待命中 · " + timeFmt.format(Date(now)))
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.stockpilot.app.START"
        const val ACTION_STOP = "com.stockpilot.app.STOP"

        /** 循环心跳（秒）；真正的扫描间隔由设置项控制 */
        private const val TICK_MS = 20_000L
    }
}
