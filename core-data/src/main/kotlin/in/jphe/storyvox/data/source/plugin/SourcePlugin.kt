package `in`.jphe.storyvox.data.source.plugin

/**
 * Plugin-seam Phase 1 (#384) — marker annotation that auto-registers a
 * `FictionSource` implementation into [SourcePluginRegistry].
 *
 * Decorate a `FictionSource` implementation with this annotation, and
 * the `:core-plugin-ksp` SymbolProcessor generates a Hilt module that
 * contributes a [SourcePluginDescriptor] for it into a multibinding
 * `Set<SourcePluginDescriptor>`. The registry consumes that set at
 * construction time, surfacing the plugin without the caller having to
 * touch the central `BrowseSourceKey` enum / settings DataStore /
 * Settings → Library & Sync screen by hand (issue #384's "~17
 * touchpoints per new backend" → "~4" goal).
 *
 * Targets a class (the `FictionSource` implementation itself). KSP
 * emits the descriptor binding from the annotation's metadata + the
 * @Inject constructor on the target class.
 *
 * Retention is BINARY so the annotation is visible to the KSP pass at
 * compile time but isn't carried into the runtime classpath — there's
 * no need to read it via reflection once KSP has emitted the bindings.
 *
 * ## Phase 1 scope
 *
 * Phase 1 lands the annotation + registry + KSP processor + the new
 * `sourcePluginsEnabled` settings shape, with `:source-kvmr` as the
 * worked example. The other 11 backends keep their existing
 * `@IntoMap @StringKey` bindings unchanged; Phase 2 migrates them
 * one-by-one. The legacy bindings and the new descriptor binding
 * coexist — a `FictionSource` can have both a `@IntoMap` legacy
 * binding (so the repository's `Map<String, FictionSource>` keeps
 * resolving it) AND an `@SourcePlugin` annotation (so the registry
 * also surfaces it) until the legacy binding is removed.
 *
 * @property id Stable identifier matching `FictionSource.id` and the
 *  legacy `SourceIds` constant. Used as the key in
 *  `UiSettings.sourcePluginsEnabled` and in any persisted
 *  per-plugin state.
 * @property displayName Human-readable label for the Browse chip and
 *  the Settings → Library & Sync row.
 * @property defaultEnabled Whether a fresh install renders the plugin
 *  with its toggle ON. The settings migration uses this to seed
 *  the per-plugin map on first read.
 * @property category Grouping hint for the Settings auto-section
 *  (`Text` / `AudioStream` / `Database` / etc.).
 * @property supportsFollow True when the plugin implements a
 *  non-trivial `setFollowed` / `followsList`. The Browse and Detail
 *  UIs read this to gate Follow controls.
 * @property supportsSearch True when the plugin implements a
 *  meaningful `search()` surface. Browse hides the Search tab when
 *  false.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class SourcePlugin(
    val id: String,
    val displayName: String,
    val defaultEnabled: Boolean = false,
    val category: SourceCategory = SourceCategory.Text,
    val supportsFollow: Boolean = false,
    val supportsSearch: Boolean = false,
)

/**
 * High-level grouping for the Settings → Library & Sync auto-section
 * (#384). Categories drive section headings and the order plugins
 * appear in. Adding a category here is a one-line change; backends
 * use the existing values via the [SourcePlugin] annotation.
 *
 * - [Text] — HTML / Markdown / plain-text fiction: Royal Road, AO3,
 *   GitHub, RSS, Outline, Wikipedia, Notion.
 * - [Ebook] — EPUB-based catalogs: Project Gutenberg, Standard Ebooks,
 *   local EPUB files.
 * - [AudioStream] — live audio that bypasses TTS: KVMR community
 *   radio, future LibriVox MP3s, etc.
 * - [Database] — structured-DB backends that don't fit Text (Notion's
 *   alternative classification if we later split it; reserved).
 * - [Other] — escape hatch for backends that don't fit the above.
 */
enum class SourceCategory {
    Text,
    Ebook,
    AudioStream,
    Database,
    Other,
}
