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

        /** 应用包名前缀（debug 变体会多一个 .debug 后缀，Activity 类名始终在这个前缀下）。 */
        private const val APP_PACKAGE_PREFIX = "com.shortdrama.assist"

        @Volatile
        var instance: AutoPlayAccessibilityService? = null
            private set

        /** 服务是否已连接（用户在系统设置里开启了无障碍） */
        fun isConnected(): Boolean = instance != null

        /** 最近一条无障碍事件里的包名；只用来给「初始同步」兜底，不作为前台判定依据。 */
        @Volatile
        private var lastSeenPackage: String? = null

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
        // 无障碍服务可能先于界面被系统拉起，这里也要初始化，否则「开发者日志」开关读不到、
        // 规则命中的日志就只进 logcat 不入库。
        DevLog.init(this)
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
        // 刚连上时前台可能已经是红果了，那条窗口切换事件我们没赶上；
        // 只做一次初始同步不够（可能读到桌面），这里起一个 2 秒轮询兜底，
        // noteForeground 内部会去重，包名没变不会反复显隐。
        handler.removeCallbacks(syncTicker)
        handler.postDelayed(syncTicker, 800L)
    }

    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 兜底轮询：把「当前真正在前台的包名」喂给悬浮球判定。 */
    private val syncTicker = object : Runnable {
        override fun run() {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                ?: lastSeenPackage
            // 球和菜单本身就是悬浮窗，抢到焦点时 rootInActiveWindow 会报成我们自己的包名；
            // 轮询里必须忽略它，否则球一出现就把自己判成非目标 App，然后立刻收起 → 疯狂闪烁。
            // 用户真的切到本 App 时由窗口切换事件负责隐藏，不靠这个轮询。
            if (pkg != null && pkg != packageName) {
                FloatingBallService.noteForeground(this@AutoPlayAccessibilityService, pkg)
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 悬浮球只在规则对应的 App 前台时露头：这里把窗口切换的包名喂过去。
        // 只认 TYPE_WINDOW_STATE_CHANGED —— 内容变化事件量极大，且包名会在
        // 目标 App / 输入法 / 系统弹窗之间来回跳，跟着它走球会疯狂显隐。
        val pkg = event.packageName?.toString()
        if (pkg != null) lastSeenPackage = pkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            // 球自己是个悬浮窗，加进 WindowManager 时也会发一条 WINDOW_STATE_CHANGED，
            // 包名正是本 App。照单全收的话：加球 → 判定为「非目标 App」→ 1.2s 后收球 →
            // 下一轮轮询又把球加回来 → 无限循环，看起来就是一闪一现。
            // 所以本 App 的窗口只认真正的 Activity（className 带我们的包名前缀），其余忽略。
            // 注意 debug 变体包名是 com.shortdrama.assist.debug，而 Activity 类名在
            // com.shortdrama.assist 下，必须用类名前缀比较，不能拿 packageName 比。
            val className = event.className?.toString()
            val ownActivity = pkg == packageName && className != null &&
                className.startsWith(APP_PACKAGE_PREFIX)
            if (pkg != packageName || ownActivity) {
                FloatingBallService.noteForeground(this, pkg)
            }
        }
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
        handler.removeCallbacks(syncTicker)
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
