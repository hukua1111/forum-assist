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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.Board
import com.forumassist.app.data.Site
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.SiteAvatar

@Composable
fun BoardsScreen(
    state: AppState,
    site: Site,
    onBack: () -> Unit,
    onOpenBoard: (Board) -> Unit,
    onOpenWeb: () -> Unit,
    onSearch: () -> Unit
) {
    var allBoards by remember { mutableStateOf<List<Board>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val current = state.site(site.id) ?: site
    val boards = allBoards.filterNot { it.fid in current.blockedFids }
    val hiddenBoards = allBoards.filter { it.fid in current.blockedFids }

    LaunchedEffect(site.id) {
        loading = true
        allBoards = state.allBoards(site)
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            SiteAvatar(site.name, site.color, size = 34.dp, radius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = site.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Outlined.Search, "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onOpenWeb) {
                Icon(
                    Icons.Outlined.Language, "网页版",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }

            boards.isEmpty() -> EmptyState(
                title = "没能读取到版块",
                desc = "该站点可能调整了页面结构、需要登录，或者开启了防护。\n可以直接用网页模式浏览，体验同样完整。",
                icon = Icons.Rounded.Forum,
                actionLabel = "用网页模式打开",
                onAction = onOpenWeb
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "版块  ·  ${boards.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                    )
                }

                items(boards, key = { it.fid }) { board ->
                    BoardRow(
                        board = board,
                        siteColor = site.color,
                        blocked = false,
                        onOpen = { onOpenBoard(board) },
                        onToggleBlock = { state.toggleBoardBlocked(current, board.fid) }
                    )
                }

                if (hiddenBoards.isNotEmpty()) {
                    item {
                        Text(
                            text = "已屏蔽 · ${hiddenBoards.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(hiddenBoards, key = { "hidden_${it.fid}" }) { board ->
                        BoardRow(
                            board = board,
                            siteColor = site.color,
                            blocked = true,
                            onOpen = {},
                            onToggleBlock = { state.toggleBoardBlocked(current, board.fid) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(
    board: Board,
    siteColor: Int,
    blocked: Boolean,
    onOpen: () -> Unit,
    onToggleBlock: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !blocked) { onOpen() },
        shape = RoundedCornerShape(16.dp),
        color = if (blocked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(siteColor).copy(alpha = if (blocked) 0.06f else 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Forum, null,
                    tint = Color(siteColor).copy(alpha = if (blocked) 0.5f else 1f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = board.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (blocked) "已屏蔽，不显示在列表中" else board.desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!blocked && board.todayPosts > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "今日 ${board.todayPosts}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            IconButton(onClick = onToggleBlock, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (blocked) Icons.Rounded.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (blocked) "恢复该版块" else "屏蔽该版块",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
            if (!blocked) {
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
