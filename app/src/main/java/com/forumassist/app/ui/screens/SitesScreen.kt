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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import com.forumassist.app.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.Site
import com.forumassist.app.ui.components.ScreenHeader
import com.forumassist.app.ui.components.SiteAvatar
import com.forumassist.app.ui.components.TagPill
import kotlinx.coroutines.launch

@Composable
fun SitesScreen(
    state: AppState,
    onOpenSite: (Site) -> Unit,
    onOpenWeb: (Site) -> Unit,
    onLogin: (Site) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "论坛",
            subtitle = "${state.sites.size} 个站点 · 支持 Discuz / PHPWind / XenForo",
            trailing = {
                Surface(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MiuixTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Add, null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "添加",
                            color = MiuixTheme.colorScheme.onPrimary,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.sites, key = { it.id }) { site ->
                SiteRow(
                    state = state,
                    site = site,
                    onClick = { onOpenSite(site) },
                    onWeb = { onOpenWeb(site) },
                    onLogin = { onLogin(site) }
                )
            }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdd = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.Add, null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "添加新论坛（粘贴网址即可自动识别）",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSiteDialog(state = state, onDismiss = { showAdd = false })
    }
}

@Composable
private fun SiteRow(
    state: AppState,
    site: Site,
    onClick: () -> Unit,
    onWeb: () -> Unit,
    onLogin: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var addressInput by remember { mutableStateOf(site.addressPage) }

    if (showAddressDialog) {
        SuperDialog(
            show = true,
            title = "地址发布页",
            summary = "填写该站点的地址发布页网址。域名更换时，App 会从这里自动找最新可用地址。",
            onDismissRequest = { showAddressDialog = false }
        ) {
            TextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = "例如 https://sxsy.org/",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "取消",
                    onClick = { showAddressDialog = false },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        state.updateSite(site.copy(addressPage = addressInput.trim()))
                        showAddressDialog = false
                        state.notify(if (addressInput.isBlank()) "已清除地址发布页" else "已保存地址发布页")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SiteAvatar(site.name, site.color, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = site.name,
                        style = MiuixTheme.textStyles.headline1,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    TagPill(site.kind.label, androidx.compose.ui.graphics.Color(site.color))
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = site.host,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1
                    )
                    if (site.loggedIn) {
                        Spacer(Modifier.width(6.dp))
                        TagPill("已登录 ${site.username ?: ""}", MiuixTheme.colorScheme.primary)
                    }
                }
            }
            Switch(
                checked = site.enabled,
                onCheckedChange = { state.toggleSite(site) }
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Outlined.MoreHoriz, null,
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("打开网页版") },
                        leadingIcon = { Icon(Icons.Outlined.Language, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onWeb() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (site.loggedIn) "账号管理" else "登录账号") },
                        leadingIcon = { Icon(Icons.Outlined.Login, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onLogin() }
                    )
                    if (site.addressPage.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("更新最新地址") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; state.refreshAddress(site) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (site.addressPage.isBlank()) "设置地址发布页" else "修改地址发布页") },
                        leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            addressInput = site.addressPage
                            showAddressDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除站点") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; state.deleteSite(site) }
                    )
                }
            }
        }
    }
}

private fun Modifier.scaleSwitch() = this.then(Modifier.size(width = 40.dp, height = 26.dp))
@Composable
private fun AddSiteDialog(state: AppState, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SuperDialog(
        show = true,
        title = "添加论坛",
        summary = "粘贴论坛首页地址，App 会自动识别站点类型并抓取版块。",
        onDismissRequest = onDismiss
    ) {
        TextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = "https://bbs.example.com",
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        TextField(
            value = name,
            onValueChange = { name = it },
            label = "显示名称（可留空）",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = if (busy) "识别中…" else "添加",
                enabled = !busy,
                onClick = {
                    busy = true
                    state.addSite(url, name) { res ->
                        busy = false
                        res.onSuccess { onDismiss() }
                        res.onFailure { error = it.message }
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
