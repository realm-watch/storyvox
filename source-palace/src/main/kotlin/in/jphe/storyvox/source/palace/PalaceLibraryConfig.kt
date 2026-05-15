package `in`.jphe.storyvox.source.palace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #502 — user-configurable Palace Project library root URL.
 *
 * Palace Project doesn't have a canonical "default" library — every
 * supported library is its own OPDS root at a different host (some
 * libraries at `<library>.palaceproject.io`, some at custom domains
 * like `circulation.openebooks.us`, some at IP-restricted endpoints
 * served from inside a library network).
 *
 * The user supplies their library's root URL in Settings → Library &
 * Sync → Palace Project. The [PalaceSource] reads from this contract
 * on every catalog call; an unset URL returns
 * [FictionResult.AuthRequired]-shaped failures from the source surface
 * so the UI knows to prompt the user to configure a library.
 *
 * ## Why an interface and not a DataStore call directly
 *
 * `:source-palace` doesn't pull in `androidx.datastore` — keeps the
 * leaf-source dependency graph clean and matches the pattern other
 * source modules use (e.g., [`in.jphe.storyvox.source.radio.RadioConfig`],
 * `:source-discord`'s [`DiscordConfig`]). The real DataStore-backed
 * implementation lives in `:app` (or `:feature`) and is bound to this
 * interface via Hilt. Issue #501 owns the settings-surface wiring;
 * this PR provides the contract + an in-memory default that returns
 * "no library configured" so the source compiles and the unit tests
 * don't need an Android Context.
 *
 * ## Future-state thread
 *
 * v2 will likely take a *list* of configured libraries (some users have
 * cards at multiple libraries). Surface this as
 * `currentLibrary: Flow<PalaceLibrary?>` + a separate "list of
 * configured libraries" Flow. v1 keeps a single library to avoid
 * dragging the multi-library settings UX into this PR.
 *
 * Issue #500's encrypted-credential storage (library card + PIN) will
 * land alongside the multi-library expansion when borrow flows become
 * relevant — at which point a [PalaceLibrary] grows from "URL only" to
 * "URL + credential ref + last-known display name". For v1, we only
 * read public OPDS feeds, so no credentials touch this module.
 */
interface PalaceLibraryConfig {
    /**
     * The currently configured library root URL. Emits `null` when no
     * library has been configured yet (the fresh-install state). The
     * source treats `null` as "return an empty browse and an
     * AuthRequired on detail/chapter".
     *
     * Implementations MUST emit the current value on subscribe so the
     * source can observe the value synchronously after Hilt
     * construction — matches the StateFlow contract.
     */
    val libraryRootUrl: Flow<String?>
}

/**
 * Fallback implementation used when no DataStore-backed binding is
 * provided (e.g., unit tests, and the bootstrap path before #501 wires
 * a real settings binding). Always emits `null`, which surfaces as
 * "configure a library to start" in the source's read-side surface.
 *
 * The [setForTesting] hook lets unit tests inject a fake URL without
 * pulling in a DataStore harness.
 */
@Singleton
internal class InMemoryPalaceLibraryConfig @Inject constructor() : PalaceLibraryConfig {
    private val state = MutableStateFlow<String?>(null)
    override val libraryRootUrl: Flow<String?> = state.asStateFlow()

    /** Test-only: poke a URL into the in-memory state.
     *
     *  Production code lives in #501's settings binding — this method
     *  exists so the unit tests in `:source-palace/src/test/` can
     *  exercise the source against a fake library URL without the real
     *  DataStore implementation. */
    internal fun setForTesting(url: String?) {
        state.value = url
    }
}
