package `in`.jphe.storyvox.data.source.plugin

import `in`.jphe.storyvox.data.source.FictionSource

/**
 * Plugin-seam Phase 1 (#384) — runtime-visible description of an
 * `@SourcePlugin`-annotated source.
 *
 * One descriptor per registered plugin. KSP (`:core-plugin-ksp`) emits
 * a `@Provides @IntoSet` factory for each `@SourcePlugin` class that
 * constructs the descriptor from the annotation metadata + the
 * injected `FictionSource` instance. [SourcePluginRegistry] consumes
 * that set.
 *
 * The descriptor mirrors the [SourcePlugin] annotation fields and
 * additionally carries the live `FictionSource` instance so consumers
 * (BrowseScreen chip row, repository routing, Settings auto-section)
 * have a single object to work with instead of doing the
 * annotation-to-instance correlation themselves.
 *
 * Kept a plain `data class` so tests can construct fakes directly
 * without involving Hilt or KSP — see `SourcePluginRegistryTest`
 * for the fake-registry pattern.
 */
data class SourcePluginDescriptor(
    /** Stable identifier — matches [FictionSource.id] and the
     *  legacy `SourceIds` constant for backends that have one. */
    val id: String,
    /** UI label. Matches the annotation's `displayName`. */
    val displayName: String,
    /** Whether a fresh install seeds this plugin's toggle as ON. */
    val defaultEnabled: Boolean,
    /** Grouping hint for the Settings auto-section. */
    val category: SourceCategory,
    /** True when the plugin implements meaningful Follow support. */
    val supportsFollow: Boolean,
    /** True when the plugin implements a meaningful search surface. */
    val supportsSearch: Boolean,
    /** Live `FictionSource` instance — same one the repository's
     *  `Map<String, FictionSource>` resolves for this id. */
    val source: FictionSource,
)
