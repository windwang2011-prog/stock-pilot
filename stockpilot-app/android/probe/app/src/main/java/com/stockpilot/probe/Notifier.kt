package com.stockpilot.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 通知封装：渠道创建、前台服务常驻通知、普通弹窗通知。
 * 探针核心目的就是验证「系统通知能否正常弹出、前台服务通知能否常驻」。
 */
object Notifier {

    const val CHANNEL_ID = "stockpilot_probe"
    const val CHANNEL_NAME = "StockPilot 盯盘提醒"
    const val FOREGROUND_ID = 1001
    const val POPUP_ID = 2001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        ch.description = "盯盘信号与后台状态通知"
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

    fun foregroundNotification(ctx: Context, text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID)
        else Notification.Builder(ctx)
        return b.setContentTitle("StockPilot 正在盯盘")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(ctx))
            .build()
    }

    fun popup(ctx: Context, title: String, body: String) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID)
        else Notification.Builder(ctx)
        b.setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
        mgr.notify(POPUP_ID, b.build())
    }
}
