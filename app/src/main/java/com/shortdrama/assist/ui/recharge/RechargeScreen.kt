package com.shortdrama.assist.ui.recharge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortdrama.assist.ui.components.CardBox
import com.shortdrama.assist.ui.components.Hint
import com.shortdrama.assist.ui.theme.LocalTokens

/**
 * 卡密兑换（M1 占位，功能属 M3）。
 *
 * **当前阶段不开放**：商业化与后端是延后但不取消（见 docs/03、docs/04），
 * 所以这一页保留完整交互与文案，只把"提交"接到一个明确的未开放说明上。
 *
 * 卡密格式：Crockford Base32，**排除易混字符 I / L / O / U**。
 */
@Composable
fun RechargeScreen(
    input: String,
    onInputChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val t = LocalTokens.current

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
                    Text(text = "‹ 返回", color = t.textSecondary, fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(text = "兑换卡密", color = t.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.padding(horizontal = 22.dp))
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "卡密会自动叠加到现有有效期上",
                    color = t.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(22.dp))

                CardBox(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 22.dp),
                    ) {
                        Text(
                            text = input.ifEmpty { "XXXX-XXXX-XXXX-XXXX" },
                            color = if (input.isEmpty()) t.textTertiary else t.text,
                            fontSize = 22.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Hint(text = "自动大写，忽略分隔符")

                Spacer(Modifier.height(26.dp))
                CardBox(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(
                            text = "当前版本未开放",
                            color = t.danger,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "授权服务尚未启用，后续开放后会在这里直接生效。",
                            color = t.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

/**
 * 卡密输入规范化：去分隔符、转大写、排除非法字符、每 4 位补分隔线。
 * 独立成纯函数便于单测。
 */
object CardKeyFormat {

    private val ALLOWED = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford Base32，无 I L O U
    private const val GROUP = 4
    private const val MAX_LEN = 16

    fun normalize(raw: String): String {
        val cleaned = raw.uppercase().filter { it in ALLOWED }.take(MAX_LEN)
        return cleaned.chunked(GROUP).joinToString("-")
    }

    /** 去掉分隔线后的裸值，提交给服务端用。 */
    fun raw(formatted: String): String = formatted.filter { it in ALLOWED }
}
