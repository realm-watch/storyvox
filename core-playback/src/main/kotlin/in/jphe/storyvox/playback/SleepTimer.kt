package `in`.jphe.storyvox.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface VolumeRamp {
    /** v in [0f, 1f]. Applied at next sentence boundary. */
    fun set(v: Float)
}

@Singleton
class SleepTimer @Inject constructor(
    private val volumeRamp: VolumeRamp,
    private val pauseAction: PauseAction,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private val chapterEndSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val chapterEnd: SharedFlow<Unit> = chapterEndSignal.asSharedFlow()

    /** Called by [tts.SentenceTracker] when the last sentence of a chapter finishes. */
    fun signalChapterEnd() {
        chapterEndSignal.tryEmit(Unit)
    }

    fun start(mode: SleepTimerMode) {
        cancel()
        job = scope.launch {
            when (mode) {
                is SleepTimerMode.Duration -> runCountdown(mode.minutes * 60_000L)
                SleepTimerMode.EndOfChapter -> {
                    chapterEndSignal.first()
                    fadeAndPause()
                }
            }
        }
    }

    private suspend fun runCountdown(totalMs: Long) {
        var remaining = totalMs
        while (remaining > FADE_TAIL_MS) {
            _remainingMs.value = remaining
            delay(TICK_MS)
            remaining -= TICK_MS
        }
        fadeAndPause()
    }

    private suspend fun fadeAndPause() {
        val steps = (FADE_TAIL_MS / FADE_STEP_MS).toInt()
        for (step in 0..steps) {
            val v = (steps - step).toFloat() / steps
            volumeRamp.set(v)
            _remainingMs.value = ((steps - step) * FADE_STEP_MS).toLong()
            delay(FADE_STEP_MS)
        }
        pauseAction.invoke()
        volumeRamp.set(1.0f)
        _remainingMs.value = null
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
        volumeRamp.set(1.0f)
    }

    fun interface PauseAction {
        operator fun invoke()
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val FADE_TAIL_MS = 10_000L
        private const val FADE_STEP_MS = 100L
    }
}
