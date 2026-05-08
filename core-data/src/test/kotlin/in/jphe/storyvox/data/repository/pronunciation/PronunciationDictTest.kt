package `in`.jphe.storyvox.data.repository.pronunciation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PronunciationDictTest {

    @Test
    fun `empty dict returns input unchanged`() {
        val dict = PronunciationDict()
        assertEquals("Astaria fell.", dict.apply("Astaria fell."))
    }

    @Test
    fun `WORD mode is whole-word and case-insensitive by default`() {
        // The 90% case from the spec — "Astaria" everywhere, mixed case,
        // gets remapped phonetically. "Astar" alone (substring) stays.
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "Astaria", replacement = "uh-STAY-ree-uh"),
            )
        )
        assertEquals(
            "uh-STAY-ree-uh fell. uh-STAY-ree-uh's gates. Astar held.",
            dict.apply("Astaria fell. astaria's gates. Astar held."),
        )
    }

    @Test
    fun `WORD mode caseSensitive limits matches to exact casing`() {
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "Kuroinu", replacement = "kuroino", caseSensitive = true),
            )
        )
        // Only the capital-K form gets rewritten.
        assertEquals(
            "kuroino howled. kuroinu growled.",
            dict.apply("Kuroinu howled. kuroinu growled."),
        )
    }

    @Test
    fun `WORD mode quotes regex metacharacters in the middle of the pattern`() {
        // User types a name with an embedded regex metachar (e.g.
        // a hyphen, dot, or `?` inside a coined fictional name).
        // Pattern.quote must keep the metachar literal — without
        // quoting, `.` would match any single char and a name like
        // "Va.lia" would also match "Vaxlia", "Va lia", etc. Whole-word
        // semantics still requires word chars at both ends, so we use
        // a pattern that has metachars only in the middle.
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "Va.lia", replacement = "VAH-lee-ah"),
            )
        )
        assertEquals(
            "VAH-lee-ah arrived. Vaxlia stayed.",
            dict.apply("Va.lia arrived. Vaxlia stayed."),
        )
    }

    @Test
    fun `REGEX mode treats pattern as raw java regex`() {
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(
                    pattern = "(\\d+)st",
                    replacement = "$1-st",
                    matchType = MatchType.REGEX,
                ),
            )
        )
        assertEquals("the 21-st century", dict.apply("the 21st century"))
    }

    @Test
    fun `invalid REGEX entry is skipped, others still apply`() {
        // First entry has unbalanced bracket — Pattern.compile throws.
        // Second entry is valid. Per NVDA's invalid-entry-skip pattern,
        // the broken one drops out and the rest of the chain runs.
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(
                    pattern = "[unclosed",
                    replacement = "x",
                    matchType = MatchType.REGEX,
                ),
                PronunciationEntry(pattern = "Vessari", replacement = "veh-SAR-ee"),
            )
        )
        assertEquals("veh-SAR-ee waited.", dict.apply("Vessari waited."))
    }

    @Test
    fun `empty pattern entry is skipped`() {
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "", replacement = "should-not-appear"),
                PronunciationEntry(pattern = "Astaria", replacement = "ast"),
            )
        )
        assertEquals("ast.", dict.apply("Astaria."))
    }

    @Test
    fun `entries chain — later entries see earlier output`() {
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "foo", replacement = "bar"),
                PronunciationEntry(pattern = "bar", replacement = "baz"),
            )
        )
        // "foo" → "bar" → "baz"
        assertEquals("baz baz", dict.apply("foo bar"))
    }

    @Test
    fun `apply on empty string is identity`() {
        val dict = PronunciationDict(
            entries = listOf(PronunciationEntry(pattern = "x", replacement = "y")),
        )
        assertEquals("", dict.apply(""))
    }

    @Test
    fun `bad replacement backreference does not crash the chain`() {
        // `$9` references a group that doesn't exist; replaceAll
        // throws IndexOutOfBoundsException. We swallow and keep going.
        val dict = PronunciationDict(
            entries = listOf(
                PronunciationEntry(
                    pattern = "(foo)",
                    replacement = "$9",
                    matchType = MatchType.REGEX,
                ),
                PronunciationEntry(pattern = "bar", replacement = "ok"),
            )
        )
        // First entry is dropped at apply-time (not compile-time —
        // replaceAll fails on use). Sentence passes through, then the
        // second entry rewrites "bar".
        assertEquals("foo ok", dict.apply("foo bar"))
    }

    @Test
    fun `contentHash is stable for equal entries`() {
        val a = PronunciationDict(
            entries = listOf(PronunciationEntry(pattern = "Astaria", replacement = "ast")),
        )
        val b = PronunciationDict(
            entries = listOf(PronunciationEntry(pattern = "Astaria", replacement = "ast")),
        )
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `contentHash shifts when any entry field changes`() {
        val base = PronunciationDict(
            entries = listOf(
                PronunciationEntry(pattern = "Astaria", replacement = "ast"),
            )
        )
        val differentReplacement = base.copy(
            entries = listOf(PronunciationEntry(pattern = "Astaria", replacement = "ast-2")),
        )
        val differentPattern = base.copy(
            entries = listOf(PronunciationEntry(pattern = "Astar", replacement = "ast")),
        )
        val differentMode = base.copy(
            entries = listOf(
                PronunciationEntry(pattern = "Astaria", replacement = "ast", matchType = MatchType.REGEX),
            )
        )
        val differentCaseSens = base.copy(
            entries = listOf(
                PronunciationEntry(pattern = "Astaria", replacement = "ast", caseSensitive = true),
            )
        )
        for (other in listOf(differentReplacement, differentPattern, differentMode, differentCaseSens)) {
            assertNotEquals(
                "contentHash must shift; got same for $other",
                base.contentHash,
                other.contentHash,
            )
        }
    }

    @Test
    fun `EMPTY sentinel has zero entries`() {
        assertEquals(0, PronunciationDict.EMPTY.entries.size)
        // Identity is fine to assert here — EMPTY is a vended companion
        // val, callers shouldn't be allocating their own "empty" dicts.
        assertSame(PronunciationDict.EMPTY, PronunciationDict.EMPTY)
    }

    @Test
    fun `repeated apply reuses compiled patterns`() {
        // Soft sanity check that the lazy-compiled cache doesn't break
        // on multi-call. Functional only — we can't easily peek at the
        // cache without exposing it; assert that 1000 sentence calls
        // give consistent output.
        val dict = PronunciationDict(
            entries = listOf(PronunciationEntry(pattern = "Astaria", replacement = "ast")),
        )
        repeat(1000) {
            assertEquals("ast fell.", dict.apply("Astaria fell."))
        }
    }
}
