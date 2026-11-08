package com.forumassist.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf(title) }
    var loading by remember { mutableStateOf(true) }
    val isDemo = url.contains("demo.forum")

    if (isDemo) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text("演示论坛", style = MaterialTheme.typography.titleLarge)
            }
            com.forumassist.app.ui.components.EmptyState(
                title = "演示站点没有对应网页",
                desc = "「演示论坛」的内容全部在本地生成，用于离线体验阅读、收藏与发帖流程。\n添加一个真实的 Discuz 论坛后，就可以用网页模式浏览了。",
                icon = Icons.Outlined.Language,
                actionLabel = "返回",
                onAction = onBack
            )
        }
        return
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }) {
                Icon(Icons.Outlined.OpenInNew, "浏览器打开", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.userAgentString = settings.userAgentString +
                            " ForumAssist/1.0"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 原生登录态 → WebView：打开网页模式前先带上已有 Cookie
                        runCatching { com.forumassist.app.App.state.http.exportCookiesToWebView(url) }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) loading = false
                            }

                            override fun onReceivedTitle(view: WebView?, t: String?) {
                                if (!t.isNullOrBlank()) pageTitle = t
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, vUrl: String?) {
                                loading = false
                                // WebView 登录态 → 原生：页面加载完成后把 Cookie 回灌给 OkHttp。
                                // 若检测到新出现的登录 Cookie（*_auth / *_saltkey），刷新原生数据。
                                val target = vUrl ?: url
                                runCatching {
                                    val http = com.forumassist.app.App.state.http
                                    val hadLogin = http.hasLoginCookieForUrl(target)
                                    val ok = http.importWebCookies(target)
                                    if (ok && !hadLogin) {
                                        com.forumassist.app.App.state.loadFeed(reset = true)
                                    }
                                }
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                update = { }
            )
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            }
        }
    }
}
