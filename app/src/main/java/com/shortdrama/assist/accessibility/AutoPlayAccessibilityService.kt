package com.shortdrama.assist.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.shortdrama.assist.AppSettings
import com.shortdrama.assist.DevLog

/**
 * 短剧自动续播 + 广告跳过 无障碍服务。
 *
 * 通过节点树检测片尾提示与广告特征，自动执行上滑/点击。
 * - 只需开启无障碍，不需要 root 或额外授权
 * - 直接读节点树，不用截图识别，延迟更低
 * - 事件驱动 + 看门狗轮询，静止画面也能检测
 *
 * 检测策略与各项阈值参考了 ad-auto 项目
 * （https://github.com/clarithromycine/ad-auto），移植说明见仓库根目录 NOTICE.md。
 */
class AutoPlayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SDA11y"

        @Volatile
        var instance: AutoPlayAccessibilityService? = null
            private set

        /** 服务是否已连接（用户在系统设置里开启了无障碍） */
        fun isConnected(): Boolean = instance != null

        /**
         * 是否已在系统设置中勾选本服务。
         *
         * 不能用 [isConnected] 单独判断：服务刚被勾选时 bind 可能还没完成，
         * 而从设置页返回的一瞬间 UI 就会重建，此时 instance 仍是 null。
         */
        fun isEnabledInSettings(ctx: android.content.Context): Boolean {
            val expected = android.content.ComponentName(
                ctx.applicationContext,
                AutoPlayAccessibilityService::class.java,
            )
            val enabled = try {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            } catch (_: Throwable) {
                null
            } ?: return false

            val splitter = android.text.TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val name = splitter.next()
                val cn = android.content.ComponentName.unflattenFromString(name) ?: continue
                if (cn == expected) return true
            }
            return false
        }

        /** 跳系统无障碍设置页。部分 ROM 没有 ACTION_ACCESSIBILITY_SETTINGS，需兜底。 */
        fun openSettings(ctx: android.content.Context) {
            val direct = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(direct) }.isSuccess) return

            // 兜底：部分国内 ROM 只认"应用详情→无障碍"路径
            val appDetails = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(appDetails) }.isSuccess) return

            val allSettings = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(allSettings) }
        }
    }

    private var detector: DramaDetector? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        A11yState.connected = true
        A11yState.status = "已连接，监听中"
        detector = DramaDetector(this)
        // 看门狗：横屏静止广告/片尾可能一个无障碍事件都不发
        detector?.startWatchdog()
        // 前台服务保活：没有前台服务时 App 在后台会被 MIUI/HyperOS 挂起
        try {
            FloatingBallService.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "拉起前台服务失败: $t")
        }
        DevLog.event("a11y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        detector?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DevLog.event("a11y", "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detector?.stopWatchdog()
        detector = null
        // 只在"被销毁的正是当前实例"时才清状态：系统重新绑定时可能先建新实例再销毁旧实例，
        // 无条件清空会让 UI 一直停在"等待服务连接"。
        if (instance === this) {
            instance = null
            A11yState.connected = false
            A11yState.status = "服务已关闭"
        }
        DevLog.event("a11y", "无障碍服务已销毁")
        super.onDestroy()
    }

    /** 供 UI 的“立即检测”按钮调用 */
    fun scanOnce(callback: (String) -> Unit) {
        detector?.scanOnce(callback) ?: callback("无障碍服务尚未就绪")
    }

    /** 当前是否启用了任一自动功能 */
    fun hasAnyFeatureEnabled(): Boolean =
        AppSettings.isAutoContinueEnabled(this) || AppSettings.isAdSkipEnabled(this)
}
