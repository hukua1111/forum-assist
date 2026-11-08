package com.forumassist.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.BoardFilter
import com.forumassist.app.data.DefaultBoardOrders
import com.forumassist.app.data.Site
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.ThreadCard

private val DEFAULT_ORDERS = DefaultBoardOrders

@Composable
fun ThreadListScreen(
    state: AppState,
    site: Site,
    fid: String,
    boardName: String,
    onBack: () -> Unit,
    onOpenThread: (ThreadSummary) -> Unit,
    onCompose: () -> Unit,
    onLogin: () -> Unit = {}
) {
    // 第一层用 key 记选中项，真正请求用的是它的 query（页面链接原文）
    var orderKey by remember(fid, site.id) { mutableStateOf("") }
    var typeKey by remember(fid, site.id) { mutableStateOf("") }
    val orders = DEFAULT_ORDERS
    // 换版块时分类列表要重来，否则会带着上一个版块的 typeid 去请求
    var types by remember(fid, site.id) { mutableStateOf<List<BoardFilter>>(emptyList()) }
    var items by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var end by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // 置顶帖不进列表（版规/公告类，占地方又不常看）
    val visible = remember(items) { items.filterNot { it.isTop } }

    LaunchedEffect(site.id) { state.syncLoginState(site) }

    LaunchedEffect(site.id, fid, orderKey, typeKey, reloadKey) {
        loading = true
        error = null
        end = false
        page = 1
        // 立刻清空：以前保留旧列表且不显示任何加载状态，
        // 点筛选看起来像「界面没刷新」，其实只是新数据还没回来
        items = emptyList()
        runCatching { listState.scrollToItem(0) }

        try {
            val orderQuery = orders.firstOrNull { it.key == orderKey }?.query.orEmpty()
            val typeQuery = types.firstOrNull { it.key == typeKey }?.query.orEmpty()
            // 硬超时：网络卡住时也要把转圈收掉，给用户一个明确的出口
            val data = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                state.threadPage(site, fid, 1, orderQuery, typeQuery)
            }
            if (data == null) {
                error = "加载超时（20 秒）。可能是站点响应慢，或登录态已失效。\n可以下拉重试，或用右上角网页模式打开。"
            } else {
                items = data.threads
                if (data.types.isNotEmpty()) types = data.types
                end = data.threads.isEmpty()
                if (data.threads.filterNot { it.isTop }.isEmpty()) {
                    val issue = state.client.lastListIssue
                    error = when {
                        !issue.isNullOrBlank() -> issue
                        data.threads.isEmpty() -> "这个版块暂时没有内容，可能会员可见或需要登录"
                        else -> "这个分类下只有置顶帖"
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.forumassist.app.data.AppLog.e("板块", "加载失败 fid=$fid", e)
            error = "加载失败：" + (e.message ?: "未知错误")
        } finally {
            loading = false
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= visible.size - 2 && visible.isNotEmpty() && !loading && !end
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loading = true
            val next = page + 1
            try {
                val orderQuery = orders.firstOrNull { it.key == orderKey }?.query.orEmpty()
                val typeQuery = types.firstOrNull { it.key == typeKey }?.query.orEmpty()
                val more = state.threadPage(site, fid, next, orderQuery, typeQuery).threads
                page = next
                if (more.isEmpty()) {
                    end = true
                } else {
                    items = items + more.filterNot { n -> items.any { it.key == n.key } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 翻页失败就当到底，别在滚动时一直重试
                end = true
            } finally {
                loading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = boardName.ifBlank { "版块" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = site.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第一层：排序（全部 / 最新回复 / 最新发表 / 热门 / 精华）
            FilterChips(
                options = orders,
                selectedKey = orderKey,
                onSelect = { if (it != orderKey) orderKey = it }
            )
            // 第二层：分类（页面没有分类时不显示）
            if (types.isNotEmpty()) {
                FilterChips(
                    options = listOf(BoardFilter("", "全部")) + types,
                    selectedKey = typeKey,
                    onSelect = { if (it != typeKey) typeKey = it }
                )
            }

            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }

                visible.isEmpty() -> {
                    // 登录掉了就去登录，别让用户对着「重新加载」反复点
                    val needLogin = error?.contains("登录") == true
                    EmptyState(
                        title = if (needLogin) "需要登录" else if (error != null) "这里没有帖子" else "没有帖子",
                        desc = error ?: "换个排序方式或分类试试",
                        actionLabel = if (needLogin) "去登录" else "重新加载",
                        onAction = { if (needLogin) onLogin() else reloadKey += 1 }
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.key }) { t ->
                        ThreadCard(
                            thread = t,
                            siteName = site.name,
                            siteColor = site.color,
                            newReplies = state.newReplyCount(t),
                            read = state.isRead(t.key),
                            onClick = { onOpenThread(t) }
                        )
                    }
                    if (loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (end) {
                        item {
                            Text(
                                text = "— 没有更多了 —",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        if (state.settings.showComposeButton) {
            ExtendedFloatingActionButton(
                onClick = onCompose,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) },
                text = { Text("发帖", fontWeight = FontWeight.Medium) }
            )
        }
    }
}

/** 一行可横向滚动的筛选 chip（排序 / 分类共用） */
@Composable
private fun FilterChips(
    options: List<BoardFilter>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val selected = selectedKey == key
            Surface(
                modifier = Modifier.clickable { onSelect(key) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
