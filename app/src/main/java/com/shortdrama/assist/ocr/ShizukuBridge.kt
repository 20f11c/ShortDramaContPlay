package com.shortdrama.assist.ocr

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.shortdrama.assist.BuildConfig
import com.shortdrama.assist.DevLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import rikka.shizuku.Shizuku

/**
 * App 与 Shizuku UserService 之间的桥。
 *
 * 所有方法都可能在 **后台线程** 被调用；不要在主线程序列化等待。
 * 绑定一次后常驻，掉线时下一次调用会重新绑定。
 */
object ShizukuBridge {

    const val REQUEST_CODE = 4201
    private const val TAG = "SDOcr"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SCREENSHOT_TIMEOUT_MS = 12_000L

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var appPackage: String = ""

    @Volatile
    private var shotDir: String = ""

    @Volatile
    private var shotExecutor = newShotExecutor()

    @Volatile
    private var execExecutor = newExecExecutor()

    @Volatile
    private var args: Shizuku.UserServiceArgs? = null

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var binding = false

    private val lock = Object()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                binder = service
                binding = false
                lock.notifyAll()
            }
            DevLog.event("shizuku", "服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                binder = null
                binding = false
                lock.notifyAll()
            }
            DevLog.w(TAG, "[shizuku] 服务连接断开")
        }
    }

    fun init(context: Context) {
        DevLog.init(context)
        appPackage = context.applicationContext.packageName
        val dir = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.cacheDir
        shotDir = dir.absolutePath
        args = Shizuku.UserServiceArgs(
            ComponentName(appPackage, ShizukuShellService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("ocr_shell")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        binderAlive() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(activity: Activity) {
        try {
            if (!binderAlive()) {
                lastError = "Shizuku 未运行"
                return
            }
            if (Shizuku.isPreV11()) {
                lastError = "Shizuku 版本过低（需 v11+）"
                return
            }
            if (hasPermission()) return
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            lastError = "请求授权失败：${t.message}"
            Log.e(TAG, "requestPermission 失败", t)
        }
    }

    /** 阻塞等待绑定，超时返回 false。调用方必须在后台线程。 */
    fun ensureBound(timeoutMs: Long = 6_000L): Boolean {
        // 不能先用 hasPermission() 拦截：Shizuku 进程重启后 binder 已死，
        // 而权限仍然有效；此时必须允许重新 bind，否则 OCR 会永久停在“未授权”。
        val current = binder
        if (current != null && current.pingBinder()) return true

        synchronized(lock) {
            val again = binder
            if (again != null && again.pingBinder()) return true
            if (!binding) {
                val localArgs = args
                if (localArgs == null) {
                    lastError = "ShizukuBridge 未初始化"
                    return false
                }
                binding = true
                try {
                    Shizuku.bindUserService(localArgs, connection)
                } catch (t: Throwable) {
                    binding = false
                    lastError = "绑定 Shizuku 服务失败：${t.message}"
                    Log.e(TAG, "bindUserService 失败", t)
                    return false
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val b = binder
                if (b != null && b.pingBinder()) return true
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) {
                    lastError = "绑定 Shizuku 服务超时"
                    return false
                }
                try {
                    lock.wait(remain.coerceAtMost(250L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
    }

    /**
     * 截图必须带 App 侧硬超时。
     *
     * Shizuku 的 Binder 调用本身没有超时可设；一旦 UserService 里的 screencap
     * 卡在 SurfaceFlinger，主循环会几十秒没有任何进展（实测出现过 101 秒）。
     * 这里把阻塞截图丢到独立线程，12 秒不返回就丢弃这次连接并重建线程池。
     */
    fun screenshot(): Bitmap? {
        if (shotDir.isEmpty()) {
            lastError = "截图路径未初始化"
            return null
        }
        val executor = shotExecutor
        return try {
            executor.submit<Bitmap?> { screenshotBlocking() }
                .get(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            lastError = "截图超时（${SCREENSHOT_TIMEOUT_MS / 1000}s），已重置 Shizuku 连接"
            DevLog.event("error", lastError!!)
            resetShotExecutor()
            reset()
            null
        } catch (t: Throwable) {
            lastError = "截图异常：${t.javaClass.simpleName}"
            Log.e(TAG, "screenshot 失败", t)
            null
        }
    }

    private fun screenshotBlocking(): Bitmap? {
        val rawPath = "$shotDir/ocr-shot-${System.nanoTime()}.raw"
        val rawResult = exec("screencap \"$rawPath\"", timeoutMs = 8_000L)
        if (rawResult.ok) {
            val raw = decodeRaw(rawPath)
            if (raw != null) return raw
        }
        // 极少数设备 raw 头/步长不兼容时退回 PNG，保证功能不中断。
        val pngPath = "$shotDir/ocr-shot-${System.nanoTime()}.png"
        val pngResult = exec("screencap -p \"$pngPath\"", timeoutMs = 8_000L)
        if (!pngResult.ok) {
            lastError = "截图命令失败：${pngResult.output}"
            return null
        }
        return try {
            BitmapFactory.decodeFile(pngPath) ?: run {
                lastError = "截图解码失败"
                null
            }
        } finally {
            runCatching { java.io.File(pngPath).delete() }
        }
    }

    /**
     * `screencap` 默认输出 raw：16 字节头（宽、高、format、colorspace）+ RGBA_8888。
     * 比 PNG 少一次压缩/解压，实测单帧快约 300ms。
     */
    private fun decodeRaw(path: String): Bitmap? {
        val file = java.io.File(path)
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 16) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            val format = buffer.int
            buffer.int // colorspace
            if (width <= 0 || height <= 0) return null
            if (format != 1 && format != 2) return null
            val pixelBytes = width.toLong() * height * 4L
            val header = if (bytes.size >= 16L + pixelBytes) 16 else if (bytes.size >= 12L + pixelBytes) 12 else return null
            buffer.position(header)
            if (buffer.remaining() < pixelBytes) return null
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(buffer)
            }
        } catch (t: Throwable) {
            lastError = "RAW 截图解码失败：${t.javaClass.simpleName}"
            Log.e(TAG, "decodeRaw 失败", t)
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun resetShotExecutor() {
        val old = shotExecutor
        shotExecutor = newShotExecutor()
        old.shutdownNow()
    }

    private fun resetExecExecutor() {
        val old = execExecutor
        execExecutor = newExecExecutor()
        old.shutdownNow()
    }

    private fun newShotExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sdv2-shot").apply { isDaemon = true }
    }

    private fun newExecExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sdv2-exec").apply { isDaemon = true }
    }

    /**
     * 所有 shell 命令都必须有 App 侧硬超时。
     *
     * Binder 的 transact 没有超时参数；如果 UserService 进程被冻结，调用线程会
     * 永久卡在 transact 上。这里把命令丢到独立线程，超时后整体替换执行器，
     * 保证 OCR 主循环永远不会被一条 dumpsys/input 命令拖死。
     */
    fun exec(command: String, timeoutMs: Long = 3_000L): ShellResult {
        val executor = execExecutor
        return try {
            executor.submit<ShellResult> { execBlocking(command, timeoutMs) }
                .get(timeoutMs + 1_500L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            lastError = "命令超时（${timeoutMs / 1000}s）：${command.take(80)}"
            DevLog.event("error", "Shizuku 命令超时，已重置连接")
            resetExecExecutor()
            reset()
            ShellResult(-1, lastError.orEmpty())
        } catch (t: Throwable) {
            lastError = "命令异常：${t.javaClass.simpleName}"
            Log.e(TAG, "exec 失败", t)
            reset()
            ShellResult(-1, lastError.orEmpty())
        }
    }

    private fun execBlocking(command: String, timeoutMs: Long): ShellResult {
        if (!ensureBound(3_000L)) return ShellResult(-1, lastError.orEmpty())
        val b = binder ?: return ShellResult(-1, "Shizuku 服务未连接")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(command)
            data.writeLong(timeoutMs)
            if (!b.transact(ShizukuShellService.TRANSACTION_EXEC, data, reply, 0)) {
                return ShellResult(-1, "命令事务发送失败")
            }
            reply.readException()
            val code = reply.readInt()
            val output = reply.readString().orEmpty()
            ShellResult(code, output)
        } catch (t: Throwable) {
            lastError = "命令异常：${t.message}"
            Log.e(TAG, "exec 失败", t)
            reset()
            ShellResult(-1, lastError.orEmpty())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun statusText(context: Context): String = when {
        !isInstalled(context) -> "未安装 Shizuku"
        !binderAlive() -> "Shizuku 未运行"
        runCatching { Shizuku.isPreV11() }.getOrDefault(false) -> "Shizuku 版本过低"
        !hasPermission() -> "Shizuku 未授权"
        else -> "Shizuku 已授权"
    }

    private fun reset() {
        synchronized(lock) {
            binder = null
            binding = false
            lock.notifyAll()
        }
    }

    data class ShellResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }
}
