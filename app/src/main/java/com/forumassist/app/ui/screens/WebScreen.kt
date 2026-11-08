package com.forumassist.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.material.icons.outlined.CleaningServices
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
import com.forumassist.app.data.AdBlock
import java.io.ByteArrayInputStream

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
                // 清除本站网页 Cookie 后重新加载：用于登录状态错乱（如验证码一直报错）
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                if (!host.isNullOrBlank()) {
                    runCatching { com.forumassist.app.App.state.http.clearWebViewCookiesFor(host) }
                    webView?.clearCache(true)
                    webView?.reload()
                }
            }) {
                Icon(
                    Icons.Outlined.CleaningServices,
                    "清除本站登录状态",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
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
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.databaseEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        // 注意：不要在这里把原生 Cookie 注入 WebView。
                        // Discuz 的验证码 hash / formhash 与 session cookie 绑定，
                        // 注入同名 Cookie（domain/path 不同）会让站点读到旧会话，
                        // 表现为「验证码输入正确却报验证码错误」。
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) loading = false
                            }

                            override fun onReceivedTitle(view: WebView?, t: String?) {
                                if (!t.isNullOrBlank()) pageTitle = t
                            }
                        }
                        val adBlockOn = com.forumassist.app.App.state.settings.adBlock
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val u = request?.url?.toString().orEmpty()
                                if (adBlockOn && AdBlock.shouldBlock(u)) {
                                    // 直接返回空响应，等价于广告屏蔽插件的网络层拦截
                                    return WebResourceResponse(
                                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

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
                                // 隐藏页面里的广告位（与网络层拦截配合）
                                if (adBlockOn) {
                                    runCatching { view?.evaluateJavascript(AdBlock.COSMETIC_JS, null) }
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
