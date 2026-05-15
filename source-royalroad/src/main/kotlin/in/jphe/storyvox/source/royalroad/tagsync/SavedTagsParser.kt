package `in`.jphe.storyvox.source.royalroad.tagsync

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses the user's saved-tag preference set out of the
 * `/fictions/search?globalFilters=true` page (issue #178).
 *
 * When the user is signed in to Royal Road and has the "global
 * filters" toggle on, the rendered search form pre-selects their
 * saved tags as `<button class="btn default search-tag selected"
 * data-tag="action">` rows. We harvest the set by walking every
 * `button.search-tag.selected` and collecting `data-tag`.
 *
 * The page also serves the antiforgery token under the form's
 * hidden `__RequestVerificationToken` input — the writer needs
 * that token to POST back. We surface both in [Parsed].
 *
 * Forgiving by design: a future RR redesign that renames the
 * `selected` class will leave us with an empty set rather than a
 * crash. The 24h sync window picks up the redesign at the next
 * read; in the meantime the local mirror (`pref_followed_tags_v1`)
 * keeps storyvox itself coherent.
 */
internal object SavedTagsParser {

    sealed interface Result {
        data class Ok(val parsed: Parsed) : Result
        /** Endpoint returned the login redirect (302 → /account/login). */
        data object NotAuthenticated : Result
    }

    data class Parsed(
        /** Tag slugs ("action", "litrpg", "high_fantasy") the user
         *  has saved as preferred filters on RR. */
        val savedTags: Set<String>,
        /** Antiforgery token to attach to the write POST. May be null
         *  when the page didn't include the hidden input (the writer
         *  treats absent token as "skip write this round" rather
         *  than failing the whole sync). */
        val csrfToken: String?,
    )

    fun parse(html: String, finalUrl: String): Result {
        if (looksUnauthed(finalUrl)) return Result.NotAuthenticated
        val doc = Jsoup.parse(html)

        // Defensive: if RR shows the login form prominently in-body
        // (instead of redirecting), treat as unauthed so we don't
        // mis-parse "0 saved tags" from a fully signed-out page.
        if (doc.selectFirst("form.form-login-details") != null) {
            return Result.NotAuthenticated
        }

        val saved = doc.select("button.search-tag.selected")
            .mapNotNull { it.attr("data-tag").trim().takeIf { s -> s.isNotEmpty() } }
            .toSet()

        // Fallback selector — some RR layouts emit the selected
        // state as `data-selected="true"` or `aria-pressed="true"`
        // instead of the `selected` class. Union both to keep the
        // parser robust across small markup tweaks.
        val ariaSaved = doc.select("button.search-tag[aria-pressed=true]")
            .mapNotNull { it.attr("data-tag").trim().takeIf { s -> s.isNotEmpty() } }
            .toSet()

        val token = doc.selectFirst("input[name=__RequestVerificationToken]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }

        return Result.Ok(Parsed(savedTags = saved + ariaSaved, csrfToken = token))
    }

    private fun looksUnauthed(finalUrl: String): Boolean {
        // The cookie-redirect flow lands on /account/login?ReturnUrl=...
        // The body still has the same shell HTML, so we key off the
        // resolved URL, not the body bytes.
        return RoyalRoadTagSyncEndpoints.UNAUTHED_PATH_PREFIX in finalUrl
    }

    @Suppress("unused")
    internal fun parseFromDocument(doc: Document, finalUrl: String): Result =
        parse(doc.outerHtml(), finalUrl)
}
