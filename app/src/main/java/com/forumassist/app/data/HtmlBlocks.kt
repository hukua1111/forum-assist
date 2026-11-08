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

    fun parse(html: String, baseUrl: String = ""): List<Block> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Block>()
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString()
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
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
                    val tag = node.tagName().lowercase()
                    when {
                        tag == "img" -> {
                            // Discuz 的懒加载图：src 是占位/缩略，真实地址在 zoomfile / file
                            var raw = ""
                            for (key in listOf("zoomfile", "file", "data-src", "data-original", "src")) {
                                val v = node.attr(key).trim()
                                if (v.isNotBlank() && !v.contains("none.gif")) { raw = v; break }
                            }
                            val src = absolute(raw)
                            val w = node.attr("width").toIntOrNull() ?: 0
                            val skip = src.isEmpty() ||
                                src.contains("none.gif") ||
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

        doc.body().childNodes().forEach { walk(it) }
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
