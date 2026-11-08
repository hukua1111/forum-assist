package com.forumassist.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：环形缓冲（内存）+ 追加写文件（filesDir/app.log）。
 *
 * 目的很单纯——出问题时用户能直接把日志发过来，不用靠猜。
 * 只记请求、解析结果的概要，不记正文内容，也不记 Cookie。
 */
object AppLog {

    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_LINES = 1200

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val buffer = ArrayDeque<String>()
    private var file: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.filesDir, "app.log")
            file = f
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(context.filesDir, "app.old.log")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
            }
            d("日志", "===== 启动 =====")
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)

    fun w(tag: String, msg: String) = write("W", tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val time = fmt.format(Date())
        val line = buildString {
            append(time).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) {
                append('\n').append(t.javaClass.simpleName).append(": ").append(t.message)
                t.stackTrace.take(8).forEach { append("\n    at ").append(it.toString()) }
            }
        }
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            val f = file ?: return
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "app.old.log")
                    if (old.exists()) old.delete()
                    f.copyTo(old, overwrite = true)
                    f.writeText("")
                }
                f.appendText(line + "\n")
            }
        }
    }

    /** 内存里的最近日志（页面展示用） */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    /** 文件里的完整日志（发送用） */
    fun readAll(): String = synchronized(lock) {
        val f = file
        val text = if (f != null && f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        if (text.isBlank()) buffer.joinToString("\n") else text
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            file?.let { runCatching { it.writeText("") } }
            d("日志", "===== 已清空 =====")
        }
    }

    fun sizeKb(): Long {
        val f = file ?: return 0
        return runCatching { f.length() / 1024 }.getOrDefault(0)
    }
}
