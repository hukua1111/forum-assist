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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.ThreadCard

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    state: AppState,
    onOpenThread: (ThreadSummary) -> Unit,
    onSearch: () -> Unit,
    onOpenSites: () -> Unit
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullRefreshState(
        refreshing = state.feedRefreshing,
        onRefresh = { state.loadFeed(reset = true) }
    )

    LaunchedEffect(state.feedTab) {
        if (state.feed.isEmpty() && !state.feedLoading) state.loadFeed(reset = true)
    }

    // 回到首页时按需刷新（3 分钟内不重复）
    LaunchedEffect(Unit) { state.maybeAutoRefresh() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.feed.size - 3 && state.feed.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) state.loadMoreFeed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullState)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(state, onSearch, onOpenSites)
            FeedTabs(state)

            when {
                state.feedLoading && state.feed.isEmpty() -> LoadingBlock()
                state.feed.isEmpty() && state.feedError != null -> EmptyState(
                    title = "暂时没有内容",
                    desc = state.feedError.orEmpty(),
                    icon = Icons.Rounded.WifiOff,
                    actionLabel = "重试",
                    onAction = { state.loadFeed(reset = true) }
                )
                state.feed.isEmpty() -> EmptyState(
                    title = "还没有帖子",
                    desc = "下拉刷新试试，或者去添加更多论坛站点",
                    actionLabel = "管理站点",
                    onAction = onOpenSites
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.feedError?.let { msg ->
                        item(key = "error") { SoftNotice(msg) { state.loadFeed(reset = true) } }
                    }
                    items(state.feed, key = { it.key }) { t ->
                        ThreadCard(
                            thread = t,
                            siteName = state.siteName(t.siteId),
                            siteColor = state.site(t.siteId)?.color ?: 0xFF888888.toInt(),
                            newReplies = state.newReplyCount(t),
                            onClick = { onOpenThread(t) }
                        )
                    }
                    if (state.feedLoading) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                    if (state.feedEnd && state.feed.isNotEmpty()) {
                        item(key = "end") {
                            Text(
                                text = "— 已经到底了 —",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = state.feedRefreshing,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HomeHeader(state: AppState, onSearch: () -> Unit, onOpenSites: () -> Unit) {
    val enabled = state.sites.count { it.enabled }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "论坛助手",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenSites)
            ) {
                Text(
                    text = "$enabled 个站点聚合中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "  ·  管理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { state.loadFeed(reset = true) }) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeedTabs(state: AppState) {
    val tabs = listOf("最新主题", "最新回复", "热帖", "精华")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = state.feedTab == index
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { state.switchTab(index) },
                shape = RoundedCornerShape(9.dp),
                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                shadowElevation = if (selected) 1.dp else 0.dp
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(vertical = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SoftNotice(text: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "正在聚合各站点最新内容…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
