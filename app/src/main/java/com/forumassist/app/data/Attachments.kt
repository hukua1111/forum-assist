package com.forumassist.app.data

import org.jsoup.nodes.Element

/**
 * Discuz 附件解析。
 *
 * 两种模式：
 *  - [legacy]：原有逻辑，只认 `div.pattl > dl.tattl`，其他站点继续使用，行为不变；
 *  - [rich]：尚香书苑这类定制模板用，覆盖 dl.tattl、ignore_js_op、图片附件、
 *    付费附件（attachpay）、正文内联附件链接，以及「无权限查看」的提示。
 */
object Attachments {

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif")

    /** 附件块里的「记录 / 购买 / 下载」等按钮文案，不是附件名 */
    private val NOISE_LINK_TEXT = setOf("记录", "购买", "下载", "查看", "收藏", "评分", "支付")

    // ------------------------------------------------------------ 原逻辑

    fun legacy(scope: Element, baseUrl: String): List<Block> {
        val out = mutableListOf<Block>()
        val seen = HashSet<String>()
        for (dl in scope.select("div.pattl dl.tattl, dl.tattl")) {
            val link = dl.selectFirst("p.attnm a[href], .attnm a[href]")
            if (link == null) {
                // 图片型附件没有文件名链接，直接取图片地址
                val img = dl.selectFirst("img") ?: continue
                val url = absolute(firstSrc(img), baseUrl)
                if (url.isNotBlank() && seen.add(url)) out.add(Block.Image(url))
                continue
            }
            val name = link.text().trim()
            if (name.isBlank()) continue
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank() || !href.startsWith("http")) continue
            if (!seen.add(name + "|" + href)) continue
            out.add(Block.File(name = name, url = href, meta = metaOf(dl)))
        }
        return out
    }

    // ------------------------------------------------------------ 增强逻辑

    fun rich(scope: Element, baseUrl: String): List<Block> {
        val out = mutableListOf<Block>()
        val seen = HashSet<String>()

        fun addImage(raw: String) {
            val url = absolute(raw, baseUrl)
            if (url.isBlank()) return
            if (url.contains("static/image") || url.contains("none.gif")) return
            if (seen.add(url)) out.add(Block.Image(url))
        }

        fun addFile(name: String, rawHref: String, meta: String) {
            val href = absolute(rawHref, baseUrl)
            if (href.isBlank()) return
            // 「记录 / 购买」这类附属操作入口不是附件本身
            if (href.contains("viewattachpayments")) return
            if (!seen.add(href)) return
            val title = name.ifBlank { href.substringAfterLast('/') }
            out.add(Block.File(name = title, url = href, meta = meta))
        }

        // 1) 标准附件单元：一个单元里可能同时有缩略图和文件名链接
        val units = LinkedHashSet<Element>()
        scope.select("dl.tattl").forEach { units.add(it) }
        scope.select("ignore_js_op").forEach { op ->
            // 已经按 dl.tattl 处理过的不要再算一次
            if (op.select("dl.tattl").isEmpty()) units.add(op)
        }
        scope.select("p.attach, div.attachlist p, div.attach_nopermission, div.attach_tips")
            .forEach { units.add(it) }

        for (unit in units) {
            for (img in unit.select("img[zoomfile], img[file], img[data-src], img[src*='data/attachment']")) {
                addImage(firstSrc(img))
            }
            val link = unit.selectFirst(
                "p.attnm a[href], .attnm a[href], a[href*='attachpay'], " +
                    "a[href*='mod=attachment'], a[href*='action=attachcredit']"
            )
            if (link != null) {
                val name = link.text().trim().ifBlank { link.attr("title").trim() }
                addFile(name, link.absUrl("href").ifBlank { link.attr("href") }, metaOf(unit, clean = true))
            }
        }

        // 2) 正文里内联的附件链接（定制模板常这么放，例如直接 <a href="...attachpay">）
        for (a in scope.select(
            "a[href*='attachpay'], a[href*='mod=attachment'], a[href*='action=attachcredit']"
        )) {
            val name = a.text().trim().ifBlank { a.attr("title").trim() }
            if (name.isBlank() || name in NOISE_LINK_TEXT) continue
            addFile(name, a.absUrl("href").ifBlank { a.attr("href") }, "")
        }

        // 3) 图片附件（缩略图 + 原图地址在 zoomfile/file 上）
        for (img in scope.select("img[zoomfile], img[file]")) {
            val src = firstSrc(img)
            if (!IMAGE_EXT.any { src.lowercase().contains(it) }) continue
            addImage(src)
        }

        // 4) 权限不足时给出明确提示，而不是静默什么都不显示
        if (out.isEmpty()) {
            val hint = scope.selectFirst("div.attach_nopermission, div.attach_tips, .locked")
                ?.text()?.trim()
            if (!hint.isNullOrBlank()) {
                out.add(Block.Text("⚠ 附件未显示：" + hint.take(160)))
            }
        }
        return out
    }

    // ------------------------------------------------------------ 工具

    /** Discuz 懒加载图：真实地址在 zoomfile / file 上，src 往往是 none.gif 占位 */
    private fun firstSrc(img: Element): String {
        for (key in listOf("zoomfile", "file", "data-src", "data-original", "src")) {
            val v = img.attr(key).trim()
            if (v.isNotBlank() && !v.contains("none.gif")) return v
        }
        return ""
    }

    private fun absolute(raw: String, baseUrl: String): String {
        val v = raw.trim()
        if (v.isBlank()) return ""
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        if (v.startsWith("//")) return "https:$v"
        if (baseUrl.isBlank()) return ""
        return runCatching { java.net.URL(java.net.URL(baseUrl), v).toString() }.getOrDefault("")
    }

    private fun metaOf(dl: Element, clean: Boolean = false): String {
        var list = dl.select("p").map { it.text().trim() }.filter { it.isNotBlank() }
        if (clean) {
            // 富解析模式下把「点击文件名下载附件」「1 小时前 上传」这类提示去掉，只留体积/售价/下载次数
            list = list.filterNot { it.contains("点击文件名") || it.endsWith("上传") }
        }
        return list
            .filter {
                it.contains("MB") || it.contains("KB") || it.contains("GB") ||
                    it.contains("下载") || it.contains("售价") || it.contains("金币") ||
                    it.contains("金钱")
            }
            .distinct()
            .take(if (clean) 3 else Int.MAX_VALUE)
            .joinToString(" · ")
    }
}
