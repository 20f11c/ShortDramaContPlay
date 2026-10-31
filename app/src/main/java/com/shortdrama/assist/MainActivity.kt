package com.shortdrama.assist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.shortdrama.assist.accessibility.A11yState
import com.shortdrama.assist.accessibility.AutoPlayAccessibilityService
import com.shortdrama.assist.rules.RuleEngine
import com.shortdrama.assist.rules.RulePackage
import com.shortdrama.assist.rules.RuleStore
import com.shortdrama.assist.rules.RuleSubscription
import com.shortdrama.assist.rules.StatsStore
import com.shortdrama.assist.rules.SubscriptionStore
import com.shortdrama.assist.ui.components.AppIcons
import com.shortdrama.assist.ui.components.AppTab
import com.shortdrama.assist.ui.components.BottomTabs
import com.shortdrama.assist.ui.home.HomeScreen
import com.shortdrama.assist.ui.home.PermissionRow
import com.shortdrama.assist.ui.home.StatItem
import com.shortdrama.assist.ui.settings.A11yScreen
import com.shortdrama.assist.ui.settings.DevLogScreen
import com.shortdrama.assist.ui.settings.SettingsScreen
import com.shortdrama.assist.ui.subscribe.AddSubscribeSheet
import com.shortdrama.assist.ui.subscribe.SubscribeDetailScreen
import com.shortdrama.assist.ui.subscribe.SubscribeScreen
import com.shortdrama.assist.ui.theme.ShortDramaTheme
import com.shortdrama.assist.ui.recharge.RechargeScreen
import com.shortdrama.assist.ui.theme.LocalTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val debugScreen = intent?.getStringExtra(EXTRA_SCREEN)
        setContent { AppRoot(debugScreen) }
    }
}

private const val EXTRA_SCREEN = "screen"
private const val TARGET_PKG = "com.phoenix.read"
private const val TARGET_NAME = "红果免费短剧"

private enum class Screen {
    HOME,
    SUBSCRIBE,
    SUBSCRIBE_DETAIL,
    SETTINGS,
    DEVLOG,
    A11Y,
    RECHARGE,
}

private fun String?.toScreen(): Screen = when (this?.lowercase(Locale.US)) {
    "subscribe" -> Screen.SUBSCRIBE
    "settings" -> Screen.SETTINGS
    "a11y" -> Screen.A11Y
    "devlog" -> Screen.DEVLOG
    "recharge" -> Screen.RECHARGE
    else -> Screen.HOME
}

private fun Screen.toTab(): AppTab = when (this) {
    Screen.SUBSCRIBE, Screen.SUBSCRIBE_DETAIL -> AppTab.SUBSCRIBE
    Screen.SETTINGS, Screen.DEVLOG, Screen.A11Y, Screen.RECHARGE -> AppTab.SETTINGS
    Screen.HOME -> AppTab.HOME
}

private fun AppTab.toScreen(): Screen = when (this) {
    AppTab.HOME -> Screen.HOME
    AppTab.SUBSCRIBE -> Screen.SUBSCRIBE
    AppTab.SETTINGS -> Screen.SETTINGS
}

@Composable
private fun AppRoot(debugScreen: String? = null) {
    val ctx = LocalContext.current
    var darkOverride by remember { mutableStateOf(AppSettings.darkModeOverride(ctx)) }
    val dark = when (darkOverride) {
        1 -> false
        2 -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    ShortDramaTheme(darkOverride = dark) {
        val t = LocalTokens.current
        var screen by remember { mutableStateOf(debugScreen.toScreen()) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                tick++
            }
        }

        val a11yConnected = remember(tick) { A11yState.connected }
        val a11yEnabledInSettings = remember(tick) {
            AutoPlayAccessibilityService.isEnabledInSettings(ctx)
        }
        val autoContinueOn = remember(tick) { AppSettings.isAutoContinueEnabled(ctx) }
        val adSkipOn = remember(tick) { AppSettings.isAdSkipEnabled(ctx) }
        val batteryOk = remember(tick) { isIgnoringBattery(ctx) }
        val notificationsOk = remember(tick) {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }
        val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
        val targetInstalled = remember(tick) { isInstalled(ctx, TARGET_PKG) }

        var subscriptions by remember { mutableStateOf<List<RuleSubscription>>(emptyList()) }
        var packages by remember { mutableStateOf<Map<String, RulePackage?>>(emptyMap()) }
        var openSubId by remember { mutableStateOf<String?>(null) }
        var checkingAll by remember { mutableStateOf(false) }
        var checkingOne by remember { mutableStateOf(false) }
        var showAddSheet by remember { mutableStateOf(false) }
        var editingSubId by remember { mutableStateOf<String?>(null) }
        var addUrl by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }
        var statsTick by remember { mutableIntStateOf(0) }
        var devLogEnabled by remember { mutableStateOf(DevLog.isEnabled(ctx)) }
        val scope = rememberCoroutineScope()

        val enabledCount = subscriptions.count { it.enabled }
        val statsRows = remember(tick, statsTick) { StatsStore.rows(ctx) }
        val totalToday = remember(tick, statsTick) { StatsStore.totalToday(ctx) }
        val lastAt = remember(tick, statsTick) { StatsStore.lastAt(ctx) }

        /** 拉一个订阅并落库：成功清空 lastError，失败只写 lastError，不动已有版本信息。 */
        suspend fun refresh(sub: RuleSubscription): RuleSubscription {
            val result = withContext(Dispatchers.IO) { RuleStore.fetch(ctx, sub) }
            val now = System.currentTimeMillis()
            return result.fold(
                onSuccess = { pkg ->
                    packages = packages + (sub.id to pkg)
                    RuleEngine.invalidate()
                    sub.copy(
                        version = pkg.version,
                        ruleCount = pkg.rules.size,
                        minAppVersion = pkg.minAppVersion,
                        signed = pkg.signed,
                        lastCheckedAt = now,
                        lastError = null,
                    )
                },
                onFailure = { err ->
                    sub.copy(
                        lastCheckedAt = now,
                        lastError = err.message?.take(60) ?: "更新失败",
                    )
                },
            )
        }

        fun persist(list: List<RuleSubscription>) {
            subscriptions = list
            RuleEngine.invalidate()
            scope.launch(Dispatchers.IO) { SubscriptionStore.save(ctx, list) }
        }

        // 启动：种下默认订阅 → 读本地缓存 → 没缓存的静默拉一次
        LaunchedEffect(Unit) {
            val seeded = withContext(Dispatchers.IO) { SubscriptionStore.ensureSeeded(ctx) }
            subscriptions = seeded
            packages = withContext(Dispatchers.IO) {
                seeded.associate { sub ->
                    sub.id to (RuleStore.cached(ctx, sub.id)
                        ?: if (sub.id == RuleStore.OFFICIAL_ID) RuleStore.builtIn(ctx) else null)
                }
            }
            val stale = seeded.filter { it.enabled && it.autoUpdate && packages[it.id] == null }
            if (stale.isNotEmpty()) {
                val updated = stale.map { refresh(it) }
                persist(seeded.map { s -> updated.firstOrNull { it.id == s.id } ?: s })
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Surface(color = t.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                            Screen.HOME -> HomeScreen(
                                running = a11yConnected && (autoContinueOn || adSkipOn),
                                headline = when {
                                    !targetInstalled -> "未安装红果"
                                    a11yConnected && (autoContinueOn || adSkipOn) -> "运行中"
                                    !a11yEnabledInSettings -> "未开启无障碍"
                                    else -> "已暂停"
                                },
                                subtitle = "启用规则 $enabledCount / ${subscriptions.size} 个订阅",
                                autoContinueEnabled = autoContinueOn,
                                adSkipEnabled = adSkipOn,
                                onAutoContinueChange = { on ->
                                    AppSettings.setAutoContinueEnabled(ctx, on)
                                    DevLog.event("app", "自动续播 → $on", tag = "App")
                                },
                                onAdSkipChange = { on ->
                                    AppSettings.setAdSkipEnabled(ctx, on)
                                    DevLog.event("app", "广告跳过 → $on", tag = "App")
                                },
                                permissions = listOf(
                                    PermissionRow(
                                        title = "无障碍服务",
                                        value = when {
                                            a11yConnected -> "运行中"
                                            a11yEnabledInSettings -> "连接中"
                                            else -> "未开启"
                                        },
                                        granted = a11yConnected,
                                        running = true,
                                        onClick = { AutoPlayAccessibilityService.openSettings(ctx) },
                                    ),
                                    PermissionRow(
                                        title = "后台运行",
                                        value = if (batteryOk) "已允许" else "未允许",
                                        granted = batteryOk,
                                        onClick = { requestIgnoreBattery(ctx) },
                                    ),
                                    PermissionRow(
                                        title = "通知",
                                        value = if (notificationsOk) "已开启" else "未开启",
                                        granted = notificationsOk,
                                        onClick = { openNotificationSettings(ctx) },
                                    ),
                                    PermissionRow(
                                        title = "悬浮框",
                                        value = if (overlayOk) "已授权" else "未授权",
                                        granted = overlayOk,
                                        onClick = { openOverlaySettings(ctx) },
                                    ),
                                ),
                                stats = statsRows.take(4).mapIndexed { index, row ->
                                    val label = AppIcons.label(ctx, row.packageName, row.label)
                                    StatItem(
                                        label = label,
                                        packageName = row.packageName,
                                        short = label.take(1),
                                        count = row.count,
                                        color = STAT_COLORS[index % STAT_COLORS.size],
                                    )
                                },
                                statsFootnote = buildString {
                                    append("今日 $totalToday 次")
                                    if (lastAt > 0) {
                                        append(" · 上次续播 ${relativeTime(lastAt)}")
                                    }
                                },
                            )

                            Screen.SUBSCRIBE -> SubscribeScreen(
                                subscriptions = subscriptions,
                                checking = checkingAll,
                                onRefreshAll = {
                                    checkingAll = true
                                    scope.launch {
                                        var list = subscriptions
                                        list = list.map { refresh(it) }
                                        persist(list)
                                        checkingAll = false
                                        statsTick++
                                    }
                                },
                                onAdd = {
                                    editingSubId = null
                                    addUrl = ""
                                    addError = null
                                    showAddSheet = true
                                },
                                onToggle = { sub, on ->
                                    persist(subscriptions.map { if (it.id == sub.id) it.copy(enabled = on) else it })
                                },
                                onOpen = { sub ->
                                    openSubId = sub.id
                                    screen = Screen.SUBSCRIBE_DETAIL
                                },
                                footerText = footerFor(subscriptions, packages),
                            )

                            Screen.SUBSCRIBE_DETAIL -> {
                                val sub = subscriptions.firstOrNull { it.id == openSubId }
                                LaunchedEffect(sub) {
                                    if (sub == null) screen = Screen.SUBSCRIBE
                                }
                                if (sub != null) {
                                    SubscribeDetailScreen(
                                        sub = sub,
                                        pkg = packages[sub.id],
                                        checking = checkingOne,
                                        onBack = { screen = Screen.SUBSCRIBE },
                                        onToggle = { on ->
                                            persist(
                                                subscriptions.map {
                                                    if (it.id == sub.id) it.copy(enabled = on) else it
                                                },
                                            )
                                        },
                                        onAutoUpdateChange = { on ->
                                            persist(
                                                subscriptions.map {
                                                    if (it.id == sub.id) it.copy(autoUpdate = on) else it
                                                },
                                            )
                                        },
                                        onCheckNow = {
                                            checkingOne = true
                                            scope.launch {
                                                val updated = refresh(sub)
                                                persist(
                                                    subscriptions.map {
                                                        if (it.id == sub.id) updated else it
                                                    },
                                                )
                                                checkingOne = false
                                            }
                                        },
                                        onEdit = {
                                            editingSubId = sub.id
                                            addUrl = sub.url
                                            addError = null
                                            showAddSheet = true
                                        },
                                        onRollback = {
                                            toast(ctx, "上一版本暂未保留")
                                        },
                                        onRemove = {
                                            RuleStore.clearCache(ctx, sub.id)
                                            scope.launch {
                                                val list = withContext(Dispatchers.IO) {
                                                    SubscriptionStore.remove(ctx, sub.id)
                                                }
                                                subscriptions = list
                                                screen = Screen.SUBSCRIBE
                                            }
                                        },
                                    )
                                }
                            }

                            Screen.SETTINGS -> SettingsScreen(
                                darkOverride = darkOverride,
                                onDarkOverrideChange = { index ->
                                    darkOverride = index
                                    AppSettings.setDarkModeOverride(ctx, index)
                                },
                                devLogEnabled = devLogEnabled,
                                onDevLogToggle = { on ->
                                    DevLog.setEnabled(ctx, on)
                                    devLogEnabled = on
                                },
                                onOpenDevLog = { screen = Screen.DEVLOG },
                                versionName = BuildConfig.VERSION_NAME,
                                onAbout = { toast(ctx, "续播 ${BuildConfig.VERSION_NAME} · 仅支持$TARGET_NAME") },
                                onResetDefaults = {
                                    AppSettings.setAutoContinueEnabled(ctx, true)
                                    AppSettings.setAdSkipEnabled(ctx, true)
                                    AppSettings.setDarkModeOverride(ctx, 0)
                                    darkOverride = 0
                                    toast(ctx, "已恢复默认设置")
                                },
                            )

                            Screen.DEVLOG -> DevLogScreen(
                                enabled = devLogEnabled,
                                onToggle = { on ->
                                    DevLog.setEnabled(ctx, on)
                                    devLogEnabled = on
                                },
                                onCopy = {
                                    copyToClipboard(ctx, DevLog.exportText(ctx))
                                    toast(ctx, "开发者日志已复制")
                                },
                                onShare = { DevLog.share(ctx) },
                                onClear = {
                                    DevLog.clear()
                                    toast(ctx, "开发者日志已清空")
                                },
                                onBack = { screen = Screen.SETTINGS },
                            )

                            Screen.A11Y -> A11yScreen(onBack = { screen = Screen.SETTINGS })

                            Screen.RECHARGE -> RechargeScreen(
                                input = "",
                                onInputChange = { },
                                onBack = { screen = Screen.SETTINGS },
                            )
                        }

                    if (screen != Screen.SUBSCRIBE_DETAIL) {
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            BottomTabs(
                                current = screen.toTab(),
                                onSelect = { screen = it.toScreen() },
                            )
                        }
                    }
                }
            }

            AddSubscribeSheet(
                visible = showAddSheet,
                isEdit = editingSubId != null,
                url = addUrl,
                onUrlChange = { addUrl = it; addError = null },
                error = addError,
                onCancel = {
                    showAddSheet = false
                    editingSubId = null
                },
                onConfirm = {
                    val trimmed = addUrl.trim()
                    val editing = editingSubId
                    if (!trimmed.startsWith("https://")) {
                        addError = "只支持 https:// 地址"
                    } else if (subscriptions.any { it.url == trimmed && it.id != editing }) {
                        addError = "这个地址已经添加过"
                    } else if (editing != null) {
                        // 改地址：清掉旧缓存，按新地址重新拉；没拉到的先沿用旧规则，不打断使用。
                        val old = subscriptions.firstOrNull { it.id == editing }
                        val renamed = old != null && old.name == hostNameOf(old.url)
                        val updated = old!!.copy(
                            url = trimmed,
                            name = if (renamed) hostNameOf(trimmed) else old.name,
                            lastError = null,
                        )
                        RuleStore.clearCache(ctx, editing)
                        scope.launch {
                            val list = withContext(Dispatchers.IO) {
                                SubscriptionStore.upsert(ctx, updated)
                            }
                            val fetched = refresh(updated)
                            persist(list.map { if (it.id == editing) fetched else it })
                            showAddSheet = false
                            editingSubId = null
                        }
                    } else {
                        val sub = RuleSubscription(
                            id = "sub-" + System.currentTimeMillis().toString(36),
                            url = trimmed,
                            name = hostNameOf(trimmed),
                        )
                        scope.launch {
                            val list = withContext(Dispatchers.IO) {
                                SubscriptionStore.upsert(ctx, sub)
                            }
                            val fetched = refresh(sub)
                            val merged = list.map { if (it.id == sub.id) fetched else it }
                            persist(merged)
                            showAddSheet = false
                        }
                    }
                },
            )

            BackHandler(enabled = showAddSheet) {
                showAddSheet = false
                editingSubId = null
            }
            BackHandler(enabled = !showAddSheet && screen != Screen.HOME) {
                screen = when (screen) {
                    Screen.SUBSCRIBE_DETAIL -> Screen.SUBSCRIBE
                    Screen.SUBSCRIBE -> Screen.HOME
                    else -> Screen.HOME
                }
            }
        }
    }
}

private val STAT_COLORS = listOf(
    androidx.compose.ui.graphics.Color(0xFFFF5C4C),
    androidx.compose.ui.graphics.Color(0xFFFF981A),
    androidx.compose.ui.graphics.Color(0xFF5692FF),
    androidx.compose.ui.graphics.Color(0xFF4CBE98),
)

private fun hostNameOf(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (_: Throwable) {
    url
}

private fun footerFor(subs: List<RuleSubscription>, pkg: Map<String, RulePackage?>): String {
    if (subs.isEmpty()) return ""
    val lastChecked = subs.maxOfOrNull { it.lastCheckedAt } ?: 0L
    val version = pkg.values.filterNotNull().firstOrNull()?.version
    val count = pkg.values.filterNotNull().sumOf { it.rules.size }
    return buildString {
        append("上次检查 ")
        append(if (lastChecked > 0) relativeTime(lastChecked) else "从未")
        if (version != null) {
            append(" · 规则包 v")
            append(version)
            append(" · ")
            append(count)
            append(" 条")
        }
    }
}

private fun relativeTime(at: Long): String {
    val delta = System.currentTimeMillis() - at
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> "${delta / 60_000} 分钟前"
        delta < 86_400_000 -> "${delta / 3_600_000} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
    ctx.packageManager.getPackageInfo(pkg, 0)
    true
} catch (_: Throwable) {
    false
}

private fun requestIgnoreBattery(ctx: Context) = runCatching {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}.onFailure { toast(ctx, "请在系统设置里手动允许后台运行") }

private fun openNotificationSettings(ctx: Context) = runCatching {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}.onFailure {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openOverlaySettings(ctx: Context) = runCatching {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        .setData(Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}.onFailure { toast(ctx, "请到系统设置里允许「显示在其他应用上层」") }

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("shortdrama-diagnostics", text))
}

private fun toast(ctx: Context, text: String) {
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
