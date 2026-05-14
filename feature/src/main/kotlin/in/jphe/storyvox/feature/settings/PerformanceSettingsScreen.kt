package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Performance & buffering subscreen (follow-up to #440 / #467).
 *
 * Daily-tunable knobs at the top of the card; rarely-touched / expert
 * controls tucked behind [AdvancedExpander]. Mirrors the legacy
 * [SettingsScreen] "Performance & buffering" section row-for-row so
 * users searching from "All settings" find the same controls in the
 * same order.
 *
 * Top of card:
 *  - Catch-up Pause switch (#77) — pause+resume cleanly on underrun.
 *  - Buffer slider — colored amber/red past the recommended tick.
 *
 * Behind "More":
 *  - Warm-up Wait switch (#98 Mode A).
 *  - Voice Determinism preset (#85).
 *  - Tier-3 parallel-synth sliders (#88) — engines × threads.
 */
@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Performance", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsSwitchRow(
                    title = "Catch-up Pause",
                    subtitle = if (s.catchupPause) {
                        "Pause briefly when the voice falls behind, then resume cleanly."
                    } else {
                        "Drain through underruns; no buffering spinner."
                    },
                    checked = s.catchupPause,
                    onCheckedChange = viewModel::setCatchupPause,
                )

                BufferSlider(
                    chunks = s.playbackBufferChunks,
                    onChunksChange = viewModel::setPlaybackBufferChunks,
                )

                var perfAdvancedOpen by remember { mutableStateOf(false) }
                AdvancedExpander(
                    titlesPreview = listOf(
                        "Warm-up Wait",
                        "Voice Determinism",
                        "Parallel synth (engines + threads)",
                    ),
                    expanded = perfAdvancedOpen,
                    onToggle = { perfAdvancedOpen = !perfAdvancedOpen },
                ) {
                    SettingsSwitchRow(
                        title = "Warm-up Wait",
                        subtitle = if (s.warmupWait) {
                            "Wait for the voice to warm up before playback starts."
                        } else {
                            "Start playback immediately; accept silence at chapter start."
                        },
                        checked = s.warmupWait,
                        onCheckedChange = viewModel::setWarmupWait,
                    )
                    SettingsSwitchRow(
                        title = "Voice Determinism",
                        subtitle = if (s.voiceSteady) {
                            "Steady — identical text plays the same each time."
                        } else {
                            "Expressive — slight variation, fuller prosody."
                        },
                        checked = s.voiceSteady,
                        onCheckedChange = viewModel::setVoiceSteady,
                    )
                    ParallelSynthSliders(
                        instances = s.parallelSynthInstances,
                        threadsPerInstance = s.synthThreadsPerInstance,
                        onInstancesChange = viewModel::setParallelSynthInstances,
                        onThreadsChange = viewModel::setSynthThreadsPerInstance,
                    )
                }
            }
        }
    }
}
