package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.crypto.UserDerivedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserDerivedKeyTest {

    @Test fun `deriveKey is deterministic for the same passphrase and salt`() {
        val salt = UserDerivedKey.randomSalt()
        val a = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val b = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        assertTrue("same passphrase + salt should produce same key bytes",
            a.encoded.contentEquals(b.encoded))
    }

    @Test fun `deriveKey is different for different salts`() {
        val passphrase = "hunter2".toCharArray()
        val a = UserDerivedKey.deriveKey(passphrase, UserDerivedKey.randomSalt())
        val b = UserDerivedKey.deriveKey(passphrase, UserDerivedKey.randomSalt())
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test fun `encrypt then decrypt roundtrips the plaintext`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val plaintext = "{\"openai\":\"sk-test\",\"royalroad\":\"cookie-blob\"}".toByteArray()
        val ct = UserDerivedKey.encrypt(key, plaintext)
        val pt = UserDerivedKey.decrypt(key, ct)
        assertTrue(plaintext.contentEquals(pt))
    }

    @Test fun `wrong key fails to decrypt`() {
        val saltA = UserDerivedKey.randomSalt()
        val saltB = UserDerivedKey.randomSalt()
        val keyA = UserDerivedKey.deriveKey("hunter2".toCharArray(), saltA)
        val keyB = UserDerivedKey.deriveKey("hunter3".toCharArray(), saltB)
        val ct = UserDerivedKey.encrypt(keyA, "secret".toByteArray())
        try {
            UserDerivedKey.decrypt(keyB, ct)
            fail("expected GCM auth failure")
        } catch (e: Exception) {
            // GCM throws AEADBadTagException (subclass of BadPaddingException);
            // either is acceptable evidence of authenticated-decrypt failure.
        }
    }

    @Test fun `envelope roundtrips through parse`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val ct = UserDerivedKey.encrypt(key, "secret".toByteArray())
        val env = UserDerivedKey.envelope(salt, ct)
        val parsed = UserDerivedKey.parseEnvelope(env)
        assertNotNull(parsed)
        assertTrue(salt.contentEquals(parsed!!.salt))
        assertEquals(ct, parsed.blob)
        // And the parsed blob decrypts cleanly with the original key.
        assertTrue("secret".toByteArray().contentEquals(UserDerivedKey.decrypt(key, parsed.blob)))
    }

    @Test fun `parseEnvelope returns null on garbage`() {
        assertNull(UserDerivedKey.parseEnvelope(""))
        assertNull(UserDerivedKey.parseEnvelope("v0:foo:bar:baz")) // bad version
        assertNull(UserDerivedKey.parseEnvelope("v1:notbase64!!:notbase64!!:notbase64!!"))
        assertNull(UserDerivedKey.parseEnvelope("v1:onlyoneparts"))
    }

    @Test fun `encrypt produces a different iv each call`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val plaintext = "same plaintext".toByteArray()
        val a = UserDerivedKey.encrypt(key, plaintext)
        val b = UserDerivedKey.encrypt(key, plaintext)
        // GCM nonce-reuse with the same key is catastrophic; verify we
        // generate fresh IVs and therefore the ciphertexts diverge.
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }
}
