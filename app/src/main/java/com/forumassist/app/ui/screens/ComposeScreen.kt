package com.forumassist.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Send
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import com.forumassist.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.Site

/**
 * 发帖 / 回复编辑器。
 * tid 为空 = 发新帖；否则为回帖。
 */
@Composable
fun ComposeScreen(
    state: AppState,
    site: Site,
    fid: String,
    tid: String?,
    quotedTitle: String,
    onBack: () -> Unit,
    onLogin: () -> Unit
) {
    var subject by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isReply = !tid.isNullOrBlank()
    // 登录态以 Cookie 实际状态为准：网页模式登录过就直接可发
    LaunchedEffect(site.id) { state.syncLoginState(site) }
    val liveSite = state.site(site.id) ?: site
    val canPost = liveSite.loggedIn || liveSite.isDemo

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MiuixTheme.colorScheme.onBackground)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isReply) "回复帖子" else "发表新帖",
                    style = MiuixTheme.textStyles.headline1
                )
                Text(
                    text = site.name,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
            Button(
                onClick = {
                    if (content.isBlank()) {
                        error = "写点什么再发布吧"
                        return@Button
                    }
                    if (!isReply && subject.isBlank()) {
                        error = "标题不能为空"
                        return@Button
                    }
                    busy = true
                    error = null
                    val done: (Result<Unit>) -> Unit = { res ->
                        busy = false
                        res.onSuccess { onBack() }
                        res.onFailure { error = it.message }
                    }
                    if (isReply) {
                        state.reply(site, tid!!, fid, content, done)
                    } else {
                        state.newThread(site, fid, subject, content, done)
                    }
                },
                enabled = !busy && canPost,
                cornerRadius = 12.dp
            ) {
                Icon(Icons.Rounded.Send, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (busy) "发送中" else "发布")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (!canPost) {
                Surface(
                    color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "登录后才能发帖或回复",
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "去登录",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onLogin)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (isReply && quotedTitle.isNotBlank()) {
                Surface(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "回复：$quotedTitle",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!isReply) {
                TextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "标题",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            TextField(
                value = content,
                onValueChange = { content = it },
                label = "支持论坛原生格式，可粘贴图片链接…",
                useLabelAsPlaceholder = true,
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${content.length} 字",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Spacer(Modifier.weight(1f))
                listOf("😀", "👍", "😂", "🎉", "→").forEach { emoji ->
                    TextButton(text = emoji, onClick = { content += emoji })
                }
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "提示：图片暂不支持本地相册上传，可先上传到图床再粘贴链接。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f)
            )
        }
    }
}
