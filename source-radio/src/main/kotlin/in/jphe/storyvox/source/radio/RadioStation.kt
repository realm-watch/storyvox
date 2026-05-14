package `in`.jphe.storyvox.source.radio

/**
 * Issue #417 — one radio station as a storyvox fiction.
 *
 * Generalized from the original [`:source-kvmr`] single-station shape
 * (one fiction, one live chapter, one AAC URL). A `RadioStation` is the
 * descriptor that every station in the curated seed list (KVMR, KQED,
 * KCSB, etc.) AND every user-starred Radio Browser hit reduces to. The
 * source layer maps a `RadioStation` to a `FictionSummary` for browse
 * and to a `FictionDetail` with one "Live" chapter whose
 * [`ChapterContent.audioUrl`][in.jphe.storyvox.data.source.model.ChapterContent]
 * is [streamUrl] — same v0.5.20 audio-stream pipeline.
 *
 * ## Field semantics
 *
 * - [id] is the stable storyvox-scoped station identifier. For curated
 *   stations it's a short slug (`"kvmr"`, `"kqed"`); for Radio Browser
 *   imports it's the upstream `stationuuid` prefixed with `rb:` so the
 *   two namespaces never collide. The `fictionId` exposed through
 *   FictionSource is built as `"$id:live"` (e.g. `"kvmr:live"`) so
 *   existing v0.5.20+ persisted KVMR fictions keep resolving after the
 *   :source-kvmr → :source-radio rename (the persisted id is keyed off
 *   the station id, not the source id).
 * - [streamUrl] should be HTTPS — Media3 / ExoPlayer's default
 *   `DefaultDataSource.Factory` refuses cleartext HTTP on API 28+
 *   without an explicit allow-list in the manifest. The curated list
 *   below filters to HTTPS-only entries during construction; Radio
 *   Browser imports inherit the upstream value, and the source rejects
 *   stations whose URL isn't HTTPS.
 * - [streamCodec] is the upstream codec hint (AAC / MP3 / HLS). Media3
 *   sniffs the container itself, so the field is informational only —
 *   it's surfaced in the FictionSummary description so users know what
 *   they're tuning in.
 * - [homepage] points at the station's web home; surfaced as the
 *   FictionDetail outbound link when a user wants to verify the
 *   station before tapping play.
 */
data class RadioStation(
    val id: String,
    val displayName: String,
    val description: String,
    val streamUrl: String,
    val streamCodec: String,
    val country: String,
    val language: String,
    val tags: List<String>,
    val homepage: String,
)
