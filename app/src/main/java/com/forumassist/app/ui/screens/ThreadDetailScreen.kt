package com.forumassist.app.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Send
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.forumassist.app.AppState
import com.forumassist.app.data.Block
import com.forumassist.app.data.Post
import com.forumassist.app.data.Site
import com.forumassist.app.data.ThreadSummary
import com.forumassist.app.ui.components.EmptyState
import com.forumassist.app.ui.components.SiteAvatar
import com.forumassist.app.ui.components.TagPill

@Composable
fun ThreadDetailScreen(
    state: AppState,
    site: Site,
    thread: ThreadSummary,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onOpenWeb: () -> Unit,
    onLogin: () -> Unit
) {
    var detail by remember { mutableStateOf<com.forumassist.app.data.ThreadDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedPage by remember { mutableStateOf(1) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val url = "${site.baseUrl}/forum.php?mod=viewthread&tid=${thread.tid}"

    LaunchedEffect(thread.key) {
        loading = true
        detail = state.detail(site, thread.tid)
        loadedPage = 1
        loading = false
        if (detail == null) {
            error = if (site.isDemo) "演示帖不存在" else "正文加载失败。该站点可能需要登录，或限制了外部访问。"
        } else {
            state.markRead(thread)
        }
    }

    val fav = state.isFav(thread.key)
    val offline = state.isOffline(thread.key)

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

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
            }

            detail == null -> EmptyState(
                title = "正文没能加载出来",
                desc = error ?: "该站点可能限制外部访问，建议用网页模式阅读。",
                actionLabel = "网页模式打开",
                onAction = onOpenWeb
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "head") {
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
                                text = detail!!.summary.title.ifBlank { thread.title },
                                style = MaterialTheme.typography.headlineSmall,
                                lineHeight = 30.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${detail!!.posts.size} 层楼",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "  ·  ${thread.replies} 回复  ·  ${thread.views} 浏览",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            if (offline) TagPill("已离线", MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                items(detail!!.posts, key = { it.pid + it.floor }) { post ->
                    PostCard(post = post, site = site, accent = Color(site.color))
                }

                if (detail!!.totalPages > loadedPage) {
                    item(key = "more") {
                        TextButton(
                            onClick = {
                                val next = loadedPage + 1
                                loadedPage = next
                                // 简化处理：提示开网页看后续楼层
                                state.notify("更多楼层请使用网页模式查看")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("还有 ${detail!!.totalPages - loadedPage} 页回复") }
                    }
                }
            }
        }

        ReplyBar(
            loggedIn = site.loggedIn || site.isDemo,
            onReply = { if (site.loggedIn || site.isDemo) onReply() else onLogin() }
        )
    }
}

@Composable
private fun PostCard(post: Post, site: Site, accent: Color) {
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

                            is Block.Image -> AsyncImage(
                                model = block.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }
    }
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
