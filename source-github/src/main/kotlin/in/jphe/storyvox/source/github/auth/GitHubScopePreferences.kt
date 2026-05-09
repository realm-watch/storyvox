package `in`.jphe.storyvox.source.github.auth

/**
 * Tiny port for the user-controlled "Enable private repos" toggle
 * (#203). The Device Flow code lives in `:source-github`; the toggle
 * itself is owned by `:app`'s settings DataStore. This interface is
 * the seam — kept narrow so the source module doesn't take a
 * dependency on `:feature/api`'s broader `SettingsRepositoryUi`.
 *
 * The Hilt binding lives in `:app`'s `AppBindings` and forwards to
 * the same `SettingsRepositoryUiImpl` singleton that backs
 * `SettingsRepositoryUi`.
 */
interface GitHubScopePreferences {
    /**
     * Read the persisted "Enable private repos" preference. Returns
     * false (least-privilege) when the user hasn't opted in, which
     * matches the v0.4.x baseline behaviour for existing installs.
     */
    suspend fun privateReposEnabled(): Boolean
}
