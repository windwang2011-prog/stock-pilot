package com.stockpilot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：重启手机后自动恢复盯盘服务（若用户此前已开启）。
 * Android 14+ 对 BOOT_COMPLETED 启动前台服务有限制，这里用 try/catch 兜住，
 * 失败仅记录，不影响 App 正常使用。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        try {
            val store = com.stockpilot.app.core.Store(context)
            if (!store.watchEnabled) return
            val i = Intent(context, WatchService::class.java)
            i.action = WatchService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            // 系统限制导致失败时静默忽略
        }
    }
}
