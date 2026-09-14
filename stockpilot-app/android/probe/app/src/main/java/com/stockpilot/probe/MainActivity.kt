package com.stockpilot.probe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 探针主界面：启动/停止后台服务、申请通知权限、申请电池优化白名单、实时状态与日志。
 * 全部用代码构建界面，不依赖任何第三方库。
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1

    private val uiLoop = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLog()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        ProbeState.log("App 启动")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "StockPilot 探针"
        title.textSize = 20f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(title)

        val desc = TextView(this)
        desc.text = "用途：验证兼容层（卓易通）下能否「后台存活 / 前台服务常驻 / 弹通知」。\n" +
            "步骤：① 允许通知 ② 加入电池白名单 ③ 启动后台服务 ④ 熄屏放置 10 分钟以上 ⑤ 回来看心跳是否连续。"
        desc.textSize = 13f
        desc.setTextColor(Color.parseColor("#555555"))
        desc.setPadding(0, dp(8), 0, dp(12))
        root.addView(desc)

        statusView = TextView(this)
        statusView.textSize = 14f
        statusView.setPadding(0, 0, 0, dp(12))
        root.addView(statusView)

        root.addView(mkButton("① 允许通知（Android 13+）") { requestNotifyPermission() })
        root.addView(mkButton("② 加入电池优化白名单") { requestIgnoreBattery() })
        root.addView(mkButton("③ 启动后台服务") { startWatch() })
        root.addView(mkButton("停止后台服务") { stopWatch() })
        root.addView(mkButton("立即发送一条测试通知") {
            Notifier.popup(this, "StockPilot 测试通知", "如果你看到了这条通知，说明系统通知可用 ✅")
            ProbeState.log("手动发送测试通知")
        })

        val logTitle = TextView(this)
        logTitle.text = "运行日志"
        logTitle.textSize = 14f
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        logTitle.setPadding(0, dp(14), 0, dp(6))
        root.addView(logTitle)

        val scroll = ScrollView(this)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        scroll.layoutParams = lp
        logView = TextView(this)
        logView.textSize = 12f
        logView.setTextColor(Color.parseColor("#333333"))
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)
        handler.post(uiLoop)
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiLoop)
        super.onDestroy()
    }

    private fun mkButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.gravity = Gravity.CENTER_VERTICAL
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("服务状态：").append(if (ProbeState.serviceRunning) "运行中 ✅" else "未运行 ❌").append('\n')
        sb.append("心跳次数：").append(ProbeState.tickCount).append('\n')
        sb.append("最近心跳：").append(ProbeState.lastTickTime).append('\n')
        sb.append("已运行：").append(ProbeState.aliveMinutes()).append(" 分钟\n")
        sb.append("通知权限：").append(if (hasNotifyPermission()) "已授权 ✅" else "未授权 ❌").append('\n')
        sb.append("电池白名单：").append(if (isIgnoringBattery()) "已加入 ✅" else "未加入 ❌")
        statusView.text = sb.toString()
    }

    private fun refreshLog() {
        val lines = ProbeState.logLines()
        if (lines.size == lastLogCount) return
        lastLogCount = lines.size
        logView.text = lines.joinToString("\n")
    }

    private fun hasNotifyPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        } else {
            ProbeState.log("当前系统无需单独申请通知权限")
        }
    }

    private fun isIgnoringBattery(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) { false }
    }

    private fun requestIgnoreBattery() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
            ProbeState.log("已弹出电池优化白名单申请")
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                ProbeState.log("无法打开电池优化设置：" + e2.message)
            }
        }
    }

    private fun startWatch() {
        try {
            val i = Intent(this, WatchService::class.java)
            i.action = WatchService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            ProbeState.log("已请求启动后台服务")
        } catch (e: Exception) {
            ProbeState.log("启动失败：" + e.message)
        }
    }

    private fun stopWatch() {
        try {
            val i = Intent(this, WatchService::class.java)
            i.action = WatchService.ACTION_STOP
            startService(i)
            ProbeState.log("已请求停止后台服务")
        } catch (e: Exception) {
            ProbeState.log("停止失败：" + e.message)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ProbeState.log(if (ok) "通知权限已授权 ✅" else "通知权限被拒绝 ❌（将收不到弹窗）")
        }
    }
}
