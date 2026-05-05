package `in`.jphe.storyvox.data.source.model

import kotlin.time.Duration

/**
 * Sealed return type for every [`in`.jphe.storyvox.data.source.FictionSource] call.
 *
 * Forces callers to handle the failure axes that actually shape UX: not-found,
 * rate-limited, network, auth-required, and Cloudflare challenges. Anything truly
 * unknown should be wrapped as [NetworkError] with a `cause`.
 */
sealed class FictionResult<out T> {

    data class Success<T>(val value: T) : FictionResult<T>()

    sealed class Failure : FictionResult<Nothing>() {
        abstract val cause: Throwable?
        abstract val message: String
    }

    /** Resource does not exist on the source. */
    data class NotFound(
        override val message: String = "Not found",
        override val cause: Throwable? = null,
    ) : Failure()

    /** HTTP 429 or equivalent. `retryAfter` may be null when the source didn't tell us. */
    data class RateLimited(
        val retryAfter: Duration?,
        override val message: String = "Rate limited",
        override val cause: Throwable? = null,
    ) : Failure()

    /** Connectivity / IO / HTTP 5xx. */
    data class NetworkError(
        override val message: String,
        override val cause: Throwable? = null,
    ) : Failure()

    /** The endpoint required an authenticated session and we didn't have one. */
    data class AuthRequired(
        override val message: String = "Sign-in required",
        override val cause: Throwable? = null,
    ) : Failure()

    /**
     * The request was intercepted by Cloudflare (or similar) and needs to be
     * resolved by a WebView. The caller is expected to escalate to a
     * `WebViewFetcher` and retry.
     */
    data class Cloudflare(
        val challengeUrl: String,
        override val message: String = "Cloudflare challenge",
        override val cause: Throwable? = null,
    ) : Failure()
}

/** Map success while passing failures through unchanged. */
inline fun <T, R> FictionResult<T>.map(transform: (T) -> R): FictionResult<R> =
    when (this) {
        is FictionResult.Success -> FictionResult.Success(transform(value))
        is FictionResult.Failure -> this
    }

/** Run a side effect on success; return the original result. */
inline fun <T> FictionResult<T>.onSuccess(block: (T) -> Unit): FictionResult<T> {
    if (this is FictionResult.Success) block(value)
    return this
}

/** Run a side effect on failure; return the original result. */
inline fun <T> FictionResult<T>.onFailure(block: (FictionResult.Failure) -> Unit): FictionResult<T> {
    if (this is FictionResult.Failure) block(this)
    return this
}
