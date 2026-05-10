package `in`.jphe.storyvox.feature.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pure formatters for the debug overlay/screen. Extracted so previews +
 * tests don't pull in viewmodels. Library Nocturne idiom is "monospace
 * for numerics, full prose for everything else" — these helpers produce
 * the monospace-ready strings.
 */
internal object DebugFormatters {

    /** "12.4 KB", "1.20 MB", "—" for null/zero. */
    fun bytes(b: Long?): String {
        if (b == null || b <= 0L) return "—"
        val kb = b / 1024.0
        if (kb < 1.0) return "$b B"
        val mb = kb / 1024.0
        if (mb < 1.0) return "%.1f KB".format(Locale.US, kb)
        val gb = mb / 1024.0
        if (gb < 1.0) return "%.2f MB".format(Locale.US, mb)
        return "%.2f GB".format(Locale.US, gb)
    }

    /** "240 ms", "1.4 s", "12 min", "—" for null. */
    fun duration(ms: Long?): String {
        if (ms == null) return "—"
        val abs = abs(ms)
        return when {
            abs < 1_000 -> "$abs ms"
            abs < 60_000 -> "%.1f s".format(Locale.US, abs / 1000.0)
            abs < 3_600_000 -> "%d min %d s".format(Locale.US, abs / 60_000, (abs % 60_000) / 1000)
            else -> "%.1f h".format(Locale.US, abs / 3_600_000.0)
        }
    }

    /** Relative time like "just now", "12 s ago", "4 min ago", "1 h ago". */
    fun ago(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = nowMs - timestampMs
        if (diff < 0L) return "now"
        return when {
            diff < 2_000 -> "now"
            diff < 60_000 -> "${diff / 1000} s ago"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} h ago"
            else -> "${diff / 86_400_000} d ago"
        }
    }

    /** "HH:mm:ss" — wall-clock time stamp for log rows. */
    fun clockTime(timestampMs: Long): String =
        try {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
        } catch (_: Throwable) {
            "--:--:--"
        }

    /** Pads / truncates an id to a fixed width so columns line up. */
    fun id(id: String?, width: Int = 16): String {
        val s = id.orEmpty()
        return if (s.length <= width) s.padEnd(width) else "…" + s.takeLast(width - 1)
    }
}
