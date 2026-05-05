package `in`.jphe.storyvox.playback

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackState(
    val currentFictionId: String? = null,
    val currentChapterId: String? = null,
    val charOffset: Int = 0,
    val durationEstimateMs: Long = 0L,
    val isPlaying: Boolean = false,
    val currentSentenceRange: SentenceRange? = null,
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sleepTimerRemainingMs: Long? = null,
    val bookTitle: String? = null,
    val chapterTitle: String? = null,
    val coverUri: String? = null,
    val error: PlaybackError? = null,
)

@Serializable
data class SentenceRange(
    val sentenceIndex: Int,
    val startCharInChapter: Int,
    val endCharInChapter: Int,
)

@Serializable
sealed class PlaybackError {
    @Serializable data object EngineUnavailable : PlaybackError()
    @Serializable data class ChapterFetchFailed(val message: String) : PlaybackError()
    @Serializable data class TtsSpeakFailed(val utteranceId: String, val errorCode: Int) : PlaybackError()
}

sealed class SleepTimerMode {
    data class Duration(val minutes: Int) : SleepTimerMode()
    data object EndOfChapter : SleepTimerMode()
}

sealed class PlaybackUiEvent {
    data object BookFinished : PlaybackUiEvent()
    data class ChapterChanged(val chapterId: String) : PlaybackUiEvent()
    data class EngineMissing(val installUrl: String) : PlaybackUiEvent()
}

fun PlaybackState.scrubProgress(): Float {
    if (durationEstimateMs <= 0L) return 0f
    val totalChars = (durationEstimateMs.toFloat() / 1000f) *
        SPEED_BASELINE_CHARS_PER_SECOND * speed
    if (totalChars <= 0f) return 0f
    return (charOffset / totalChars).coerceIn(0f, 1f)
}

const val SPEED_BASELINE_WPM = 150f
const val SPEED_BASELINE_CHARS_PER_SECOND = SPEED_BASELINE_WPM * 5f / 60f
