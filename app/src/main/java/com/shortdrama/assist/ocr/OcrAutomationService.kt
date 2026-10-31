package com.shortdrama.assist.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shortdrama.assist.AppSettings
import com.shortdrama.assist.DevLog
import com.shortdrama.assist.DevLogOverlay
import com.shortdrama.assist.MainActivity
import com.shortdrama.assist.R
import java.util.concurrent.Executors

/**
 * Shizuku OCR 常驻服务。
 *
 * 链路：
 * ```
 *   前台包名（dumpsys window）
 *     → Shizuku screencap
 *     → ML Kit 中文 OCR
 *     → OcrDecider（片尾/广告/验证/未知）
 *     → Shizuku input swipe / tap
 *     → 下一帧复核
 * ```
 *
 * 原则：**未知页面不动**。宁可漏一集，也不在首页/广告/验证页乱滑。
 */
class OcrAutomationService : Service() {

    companion object {
        private const val TAG = "SDOcr"
        private const val CHANNEL_ID = "sdv2_ocr"
        private const val NOTIFICATION_ID = 4202
        private const val TARGET_PACKAGE = "com.phoenix.read"
        private const val POLL_MS = 120L
        private const val MAX_OCR_WIDTH_PORTRAIT = 600
        private const val MAX_OCR_WIDTH_LANDSCAPE = 900
        private const val ACTION_SETTLE_MS = 350L
        private const val PORTRAIT_CROP_TOP = 0.72f
        private const val LANDSCAPE_CROP_LEFT = 0.45f
        private const val LANDSCAPE_CROP_BOTTOM = 0.32f

        const val ACTION_START = "com.shortdrama.assist.ocr.START"
        const val ACTION_STOP = "com.shortdrama.assist.ocr.STOP"

        fun start(context: Context) {
            val intent = Intent(context, OcrAutomationService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OcrAutomationService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private var worker: Thread? = null

    @Volatile
    private var active = false

    private val decider = OcrDecider(cooldownMs = 3_000L)

    private val packageRegex = Regex("""(?:u0\s+)([A-Za-z0-9_.]+)/""")
    private val fallbackPackageRegex = Regex("""([A-Za-z0-9_.]+)/[A-Za-z0-9_.]+""")

    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything("已停止")
            return START_NOT_STICKY
        }

        if (!AppSettings.isOcrMode(this)) {
            stopEverything("OCR 模式未开启")
            return START_NOT_STICKY
        }

        startInForeground(buildNotification("正在启动…"))
        if (!active) {
            active = true
            OcrState.running = true
            DevLog.event("app", "OCR 服务启动")
            worker = Thread({ loop() }, "sdv2-ocr").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横屏时悬浮日志移到屏幕底部，避开右上角 OCR 裁剪区。
        DevLogOverlay.sync(this)
    }

    override fun onDestroy() {
        DevLog.event("app", "OCR 服务停止")
        active = false
        OcrState.running = false
        worker?.interrupt()
        worker = null
        shizukuRecoveryExecutor.shutdownNow()
        stopForegroundCompat()
        super.onDestroy()
    }

    @Volatile
    private var stage = "init"

    @Volatile
    private var lastProgressAt = 0L

    @Volatile
    private var cachedForeground = ""

    private var lastForegroundCheckAt = 0L
    private var lastShizukuRetryAt = 0L
    private var lastShizukuWarnAt = 0L
    private val shizukuRecoveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sdv2-shizuku-recovery").apply { isDaemon = true }
    }

    @Volatile
    private var lastDecisionNote: String? = null

    @Volatile
    private var watchdogLogged = false

    @Volatile
    private var lastNotification: String? = null

    /**
     * 通知更新走独立线程。ColorOS 上 NotificationManager.notify 偶发卡住几十秒，
     * 绝不能让它阻塞 OCR 主循环。
     */
    private val notifyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sdv2-notify").apply { isDaemon = true }
    }

    private fun startWatchdog() {
        Thread({
            while (active) {
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!active) return@Thread
                val silent = SystemClock.elapsedRealtime() - lastProgressAt
                // 用 logcat 证明守护线程本身还活着；App 内只在真正卡住时记一条。
                Log.d(TAG, "WATCHDOG alive silent=${silent}ms stage=$stage")
                if (silent < 12_000L) {
                    watchdogLogged = false
                    continue
                }
                Log.e(TAG, "WATCHDOG silent=${silent}ms stage=$stage")
                if (watchdogLogged) continue
                watchdogLogged = true
                DevLog.event("error", "OCR 卡住 ${silent / 1000}s，阶段=$stage")
                Thread.getAllStackTraces()
                    .filterKeys { it.name.startsWith("sdv2") || it.name == "main" }
                    .forEach { (thread, stack) ->
                        Log.e(TAG, "STACK ${thread.name}\n" + stack.joinToString("\n") { "  at $it" })
                    }
            }
        }, "sdv2-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var engine: OcrEngine? = null
        var lastActionAt = 0L
        var lastLogAt = 0L
        lastDecisionNote = null
        try {
            // 守护线程必须早于 OcrEngine 初始化：ML Kit 首次加载也可能卡住。
            lastProgressAt = SystemClock.elapsedRealtime()
            startWatchdog()
            var ocr = OcrEngine()
            engine = ocr
            lastProgressAt = SystemClock.elapsedRealtime()
            while (active) {
                val cycleStart = SystemClock.elapsedRealtime()
                stage = "tick"
                try {
                    tick(ocr, lastActionAt)?.let { acted ->
                        if (acted) lastActionAt = SystemClock.elapsedRealtime()
                    }
                } catch (t: Throwable) {
                    if (t is java.util.concurrent.TimeoutException) {
                        // ML Kit 的识别任务偶尔会卡住；超时后旧 recognizer 里可能
                        // 还排着未完成的任务，直接重建，避免后面每一帧继续排队。
                        runCatching { ocr.close() }
                        ocr = OcrEngine()
                        engine = ocr
                        OcrState.status = "OCR 超时，已重建识别器"
                        DevLog.event("error", "OCR 识别超时，已重建识别器")
                    } else {
                        OcrState.status = "OCR 异常：${t.javaClass.simpleName}"
                        DevLog.e(TAG, "[error] OCR 循环异常：${t.javaClass.simpleName}", t)
                    }
                }

                OcrState.lastLatencyMs = SystemClock.elapsedRealtime() - cycleStart
                if (SystemClock.elapsedRealtime() - lastLogAt > 5_000L) {
                    lastLogAt = SystemClock.elapsedRealtime()
                    DevLog.event(
                        "ocr",
                        "心跳：前台=${OcrState.foregroundPackage}，状态=${OcrState.status}，" +
                            "耗时=${OcrState.lastLatencyMs}ms",
                    )
                }
                if (!active) break
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            engine?.close()
            OcrState.running = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    /**
     * 返回 true 表示本轮执行了动作，用于刷新冷却时间。
     */
    private fun tick(engine: OcrEngine, lastActionAt: Long): Boolean? {
        val tickStartedAt = SystemClock.elapsedRealtime()
        if (!ShizukuBridge.hasPermission()) {
            // Shizuku 进程重启/被系统回收时 binder 会短暂失效，但权限还在。
            // 这里只重试绑定，绝不把整个 OCR 服务停掉。
            stage = "shizuku"
            val now = SystemClock.elapsedRealtime()
            if (now - lastShizukuRetryAt > 5_000L) {
                lastShizukuRetryAt = now
                // bindUserService 在某些 ROM 上可能阻塞十几秒，不能占用 OCR 主循环。
                shizukuRecoveryExecutor.execute {
                    val ok = ShizukuBridge.ensureBound(3_000L)
                    DevLog.event(
                        if (ok) "shizuku" else "error",
                        if (ok) "Shizuku 已恢复连接" else "Shizuku 仍不可用，稍后重试"
                    )
                }
            }
            OcrState.status = "Shizuku 未连接，等待恢复"
            if (now - lastShizukuWarnAt > 10_000L) {
                lastShizukuWarnAt = now
                DevLog.event("error", "Shizuku 未连接，等待恢复；不会停止 OCR 服务")
            }
            updateNotification(OcrState.status)
            return null
        }

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) {
            OcrState.status = "屏幕关闭，暂停"
            return null
        }

        stage = "foreground"
        lastProgressAt = SystemClock.elapsedRealtime()
        // dumpsys window 单次约 100–200ms；不必每帧都查。真正执行动作前会再查一次。
        val nowFg = SystemClock.elapsedRealtime()
        if (cachedForeground.isEmpty() || nowFg - lastForegroundCheckAt > 1_000L) {
            cachedForeground = foregroundPackage()
            lastForegroundCheckAt = nowFg
        }
        val pkg = cachedForeground
        OcrState.foregroundPackage = pkg
        if (pkg != TARGET_PACKAGE) {
            OcrState.status = "等待红果前台（当前 ${pkg.ifEmpty { "未知" }}）"
            return null
        }

        stage = "screenshot"
        lastProgressAt = SystemClock.elapsedRealtime()
        val screenshot = ShizukuBridge.screenshot()
        if (screenshot == null) {
            OcrState.status = "截图失败：${ShizukuBridge.lastError ?: "未知"}"
            DevLog.event("error", "截图失败：${ShizukuBridge.lastError ?: "未知"}")
            updateNotification(OcrState.status)
            return null
        }

        val screenWidth = screenshot.width
        val screenHeight = screenshot.height
        val isLandscape = screenWidth > screenHeight
        val cropped = cropForOcr(screenshot, isLandscape)
        val scaled = scaleForOcr(cropped, isLandscape)
        if (cropped !== screenshot) screenshot.recycle()

        stage = "ocr"
        lastProgressAt = SystemClock.elapsedRealtime()
        val snapshot = try {
            engine.recognize(scaled)
        } finally {
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
        }

        stage = "decide"
        lastProgressAt = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - tickStartedAt > 12_000L) {
            OcrState.status = "画面过期，丢弃"
            DevLog.event("error", "单帧耗时过长，丢弃本次识别结果")
            return null
        }
        OcrState.lastText = snapshot.fullText
        val now = SystemClock.elapsedRealtime()
        val decision = decider.decide(snapshot, now, lastActionAt)
        OcrState.status = "OCR ${snapshot.lines.size} 行 · ${decisionStatus(decision.note)}"
        if (decision.note != lastDecisionNote) {
            lastDecisionNote = decision.note
            DevLog.event(decisionCategory(decision.note), decisionDescription(decision))
        }

        // 动作前再确认前台，防止 OCR 期间用户切走，手势落到别的 App。
        if (decision.action != OcrAction.None) {
            val pkgNow = foregroundPackage()
            cachedForeground = pkgNow
            lastForegroundCheckAt = SystemClock.elapsedRealtime()
            if (pkgNow != TARGET_PACKAGE) {
                OcrState.status = "前台已变化，取消动作（${pkgNow.ifEmpty { "未知" }}）"
                DevLog.event("action", "前台已变化，取消本次动作")
                return null
            }
        }

        when (val action = decision.action) {
            OcrAction.None -> {
                stage = "notify"
                updateNotification("红果前台 · ${decisionStatus(decision.note)}")
                return false
            }

            is OcrAction.Swipe -> {
                stage = "action"
                OcrState.lastAction = "SWIPE"
                val ok = swipeUp(screenWidth, screenHeight)
                OcrState.status = if (ok) "已上滑（${action.reason}）" else "上滑失败"
                updateNotification(OcrState.status)
                DevLog.event(
                    "action",
                    if (ok) "上滑成功：原因=${action.reason}" else "上滑失败：原因=${action.reason}",
                )
                Thread.sleep(ACTION_SETTLE_MS)
                return true
            }
        }
    }

    private fun decisionStatus(note: String): String = when (note) {
        "end-card" -> "片尾待续播"
        "empty-ocr" -> "未识别到文字"
        "cooldown" -> "冷却中"
        else -> "未知页面"
    }

    private fun decisionCategory(note: String): String = when (note) {
        "end-card" -> "end"
        "empty-ocr" -> "empty"
        "cooldown" -> "cooldown"
        else -> "unknown"
    }

    private fun decisionDescription(decision: OcrDecision): String = when (decision.note) {
        // 不要把触发词写进日志：悬浮日志会被整屏截图再次 OCR，导致自触发。
        "end-card" -> "识别到片尾提示，准备上滑"
        "empty-ocr" -> "未识别到文字，保持不动"
        "cooldown" -> "动作冷却中，本轮不动作"
        else -> "未识别到已知页面，保持不动"
    }

    /**
     * 只把关键词附近送进 OCR：
     * - 竖屏：底部 32% 全宽（片尾提示在底部）
     * - 横屏：右上角 58% 宽 × 34% 高（片尾提示在右上角）
     */
    private fun cropForOcr(source: Bitmap, isLandscape: Boolean): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 4 || h < 4) return source
        val rect = if (isLandscape) {
            Rect(
                (w * LANDSCAPE_CROP_LEFT).toInt().coerceIn(0, w - 1),
                0,
                w,
                (h * LANDSCAPE_CROP_BOTTOM).toInt().coerceIn(1, h),
            )
        } else {
            Rect(
                0,
                (h * PORTRAIT_CROP_TOP).toInt().coerceIn(0, h - 1),
                w,
                h,
            )
        }
        if (rect.width() < 4 || rect.height() < 4) return source
        return runCatching {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        }.getOrDefault(source)
    }

    private fun scaleForOcr(source: Bitmap, isLandscape: Boolean): Bitmap {
        // 注意：裁出底部条后宽高比会变，不能再用 source.width > source.height 判断方向。
        val maxWidth = if (isLandscape) {
            MAX_OCR_WIDTH_LANDSCAPE
        } else {
            MAX_OCR_WIDTH_PORTRAIT
        }
        if (source.width <= maxWidth) return source
        val ratio = maxWidth.toFloat() / source.width
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    private fun tapScaled(
        x: Int,
        y: Int,
        scaledWidth: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val ratio = screenWidth.toFloat() / scaledWidth.coerceAtLeast(1)
        val sx = (x * ratio).toInt().coerceIn(0, screenWidth - 1)
        val sy = (y * ratio).toInt().coerceIn(0, screenHeight - 1)
        return ShizukuBridge.exec("input tap $sx $sy").ok
    }

    private fun swipeUp(screenWidth: Int, screenHeight: Int): Boolean {
        // 用截图的实际宽高，不能用 resources.displayMetrics：服务在横屏/全屏切换后
        // 可能仍持有旧的竖屏尺寸，会滑到屏幕外。
        // 横屏全屏时视频两侧有黑边，起手点必须在画面中央，落在 20% 处会被黑边吞掉。
        val x = if (screenWidth > screenHeight) {
            (screenWidth * 0.50f).toInt()
        } else {
            (screenWidth * 0.20f).toInt()
        }
        val y1 = (screenHeight * 0.82f).toInt()
        val y2 = (screenHeight * 0.25f).toInt()
        return ShizukuBridge.exec("input swipe $x $y1 $x $y2 420").ok
    }

    private fun foregroundPackage(): String {
        val result = ShizukuBridge.exec(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
        )
        if (!result.ok) return ""
        val match = packageRegex.find(result.output) ?: fallbackPackageRegex.find(result.output)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun stopEverything(status: String) {
        active = false
        OcrState.running = false
        OcrState.status = status
        Log.i(TAG, "STOP $status")
        stopForegroundCompat()
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OCR 自动续播",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shizuku 截图 + OCR 的自动续播服务"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("续播 · OCR 模式")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotification) return
        lastNotification = text
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(text)
        runCatching {
            notifyExecutor.execute {
                runCatching { manager.notify(NOTIFICATION_ID, notification) }
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
