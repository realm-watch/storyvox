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
 */
class SentenceTracker(
    private val sentences: List<Sentence>,
    private val onSentence: (SentenceRange) -> Unit,
    private val onChapterDone: () -> Unit,
    private val onErrorEmitted: (utteranceId: String, errorCode: Int) -> Unit,
    private val parseIndex: (String) -> Int?,
) : UtteranceProgressListener() {

    @Volatile var hasReceivedRange: Boolean = false
        private set

    override fun onStart(utteranceId: String) {
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
}
