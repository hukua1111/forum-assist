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

class Http(private val store: Store) {

    private val jar = PersistentCookieJar(store)

    /**
     * 供图片加载（Coil）复用：带上 Cookie 与 Referer，
     * 否则登录可见的附件图/防防盗链图片会 403。
     */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        // 并发上限：以前用默认值（同主机 5 条），图片/正文/列表全挤在一条队列里，
        // 图片一多就会把列表请求饿死，界面看起来就是「一直转圈」。
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 24
                maxRequestsPerHost = 6
            }
        )
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        // 兜底：单次调用最长 35 秒，避免服务端慢速滴水时连接被无限占用
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val referer = req.header("Referer")
                ?: (req.url.scheme + "://" + req.url.host + "/")
            chain.proceed(req.newBuilder().header("Referer", referer).build())
        }
        .build()

    /**
     * 抓取统一使用电脑版 UA：Discuz 手机模板缺首页四格、附件等结构，解析规则也只针对电脑版。
     * 网页模式（WebScreen）由 WebView 使用系统默认的移动 UA，不受这里影响。
     */
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private val desktopUa = ua

    fun get(url: String, referer: String? = null, desktop: Boolean = false): String? {
        val started = System.currentTimeMillis()
        return try {
            val builder = Request.Builder().url(url).get()
                .header("User-Agent", if (desktop) desktopUa else ua)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            referer?.let { builder.header("Referer", it) }
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string()
                val cost = System.currentTimeMillis() - started
                AppLog.d(
                    "HTTP",
                    "GET ${resp.code} ${body?.length ?: 0}B ${cost}ms ${shortUrl(resp.request.url.toString())}"
                )
                if (!resp.isSuccessful) null else body
            }
        } catch (t: Throwable) {
            val cost = System.currentTimeMillis() - started
            AppLog.e("HTTP", "GET 失败 ${cost}ms ${shortUrl(url)}", t)
            null
        }
    }

    /** 日志里只留路径，域名和参数太多会把日志撑爆 */
    private fun shortUrl(url: String): String = runCatching {
        val u = java.net.URL(url)
        (u.path + if (u.query.isNullOrBlank()) "" else "?" + u.query).take(180)
    }.getOrDefault(url.take(180))

    /**
     * 下载附件：返回 (Content-Type, 字节, Content-Disposition)。
     * 拿不到文件时返回 null；Discuz 在未登录/未购买时会返回一个 HTML 提示页，
     * 调用方要按 Content-Type 判断，不能把提示页当附件存下来。
     */
    fun getFile(url: String, referer: String? = null): Triple<String, ByteArray, String>? {
        val started = System.currentTimeMillis()
        return try {
            val builder = Request.Builder().url(url).get()
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
            referer?.let { builder.header("Referer", it) }
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val ct = resp.header("Content-Type").orEmpty()
                val cd = resp.header("Content-Disposition").orEmpty()
                val bytes = resp.body?.bytes()
                AppLog.d(
                    "下载",
                    "GET ${resp.code} ${bytes?.size ?: 0}B ${System.currentTimeMillis() - started}ms " +
                        "${ct.take(40)} ${shortUrl(url)}"
                )
                if (!resp.isSuccessful || bytes == null) null else Triple(ct, bytes, cd)
            }
        } catch (t: Throwable) {
            AppLog.e("下载", "失败 ${shortUrl(url)}", t)
            null
        }
    }

    /** 下载二进制内容（如验证码图片） */
    fun getBytes(url: String, referer: String? = null): ByteArray? = runCatching {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        referer?.let { builder.header("Referer", it) }
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.bytes()
        }
    }.getOrNull()

    fun post(url: String, form: Map<String, String>, referer: String? = null): String? = try {
        val body = form.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
        referer?.let { builder.header("Referer", it) }
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            AppLog.d("HTTP", "POST ${resp.code} ${shortUrl(resp.request.url.toString())}")
            resp.body?.string()
        }
    } catch (t: Throwable) {
        AppLog.e("HTTP", "POST 失败 ${shortUrl(url)}", t)
        null
    }

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
     * 清除该站点的会话 Cookie（*_sid / sid）。
     * 原生登录前调用，避免带上旧会话导致验证码 hash 与表单不匹配。
     */
    fun clearSessionCookies(site: Site) {
        val host = runCatching { site.baseUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: return
        val all = store.cookies()
        val kept = all.filterNot {
            it.host == host && (it.name.endsWith("_sid") || it.name == "sid")
        }
        if (kept.size != all.size) store.saveCookies(kept)
    }

    /** 退出登录：同时清掉 OkHttp 存储与 WebView 里的该站 Cookie */
    fun clearAllCookiesFor(site: Site) {
        val host = runCatching { site.baseUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: return
        store.saveCookies(store.cookies().filterNot { it.host == host })
        clearWebViewCookiesFor(host)
    }

    /**
     * 渲染兜底前确保 WebView 有登录态：仅当 WebView 缺少 auth Cookie 时，
     * 才把原生的 *_auth / *_saltkey 注入（不动 sid，避免会话错乱）。
     */
    fun ensureWebSession(pageUrl: String) {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return
        val cm = android.webkit.CookieManager.getInstance()
        val existing = runCatching { cm.getCookie(pageUrl) }.getOrNull().orEmpty()
        if (existing.contains("_auth")) return
        store.cookies()
            .filter { it.host == httpUrl.host && (it.name.endsWith("_auth") || it.name.endsWith("_saltkey")) }
            .forEach { c ->
                runCatching {
                    cm.setCookie(pageUrl, "${c.name}=${c.value}; Path=/; Domain=${httpUrl.host}")
                }
            }
        cm.flush()
    }

    /** 只清 WebView 里该主机的 Cookie（不动原生登录态） */
    fun clearWebViewCookiesFor(host: String) {
        val cm = android.webkit.CookieManager.getInstance()
        val base = "https://$host/"
        // 先把该主机现存 Cookie 逐个置空，再清一遍通用站点数据
        runCatching { cm.getCookie(base) }.getOrNull().orEmpty().split(";").forEach { part ->
            val name = part.substringBefore("=").trim()
            if (name.isNotBlank()) {
                // host-only 与 domain 两种作用域都尝试置空
                listOf("", "; Domain=$host", "; Domain=.$host").forEach { scope ->
                    runCatching { cm.setCookie(base, "$name=; Max-Age=0; Path=/$scope") }
                }
            }
        }
        runCatching { cm.setCookie(base, "expires=; Max-Age=0; Path=/") }
        cm.flush()
    }

    private fun hasLoginCookieFor(url: HttpUrl): Boolean =
        jar.loadForRequest(url).any { it.name.endsWith("_auth") || it.name.endsWith("_saltkey") }
}
