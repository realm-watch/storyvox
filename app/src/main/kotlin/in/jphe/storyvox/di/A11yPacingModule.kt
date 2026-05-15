package `in`.jphe.storyvox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.SettingsRepositoryUiImpl
import `in`.jphe.storyvox.data.repository.playback.A11yPacingConfig
import `in`.jphe.storyvox.feature.settings.AccessibilityStateBridge
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — Hilt binding
 * for the [A11yPacingConfig] consumed by `core-playback`'s EnginePlayer.
 *
 * The implementation folds two upstream signals:
 *  - the user's `pref_a11y_screen_reader_pause_ms` slider (lives in
 *    DataStore, surfaced as `UiSettings.a11yScreenReaderPauseMs` by
 *    [SettingsRepositoryUiImpl])
 *  - the live `isTalkBackActive` flag from [AccessibilityStateBridge]
 *
 * EnginePlayer must see 0 ms whenever TalkBack is OFF, regardless of
 * what the slider says — so a sighted listener who once experimented
 * with the slider doesn't sit through dragging pauses every chapter.
 * Inside TalkBack the pad takes effect immediately on the next
 * sentence boundary.
 *
 * Lives in `:app` (not `:feature` or `:core-playback`) because it's
 * the only place both contracts are already injectable. The
 * `AccessibilityStateBridge` interface lives in `:feature`, the
 * `A11yPacingConfig` interface lives in `:core-data`; both are
 * dependencies of `:app`.
 */
@Module
@InstallIn(SingletonComponent::class)
object A11yPacingModule {

    @Provides
    @Singleton
    fun provideA11yPacingConfig(
        settings: SettingsRepositoryUiImpl,
        bridge: AccessibilityStateBridge,
    ): A11yPacingConfig = TalkBackGatedA11yPacingConfig(settings, bridge)
}

internal class TalkBackGatedA11yPacingConfig(
    private val settings: SettingsRepositoryUiImpl,
    private val bridge: AccessibilityStateBridge,
) : A11yPacingConfig {

    /**
     * Combine the slider value with the bridge's TalkBack flag. When
     * TalkBack is off, emit 0 regardless of the slider — outside
     * TalkBack the slider is inert.
     */
    override val extraSilenceMs: Flow<Int> =
        combine(
            settings.settings.map { it.a11yScreenReaderPauseMs },
            bridge.state.map { it.isTalkBackActive },
        ) { sliderMs, talkBackOn -> if (talkBackOn) sliderMs else 0 }

    override suspend fun currentExtraSilenceMs(): Int = try {
        extraSilenceMs.first()
    } catch (_: Throwable) {
        0
    }
}
