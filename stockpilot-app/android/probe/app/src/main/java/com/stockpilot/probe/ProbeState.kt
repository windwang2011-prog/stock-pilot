package com.stockpilot.probe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 探针状态：供界面轮询展示，并记录日志，方便把「后台存活情况」截图反馈。
 */
object ProbeState {

    @Volatile var serviceRunning = false
    @Volatile var tickCount = 0
    @Volatile var lastTickTime = "-"
    @Volatile var startedAt = 0L

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val logs = ArrayList<String>()
    private const val MAX_LOG = 200

    @Synchronized
    fun log(msg: String) {
        val line = fmt.format(Date()) + "  " + msg
        logs.add(line)
        while (logs.size > MAX_LOG) logs.removeAt(0)
    }

    @Synchronized
    fun logLines(): List<String> = ArrayList(logs)

    fun onStart() {
        serviceRunning = true
        startedAt = System.currentTimeMillis()
        log("前台服务已启动")
    }

    fun onStop() {
        serviceRunning = false
        log("前台服务已停止")
    }

    fun onTick(tick: Int, time: String) {
        tickCount = tick
        lastTickTime = time
    }

    /** 已运行时长（分钟，保留 1 位） */
    fun aliveMinutes(): String {
        if (startedAt == 0L) return "--"
        val min = (System.currentTimeMillis() - startedAt) / 60000.0
        return String.format(Locale.CHINA, "%.1f", min)
    }
}
