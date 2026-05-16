package `in`.jphe.storyvox.playback.diagnostics

/**
 * "Why is no audio coming out of the speakers right now?" — the user-facing
 * answer the reader UI surfaces in a magical brass panel above the cover.
 *
 * The contract: [AudioOutputMonitor] derives a [WaitReason] every ~200 ms
 * while playback is active, by reading the engine's truthful audio
 * position alongside [in.jphe.storyvox.playback.AudioFocusController] state
 * and [android.media.AudioManager] route/volume signals. When the user
 * hears audio (head-position advancing), the monitor exposes `null`. The
 * UI's `WhyAreWeWaitingPanel` listens to the resulting StateFlow and
 * fades in / out accordingly.
 *
 * Why a sealed interface (not enum): each variant carries different
 * structured payload (voice name, seconds-waiting, queue depth) that
 * drives the magical panel's secondary line + optional retry chip. An
 * enum would force every consumer to look the payload up in a side map,
 * which is exactly the silent-stuck-state class of bug we're killing.
 *
 * "EVERY time, beautifully" — the catch-all [AudioOutputStuck] guarantees
 * that any silence period falls into a typed reason rather than an empty
 * panel. The UI's invariant is "if the engine is not Playing, the panel
 * MUST be visible with a reason".
 */
sealed interface WaitReason {
    /** Render-ready primary headline ("Warming Brian…", "Phone call has
     *  audio focus", "Volume is muted"). EB Garamond 18sp brass in the
     *  Library Nocturne panel. */
    val message: String

    /** Optional progress 0..1 — drives the thin brass progress bar
     *  beneath the headline. `null` when progress is unknowable (e.g.
     *  sherpa-onnx voice loads silently). */
    val progressFraction: Float?

    /** When true, the panel renders a "Retry" chip at the bottom right.
     *  Tapping it dispatches the same code path that the user's manual
     *  play tap would (the actual wire-up lives in the panel — the
     *  reason itself just advertises retryability). */
    val isRecoverable: Boolean

    /** When true, the panel surfaces a chip inviting user action (Retry,
     *  Open Settings, etc.). [isRecoverable] implies this; some reasons
     *  are user-actionable without being mid-failure (e.g. DeviceMuted
     *  → "Open volume controls"). */
    val isUserActionable: Boolean

    /**
     * Sherpa-onnx (or piper, or Azure) is loading the voice model + first
     * inference. Typical 1-15 s depending on tier; the longest tier-3
     * Kokoro voices can run 8-12 s on a Tab A7 Lite cold start.
     */
    data class WarmingVoice(
        val voiceName: String,
        val progress: Float? = null,
    ) : WaitReason {
        // Plain-English headline (#606, v1.0). The voice display name
        // is interpolated so the user sees the voice they actually
        // picked ("Warming up Brian's voice…") rather than a generic
        // "Warming voice" or an engine identifier. Phrasing matches
        // how a person would describe what's happening to a friend.
        override val message: String get() = "Warming up $voiceName's voice. This happens once per session."
        override val progressFraction: Float? get() = progress
        override val isRecoverable: Boolean get() = false
        override val isUserActionable: Boolean get() = false
    }

    /**
     * Chapter body fetch hasn't completed yet — typically a Notion /
     * Royal Road / EPUB body roundtrip. The TTS pipeline can't render
     * anything because there's no text to feed it.
     */
    data class LoadingChapter(val chapterTitle: String) : WaitReason {
        // Plain-English (#606). Drops the per-chapter title from the
        // headline so the panel doesn't fill the whole top edge with
        // a 60-character "Loading <very long chapter title>" line;
        // the chapter title is already visible elsewhere on the
        // reader. "Loading the chapter." reads as a single thought.
        override val message: String get() = "Loading the chapter."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = false
        override val isUserActionable: Boolean get() = false
    }

    /**
     * Mid-chapter underrun: the AudioTrack drained faster than the
     * producer could refill the sentence queue. The pipeline is alive;
     * we're waiting for the next sentence's PCM to land.
     */
    data class BufferingNextSentence(val queueDepth: Int) : WaitReason {
        // Plain-English (#606). "Buffering" is engineering jargon —
        // a 5-year-old hears "loading" but not "buffering". "The
        // next bit of audio" is more concrete than "next sentence";
        // it's also true (the chunk in flight is usually a phrase,
        // not always a full sentence).
        override val message: String get() = "Loading the next bit of audio."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = false
        override val isUserActionable: Boolean get() = false
    }

    /**
     * Chapter source is a network endpoint and the fetch is taking too
     * long (>3 s). Surfaced separately from [LoadingChapter] so the panel
     * can show "Network is slow — N s elapsed" rather than a generic
     * loading message.
     */
    data class NetworkSlow(val secondsWaiting: Int) : WaitReason {
        // Plain-English (#606). Drops the "(N s)" parenthetical from
        // the headline — the count adds technical clutter without
        // helping a user decide what to do. "Internet is slow. Hang
        // tight." is two short sentences anyone can parse, including
        // a child or a TalkBack listener.
        override val message: String get() = "Internet is slow. Hang tight."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = true
        override val isUserActionable: Boolean get() = true
    }

    /**
     * Another app or system service holds audio focus — a phone call, an
     * alarm, a video in another app, the assistant. The framework's
     * `AUDIOFOCUS_LOSS*` code translates to a human-readable cause via
     * the constructor argument.
     */
    data class FocusLost(val cause: String) : WaitReason {
        // Plain-English (#606). "Paused for a call" assumes the
        // common case (the focus-loss cause is overwhelmingly a
        // phone call or alarm); "will resume when you're done" tells
        // the user what happens next, which is the question they
        // actually have when audio drops out mid-chapter. The
        // original [cause] argument is retained as part of the data
        // class so future variants (assistant invocation, video in
        // another app) can branch on it; for v1.0 we keep the
        // headline simple.
        override val message: String get() = "Paused for a call — will resume when you're done."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = true
        override val isUserActionable: Boolean get() = true
    }

    /**
     * Output route changed (headphones plugged/unplugged, Bluetooth
     * connect/disconnect). The framework typically auto-pauses on a
     * disconnect; on connect the user often expects a re-play.
     */
    data object AudioRouteChange : WaitReason {
        // Plain-English (#606). Adds the trailing period for parity
        // with the other reason headlines — they all read as complete
        // sentences now, which TalkBack pauses cleanly between.
        override val message: String get() = "Audio output changed."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = true
        override val isUserActionable: Boolean get() = true
    }

    /** Device volume is at 0 or the music stream is system-muted. */
    data object DeviceMuted : WaitReason {
        // Plain-English (#606). "Your phone is muted" is concrete
        // (the user knows what "phone" means; "device" reads as
        // engineering speak). "Turn up volume to hear" tells them
        // exactly what to do — the panel's chip still surfaces the
        // Open-settings affordance for users on the Volume / Do Not
        // Disturb permissions case.
        override val message: String get() = "Your phone is muted. Turn up volume to hear."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = false
        override val isUserActionable: Boolean get() = true
    }

    /** A voice model download failed (404, network, OOM). */
    data class VoiceDownloadFailed(val voiceName: String) : WaitReason {
        // Plain-English (#606). Adds "Tap to try again." inline so
        // the headline names the recovery action even on a TalkBack
        // user's first sweep through the panel — they don't have to
        // hunt for the chip to know what to do.
        override val message: String get() = "Couldn't download $voiceName. Tap to try again."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = true
        override val isUserActionable: Boolean get() = true
    }

    /**
     * Catch-all. The engine claims it's playing, but no PCM has reached
     * the speakers for [secondsSilent] seconds. We don't know why; the
     * UI surfaces this so the user is never left wondering — "EVERY
     * time, beautifully". After ~10 s of this state, the user can tap
     * the retry chip to kick the pipeline.
     */
    data class AudioOutputStuck(val secondsSilent: Int) : WaitReason {
        // Plain-English (#606). Below the 4 s threshold the panel is
        // a friendly "we're listening" hint; once we cross 4 s
        // without audio the headline shifts to "Reconnecting" so the
        // user knows we're actively re-arming the pipeline rather
        // than hung. Drops the "$secondsSilent s" parenthetical —
        // the user doesn't need the count, they need to know
        // someone's working on it.
        override val message: String
            get() = if (secondsSilent < 4) "Listening for the first words."
            else "Reconnecting — please wait."
        override val progressFraction: Float? get() = null
        override val isRecoverable: Boolean get() = secondsSilent >= 4
        override val isUserActionable: Boolean get() = secondsSilent >= 4
    }
}
