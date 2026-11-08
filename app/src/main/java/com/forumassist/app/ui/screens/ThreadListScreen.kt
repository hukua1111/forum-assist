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
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
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

/**
 * 选中项的 query。
 * 页面解析出来的选项 key 本身就是链接原文（如 filter=heat&orderby=heats）；
 * 记忆恢复时选项列表可能还没解析回来，这时直接把 key 当 query 用，不能退化成「全部」。
 */
private fun queryOf(options: List<BoardFilter>, key: String): String {
    if (key.isBlank()) return ""
    val hit = options.firstOrNull { it.key == key }?.query.orEmpty()
    return hit.ifBlank { if (key.contains("=")) key else "" }
}

@Composable
fun ThreadListScreen(
    state: AppState,
    site: Site,
    fid: String,
    boardName: String,
    onBack: () -> Unit,
    onOpenThread: (ThreadSummary) -> Unit,
    onCompose: () -> Unit,
    onLogin: () -> Unit = {},
    onOpenWeb: () -> Unit = {}
) {
    // 上次在这个版块用的筛选：从正文退回来 / 切走再回来都还原，不再重置成「全部」
    val saved = remember(fid, site.id) { state.boardFilter(site.id, fid) }
    // 第一层用 key 记选中项，真正请求用的是它的 query（页面链接原文）
    var orderKey by remember(fid, site.id) { mutableStateOf(saved?.first.orEmpty()) }
    var typeKey by remember(fid, site.id) { mutableStateOf(saved?.second.orEmpty()) }
    // 第一层排序以页面解析结果为准（不同版块入口不同），拿不到才用兜底
    var orders by remember(fid, site.id) { mutableStateOf(DEFAULT_ORDERS) }
    // 换版块时分类列表要重来，否则会带着上一个版块的 typeid 去请求
    var types by remember(fid, site.id) { mutableStateOf<List<BoardFilter>>(emptyList()) }
    var items by remember { mutableStateOf<List<ThreadSummary>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var end by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    // 第一层/第二层选项只取一次：筛选后页面返回的分类栏跟默认页不一样，
    // 每次覆盖会让刚点的 chip 消失（看起来像「点了就没了」）
    var optionsLoaded by remember(fid, site.id) { mutableStateOf(false) }
    var restoreIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val cacheKey = state.boardCacheKey(site.id, fid, orderKey, typeKey)

    // 置顶帖不进列表（版规/公告类，占地方又不常看）
    val visible = remember(items) { items.filterNot { it.isTop } }

    LaunchedEffect(site.id) { state.syncLoginState(site) }

    // 筛选一变就记下来（回到这个版块时还原）
    LaunchedEffect(orderKey, typeKey) {
        state.saveBoardFilter(site.id, fid, orderKey, typeKey)
    }

    LaunchedEffect(site.id, fid, orderKey, typeKey, reloadKey) {
        val cached = state.cachedBoard(cacheKey)
        if (cached != null) {
            // 从正文退回来 / 切回来：先把上次的内容原样铺上，不转圈、不闪
            items = cached.items
            if (cached.orders.isNotEmpty()) {
                orders = cached.orders
                types = cached.types
                optionsLoaded = true
            }
            page = 1
            end = cached.items.isEmpty()
            loading = false
            restoreIndex = cached.scrollIndex
        } else {
            loading = true
            items = emptyList()
        }
        error = null

        val fresh = cached != null &&
            System.currentTimeMillis() - cached.ts <= 10 * 60_000L &&
            reloadKey == 0
        // 10 分钟内且不是手动重试：直接用缓存，一个请求都不发
        if (fresh) {
            if (items.isNotEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(800L) {
                    listState.scrollToItem(restoreIndex.coerceAtLeast(0))
                }
                restoreIndex = -1
            }
            return@LaunchedEffect
        }
        // 有旧内容时不转圈，静默刷新
        if (cached == null) loading = true

        com.forumassist.app.data.AppLog.d(
            "板块",
            "进入 fid=$fid 排序=${orderKey.ifBlank { "全部" }} 分类=${typeKey.ifBlank { "全部" }}"
        )

        try {
            val orderQuery = queryOf(orders, orderKey)
            val typeQuery = queryOf(types, typeKey)
            val data = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                state.threadPage(site, fid, 1, orderQuery, typeQuery)
            }
            if (data == null) {
                error = "加载超时（15 秒）。可能是站点响应慢、被 Cloudflare 拦了，或登录态已失效。\n点「重新加载」再试，或先用网页模式打开。"
            } else {
                items = data.threads
                if (!optionsLoaded) {
                    if (data.orders.isNotEmpty()) orders = data.orders
                    if (data.types.isNotEmpty()) types = data.types
                    optionsLoaded = data.orders.isNotEmpty() || data.types.isNotEmpty()
                }
                end = data.threads.isEmpty()
                if (data.threads.filterNot { it.isTop }.isEmpty()) {
                    val issue = state.client.lastListIssue
                    error = when {
                        !issue.isNullOrBlank() -> issue
                        data.threads.isEmpty() -> "这个版块暂时没有内容，可能会员可见或需要登录"
                        else -> "这个分类下只有置顶帖"
                    }
                }
                if (data.threads.isNotEmpty()) {
                    state.putBoard(
                        cacheKey,
                        AppState.BoardPageCache(
                            items = data.threads,
                            orders = orders,
                            types = types,
                            ts = System.currentTimeMillis(),
                            scrollIndex = restoreIndex.coerceAtLeast(0)
                        )
                    )
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

        // LazyColumn 组合之后才滚：组合前调用 scrollToItem 会永久挂起
        if (items.isNotEmpty()) {
            kotlinx.coroutines.withTimeoutOrNull(800L) {
                listState.scrollToItem(restoreIndex.coerceAtLeast(0))
            }
        }
        restoreIndex = -1
    }

    // 离开页面时记住滚动位置，退回来接着看
    androidx.compose.runtime.DisposableEffect(cacheKey) {
        onDispose { state.updateBoardScroll(cacheKey, listState.firstVisibleItemIndex) }
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
                val orderQuery = queryOf(orders, orderKey)
                val typeQuery = queryOf(types, typeKey)
                val more = state.threadPage(site, fid, next, orderQuery, typeQuery).threads
                page = next
                if (more.isEmpty()) {
                    end = true
                } else {
                    items = items + more.filterNot { n -> items.any { it.key == n.key } }
                    // 翻页结果也进缓存，退回来不会只剩第一页
                    state.putBoard(
                        cacheKey,
                        AppState.BoardPageCache(
                            items = items,
                            orders = orders,
                            types = types,
                            ts = System.currentTimeMillis(),
                            scrollIndex = listState.firstVisibleItemIndex
                        )
                    )
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
                // 手动刷新：清掉这页的缓存重新拉，不受 10 分钟限制
                IconButton(onClick = {
                    state.clearBoardPage(cacheKey)
                    reloadKey += 1
                }) {
                    Icon(
                        Icons.Rounded.Refresh,
                        "刷新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 抓取卡住 / 被拦截时的应急出口：直接用站内浏览器看这一页
                IconButton(onClick = onOpenWeb) {
                    Icon(
                        Icons.Outlined.Language,
                        "用网页模式打开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
