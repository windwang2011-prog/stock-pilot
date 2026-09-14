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
 *  2) 收盘后（15:30-16:00）自动生成盘后汇总报告并通知（每天一次）
 *  3) 通过常驻通知展示当前状态
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
        val phase = Strategy.marketPhase(now)
        val stocks = store.loadWatchlist()

        if (stocks.isEmpty()) {
            Notifier.updateForeground(this, phase.label + " · 自选为空，请先在 App 中添加自选股")
            return
        }

        // ① 盘后报告（每天一次）
        if (engine.isReportWindow(now) && store.lastReportDate != engine.today(now)) {
            Notifier.updateForeground(this, "正在生成盘后汇总报告…")
            val results = engine.scan(stocks, now)
            val text = engine.buildAndSaveReport(results, now)
            if (store.notifyEnabled) {
                val head = text.split("\n").take(8).joinToString("\n")
                Notifier.popup(
                    this, 9001,
                    "StockPilot 盘后汇总（" + Report.summaryLine(results) + "）",
                    head
                )
            }
            Notifier.updateForeground(this, "盘后报告已生成 · " + timeFmt.format(Date(now)))
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
