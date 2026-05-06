package `in`.jphe.storyvox.playback.tts

import android.speech.tts.UtteranceProgressListener
import `in`.jphe.storyvox.playback.SentenceRange

/**
 * Bridges [UtteranceProgressListener] callbacks into per-sentence state updates.
 *
 * Engines vary in `onRangeStart` support — some emit per-word ranges, some never.
 * [hasReceivedRange] flips true on the first `onRangeStart`; if it stays false, we
 * fall back to whole-sentence highlighting via `onStart`. Either way the reader UI
 * gets a [SentenceRange].
 *
 * Also detects VoxSherpa's "silent speed-run" bug — when the engine's audio stream
 * is interrupted (incoming call, audio focus loss, another TTS engine grabbing the
 * output), it can keep firing onStart/onDone callbacks without actually emitting
 * audio. The reader's brass underline rockets through the chapter and the user
 * hears nothing. We watch the wall-clock interval between successive onStart calls;
 * three sub-[SPEED_RUNNER_FLOOR_MS]ms gaps in a row triggers [onSpeedRunnerDetected]
 * so the player can pause and recover.
 */
class SentenceTracker(
    private val sentences: List<Sentence>,
    private val onSentence: (SentenceRange) -> Unit,
    private val onChapterDone: () -> Unit,
    private val onErrorEmitted: (utteranceId: String, errorCode: Int) -> Unit,
    private val parseIndex: (String) -> Int?,
    private val onSpeedRunnerDetected: () -> Unit = {},
) : UtteranceProgressListener() {

    @Volatile var hasReceivedRange: Boolean = false
        private set

    @Volatile private var lastStartAtMs: Long = 0L
    @Volatile private var fastStartStreak: Int = 0

    override fun onStart(utteranceId: String) {
        val now = System.currentTimeMillis()
        if (lastStartAtMs > 0L && now - lastStartAtMs < SPEED_RUNNER_FLOOR_MS) {
            fastStartStreak += 1
            if (fastStartStreak >= SPEED_RUNNER_TRIGGER) {
                fastStartStreak = 0
                lastStartAtMs = 0L
                onSpeedRunnerDetected()
                return
            }
        } else {
            fastStartStreak = 0
        }
        lastStartAtMs = now

        val idx = parseIndex(utteranceId) ?: return
        val s = sentences.getOrNull(idx) ?: return
        onSentence(SentenceRange(s.index, s.startChar, s.endChar))
    }

    override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
        hasReceivedRange = true
        val idx = parseIndex(utteranceId) ?: return
        val s = sentences.getOrNull(idx) ?: return
        onSentence(
            SentenceRange(
                sentenceIndex = s.index,
                startCharInChapter = s.startChar + start,
                endCharInChapter = s.startChar + end,
            ),
        )
    }

    override fun onDone(utteranceId: String) {
        val idx = parseIndex(utteranceId) ?: return
        if (idx == sentences.lastIndex) onChapterDone()
    }

    @Deprecated("Use the (utteranceId, errorCode) overload")
    override fun onError(utteranceId: String) {
        onErrorEmitted(utteranceId, -1)
    }

    override fun onError(utteranceId: String, errorCode: Int) {
        onErrorEmitted(utteranceId, errorCode)
    }

    private companion object {
        /** Floor below which a sentence couldn't have been spoken aloud — even
         *  at max 3.0× speed and a tiny utterance, real audio takes longer than
         *  this. Sub-floor onStart→onStart gaps mean the engine is dry-running. */
        const val SPEED_RUNNER_FLOOR_MS = 250L
        /** Number of consecutive sub-floor gaps before we flag dry-run. Tolerates
         *  one or two transients without false-positive on quirky engines. */
        const val SPEED_RUNNER_TRIGGER = 3
    }
}
