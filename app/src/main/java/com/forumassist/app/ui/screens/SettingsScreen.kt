package com.forumassist.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.AppLog
import com.forumassist.app.data.Settings
import com.forumassist.app.ui.components.SettingRow
import com.forumassist.app.ui.components.SoftCard
import androidx.documentfile.provider.DocumentFile
import com.forumassist.app.ui.theme.AccentPresets

@Composable
fun SettingsScreen(state: AppState, onBack: () -> Unit, onOpenLogs: () -> Unit = {}) {
    val s = state.settings
    var editKeywords by remember { mutableStateOf(false) }
    var editAuthors by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 清完之后要重新算一次占用，所以放 state 里
    var cacheMb by remember { mutableStateOf(state.store.cacheSize() / 1024f / 1024f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 30.dp)
        ) {
            SectionLabel("外观")
            SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("主题模式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf("跟随系统", "浅色", "深色").forEachIndexed { i, label ->
                            val selected = s.themeMode == i
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { state.updateSettings(s.copy(themeMode = i)) },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Palette, null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("主题色", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccentPresets.forEach { (_, color) ->
                            val selected = s.accent == color
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .then(
                                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { state.updateSettings(s.copy(accent = color)) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正文字号", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${(s.fontScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = s.fontScale,
                        onValueChange = { state.updateSettings(s.copy(fontScale = it)) },
                        valueRange = 0.85f..1.4f,
                        steps = 10
                    )
                    Text(
                        text = "正文效果预览：慢慢读，才读得进去。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("内容")
            SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow(
                    title = "演示数据兜底",
                    desc = "所有站点都抓取失败时，展示内置示例内容",
                    trailing = {
                        Switch(
                            checked = s.demoFallback,
                            onCheckedChange = { state.updateSettings(s.copy(demoFallback = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "广告屏蔽",
                    desc = "拦截常见广告/统计请求，并隐藏网页模式里的广告位",
                    icon = Icons.Outlined.CleaningServices,
                    trailing = {
                        Switch(
                            checked = s.adBlock,
                            onCheckedChange = { state.updateSettings(s.copy(adBlock = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "发帖按钮",
                    desc = if (s.showComposeButton) "版块页右下角显示「发帖」按钮" else "已隐藏，只保留阅读",
                    trailing = {
                        Switch(
                            checked = s.showComposeButton,
                            onCheckedChange = { state.updateSettings(s.copy(showComposeButton = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "回帖输入框",
                    desc = if (s.showReplyBar) "正文底部显示「写下你的回复」，可直接回帖"
                    else "已隐藏，只保留阅读",
                    trailing = {
                        Switch(
                            checked = s.showReplyBar,
                            onCheckedChange = { state.updateSettings(s.copy(showReplyBar = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "已读置灰",
                    desc = if (s.readGray) "看过的帖子在列表里变灰" else "所有帖子同样显示",
                    trailing = {
                        Switch(
                            checked = s.readGray,
                            onCheckedChange = { state.updateSettings(s.copy(readGray = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "站内渲染兜底",
                    desc = if (s.webRenderFallback) "抓取失败时用隐藏浏览器再解析一次（慢，但能打开被拦的帖子）"
                    else "已关闭，加载更快；打不开的帖子用网页模式看",
                    trailing = {
                        Switch(
                            checked = s.webRenderFallback,
                            onCheckedChange = { state.updateSettings(s.copy(webRenderFallback = it)) }
                        )
                    }
                )
                SettingRow(
                    title = "屏蔽关键词",
                    desc = if (s.blockedKeywords.isEmpty()) "未设置" else s.blockedKeywords.joinToString("、"),
                    icon = Icons.Outlined.Block,
                    onClick = { editKeywords = true }
                )
                SettingRow(
                    title = "屏蔽作者",
                    desc = if (s.blockedAuthors.isEmpty()) "未设置" else s.blockedAuthors.joinToString("、"),
                    icon = Icons.Outlined.Block,
                    onClick = { editAuthors = true }
                )
            }

            SectionLabel("存储")
            val context = LocalContext.current
            var dirLabel by remember {
                mutableStateOf(
                    state.store.downloadTree()?.let { raw ->
                        runCatching {
                            DocumentFile.fromTreeUri(context, Uri.parse(raw))?.name
                        }.getOrNull()
                    } ?: ""
                )
            }
            val pickDir = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    state.store.saveDownloadTree(uri.toString())
                    dirLabel = runCatching {
                        DocumentFile.fromTreeUri(context, uri)?.name
                    }.getOrNull().orEmpty()
                    state.notify("附件将保存到这个文件夹")
                }
            }
            SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow(
                    title = "清理缓存",
                    desc = "正文、图片、首页列表共占用 ${"%.1f".format(cacheMb)} MB",
                    icon = Icons.Outlined.CleaningServices,
                    onClick = { confirmClear = true }
                )
                SettingRow(
                    title = "附件保存位置",
                    desc = if (dirLabel.isBlank()) "未选择，点一下挑一个文件夹（购买后的附件下载到这里）"
                    else "$dirLabel · 点一下可以更换",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = { pickDir.launch(null) }
                )
                SettingRow(
                    title = "运行日志",
                    desc = "抓取失败、登录失效、解析异常都会记在这里（${AppLog.sizeKb()} KB）",
                    icon = Icons.Outlined.Article,
                    onClick = onOpenLogs
                )
            }

            SectionLabel("关于")
            SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
                SettingRow(title = "版本", trailing = {
                    Text(
                        "${com.forumassist.app.BuildConfig.VERSION_NAME} (${com.forumassist.app.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                })
                SettingRow(title = "开源协议", trailing = {
                    Text(
                        "MIT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                })
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "抓取行为仅用于个人阅读，请遵守各论坛的版规与 robots 协议。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理缓存？") },
            text = {
                Text(
                    "会删掉已保存的正文、图片和首页列表（约 ${"%.1f".format(cacheMb)} MB）。\n" +
                        "收藏、已读记录和登录状态不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.clearCache()
                    cacheMb = state.store.cacheSize() / 1024f / 1024f
                    confirmClear = false
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    if (editKeywords) {
        ListEditorDialog(
            title = "屏蔽关键词",
            hint = "每行一个，标题包含即隐藏",
            initial = s.blockedKeywords,
            onDismiss = { editKeywords = false },
            onSave = {
                state.updateSettings(s.copy(blockedKeywords = it))
                editKeywords = false
                state.loadFeed(reset = true)
            }
        )
    }
    if (editAuthors) {
        ListEditorDialog(
            title = "屏蔽作者",
            hint = "每行一个作者名",
            initial = s.blockedAuthors,
            onDismiss = { editAuthors = false },
            onSave = {
                state.updateSettings(s.copy(blockedAuthors = it))
                editAuthors = false
                state.loadFeed(reset = true)
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun ListEditorDialog(
    title: String,
    hint: String,
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                placeholder = { Text(hint) },
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.split("\n", "，", ",").map { it.trim() }.filter { it.isNotBlank() })
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
