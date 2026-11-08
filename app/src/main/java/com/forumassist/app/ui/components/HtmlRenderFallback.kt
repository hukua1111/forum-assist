package com.forumassist.app.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.forumassist.app.data.AdBlock
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * 隐藏的 WebView 渲染器：加载页面 → 等 JS 执行完 → 取回渲染后的 DOM。
 * 用于绕过 WAF / JS 渲染 / 动态签名等「直接 HTTP 抓不到内容」的站点。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlRenderFallback(
    url: String,
    adBlock: Boolean,
    onHtml: (String) -> Unit,
    onFailed: () -> Unit
) {
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false // 只要 DOM，不需要图片
                settings.blockNetworkImage = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (adBlock && AdBlock.shouldBlock(u)) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        view?.evaluateJavascript(
                            "(function(){try{return document.documentElement.outerHTML||''}catch(e){return ''}})()"
                        ) { raw ->
                            val html = decodeJsString(raw)
                            if (html != null && html.length > 800) onHtml(html) else onFailed()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) onFailed()
                    }
                }
                runCatching { com.forumassist.app.App.state.http.ensureWebSession(url) }
                loadUrl(url)
            }
        }
    )
}

/** evaluateJavascript 返回的是 JSON 字符串字面量，需要反转义 */
private fun decodeJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}
