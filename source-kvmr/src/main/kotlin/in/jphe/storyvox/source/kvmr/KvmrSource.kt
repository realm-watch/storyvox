package `in`.jphe.storyvox.source.kvmr

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #374 (closes #373 first piece) — KVMR community radio
 * (kvmr.org) as the first concrete audio-stream backend.
 *
 * KVMR is the community radio station for Nevada City + the Sierra
 * foothills — JP's local station. Long-form spoken-word programming,
 * live music, scheduled shows; a perfect fit for storyvox's
 * audiobook-reader audience. The source publishes one fiction with
 * one chapter — "Live" — whose [ChapterContent.audioUrl] points at
 * KVMR's public AAC stream. The playback engine (in
 * `:core-playback`) routes that URL through a Media3 ExoPlayer
 * instance and bypasses the TTS pipeline entirely (issue #373's
 * architectural seam).
 *
 * Discovery surfaces (search, latestUpdates, byGenre, genres) all
 * collapse to the one live fiction or return empty — there's no
 * catalog to browse, just the station. Auth-gated surfaces (follows
 * list, set-followed) are no-ops; the user can still add the live
 * fiction to their Library shelf via the regular library plumbing.
 *
 * Legal posture: zero ToS friction. KVMR publishes the AAC stream
 * URL on their public Listen Live page explicitly intended for
 * third-party listeners (mobile apps, browser players, smart
 * speakers). The User-Agent below identifies storyvox so any
 * rate-limit / abuse concerns can be routed to a real contact.
 *
 * Pattern for future audio sources: copy this module shape (single
 * fiction + single chapter + audioUrl populated), swap the URL and
 * the User-Agent, and the playback wiring lights up for free. A
 * `:source-radio-stream` consolidation can follow once three or
 * more stations land — premature for one.
 */
/**
 * Plugin-seam Phase 1 worked example (#384). The `@SourcePlugin`
 * annotation is what makes `:core-plugin-ksp` emit a
 * `@Provides @IntoSet SourcePluginDescriptor` Hilt module for KVMR
 * — registering it into [SourcePluginRegistry] without anyone having
 * to add a new branch to `BrowseSourceKey`, a new `sourceKvmrEnabled`
 * field on `UiSettings`, or a new toggle row in
 * `SettingsScreen.kt`. The legacy `@IntoMap @StringKey("kvmr")`
 * binding in `di/KvmrModule.kt` is intentionally kept for Phase 1 so
 * the repository's existing `Map<String, FictionSource>` keeps
 * resolving KVMR; Phase 2 removes the legacy binding once the
 * registry-driven repository routing lands.
 *
 * NOTE: the `internal` visibility on this class matters — KSP's
 * generated factory references `KvmrSource` by FQN from the
 * `in.jphe.storyvox.plugin.generated` package. That package is
 * compiled into the SAME module (`:source-kvmr`), so `internal` is
 * still visible to the generated code. If a future refactor moves the
 * generated module to a different Gradle module, this class would
 * need to be promoted to `public`.
 */
@SourcePlugin(
    id = SourceIds.KVMR,
    displayName = "KVMR",
    defaultEnabled = true,
    category = SourceCategory.AudioStream,
    supportsFollow = false,
    supportsSearch = true,
    description = "KVMR community radio · Nevada City · live AAC stream via Media3 (bypasses TTS)",
    sourceUrl = "https://kvmr.org",
)
@Singleton
internal class KvmrSource @Inject constructor() : FictionSource {

    override val id: String = SourceIds.KVMR
    override val displayName: String = "KVMR"

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        // Single-fiction backend — the popular tab IS the station. Page 2+
        // returns empty so the paginator stops requesting.
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        return FictionResult.Success(
            ListPage(
                items = listOf(liveStationSummary()),
                page = 1,
                hasNext = false,
            ),
        )
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Same list as popular — there's only one fiction. NewReleases is
        // tab-hidden on KVMR (see BrowseSourceKey.supportedTabs); this
        // method exists so a stale tab pointer doesn't 404 the paginator.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // KVMR is one continuous program; no genre faceting. Returning
        // empty rather than the live fiction is more honest — if the user
        // hit a genre row by mistake the result reads as "nothing in this
        // genre" not "this station IS this genre."
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        // If the user typed something that looks like the station name,
        // surface the live fiction. Otherwise empty — fuzzy matching
        // against one record isn't valuable.
        val matches = term.isEmpty() ||
            term.contains("kvmr") ||
            "nevada city".startsWith(term) ||
            "community radio".contains(term)
        return FictionResult.Success(
            ListPage(
                items = if (matches) listOf(liveStationSummary()) else emptyList(),
                page = 1,
                hasNext = false,
            ),
        )
    }

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        if (fictionId != LIVE_FICTION_ID) {
            return FictionResult.NotFound("Unknown KVMR fiction id: $fictionId")
        }
        return FictionResult.Success(
            FictionDetail(
                summary = liveStationSummary(),
                chapters = listOf(liveChapterInfo()),
            ),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        if (fictionId != LIVE_FICTION_ID || chapterId != LIVE_CHAPTER_ID) {
            return FictionResult.NotFound("Unknown KVMR chapter: $fictionId / $chapterId")
        }
        return FictionResult.Success(
            ChapterContent(
                info = liveChapterInfo(),
                // Issue #373 — audio chapters have empty text bodies. The
                // playback engine sees `audioUrl != null` and routes
                // through Media3 instead of the TTS pipeline; the reader
                // view falls back to a "Live audio" card when both
                // bodies are blank.
                htmlBody = "",
                plainBody = "",
                audioUrl = STREAM_URL,
            ),
        )
    }

    // ─── auth-gated ────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    private fun liveStationSummary(): FictionSummary =
        FictionSummary(
            id = LIVE_FICTION_ID,
            sourceId = SourceIds.KVMR,
            title = "KVMR Community Radio",
            author = "Nevada City, California",
            description = "Music of the world. Voice of the community. " +
                "Live stream from KVMR 89.5 FM in Nevada City.",
            coverUrl = null,
            tags = listOf("community radio", "live", "Nevada City"),
            // ONGOING reads correctly for a live stream — the "story"
            // (broadcast) is continuously updating. COMPLETED would
            // misrepresent a perpetually-on station.
            status = FictionStatus.ONGOING,
            chapterCount = 1,
        )

    private fun liveChapterInfo(): ChapterInfo =
        ChapterInfo(
            id = LIVE_CHAPTER_ID,
            sourceChapterId = "live",
            index = 0,
            title = "Live",
        )

    companion object {
        /**
         * Stable fiction id — there's only ever one KVMR fiction. Using
         * a hardcoded constant rather than mining a kvmr.org per-station
         * id because (a) there's no station catalog to vary against,
         * and (b) it keeps the chapter id stable across reinstalls so
         * a user's Library / Follows / playback position rows survive.
         */
        const val LIVE_FICTION_ID: String = "kvmr:live"

        /** Stable chapter id — same rationale as [LIVE_FICTION_ID]. */
        const val LIVE_CHAPTER_ID: String = "kvmr:live:0"

        /**
         * KVMR public AAC stream URL (harvested 2026-05-13 from
         * kvmr.org/listen-live). 96kbps AAC — Media3 / ExoPlayer's
         * default extractor + DefaultDataSource.Factory handle this
         * natively, no custom protocol code needed. The `#.mp3` suffix
         * is a hint for older players that want a familiar extension;
         * ExoPlayer ignores the fragment and sniffs the AAC container.
         *
         * If KVMR ever rotates the URL, this is the one place to
         * update. The persisted Chapter row's `audioUrl` column gets
         * refreshed on the next chapter-download run (the URL is
         * regenerated per-fetch from this constant).
         */
        const val STREAM_URL: String =
            "https://sslstream.kvmr.org:9433/aac96#.mp3"

        /**
         * User-Agent used by future KVMR HTTP traffic (now-playing
         * lookups, schedule metadata) — declared here so a single
         * grep finds every storyvox→KVMR network surface. The
         * playback-side stream fetch happens inside Media3 and uses
         * Media3's own UA today; if that ever needs overriding, build
         * a custom DataSource.Factory off this string.
         */
        const val USER_AGENT: String =
            "storyvox-kvmr/1.0 (+https://github.com/jphein/storyvox)"
    }
}
