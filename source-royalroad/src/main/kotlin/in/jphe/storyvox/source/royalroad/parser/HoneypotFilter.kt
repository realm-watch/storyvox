package `in`.jphe.storyvox.source.royalroad.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Royal Road injects an anti-piracy span into chapter HTML, hidden via an
 * inline <style> rule (display: none; speak: never;). The class name is
 * randomized per-page. Without filtering, an audiobook reader will narrate
 * "Love this novel? Read it on Royal Road..." mid-paragraph.
 *
 * Strategy:
 *   1. Parse every inline <style> block; collect class selectors that set
 *      display:none or speak:never.
 *   2. Strip every element bearing one of those classes from the document.
 *   3. Defense-in-depth: also strip elements with inline style="...display:none..."
 *      or visibility: hidden.
 */
internal object HoneypotFilter {

    private val HIDDEN_CLASS_RE = Regex(
        """\.([A-Za-z_][\w-]*)\s*\{[^}]*?(?:display\s*:\s*none|speak\s*:\s*never)[^}]*?\}""",
        RegexOption.IGNORE_CASE,
    )

    private val INLINE_HIDDEN_RE = Regex(
        """(?:display\s*:\s*none|visibility\s*:\s*hidden|speak\s*:\s*never)""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(doc: Document) {
        val hiddenClasses = collectHiddenClassNames(doc)
        if (hiddenClasses.isNotEmpty()) {
            val sel = hiddenClasses.joinToString(",") { ".$it" }
            doc.select(sel).remove()
        }
        doc.select("[style]").forEach { el ->
            val style = el.attr("style")
            if (INLINE_HIDDEN_RE.containsMatchIn(style)) el.remove()
        }
    }

    /** Same filter applied to a chapter content fragment. */
    fun stripFragment(fragment: Element, doc: Document) {
        strip(doc)
        fragment.select("[style]").forEach { el ->
            if (INLINE_HIDDEN_RE.containsMatchIn(el.attr("style"))) el.remove()
        }
    }

    private fun collectHiddenClassNames(doc: Document): List<String> {
        val out = mutableListOf<String>()
        doc.select("style").forEach { styleEl ->
            val css = styleEl.data()
            HIDDEN_CLASS_RE.findAll(css).forEach { match ->
                out += match.groupValues[1]
            }
        }
        return out
    }
}
