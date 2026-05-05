package `in`.jphe.storyvox.playback

import androidx.media3.session.SessionToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge so that [auto.StoryvoxAutoBrowserService] can find the playback service's
 * session token without a direct service-to-service dependency. The playback service
 * writes the token on its onCreate; the auto browser reads it on demand.
 */
@Singleton
class MediaSessionLocator @Inject constructor() {
    @Volatile var token: SessionToken? = null
}
