package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.NotePosition
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses a Royal Road chapter page (`/fiction/{id}/.../chapter/{chapterId}/...`)
 * into Selene's [ChapterContent] model.
 *
 * Anti-piracy invariant: [HoneypotFilter.strip] runs on the parsed [Document]
 * BEFORE we read body text. Royal Road injects a per-page-randomized class with
 * `display:none; speak:never` containing "Love this novel? Read it on Royal
 * Road..." Without this filter, the audiobook narrates anti-piracy notices.
 *
 * Body extraction strategy:
 *   - HTML body for the reader view is the inner HTML of `div.chapter-inner.chapter-content`,
 *     with `<img>` retained (Reader can render them, the `:` Glide pipeline handles caching).
 *   - Plain body for TTS strips `<img>`, `<hr>`, `<iframe>`, `<script>`, `<style>`,
 *     converts `<br>` to newlines, then joins paragraph text with double-newlines.
 *
 * Author notes (`div.author-note-portlet div.author-note`) appear 0–2 times. By
 * inspection of RR markup, the first occurrence above the chapter body is BEFORE,
 * the one below is AFTER. We keep at most one of each and prefer the first
 * BEFORE / first AFTER if duplicates appear.
 */
internal object ChapterParser {

    fun parse(
        html: String,
        info: ChapterInfo,
    ): ChapterContent {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        HoneypotFilter.strip(doc)

        val bodyEl = doc.selectFirst("div.chapter-inner.chapter-content")
            ?: error("chapter-content block missing")

        val (notesBefore, notesAfter) = extractAuthorNotes(doc, bodyEl)

        // Reader view: keep `<img>`, strip honeypot residue (already done above),
        // remove inline scripts/styles defensively. Output is the inner HTML.
        bodyEl.select("script, style").remove()
        val htmlBody = bodyEl.html()

        // TTS view: clone, strip media, collapse to paragraph text.
        val plainBody = extractPlainText(bodyEl.clone())

        val notesAuthor: String?
        val notesAuthorPosition: NotePosition?
        when {
            notesBefore != null -> {
                notesAuthor = notesBefore.text().trim().ifEmpty { null }
                notesAuthorPosition = if (notesAuthor != null) NotePosition.BEFORE else null
            }
            notesAfter != null -> {
                notesAuthor = notesAfter.text().trim().ifEmpty { null }
                notesAuthorPosition = if (notesAuthor != null) NotePosition.AFTER else null
            }
            else -> {
                notesAuthor = null
                notesAuthorPosition = null
            }
        }

        return ChapterContent(
            info = info,
            htmlBody = htmlBody,
            plainBody = plainBody,
            notesAuthor = notesAuthor,
            notesAuthorPosition = notesAuthorPosition,
        )
    }

    /**
     * Returns (BEFORE-note, AFTER-note). Position is determined by DOM order
     * relative to the chapter body element.
     */
    private fun extractAuthorNotes(
        doc: org.jsoup.nodes.Document,
        bodyEl: Element,
    ): Pair<Element?, Element?> {
        val notes = doc.select("div.author-note-portlet div.author-note")
        if (notes.isEmpty()) return null to null

        var before: Element? = null
        var after: Element? = null

        // Compare DOM position by walking siblings/ancestors. A note "appears
        // before" the body if its portlet's start tag occurs earlier in the
        // document than the body's start tag.
        val bodyOffset = nodeOrdinal(bodyEl)
        for (note in notes) {
            val noteOffset = nodeOrdinal(note)
            if (noteOffset < bodyOffset) {
                if (before == null) before = note
            } else {
                if (after == null) after = note
            }
        }
        return before to after
    }

    /**
     * Cheap DOM-order ordinal: count preceding text+element nodes via depth-first
     * traversal of the owning document. Sufficient for "is A before B in the DOM".
     */
    private fun nodeOrdinal(el: Element): Int {
        var n = 0
        var found = -1
        val root = el.ownerDocument()?.body() ?: return 0
        root.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node === el) found = n
                n++
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })
        return if (found >= 0) found else Int.MAX_VALUE
    }

    /**
     * TTS-friendly plaintext extraction:
     *   - strip <img>, <hr>, <iframe>, <script>, <style>
     *   - convert <br> to a newline character
     *   - join paragraph text with double-newlines (helps SentenceChunker boundary)
     *   - fall back to element text if no <p> children
     */
    private fun extractPlainText(content: Element): String {
        content.select("img, hr, iframe, script, style").remove()
        content.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        val paragraphs = content.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else {
            content.text().trim()
        }
    }
}
