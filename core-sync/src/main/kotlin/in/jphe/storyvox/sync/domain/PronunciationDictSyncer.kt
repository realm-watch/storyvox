package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.decodePronunciationDictJson
import `in`.jphe.storyvox.data.repository.pronunciation.encodePronunciationDictJson
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the user's pronunciation dictionary (issue #135).
 *
 * The dict is already a JSON-serializable [PronunciationDict] with stable
 * encode/decode helpers, so this syncer is a thin wrapper over
 * [LwwBlobSyncer] — push the JSON blob, last-writer-wins.
 *
 * Why this is one of the early syncers: the dict is the user's most
 * meaningful TTS customisation. Re-typing 30+ pronunciation overrides
 * after a reinstall is exactly the kind of pain that motivates the sync
 * project in the first place.
 *
 * Caveat: there's no per-entry merging — adding a new entry on device A
 * while editing one on device B will lose one of the edits on next
 * sync. Per-entry merge is a v2 improvement; we accept the
 * "last-writer-wins on the whole dict" footgun for v1 because the dict
 * is rarely edited from multiple devices simultaneously.
 */
@Singleton
class PronunciationDictSyncer @Inject constructor(
    private val repo: PronunciationDictRepository,
    private val backend: InstantBackend,
) : Syncer {

    private val delegate by lazy {
        LwwBlobSyncer(
            name = DOMAIN,
            localRead = ::readLocal,
            localWrite = ::writeLocal,
            remote = BackendBlobRemote(domain = DOMAIN, backend = backend),
        )
    }

    /** updatedAt tracking: the repository doesn't carry one today, so we
     *  derive it from the content hash + last write epoch we remember.
     *  v1: just stamp every push with `now`. This loses some ordering
     *  information (a local edit that happened before a remote push
     *  may still get stamped "later" because we re-push on sync) but
     *  the conflict outcome is no worse than naive LWW. */
    private var lastLocalWriteAt: Long = 0L

    override val name: String get() = DOMAIN
    override suspend fun push(user: SignedInUser): SyncOutcome = delegate.push(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = delegate.pull(user)

    /** Call this from the dict repository every time we save. */
    fun stampLocalWrite(at: Long = System.currentTimeMillis()) {
        lastLocalWriteAt = at
    }

    private suspend fun readLocal(): Stamped<String>? {
        val dict = repo.current()
        if (dict.entries.isEmpty()) return null
        val stamp = if (lastLocalWriteAt > 0) lastLocalWriteAt else System.currentTimeMillis()
        return Stamped(value = encodePronunciationDictJson(dict), updatedAt = stamp)
    }

    private suspend fun writeLocal(stamped: Stamped<String>) {
        val decoded = decodePronunciationDictJson(stamped.value)
        repo.replaceAll(decoded)
        lastLocalWriteAt = stamped.updatedAt
    }

    companion object {
        const val DOMAIN: String = "pronunciation"
    }
}
