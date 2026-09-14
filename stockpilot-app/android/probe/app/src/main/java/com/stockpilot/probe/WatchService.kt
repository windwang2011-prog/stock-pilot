package com.stockpilot.probe

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务：每 30 秒一次心跳，更新常驻通知；每 5 次心跳弹一条通知。
 *
 * 验证目标：
 *  1) 服务能否在后台长期存活（心跳是否连续、时长是否持续增长）
 *  2) 常驻通知是否一直存在（不被系统清理）
 *  3) 普通通知能否正常弹出
 */
class WatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0

    private val loop = object : Runnable {
        override fun run() {
            tick++
            val now = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            ProbeState.onTick(tick, now)
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(Notifier.FOREGROUND_ID,
                    Notifier.foregroundNotification(this@WatchService,
                        "心跳 #$tick · $now · 已运行 ${ProbeState.aliveMinutes()} 分钟"))
            } catch (e: Exception) { /* ignore */ }

            if (tick % 5 == 0) {
                Notifier.popup(this@WatchService, "StockPilot 探针",
                    "后台存活正常：已 $tick 次心跳（$now），累计运行 ${ProbeState.aliveMinutes()} 分钟")
                ProbeState.log("已发送第 $tick 次心跳通知")
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        startForeground(Notifier.FOREGROUND_ID, Notifier.foregroundNotification(this, "正在启动…"))
        ProbeState.onStart()
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY   // 被系统回收后尽量自动重建，用于验证存活能力
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        ProbeState.onStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.stockpilot.probe.START"
        const val ACTION_STOP = "com.stockpilot.probe.STOP"
        const val INTERVAL_MS = 30_000L
    }
}
