package com.shortdrama.assist.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.DevLog
import com.shortdrama.assist.DevLogOverlay
import com.shortdrama.assist.log.LogDb
import com.shortdrama.assist.ui.components.CardBox
import com.shortdrama.assist.ui.components.Hairline
import com.shortdrama.assist.ui.components.IosSectionTitle
import com.shortdrama.assist.ui.components.IosSegmented
import com.shortdrama.assist.ui.theme.LocalTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_SIZE = 200

/** 级别筛选：索引对应 [LEVEL_FILTERS]，null 表示全部。 */
private val LEVEL_FILTER_LABELS = listOf("全部", "信息+", "警告+", "错误")
private val LEVEL_FILTER_VALUES = listOf<Char?>(null, 'I', 'W', 'E')

/**
 * 开发者模式：控制日志记录，并按数据库查看 / 搜索 / 复制 / 分享。
 *
 * 日志落在本机 SQLite（`dev_log.db`，见 [LogDb]），重启 App 不会丢；
 * 最多保留最近 [LogDb.MAX_ROWS] 条与 [LogDb.MAX_AGE_DAYS] 天。
 */
@Composable
fun DevLogScreen(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalTokens.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val overlayGranted = Settings.canDrawOverlays(context)

    var keyword by remember { mutableStateOf("") }
    var levelIndex by remember { mutableStateOf(0) }
    var limit by remember { mutableStateOf(PAGE_SIZE) }
    var entries by remember { mutableStateOf(listOf<DevLog.Entry>()) }
    var total by remember { mutableLongStateOf(0L) }
    var bytes by remember { mutableLongStateOf(0L) }

    // 关键字/级别/条数变了就重查；每秒轮询一次把新日志刷上来
    LaunchedEffect(enabled, keyword, levelIndex, limit) {
        while (true) {
            val filter = LEVEL_FILTER_VALUES[levelIndex]
            val page = withContext(Dispatchers.IO) {
                DevLog.query(limit = limit, minLevel = filter, keyword = keyword)
            }
            val rows = withContext(Dispatchers.IO) { DevLog.rowCount(filter, keyword) }
            entries = page
            total = rows
            bytes = DevLog.dbBytes()
            delay(1_200)
        }
    }

    // 悬浮日志不自动挂：它是给「看红果时」用的，在这里自动 sync 会把自己界面挡住。
    // 只有用户点「悬浮日志」那一行才挂上/收起。

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
                        .clickable(onClick = onBack)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "‹ 返回", color = t.textSecondary, fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "开发者模式",
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 22.dp))
            }

            CardBox(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "记录运行日志", color = t.text, fontSize = 16.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (enabled) {
                                    "正在写入本机数据库"
                                } else {
                                    "开启后写入本机数据库，重启不丢"
                                },
                                color = t.textTertiary,
                                fontSize = 13.sp,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = t.accent,
                                checkedTrackColor = t.accent.copy(alpha = 0.35f),
                                uncheckedThumbColor = t.textTertiary,
                                uncheckedTrackColor = t.hairline,
                            ),
                        )
                    }
                    Hairline()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LogAction("复制", onCopy, Modifier.weight(1f))
                        HairlineVertical()
                        LogAction("分享", onShare, Modifier.weight(1f))
                        HairlineVertical()
                        LogAction("清空", onClear, Modifier.weight(1f))
                    }
                    Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatBytes(bytes)} · 共 $total 条",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "SQLite · 留 ${LogDb.MAX_AGE_DAYS} 天",
                            color = t.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                    Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!overlayGranted) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } else {
                                    DevLogOverlay.sync(context)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "悬浮日志", color = t.text, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "屏幕最顶层透明显示，点击条展开",
                                color = t.textTertiary,
                                fontSize = 12.sp,
                            )
                        }
                        Text(
                            text = if (overlayGranted) "已授权" else "去授权",
                            color = t.accent,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SearchField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    limit = PAGE_SIZE
                },
                onClear = {
                    keyword = ""
                    limit = PAGE_SIZE
                },
            )
            Spacer(Modifier.height(10.dp))
            IosSegmented(
                options = LEVEL_FILTER_LABELS,
                selectedIndex = levelIndex,
                onSelect = {
                    levelIndex = it
                    limit = PAGE_SIZE
                },
            )

            IosSectionTitle(
                text = "日志",
                trailing = if (total > entries.size) "${entries.size} / $total" else null,
            )

            CardBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                when {
                    entries.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                !enabled -> "开发者日志未开启"
                                keyword.isNotBlank() -> "没有匹配「$keyword」的日志"
                                levelIndex > 0 -> "该级别下还没有日志"
                                else -> "等待日志…触发一次动作后会自动记录"
                            },
                            color = t.textTertiary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        // 倒序展示，最新的在最上面，省得每次都要滚到底
                        items(entries) { entry ->
                            SelectionContainer {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = timeOf(entry.at),
                                            color = t.textTertiary,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "${entry.level}/${entry.tag}",
                                            color = when (entry.level) {
                                                'E' -> t.danger
                                                'W' -> t.accent
                                                else -> t.textTertiary
                                            },
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    Text(
                                        text = entry.message,
                                        color = t.textSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        if (total > entries.size) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { limit += PAGE_SIZE }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "加载更多（还有 ${total - entries.size} 条）",
                                        color = t.accent,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(t.surfaceSunken)
            .heightIn(min = 38.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(text = "搜索", color = t.textTertiary, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = "tag 或日志内容", color = t.textTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(t.accent),
                textStyle = TextStyle(fontSize = 13.sp, color = t.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                text = "✕",
                color = t.textTertiary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun LogAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = t.accent, fontSize = 14.sp)
    }
}

@Composable
private fun HairlineVertical() {
    val t = LocalTokens.current
    Box(
        modifier = Modifier
            .padding(vertical = 10.dp)
            .width(1.dp)
            .height(22.dp)
            .background(t.hairline),
    )
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun timeOf(at: Long): String = timeFormat.format(Date(at))

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
