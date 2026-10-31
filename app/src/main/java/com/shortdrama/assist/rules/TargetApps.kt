package com.shortdrama.assist.rules

import android.content.Context
import android.content.pm.PackageManager

/**
 * 订阅 → 目标 App 的解析层。
 *
 * 为什么要有这一层：订阅 id 只是本地主键（官方源是包名，用户自建的源可能是别的东西），
 * 真正决定「规则管哪个 App」的是规则包里的 `package` 字段。悬浮球要不要显示、
 * 菜单里「打开 xxx」打开谁，都必须走这里，而不是自己猜 id。
 */
object TargetApps {

    /**
     * 一个订阅对应的目标 App。
     * [packageName] 为 null 表示这条订阅还没拉到过规则包、也解析不出包名（此时它不参与匹配）。
     */
    data class Target(
        val subscriptionId: String,
        val subscriptionName: String,
        val packageName: String?,
        val label: String,
        val enabled: Boolean,
    ) {
        val usable: Boolean get() = enabled && !packageName.isNullOrBlank()
    }

    /** 所有订阅，按「先启用的、再按名字」排。 */
    fun all(ctx: Context): List<Target> =
        SubscriptionStore.load(ctx)
            .map { sub ->
                val pkg = packageOf(ctx, sub)
                Target(
                    subscriptionId = sub.id,
                    subscriptionName = sub.name,
                    packageName = pkg,
                    label = pkg?.let { labelOf(ctx, it) } ?: sub.name,
                    enabled = sub.enabled,
                )
            }
            .sortedWith(compareByDescending<Target> { it.enabled }.thenBy { it.label })

    /** 当前真正生效的目标包名集合。 */
    fun enabledPackages(ctx: Context): Set<String> =
        all(ctx).filter { it.usable }.mapNotNull { it.packageName }.toSet()

    fun isTarget(ctx: Context, pkg: String?): Boolean =
        pkg != null && pkg in enabledPackages(ctx)

    /** 菜单里「打开 xxx」的目标：前台就是目标就用前台，否则退到第一个启用的目标。 */
    fun primary(ctx: Context, foreground: String?): Target? {
        val usable = all(ctx).filter { it.usable }
        foreground?.let { fg -> usable.firstOrNull { it.packageName == fg }?.let { return it } }
        return usable.firstOrNull()
    }

    /** 读系统里的应用名；读不到返回 null，让调用方退回订阅名。 */
    fun labelOf(ctx: Context, pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * 从订阅解析目标包名：优先读已缓存的规则包，其次官方源的内置资产，
     * 最后才退回「id 看起来像包名」的情况（用户手填的官方地址 id 就与包名一致）。
     */
    private fun packageOf(ctx: Context, sub: RuleSubscription): String? {
        val fromPackage = RuleStore.cached(ctx, sub.id)?.packageName
            ?: if (sub.id == RuleStore.OFFICIAL_ID) RuleStore.builtIn(ctx)?.packageName else null
        if (!fromPackage.isNullOrBlank()) return fromPackage
        return sub.id.takeIf { it.contains('.') && !it.startsWith("http") }
    }

    /** 目标 App 是否装着；没装时菜单里那行应当置灰或提示。 */
    fun isUsable(ctx: Context, target: Target?): Boolean {
        val pkg = target?.packageName ?: return false
        return target.enabled && isInstalled(ctx, pkg)
    }
}
