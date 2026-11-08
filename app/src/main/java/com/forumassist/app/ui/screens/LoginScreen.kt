package com.forumassist.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VerifiedUser
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.forumassist.app.AppState
import com.forumassist.app.data.Site
import com.forumassist.app.ui.components.SettingRow
import com.forumassist.app.ui.components.SoftCard
import com.forumassist.app.ui.components.SiteAvatar

@Composable
fun LoginScreen(state: AppState, site: Site, onBack: () -> Unit) {
    var username by remember { mutableStateOf(site.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var captchaHash by remember { mutableStateOf<String?>(null) }
    var captchaImage by remember { mutableStateOf<ByteArray?>(null) }
    var captchaCode by remember { mutableStateOf("") }
    val current = state.site(site.id) ?: site

    fun refreshCaptcha() {
        state.fetchLoginCaptcha(current) { c ->
            if (c != null) {
                captchaHash = c.hash
                captchaImage = c.image
            }
        }
    }

    // 进入页面先探一次验证码：站点开启时直接展示
    LaunchedEffect(current.id) {
        if (!current.loggedIn) refreshCaptcha()
    }

    val captchaBitmap = remember(captchaImage) {
        captchaImage?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MiuixTheme.colorScheme.onBackground)
            }
            Text("账号", style = MiuixTheme.textStyles.title3)
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteAvatar(current.name, current.color, size = 52.dp, radius = 16.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(current.name, style = MiuixTheme.textStyles.headline1)
                    Text(
                        current.host,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            if (current.loggedIn) {
                SoftCard {
                    SettingRow(
                        title = "已登录",
                        desc = current.username ?: ""
                    )
                    SettingRow(
                        title = "退出登录",
                        onClick = {
                            state.logout(current)
                            onBack()
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "退出后，收藏与离线内容仍保留在本机。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                return@Column
            }

            TextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = "用户名 / 邮箱",
                leadingIcon = { Icon(Icons.Outlined.Person, null, Modifier.size(18.dp)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "密码",
                leadingIcon = { Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (captchaHash != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = captchaCode,
                        onValueChange = { captchaCode = it; error = null },
                        label = "验证码",
                        leadingIcon = { Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    if (captchaBitmap != null) {
                        Image(
                            bitmap = captchaBitmap,
                            contentDescription = "验证码，点击刷新",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(48.dp)
                                .width(110.dp)
                                .clickable { captchaCode = ""; refreshCaptcha() }
                        )
                    } else {
                        TextButton(text = "点击获取", onClick = { refreshCaptcha() })
                    }
                }
                Text(
                    "看不清？点图片换一张",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        error = "请输入账号和密码"
                        return@Button
                    }
                    if (captchaHash != null && captchaCode.isBlank()) {
                        error = "请输入图片验证码"
                        return@Button
                    }
                    busy = true
                    state.login(current, username, password, captchaHash, captchaCode) { res ->
                        busy = false
                        res.onFailure {
                            error = it.message
                            // 验证码错误：清空并换一张
                            if (it.message?.contains("验证码") == true) {
                                captchaCode = ""
                                refreshCaptcha()
                            }
                        }
                        res.onSuccess { onBack() }
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                cornerRadius = 14.dp
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        size = 18.dp,
                        strokeWidth = 2.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.onPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "登录中…" else "登录")
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
            ) {
                Text(
                    "说明：站点开启图片验证码时会自动显示验证码输入框；" +
                        "遇到安全问题（密保提问）请改用网页模式登录。" +
                        "登录状态仅保存在本机，不会上传到任何服务器。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
