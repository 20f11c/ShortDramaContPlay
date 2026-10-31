package com.shortdrama.assist.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ocr.OcrState
import com.shortdrama.assist.ocr.ShizukuBridge
import com.shortdrama.assist.ui.components.CardBox
import com.shortdrama.assist.ui.components.GhostButton
import com.shortdrama.assist.ui.components.Hairline
import com.shortdrama.assist.ui.components.PrimaryButton
import com.shortdrama.assist.ui.components.SectionLabel
import com.shortdrama.assist.ui.components.SettingsRow
import com.shortdrama.assist.ui.theme.LocalTokens
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay

private fun ignoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun requestIgnoreBattery(ctx: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${ctx.packageName}"),
    )
    val ok = runCatching { ctx.startActivity(direct) }.isSuccess
    if (!ok) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/**
 * OCR 模式（V2）。
 *
 * 这里只负责：授权、开关、看状态。真正的截图/识别/手势在
 * [com.shortdrama.assist.ocr.OcrAutomationService] 的前台服务里。
 */
@Composable
fun OcrScreen(
    enabled: Boolean,
    targetInstalled: Boolean,
    onToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var shizukuStatus by remember { mutableStateOf(ShizukuBridge.statusText(ctx)) }
    var foreground by remember { mutableStateOf("") }
    var lastText by remember { mutableStateOf("") }
    var lastAction by remember { mutableStateOf("") }
    var lastLatency by remember { mutableStateOf(0L) }
    var batteryExempt by remember { mutableStateOf(ignoringBattery(ctx)) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            shizukuStatus = ShizukuBridge.statusText(ctx)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            shizukuStatus = ShizukuBridge.statusText(ctx)
            foreground = OcrState.foregroundPackage
            lastText = OcrState.lastText
            lastAction = OcrState.lastAction
            lastLatency = OcrState.lastLatencyMs
            batteryExempt = ignoringBattery(ctx)
            delay(1_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(t.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "‹ 返回",
                        color = t.textSecondary,
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "OCR 模式",
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 22.dp))
            }

            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SectionLabel("运行状态")
                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        SettingsRow(
                            title = "OCR 自动续播",
                            subtitle = when {
                                !targetInstalled -> "目标应用未安装"
                                enabled && OcrState.running -> "已开启，前台服务运行中"
                                enabled -> "已开启，服务未运行"
                                else -> "已关闭"
                            },
                            trailing = when {
                                enabled && OcrState.running -> "运行中 ›"
                                enabled -> "未运行 ›"
                                else -> "已关闭 ›"
                            },
                            onClick = {
                                if (!enabled) {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationPermission.launch(
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    }
                                    onToggle(true)
                                } else {
                                    onToggle(false)
                                }
                            },
                        )
                        Hairline()
                        SettingsRow(
                            title = "Shizuku",
                            subtitle = shizukuStatus,
                            trailing = if (ShizukuBridge.hasPermission()) "已授权 ›" else "去授权 ›",
                            onClick = {
                                val activity = ctx as? Activity
                                if (activity != null) ShizukuBridge.requestPermission(activity)
                            },
                        )
                        Hairline()
                        SettingsRow(
                            title = "后台运行",
                            subtitle = if (batteryExempt) {
                                "已允许后台运行"
                            } else {
                                "系统可能冻结 OCR，请设为“不优化”"
                            },
                            trailing = if (batteryExempt) "已允许" else "去设置 ›",
                            onClick = { if (!batteryExempt) requestIgnoreBattery(ctx) },
                        )
                        Hairline()
                        SettingsRow(
                            title = "当前前台",
                            subtitle = foreground.ifEmpty { "等待服务上报" },
                            trailing = if (OcrState.running) "运行中" else "未运行",
                        )
                    }
                }

                SectionLabel("最近一帧")
                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "耗时 ${lastLatency}ms" +
                                if (lastAction.isNotEmpty()) " · 动作 $lastAction" else "",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = lastText.ifEmpty { "还没有识别结果。开启后请让红果停在播放页。" },
                            color = t.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (enabled && OcrState.running) {
                        GhostButton(text = "停止 OCR 模式", onClick = { onToggle(false) })
                    } else {
                        PrimaryButton(
                            text = when {
                                !ShizukuBridge.hasPermission() -> "先授权 Shizuku"
                                enabled -> "重新启动 OCR 模式"
                                else -> "启动 OCR 模式"
                            },
                            onClick = {
                                if (!ShizukuBridge.hasPermission()) {
                                    val activity = ctx as? Activity
                                    if (activity != null) ShizukuBridge.requestPermission(activity)
                                } else {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationPermission.launch(
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    }
                                    onToggle(true)
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "工作方式",
                            color = t.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Shizuku 整屏截图 → 裁出片尾区域 → 本地 OCR → " +
                                "只在识别到「上滑继续观看短剧」或「上滑继续观看剧集」时上滑；" +
                                "未知页面一律不动。",
                            color = t.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "需要 Shizuku 常驻并授权；裁剪后每帧识别约 0.4–0.7 秒。",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
