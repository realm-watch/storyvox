package `in`.jphe.storyvox.data.repository

import android.content.SharedPreferences
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.FictionSourceEvent
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [AuthRepositoryImpl] — covers the existing Royal
 * Road sign-in / sign-out behaviour AND the multi-source semantics
 * introduced by #426 (PR1).
 *
 * The pre-#426 implementation pinned to a single hardcoded source
 * (Royal Road); the post-refactor implementation routes by sourceId.
 * Both shapes must keep RR existing call sites bit-identical and let
 * PR2 add an AO3 binding without code changes to `:core-data`.
 *
 * Robolectric isn't pulled in — the SharedPreferences and AuthDao
 * dependencies are stubbed with hand-rolled in-memory fakes so the
 * test runs in milliseconds on plain JUnit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {

    // -- repo() ---------------------------------------------------------------

    private fun repo(
        sources: Map<String, FictionSource> = mapOf(SourceIds.ROYAL_ROAD to FakeSource(SourceIds.ROYAL_ROAD)),
        prefs: FakePrefs = FakePrefs(),
        dao: FakeAuthDao = FakeAuthDao(),
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()),
    ): Triple<AuthRepositoryImpl, FakePrefs, FakeAuthDao> {
        val impl = AuthRepositoryImpl(dao, prefs, sources, scope)
        return Triple(impl, prefs, dao)
    }

    // -- backwards-compat: RR default ----------------------------------------

    @Test fun `captureSession defaults to royal road sourceId`() = runTest {
        val (r, prefs, dao) = repo()
        r.captureSession(
            cookieHeader = "id=rr",
            userDisplayName = "alice",
            userId = "1",
            expiresAt = 123L,
        )
        // Stored under the RR-shaped key, not under some new
        // "default" string — so a v0.5.x install upgrading into
        // this build keeps reading its own cookie back.
        assertEquals("id=rr", prefs.getString("cookie:${SourceIds.ROYAL_ROAD}", null))
        assertEquals("alice", dao.rows[SourceIds.ROYAL_ROAD]?.userDisplayName)
        // Legacy single-flow accessor still flips to Authenticated.
        val state = r.sessionState.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals("id=rr", (state as SessionState.Authenticated).cookieHeader)
    }

    @Test fun `clearSession defaults to royal road and tears down both stores`() = runTest {
        val (r, prefs, dao) = repo()
        r.captureSession("id=rr", null, null, null)
        assertNotNull(prefs.getString("cookie:${SourceIds.ROYAL_ROAD}", null))

        r.clearSession()
        assertNull(prefs.getString("cookie:${SourceIds.ROYAL_ROAD}", null))
        assertNull(dao.rows[SourceIds.ROYAL_ROAD])
        assertEquals(SessionState.Anonymous, r.sessionState.value)
    }

    @Test fun `cookieHeader defaults to royal road`() = runTest {
        val (r, _, _) = repo()
        assertNull(r.cookieHeader())
        r.captureSession("id=rr", null, null, null)
        assertEquals("id=rr", r.cookieHeader())
    }

    // -- pre-existing RR session is rehydrated on init -----------------------

    @Test fun `init rehydrates an RR cookie persisted on disk`() = runTest {
        val prefs = FakePrefs().apply {
            edit().putString("cookie:${SourceIds.ROYAL_ROAD}", "id=rr").commit()
        }
        val dao = FakeAuthDao().apply {
            rows[SourceIds.ROYAL_ROAD] = AuthCookie(
                sourceId = SourceIds.ROYAL_ROAD,
                userDisplayName = "alice",
                userId = "1",
                capturedAt = 0L,
                expiresAt = 999L,
                lastVerifiedAt = 0L,
            )
        }
        val (r, _, _) = repo(prefs = prefs, dao = dao)
        val state = r.sessionState.value
        assertTrue(state is SessionState.Authenticated)
        assertEquals("id=rr", (state as SessionState.Authenticated).cookieHeader)
    }

    // -- multi-source: PR1 generalization ------------------------------------

    @Test fun `capture for two source ids keeps their states independent`() = runTest {
        val (r, prefs, _) = repo(
            sources = mapOf(
                SourceIds.ROYAL_ROAD to FakeSource(SourceIds.ROYAL_ROAD),
                SourceIds.AO3 to FakeSource(SourceIds.AO3),
            ),
        )
        r.captureSession("id=rr", null, null, null, sourceId = SourceIds.ROYAL_ROAD)
        r.captureSession("id=ao3", null, null, null, sourceId = SourceIds.AO3)

        // Encrypted prefs keys are per-source.
        assertEquals("id=rr", prefs.getString("cookie:${SourceIds.ROYAL_ROAD}", null))
        assertEquals("id=ao3", prefs.getString("cookie:${SourceIds.AO3}", null))

        // Each flow reflects its own source.
        val rr = r.sessionState(SourceIds.ROYAL_ROAD).value
        val ao3 = r.sessionState(SourceIds.AO3).value
        assertTrue(rr is SessionState.Authenticated)
        assertTrue(ao3 is SessionState.Authenticated)
        assertEquals("id=rr", (rr as SessionState.Authenticated).cookieHeader)
        assertEquals("id=ao3", (ao3 as SessionState.Authenticated).cookieHeader)

        // Clearing one source must not touch the other.
        r.clearSession(SourceIds.ROYAL_ROAD)
        assertEquals(SessionState.Anonymous, r.sessionState(SourceIds.ROYAL_ROAD).value)
        val ao3After = r.sessionState(SourceIds.AO3).value
        assertTrue(ao3After is SessionState.Authenticated)
    }

    @Test fun `cookieHeader is per source`() = runTest {
        val (r, _, _) = repo(
            sources = mapOf(
                SourceIds.ROYAL_ROAD to FakeSource(SourceIds.ROYAL_ROAD),
                SourceIds.AO3 to FakeSource(SourceIds.AO3),
            ),
        )
        r.captureSession("id=rr", null, null, null, sourceId = SourceIds.ROYAL_ROAD)
        r.captureSession("id=ao3", null, null, null, sourceId = SourceIds.AO3)
        assertEquals("id=rr", r.cookieHeader(SourceIds.ROYAL_ROAD))
        assertEquals("id=ao3", r.cookieHeader(SourceIds.AO3))
    }

    // -- verifyOrExpire -------------------------------------------------------

    @Test fun `verifyOrExpire transitions to Expired on AuthRequired`() = runTest {
        val src = FakeSource(SourceIds.ROYAL_ROAD).apply {
            followsListResult = FictionResult.AuthRequired()
        }
        val (r, _, _) = repo(sources = mapOf(SourceIds.ROYAL_ROAD to src))
        r.captureSession("id=rr", null, null, null)
        val state = r.verifyOrExpire()
        assertEquals(SessionState.Expired, state)
    }

    @Test fun `verifyOrExpire success stamps lastVerifiedAt and keeps Authenticated`() = runTest {
        val src = FakeSource(SourceIds.ROYAL_ROAD).apply {
            followsListResult = FictionResult.Success(ListPage(emptyList(), 1, false))
        }
        val (r, _, dao) = repo(sources = mapOf(SourceIds.ROYAL_ROAD to src))
        r.captureSession("id=rr", null, null, null)
        val before = dao.rows[SourceIds.ROYAL_ROAD]?.lastVerifiedAt
        val state = r.verifyOrExpire()
        assertTrue(state is SessionState.Authenticated)
        val after = dao.rows[SourceIds.ROYAL_ROAD]?.lastVerifiedAt
        // The fake DAO touchVerified updates lastVerifiedAt to the
        // current time; both before and after are non-null and after
        // is >= before. We just assert it didn't get nulled out.
        assertNotNull(after)
        assertTrue(after!! >= (before ?: 0L))
    }

    @Test fun `verifyOrExpire with no cookie flips to Anonymous`() = runTest {
        val src = FakeSource(SourceIds.ROYAL_ROAD)
        val (r, _, _) = repo(sources = mapOf(SourceIds.ROYAL_ROAD to src))
        val state = r.verifyOrExpire()
        assertEquals(SessionState.Anonymous, state)
        // Source must not be called — there was nothing to verify.
        assertEquals(emptyList<String>(), src.callLog)
    }

    @Test fun `verifyOrExpire for an unbound sourceId keeps current state`() = runTest {
        val (r, prefs, _) = repo(
            sources = mapOf(SourceIds.ROYAL_ROAD to FakeSource(SourceIds.ROYAL_ROAD)),
        )
        // Simulate PR2: a cookie persisted for AO3 but the AO3 source
        // binding hasn't landed yet. The repo MUST NOT throw; it
        // should preserve the existing state.
        prefs.edit().putString("cookie:${SourceIds.AO3}", "id=ao3").commit()
        // Re-build the repo so init() picks up the disk state.
        val (r2, _, _) = repo(
            sources = mapOf(SourceIds.ROYAL_ROAD to FakeSource(SourceIds.ROYAL_ROAD)),
            prefs = prefs,
        )
        val state = r2.verifyOrExpire(SourceIds.AO3)
        // Existing state was Authenticated (from init's hydration); the
        // unbound-source branch keeps it Authenticated rather than
        // erroring.
        assertTrue(state is SessionState.Authenticated)
    }

    // -- fakes ---------------------------------------------------------------

    private class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { map[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { map[k] = v }
            override fun putInt(k: String, v: Int) = apply { map[k] = v }
            override fun putLong(k: String, v: Long) = apply { map[k] = v }
            override fun putFloat(k: String, v: Float) = apply { map[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { map[k] = v }
            override fun remove(k: String) = apply { map.remove(k) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeAuthDao : AuthDao {
        val rows = mutableMapOf<String, AuthCookie>()
        private val flows = mutableMapOf<String, MutableStateFlow<AuthCookie?>>()
        private fun flowFor(id: String) = flows.getOrPut(id) { MutableStateFlow(rows[id]) }

        override fun observe(sourceId: String): Flow<AuthCookie?> = flowFor(sourceId).asStateFlow()
        override suspend fun get(sourceId: String): AuthCookie? = rows[sourceId]
        override suspend fun upsert(cookie: AuthCookie) {
            rows[cookie.sourceId] = cookie
            flowFor(cookie.sourceId).value = cookie
        }
        override suspend fun touchVerified(sourceId: String, now: Long) {
            val existing = rows[sourceId] ?: return
            val updated = existing.copy(lastVerifiedAt = now)
            rows[sourceId] = updated
            flowFor(sourceId).value = updated
        }
        override suspend fun clear(sourceId: String) {
            rows.remove(sourceId)
            flowFor(sourceId).value = null
        }
    }

    /**
     * Minimal [FictionSource] stub that records its own follows-list
     * calls and lets each test stub the result. Other methods aren't
     * touched by [AuthRepositoryImpl] — left as TODO() so any
     * unintended use surfaces immediately.
     */
    private class FakeSource(override val id: String) : FictionSource {
        override val displayName: String = "Fake $id"
        var followsListResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        val callLog = mutableListOf<String>()

        override suspend fun popular(page: Int) = TODO("unused")
        override suspend fun latestUpdates(page: Int) = TODO("unused")
        override suspend fun byGenre(genre: String, page: Int) = TODO("unused")
        override suspend fun search(query: SearchQuery) = TODO("unused")
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = TODO("unused")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = TODO("unused")
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> {
            callLog += "followsList($page)"
            return followsListResult
        }
        override suspend fun setFollowed(fictionId: String, followed: Boolean) = TODO("unused")
        override suspend fun genres() = TODO("unused")
    }
}
