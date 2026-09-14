package com.stockpilot.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：验证「重启手机后后台服务能否自动恢复」。
 * 注意：Android 14+ 对 BOOT_COMPLETED 启动前台服务有额外限制，这里用 try/catch 兜住，
 * 失败只记日志，不影响 App 使用。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        ProbeState.log("收到开机广播，尝试恢复后台服务")
        try {
            val i = Intent(context, WatchService::class.java)
            i.action = WatchService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
            ProbeState.log("开机自启启动成功")
        } catch (e: Exception) {
            ProbeState.log("开机自启失败（系统限制）：" + e.message)
        }
    }
}
