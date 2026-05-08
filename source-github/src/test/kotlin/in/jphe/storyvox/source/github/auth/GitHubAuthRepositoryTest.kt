package `in`.jphe.storyvox.source.github.auth

import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [GitHubAuthRepositoryImpl] against an in-memory
 * SharedPreferences fake. Verifies the encrypted-prefs key shape spec'd
 * in the design doc (`token:github*` namespace) plus the StateFlow
 * lifecycle on capture / clear / markExpired. Issue #91.
 */
class GitHubAuthRepositoryTest {

    @Test
    fun `init starts Anonymous when no token on disk`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = GitHubAuthRepositoryImpl(prefs)
        assertEquals(GitHubSession.Anonymous, repo.sessionState.value)
    }

    @Test
    fun `init hydrates Authenticated when token is present`() = runTest {
        val prefs = InMemoryPrefs().apply {
            putString(GitHubAuthRepositoryImpl.KEY_TOKEN, "gho_abc")
            putString(GitHubAuthRepositoryImpl.KEY_LOGIN, "octocat")
            putString(GitHubAuthRepositoryImpl.KEY_SCOPES, "read:user public_repo")
            putString(GitHubAuthRepositoryImpl.KEY_GRANTED_AT, "1735000000000")
        }
        val repo = GitHubAuthRepositoryImpl(prefs)
        val session = repo.sessionState.value
        assertTrue(session is GitHubSession.Authenticated)
        session as GitHubSession.Authenticated
        assertEquals("gho_abc", session.token)
        assertEquals("octocat", session.login)
        assertEquals("read:user public_repo", session.scopes)
        assertEquals(1735000000000L, session.grantedAt)
    }

    @Test
    fun `captureSession persists all four keys and updates StateFlow`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = GitHubAuthRepositoryImpl(prefs)
        repo.captureSession(token = "gho_xyz", login = "alice", scopes = "read:user public_repo")
        // StateFlow updated.
        val session = repo.sessionState.first()
        assertTrue(session is GitHubSession.Authenticated)
        session as GitHubSession.Authenticated
        assertEquals("gho_xyz", session.token)
        assertEquals("alice", session.login)
        // All four keys on disk.
        assertEquals("gho_xyz", prefs.getString(GitHubAuthRepositoryImpl.KEY_TOKEN, null))
        assertEquals("alice", prefs.getString(GitHubAuthRepositoryImpl.KEY_LOGIN, null))
        assertEquals("read:user public_repo", prefs.getString(GitHubAuthRepositoryImpl.KEY_SCOPES, null))
        assertTrue(
            "granted_at should be set to a positive epoch ms, got ${prefs.getString(GitHubAuthRepositoryImpl.KEY_GRANTED_AT, null)}",
            prefs.getString(GitHubAuthRepositoryImpl.KEY_GRANTED_AT, null)
                ?.toLongOrNull()?.let { it > 0L } == true,
        )
    }

    @Test
    fun `captureSession with null login removes login key but keeps token`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = GitHubAuthRepositoryImpl(prefs)
        repo.captureSession(token = "gho_first", login = "old", scopes = "read:user")
        repo.captureSession(token = "gho_second", login = null, scopes = "read:user")
        assertEquals("gho_second", prefs.getString(GitHubAuthRepositoryImpl.KEY_TOKEN, null))
        assertNull(prefs.getString(GitHubAuthRepositoryImpl.KEY_LOGIN, null))
    }

    @Test
    fun `clearSession removes all four keys and flips StateFlow to Anonymous`() = runTest {
        val prefs = InMemoryPrefs().apply {
            putString(GitHubAuthRepositoryImpl.KEY_TOKEN, "gho_abc")
            putString(GitHubAuthRepositoryImpl.KEY_LOGIN, "octocat")
            putString(GitHubAuthRepositoryImpl.KEY_SCOPES, "read:user")
            putString(GitHubAuthRepositoryImpl.KEY_GRANTED_AT, "1")
        }
        val repo = GitHubAuthRepositoryImpl(prefs)
        repo.clearSession()
        assertEquals(GitHubSession.Anonymous, repo.sessionState.value)
        assertNull(prefs.getString(GitHubAuthRepositoryImpl.KEY_TOKEN, null))
        assertNull(prefs.getString(GitHubAuthRepositoryImpl.KEY_LOGIN, null))
        assertNull(prefs.getString(GitHubAuthRepositoryImpl.KEY_SCOPES, null))
        assertNull(prefs.getString(GitHubAuthRepositoryImpl.KEY_GRANTED_AT, null))
    }

    @Test
    fun `markExpired flips StateFlow but leaves disk copy intact`() = runTest {
        val prefs = InMemoryPrefs()
        val repo = GitHubAuthRepositoryImpl(prefs)
        repo.captureSession(token = "gho_abc", login = "octocat", scopes = "read:user")
        repo.markExpired()
        assertEquals(GitHubSession.Expired, repo.sessionState.value)
        // Disk copy intact so Settings can show "Session expired" instead
        // of silently losing identity.
        assertEquals("gho_abc", prefs.getString(GitHubAuthRepositoryImpl.KEY_TOKEN, null))
        assertEquals("octocat", prefs.getString(GitHubAuthRepositoryImpl.KEY_LOGIN, null))
    }

    @Test
    fun `keys live under the per-source token namespace`() {
        // Spec-locking the key shape so future multi-source refactors don't
        // accidentally migrate them to a different prefix.
        assertEquals("token:github", GitHubAuthRepositoryImpl.KEY_TOKEN)
        assertEquals("token:github:login", GitHubAuthRepositoryImpl.KEY_LOGIN)
        assertEquals("token:github:scopes", GitHubAuthRepositoryImpl.KEY_SCOPES)
        assertEquals("token:github:granted_at", GitHubAuthRepositoryImpl.KEY_GRANTED_AT)
    }
}

/**
 * Minimal in-memory [SharedPreferences] for unit tests. Only the methods
 * the repo touches are non-trivial. Mirrors the FakeSecrets in
 * `:app`'s test-support module.
 */
private class InMemoryPrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { map[key] = values }
        override fun putInt(key: String, value: Int) = apply { map[key] = value }
        override fun putLong(key: String, value: Long) = apply { map[key] = value }
        override fun putFloat(key: String, value: Float) = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
