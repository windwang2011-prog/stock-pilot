package com.stockpilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.stockpilot.app.MainActivity

/** 通知封装：渠道创建、前台服务常驻通知、买卖信号弹窗通知 */
object Notifier {

    const val CHANNEL_ID = "stockpilot_watch"
    const val CHANNEL_NAME = "StockPilot 盯盘提醒"
    const val FOREGROUND_ID = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        ch.description = "盯盘状态与买卖信号提醒"
        ch.enableVibration(true)
        mgr.createNotificationChannel(ch)
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, 0, i, flags)
    }

    private fun builder(ctx: Context): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID)
        else Notification.Builder(ctx)
    }

    /** 前台服务常驻通知 */
    fun foreground(ctx: Context, text: String): Notification {
        return builder(ctx)
            .setContentTitle("StockPilot 正在盯盘")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(ctx))
            .build()
    }

    /** 更新常驻通知（不重复弹窗、不响铃） */
    fun updateForeground(ctx: Context, text: String) {
        try {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(FOREGROUND_ID, foreground(ctx, text))
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 买卖信号弹窗通知；id 用 secid 哈希，保证不同个股互不覆盖 */
    fun popup(ctx: Context, id: Int, title: String, body: String) {
        try {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = builder(ctx)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(ctx))
                .build()
            mgr.notify(id, n)
        } catch (e: Exception) {
            // ignore
        }
    }
}
