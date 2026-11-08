package com.forumassist.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 每个「主机」独立的 Cookie 容器，退出 App 后仍然保留登录态。
 */
class PersistentCookieJar(private val store: Store) : CookieJar {

    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = store.cookies().filter { it.host == url.host }.toMutableList()
            for (c in cookies) {
                current.removeAll { it.name == c.name }
                if (c.expiresAt > now) {
                    current.add(CookieRec(url.host, c.name, c.value, c.expiresAt, c.secure))
                }
            }
            val others = store.cookies().filterNot { it.host == url.host }
            store.saveCookies(others + current)
        }
    }

    /**
     * 导入浏览器导出的原始 Cookie 头（"k=v; k2=v2"），用于 WebView 登录态回灌。
     * WebView Cookie 通常不带过期时间，这里默认保留 180 天。
     */
    fun importRaw(url: HttpUrl, header: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val defaultExpire = now + 180L * 24 * 3600 * 1000
            val current = store.cookies().filter { it.host == url.host }.toMutableList()
            for (part in header.split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isBlank()) continue
                current.removeAll { it.name == name }
                current.add(CookieRec(url.host, name, value, defaultExpire, url.isHttps))
            }
            val others = store.cookies().filterNot { it.host == url.host }
            store.saveCookies(others + current)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            return store.cookies()
                .filter { it.host == url.host && it.expires > now }
                .mapNotNull { rec ->
                    runCatching {
                        Cookie.Builder()
                            .name(rec.name)
                            .value(rec.value)
                            .domain(rec.host)
                            .path("/")
                            .expiresAt(rec.expires)
                            .apply { if (rec.secure) secure() }
                            .build()
                    }.getOrNull()
                }
        }
    }
}

class Http(store: Store) {

    private val jar = PersistentCookieJar(store)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    fun get(url: String, referer: String? = null): String? = runCatching {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.getOrNull()

    fun post(url: String, form: Map<String, String>, referer: String? = null): String? = runCatching {
        val body = form.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { resp ->
            resp.body?.string()
        }
    }.getOrNull()

    /**
     * 探测站点：返回 (站点类型, 站点标题)
     */
    fun probe(baseUrl: String): Pair<SiteKind, String>? {
        val html = get("$baseUrl/") ?: return null
        val title = runCatching { org.jsoup.Jsoup.parse(html).title() }.getOrNull().orEmpty()
            .substringBefore("-").substringBefore("_").trim()
        val kind = if (baseUrl.contains("discuz", true)) SiteKind.DISCUZ else SiteDetector.detectType(html)
        return kind to title.ifBlank { baseUrl }
    }

    fun hasLoginCookie(site: Site): Boolean {
        val url = runCatching { site.baseUrl.toHttpUrlOrNull() }.getOrNull() ?: return false
        return hasLoginCookieFor(url)
    }

    fun hasLoginCookieForUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        return hasLoginCookieFor(httpUrl)
    }

    // ------------------------------------------------ Cookie 双向同步（WebView ↔ 原生）

    /**
     * 把系统 WebView CookieManager 里的登录 Cookie 导入到 OkHttp 持久化存储。
     * 用户在「网页模式」里登录后调用，原生抓取即可复用登录态。
     */
    fun importWebCookies(pageUrl: String): Boolean {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return false
        val cm = android.webkit.CookieManager.getInstance()
        val raw = runCatching { cm.getCookie(pageUrl) }.getOrNull() ?: return false
        if (raw.isBlank()) return false
        jar.importRaw(httpUrl, raw)
        return hasLoginCookieFor(httpUrl)
    }

    /**
     * 把原生存储的登录 Cookie 写入系统 WebView，让网页模式也能复用原生登录态。
     */
    fun exportCookiesToWebView(pageUrl: String) {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return
        val cm = android.webkit.CookieManager.getInstance()
        jar.loadForRequest(httpUrl).forEach { c ->
            runCatching {
                val attrs = StringBuilder()
                attrs.append("Path=/; Domain=").append(httpUrl.host)
                if (c.secure) attrs.append("; Secure")
                cm.setCookie(pageUrl, "${c.name}=${c.value}; $attrs")
            }
        }
        cm.flush()
    }

    private fun hasLoginCookieFor(url: HttpUrl): Boolean =
        jar.loadForRequest(url).any { it.name.endsWith("_auth") || it.name.endsWith("_saltkey") }
}
