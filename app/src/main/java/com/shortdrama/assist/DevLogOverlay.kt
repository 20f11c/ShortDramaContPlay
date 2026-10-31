package com.shortdrama.assist

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 开发者日志悬浮层。
 *
 * - 只在开发者日志开启且已授予“显示在其他应用上层”时显示；
 * - 顶部半透明条，点击展开/收起最近日志；
 * - 不走无障碍、不申请录屏，只是一个普通 overlay。
 */
object DevLogOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var header: TextView? = null
    private var body: TextView? = null
    private var bodyScroll: ScrollView? = null
    private var expanded = false
    private var lastCount = -1

    fun sync(context: Context) {
        val app = context.applicationContext
        if (!DevLog.isEnabled(app) || !Settings.canDrawOverlays(app)) {
            hide()
            return
        }
        main.post {
            if (root == null) {
                attach(app)
            } else {
                updateGravity(app)
                refresh()
            }
        }
    }

    fun hide() {
        main.post {
            val wm = windowManager
            val view = root
            if (wm != null && view != null) {
                runCatching { wm.removeView(view) }
            }
            windowManager = null
            params = null
            root = null
            header = null
            body = null
            bodyScroll = null
            expanded = false
            lastCount = -1
        }
    }

    private fun attach(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val panel = buildPanel(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityFor(context)
            x = 0
            y = 0
        }
        runCatching { wm.addView(panel, params) }
            .onSuccess {
                windowManager = wm
                this.params = params
                root = panel
                scheduleRefresh()
            }
            .onFailure { hide() }
    }

    private fun gravityFor(context: Context): Int {
        val landscape = context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) {
            // 横屏时播放器控件在右上角，日志放底部避免挡住它。
            Gravity.BOTTOM or Gravity.START
        } else {
            // 竖屏时片尾提示在底部，日志放顶部。
            Gravity.TOP or Gravity.START
        }
    }

    private fun updateGravity(context: Context) {
        val wm = windowManager ?: return
        val view = root ?: return
        val p = params ?: return
        val gravity = gravityFor(context)
        if (p.gravity == gravity) return
        p.gravity = gravity
        runCatching { wm.updateViewLayout(view, p) }
    }

    private fun buildPanel(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
        }

        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "续播日志"
            setOnClickListener {
                expanded = !expanded
                bodyScroll?.visibility = if (expanded) View.VISIBLE else View.GONE
                refresh(force = true)
            }
        }
        panel.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        header = title

        val scroll = ScrollView(context).apply {
            visibility = View.GONE
            isFillViewport = false
        }
        val content = TextView(context).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(2), dp(12), dp(10))
            setLineSpacing(0f, 1.05f)
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220),
            ),
        )
        bodyScroll = scroll
        body = content
        return panel
    }

    private fun scheduleRefresh() {
        if (root == null) return
        refresh()
        main.postDelayed({ scheduleRefresh() }, 1_000L)
    }

    private fun refresh(force: Boolean = false) {
        val title = header ?: return
        val entries = DevLog.snapshot()
        if (force || entries.size != lastCount) {
            lastCount = entries.size
            title.text = buildString {
                append("续播日志 ")
                append(entries.size)
                append("/")
                append(DevLog.MAX_ENTRIES)
                append(if (expanded) " · 点击收起" else " · 点击展开")
            }
            body?.text = entries.takeLast(12)
                .joinToString("\n") { "${it.level}/${it.tag}  ${safeForOverlay(it.message)}" }
                .ifBlank { "暂无日志" }
            bodyScroll?.post { bodyScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 悬浮日志本身会画在播放器上方，把片尾提示的原文替换成描述，
     * 避免日志窗口里的文字反过来干扰页面文本判据。
     */
    private fun safeForOverlay(message: String): String = message
        .replace("上滑继续观看短剧", "片尾提示")
        .replace("上滑继续观看剧集", "片尾提示")
}
