package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * #90 — tracks whether the user's last play/pause action was "play"
 * (true) or "pause" (false). Library's Resume CTA reads this to
 * decide whether to auto-start playback or just navigate-and-stay-
 * paused.
 *
 * Reasoning: explicit pause is a user intent. If the app is killed
 * mid-playback (Android LMK, swipe-from-recents, etc.) the flag
 * stays at its last value — usually true (was playing) — so the
 * default "resume should auto-play" behavior holds for the common
 * "phone died and I'm continuing my book" case.
 *
 * Setter is called by `PlaybackController.pause()` (false) and
 * `PlaybackController.play()` / `resume()` (true). Reader is
 * `LibraryViewModel.resume()` and any other Continue-listening
 * surface (Auto/Wear/MediaSession reentry).
 *
 * Persisted in the same DataStore as the rest of the playback
 * config so we don't need a Room migration; mirrors the
 * [PlaybackBufferConfig] / [PlaybackModeConfig] / [AzureFallbackConfig]
 * pattern.
 */
interface PlaybackResumePolicyConfig {
    /** Hot flow of the flag. Default true = preserve pre-#90
     *  behavior on first install. */
    val lastWasPlaying: Flow<Boolean>

    /** Snapshot read at resume-tap time. */
    suspend fun currentLastWasPlaying(): Boolean

    /** Setter called by PlaybackController on every play/pause. */
    suspend fun setLastWasPlaying(playing: Boolean)
}
