// 前台服务保活思路参考 ad-auto（https://github.com/clarithromycine/ad-auto）：
// MIUI/HyperOS 在服务被停后约 20 秒就不再派发无障碍事件，必须常驻一条低优先级通知
// 把进程挂住。本项目只保留这层保活，不再显示任何悬浮窗口。
package com.shortdrama.assist.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shortdrama.assist.DevLog
import com.shortdrama.assist.DevLogOverlay
import com.shortdrama.assist.MainActivity

/**
 * 只做一件事：用一条低优先级常驻通知把进程挂在后台。
 *
 * 为什么还需要它：无障碍服务本身由 system_server 绑定，但在 MIUI/HyperOS 上
 * 进程退到后台一段时间后仍会被冻结，届时不再派发任何无障碍事件，规则就停了。
 * 顺带在这里记录「当前前台包名」，给悬浮日志（[DevLogOverlay]）判断该不该挂。
 *
 * 不显示悬浮球、不加任何窗口：那种浮在别人界面上的球既挡画面又要额外权限，
 * 实际收益只有「看一眼状态」，不如省掉。
 */
class KeepAliveService : Service() {

    companion object {
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "sd_accessibility_keepalive"
        private const val TAG = "SDKeepAlive"

        @Volatile
        var instance: KeepAliveService? = null
            private set

        /** 当前前台包名；由无障碍服务在窗口事件/兜底轮询里喂进来。 */
        @Volatile
        private var foregroundPackage: String? = null

        fun currentForeground(): String? = foregroundPackage

        /** 记录前台变化，并顺带让悬浮日志跟着显隐（它只该出现在规则对应的 App 上）。 */
        fun noteForeground(context: Context, pkg: String?) {
            if (pkg == foregroundPackage) return
            foregroundPackage = pkg
            DevLogOverlay.sync(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 无障碍服务可能先于界面被系统拉起，这里也初始化一遍，保证后台也往库里记日志
        DevLog.init(this)
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
            // 通知权限被拒绝等场景，忽略并继续
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动续播服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持无障碍服务在后台运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("短剧自动续播")
            .setContentText("无障碍服务运行中")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
