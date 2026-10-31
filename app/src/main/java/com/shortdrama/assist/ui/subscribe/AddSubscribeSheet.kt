package com.shortdrama.assist.ui.subscribe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 添加订阅的底部弹层。
 *
 * 自己画而不用 ModalBottomSheet：Material3 的弹层自带 scrim 动画与圆角规格，
 * 跟 iOS 的 20pt 圆角 + 抓取条对不上，改起来比手写还麻烦。
 */
@Composable
fun AddSubscribeSheet(
    visible: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    isEdit: Boolean = false,
) {
    if (!visible) return
    val t = LocalTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(t.surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            // 抓取条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(t.hairline),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (isEdit) "编辑订阅" else "添加订阅",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = t.text,
            )
            Spacer(Modifier.height(14.dp))
            Text(text = "规则包地址", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.height(7.dp))
            BasicTextField(
                value = url,
                onValueChange = onUrlChange,
                singleLine = true,
                cursorBrush = SolidColor(t.accent),
                textStyle = TextStyle(fontSize = 15.sp, color = t.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surfaceSunken)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (url.isEmpty()) {
                        Text(
                            text = "https://example.com/rules.json",
                            fontSize = 15.sp,
                            color = t.textTertiary,
                        )
                    }
                    inner()
                },
            )
            if (error != null) {
                Spacer(Modifier.height(7.dp))
                Text(text = error, fontSize = 12.sp, color = t.danger)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "需返回 JSON：name / version / rules\n第三方源无需签名；只接受声明式判据，不执行远程代码",
                fontSize = 12.sp,
                color = t.textSecondary,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SheetButton(
                    text = "取消",
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = onCancel,
                )
                Spacer(Modifier.width(12.dp))
                SheetButton(
                    text = if (isEdit) "保存" else "添加",
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) t.accent else t.surfaceSunken)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) androidx.compose.ui.graphics.Color.White else t.text,
        )
    }
}
