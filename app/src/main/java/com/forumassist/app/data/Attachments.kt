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
    private val NOISE_LINK_TEXT = setOf(
        "记录", "购买", "购买并下载", "立即购买", "下载", "免费下载", "点击下载",
        "查看", "收藏", "评分", "支付", "附件", "点击文件名下载附件"
    )

    /** 长词优先，避免先把「购买并下载」截成「购买」 */
    private val ACTION_WORDS = NOISE_LINK_TEXT.sortedByDescending { it.length }

    private fun isActionText(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        return t in NOISE_LINK_TEXT ||
            ACTION_WORDS.any { t == it || t == "【$it】" || t == "[$it]" }
    }

    /** 从「文件名.rar (1.2 MB) 购买并下载」这种混排文本里抠出文件名 */
    private fun cleanName(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        // 只去掉「(1.2 MB, 下载次数: 3)」这类附加信息，书名号里带括号的真相名不动
        s = s.replace(
            Regex("\\s*[（(][^）)]*(MB|KB|GB|下载|售价|金币|金钱|次)[^）)]*[）)]\\s*$"),
            ""
        ).trim()
        for (w in ACTION_WORDS) {
            s = s.replace(Regex(Regex.escape(w) + "\\s*$"), "").trim()
            s = s.replace(Regex("^\\s*" + Regex.escape(w)), "").trim()
        }
        return s
    }

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
            // 同一个附件既有文件链接又有「购买并下载」按钮时，只保留先出现的那条
            if (href.contains("attachpay") &&
                out.any { it is Block.File && it.name == title }
            ) return
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
            // 一个附件单元里常有两条链接：文件名（真实下载）+「购买并下载」（attachpay 按钮）。
            // 以前后者会被当成另一个附件单独占一行，现在合并成一条，用 meta 标记「需购买」。
            val anchors = unit.select("a[href]").filter { a ->
                val h = a.attr("href")
                h.contains("attachpay") || h.contains("mod=attachment") || h.contains("action=attachcredit")
            }
            val pay = anchors.firstOrNull { it.attr("href").contains("attachpay") }
            val direct = anchors.firstOrNull {
                !it.attr("href").contains("attachpay") && !isActionText(it.text())
            } ?: anchors.firstOrNull { !it.attr("href").contains("attachpay") }
            val name = direct?.text()?.let { cleanName(it) }
                ?.takeIf { it.isNotBlank() && !isActionText(it) }
                // 文件名节点优先于 dt：dt 里通常只有一张类型图标，按文档顺序取会拿到空串
                ?: listOf("p.attnm a", "p.attnm", ".attnm", "dt", ".attname", ".filename")
                    .firstNotNullOfOrNull { sel ->
                        unit.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
                    }
                    ?.let { cleanName(it).ifBlank { null } }
                ?: direct?.attr("title")?.trim()?.ifBlank { null }
                ?: pay?.attr("title")?.trim()?.ifBlank { null }
                ?: cleanName(anchors.firstOrNull()?.text().orEmpty()).ifBlank { null }
            val hrefEl = direct ?: pay ?: anchors.firstOrNull()
            val href = hrefEl?.let { it.absUrl("href").ifBlank { it.attr("href") } }.orEmpty()
            if (name != null && href.isNotBlank()) {
                var meta = metaOf(unit, clean = true)
                val paid = meta.contains("售价") || meta.contains("金币") ||
                    meta.contains("金钱") || meta.contains("购买")
                if (pay != null && !paid) {
                    meta = listOf(meta, "需购买").filter { it.isNotBlank() }.joinToString(" · ")
                }
                addFile(name, href, meta)
            }
        }

        // 2) 正文里内联的附件链接（定制模板常这么放，例如直接 <a href="...attachpay">）
        for (a in scope.select(
            "a[href*='attachpay'], a[href*='mod=attachment'], a[href*='action=attachcredit']"
        )) {
            val name = cleanName(a.text().ifBlank { a.attr("title") })
            if (name.isBlank() || isActionText(a.text())) continue
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
        var list = dl.select("p").map {
            // 「售价: 1 金钱 [ 记录 ] [ 购买 ]」里的方括号是操作链接，展示时去掉
            it.text().replace(Regex("\\[[^\\]]*\\]"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotBlank() }
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
