package com.shortdrama.assist.ocr

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 的 shell 进程（非 root 时 UID 2000）里的最小特权服务。
 *
 * 只暴露两个能力：
 *  1. 执行一条 shell 命令，返回退出码和文本输出；
 *  2. 把 `screencap -p` 的 PNG 通过管道流回 App。
 *
 * 截图不走 Binder byte[]：一张 1080p PNG 很容易超过 Binder 事务上限，
 * ParcelFileDescriptor 流式传输既没有大小问题，也不需要落盘临时文件。
 *
 * 这个类不引用任何 Shizuku API，由宿主进程通过 bindUserService 加载。
 */
class ShizukuShellService : Binder() {

    companion object {
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val out = reply ?: return super.onTransact(code, data, null, flags)
        when (code) {
            TRANSACTION_EXEC -> {
                val command = data.readString().orEmpty()
                val timeoutMs = data.readLong().coerceIn(1_000L, 15_000L)
                val result = runCommand(command, timeoutMs)
                out.writeNoException()
                out.writeInt(result.code)
                out.writeString(result.output)
                return true
            }

            TRANSACTION_SCREENSHOT -> {
                val pipe = try {
                    ParcelFileDescriptor.createPipe()
                } catch (t: Throwable) {
                    out.writeNoException()
                    out.writeInt(0)
                    out.writeString("${t.javaClass.simpleName}: ${t.message}")
                    return true
                }
                val readSide = pipe[0]
                val writeSide = pipe[1]
                out.writeNoException()
                out.writeInt(1)
                try {
                    readSide.writeToParcel(out, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                } finally {
                    // Binder 已经把 fd 复制给客户端；服务端这份要及时关，否则每截图一次漏一个 fd。
                    readSide.close()
                }

                Thread({ streamScreenshot(writeSide) }, "sdv2-screencap").apply {
                    isDaemon = true
                    start()
                }
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun streamScreenshot(writeSide: ParcelFileDescriptor) {
        var process: Process? = null
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                process = ProcessBuilder("/system/bin/screencap", "-p").start()
                val active = process!!
                val watchdog = Thread {
                    try {
                        Thread.sleep(4000)
                        kill(active)
                    } catch (_: InterruptedException) {
                        // finished normally
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                try {
                    active.inputStream.use { input: InputStream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) out.write(buffer, 0, n)
                        }
                    }
                    active.waitFor(2, TimeUnit.SECONDS)
                } finally {
                    watchdog.interrupt()
                    kill(active)
                }
            }
        } catch (_: Throwable) {
            // 关闭管道就是给客户端的错误信号；OCR 侧会得到 null。
        } finally {
            process?.destroy()
        }
    }

    private fun runCommand(command: String, timeoutMs: Long): CommandResult {
        var process: Process? = null
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.redirectErrorStream(true)
            process = pb.start()
            val active = process!!
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    kill(active)
                } catch (_: InterruptedException) {
                    // finished normally
                }
            }.apply {
                isDaemon = true
                start()
            }
            val output = StringBuilder()
            try {
                BufferedReader(
                    InputStreamReader(active.inputStream, StandardCharsets.UTF_8),
                ).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        output.append(line).append('\n')
                    }
                }
                val finished = active.waitFor(2, TimeUnit.SECONDS)
                CommandResult(if (finished) active.exitValue() else -1, output.toString().trim())
            } finally {
                watchdog.interrupt()
                kill(active)
            }
        } catch (t: Throwable) {
            CommandResult(-1, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            kill(process)
        }
    }

    private fun kill(process: Process?) {
        if (process == null) return
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private data class CommandResult(val code: Int, val output: String)
}
