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
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class StoryvoxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sessionHydrator: SessionHydrator
    @Inject lateinit var syncCoordinator: SyncCoordinator
    @Inject lateinit var settingsRepo: SettingsRepositoryUi

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        workScheduler.ensurePeriodicWorkScheduled()
        rehydrateRoyalRoadCookies()
        applyPitchQualityFromSettings()
        applyPerVoiceEngineKnobsFromSettings()
        // InstantDB sync — if a refresh token is stored, validate it and
        // pull every per-domain syncer. No-op when no one is signed in.
        // Fire-and-forget: the coordinator launches its own coroutines.
        syncCoordinator.initialize()
    }

    /**
     * Issue #193 — seed the VoxSherpa engine static `sonicQuality`
     * fields from the user's persisted Settings toggle. The field
     * default in VoxSherpa-TTS v2.7.13 is 1 (high quality), but a
     * user who flipped to OFF in a prior session needs that pref
     * re-applied on cold start, before the first chapter renders.
     */
    private fun applyPitchQualityFromSettings() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val highQuality = settingsRepo.settings.first().pitchInterpolationHighQuality
            VoiceEngineQualityBridge.applyPitchQuality(highQuality)
        }
    }

    /**
     * Issues #197 + #198 — seed the VoxSherpa engine static fields
     * `voiceLexicon` and `phonemizerLang` from the *active voice's*
     * persisted overrides on cold start. Without this, the first
     * chapter render after process restart uses the engine's built-in
     * lexicon and the voice's native language even if the user had
     * set per-voice overrides in a prior session.
     *
     * Mirrors [applyPitchQualityFromSettings] — fire-and-forget, on
     * IO, no need to await before onCreate() returns because the
     * VoiceManager's first loadModel() always comes after the user
     * interacts with the UI, well after this short flow.first()
     * completes.
     */
    private fun applyPerVoiceEngineKnobsFromSettings() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settings = settingsRepo.settings.first()
            VoiceEngineQualityBridge.applyLexicon(settings.effectiveLexicon)
            VoiceEngineQualityBridge.applyPhonemizerLang(settings.effectivePhonemizerLang)
        }
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
