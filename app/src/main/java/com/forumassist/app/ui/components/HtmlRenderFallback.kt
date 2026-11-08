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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.forumassist.app.data.AdBlock
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 隐藏的 WebView 渲染器：加载页面 → 等 JS 执行完 → 取回渲染后的 DOM。
 * 用于绕过 WAF / JS 渲染 / 动态签名等「直接 HTTP 抓不到内容」的站点。
 *
 * [fastExtract] = true 时不再死等 onPageFinished（它会等到所有图片/统计脚本结束），
 * 而是在首屏可见后就开始尝试取 DOM，通常能提前 1~3 秒拿到正文。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlRenderFallback(
    url: String,
    adBlock: Boolean,
    fastExtract: Boolean = false,
    onHtml: (String) -> Unit,
    onFailed: () -> Unit
) {
    val done = remember { AtomicBoolean(false) }

    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false // 只要 DOM，不需要图片
                settings.blockNetworkImage = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                var tries = 0
                val evaluating = AtomicBoolean(false)

                /** 从导航开始就轮询正文节点；readyState=loading 也允许提取。 */
                fun attemptFastExtract() {
                    if (!fastExtract || done.get() || tries >= 40) return
                    if (!evaluating.compareAndSet(false, true)) return
                    tries++
                    evaluateJavascript(DOM_IF_READY_JS) { raw ->
                        evaluating.set(false)
                        val html = decodeJsString(raw)
                        if (!done.get() && html != null && html.length > 800) {
                            if (done.compareAndSet(false, true)) {
                                // 只要 DOM，正文已到手就停掉剩余图片/统计脚本，释放网络和 CPU。
                                stopLoading()
                                onHtml(html)
                            }
                        } else if (!done.get() && tries < 40) {
                            postDelayed({ attemptFastExtract() }, 120)
                        }
                    }
                }

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

                    /** 首屏可见时立刻补试一次；常规轮询在 loadUrl 后就已经开始。 */
                    override fun onPageCommitVisible(view: WebView?, u: String?) {
                        super.onPageCommitVisible(view, u)
                        if (fastExtract) view?.post { attemptFastExtract() }
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (done.get()) return
                        view?.evaluateJavascript(DOM_JS) { raw ->
                            if (done.get()) return@evaluateJavascript
                            val html = decodeJsString(raw)
                            if (html != null && html.length > 800) {
                                if (done.compareAndSet(false, true)) {
                                    view.stopLoading()
                                    onHtml(html)
                                }
                            } else if (done.compareAndSet(false, true)) {
                                onFailed()
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && done.compareAndSet(false, true)) {
                            onFailed()
                        }
                    }
                }
                runCatching { com.forumassist.app.App.state.http.ensureWebSession(url) }
                loadUrl(url)
                // 不等 onPageCommitVisible：导航后 60ms 就开始找正文，找到即返回。
                if (fastExtract) postDelayed({ attemptFastExtract() }, 60)
            }
        }
    )
}

private const val DOM_JS =
    "(function(){try{return document.documentElement.outerHTML||''}catch(e){return ''}})()"

/** 正文节点已出现就返回；不等 readyState，也不等首屏可见或整页加载完成。 */
private const val DOM_IF_READY_JS =
    "(function(){try{" +
        "var b=document.querySelector('td.t_f, div.t_f, div.message, div.postmessage, [id^=postmessage_], .article-content, .post-content, .thread-content, .post_message');" +
        "if(!b||b.textContent.trim().length<2)return '';" +
        "return document.documentElement.outerHTML||''}catch(e){return ''}})()"

/** evaluateJavascript 返回的是 JSON 字符串字面量，需要反转义 */
private fun decodeJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}
