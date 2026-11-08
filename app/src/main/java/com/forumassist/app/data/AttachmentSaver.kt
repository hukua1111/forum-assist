package com.forumassist.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 附件保存。
 *
 * 用 SAF（用户自己选的文件夹）而不是写死 Download 目录：
 *  - 不需要任何存储权限，Android 10+ / 10 以下都一样；
 *  - 用户可以在设置里把目录指到 SD 卡、网盘同步目录等任意位置；
 *  - 卸载应用也不会把文件带走。
 */
object AttachmentSaver {

    data class Result(val ok: Boolean, val message: String, val uri: Uri? = null)

    /** Discuz 在未登录 / 未购买 / 权限不足时返回 HTML 提示页，这里把它翻译成人话 */
    private fun explain(html: String): String {
        val text = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            text.contains("购买") || text.contains("售价") -> "这个附件还没购买，先用网页模式购买后再下载"
            text.contains("登录") || text.contains("游客") -> "登录态已失效，重新登录后再下载"
            text.contains("权限") -> "当前账号没有下载权限"
            text.contains("不存在") || text.contains("删除") -> "附件已被删除或链接失效"
            else -> "站点返回的是一个网页而不是文件，可能需要先购买或用网页模式打开"
        }
    }

    /** 清掉文件名里的非法字符和 Discuz 附加的「(1.2 MB, 下载次数: 3)」 */
    private fun cleanName(raw: String, contentType: String): String {
        var name = raw
            .replace(Regex("\\s*[（(][^）)]*[）)]\\s*$"), "")
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .trimEnd('.')
        if (name.isBlank()) name = "attachment"
        if (name.substringAfterLast('.', "").length in 1..5) return name
        val ext = when {
            contentType.contains("epub") -> "epub"
            contentType.contains("pdf") -> "pdf"
            contentType.contains("zip") -> "zip"
            contentType.contains("rar") -> "rar"
            contentType.contains("msword") -> "doc"
            contentType.contains("officedocument.wordprocessingml") -> "docx"
            contentType.contains("text/plain") -> "txt"
            else -> ""
        }
        return if (ext.isBlank()) name else "$name.$ext"
    }

    /**
     * 保存到用户选定的目录。整个过程是阻塞 IO，调用方要放在 Dispatchers.IO。
     */
    fun save(
        context: Context,
        http: Http,
        url: String,
        fileName: String,
        referer: String?,
        treeUri: String?
    ): Result {
        if (treeUri.isNullOrBlank()) {
            return Result(false, "还没选保存文件夹，去「设置 → 附件下载」选一个")
        }
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
        if (tree == null || !tree.canWrite()) {
            return Result(false, "保存文件夹不可用，去「设置 → 附件下载」重新选一个")
        }

        val file = http.getFile(url, referer)
            ?: return Result(false, "下载失败：网络不通或站点拒绝访问")

        val (contentType, bytes, _) = file
        if (contentType.contains("text/html", true)) {
            val html = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            AppLog.w("下载", "返回 HTML 而不是文件：${explain(html)}")
            return Result(false, explain(html))
        }
        if (bytes.size < 512) {
            return Result(false, "文件异常（只有 ${bytes.size} 字节），可能站点返回了错误页")
        }

        val mime = contentType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        var name = cleanName(fileName, mime)
        // 重名就加序号，别把已有文件覆盖掉
        var target = tree.findFile(name)
        var index = 1
        while (target != null && index < 99) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            name = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            target = tree.findFile(name)
            index++
        }

        val doc = runCatching { tree.createFile(mime, name) }.getOrNull()
            ?: return Result(false, "无法在所选文件夹里创建文件")
        val ok = runCatching {
            context.contentResolver.openOutputStream(doc.uri)?.use { out -> out.write(bytes) }
        }.isSuccess
        if (!ok) {
            runCatching { doc.delete() }
            return Result(false, "写入失败，换个文件夹试试")
        }
        AppLog.d("下载", "已保存 $name ${bytes.size}B → ${tree.name}")
        return Result(true, "已保存到「${tree.name ?: "所选文件夹"}」/$name", doc.uri)
    }
}
