package `in`.jphe.storyvox.llm.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #219 — round-trip + validation tests for the
 * service-account JSON parser. Reads from the canned blob in
 * `src/test/resources/test-sa.json` (real RSA-2048 keypair generated
 * at fixture-build time so signature verification works in
 * [GoogleJwtSignerTest] without injecting a network dependency).
 */
class GoogleServiceAccountTest {

    @Test
    fun `parses a valid service-account JSON`() {
        val json = readFixture("test-sa.json")
        val sa = GoogleServiceAccount.parse(json)
        assertEquals("service_account", sa.type)
        assertEquals("storyvox-test", sa.projectId)
        assertEquals(
            "storyvox-test-sa@storyvox-test.iam.gserviceaccount.com",
            sa.clientEmail,
        )
        assertEquals("https://oauth2.googleapis.com/token", sa.tokenUri)
        assertTrue(sa.privateKey.contains("BEGIN PRIVATE KEY"))
        assertTrue(sa.privateKey.contains("END PRIVATE KEY"))
    }

    @Test
    fun `rejects non-service-account type`() {
        val authorizedUser = """
            {
              "type": "authorized_user",
              "project_id": "x",
              "private_key": "-----BEGIN PRIVATE KEY-----\n\n-----END PRIVATE KEY-----",
              "client_email": "x@y.com",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GoogleServiceAccount.parse(authorizedUser)
        }
        // Message should mention the offending type for grep-ability.
        assertTrue(ex.message!!.contains("authorized_user"))
    }

    @Test
    fun `rejects malformed JSON`() {
        val garbage = "not even close to JSON"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GoogleServiceAccount.parse(garbage)
        }
        // Wraps the underlying parse error for caller convenience.
        assertTrue(ex.message!!.contains("Not a valid JSON file"))
    }

    @Test
    fun `rejects missing private_key field`() {
        // Schema-required field missing; serialization fails before
        // our validator runs, but we still bubble it up as an
        // IllegalArgumentException with a useful message.
        val missing = """
            {
              "type": "service_account",
              "project_id": "x",
              "client_email": "x@y.com",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            GoogleServiceAccount.parse(missing)
        }
    }

    @Test
    fun `rejects empty token_uri`() {
        // token_uri scheme is NOT validated at parse time (MockWebServer
        // tests need http URIs); we just require something non-empty.
        // A wrong scheme fails naturally at the OAuth round-trip.
        val empty = """
            {
              "type": "service_account",
              "project_id": "x",
              "private_key": "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
              "client_email": "x@y.com",
              "token_uri": ""
            }
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GoogleServiceAccount.parse(empty)
        }
        assertTrue(ex.message!!.contains("token_uri"))
    }

    /** Load a test fixture from the test/resources/ directory. */
    private fun readFixture(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()
}
