package `in`.jphe.storyvox.source.palace.parse

/**
 * Issue #502 — OPDS 1.x Atom-flavoured catalog feed representation.
 *
 * OPDS 1.x ([spec](https://specs.opds.io/opds-1.2)) is an Atom-flavoured
 * XML profile for publication catalogs. The shape mapped here covers
 * the slice of OPDS that `:source-palace` actually consumes:
 *
 *  - **Acquisition feeds** — feeds whose `<entry>` elements carry a
 *    `<link rel="http://opds-spec.org/acquisition">` (or the
 *    `/acquisition/open-access` / `/acquisition/borrow` variants).
 *    Each entry is one publication.
 *  - **Navigation feeds** — feeds whose `<entry>` elements link out to
 *    sub-feeds (collections, "by genre" lanes, etc.) via
 *    `type="application/atom+xml;profile=opds-catalog"`. The walker
 *    descends into these one level deep when assembling the genre list.
 *
 * The OPDS 2.x JSON profile is **not** parsed here; Palace Project
 * libraries currently expose 1.x as the default consumer-facing feed
 * (the Palace app pins to 1.x). A future-version pass can sit alongside
 * this one — see [`OpdsParser.parse`] for the entry point dispatch.
 */
internal data class OpdsFeed(
    /** Feed-level `<title>` (e.g. "NYPL — Featured"). */
    val title: String,
    /** Feed-level `<id>` if present; opaque, used for cache keys. */
    val id: String? = null,
    /**
     * One row per publication in the feed. Navigation entries (links to
     * sub-feeds rather than publications) are filtered into [navLinks]
     * during parse — the source-side surface only deals in publications.
     */
    val entries: List<OpdsEntry>,
    /** Sub-feed links pulled out of "navigation" entries. */
    val navLinks: List<OpdsNavLink> = emptyList(),
    /**
     * `<link rel="next">` pagination link as an absolute URL. Null when
     * the feed didn't advertise a next page; the source treats null as
     * "no more pages".
     */
    val nextHref: String? = null,
)

/**
 * One publication entry from an OPDS acquisition feed.
 *
 * Mirrors the Atom `<entry>` shape minus the bits storyvox doesn't use
 * (rights, subjects with vocab URIs, edition strings). The acquisition
 * link list is preserved verbatim so the source can dispatch on link
 * `rel` + `type` to decide free-EPUB-download vs LCP-deep-link.
 */
internal data class OpdsEntry(
    /** Atom `<id>` — Palace uses URN-style identifiers
     *  (`urn:librarysimplified.org:works/<id>`). Carried through to the
     *  storyvox `fictionId` so detail fetches stay stable across feed
     *  refreshes. */
    val id: String,
    val title: String,
    val author: String?,
    /** First `<summary>` or `<content>` text, plain. */
    val summary: String?,
    /** Cover image URL if the entry carried an `<link rel="…/image">`
     *  or `<link rel="http://opds-spec.org/image/thumbnail">`. */
    val coverUrl: String?,
    /** Publication categories — Atom `<category term="…" label="…">`.
     *  Used as the `tags` field on the storyvox [FictionSummary]. */
    val categories: List<String>,
    /** Every `<link>` on the entry, kept in document order so the
     *  caller can pick the highest-quality acquisition link. */
    val links: List<OpdsLink>,
)

/**
 * One `<link>` element on an entry or feed. Atom links carry semantic
 * meaning via the `rel` attribute (the relationship type) and a MIME
 * type indicating the resource shape at the other end.
 *
 * Acquisition rels seen in Palace feeds:
 *  - `http://opds-spec.org/acquisition` — generic acquisition (often a
 *    library-borrow flow, may resolve to a free EPUB or to an LCP
 *    license depending on `type`)
 *  - `http://opds-spec.org/acquisition/open-access` — free, no-DRM
 *    download (this is the only acquisition rel storyvox can serve
 *    directly in v1)
 *  - `http://opds-spec.org/acquisition/borrow` — requires an active
 *    loan; surfaces as a "borrow in Palace app" deep-link
 *
 * MIME types seen:
 *  - `application/epub+zip` — free EPUB
 *  - `application/vnd.readium.lcp.license.v1.0+json` — LCP license
 *    (DRM'd; v1 storyvox deep-links out, see lcp-drm-scope.md)
 *  - `application/audiobook+json` — Readium audiobook manifest (out of
 *    scope; future audiobook support would consume this)
 */
internal data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String? = null,
    /** Human-readable label, sometimes present on multi-acquisition
     *  entries to disambiguate ("EPUB", "PDF", etc.). */
    val title: String? = null,
)

/** Navigation entry collapsed into a (label, href) pair. The walker
 *  uses these to emit a genre list and to step into sub-collections. */
internal data class OpdsNavLink(
    val title: String,
    val href: String,
    val summary: String? = null,
)

/**
 * Standard OPDS link rels surfaced as Kotlin constants so call sites
 * don't carry stringly-typed magic. See the OPDS 1.2 spec for the full
 * list; this is the subset `:source-palace` reads.
 */
internal object OpdsRel {
    const val ACQUISITION = "http://opds-spec.org/acquisition"
    const val ACQUISITION_OPEN_ACCESS = "http://opds-spec.org/acquisition/open-access"
    const val ACQUISITION_BORROW = "http://opds-spec.org/acquisition/borrow"
    const val ACQUISITION_BUY = "http://opds-spec.org/acquisition/buy"
    const val ACQUISITION_SUBSCRIBE = "http://opds-spec.org/acquisition/subscribe"
    const val ACQUISITION_SAMPLE = "http://opds-spec.org/acquisition/sample"
    const val IMAGE = "http://opds-spec.org/image"
    const val IMAGE_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    const val ALTERNATE = "alternate"
    const val NEXT = "next"
    const val SELF = "self"
}

/**
 * MIME types of interest to the storyvox dispatch logic. Free EPUB
 * downloads route through `:source-epub`; LCP licenses produce a
 * deep-link CTA; everything else is informational.
 */
internal object OpdsMime {
    const val EPUB = "application/epub+zip"

    /** Readium LCP license-acquisition payload. Receiving this MIME on
     *  an acquisition link means the publication is DRM'd — storyvox
     *  surfaces it greyed-out with a deep-link out, no decryption
     *  attempt. See `scratch/libby-hoopla-palace-scope/lcp-drm-scope.md`
     *  for the deferred LCP plan. */
    const val LCP_LICENSE = "application/vnd.readium.lcp.license.v1.0+json"

    /** Readium audiobook manifest. Out of scope for v1; reserved here
     *  so future audiobook work can be a content-type addition rather
     *  than a parser rewrite. */
    const val AUDIOBOOK_MANIFEST = "application/audiobook+json"
}
