package `in`.jphe.storyvox

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
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

    /**
     * Issue #409 — cold launch on the Tab A7 Lite (Helio P22T) was 6.8s
     * vs 1.2s on the Z Flip3 (SD888). Logcat traced ~3.3s of that to the
     * synchronous `super.onCreate()` block below: `@Inject lateinit var`
     * fields are materialized eagerly when Hilt's
     * [HiltAndroidApp]-generated subclass calls `inject(this)` from
     * `attachBaseContext` / `onCreate`, and on a low-end SoC, building a
     * 6-field graph that transitively constructs ~25 [Singleton]s (esp.
     * the 20-arg [SettingsRepositoryUiImpl] and the 16-syncer
     * [SyncCoordinator] set-binding) before the first frame budget even
     * starts is a meaningful percentage of the cold-launch wall-clock.
     *
     * Switching to `Lazy<T>` defers actual construction to the moment of
     * `.get()`, which we punt to a background coroutine in [onCreate].
     * The Hilt graph is still fully wired (still type-safe, still
     * @Singleton-scoped); we just stop *materialising* it on the main
     * thread before the splash screen disappears.
     *
     * Only [workerFactory] stays eager — Android's `WorkManager`
     * initializer queries [Configuration.Provider.workManagerConfiguration]
     * the first time `WorkManager.getInstance(context)` is called, and
     * that getter reads [workerFactory]. We need it ready before any
     * worker is enqueued.
     */
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: Lazy<WorkScheduler>
    @Inject lateinit var authRepository: Lazy<AuthRepository>
    @Inject lateinit var sessionHydrator: Lazy<SessionHydrator>
    @Inject lateinit var syncCoordinator: Lazy<SyncCoordinator>
    @Inject lateinit var settingsRepo: Lazy<SettingsRepositoryUi>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /**
     * Long-lived scope for the deferred init coroutines. Survives the
     * Application lifetime; uses a [SupervisorJob] so a failure in one
     * init step doesn't poison the others.
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Issue #409 — every previous-eager step is now scheduled on
        // [Dispatchers.IO] via the [Lazy] accessors. The main thread
        // returns from onCreate as fast as the Hilt component-injection
        // boilerplate allows, so the splash screen → first-frame budget
        // is dominated by Compose work, not Application init.
        initScope.launch {
            // ensurePeriodicWorkScheduled hits SQLite via WorkManager's
            // internal database; on the Helio P22T that's ~150-300ms of
            // disk I/O. Off the main thread → invisible to cold launch.
            workScheduler.get().ensurePeriodicWorkScheduled()
        }
        rehydrateRoyalRoadCookies()
        applyPitchQualityFromSettings()
        applyPerVoiceEngineKnobsFromSettings()
        // InstantDB sync — if a refresh token is stored, validate it and
        // pull every per-domain syncer. No-op when no one is signed in.
        // Fire-and-forget: the coordinator launches its own coroutines
        // once [Lazy.get] materialises it on IO.
        initScope.launch {
            syncCoordinator.get().initialize()
        }
    }

    /**
     * Issue #193 — seed the VoxSherpa engine static `sonicQuality`
     * fields from the user's persisted Settings toggle. The field
     * default in VoxSherpa-TTS v2.7.13 is 1 (high quality), but a
     * user who flipped to OFF in a prior session needs that pref
     * re-applied on cold start, before the first chapter renders.
     */
    private fun applyPitchQualityFromSettings() {
        initScope.launch {
            val highQuality = settingsRepo.get().settings.first().pitchInterpolationHighQuality
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
        initScope.launch {
            val settings = settingsRepo.get().settings.first()
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
        initScope.launch {
            val header = authRepository.get().cookieHeader() ?: return@launch
            // Cookie header is "name1=value1; name2=value2; ..." — parse back
            // into the Map<String,String> shape SessionHydrator expects.
            val cookies = header.split("; ")
                .mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
                }
                .toMap()
            if (cookies.isNotEmpty()) sessionHydrator.get().hydrate(cookies)
        }
    }
}
