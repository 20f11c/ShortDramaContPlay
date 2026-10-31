// 前台服务 + 悬浮球的保活思路参考 ad-auto（https://github.com/clarithromycine/ad-auto）：
// MIUI/HyperOS 在服务被停后约 20 秒就不再派发无障碍事件，必须常驻一条低优先级通知；
// 悬浮球则是有可见窗口时的抓手与手动开关。本项目为独立重写实现，
// 移植清单与许可说明见仓库根目录 NOTICE.md。
package com.shortdrama.assist.accessibility

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.shortdrama.assist.AppSettings
import com.shortdrama.assist.MainActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 前台服务 + 悬浮球。
 *
 * 两件事分开看：
 * 1. **保活**靠的是前台服务那条常驻通知（低优先级、ongoing），以及无障碍服务本身由 system_server 绑定；
 *    悬浮球本身不"保证"不被杀，它提供的是一个可见窗口和手动入口。
 * 2. **悬浮球**在没有无障碍事件时（比如卡在某个页面）也能量看状态、点一下回到 App。
 */
class FloatingBallService : Service() {

    companion object {
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "sd_accessibility_keepalive"
        private const val TAG = "SDFloatingBall"
        private const val PREFS = "sd_overlay"

        @Volatile
        var instance: FloatingBallService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }

        /** 设置里切换「悬浮球」后调用：已授权的立刻显示/隐藏，不用重启服务。 */
        fun refresh(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.syncBall()
            } else if (AppSettings.isFloatingBallEnabled(context) &&
                Settings.canDrawOverlays(context)
            ) {
                start(context)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var ball: BallView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
            // 通知权限被拒绝等场景，忽略并继续
        }
        syncBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncBall()
        return START_STICKY
    }

    override fun onDestroy() {
        removeBall()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 球该不该显示，只看「设置里开着」+「悬浮窗权限给了」。 */
    private fun syncBall() {
        val want = AppSettings.isFloatingBallEnabled(this) && Settings.canDrawOverlays(this)
        val has = ball != null
        if (want && !has) {
            addBall()
        } else if (!want && has) {
            removeBall()
        } else if (want && has) {
            ball?.invalidate()
        }
    }

    private fun addBall() {
        if (ball != null) return
        val size = dp(44)
        val p = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val w = screenWidth()
            val h = screenHeight()
            val defX = (w - size - dp(8)).coerceAtLeast(0)
            val defY = (h * 0.55f).toInt()
            x = prefs.getInt("x", defX).coerceIn(0, (w - size).coerceAtLeast(0))
            y = prefs.getInt("y", defY).coerceIn(0, (h - size).coerceAtLeast(0))
        }
        val view = BallView(this)
        view.alpha = if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("dim", false)) 0.35f else 1f
        runCatching {
            windowManager.addView(view, p)
            ball = view
            params = p
        }.onFailure { Log.w(TAG, "悬浮球添加失败: ${it.message}") }
    }

    private fun removeBall() {
        val view = ball ?: return
        runCatching { windowManager.removeView(view) }
        ball = null
        params = null
    }

    private fun screenWidth(): Int = displaySize().x

    private fun screenHeight(): Int = displaySize().y

    /**
     * 服务里读真实屏幕尺寸。
     *
     * `resources.displayMetrics` 在服务没有窗口时给的是"兼容尺寸"，某些机型会缩水，
     * 导致球贴在错误的位置；API 30+ 用 `WindowManager.currentWindowMetrics` 才准。
     */
    private fun displaySize(): android.graphics.Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return android.graphics.Point(b.width(), b.height())
        }
        val dm = resources.displayMetrics
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun persist(x: Int, y: Int, dim: Boolean = false) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("x", x)
            .putInt("y", y)
            .apply {
                if (dim) putBoolean("dim", true)
            }
            .apply()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    /**
     * 悬浮球本体。
     *
     * 拖动时直接 `updateViewLayout`；松手吸附到最近的一条竖直边，位置写进 prefs，
     * 下次启动还在原地。单击回 App，长按把球变半透明（再长按恢复），
     * 免得盖住剧集字幕还挡手。
     */
    private inner class BallView(context: Context) : View(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPressRunnable = Runnable {
            longPressed = true
            val dim = alpha > 0.5f
            animate().alpha(if (dim) 0.35f else 1f).setDuration(150).start()
            persist(paramsX(), paramsY(), dim = dim)
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE01C1C1E.toInt() }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(2f)
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val glyphPath = Path()

        private fun paramsX(): Int = params?.x ?: 0
        private fun paramsY(): Int = params?.y ?: 0

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            val running = AutoPlayAccessibilityService.isConnected()

            // 底盘
            canvas.drawCircle(cx, cy, r - dpF(1.5f), bgPaint)

            // 外圈：运行中蓝色，未连接灰
            ringPaint.color = if (running) 0xFF0A84FF.toInt() else 0xFF8E8E93.toInt()
            canvas.drawCircle(cx, cy, r - dpF(2.5f), ringPaint)

            // 中间一个播放三角（回 App 的暗示），颜色跟外圈一致更统一
            glyphPaint.color = if (running) 0xFF0A84FF.toInt() else 0xFF8E8E93.toInt()
            val s = r * 0.42f
            glyphPath.reset()
            glyphPath.moveTo(cx - s * 0.6f, cy - s)
            glyphPath.lineTo(cx + s * 0.95f, cy)
            glyphPath.lineTo(cx - s * 0.6f, cy + s)
            glyphPath.close()
            canvas.drawPath(glyphPath, glyphPaint)

            // 运行中的小绿点，弱化但可辨
            if (running) {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
                canvas.drawCircle(width - dpF(10f), dpF(10f), dpF(3.2f), dot)
            }
        }

        private fun dpF(value: Float) = value * resources.displayMetrics.density

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    longPressed = false
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        p.x = (startX + dx).toInt().coerceIn(0, max(0, screenWidth() - width))
                        p.y = (startY + dy).toInt().coerceIn(0, max(0, screenHeight() - height))
                        runCatching { windowManager.updateViewLayout(this, p) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (dragging) {
                        snapToEdge()
                        persist(p.x, p.y)
                    } else if (!longPressed) {
                        performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            openApp()
            return super.performClick()
        }

        /** 松手后贴到最近那条竖直边，带一小段动画。 */
        private fun snapToEdge() {
            val p = params ?: return
            val maxX = max(0, screenWidth() - width)
            val targetX = if (p.x + width / 2 < screenWidth() / 2) 0 else maxX
            val fromX = p.x
            if (fromX == targetX) return
            ValueAnimator.ofInt(fromX, targetX).apply {
                duration = 160
                addUpdateListener {
                    p.x = it.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(this@BallView, p) }
                }
                start()
            }
        }
    }

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
