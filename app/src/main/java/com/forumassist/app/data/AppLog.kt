package com.forumassist.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行日志：环形缓冲（内存）+ 追加写文件（filesDir/app.log）。
 *
 * 目的很单纯——出问题时用户能直接把日志发过来，不用靠猜。
 * 只记请求、解析结果的概要，不记正文内容，也不记 Cookie。
 */
object AppLog {

    private data class QueuedLog(val generation: Long, val line: String)

    private const val MAX_BYTES = 512 * 1024L
    private const val MAX_LINES = 1200

    private val dateFormatLock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val buffer = ArrayDeque<String>()
    private val pending = LinkedBlockingQueue<QueuedLog>(MAX_LINES)
    private val writerStarted = AtomicBoolean(false)
    private var generation = 0L
    private var file: File? = null
    private val lock = Any()
    private val diskLock = Any()
    private val clearLock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.filesDir, "app.log")
            file = f
            startWriter()
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
        val time = synchronized(dateFormatLock) { fmt.format(Date()) }
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
            if (file != null) enqueue(QueuedLog(generation, line))
        }
    }

    private fun enqueue(entry: QueuedLog) {
        if (pending.offer(entry)) return
        pending.poll()
        pending.offer(entry)
    }

    private fun startWriter() {
        if (!writerStarted.compareAndSet(false, true)) return
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                val first = try {
                    pending.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                val batch = ArrayList<QueuedLog>(64)
                batch.add(first)
                pending.drainTo(batch, 63)
                if (batch.size == 1) {
                    try {
                        val next = pending.poll(25, TimeUnit.MILLISECONDS)
                        if (next != null) {
                            batch.add(next)
                            pending.drainTo(batch, 62)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                appendBatch(batch)
            }
        }, "forum-assist-log-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendBatch(entries: List<QueuedLog>) {
        val f = file ?: return
        synchronized(clearLock) {
            val lines = entries.filter { it.generation == generation }.map { it.line }
            if (lines.isEmpty()) return
            synchronized(diskLock) {
                runCatching {
                    if (f.length() > MAX_BYTES) {
                        val old = File(f.parentFile, "app.old.log")
                        if (old.exists()) old.delete()
                        f.copyTo(old, overwrite = true)
                        f.writeText("")
                    }
                    f.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
                }
            }
        }
    }

    /** 内存里的最近日志（页面展示用） */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    /** 文件里的完整日志（发送用） */
    fun readAll(): String {
        val lines = synchronized(lock) { buffer.toList() }
        val text = synchronized(diskLock) {
            val f = file
            if (f != null && f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        }
        return if (text.isBlank()) lines.joinToString("\n") else text
    }

    fun clear() {
        synchronized(clearLock) {
            synchronized(lock) {
                generation++
                pending.clear()
                buffer.clear()
                synchronized(diskLock) {
                    file?.let { runCatching { it.writeText("") } }
                }
                val line = "${synchronized(dateFormatLock) { fmt.format(Date()) }} D/日志: ===== 已清空 ====="
                buffer.addLast(line)
                enqueue(QueuedLog(generation, line))
            }
        }
    }

    fun sizeKb(): Long {
        val f = file ?: return 0
        return runCatching { f.length() / 1024 }.getOrDefault(0)
    }
}
