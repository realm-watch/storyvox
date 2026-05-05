package `in`.jphe.storyvox.playback.tts

import java.text.BreakIterator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class Sentence(
    val index: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
)

/**
 * Splits chapter text into sentence-sized utterances using ICU's [BreakIterator].
 * Sentences exceeding [maxUtteranceChars] (engine limit ~4000 by default) are
 * subdivided at clause boundaries; the [SentenceTracker] reassembles parent
 * ranges from sub-utterance ids.
 */
@Singleton
class SentenceChunker @Inject constructor() {

    fun chunk(text: String, locale: Locale = Locale.getDefault()): List<Sentence> {
        if (text.isEmpty()) return emptyList()
        val iter = BreakIterator.getSentenceInstance(locale)
        iter.setText(text)

        val out = mutableListOf<Sentence>()
        var start = iter.first()
        var idx = 0
        while (true) {
            val end = iter.next()
            if (end == BreakIterator.DONE) break
            val raw = text.substring(start, end)
            val trimmedStart = start + raw.indexOfFirst { !it.isWhitespace() }
                .takeIf { it >= 0 }.let { it ?: 0 }
            val sentenceText = raw.trim()
            if (sentenceText.isNotEmpty()) {
                out += Sentence(
                    index = idx++,
                    startChar = trimmedStart,
                    endChar = trimmedStart + sentenceText.length,
                    text = sentenceText,
                )
            }
            start = end
        }
        return out
    }

    fun utteranceId(sentenceIndex: Int, subIndex: Int = 0): String =
        "s${sentenceIndex}_p$subIndex"

    fun parseSentenceIndex(utteranceId: String): Int? =
        utteranceId.removePrefix("s").substringBefore("_").toIntOrNull()
}
