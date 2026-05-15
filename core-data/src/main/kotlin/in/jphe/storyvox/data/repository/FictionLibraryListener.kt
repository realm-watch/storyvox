package `in`.jphe.storyvox.data.repository

/**
 * Hook that listens for library-add / library-remove events. Bound by
 * `:app`'s Hilt module to the playback layer's `PrerenderTriggers`
 * (PR-F, #86) so [FictionRepository] can call into the cache layer
 * without taking a `:core-playback` dependency directly. The interface
 * is a one-way seam — :core-data calls; :core-playback (via :app's
 * binding) listens.
 *
 * Default impl is a no-op so test doubles, alternate FictionRepository
 * impls, and library-only consumers that don't run the playback layer
 * never need to stub the methods.
 *
 * Default binding is the [NoOp] singleton so the graph compiles even
 * when no PrerenderTriggers consumer is wired (e.g. instrumented tests
 * that exercise FictionRepository in isolation). `:app`'s
 * CacheBindingsModule overrides with the real PrerenderTriggers.
 */
interface FictionLibraryListener {
    /**
     * Called from [FictionRepository.addToLibrary] AFTER the row has
     * been flipped in_library = true. The receiver typically schedules
     * background pre-renders for the first few chapters.
     *
     * Suspending so the receiver can hit DataStore / SQLite without
     * pinning the caller's coroutine — addToLibrary already runs on
     * `Dispatchers.IO`.
     */
    suspend fun onLibraryAdded(fictionId: String) {}

    /**
     * Called from [FictionRepository.removeFromLibrary] AFTER the row
     * has been flipped out of the library. The receiver typically
     * cancels pending background renders for the fiction.
     *
     * Non-suspending so removeFromLibrary's "cancel-by-tag" call is
     * synchronous from the repo's perspective — WorkManager's
     * cancelAllWorkByTag is fire-and-forget.
     */
    fun onLibraryRemoved(fictionId: String) {}

    /** No-op default. Used as the Hilt binding default when no
     *  PrerenderTriggers is wired (tests, library-only consumers). */
    object NoOp : FictionLibraryListener
}
