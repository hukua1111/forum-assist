package com.forumassist.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.forumassist.app.AppState
import com.forumassist.app.data.AttachmentSaver
import com.forumassist.app.data.Block
import com.forumassist.app.data.Post
import com.forumassist.app.data.Site
import com.forumassist.app.data.SiteProfile
import com.forumassist.app.data.TextFile
import com.forumassist.app.data.ThreadDetail
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.data.profile
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.HtmlRenderFallback
import com.forumassist.app.ui.components.SiteAvatar
import com.forumassist.app.ui.components.TagPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ThreadDetailScreen(
    state: AppState,
    site: Site,
    thread: ThreadSummary,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onOpenWeb: () -> Unit,
    onLogin: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var detail by remember { mutableStateOf<ThreadDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedPage by remember { mutableStateOf(1) }
    // WebView 渲染兜底（绕过 WAF / 动态签名）
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    var rendering by remember { mutableStateOf(false) }
    // 抓取超过 5 秒还没结果：给一个「先用网页模式」的出口，别让人干等
    var slowHint by remember { mutableStateOf(false) }
    // 附件下载：先弹确认框，避免点一下就默默开始下大文件
    var pendingFile by remember { mutableStateOf<Block.File?>(null) }
    var downloading by remember { mutableStateOf(false) }
    // txt 附件在线阅读：站点上很多「正文」其实全在附件里
    var reader by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reading by remember { mutableStateOf(false) }
    // 抓取失败后手动重试：站点风控常常是瞬时的，隔几秒再要一次就能成功
    var reloadToken by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val url = "${site.baseUrl}/forum.php?mod=viewthread&tid=${thread.tid}"
    val profile = site.profile
    // 防盗链校验的是帖子页，不是站点根目录
    val referer = thread.url.ifBlank { url }

    // 网页模式登录过的账号，进正文后就该是「已登录」
    LaunchedEffect(site.id) { state.syncLoginState(site) }

    val scope = rememberCoroutineScope()

    // 在线阅读：带 Cookie 把附件直接拉进内存，不落盘
    val openReader: (Block.File) -> Unit = { f ->
        if (!reading) {
            reading = true
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    AttachmentSaver.readText(state.client.httpClient, f.url, referer)
                }
                reading = false
                val text = res.text
                if (text != null) {
                    pendingFile = null
                    reader = TextFile.displayName(f.name) to text
                } else {
                    Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(thread.key, reloadToken) {
        loading = detail == null
        error = null
        loadedPage = 1

        // 1) 上次解析过的正文先秒开，不用对着转圈等网络
        if (profile.cacheDetail && detail == null) {
            state.cachedDetail(site, thread.tid)?.let {
                detail = it
                loading = false
            }
        }

        // 2) 与站内渲染并行。站点对详情页的拦截很随机，直接抓的成功率只有两成上下，
        //    以前要等整套超时 + 重试跑完（5~8 秒）才拉起 WebView，等于白等。
        //    现在 500 ms 还没内容就让渲染器先起跑，谁先拿到正文用谁。
        if (!site.isDemo && state.settings.webRenderFallback && detail == null) {
            launch {
                // 已知这个站点的详情页只能靠 JS 渲染，就别再让 HTTP 抢跑了
                // 列表点击时 HTTP 已预取，导航本身已经给了它几百毫秒先手；
                // 增强站点只再错开 100ms，不再让低速率的 HTTP 探测白等半秒。
                val renderDelay = if (profile.parallelDetail) 100L else 500L
                if (!state.isRenderFirst(site.id)) kotlinx.coroutines.delay(renderDelay)
                if (detail == null && fallbackUrl == null) {
                    com.forumassist.app.data.AppLog.d(
                        "正文",
                        "tid=${thread.tid} 站内渲染起跑（${if (state.isRenderFirst(site.id)) "直连" else "${renderDelay}ms"}）"
                    )
                    fallbackUrl = thread.url.ifBlank { url }
                    rendering = true
                }
            }
        }

        // 3) 网络请求。点列表时可能已经在跑了，这里 await 同一份任务，不会重复发。
        //    已知只能靠 JS 渲染的站点，让 WebView 先跑一秒半——HTTP 那点命中率不值得
        //    和它抢同一条连接。
        if (detail == null && state.isRenderFirst(site.id)) kotlinx.coroutines.delay(1_500L)
        val fresh = if (detail != null) null else runCatching {
            state.detailDeferred(site, thread.tid, 1, thread.url).await()
        }.getOrNull()
        if (fresh != null) {
            // 渲染器可能先返回了，别用更晚到的 HTTP 结果覆盖它
            if (detail == null) detail = fresh
            error = null
            loading = false
            rendering = false
            fallbackUrl = null
            state.markRead(thread)
        } else if (detail == null) {
            // 渲染器还在跑就先别报错：错误页会把「正在加载」盖掉，让人以为已经失败了
            if (rendering) {
                // HTTP 已经明确失败、渲染器还在跑 → 这站的详情基本只能走 JS，记下来
                state.markRenderFirst(site.id)
                return@LaunchedEffect
            }
            val diag = state.lastDetailError.orEmpty()
            error = if (site.isDemo) "演示帖不存在"
            else "正文加载失败。该站点可能需要登录，或限制了外部访问。" +
                if (diag.isBlank()) "" else "\n\n诊断：\n$diag"
            loading = false
            if (!site.isDemo && state.settings.webRenderFallback && fallbackUrl == null) {
                // 自动用隐藏 WebView 真实渲染一次（可过 WAF / JS 签名）
                com.forumassist.app.data.AppLog.w(
                    "正文",
                    "tid=${thread.tid} 直接抓取失败，转站内渲染 " +
                        (state.lastDetailError.orEmpty().lineSequence().firstOrNull().orEmpty())
                )
                fallbackUrl = thread.url.ifBlank { url }
                rendering = true
            }
        }
    }

    val fav = state.isFav(thread.key)
    val offline = state.isOffline(thread.key)

    LaunchedEffect(loading, rendering, detail) {
        slowHint = false
        if ((loading || rendering) && detail == null) {
            kotlinx.coroutines.delay(5_000L)
            slowHint = true
        }
    }

    // 长章节模式：段落是在 IO 线程预先切好的，这里直接用，组合阶段零排版开销
    val splitReady = remember(detail, profile.splitLongPost) {
        val d = detail
        profile.splitLongPost && d != null && d.sections.size == d.posts.size
    }

    val onDownload: (Block.File) -> Unit = { f -> pendingFile = f }

    CompositionLocalProvider(LocalAttachmentDownload provides onDownload) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = site.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { state.toggleFav(thread) }) {
                Icon(
                    imageVector = if (fav) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                    contentDescription = "收藏",
                    tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制链接") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(url))
                            state.notify("链接已复制")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("复制正文") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            val text = detail?.posts?.joinToString("\n\n") { p ->
                                p.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                            }.orEmpty()
                            if (text.isBlank()) {
                                state.notify("正文还没加载出来")
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                state.notify("正文已复制")
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (offline) "移除离线缓存" else "离线保存") },
                        leadingIcon = { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; state.download(thread) }
                    )
                    DropdownMenuItem(
                        text = { Text("在网页中打开") },
                        leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onOpenWeb() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${thread.title}\n$url")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享帖子"))
                        }
                    )
                }
            }
        }

        // 隐藏的渲染器：加载真实页面并取回 DOM
        fallbackUrl?.let { fu ->
            HtmlRenderFallback(
                url = fu,
                adBlock = state.settings.adBlock,
                // 尚香书苑加载慢多半卡在等图片/统计脚本，首屏可见就先取 DOM
                fastExtract = profile.parallelDetail,
                onHtml = { html ->
                    // 解析整页 HTML（Jsoup）很吃 CPU，绝不能放在 WebView 回调的主线程上
                    scope.launch {
                        // HTTP 可能已经先拿到了正文，这份渲染结果就没必要再解析一遍
                        if (detail != null) {
                            rendering = false
                            fallbackUrl = null
                            return@launch
                        }
                        val parsed = state.detailFromHtml(site, thread.tid, html)
                        if (parsed != null && parsed.posts.isNotEmpty()) {
                            com.forumassist.app.data.AppLog.d(
                                "正文",
                                "tid=${thread.tid} 站内渲染成功 ${parsed.posts.size} 楼 ${html.length}B"
                            )
                            detail = parsed
                            error = null
                            state.markRead(thread)
                        } else {
                            com.forumassist.app.data.AppLog.w(
                                "正文",
                                "tid=${thread.tid} 站内渲染拿到 ${html.length}B 但解析不出正文"
                            )
                            error = (error ?: "正文加载失败") + "\n\n已尝试站内渲染，仍未能解析出正文。"
                        }
                        rendering = false
                        fallbackUrl = null
                    }
                },
                onFailed = {
                    com.forumassist.app.data.AppLog.w("正文", "tid=${thread.tid} 站内渲染失败")
                    error = (error ?: "正文加载失败") + "\n\n站内渲染失败，可改用网页模式阅读。"
                    rendering = false
                    fallbackUrl = null
                }
            )
        }

        when {
            // 正文还没到：先把列表里已有的标题/作者画出来，比一整屏空白转圈体感快得多
            (loading || rendering) && detail == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.headlineSmall,
                    lineHeight = 30.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${thread.author.ifBlank { "匿名" }}  ·  ${thread.replies} 回复  ·  ${thread.views} 浏览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(22.dp))
                // 骨架屏：先把「作者行 + 正文段落」的版式占住。
                // 一整屏空白转圈会让人觉得卡死了，灰条则像「马上就有」，
                // 正文到达时也不会整体跳动。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                    )
                    Spacer(Modifier.width(10.dp))
                    SkeletonBar(width = 86.dp)
                }
                Spacer(Modifier.height(16.dp))
                SkeletonBar(fraction = 1f)
                Spacer(Modifier.height(9.dp))
                SkeletonBar(fraction = 0.94f)
                Spacer(Modifier.height(9.dp))
                SkeletonBar(fraction = 0.98f)
                Spacer(Modifier.height(9.dp))
                SkeletonBar(fraction = 0.72f)
                Spacer(Modifier.height(20.dp))
                SkeletonBar(fraction = 1f)
                Spacer(Modifier.height(9.dp))
                SkeletonBar(fraction = 0.9f)
                Spacer(Modifier.height(9.dp))
                SkeletonBar(fraction = 0.55f)

                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (rendering) "正在用站内渲染读取页面…" else "正在读取帖子…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (slowHint) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onOpenWeb,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("网络有点慢，先用网页模式", style = MaterialTheme.typography.labelSmall) }
                }
                Spacer(Modifier.height(26.dp))
            }

            detail == null -> EmptyState(
                title = "正文没能加载出来",
                desc = error ?: "该站点可能限制外部访问，建议用网页模式阅读。",
                actionLabel = "重试",
                onAction = { reloadToken += 1 },
                secondaryLabel = "网页模式打开",
                onSecondary = onOpenWeb
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val d = detail!!
                item(key = "head") {
                    DetailHead(d, thread, site, offline, showAttCount = profile.richAttachment)
                }

                // 正文只有一两句、内容全在 txt 附件里（小说站常见）：直接给阅读入口，
                // 不用让人先下载再找文件管理器打开。
                val flatFiles = d.posts.flatMap { p -> p.blocks.filterIsInstance<Block.File>() }
                // 优先挑免费的 txt：付费的点了只会弹「先购买」，没意义
                val txtFile = flatFiles.firstOrNull { TextFile.isText(it.name, it.url) && !it.needsPay }
                    ?: flatFiles.firstOrNull { TextFile.isText(it.name, it.url) }
                val bodyChars = d.posts.sumOf { p ->
                    p.blocks.filterIsInstance<Block.Text>().sumOf { it.text.length }
                }
                if (txtFile != null && bodyChars < 200) {
                    val file = txtFile
                    item(key = "txt-hint") {
                        TxtAttachmentHint(
                            file = file,
                            loading = reading,
                            onRead = { openReader(file) }
                        )
                    }
                }

                if (splitReady) {
                    val sections = d.sections
                    d.posts.forEachIndexed { index, post ->
                        val blocks = sections.getOrElse(index) { post.blocks }
                        item(key = "ph-${post.pid}-${post.floor}") {
                            PostHeadRow(post, site, Color(site.color))
                        }
                        itemsIndexed(
                            items = blocks,
                            key = { i, _ -> "${post.pid}-$i" },
                            // 同类型的块共用一套布局节点，长文滚动时复用更彻底
                            contentType = { _, block -> block::class.java.simpleName }
                        ) { _, block ->
                            // 不再逐块套 SelectionContainer：长文几百个块时，
                            // 每个容器都要往 SelectionRegistrar 注册，测量和滚动明显变慢。
                            // 需要整篇复制用右上角菜单的「复制正文」。
                            BlockContent(block, site, profile, referer, onOpenUrl)
                        }
                        if (index != d.posts.lastIndex) {
                            item(key = "pd-${post.pid}-${post.floor}") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                } else {
                    items(d.posts, key = { it.pid + it.floor }) { post ->
                        PostCard(
                            post = post, site = site, accent = Color(site.color),
                            referer = referer, onOpenUrl = onOpenUrl
                        )
                    }
                }

                if (d.totalPages > loadedPage) {
                    item(key = "more") {
                        TextButton(
                            onClick = {
                                loadedPage += 1
                                // 简化处理：提示开网页看后续楼层
                                state.notify("更多楼层请使用网页模式查看")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("还有 ${d.totalPages - loadedPage} 页回复") }
                    }
                }
            }
        }

        val liveSite = state.site(site.id) ?: site
        if (state.settings.showReplyBar) {
            ReplyBar(
                loggedIn = liveSite.loggedIn || liveSite.isDemo,
                onReply = { if (liveSite.loggedIn || liveSite.isDemo) onReply() else onLogin() }
            )
        }
    }
    }

    pendingFile?.let { f ->
        val pay = f.needsPay
        // 真正落盘：带 Cookie 直接抓文件，结果用 Toast 反馈
        val doDownload: () -> Unit = {
            val target = f
            downloading = true
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    AttachmentSaver.save(
                        context = context,
                        http = state.client.httpClient,
                        url = target.url,
                        fileName = target.name,
                        referer = referer,
                        treeUri = state.store.downloadTree()
                    )
                }
                downloading = false
                pendingFile = null
                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
            }
        }
        AlertDialog(
            onDismissRequest = { if (!downloading) pendingFile = null },
            title = { Text(f.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    when {
                        downloading -> "正在下载，请稍候…"
                        pay -> "这个附件需要先购买" +
                            (if (f.meta.isNotBlank()) "（${f.meta}）" else "") + "。\n" +
                            "点「购买并下载」在站内打开购买页，买完点页面里的下载链接会直接存到这里；\n" +
                            "也可以在那边买完返回，再点一次附件直接下载。"
                        else -> "保存到「设置 → 附件下载」里选的文件夹。\n" +
                            "如果站点提示需要购买，先点「网页打开」买完再回来下载。"
                    }
                )
            },
            confirmButton = {
                if (pay) {
                    TextButton(
                        enabled = !downloading,
                        onClick = {
                            val target = f
                            pendingFile = null
                            onOpenUrl(target.url)
                        }
                    ) { Text("购买并下载") }
                } else {
                    TextButton(enabled = !downloading, onClick = doDownload) {
                        Text(if (downloading) "下载中…" else "下载")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 小说站的正文经常整个放在 txt 附件里，先给一个「不用落盘直接看」的入口
                    if (TextFile.isText(f.name, f.url)) {
                        TextButton(
                            enabled = !downloading && !reading,
                            onClick = { openReader(f) }
                        ) { Text(if (reading) "载入中…" else "在线阅读") }
                    }
                    TextButton(onClick = {
                        if (downloading) return@TextButton
                        if (pay) doDownload() else {
                            pendingFile = null
                            onOpenUrl(f.url)
                        }
                    }) { Text(if (pay) "直接下载" else "网页打开") }
                }
            }
        )
    }

    reader?.let { (title, content) ->
        TextReaderDialog(
            title = title,
            content = content,
            onCopy = {
                clipboard.setText(AnnotatedString(content))
                state.notify("全文已复制")
            },
            onClose = { reader = null }
        )
    }
}

/** 附件下载回调：BlockContent 埋在很深的列表里，用 CompositionLocal 传比层层加参数省事 */
private val LocalAttachmentDownload = staticCompositionLocalOf<(Block.File) -> Unit> { {} }

/** 「正文很短 + 有 txt 附件」时的提示卡：一键在线阅读，不用先存到文件夹再去找 */
@Composable
private fun TxtAttachmentHint(file: Block.File, loading: Boolean, onRead: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.MenuBook, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本帖正文在附件里",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = TextFile.displayName(file.name) + if (file.needsPay) "（需先购买）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = !loading, onClick = onRead) {
                Text(if (loading) "载入中…" else "在线阅读")
            }
        }
    }
}

/**
 * txt 附件全屏阅读器。
 * 按 1600 字切块后交给 LazyColumn：十万字的整本也不会在测量阶段卡主线程。
 */
@Composable
private fun TextReaderDialog(
    title: String,
    content: String,
    onCopy: () -> Unit,
    onClose: () -> Unit
) {
    val chunks = remember(content) { TextFile.chunk(content) }
    val listState = rememberLazyListState()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${content.length} 字 · 共 ${chunks.size} 段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Outlined.ContentCopy, "复制全文", Modifier.size(18.dp))
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 48.dp)
                ) {
                    itemsIndexed(chunks, key = { i, _ -> i }) { _, chunk ->
                        Text(
                            text = chunk,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 28.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** 付费附件：meta 里带售价/购买标记（解析时补的「需购买」也算） */
private val Block.File.needsPay: Boolean
    get() = meta.contains("需购买") || meta.contains("售价") ||
        meta.contains("金币") || meta.contains("金钱") || meta.contains("购买")

/** 骨架屏灰条：呼吸式明暗，避免看起来像静止的占位块 */
@Composable
private fun SkeletonBar(width: Dp? = null, fraction: Float = 1f, height: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Box(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction))
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun DetailHead(
    detail: ThreadDetail,
    thread: ThreadSummary,
    site: Site,
    offline: Boolean,
    showAttCount: Boolean = false
) {
    Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thread.boardName.isNotBlank()) {
                TagPill(thread.boardName, Color(site.color))
                Spacer(Modifier.width(6.dp))
            }
            if (thread.isDigest) TagPill("精华", Color(0xFFFF922B))
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = detail.summary.title.ifBlank { thread.title },
                style = MaterialTheme.typography.headlineSmall,
                lineHeight = 30.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${detail.posts.size} 层楼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "  ·  ${thread.replies} 回复  ·  ${thread.views} 浏览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showAttCount) {
                val att = detail.posts.sumOf { p -> p.blocks.count { it is Block.File } }
                if (att > 0) {
                    Text(
                        text = "  ·  $att 个附件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (offline) TagPill("已离线", MaterialTheme.colorScheme.primary)
        }
    }
}

/** 长正文模式下每层楼的作者信息（不再套整块卡片，阅读更连贯） */
@Composable
private fun PostHeadRow(post: Post, site: Site, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (post.avatar.startsWith("http")) {
            AsyncImage(
                model = post.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            SiteAvatar(post.author, site.color, size = 30.dp, radius = 15.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = post.author,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (post.isOp) {
            Spacer(Modifier.width(6.dp))
            TagPill("楼主", accent)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = listOf(post.time, if (post.floor > 0) "${post.floor}楼" else "")
                .filter { it.isNotBlank() }
                .joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PostCard(
    post: Post,
    site: Site,
    accent: Color,
    referer: String,
    onOpenUrl: (String) -> Unit
) {
    val profile = site.profile
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (post.avatar.startsWith("http")) {
                    AsyncImage(
                        model = post.avatar,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    SiteAvatar(post.author, site.color, size = 36.dp, radius = 18.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.author,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (post.isOp) {
                            Spacer(Modifier.width(6.dp))
                            TagPill("楼主", accent)
                        }
                    }
                    Text(
                        text = post.time.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (post.floor > 0) "${post.floor}楼" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(10.dp))

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (block in post.blocks) {
                        BlockContent(block, site, profile, referer = referer, onOpenUrl = onOpenUrl)
                    }
                }
            }
        }
    }
}

/** 正文块渲染，两种排版共用 */
@Composable
private fun BlockContent(
    block: Block,
    site: Site,
    profile: SiteProfile,
    referer: String,
    onOpenUrl: (String) -> Unit
) {
    val ctx = LocalContext.current
    when (block) {
        is Block.Text -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 25.sp
        )

        is Block.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .heightIn(min = 24.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }

        is Block.File -> {
            // CompositionLocal 必须在 @Composable 上下文里读，不能塞进 clickable 的 lambda
            val download = LocalAttachmentDownload.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable {
                        // 默认走「下载」：带 Cookie 直接抓文件；需要购买的会明确提示，
                        // 用户可以在弹窗里改用网页模式去买。
                        download(block)
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            Icon(
                Icons.Outlined.Download, "附件",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (block.meta.isNotBlank()) {
                    Text(
                        text = block.meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 付费附件：把「购买并下载」贴在附件卡片上，不再作为一个单独的附件出现
            if (block.needsPay) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = "购买并下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            }
        }

        is Block.Image -> PostImage(
            url = block.url,
            referer = if (profile.imageReferer) referer else ""
        )
    }
}

/**
 * 正文图片：失败时给一个可点重试的占位块，而不是留一块莫名的空白。
 * referer：防盗链站点需要带帖子页 Referer。
 */
@Composable
private fun PostImage(url: String, referer: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableStateOf(0) }
    // 有些站点的防盗链反过来：校验 Referer 必须是本站，带错反被打回占位图。
    // 所以带 Referer 失败后自动去掉 Referer 再试一次，两次都失败才显示重试块。
    var noReferer by remember(url) { mutableStateOf(false) }

    if (failed) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable { failed = false; noReferer = false; attempt += 1 }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "图片加载失败，点击重试",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val sendReferer = referer.isNotBlank() && !noReferer
    val model = remember(url, referer, attempt, noReferer) {
        ImageRequest.Builder(ctx)
            // 重试时换一个缓存 key，绕开上一次的失败结果
            .data(if (attempt == 0) url else "$url#retry$attempt")
            .apply { if (sendReferer) addHeader("Referer", referer) }
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.FillWidth,
        onError = {
            if (sendReferer) noReferer = true else failed = true
        }
    )
}

@Composable
private fun ReplyBar(loggedIn: Boolean, onReply: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onReply),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (loggedIn) "写下你的回复…" else "登录后参与讨论",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onReply,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Send, "回复",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}
