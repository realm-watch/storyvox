package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format data classes for the InstantDB runtime API.
 *
 * We're not using the JS SDK (no Kotlin port exists). These types mirror the
 * actual JSON the public runtime/auth HTTP endpoints take and return,
 * extracted from instantdb/instant `client/packages/core/src/authAPI.ts`.
 * Field naming uses `@SerialName` because Instant's API uses kebab-case for
 * some fields (`app-id`, `refresh-token`) and snake_case for others — we
 * keep idiomatic Kotlin camelCase on this side and let the serializer do the
 * translation. See `docs/sync.md` for the architecture overview.
 */

/* ----- Magic-code auth (runtime/auth/send_magic_code) ----- */

@Serializable
internal data class SendMagicCodeRequest(
    @SerialName("app-id") val appId: String,
    val email: String,
)

@Serializable
internal data class SendMagicCodeResponse(
    val sent: Boolean,
)

/* ----- Verify code (runtime/auth/verify_magic_code) ----- */

@Serializable
internal data class VerifyMagicCodeRequest(
    @SerialName("app-id") val appId: String,
    val email: String,
    val code: String,
    @SerialName("refresh-token") val refreshToken: String? = null,
)

/**
 * The user envelope Instant returns from any auth verification endpoint.
 * The full record has extras (created_at, etc.) but we only consume what we
 * need — kotlinx-serialization's `ignoreUnknownKeys` (set on the Json
 * instance in [InstantClient]) drops the rest.
 */
@Serializable
data class InstantUser(
    val id: String,
    val email: String? = null,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
internal data class UserEnvelope(
    val user: InstantUser,
)

/* ----- Verify refresh token (runtime/auth/verify_refresh_token) ----- */

@Serializable
internal data class VerifyRefreshRequest(
    @SerialName("app-id") val appId: String,
    @SerialName("refresh-token") val refreshToken: String,
)

/* ----- Sign out (runtime/signout) ----- */

@Serializable
internal data class SignOutRequest(
    @SerialName("app_id") val appId: String,
    @SerialName("refresh_token") val refreshToken: String,
)

/* ----- Transaction step tuple ----- */

/**
 * A single step in an Instant transaction. The wire shape is a tuple
 * `["update"|"delete"|"link"|"unlink", entityName, id, fieldsOrLinks]`.
 *
 * We model these as a small sealed hierarchy and serialize manually via
 * [TxStepSerializer] (in InstantTransact.kt) — kotlinx-serialization
 * doesn't speak heterogeneous tuples natively.
 */
sealed interface TxStep {
    val entity: String
    val id: String

    data class Update(
        override val entity: String,
        override val id: String,
        val fields: JsonObject,
    ) : TxStep

    data class Delete(
        override val entity: String,
        override val id: String,
    ) : TxStep

    data class Link(
        override val entity: String,
        override val id: String,
        val links: JsonObject,
    ) : TxStep

    data class Unlink(
        override val entity: String,
        override val id: String,
        val links: JsonObject,
    ) : TxStep
}

/** Internal helper — wraps `Map<String, JsonElement>` for use in fields blobs. */
internal typealias FieldsMap = Map<String, JsonElement>
