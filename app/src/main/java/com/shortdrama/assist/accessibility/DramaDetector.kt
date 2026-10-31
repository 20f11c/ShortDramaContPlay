// 检测策略与阈值参考 ad-auto（https://github.com/clarithromycine/ad-auto），
// 本项目为独立重写实现；移植清单与许可说明见仓库根目录 NOTICE.md。
package com.shortdrama.assist.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shortdrama.assist.rules.RuleEngine
import com.shortdrama.assist.rules.StatsStore
import com.shortdrama.assist.AppSettings
import com.shortdrama.assist.DevLog
import java.util.concurrent.Executors

/**
 * 短剧自动续播 + 广告跳过检测器。
 * 
 * 基于 ad-auto 的 AdDetector 改造，增加了片尾待续播检测作为最高优先级。
 * 
 * 核心逻辑：
 * 1. 监听无障碍事件（窗口切换 / 内容变化）
 * 2. 遍历当前屏幕所有窗口的节点树
 * 3. 按 DramaRules 优先级匹配并执行动作
 * 4. 看门狗定时轮询（解决静止广告/片尾无事件问题）
 */
class DramaDetector(private val service: AccessibilityService) {

    companion object {
        const val TAG = "DramaDetector"
        
        private val NON_TARGET_PACKAGES = setOf(
            "com.shortdrama.assist",
            "com.android.systemui",
            "miui.systemui.plugin",
        )
        
        /** 窗口切换后延迟重扫间隔 */
        private const val RETRY_DELAY_MS = 350L
        /** 看门狗轮询间隔 */
        private const val WATCHDOG_MS = 3_000L
        /** 距上次检测不足该时长时看门狗不重复扫 */
        private const val WATCHDOG_MIN_IDLE_MS = 2_500L

        /**
         * 内容变化事件的最小扫描间隔。
         *
         * 红果首页有轮播/动画，TYPE_WINDOW_CONTENT_CHANGED 能密集到每 50ms 一条。
         * 每条都全树遍历（实测 80+ 节点）会明显耗电，这里限到约每秒 3 次。
         */
        private const val MIN_SCAN_INTERVAL_MS = 350L
        
        /** 两次动作之间的最小间隔 */
        private const val MIN_ACTION_INTERVAL_MS = 1_500L
        /** 穿山甲广告冷却时间 */
        private const val PANGLE_AD_COOLDOWN_MS = 6_000L
        
        /** 广告连续上滑相关常量 */
        private const val AD_SWIPE_MIN_RETRY_GAP_MS = 700L
        private const val AD_SWIPE_FAST_ATTEMPTS = 7
        private const val AD_SWIPE_WATCHDOG_GAP_MS = 1_500L
        private const val AD_SWIPE_TOTAL_BUDGET_MS = 40_000L
        private const val AD_SWIPE_MAX_ATTEMPTS = 30
        private const val AD_SWIPE_STREAK_RESET_MS = 10_000L
        
        /** 稀疏树广告确认时长 */
        private const val SPARSE_AD_CONFIRM_MS = 800L
        
        /** 横屏橙色按钮检测相关 */
        private const val ORANGE_SAMPLE_STEP = 4
        private const val ORANGE_FILL_MIN = 0.80f
        private const val ORANGE_MIN_WIDTH_FRAC = 0.25f
        private const val ORANGE_MIN_HEIGHT_PX = 20
        private const val ORANGE_MAX_HEIGHT_FRAC = 0.30f
        private const val ORANGE_MIN_ASPECT = 2.5f
        private const val ORANGE_MIN_CENTER_Y_FRAC = 0.30f
        private const val ORANGE_CTA_FILL_MIN = 0.75f
        private const val ORANGE_CTA_MIN_WIDTH_FRAC = 0.05f
        private const val ORANGE_CTA_MAX_WIDTH_FRAC = 0.20f
        private const val ORANGE_CTA_MIN_HEIGHT_FRAC = 0.03f
        private const val ORANGE_CTA_MAX_HEIGHT_FRAC = 0.15f
        private const val ORANGE_CTA_MIN_CENTER_X_FRAC = 0.75f
        private const val ORANGE_CTA_MIN_CENTER_Y_FRAC = 0.80f
        private const val ORANGE_MIN_CELLS = 4
        private const val ORANGE_RECT_SPAN_FRAC = 0.80f
        private const val ORANGE_RECT_ROW_COVER_MIN = 0.90f
        private const val ORANGE_RECT_COL_COVER_MIN = 0.88f
        private const val ORANGE_PROBE_INTERVAL_MS = 1_200L
        private const val ORANGE_RATIO_FRESH_MS = 2_500L
        private const val ORANGE_RETRY_MS = 1_300L
        private const val ORANGE_HITS_REQUIRED = 2
        private const val LANDSCAPE_POLL_MS = 1_500L
        private const val LANDSCAPE_POLL_MAX = 40
        
        @Volatile
        var isDragging = false
    }

    private val handler = Handler(Looper.getMainLooper())
    
    // 倒计时状态
    private var countdownDeadlineAt = 0L
    private var lastParsedSeconds = -1
    
    // 动作节流
    private var nextAllowedAt = 0L
    private var lastActionAt = 0L
    
    // 广告上滑链
    private var adSwipeStreak = 0
    private var lastAdSwipeAt = 0L
    private var adSwipeChainStartedAt = 0L
    private var lastCooldownNoticeAt = 0L
    
    // 稀疏树确认
    private var sparseAdSinceAt = 0L
    
    // 截图探测
    private val probeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var orangeSeq = 0L
    @Volatile private var orangeIsButton = false
    @Volatile private var orangeInfo = ""
    @Volatile private var orangeAt = 0L
    @Volatile private var orangeProbeInFlight = false
    private var lastOrangeProbeAt = 0L
    private var lastCountedOrangeSeq = 0L
    private var orangeHitCount = 0
    private var orangeRetryPending = false
    private var lastLoggedOrangeSeq = 0L
    private var landscapePollLeft = LANDSCAPE_POLL_MAX
    private var nextLandscapePollAt = 0L
    private var lastDetectAt = 0L
    /** 上次打印过的页面文本，只在变化时打日志 */
    private var lastLoggedPageText = ""
    
    // 延迟重扫
    private var retryPending = false
    private val retryRunnable = Runnable {
        retryPending = false
        if (isEnabled()) {
            detectAndAct(isWindowStateChange = false)
        }
    }
    
    // 看门狗
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (isEnabled() && !isDragging) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastDetectAt >= WATCHDOG_MIN_IDLE_MS) {
                        detectAndAct(isWindowStateChange = false)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "看门狗检测异常: $t")
            } finally {
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    private fun isEnabled(): Boolean {
        return AppSettings.isA11yMode(service) && 
               (AppSettings.isAutoContinueEnabled(service) || AppSettings.isAdSkipEnabled(service))
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isEnabled()) return
        
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        
        if (isDragging) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 新窗口 = 重置状态
            countdownDeadlineAt = 0L
            lastParsedSeconds = -1
            resetAdSwipeChain()
            sparseAdSinceAt = 0L
            orangeAt = 0L
            orangeHitCount = 0
            orangeRetryPending = false
            landscapePollLeft = LANDSCAPE_POLL_MAX
            nextLandscapePollAt = 0L
        }

        // 内容变化事件节流：红果首页有轮播/动画，CONTENT_CHANGED 能到每 50ms 一次，
        // 不节流就是持续全树遍历（耗电、发热）。窗口切换事件不节流。
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastDetectAt < MIN_SCAN_INTERVAL_MS
        ) {
            return
        }

        detectAndAct(isWindowStateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
    }

    fun scanOnce(onResult: (String) -> Unit = {}) {
        detectAndAct(onResult)
    }

    private fun resetAdSwipeChain() {
        adSwipeStreak = 0
        adSwipeChainStartedAt = 0L
    }

    private fun detectAndAct(onResult: (String) -> Unit = {}, isWindowStateChange: Boolean = false) {
        if (isDragging) return
        lastDetectAt = SystemClock.uptimeMillis()
        
        val fgPkg = foregroundPackage() ?: run {
            report("无障碍服务尚未就绪", onResult)
            return
        }
        A11yState.foregroundPackage = fgPkg

        if (fgPkg in NON_TARGET_PACKAGES) {
            report("当前界面无需检测", onResult)
            return
        }

        if (fgPkg !in DramaRules.supportedPackagesList()) {
            report("当前 App ($fgPkg) 不在检测范围内", onResult)
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectFromAllWindows(nodes, fgPkg)
        A11yState.lastNodeCount = nodes.size
        if (nodes.isEmpty()) {
            report("未获取到窗口节点", onResult)
            return
        }

        val pageText = DramaRules.pageTextOf(nodes)
        A11yState.lastText = pageText.take(400)
        val frame = screenFrame()
        val frameW = frame?.width() ?: 0
        val frameH = frame?.height() ?: 0

        Log.d(TAG, "scan: fg=$fgPkg nodes=${nodes.size} textLen=${pageText.length}")

        // 页面文本变化时才打印，避免刷屏；排查误判时这是最关键的一行
        if (pageText != lastLoggedPageText) {
            lastLoggedPageText = pageText
            Log.d(TAG, "pageText[${fgPkg}]: ${pageText.take(160)}")
        }
        
        // 订阅规则优先；规则包未命中或被护栏拦下时回退到内置规则
        var action = RuleEngine.match(service, nodes, pageText, frameW, frameH, fgPkg)
            ?: DramaRules.match(nodes, pageText, frameW, frameH, fgPkg)
        
        // 检查功能开关
        if (action != null) {
            val isEndCard = action.reason == DramaRules.REASON_END_CARD
            val isAd = !isEndCard
            
            if (isEndCard && !AppSettings.isAutoContinueEnabled(service)) {
                action = null
            } else if (isAd && !AppSettings.isAdSkipEnabled(service)) {
                action = null
            }
        }
        
        // 横屏橙色按钮检测（仅广告跳过）
        val nowOrange = SystemClock.uptimeMillis()
        val landscapeAmbiguous = action == null && 
            AppSettings.isAdSkipEnabled(service) &&
            isLandscapeAmbiguous(frameW, frameH, pageText, fgPkg)
            
        if (landscapeAmbiguous && nowOrange >= nextAllowedAt) {
            val fresh = orangeAt != 0L && nowOrange - orangeAt <= ORANGE_RATIO_FRESH_MS
            if (!fresh) {
                requestOrangeProbe()
                if (!orangeRetryPending) {
                    orangeRetryPending = true
                    scheduleRetry(ORANGE_RETRY_MS, "橙色按钮探测")
                }
                recycleAll(nodes)
                return
            }
            orangeRetryPending = false
            if (orangeIsButton && orangeSeq != lastCountedOrangeSeq) {
                lastCountedOrangeSeq = orangeSeq
                orangeHitCount++
            } else if (!orangeIsButton) {
                orangeHitCount = 0
            }
            if (orangeSeq != lastLoggedOrangeSeq) {
                lastLoggedOrangeSeq = orangeSeq
                Log.d(TAG, "横屏橙色检测: $orangeInfo 命中 $orangeHitCount/$ORANGE_HITS_REQUIRED")
            }
            if (orangeHitCount >= ORANGE_HITS_REQUIRED) {
                orangeHitCount = 0
                orangeAt = 0L
                action = DramaAction(DramaActionType.SWIPE_UP, null, DramaRules.REASON_ORANGE_AD, immediate = true)
            } else if (orangeIsButton) {
                requestOrangeProbe()
                orangeRetryPending = true
                scheduleRetry(ORANGE_RETRY_MS, "橙色按钮确认")
                recycleAll(nodes)
                return
            }
        } else if (!landscapeAmbiguous) {
            orangeHitCount = 0
        }
        
        // 横屏模糊态轮询
        if (landscapeAmbiguous && action == null && landscapePollLeft > 0 &&
            nowOrange >= nextLandscapePollAt
        ) {
            landscapePollLeft--
            val wait = if (nowOrange < nextAllowedAt) {
                (nextAllowedAt - nowOrange).coerceAtLeast(200L)
            } else {
                LANDSCAPE_POLL_MS
            }
            nextLandscapePollAt = nowOrange + wait
            requestOrangeProbe()
            scheduleRetry(wait, "横屏轮询(剩 $landscapePollLeft)")
        }
        
        // 稀疏树确认
        if (action?.reason != DramaRules.REASON_SPARSE_AD) {
            sparseAdSinceAt = 0L
        } else {
            val nowSparse = SystemClock.uptimeMillis()
            if (sparseAdSinceAt == 0L) sparseAdSinceAt = nowSparse
            val held = nowSparse - sparseAdSinceAt
            if (held < SPARSE_AD_CONFIRM_MS) {
                scheduleRetry((SPARSE_AD_CONFIRM_MS - held).coerceAtLeast(50L), "稀疏树确认")
                recycleAll(nodes)
                return
            }
        }
        
        if (action != null) {
            // 倒计时等待（仅广告）
            val isEndCard = action.reason == DramaRules.REASON_END_CARD
            if (!isEndCard && action.type == DramaActionType.SWIPE_UP && withinCountdownWindow(pageText)) {
                resetAdSwipeChain()
                nextAllowedAt = 0
                val remain = countdownDeadlineAt - SystemClock.uptimeMillis()
                Log.i(TAG, "检测到倒计时，滑动推迟 ${remain}ms")
                scheduleRetry(remain.coerceAtLeast(50L), "倒计时结束")
                recycleAll(nodes)
                return
            }
            
            // 冷却检查
            val isAdSwipe = action.type == DramaActionType.SWIPE_UP && !isEndCard
            val now = SystemClock.uptimeMillis()
            val sinceLastAdSwipe = now - lastAdSwipeAt
            if (isAdSwipe && sinceLastAdSwipe > AD_SWIPE_STREAK_RESET_MS) resetAdSwipeChain()
            val chainElapsed = if (adSwipeChainStartedAt == 0L) 0L else now - adSwipeChainStartedAt
            val chainGap = if (adSwipeStreak < AD_SWIPE_FAST_ATTEMPTS) {
                AD_SWIPE_MIN_RETRY_GAP_MS
            } else {
                AD_SWIPE_WATCHDOG_GAP_MS
            }
            val adSwipeRetry = isAdSwipe &&
                adSwipeStreak in 1 until AD_SWIPE_MAX_ATTEMPTS &&
                chainElapsed <= AD_SWIPE_TOTAL_BUDGET_MS &&
                sinceLastAdSwipe >= chainGap
                
            if (now < nextAllowedAt && !adSwipeRetry) {
                if (now - lastCooldownNoticeAt >= 500L) {
                    lastCooldownNoticeAt = now
                    val remain = (nextAllowedAt - now).coerceAtLeast(50L)
                    scheduleRetry(remain, "冷却结束补扫")
                }
                recycleAll(nodes)
                return
            }
            
            // 设置冷却
            val cooldown = if (action.reason.contains("穿山甲")) {
                PANGLE_AD_COOLDOWN_MS
            } else {
                MIN_ACTION_INTERVAL_MS
            }
            nextAllowedAt = now + cooldown
            lastActionAt = now
            landscapePollLeft = LANDSCAPE_POLL_MAX
            nextLandscapePollAt = 0L
            
            Log.i(TAG, "执行动作: ${action.type} (${action.reason})")
            DevLog.event("a11y", "${action.reason}")
            A11yState.lastAction = action.reason
            A11yState.lastActionAt = now

            val ok = perform(action)
            if (action.reason == DramaRules.REASON_SPARSE_AD) sparseAdSinceAt = 0L
            if (ok) {
                runCatching { StatsStore.bump(service, fgPkg, statLabelOf(fgPkg)) }
            }

            Log.i(TAG, "执行${if (ok) "成功" else "失败"}: ${action.type} (${action.reason})")
            report(if (ok) "已执行: ${action.reason}" else "动作执行失败", onResult)
            
            // 广告上滑链补扫
            if (isAdSwipe) {
                if (adSwipeChainStartedAt == 0L) adSwipeChainStartedAt = now
                adSwipeStreak += 1
                lastAdSwipeAt = now
                val chainElapsedNow = now - adSwipeChainStartedAt
                val inBudget = adSwipeStreak < AD_SWIPE_MAX_ATTEMPTS &&
                    chainElapsedNow <= AD_SWIPE_TOTAL_BUDGET_MS
                if (inBudget) {
                    val gap = if (adSwipeStreak < AD_SWIPE_FAST_ATTEMPTS) {
                        AD_SWIPE_MIN_RETRY_GAP_MS
                    } else {
                        AD_SWIPE_WATCHDOG_GAP_MS
                    }
                    scheduleRetry(gap, "广告上滑补扫")
                }
            }
        } else {
            report("未检测到片尾或广告", onResult)
            maybeScheduleRetry(fgPkg, isWindowStateChange, pageText)
        }
        recycleAll(nodes)
    }

    /** 统一上报：同时写状态对象和回调，避免 UI 与日志不一致。 */
    private fun report(msg: String, onResult: (String) -> Unit) {
        A11yState.status = msg
        onResult(msg)
    }

    private fun foregroundPackage(): String? {
        val windows = try { service.windows } catch (_: Throwable) { emptyList() }
        for (w in windows) {
            if (!w.isActive) continue
            val root = try { w.root } catch (_: Throwable) { null } ?: continue
            try {
                root.packageName?.toString()?.let { return it }
            } finally {
                recycleQuietly(root)
            }
        }
        return try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) { null }
    }

    private fun screenFrame(): Rect? {
        val windows = try { service.windows } catch (_: Throwable) { emptyList() }
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive) continue
            val root = w.root ?: continue
            try {
                val r = Rect()
                root.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            } finally {
                @Suppress("DEPRECATION")
                try { root.recycle() } catch (_: Throwable) {}
            }
        }
        val dm = service.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun withinCountdownWindow(pageText: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val remaining = DramaRules.remainingCountdownSeconds(pageText)
        if (remaining != null && remaining in 1..30) {
            val deadline = now + remaining * 1000L + 300L
            val isRestart = remaining > lastParsedSeconds
            val isEarlier = countdownDeadlineAt != 0L && deadline < countdownDeadlineAt
            if (countdownDeadlineAt == 0L || isRestart || isEarlier) {
                countdownDeadlineAt = deadline
            }
            lastParsedSeconds = remaining
        }
        if (countdownDeadlineAt != 0L && now < countdownDeadlineAt) return true
        countdownDeadlineAt = 0L
        lastParsedSeconds = -1
        return false
    }

    private fun maybeScheduleRetry(fgPkg: String, isWindowStateChange: Boolean, pageText: String) {
        if (withinCountdownWindow(pageText)) {
            nextAllowedAt = 0
            resetAdSwipeChain()
            val remain = countdownDeadlineAt - SystemClock.uptimeMillis()
            scheduleRetry(remain.coerceAtLeast(50L), "倒计时结束")
            return
        }
        if (!isWindowStateChange) return
        if (fgPkg !in DramaRules.supportedPackagesList()) return
        scheduleRetry(RETRY_DELAY_MS, "窗口切换")
    }

    private fun scheduleRetry(delay: Long, reason: String) {
        handler.removeCallbacks(retryRunnable)
        retryPending = true
        handler.postDelayed(retryRunnable, delay)
    }

    private fun isLandscapeAmbiguous(frameW: Int, frameH: Int, pageText: String, fgPkg: String): Boolean {
        if (frameW <= 0 || frameH <= 0 || frameW <= frameH) return false
        if (fgPkg != DramaRules.HONGUO_PACKAGE) return false
        return !DramaRules.hasPlaybackControls(pageText)
    }

    /** 统计卡片上显示的短名，不认识就退回包名末段。 */
    private fun statLabelOf(pkg: String): String = when (pkg) {
        DramaRules.HONGUO_PACKAGE -> "红果"
        else -> pkg.substringAfterLast('.').take(6)
    }

    private fun perform(action: DramaAction): Boolean {
        return when (action.type) {
            DramaActionType.CLICK -> {
                val node = action.node ?: return false
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) { false }
            }
            DramaActionType.SWIPE_UP -> swipe(up = true)
            DramaActionType.SWIPE_DOWN -> swipe(up = false)
            DramaActionType.SWIPE_LEFT -> swipe(horizontal = true, toRight = false)
            DramaActionType.SWIPE_RIGHT -> swipe(horizontal = true, toRight = true)
            DramaActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun swipe(up: Boolean = true, horizontal: Boolean = false, toRight: Boolean = true): Boolean {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val path = Path()
        if (horizontal) {
            val fromX = if (toRight) w * 0.2f else w * 0.8f
            val toX = if (toRight) w * 0.8f else w * 0.2f
            path.moveTo(fromX, h * 0.5f)
            path.lineTo(toX, h * 0.5f)
        } else {
            val fromY = if (up) h * 0.75f else h * 0.25f
            val toY = if (up) h * 0.25f else h * 0.75f
            path.moveTo(w * 0.5f, fromY)
            path.lineTo(w * 0.5f, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun requestOrangeProbe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.uptimeMillis()
        if (orangeProbeInFlight || now - lastOrangeProbeAt < ORANGE_PROBE_INTERVAL_MS) return
        orangeProbeInFlight = true
        lastOrangeProbeAt = now
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                probeExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var isButton = false
                        var info = "截图解析失败"
                        try {
                            val hw = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            if (bmp != null) {
                                val r = computeOrangeBar(bmp)
                                isButton = r.first
                                info = r.second
                            }
                            try { hw.close() } catch (_: Throwable) {}
                        } catch (t: Throwable) {
                            Log.w(TAG, "橙色探测解析失败: $t")
                        } finally {
                            onOrangeResult(isButton, info)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        onOrangeResult(false, "截图失败 code=$errorCode")
                    }
                },
            )
        } catch (t: Throwable) {
            onOrangeResult(false, "调用截图异常")
        }
    }

    private fun onOrangeResult(isButton: Boolean, info: String) {
        orangeIsButton = isButton
        orangeInfo = info
        orangeSeq++
        orangeAt = SystemClock.uptimeMillis()
        orangeProbeInFlight = false
    }

    private fun computeOrangeBar(src: Bitmap): Pair<Boolean, String> {
        val sw = try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
            Canvas(out).drawBitmap(src, 0f, 0f, null)
            out
        } catch (t: Throwable) {
            try { src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
        } ?: return false to "copy bitmap 失败"
        
        try {
            val w = sw.width
            val h = sw.height
            if (w <= 0 || h <= 0) return false to "截图尺寸异常"
            
            val step = ORANGE_SAMPLE_STEP
            val gw = (w + step - 1) / step
            val gh = (h + step - 1) / step
            val totalCells = gw * gh
            val grid = IntArray(totalCells)
            val row = IntArray(w)
            val accR = IntArray(gw)
            val accG = IntArray(gw)
            val accB = IntArray(gw)
            val accN = IntArray(gw)
            var orangeCells = 0
            var gy = 0
            
            for (y in 0 until h) {
                sw.getPixels(row, 0, w, 0, y, w, 1)
                for (gx in 0 until gw) {
                    val xs = gx * step
                    val xe = if (xs + step < w) xs + step else w
                    var sr = 0; var sg = 0; var sb = 0
                    for (x in xs until xe) {
                        val c = row[x]
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                    }
                    accR[gx] += sr; accG[gx] += sg; accB[gx] += sb; accN[gx] += xe - xs
                }
                if ((y + 1) % step == 0 || y == h - 1) {
                    val base = gy * gw
                    for (gx in 0 until gw) {
                        val n = accN[gx]
                        if (n > 0) {
                            val r = accR[gx] / n; val g = accG[gx] / n; val b = accB[gx] / n
                            if (r >= 140 && g in 40..200 && b <= 110 && r - b >= 70 && r >= g && g >= b) {
                                grid[base + gx] = (r shl 16) or (g shl 8) or b
                                orangeCells++
                            }
                        }
                        accR[gx] = 0; accG[gx] = 0; accB[gx] = 0; accN[gx] = 0
                    }
                    gy++
                }
            }
            
            if (orangeCells == 0) return false to "无橙色像素"
            
            // 连通块分析（简化版）
            val visited = BooleanArray(totalCells)
            // 复用同一个栈，避免每个连通块都新建大数组
            val stack = IntArray(orangeCells.coerceAtLeast(1))
            var bestSize = 0
            var barHit = false
            var ctaHit = false

            for (start in 0 until totalCells) {
                if (grid[start] == 0 || visited[start]) continue
                var sp = 0
                stack[sp++] = start
                visited[start] = true
                var size = 0
                var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE
                var x1 = -1; var y1 = -1
                
                while (sp > 0) {
                    val idx = stack[--sp]
                    size++
                    val cx = idx % gw; val cy = idx / gw
                    if (cx < x0) x0 = cx; if (cx > x1) x1 = cx
                    if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
                    for (d in intArrayOf(-1, 1, -gw, gw)) {
                        val n = idx + d
                        if (n in 0 until totalCells && grid[n] != 0 && !visited[n]) {
                            if (d == -1 && cx == 0) continue
                            if (d == 1 && cx == gw - 1) continue
                            visited[n] = true
                            stack[sp++] = n
                        }
                    }
                }
                
                if (size < ORANGE_MIN_CELLS) continue
                if (size > bestSize) bestSize = size
                
                val bw = (x1 - x0 + 1) * step
                val bh = (y1 - y0 + 1) * step
                val fill = size.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
                val aspect = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
                val centerYFrac = ((y0 + y1) / 2f * step) / h
                val centerXFrac = ((x0 + x1) / 2f * step) / w
                
                val isBarShape = fill >= ORANGE_FILL_MIN &&
                    bw >= w * ORANGE_MIN_WIDTH_FRAC &&
                    bh >= ORANGE_MIN_HEIGHT_PX &&
                    bh <= h * ORANGE_MAX_HEIGHT_FRAC &&
                    aspect >= ORANGE_MIN_ASPECT &&
                    centerYFrac >= ORANGE_MIN_CENTER_Y_FRAC
                    
                val isCtaShape = fill >= ORANGE_CTA_FILL_MIN &&
                    bw >= w * ORANGE_CTA_MIN_WIDTH_FRAC &&
                    bw <= w * ORANGE_CTA_MAX_WIDTH_FRAC &&
                    bh >= h * ORANGE_CTA_MIN_HEIGHT_FRAC &&
                    bh <= h * ORANGE_CTA_MAX_HEIGHT_FRAC &&
                    centerXFrac >= ORANGE_CTA_MIN_CENTER_X_FRAC &&
                    centerYFrac >= ORANGE_CTA_MIN_CENTER_Y_FRAC
                
                if (isBarShape) barHit = true
                if (isCtaShape) ctaHit = true
            }
            
            val info = "橙色块 $bestSize 格"
            return (barHit || ctaHit) to info
        } finally {
            sw.recycle()
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            if (isDragging) return
            val node = stack.removeLast()
            val visible = try { node.isVisibleToUser } catch (_: Throwable) { false }
            if (!visible) {
                // 不可见子树整棵跳过，同时回收这一个句柄避免长时间运行后句柄泄漏
                recycleQuietly(node)
                continue
            }
            if (!node.text.isNullOrEmpty() ||
                !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty() ||
                node.className?.toString()?.contains(DramaRules.AD_RENDER_VIEW_CLASS) == true
            ) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                stack.add(node.getChild(i) ?: continue)
            }
        }
    }

    private fun collectFromAllWindows(out: MutableList<AccessibilityNodeInfo>, fgPkg: String) {
        val windows = try { service.windows } catch (_: Throwable) { emptyList() }
        for (win in windows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                win.type != AccessibilityWindowInfo.TYPE_SYSTEM
            ) continue
            val root = try { win.root } catch (_: Throwable) { null } ?: continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg in NON_TARGET_PACKAGES) continue
                if (!win.isActive && pkg != fgPkg) continue
                collectNodes(root, out)
            } finally {
                // 根节点本身通常没有文案，不会进入 out，需要单独回收
                if (out.none { it === root }) recycleQuietly(root)
            }
        }
        if (out.isEmpty()) {
            val root = try { service.rootInActiveWindow } catch (_: Throwable) { null }
            if (root != null) {
                try {
                    collectNodes(root, out)
                } finally {
                    if (out.none { it === root }) recycleQuietly(root)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try { node.recycle() } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (n in nodes) recycleQuietly(n)
    }
}
