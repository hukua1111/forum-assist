package com.forumassist.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.PinnedBoard
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.ScreenHeader

/**
 * 底栏「板块」：只放用户从论坛里固定过来的版块，点一下直接进帖子列表。
 * 固定/取消固定在 论坛 → 站点 → 版块列表右侧的 ☆ 上操作。
 */
@Composable
fun PinnedBoardsScreen(
    state: AppState,
    onOpenBoard: (PinnedBoard) -> Unit,
    onManage: () -> Unit
) {
    val pins = state.pinnedBoards

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "板块",
            subtitle = if (pins.isEmpty()) "把常去的版块固定到这里" else "${pins.size} 个固定版块 · 快捷访问",
            trailing = {
                Surface(
                    onClick = onManage,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Add, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "添加",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        )

        if (pins.isEmpty()) {
            EmptyState(
                title = "还没有固定的板块",
                desc = "去「论坛」选一个站点，在版块右侧点一下 ☆，\n它就会固定到这里，以后一键直达。",
                icon = Icons.Rounded.Forum,
                actionLabel = "去论坛选板块",
                onAction = onManage
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(pins, key = { it.key }) { pin ->
                    val site = state.site(pin.siteId)
                    PinnedRow(
                        pin = pin,
                        siteName = site?.name ?: "站点已删除",
                        siteHost = site?.host.orEmpty(),
                        siteColor = site?.color ?: 0xFF888888.toInt(),
                        onClick = { onOpenBoard(pin) },
                        onUnpin = { state.unpinBoard(pin.key) }
                    )
                }

                item(key = "tip") {
                    Text(
                        text = "长按/点击右侧 ★ 可取消固定",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedRow(
    pin: PinnedBoard,
    siteName: String,
    siteHost: String,
    siteColor: Int,
    onClick: () -> Unit,
    onUnpin: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 6.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(siteColor).copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Forum, null,
                    tint = Color(siteColor),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pin.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(siteName, siteHost).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onUnpin,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Icon(
                    Icons.Rounded.Star, "取消固定",
                    tint = Color(siteColor),
                    modifier = Modifier
                        .padding(7.dp)
                        .size(17.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
