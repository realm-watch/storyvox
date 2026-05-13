package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Set-shaped remote adaptor — serializes the (members, tombstones) tuple
 * as a single JSON blob and round-trips it through [InstantBackend].
 *
 * Storage layout: entity name `sets`, id = `<domain>:<userId>` so a
 * single InstantDB app can host every set domain without rowcount
 * blow-up. The blob is the authoritative remote copy of the merged
 * members + tombstones (post-conflict-resolution).
 */
class BackendSetRemote(
    private val domain: String,
    private val backend: InstantBackend,
    private val json: Json = Defaults.json,
) : SetSyncer.SetRemote {

    @Serializable
    private data class Payload(
        val members: List<String>,
        val tombstones: List<String>,
        val updatedAt: Long,
    )

    override suspend fun fetch(user: SignedInUser): Result<SetSyncer.RemoteSet> {
        val res = backend.fetch(user, entity = ENTITY, id = rowId(user)).getOrElse {
            return Result.failure(it)
        } ?: return Result.success(SetSyncer.RemoteSet(emptySet(), emptySet()))
        return runCatching {
            val payload = json.decodeFromString(Payload.serializer(), res.payload)
            SetSyncer.RemoteSet(
                members = payload.members.toSet(),
                tombstones = payload.tombstones.toSet(),
            )
        }
    }

    override suspend fun push(user: SignedInUser, members: Set<String>, tombstones: Set<String>): Result<Unit> {
        val payload = Payload(
            members = members.toList().sorted(),
            tombstones = tombstones.toList().sorted(),
            updatedAt = System.currentTimeMillis(),
        )
        val body = json.encodeToString(Payload.serializer(), payload)
        return backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = body,
            updatedAt = payload.updatedAt,
        )
    }

    private fun rowId(user: SignedInUser) = "$domain:${user.userId}"

    private companion object { const val ENTITY = "sets" }
}

/**
 * Blob-shaped remote adaptor — single JSON string carried verbatim,
 * plus an updatedAt. Used by LwwBlobSyncer for the pronunciation
 * dict, encrypted secrets envelope, and any other "one big blob"
 * domain.
 */
class BackendBlobRemote(
    private val domain: String,
    private val backend: InstantBackend,
) : LwwBlobSyncer.BlobRemote {

    override suspend fun fetch(user: SignedInUser): Result<Stamped<String>?> =
        backend.fetch(user, entity = ENTITY, id = rowId(user))
            .map { snap -> snap?.let { Stamped(it.payload, it.updatedAt) } }

    override suspend fun push(user: SignedInUser, payload: Stamped<String>): Result<Unit> =
        backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = payload.value,
            updatedAt = payload.updatedAt,
        )

    private fun rowId(user: SignedInUser) = "$domain:${user.userId}"

    private companion object { const val ENTITY = "blobs" }
}

internal object Defaults {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
