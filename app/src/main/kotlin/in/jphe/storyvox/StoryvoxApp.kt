package `in`.jphe.storyvox

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import `in`.jphe.storyvox.BuildConfig
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
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
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Issue #409 — pre-warm targets for [warmDataLayer]. All five
     * are Hilt `@Singleton` instances LibraryViewModel (the start
     * destination's VM) injects on its constructor. Materialising
     * them on `Dispatchers.IO` from [Application.onCreate] means
     * by the time `hiltViewModel()` resolves the VM on the Compose
     * Main dispatcher, the underlying [StoryvoxDatabase] is already
     * built, the Room migration ladder has been validated, and the
     * repository singletons are cached — Hilt's DoubleCheck just
     * hands back the cached instances instead of doing first-time
     * construction (Room file open + schema validation ≈ 600-900 ms
     * on the Helio P22T tablet).
     *
     * These are still `Lazy<>` so any failure during the bg warm-up
     * doesn't take down the process: the foreground request just
     * runs the same construction it would have run pre-warm.
     */
    @Inject lateinit var database: Lazy<StoryvoxDatabase>
    @Inject lateinit var fictionRepository: Lazy<FictionRepository>
    @Inject lateinit var shelfRepository: Lazy<ShelfRepository>
    @Inject lateinit var playbackPositionRepository: Lazy<PlaybackPositionRepository>

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

    /**
     * Issue #409 (round 2) — voice-engine seed calls are gated through
     * this latch so they don't fire from [Application.onCreate]. Even
     * though the seed coroutines run on [Dispatchers.IO], the FIRST
     * static-field touch on `VoiceEngine`/`KokoroEngine` triggers
     * class-loader resolution of the VoxSherpa AAR, which dlopens
     * `libsherpa-onnx-jni.so` (~600 KB). On the Helio P22T tablet,
     * that dlopen contends with the Compose first-composition pass
     * for the single dexopt/linker mutex and steals roughly a second
     * of wall-clock from "splash → first frame" — the .so load is
     * unavoidable, but it doesn't need to happen on the cold-launch
     * critical path. MainActivity calls [seedVoiceEngineFromSettings]
     * from a `Choreographer.postFrameCallback` after the first real
     * frame is drawn; the seed coroutines then run before any chapter
     * can possibly start synthesizing (the user has to tap a chapter
     * first).
     */
    private val voiceEngineSeeded = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Issue #409 — every previous-eager step is now scheduled on
        // [Dispatchers.IO] via the [Lazy] accessors. The main thread
        // returns from onCreate as fast as the Hilt component-injection
        // boilerplate allows, so the splash screen → first-frame budget
        // is dominated by Compose work, not Application init.
        // Issue #409 — pre-warm the data layer (Room db open + key
        // repository singletons) on IO so LibraryViewModel's @Inject
        // constructor (called on Main during the first composition)
        // pulls cached instances instead of doing a first-time
        // Room.databaseBuilder().build() on the main thread.
        warmDataLayer()
        initScope.launch {
            // ensurePeriodicWorkScheduled hits SQLite via WorkManager's
            // internal database; on the Helio P22T that's ~150-300ms of
            // disk I/O. Off the main thread → invisible to cold launch.
            workScheduler.get().ensurePeriodicWorkScheduled()
        }
        rehydrateRoyalRoadCookies()
        // Voice-engine seed calls intentionally NOT invoked here — see
        // [seedVoiceEngineFromSettings] kdoc. MainActivity drives the
        // post-first-frame hand-off so the .so dlopen lands well after
        // the splash screen is gone.
        // InstantDB sync — if a refresh token is stored, validate it and
        // pull every per-domain syncer. No-op when no one is signed in.
        // Fire-and-forget: the coordinator launches its own coroutines
        // once [Lazy.get] materialises it on IO.
        initScope.launch {
            syncCoordinator.get().initialize()
        }
    }

    /**
     * Issue #409 — pre-warm the Hilt data-layer singletons LibraryViewModel
     * depends on, BEFORE the start-destination composition tries to
     * resolve them on the Compose Main dispatcher. The biggest
     * contributor is [StoryvoxDatabase] — Room's `databaseBuilder().build()`
     * opens the SQLite file and validates the schema (running any
     * pending migration) synchronously, and on the Helio P22T tablet
     * that's ~600-900 ms of disk I/O + CPU. Doing it off main pulls
     * that entire cost off the critical path.
     *
     * Once the database is up, we fan out the four key repository
     * singletons (Fiction, Shelf, History/Playback) — Hilt's
     * `DoubleCheck` provider caches each `@Singleton` after first
     * construction, so the main-thread `hiltViewModel<LibraryViewModel>()`
     * resolution just sees a cached `instance != UNINITIALIZED` and
     * skips reconstruction.
     *
     * Fire-and-forget: if the warm-up coroutine fails (no observed
     * failure mode today, but defensive) the foreground request just
     * does the work itself — same code path, same outcome, just on a
     * slower thread.
     */
    private fun warmDataLayer() {
        initScope.launch {
            // Open the database first; everything else depends on it.
            database.get()
            // Then the repos LibraryViewModel @Inject's. Each `.get()`
            // is a no-op after the first, so listing them in any order
            // is fine.
            fictionRepository.get()
            shelfRepository.get()
            playbackPositionRepository.get()
        }
    }

    /**
     * Issue #409 — post-first-frame hook for the VoxSherpa engine-
     * bridge seeds. Idempotent (the [voiceEngineSeeded] latch guards
     * against re-entry from a config-change reattach of MainActivity).
     * Called from [MainActivity] inside a `Choreographer.postFrameCallback`
     * so the .so dlopen happens after the splash has handed off to the
     * Compose surface.
     *
     * The semantics match the pre-#409-round-2 behavior — same two
     * coroutines, same dispatcher, same fields written — just shifted
     * later on the timeline. The user-visible contract ("seed the
     * static fields before the first chapter renders") still holds:
     * tapping a chapter is at minimum a few hundred ms of user-input
     * latency after the first frame, and the seed coroutines complete
     * in well under that window even on the P22T.
     */
    fun seedVoiceEngineFromSettings() {
        if (!voiceEngineSeeded.compareAndSet(false, true)) return
        applyPitchQualityFromSettings()
        applyPerVoiceEngineKnobsFromSettings()
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
