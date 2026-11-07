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
        val cookies = jar.loadForRequest(url)
        return cookies.any { it.name.endsWith("_auth") || it.name.endsWith("_saltkey") }
    }
}
