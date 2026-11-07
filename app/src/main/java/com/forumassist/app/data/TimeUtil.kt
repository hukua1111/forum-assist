package com.forumassist.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object TimeUtil {

    fun parse(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        val now = System.currentTimeMillis()

        Regex("(\\d+)\\s*秒前").find(t)?.let {
            return now - it.groupValues[1].toLong() * 1000
        }
        Regex("(\\d+)\\s*分钟前").find(t)?.let {
            return now - it.groupValues[1].toLong() * 60_000
        }
        Regex("(\\d+)\\s*小时前").find(t)?.let {
            return now - it.groupValues[1].toLong() * 3_600_000
        }
        if (t.contains("刚刚")) return now
        if (t.contains("昨天")) {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            return cal.timeInMillis
        }
        Regex("(\\d+)\\s*天前").find(t)?.let {
            return now - it.groupValues[1].toLong() * 86_400_000
        }

        val full = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{1,2}))?")
            .find(t)
        if (full != null) {
            val g = full.groupValues
            return runCatching {
                Calendar.getInstance().apply {
                    set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                        g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, 0)
                }.timeInMillis
            }.getOrDefault(0L)
        }
        val md = Regex("(\\d{1,2})[-/.](\\d{1,2})\\s+(\\d{1,2}):(\\d{1,2})").find(t)
        if (md != null) {
            val g = md.groupValues
            val cal = Calendar.getInstance().apply {
                set(Calendar.MONTH, g[1].toInt() - 1)
                set(Calendar.DAY_OF_MONTH, g[2].toInt())
                set(Calendar.HOUR_OF_DAY, g[3].toInt())
                set(Calendar.MINUTE, g[4].toInt())
            }
            if (cal.timeInMillis > now) cal.add(Calendar.YEAR, -1)
            return cal.timeInMillis
        }
        return 0L
    }

    fun pretty(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(ts)
        }
    }

    fun prettyOrRaw(raw: String, ts: Long): String =
        if (ts > 0) pretty(ts) else raw
}
