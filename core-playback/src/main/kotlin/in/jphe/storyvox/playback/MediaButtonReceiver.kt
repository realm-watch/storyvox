package `in`.jphe.storyvox.playback

import androidx.media3.common.util.UnstableApi

/**
 * Subclass of media3's [androidx.media3.session.MediaButtonReceiver]. The base class
 * already routes media-button broadcasts to the bound [MediaSessionService]; we extend
 * only so we have a stable class name to declare in the manifest receiver. The actual
 * multi-tap logic lives in [MediaButtonHandler] inside [StoryvoxSessionCallback].
 */
@UnstableApi
class MediaButtonReceiver : androidx.media3.session.MediaButtonReceiver()
