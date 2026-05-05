package `in`.jphe.storyvox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository

/**
 * Periodic worker that re-validates the captured WebView session. On
 * [SessionState.Expired], the app module is responsible for posting a
 * notification deep-linking the user back into the WebView re-auth flow.
 */
@HiltWorker
class SessionRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: AuthRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val state = auth.verifyOrExpire()
        val tag = when (state) {
            is SessionState.Authenticated -> "authenticated"
            SessionState.Expired -> "expired"
            SessionState.Anonymous -> "anonymous"
        }
        return Result.success(Data.Builder().putString(KEY_STATE, tag).build())
    }

    companion object {
        const val TAG = "auth:session-refresh"
        const val UNIQUE_NAME = "auth:session-refresh"
        const val KEY_STATE = "state"
    }
}
