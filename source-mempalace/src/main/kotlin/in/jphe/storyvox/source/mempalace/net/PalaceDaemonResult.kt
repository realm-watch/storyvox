package `in`.jphe.storyvox.source.mempalace.net

/**
 * Outcome of a palace-daemon HTTP call. Mirrors core-data's
 * `FictionResult` variants but stays internal to the source module
 * so we can shape the daemon-specific error space (LAN-rejection,
 * 503 mid-rebuild, off-network) before mapping to the cross-source
 * contract.
 */
sealed class PalaceDaemonResult<out T> {
    data class Success<T>(val value: T) : PalaceDaemonResult<T>()
    /** Drawer / wing / room id doesn't exist on the palace. */
    data class NotFound(val message: String) : PalaceDaemonResult<Nothing>()
    /** API key was provided and rejected (or required and missing). */
    data class Unauthorized(val message: String) : PalaceDaemonResult<Nothing>()
    /** Daemon reachable but palace collection isn't (typically mid-rebuild). */
    data class Degraded(val message: String) : PalaceDaemonResult<Nothing>()
    /** Connection refused, DNS fail, timeout — daemon not on LAN. */
    data class NotReachable(val cause: Throwable) : PalaceDaemonResult<Nothing>()
    /** Malformed JSON or unexpected schema. */
    data class ParseError(val cause: Throwable) : PalaceDaemonResult<Nothing>()
    /** Anything else (4xx other than 401/404, 5xx other than 503). */
    data class HttpError(val code: Int, val message: String) : PalaceDaemonResult<Nothing>()
    /**
     * The configured host doesn't resolve to a LAN/loopback address
     * and isn't on the explicit allowlist. We refuse to send the
     * request — defense in depth against typos pointing at a public
     * host. See [`MemPalaceSource`] for the allowlist.
     */
    data class HostRejected(val host: String) : PalaceDaemonResult<Nothing>()
}
