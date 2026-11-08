package com.forumassist.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.ScreenHeader
import com.forumassist.app.ui.components.ThreadCard

@Composable
fun FavoritesScreen(
    state: AppState,
    onOpenThread: (ThreadSummary) -> Unit,
    onBrowse: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("收藏", "离线", "历史")
    val list = when (tab) {
        0 -> state.favorites
        1 -> state.offline
        else -> state.history
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "我的收藏",
            subtitle = "收藏 ${state.favorites.size} · 离线 ${state.offline.size} · 读过 ${state.history.size}"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier.clickable { tab = index },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (tab == index) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Surface(
                        modifier = Modifier
                            .width(18.dp)
                            .padding(top = 2.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = if (tab == index) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ) {
                        Spacer(Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                }
            }
        }

        if (list.isEmpty()) {
            EmptyState(
                title = when (tab) {
                    0 -> "还没有收藏"
                    1 -> "没有离线帖子"
                    else -> "还没有阅读记录"
                },
                desc = when (tab) {
                    0 -> "在帖子右上角点收藏，重要的内容随时回看"
                    1 -> "在帖子菜单里选择「离线保存」，没有网络也能读"
                    else -> "读过的帖子会自动记录在这里"
                },
                icon = if (tab == 1) Icons.Outlined.History else Icons.Outlined.BookmarkBorder,
                actionLabel = "去逛逛",
                onAction = onBrowse
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.key }) { t ->
                    ThreadCard(
                        thread = t,
                        siteName = state.siteName(t.siteId),
                        siteColor = state.site(t.siteId)?.color ?: 0xFF888888.toInt(),
                        newReplies = 0,
                        read = state.isRead(t.key),
                        onClick = { onOpenThread(t) }
                    )
                }
            }
        }
    }
}
