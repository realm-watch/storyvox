package `in`.jphe.storyvox.data.repository.pronunciation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * User-edited list of text-substitution rules applied to TTS input
 * between sentence chunking and `engine.generateAudioPCM(...)`.
 *
 * Why this exists (issue #135): serial-fiction proper nouns are
 * uniquely brutal to neural TTS. Royal Road authors invent dozens of
 * names per fiction (Astaria, Kuroinu, Vessari, …) and Piper/Kokoro
 * mispronounce most of them on first read. The pronunciation dict lets
 * the user spell a name phonetically for the engine without changing
 * the displayed sentence text — `Sentence.text` (which the highlight
 * uses for character ranges) is left alone; only the `String` passed
 * into `generateAudioPCM` is rewritten.
 *
 * Match modes (#135 + #199): [MatchType.WORD] (whole-word, the default)
 * and [MatchType.REGEX] (raw Java regex) shipped in Phase 1; Phase 2
 * adds [MatchType.ANYWHERE], [MatchType.START_OF_WORD],
 * [MatchType.END_OF_WORD], and [MatchType.GLOB] (NVDA-style `*`/`?`
 * glob, internally compiled to a regex). Five modes total — the
 * "part-of-word" combinator from NVDA is dropped because START + END
 * cover its behavior asymmetrically and adding it would create three
 * adjacent picker entries that confuse rather than clarify.
 *
 * Inspiration:
 *  - **epub_to_audiobook** (p0n1, MIT) —
 *    `audiobook_generator/book_parsers/epub_book_parser.py:75-78`
 *    introduced the per-line `search==replace` substitution loop that
 *    proves this is the correct seam for fixing pronunciations on a
 *    neural TTS pipeline. ~3 LOC of regex.
 *  - **NVDA SpeechDictHandler** (NV Access, GPL-2-or-later, compatible
 *    with our GPL-3) — `source/speechDictHandler/types.py:117-171` is
 *    the canonical compile-once-apply-many model for screen-reader
 *    speech dicts; `:248-257` is the invalid-entry-skip pattern that
 *    keeps a single broken regex from blowing up the whole list. Both
 *    patterns are mirrored below.
 */
@Serializable
data class PronunciationEntry(
    /** Source string. For [MatchType.WORD] this is matched as a whole
     *  word with `\b…\b` boundaries; for [MatchType.REGEX] it's the raw
     *  Java regex pattern as the user typed it. Empty pattern is
     *  treated as invalid (skipped on compile). */
    val pattern: String,
    /** Replacement text passed straight to `Matcher.replaceAll`. Java
     *  regex backreferences (`$1`, `\\$`) work in REGEX mode; for WORD
     *  mode the typical use is a literal phonetic spelling. */
    val replacement: String,
    /** Match mode — see [MatchType]. */
    val matchType: MatchType = MatchType.WORD,
    /** Case-sensitive matching. Default false (mirrors NVDA's
     *  default-WORD-mode behavior — users want "Astaria" and "astaria"
     *  fixed by the same entry). */
    val caseSensitive: Boolean = false,
)

@Serializable
enum class MatchType {
    /** Whole-word match. Pattern is regex-quoted (so `.` and `?` in the
     *  user's input stay literal) then wrapped with `\b…\b`. Matches
     *  NVDA's `WORD` entry type. The 90% case for proper-noun fixes. */
    WORD,
    /** Substring match — pattern matches anywhere in the text. Useful
     *  for "fix every occurrence of 'lvl' to 'level'" without word
     *  boundaries. Pattern is regex-quoted; mirrors NVDA's `ANYWHERE`. */
    ANYWHERE,
    /** Pattern matches when it appears at the start of a word (after
     *  `\b` left, no boundary right). Use case: "every word starting
     *  with 'Astar'" pronounced as a prefix block. */
    START_OF_WORD,
    /** Pattern matches when it appears at the end of a word (no
     *  boundary left, `\b` right). Use case: "every plural 'aria'
     *  ending pronounced ah-ree-ah". */
    END_OF_WORD,
    /** NVDA-style glob. `*` matches any run of characters, `?` matches
     *  exactly one character; everything else is literal. Compiled
     *  internally to a regex with `\b…\b` boundaries to keep behavior
     *  word-shaped (the WORD case generalised). */
    GLOB,
    /** Power-user mode — `pattern` is compiled as a raw Java regex.
     *  Backreferences in `replacement` work as in `Matcher.replaceAll`.
     *  Bad patterns are dropped on compile; the rest of the list still
     *  applies (NVDA invalid-entry-skip). */
    REGEX,
}

/**
 * The full dictionary the user has configured. Phase 1 has only one
 * scope — the global dict. Per-fiction overrides land in phase 2.
 *
 * Two derived values:
 *  - [contentHash]: stable hash over the `entries` list, intended for
 *    `PcmCacheKey.pronunciationDictHash` so a dictionary change
 *    self-evicts the affected on-disk PCM caches without a manual
 *    sweep.
 *  - [apply]: substitution pass over a sentence string. Compiles each
 *    entry's pattern lazily on first call and caches the
 *    `(Pattern, replacement)` pair so `apply` over hundreds of
 *    sentences in a chapter is one compile per entry, not per call.
 */
@Serializable
data class PronunciationDict(
    val entries: List<PronunciationEntry> = emptyList(),
) {
    /** Sentinel for the empty dict — repositories return this when
     *  DataStore has no value stored yet. */
    companion object {
        val EMPTY: PronunciationDict = PronunciationDict()
    }

    /**
     * Stable hash of the entries list — fed into [`PcmCacheKey.pronunciationDictHash`]
     * so adding/editing/removing an entry shifts every chapter's cache
     * key and the existing on-disk render orphans. The dict's own
     * `hashCode()` is fine — the data class's auto-generated hash
     * already covers `entries`, and `String`/`enum`/`Boolean` field
     * hashes are stable across JVM runs. We surface this under a
     * named property so callers don't take a dependency on the
     * synthetic `equals`/`hashCode` shape.
     */
    val contentHash: Int get() = entries.hashCode()

    /**
     * Run every valid entry over [text], returning the rewritten
     * string. Order is the order of [entries] — later entries see the
     * output of earlier ones, mirroring epub_to_audiobook's `for
     * (search, replace) in mapping: text = re.sub(search, replace, text)`.
     *
     * Invalid entries (bad regex, empty pattern) are silently dropped
     * — the user will see them disappear from the editor on next load
     * because the repository round-trips through the same compile, but
     * a single broken row never breaks the rest of the list. NVDA's
     * `types.py:248-257` is the same pattern.
     */
    fun apply(text: String): String {
        if (text.isEmpty() || compiled.isEmpty()) return text
        var s = text
        for ((pattern, replacement) in compiled) {
            s = try {
                pattern.matcher(s).replaceAll(replacement)
            } catch (_: IndexOutOfBoundsException) {
                // Replacement string referenced a nonexistent group.
                // Drop this entry's effect on this sentence; keep going.
                s
            } catch (_: IllegalArgumentException) {
                // Malformed `\` or `$` escape in replacement. Same
                // recovery — skip this entry, keep the chain alive.
                s
            }
        }
        return s
    }

    /** Lazily-compiled (Pattern, replacement) pairs. Computed on the
     *  first [apply] call and cached for the lifetime of this dict
     *  instance — every subsequent sentence in the chapter (typically
     *  hundreds) reuses the same compiled patterns. Body-property (not
     *  in the constructor) so kotlinx.serialization ignores it
     *  automatically; only @Serializable constructor params are
     *  serialized. */
    private val compiled: List<Pair<Pattern, String>> by lazy { compileEntries(entries) }
}

/**
 * JSON codec for [PronunciationDict]. Owned here in :core-data
 * (alongside the data class + the kotlinx-serialization plugin
 * that compiles its `serializer()`) so callers in modules without
 * the plugin (`:app`) can still round-trip the dict through their
 * DataStore values via [encodeJson]/[decodeJson]. Both helpers are
 * crash-proof: encoding a freshly-constructed dict can't fail with
 * the auto-generated serializer, and decoding falls back to
 * [PronunciationDict.EMPTY] for any malformed input — see
 * [decodeJson] for the reasoning.
 */
private val PronunciationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Serialize [dict] to a JSON string suitable for storage in
 *  DataStore-Preferences (a flat string-keyed bag) or any other
 *  string-shaped settings store. */
fun encodePronunciationDictJson(dict: PronunciationDict): String =
    PronunciationJson.encodeToString(PronunciationDict.serializer(), dict)

/**
 * Parse a JSON blob produced by [encodePronunciationDictJson]. Returns
 * [PronunciationDict.EMPTY] for `null`, blank input, or any parse
 * failure — a corrupted blob (e.g. a forward-rolled payload from a
 * future schema an older binary can't read) shouldn't crash the
 * player; the user sees their dictionary "reset" and can re-enter
 * it. The corrupt blob stays on disk until the next write so a
 * downgrade-then-upgrade path preserves user data.
 */
fun decodePronunciationDictJson(blob: String?): PronunciationDict {
    if (blob.isNullOrBlank()) return PronunciationDict.EMPTY
    return runCatching {
        PronunciationJson.decodeFromString(PronunciationDict.serializer(), blob)
    }.getOrDefault(PronunciationDict.EMPTY)
}

/**
 * Compile each entry to a `(Pattern, replacement)` pair, dropping
 * entries that fail to compile (bad regex, empty pattern). Public
 * (internal-package) so the repository can run the same pass during
 * load to filter out stale broken entries that survived a save.
 *
 * Mirrors NVDA's `SpeechDictEntry.__post_init__` + `_compile_pattern`
 * (`speechDictHandler/types.py:117-171`) — invariant per entry:
 * pattern goes through `Pattern.compile`, errors are swallowed +
 * logged, the entry is excluded from the output list.
 */
internal fun compileEntries(
    entries: List<PronunciationEntry>,
): List<Pair<Pattern, String>> {
    if (entries.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Pattern, String>>(entries.size)
    for (e in entries) {
        if (e.pattern.isEmpty()) continue
        val raw = when (e.matchType) {
            // Quote so `.`, `*`, `?`, etc. in the user's word stay
            // literal; \b…\b makes it a whole-word match. Java's
            // `\b` is Unicode-aware under Pattern.UNICODE_CHARACTER_CLASS,
            // which we set below so non-ASCII proper nouns (the entire
            // point of the feature) match correctly.
            MatchType.WORD -> "\\b" + Pattern.quote(e.pattern) + "\\b"
            MatchType.ANYWHERE -> Pattern.quote(e.pattern)
            MatchType.START_OF_WORD -> "\\b" + Pattern.quote(e.pattern)
            MatchType.END_OF_WORD -> Pattern.quote(e.pattern) + "\\b"
            MatchType.GLOB -> "\\b" + globToRegex(e.pattern) + "\\b"
            MatchType.REGEX -> e.pattern
        }
        val flags = (if (e.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) or
            Pattern.UNICODE_CHARACTER_CLASS
        val compiled = try {
            Pattern.compile(raw, flags)
        } catch (_: PatternSyntaxException) {
            // User typed broken regex (REGEX mode) or — extremely
            // unlikely — Pattern.quote produced something invalid. Drop
            // this entry; the rest of the list still applies.
            continue
        }
        out.add(compiled to e.replacement)
    }
    return out
}

/**
 * Convert NVDA-style glob (`*` = any run, `?` = single char) into a
 * Java regex. Everything else is treated as a literal — the user's
 * input is regex-quoted in chunks separated by the wildcard chars.
 *
 * Empty input yields an empty regex (callers add the `\b…\b` boundary
 * outside, so an empty glob becomes `\b\b` which matches nothing —
 * intentional, mirrors NVDA's behavior on a blank GLOB pattern).
 */
internal fun globToRegex(glob: String): String {
    if (glob.isEmpty()) return ""
    val sb = StringBuilder(glob.length + 8)
    val chunk = StringBuilder()
    fun flushChunk() {
        if (chunk.isNotEmpty()) {
            sb.append(Pattern.quote(chunk.toString()))
            chunk.clear()
        }
    }
    for (c in glob) {
        when (c) {
            '*' -> { flushChunk(); sb.append(".*") }
            '?' -> { flushChunk(); sb.append('.') }
            else -> chunk.append(c)
        }
    }
    flushChunk()
    return sb.toString()
}
