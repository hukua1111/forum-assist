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

                    /** 首屏可见即尝试取 DOM（仅 fastExtract 模式），拿不到就退避重试几次 */
                    override fun onPageCommitVisible(view: WebView?, u: String?) {
                        super.onPageCommitVisible(view, u)
                        if (!fastExtract) return
                        val wv = view ?: return
                        var tries = 0
                        fun attempt(): Unit {
                            if (done.get() || tries >= 8) return
                            tries++
                            wv.evaluateJavascript(DOM_IF_READY_JS) { raw ->
                                val html = decodeJsString(raw)
                                if (!done.get() && html != null && html.length > 800) {
                                    done.set(true)
                                    onHtml(html)
                                } else if (!done.get()) {
                                    wv.postDelayed({ attempt() }, 320)
                                }
                            }
                        }
                        // 正文节点一出现就取，不用等图片/统计脚本跑完
                        wv.postDelayed({ attempt() }, 120)
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (done.get()) return
                        view?.evaluateJavascript(DOM_JS) { raw ->
                            if (done.get()) return@evaluateJavascript
                            val html = decodeJsString(raw)
                            if (html != null && html.length > 800) {
                                done.set(true)
                                onHtml(html)
                            } else {
                                done.set(true)
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
            }
        }
    )
}

private const val DOM_JS =
    "(function(){try{return document.documentElement.outerHTML||''}catch(e){return ''}})()"

/** DOM 已可交互且正文节点已出现就返回，不再死等 readyState=complete（那要等所有图片/脚本） */
private const val DOM_IF_READY_JS =
    "(function(){try{" +
        "if(document.readyState==='loading')return '';" +
        "var b=document.querySelector('td.t_f, div.t_f, div.message, div.postmessage, [id^=postmessage_]');" +
        "if(!b||b.textContent.trim().length<2)return '';" +
        "return document.documentElement.outerHTML||''}catch(e){return ''}})()"

/** evaluateJavascript 返回的是 JSON 字符串字面量，需要反转义 */
private fun decodeJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}
