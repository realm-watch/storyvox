package `in`.jphe.storyvox.source.azure

import android.util.Log
import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory cache of the live Azure voice roster, keyed by region.
 *
 * The first call to [refresh] (or the first observation of [voices]
 * after Settings configures a key) hits Azure's `voices/list` endpoint
 * and populates the cache. Subsequent reads return immediately. Region
 * or key changes invalidate the cache; the next [refresh] re-fetches.
 *
 * **No persistence today.** A cold-start fetch costs one HTTPS round
 * trip (~150 ms on a warm connection, ~600 ms cold) for ~150 KB JSON,
 * which is cheap enough that surviving process death without
 * repopulating is fine. If the latency proves visible — e.g. the
 * picker briefly flashing "no voices" on app start — a DataStore
 * persistence layer is a follow-up; the contract here doesn't change.
 *
 * **Concurrency.** [refresh] is guarded by a [Mutex] so simultaneous
 * callers (UI scrubbing the picker while Settings flips region)
 * coalesce to one in-flight fetch. The fetch itself happens off the
 * main thread via [Dispatchers.IO]; the result is published through
 * the [MutableStateFlow] which handles thread-safety on the read side.
 */
@Singleton
class AzureVoiceRoster @Inject constructor(
    private val client: AzureSpeechClient,
    private val credentials: AzureCredentials,
) : AzureVoiceProvider {

    private val state = MutableStateFlow<List<AzureVoiceDescriptor>>(emptyList())
    private val refreshMutex = Mutex()
    private var lastFetchedKey: String? = null
    private var lastFetchedRegion: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Public flow — kicks the first refresh when something starts
     *  collecting (typically the picker UI on app start). The refresh
     *  is async so the first emission is the cached snapshot
     *  (initially empty) and updates land as Azure responds. */
    override val voices: Flow<List<AzureVoiceDescriptor>> = state.asStateFlow()
        .onStart { refreshAsync() }

    /**
     * Fetch the roster if it isn't current for the active key+region.
     * No-op when the cache already matches. Errors are caught and
     * logged — the picker will keep showing whatever roster it has
     * (possibly empty) rather than crash. Callers that need to
     * distinguish success from failure should call
     * [AzureSpeechClient.voicesListDetailed] directly.
     */
    override suspend fun refresh() {
        refreshMutex.withLock {
            val key = credentials.key()
            val region = credentials.regionId()
            if (key == null) {
                // No key — clear the roster so the picker collapses to
                // the "configure Azure key" empty state. This handles
                // the "user revoked their key" path cleanly.
                state.value = emptyList()
                lastFetchedKey = null
                lastFetchedRegion = null
                return
            }
            if (key == lastFetchedKey &&
                region == lastFetchedRegion &&
                state.value.isNotEmpty()
            ) {
                return
            }
            try {
                val voices = withContext(Dispatchers.IO) {
                    client.voicesListDetailed()
                }
                state.value = voices
                lastFetchedKey = key
                lastFetchedRegion = region
                Log.i(TAG, "Fetched ${voices.size} voices for region=$region")
            } catch (t: Throwable) {
                Log.w(TAG, "Roster fetch failed: ${t.javaClass.simpleName}: ${t.message}")
                // Leave previous roster in place — partial unavailability
                // is better than blanking the picker on a transient
                // network blip.
            }
        }
    }

    /**
     * Fire-and-forget refresh — for callers (Settings region chip,
     * Test connection button) that can't suspend. The launched job is
     * tied to the singleton scope so it survives the calling
     * Composable's lifetime, but coalesces via the same [refreshMutex]
     * so multiple rapid-fire calls don't pile up.
     */
    fun refreshAsync(): Job = scope.launch { refresh() }

    companion object {
        private const val TAG = "AzureVoiceRoster"
    }
}
