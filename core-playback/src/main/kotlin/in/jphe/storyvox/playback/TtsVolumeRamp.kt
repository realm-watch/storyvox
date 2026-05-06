package `in`.jphe.storyvox.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [VolumeRamp] impl. Stores the volume target so the active
 * [tts.EnginePlayer] can read and apply it via AudioTrack.setVolume.
 *
 * The EnginePlayer is a Singleton-but-Assisted construction (created by the
 * service lifecycle), so this ramp acts as a shared register both sides poll.
 */
@Singleton
class TtsVolumeRamp @Inject constructor() : VolumeRamp {

    @Volatile
    var current: Float = 1.0f
        private set

    override fun set(v: Float) {
        current = v.coerceIn(0f, 1f)
    }
}
