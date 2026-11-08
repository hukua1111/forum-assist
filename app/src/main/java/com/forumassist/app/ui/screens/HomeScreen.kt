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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.ThreadCard
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    state: AppState,
    onOpenThread: (ThreadSummary) -> Unit,
    onSearch: () -> Unit,
    onOpenSites: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.feedTab) {
        if (state.feed.isEmpty() && !state.feedLoading) state.loadFeed(reset = true)
    }

    // 回到首页只做「过期检查」：距上次刷新超过 10 分钟才重新拉，
    // 从正文退回来、切 tab 都保持原样。下拉刷新不受这个限制。
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 0.dp)
    ) {
        HomeHeader(state, onSearch, onOpenSites)
        TabRow(
            tabs = listOf("最新主题", "最新回复", "热帖"),
            selectedTabIndex = state.feedTab,
            onTabSelected = { state.switchTab(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        PullToRefresh(
            isRefreshing = state.feedRefreshing,
            onRefresh = { state.loadFeed(reset = true) },
            modifier = Modifier.fillMaxSize()
        ) {
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
                    desc = "下拉刷新试试，或者去「论坛」把常看的版块固定到底栏板块",
                    actionLabel = "去论坛",
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
                            read = state.isRead(t.key),
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
                                CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (state.feedEnd && state.feed.isNotEmpty()) {
                        item(key = "end") {
                            Text(
                                text = "— 已经到底了 —",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: AppState, onSearch: () -> Unit, onOpenSites: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    val enabled = state.sites.count { it.enabled }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "论坛助手",
                style = MiuixTheme.textStyles.title1,
                color = cs.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onOpenSites)
            ) {
                Text(
                    text = "$enabled 个站点聚合中",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.onBackgroundVariant
                )
                Text(
                    text = "  ·  管理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = cs.primary
                )
            }
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = cs.onBackgroundVariant)
        }
        IconButton(onClick = { state.loadFeed(reset = true) }) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = cs.onBackgroundVariant)
        }
    }
}

@Composable
private fun SoftNotice(text: String, onRetry: () -> Unit) {
    val cs = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.primary.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = cs.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onBackgroundVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "重试",
                style = MiuixTheme.textStyles.footnote1,
                color = cs.primary,
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
        CircularProgressIndicator(size = 26.dp, strokeWidth = 2.5.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "正在聚合各站点最新内容…",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
    }
}
