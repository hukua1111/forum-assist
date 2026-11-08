package com.forumassist.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文字按钮。
 *
 * MIUIX 的 TextButton 只收纯文本，而 App 里大量按钮是「图标 + 文字」或者带副标题的
 * 组合，所以这里保留 Composable content 的写法，视觉上仍按 HyperOS 处理：
 * 主色文字、无底色、按下去有一层淡淡的圆角高亮。
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String? = null,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            if (content != null) {
                content()
            } else {
                Text(text = text.orEmpty(), style = MiuixTheme.textStyles.button, color = color)
            }
        }
    }
}
