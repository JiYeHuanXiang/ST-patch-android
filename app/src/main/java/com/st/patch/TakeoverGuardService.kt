package com.st.patch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.st.patch.common.TakeoverBeacon
import com.st.patch.common.TermuxBridge

/**
 * 接管守卫：前台服务，保活 app 进程（使 TakeoverBeacon 心跳端口持续监听）+ 运行通知 + 停止入口。
 * onTaskRemoved 兜底即时停止；onDestroy 关闭 beacon 并停 Termux 酒馆。
 * （若 app 被系统强杀、生命周期未触发，beacon 端口随进程关闭，Termux 守卫脚本会 kill node。）
 */
class TakeoverGuardService : Service() {

    companion object {
        private const val TAG = "TakeoverGuard"
        private const val CHANNEL_ID = "takeover_guard"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.st.patch.TAKEOVER_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        val notif = buildNotification("接管运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉任务 → 即时停止 Termux 酒馆（兜底，不等守卫脚本探测周期）
        Log.i(TAG, "onTaskRemoved -> stop termux ST")
        TakeoverBeacon.close()
        TermuxBridge.stopTermuxSt(this)
        TermuxBridge.releaseWakeLock(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        TakeoverBeacon.close()
        TermuxBridge.stopTermuxSt(this)
        TermuxBridge.releaseWakeLock(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "接管模式", NotificationManager.IMPORTANCE_LOW).apply {
                description = "接管模式运行状态"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, WebViewActivity.DEFAULT_URL)
            putExtra(WebViewActivity.EXTRA_STOP_MODE, WebViewActivity.STOP_MODE_TERMUX)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPI = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TakeoverGuardService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("SillyTavern 接管")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPI)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPI)
            .build()
    }
}
