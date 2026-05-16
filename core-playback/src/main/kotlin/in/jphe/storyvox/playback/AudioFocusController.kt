package `in`.jphe.storyvox.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #560 (stuck-state-fixer) — owner of the storyvox process's audio
 * focus lifecycle. Pre-fix EnginePlayer never called
 * [AudioManager.requestAudioFocus] for its TTS AudioTrack — on phones
 * with strict audio policy (Samsung Z Flip3 confirmed by the v0.5.57
 * audit), the AudioTrack write loop silently parks at the framework
 * level because the process has no focus claim. MediaSession heartbeats
 * still report `state=PLAYING` (the engine THINKS it's playing) but
 * `dumpsys audio` shows the focus stack empty and the AudioTrack
 * in `state:paused`. That's the audit's "silent stuck state" — the
 * Bug 1 root cause.
 *
 * Lifecycle:
 *  - `acquire()` before starting an AudioTrack write loop. Idempotent
 *    so a chapter advance (which rebuilds the pipeline) doesn't fight
 *    itself.
 *  - `abandon()` on user stop / app death so other media apps can
 *    resume.
 *  - The focus-change listener routes transient losses (a phone call,
 *    a notification ding) to a pause callback; transient-can-duck
 *    leaves us playing (matches Spotify / Pocket Casts behavior for
 *    speech). Full focus loss is treated as a user-equivalent pause.
 *
 * Why a separate Singleton: EnginePlayer is `@AssistedInject` (per-Service
 * instance), but the focus state is process-wide — Android maintains
 * one focus stack per UID, and `requestAudioFocus` deduplicates by
 * AudioFocusRequest object identity. Keeping the request object owned
 * by a Singleton makes the lifecycle predictable across service
 * restarts.
 *
 * API 26+: uses [AudioFocusRequest]. storyvox's minSdk is 26 (per AGP
 * config) so we only ship the modern path; the deprecated streamType-
 * based focus API is not wired here. If minSdk ever rises further, no
 * change needed.
 */
@Singleton
class AudioFocusController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Held so we abandon the SAME request object we acquired. Android's
     * focus stack is keyed on the AudioFocusRequest reference, so a new
     * Builder instance would be a no-op abandon. Single-shot per
     * acquire/abandon pair.
     */
    @Volatile
    private var currentRequest: AudioFocusRequest? = null

    /** Tracks whether we currently believe we hold focus. Distinct from
     *  the framework's view — flips on a successful acquire(), back off
     *  on abandon() or a focus-loss callback. The flag is the source of
     *  truth for idempotent acquire() calls. */
    private val held = AtomicBoolean(false)

    /**
     * Notification path for focus-change events that the engine should
     * react to. The controller doesn't pause the AudioTrack directly —
     * that's EnginePlayer's job. We just translate the framework's
     * focus-change codes into a single boolean intent ("the user / OS
     * effectively paused us; tear down or duck"). The hookup lives in
     * StoryvoxPlaybackService where EnginePlayer is constructed.
     */
    @Volatile
    private var onFocusLost: (() -> Unit)? = null

    fun setOnFocusLost(callback: (() -> Unit)?) {
        onFocusLost = callback
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app took permanent or transient focus — phone
                // call, video playback, etc. We're audiobook-style speech
                // (no ducking value); treat both as "pause and yield."
                // The framework reissues AUDIOFOCUS_GAIN when the
                // interruption ends; we DO NOT auto-resume on gain —
                // the user can hit play. This matches Spotify's behavior
                // for podcasts (also speech).
                Log.i(LOG_TAG, "focus lost ($focusChange) — yielding")
                held.set(false)
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // A notification ding-or-similar. Audiobook listeners
                // get more value from continued playback than from
                // ducking (the alternative is dead air for 0.5-1.5 s).
                // Stay playing; the system mixes our output below the
                // intruder. No-op.
                Log.i(LOG_TAG, "focus duck — staying loud (speech)")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Framework returned focus to us. We DO NOT auto-resume
                // — the user explicitly opted out by losing focus
                // earlier (or the OS did it for them); requiring a tap
                // is the audiobook-correct UX.
                Log.i(LOG_TAG, "focus regained — not auto-resuming")
                held.set(true)
            }
        }
    }

    /**
     * Request transient-can-duck-allowed focus for media playback.
     * Returns true if focus was granted (or already held), false if the
     * framework rejected the request. The engine should NOT start its
     * AudioTrack write loop on a `false` return — that's the
     * pre-existing silent-stuck state.
     *
     * Idempotent: a second `acquire()` while focus is held is a no-op.
     */
    fun acquire(): Boolean {
        val am = audioManager ?: run {
            Log.w(LOG_TAG, "AudioManager unavailable — assuming focus granted")
            held.set(true)
            return true
        }
        if (held.get() && currentRequest != null) {
            // Already holding focus from an earlier acquire(). Avoid the
            // duplicate request — the framework would just re-emit GAIN
            // and we'd return true anyway, but the log noise is
            // confusing during chapter transitions.
            return true
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // CONTENT_TYPE_SPEECH would tell the system this is
                    // narration — better routing on some devices for
                    // Bluetooth headsets that have a speech bias. But on
                    // Samsung firmware the speech path also activates
                    // SoundAlive speech-DSP that fights our voice
                    // engine's already-correct pitch. CONTENT_TYPE_MUSIC
                    // matches what the AudioTrack itself advertises (see
                    // EnginePlayer.createAudioTrack) so the focus
                    // attributes don't disagree with the track-level
                    // attributes; consistent attributes minimize routing
                    // re-decisions mid-stream.
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setWillPauseWhenDucked(false) // see ducking comment above
            .build()
        val result = am.requestAudioFocus(request)
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                Log.i(LOG_TAG, "focus granted")
                currentRequest = request
                held.set(true)
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                Log.w(LOG_TAG, "focus request FAILED — another app holds it; pipeline will be silent")
                false
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // We asked for non-delayed grant (setAcceptsDelayedFocusGain(false))
                // so this branch shouldn't fire. Log defensively in case
                // a future SDK changes the contract.
                Log.w(LOG_TAG, "focus request DELAYED — treating as denied")
                false
            }
            else -> {
                Log.w(LOG_TAG, "focus request unknown result=$result")
                false
            }
        }
    }

    /**
     * Release focus so other media apps can resume. Safe to call
     * repeatedly. Should be paired with each successful [acquire].
     */
    fun abandon() {
        val am = audioManager ?: return
        val request = currentRequest ?: return
        runCatching { am.abandonAudioFocusRequest(request) }
            .onFailure { Log.w(LOG_TAG, "abandon threw: ${it.message}") }
        currentRequest = null
        held.set(false)
    }

    /** True if we currently believe we hold focus. Read from the engine
     *  to short-circuit a pipeline start when focus was denied. */
    fun isHeld(): Boolean = held.get()

    companion object {
        private const val LOG_TAG = "AudioFocusController"
    }
}
