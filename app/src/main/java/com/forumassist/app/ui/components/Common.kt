package com.forumassist.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.data.TimeUtil
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import com.forumassist.app.ui.components.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** MIUIX 卡片圆角：HyperOS 的分组卡片是 16dp */
val CardRadius: Dp = 16.dp

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = CardRadius,
        colors = CardDefaults.defaultColors(color = color),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        content = content
    )
}

@Composable
fun SiteAvatar(name: String, color: Int, size: Dp = 40.dp, radius: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(Color(color).copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(1),
            color = Color(color),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp
        )
    }
}

@Composable
fun TagPill(
    text: String,
    color: Color = MiuixTheme.colorScheme.primary,
    filled: Boolean = false
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = if (filled) Color.White else color,
            style = MiuixTheme.textStyles.footnote2,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 筛选 chip：HyperOS 的胶囊按钮，选中是主色浅底 + 主色文字 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) cs.primary else cs.onSurfaceContainerVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
fun ThreadCard(
    thread: ThreadSummary,
    siteName: String,
    siteColor: Int,
    newReplies: Int = 0,
    read: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val cs = MiuixTheme.colorScheme
    // 读过的帖子整条压暗：一眼就能跳过，不用回忆「这条点过没」
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (read) 0.6f else 1f),
        cornerRadius = CardRadius,
        colors = CardDefaults.defaultColors(color = cs.surfaceContainer),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(siteColor))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = siteName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (thread.boardName.isNotBlank()) {
                    Text(
                        text = " · " + thread.boardName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = cs.onSurfaceContainerVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = TimeUtil.prettyOrRaw(thread.lastTime, thread.ts),
                    style = MiuixTheme.textStyles.footnote2,
                    color = cs.onSurfaceContainerVariant.copy(alpha = 0.9f)
                )
            }

            Spacer(Modifier.height(7.dp))

            Text(
                text = thread.title,
                style = MiuixTheme.textStyles.headline1,
                color = if (read) cs.onSurfaceContainerVariant else cs.onSurfaceContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 23.sp
            )

            // 分类 + 标签：跟列表页一样带出来，方便一眼筛掉不想看的题材
            val shownTags = thread.tags.take(3)
            if (thread.typeName.isNotBlank() || shownTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thread.typeName.isNotBlank()) {
                        TagPill(thread.typeName, cs.onTertiaryContainer)
                        Spacer(Modifier.width(4.dp))
                    }
                    shownTags.forEach { tag ->
                        TagPill("#$tag", cs.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (thread.tags.size > shownTags.size) {
                        Text(
                            text = "+${thread.tags.size - shownTags.size}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = cs.onSurfaceContainerVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(9.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.isDigest) {
                    TagPill("精", cs.primary)
                    Spacer(Modifier.width(4.dp))
                }
                if (newReplies > 0) {
                    TagPill("+$newReplies", cs.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = thread.author.ifBlank { "匿名" },
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                SmallStat(Icons.Outlined.ChatBubbleOutline, thread.replies.toString())
                Spacer(Modifier.width(12.dp))
                SmallStat(Icons.Outlined.Visibility, thread.views.toString())
                trailing?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }
        }
    }
}

@Composable
fun SmallStat(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.9f)
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    desc: String = "",
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cs = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = cs.onSurfaceContainerVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.headline1,
            color = cs.onBackground
        )
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = desc,
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onBackgroundVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, content = { Text(actionLabel) })
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onBackground
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    desc: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MiuixTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainer
            )
            if (!desc.isNullOrBlank()) {
                Text(
                    text = desc,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CardRadius),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = CardRadius,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        content = content
    )
}

/** 分组标题：HyperOS 设置页那种小一号的灰色标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun AnimatedTagBackground(selected: Boolean): Color {
    return animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
        else Color.Transparent,
        label = "tag"
    ).value
}

/** 统一的内边距：列表页左右 14dp，HyperOS 的分组卡片间距 */
val ListPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp)
