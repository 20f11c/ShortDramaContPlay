package com.shortdrama.assist.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 目标 App 的桌面图标。
 *
 * 为什么要缓存：首页每秒 tick 一次重组，`PackageManager` 每次都重新解析资源
 * （自适应图标还要建 Drawable 再画到 Bitmap），一秒一次足以让滚动掉帧。
 * 图标在安装/升级之间是稳定的，用包名当 key 缓存在内存里就够，不用落盘。
 */
object AppIcons {

    private val cache = LruCache<String, ImageBitmap>(24)

    fun cached(pkg: String): ImageBitmap? = if (pkg.isEmpty()) null else cache.get(pkg)

    /**
     * 取图标并缩放到 [sizePx]。
     *
     * 用 `toBitmap` 而不是读 `intrinsicWidth`：自适应图标（AdaptiveIconDrawable）没有静态像素，
     * 必须走 Canvas 绘制才能拿到实际图像。
     */
    fun load(ctx: Context, pkg: String, sizePx: Int): ImageBitmap? {
        if (pkg.isEmpty()) return null
        cached(pkg)?.let { return it }
        return runCatching {
            val drawable = ctx.packageManager.getApplicationIcon(pkg)
            val target = sizePx.coerceAtLeast(1)
            drawable.toBitmap(width = target, height = target)
                .asImageBitmap()
                .also { cache.put(pkg, it) }
        }.getOrNull()
    }

    /** 包名是否还能解析出图标 —— 也用来判断这个 App 是不是已被卸载。 */
    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)
}

/**
 * 异步拿图标：有缓存就在首帧直接返回（不闪），没有再去 IO 线程解析。
 */
@Composable
fun rememberAppIcon(pkg: String, sizePx: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val state by produceState<ImageBitmap?>(
        initialValue = AppIcons.cached(pkg),
        key1 = pkg,
        key2 = sizePx,
    ) {
        if (pkg.isEmpty()) {
            value = null
            return@produceState
        }
        value = AppIcons.cached(pkg) ?: withContext(Dispatchers.IO) {
            AppIcons.load(ctx.applicationContext, pkg, sizePx)
        }
    }
    return state
}
