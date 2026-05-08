package `in`.jphe.storyvox.llm

/**
 * Provider-emitted errors. Distinguished cases let the UI surface
 * different paths: NotConfigured opens Settings; AuthFailed flags the
 * key field red; Transport offers retry; ProviderError surfaces the
 * status + body excerpt for diagnostics.
 */
sealed class LlmError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    abstract val provider: ProviderId

    /** No API key set, no Ollama URL set, no provider chosen, etc.
     *  UI surfaces a "Set up AI in Settings" toast and routes to
     *  Settings → AI. */
    class NotConfigured(override val provider: ProviderId) :
        LlmError("$provider is not configured")

    /** 401 / 403 from a cloud provider. UI surfaces "Key invalid —
     *  check Settings" and the Settings key field gets a red
     *  outline. We don't auto-clear the key — user might have a
     *  typo to fix; clearing makes them re-paste from scratch. */
    class AuthFailed(override val provider: ProviderId, val detail: String) :
        LlmError("$provider auth failed: $detail")

    /** Network EOF, DNS failure, TLS error — recoverable transport
     *  layer issue. Modal offers "Try again" + "Close". */
    class Transport(override val provider: ProviderId, cause: Throwable) :
        LlmError("$provider transport error", cause)

    /** Provider returned a 4xx / 5xx that isn't auth — quota, model
     *  not found, malformed body. The status code + truncated body
     *  excerpt help with diagnosis. */
    class ProviderError(
        override val provider: ProviderId,
        val status: Int,
        val detail: String,
    ) : LlmError("$provider returned $status: $detail")
}

/** Lightweight reachability + auth probe result. Used by the
 *  Settings "Test connection" button — fast (single HTTP call,
 *  doesn't spend model time) and side-effect-free. */
sealed class ProbeResult {
    object Ok : ProbeResult()
    data class AuthError(val message: String) : ProbeResult()
    data class NotReachable(val message: String) : ProbeResult()
    data class Misconfigured(val message: String) : ProbeResult()
}
