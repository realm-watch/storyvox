package `in`.jphe.storyvox.playback

import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine

/**
 * Issue #193 — bridge between storyvox's user-facing Settings toggle
 * and the two VoxSherpa-TTS engine classes' `sonicQuality` static
 * fields. Both engines instantiate a fresh `Sonic` per
 * `generateAudioPCM` call and read `sonicQuality` at construction
 * time, so flipping these fields takes effect on the *next* chapter
 * render (any in-flight render keeps its existing Sonic instance).
 *
 * Kept here in `:core-playback` rather than `:app` because the
 * VoxSherpa-TTS dep lives here. `:app`'s SettingsRepositoryUiImpl
 * routes through this bridge to avoid a direct compile-time
 * dependency on the Java engine classes.
 */
object VoiceEngineQualityBridge {

    /**
     * Apply the user's "high-quality pitch interpolation" preference
     * to both engines. true → quality=1 (smoother, ~20% slower);
     * false → quality=0 (Sonic's upstream default — virtually as
     * good at neutral pitch, gritty at non-neutral).
     */
    fun applyPitchQuality(highQuality: Boolean) {
        val q = if (highQuality) 1 else 0
        VoiceEngine.sonicQuality = q
        KokoroEngine.sonicQuality = q
    }
}
