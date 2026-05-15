package `in`.jphe.storyvox.source.ao3.auth

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR2 of #426 — pin the AO3 [Ao3AuthSource] field literals. The four
 * values here are load-bearing: the WebView capture path watches for
 * the identity-cookie name verbatim, the cookie jar keys on the
 * cookie host verbatim, and the navigation surface routes to the
 * sign-in URL verbatim. A regression in any of them silently breaks
 * sign-in (the user would type their password but the cookie capture
 * would never trigger), so this test pins the contract.
 */
class Ao3AuthSourceTest {

    private val auth = Ao3AuthSource()

    @Test fun `sourceId is the canonical AO3 constant`() {
        assertEquals(SourceIds.AO3, auth.sourceId)
        assertEquals("ao3", auth.sourceId)
    }

    @Test fun `signInUrl is the AO3 user-login form`() {
        assertEquals("https://archiveofourown.org/users/login", auth.signInUrl)
        assertTrue(auth.signInUrl.startsWith("https://"))
        assertFalse(
            "must not include the (deprecated) www subdomain",
            auth.signInUrl.contains("//www."),
        )
    }

    @Test fun `identityCookieName is the Rails session cookie`() {
        // AO3 = Ruby on Rails; cookie name is `_otwarchive_session`
        // (the upstream Rails app name is "otwarchive"). A typo here
        // would silently break WebView capture — the cookie would
        // appear in the jar but the watch loop would never trigger.
        assertEquals("_otwarchive_session", auth.identityCookieName)
    }

    @Test fun `cookieHost is the eTLD+1 the cookie jar keys on`() {
        // No `www.` prefix, no scheme — matches the
        // `topPrivateDomain()` value OkHttp computes for any URL
        // under AO3, so the cookie jar's bucket lookup succeeds on
        // every outgoing request.
        assertEquals("archiveofourown.org", auth.cookieHost)
        assertFalse(
            "cookieHost must not carry a scheme",
            auth.cookieHost.startsWith("http"),
        )
    }

    @Test fun `extractUsernameFromUrl strips the user path segment`() {
        assertEquals("alice", extractUsernameFromUrl("https://archiveofourown.org/users/alice"))
        assertEquals(
            "alice",
            extractUsernameFromUrl("https://archiveofourown.org/users/alice/pseuds/wonderland"),
        )
        assertEquals(
            "alice",
            extractUsernameFromUrl("https://archiveofourown.org/users/alice/subscriptions"),
        )
        assertEquals(
            "alice",
            extractUsernameFromUrl("https://archiveofourown.org/users/alice?show=marked"),
        )
    }

    @Test fun `extractUsernameFromUrl returns null for non-user URLs`() {
        assertNull(extractUsernameFromUrl(null))
        assertNull(extractUsernameFromUrl(""))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/"))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/works/12345"))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/tags/Marvel"))
    }

    @Test fun `extractUsernameFromUrl rejects reserved user segments`() {
        // /users/login, /users/logout, /users/password, etc. are AO3's
        // own auth routes — not real usernames. A successful sign-in
        // never lands the user on these URLs; treating them as a
        // username would write garbage into the auth_cookie.userId
        // column.
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/users/login"))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/users/logout"))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/users/password/new"))
        assertNull(extractUsernameFromUrl("https://archiveofourown.org/users/sign_up"))
    }

    @Test fun `USERNAME_KEY is the cross-module pseudo-cookie name`() {
        // The constant is referenced by the AO3 capture path AND by
        // `:feature`'s AuthViewModel (where it's duplicated because
        // `:feature` can't depend on `:source-ao3`). Both must agree
        // on the literal string `__storyvox_user`.
        assertEquals("__storyvox_user", USERNAME_KEY)
        // Defensive: a real AO3 cookie name must never start with
        // `__storyvox` — verify the namespace is clear.
        assertTrue(USERNAME_KEY.startsWith("__storyvox_"))
    }

    @Test fun `Ao3SessionCookies preserves cookie payload verbatim`() {
        // The data class is a value carrier; assert the constructor
        // round-trips a payload without any filtering. This is the
        // surface the WebView hands to the AuthViewModel — any
        // accidental transformation would silently change the cookie
        // header.
        val payload = mapOf("_otwarchive_session" to "abc", "remember_user_token" to "def")
        val cookies = Ao3SessionCookies(payload)
        assertEquals(payload, cookies.cookies)
        assertNotNull(cookies.cookies["_otwarchive_session"])
    }
}
