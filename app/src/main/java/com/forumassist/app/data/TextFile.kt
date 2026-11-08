package com.forumassist.app.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 纯文本附件（.txt / .log / .md …）的解码与切块。
 *
 * 站点里的「正文」经常不在帖子里，而在一个 100 KB 左右的 txt 附件中
 * （帖子只写简介 + 附件）。这类附件直接用内置阅读器打开，比让人跳网页里看
 * 一坨等宽文本体验好得多。
 */
object TextFile {

    private val TEXT_EXT = setOf("txt", "log", "md", "text", "csv", "ini")

    /** 文件名 / 下载链接看起来是不是纯文本 */
    fun isText(name: String, url: String = ""): Boolean {
        val n = name.substringBefore('?').trim()
        val ext = n.substringAfterLast('.', "").lowercase()
        if (ext in TEXT_EXT) return true
        if (ext.isNotEmpty()) return false
        // 文件名被截断（卡片上只显示半截）时，退回看下载链接
        return url.contains(".txt", true)
    }

    fun displayName(name: String): String =
        name.substringBefore('?').replace(Regex("^\\[[^\\]]*]"), "").trim().ifBlank { name }

    /**
     * 解码：先看 BOM，再按 UTF-8 严格解，失败则按 GB18030（兼容 GBK/GB2312）。
     * 中文站点的 txt 大量是 GBK，直接 UTF-8 读会整篇变问号。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

            else -> strictUtf8(bytes) ?: runCatching { String(bytes, Charset.forName("GB18030")) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * 按行切成若干块，每块不超过 [maxChars]。
     * Compose 的单个 Text 放几万字会明显卡顿（测量是 O(n)），切块后 LazyColumn 才能按需组合。
     */
    fun chunk(text: String, maxChars: Int = 1600): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(text.length / maxChars + 2)
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            // 单行特别长（某些 txt 整本不分段）时再按长度硬切
            if (line.length > maxChars) {
                var start = 0
                while (start < line.length) {
                    val end = minOf(start + maxChars, line.length)
                    out.add(line.substring(start, end))
                    start = end
                }
                continue
            }
            if (sb.length + line.length + 1 > maxChars && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 附件的粗略可读大小，用于卡片上提示「约 N 字」 */
    fun sizeHint(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
