package `in`.jphe.storyvox

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import `in`.jphe.storyvox.BuildConfig
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class StoryvoxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sessionHydrator: SessionHydrator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        workScheduler.ensurePeriodicWorkScheduled()
        rehydrateRoyalRoadCookies()
    }

    /**
     * The OkHttp cookie jar is in-memory; persisted cookies live in
     * [AuthRepository] (EncryptedSharedPreferences). On every cold start we
     * pull the saved Cookie header back into the live jar so the next browse
     * / chapter / follows fetch is authed without making the user re-sign-in.
     */
    private fun rehydrateRoyalRoadCookies() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val header = authRepository.cookieHeader() ?: return@launch
            // Cookie header is "name1=value1; name2=value2; ..." — parse back
            // into the Map<String,String> shape SessionHydrator expects.
            val cookies = header.split("; ")
                .mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
                }
                .toMap()
            if (cookies.isNotEmpty()) sessionHydrator.hydrate(cookies)
        }
    }
}
