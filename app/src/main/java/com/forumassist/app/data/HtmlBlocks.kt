package com.forumassist.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 把论坛 HTML 正文切成「文本块 / 图片块 / 引用块」，方便 Compose 渲染。
 */
object HtmlBlocks {

    private val blockTags = setOf(
        "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table"
    )
    private val horizontalWhitespace = Regex("[ \\t\\x0B\\f\\r]+")
    private val lineWhitespace = Regex(" *\\n *")
    private val excessLines = Regex("\\n{3,}")
    /**
     * 真实图片地址可能出现的位置（Discuz 附件 + 各类懒加载插件）。
     * 顺序即优先级：先看原图属性，最后才看 src —— 懒加载模板的 src 往往只是占位图。
     */
    private val imageSrcAttributes = arrayOf(
        "zoomfile", "file", "data-original", "data-src", "data-lazy-src", "data-lazy",
        "data-echo", "data-actualsrc", "_src", "lazyload", "original", "src"
    )

    /** 占位图特征：这些地址就算能下载也只是「加载中 / 无图 / 防盗链」的假图 */
    private val placeholderHints = arrayOf(
        "none.gif", "loading", "lazyload", "spacer", "blank.gif", "grey.gif",
        "placeholder", "static/image/common", "nopic", "noavatar",
        // 站点自己的 UI 素材（插件/模板目录）不可能是帖子正文图
        "source/plugin", "/template/", "uc_server/images"
    )

    private fun isPlaceholder(url: String): Boolean =
        placeholderHints.any { url.contains(it, ignoreCase = true) }

    fun parse(html: String, baseUrl: String = ""): List<Block> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return emptyList()
        return parse(doc.body(), baseUrl)
    }

    /**
     * 直接遍历详情页已经解析好的正文节点。
     * 避免 body.html() → Jsoup.parseBodyFragment() 这道重复的序列化和二次解析。
     */
    fun parse(body: Element, baseUrl: String = ""): List<Block> {
        val out = mutableListOf<Block>()
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString()
                .replace(horizontalWhitespace, " ")
                .replace(lineWhitespace, "\n")
                .replace(excessLines, "\n\n")
                .trim()
            if (text.isNotEmpty()) out.add(Block.Text(text))
            buffer.setLength(0)
        }

        // 把相对地址补成绝对地址（Discuz 图片多为 data/attachment/... 相对路径）
        fun absolute(raw: String): String {
            val v = raw.trim()
            if (v.isBlank()) return ""
            if (v.startsWith("http://") || v.startsWith("https://")) return v
            if (v.startsWith("//")) return "https:$v"
            if (baseUrl.isBlank()) return ""
            return runCatching { java.net.URL(java.net.URL(baseUrl), v).toString() }.getOrDefault("")
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> buffer.append(node.text())
                is Element -> {
                    val tag = node.normalName()
                    when {
                        tag == "img" -> {
                            // Discuz 的懒加载图：src 是占位/缩略，真实地址在 zoomfile / file /
                            // data-original 等属性里；占位图要跳过，否则正文里会显示一个
                            // 「绿色的加载中/刷新」假图
                            var src = ""
                            for (key in imageSrcAttributes) {
                                val v = node.attr(key).trim()
                                if (v.isBlank() || isPlaceholder(v)) continue
                                val abs = absolute(v)
                                if (abs.isNotBlank()) { src = abs; break }
                            }
                            // 附件图常把原图挂在外层 <a href>，img 只有缩略/占位
                            if (src.isEmpty()) {
                                val href = node.closest("a[href]")?.attr("href").orEmpty().trim()
                                if (href.isNotEmpty() && !isPlaceholder(href)) {
                                    val abs = absolute(href)
                                    val looksImage = abs.contains("attachment", ignoreCase = true) ||
                                        Regex("\\.(jpe?g|png|gif|webp|bmp)(\\?|$)", RegexOption.IGNORE_CASE)
                                            .containsMatchIn(abs)
                                    if (looksImage) src = abs
                                }
                            }
                            val w = node.attr("width").toIntOrNull() ?: 0
                            val skip = src.isEmpty() ||
                                src.contains("/smiley/") ||
                                src.contains("static/image/") ||
                                (w in 1..60)
                            if (!skip) {
                                flush()
                                out.add(Block.Image(src))
                            }
                        }
                        tag == "script" || tag == "style" || tag == "iframe" -> Unit
                        tag == "blockquote" -> {
                            flush()
                            val q = node.text().trim()
                            if (q.isNotEmpty()) out.add(Block.Quote(q))
                        }
                        tag == "br" -> buffer.append("\n")
                        else -> {
                            if (tag in blockTags) buffer.append("\n")
                            node.childNodes().forEach { walk(it) }
                            if (tag in blockTags) buffer.append("\n")
                        }
                    }
                }
            }
        }

        body.childNodes().forEach { walk(it) }
        flush()
        return out
    }

    fun plain(html: String, limit: Int = 160): String {
        val text = runCatching { Jsoup.parse(html).text() }.getOrNull() ?: return ""
        return if (text.length > limit) text.take(limit) + "…" else text
    }
}

/**
 * 站点类型探测：抓取首页 HTML，根据特征字符串判断论坛程序。
 */
object SiteDetector {

    fun detectType(html: String): SiteKind {
        val h = html.lowercase()
        return when {
            h.contains("discuz") || h.contains("forum.php") || h.contains("comiis") -> SiteKind.DISCUZ
            h.contains("phpwind") || h.contains("wind.js") -> SiteKind.PHPWIND
            h.contains("xenforo") -> SiteKind.XENO
            h.contains("vbulletin") -> SiteKind.VBULLETIN
            else -> SiteKind.DISCUZ
        }
    }

    fun normalizeUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        url = url.trimEnd('/')
        // 用户常常直接粘贴 forum.php 之类的地址
        url = url.replace(Regex("/forum\\.php.*$"), "")
            .replace(Regex("/index\\.php.*$"), "")
            .replace(Regex("/bbs/forum\\.php.*$"), "/bbs")
        return url.trimEnd('/')
    }
}
