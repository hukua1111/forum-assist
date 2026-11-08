package com.forumassist.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.ChevronRight
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import com.forumassist.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.ui.components.SettingRow
import com.forumassist.app.ui.components.SiteAvatar
import com.forumassist.app.ui.components.SoftCard

@Composable
fun MeScreen(
    state: AppState,
    onSettings: () -> Unit,
    onSites: () -> Unit,
    onFavorites: () -> Unit
) {
    var showAbout by remember { mutableStateOf(false) }
    val loggedSites = state.sites.filter { it.loggedIn }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "坛",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (loggedSites.isEmpty()) "本地模式" else loggedSites.first().username ?: "已登录",
                        style = MiuixTheme.textStyles.title3
                    )
                    Text(
                        text = if (loggedSites.isEmpty()) "登录后可以发帖、回复、同步收藏"
                        else "已登录 ${loggedSites.size} 个站点",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MiuixTheme.colorScheme.surfaceContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, MiuixTheme.colorScheme.dividerLine),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(vertical = 16.dp)) {
                    StatItem("收藏", state.favorites.size.toString(), Modifier.weight(1f)) { onFavorites() }
                    StatItem("离线", state.offline.size.toString(), Modifier.weight(1f)) { onFavorites() }
                    StatItem("读帖", state.history.size.toString(), Modifier.weight(1f)) { onFavorites() }
                    StatItem("站点", state.sites.size.toString(), Modifier.weight(1f)) { onSites() }
                }
            }
        }

        SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
            SettingRow(
                title = "站点管理",
                desc = "添加、登录、删除论坛",
                icon = Icons.Outlined.Forum,
                onClick = onSites
            )
            SettingRow(
                title = "设置",
                desc = "外观、字号、屏蔽词",
                icon = Icons.Outlined.Settings,
                onClick = onSettings
            )
            SettingRow(
                title = "内容过滤",
                desc = "屏蔽关键词与作者，信息流更干净",
                icon = Icons.Outlined.Shield,
                onClick = onSettings
            )
            SettingRow(
                title = "清理缓存",
                desc = "正文、图片与离线帖子",
                icon = Icons.Outlined.CleaningServices,
                onClick = { state.clearCache() }
            )
        }

        Spacer(Modifier.height(12.dp))

        SoftCard(modifier = Modifier.padding(horizontal = 14.dp)) {
            SettingRow(
                title = "关于论坛助手",
                desc = "版本 ${com.forumassist.app.BuildConfig.VERSION_NAME} · 开源仿写",
                icon = Icons.Outlined.Info,
                onClick = { showAbout = true }
            )
            if (loggedSites.isNotEmpty()) {
                SettingRow(
                    title = "退出全部账号",
                    icon = Icons.Outlined.Logout,
                    iconTint = MiuixTheme.colorScheme.error,
                    onClick = { loggedSites.forEach { state.logout(it) } }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "本应用为个人学习项目，与「论坛助手」官方无关。\n内容版权归各论坛及原作者所有。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
        )
    }

    if (showAbout) {
        SuperDialog(
            show = true,
            title = "论坛助手 · Android",
            summary = "一个专注阅读的论坛聚合客户端。\n\n支持 Discuz / PHPWind / XenForo / Vbulletin 站点的浏览、登录、发帖与回复；内置演示站点，离线也能体验完整流程。\n\n抓取失败时可用「网页模式」直接浏览原站，不会卡住使用。",
            onDismissRequest = { showAbout = false }
        ) {
            TextButton(
                text = "知道了",
                onClick = { showAbout = false },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
    }
}
