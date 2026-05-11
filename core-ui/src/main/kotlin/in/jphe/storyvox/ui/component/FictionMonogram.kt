package `in`.jphe.storyvox.ui.component

/**
 * Derives the single-character monogram shown on a fiction's cover when
 * we have no cover image. Resolution order:
 *
 * 1. First letter / digit of the **author** (most identifying)
 * 2. First letter / digit of the **title** (RSS feeds, GitHub repos, and
 *    other backends often carry no author field — title is the next-best
 *    discriminator)
 * 3. The brass star `✦` as a final fallback. Reads as an intentional
 *    Library Nocturne mark instead of the previous `?`, which looked
 *    like a broken-image placeholder (#322).
 *
 * The result is `uppercaseChar()`-folded so locale-driven case mapping
 * doesn't make the same fiction render differently across devices.
 */
fun fictionMonogram(author: String, title: String): String {
    val ch = author.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        ?: title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
    return ch?.toString() ?: "✦"
}
