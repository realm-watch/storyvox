package `in`.jphe.storyvox.source.ao3.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #381 — minimal client for [Archive of Our Own](https://archiveofourown.org/).
 *
 * Two surfaces, neither of which requires HTML scraping:
 *
 *  1. **Per-tag Atom feeds** — `/tags/<tag>/feed.atom` returns the
 *     most recent works for a tag. Each entry already carries the
 *     work id, title, author(s), summary, and publish/update
 *     timestamps in well-defined Atom XML — enough to render a
 *     [FictionSummary] grid without a detail fetch.
 *
 *  2. **Per-work EPUB download** — every work page has an official
 *     Download menu (EPUB / MOBI / PDF / HTML / AZW3). The EPUB URL
 *     pattern is
 *     `/downloads/<work_id>/<sanitized_title>.epub?updated_at=<ts>`.
 *     We let the [EpubParser] consume the bytes verbatim, same as
 *     [GutenbergSource][in.jphe.storyvox.source.gutenberg.GutenbergSource].
 *
 * No auth required for v1. Some "Archive Warning: Choose Not to Use
 * Warnings" works gate the EPUB download behind a logged-in session —
 * we currently return a [FictionResult.NotFound] there (the
 * Archive surfaces an HTML "you must agree" interstitial rather than
 * a 401, so 200-with-non-EPUB-body is treated as missing). Sign-in
 * is a deliberate follow-up; see the PR body for #381.
 *
 * Identifies politely on every request — non-commercial, free, with a
 * contact URL in the User-Agent so OTW Ops can route any concerns to a
 * real address rather than blocking the whole CIDR.
 */
@Singleton
internal class Ao3Api @Inject constructor(
    private val client: OkHttpClient,
) {
    /**
     * `GET /tags/<tag>/feed.atom` — the listing surface. Returns the
     * raw XML body for [Ao3AtomFeed.parse] to turn into typed
     * entries. AO3 paginates this feed via a `?page=N` query param;
     * the feed itself is fixed at ~20 entries per page.
     */
    suspend fun tagFeed(tag: String, page: Int = 1): FictionResult<Ao3AtomFeed> {
        // AO3 tags are URL-encoded with spaces → `%20` and `/` → `*s*`
        // (AO3's own escape token). We support the slash-form because
        // many fandoms include slashes ("Sherlock Holmes & Related
        // Fandoms - All Media Types" doesn't, but ship/relationship
        // tags use them frequently). Curated v1 tags don't include
        // slashes so the simple form works; the more complex escapes
        // are deferred until the user-tag-picker lands.
        val safeTag = URLEncoder.encode(tag, Charsets.UTF_8)
            .replace("+", "%20")
        val path = if (page <= 1) {
            "/tags/$safeTag/feed.atom"
        } else {
            "/tags/$safeTag/feed.atom?page=$page"
        }
        return withContext(Dispatchers.IO) {
            requestText(path).let { res ->
                when (res) {
                    is FictionResult.Success -> {
                        try {
                            FictionResult.Success(Ao3AtomFeed.parse(res.value))
                        } catch (e: Exception) {
                            FictionResult.NetworkError(
                                "AO3 Atom feed for tag '$tag' unparseable: ${e.message}",
                                e,
                            )
                        }
                    }
                    is FictionResult.Failure -> res
                }
            }
        }
    }

    /**
     * Direct EPUB download from `/downloads/<work_id>/<slug>.epub`.
     * The slug is a sanitized title — anything reasonably URL-safe
     * works since AO3 redirects to the canonical filename based on
     * `work_id` alone. We pass `"storyvox.epub"` to keep the URL
     * short and identifiable in network logs.
     *
     * Returns the raw bytes for [EpubParser][in.jphe.storyvox.source.epub.parse.EpubParser]
     * to consume.
     */
    suspend fun downloadEpub(workId: Long): FictionResult<ByteArray> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/downloads/$workId/storyvox.epub"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/epub+zip")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val ctype = resp.header("Content-Type").orEmpty()
                when {
                    resp.code == 404 -> FictionResult.NotFound("EPUB not available for work $workId")
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            "AO3 sign-in required for work $workId (likely Archive-Warning gated)",
                        )
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} downloading AO3 EPUB",
                        IOException("HTTP ${resp.code}"),
                    )
                    // Archive-Warning-gated works return 200 with the
                    // HTML "You must agree" interstitial instead of a
                    // real EPUB. Detect by Content-Type and surface as
                    // AuthRequired so the UI can show the right copy
                    // (sign-in is the follow-up that will resolve it).
                    !ctype.contains("epub", ignoreCase = true) &&
                        !ctype.contains("octet-stream", ignoreCase = true) &&
                        !ctype.contains("zip", ignoreCase = true) ->
                        FictionResult.AuthRequired(
                            "AO3 returned non-EPUB ($ctype) for work $workId — likely Archive-Warning gated; sign-in is a follow-up.",
                        )
                    else -> {
                        val bytes = resp.body?.bytes()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty AO3 EPUB body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(bytes)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "AO3 EPUB download failed", e)
        }
    }

    /** Generic text-body GET. Sync OkHttp `execute()` wrapped in
     *  `withContext(Dispatchers.IO)` for the same reason as the
     *  Gutenberg client — `suspend` alone doesn't hop off the main
     *  thread; without this wrapper every fetch from the UI thread
     *  crashes with `NetworkOnMainThreadException`. */
    private suspend fun requestText(path: String): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.5")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("AO3: $path not found")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        const val BASE_URL = "https://archiveofourown.org"

        /**
         * Identifies storyvox in the User-Agent. AO3's Terms of Service
         * draw a sharp line between commercial and non-commercial
         * automated access; storyvox is free, open-source, and uses
         * only official endpoints (Atom feeds + EPUB downloads), so we
         * surface the contact URL up front. If OTW Ops ever wants to
         * reach out, the project's GitHub Issues are the door.
         */
        const val USER_AGENT = "storyvox-ao3/1.0 (+https://github.com/jphein/storyvox)"
    }
}
