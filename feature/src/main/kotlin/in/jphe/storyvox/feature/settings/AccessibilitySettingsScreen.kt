package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.ReadingDirection
import `in`.jphe.storyvox.feature.api.SpeakChapterMode
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Accessibility subscreen (Phase 1 scaffold).
 *
 * Phase 1 surfaces the user's explicit intent for the assistive-
 * service-shaped tunables. No behavior is wired today — every toggle
 * here writes to DataStore and the live state-bridge in
 * [AccessibilityStateBridge] reports what Android is doing right now,
 * but neither feeds back into the rest of the app yet. Phase 2 agents
 * consume these prefs + the bridge to:
 *
 *  - swap Library Nocturne for a higher-contrast variant
 *    ([UiSettings.a11yHighContrast]),
 *  - suppress chip-strip slide-ins / brass shimmer / page-transition
 *    curves ([UiSettings.a11yReducedMotion]),
 *  - widen `clickable` minimums from 48dp → 64dp
 *    ([UiSettings.a11yLargerTouchTargets]),
 *  - apply an extra inter-sentence pause when TalkBack is active
 *    ([UiSettings.a11yScreenReaderPauseMs]),
 *  - control TalkBack chapter-header readout
 *    ([UiSettings.a11ySpeakChapterMode]),
 *  - apply a font-scale override on top of the system font scale
 *    ([UiSettings.a11yFontScaleOverride]),
 *  - override system reading direction
 *    ([UiSettings.a11yReadingDirection]).
 *
 * Each Phase 2 agent will detect a Phase 1 placeholder by the
 * "TODO — Phase 2" inline kdoc on the relevant callback wiring and
 * replace it with the real handler. The Phase 2 placeholders are
 * deliberately load-bearing here — they document the contract for the
 * agent that lands the behavior.
 *
 * Row order matches the spec in the spawning conversation:
 *  1. High contrast theme
 *  2. Reduced motion
 *  3. Larger touch targets
 *  4. Screen-reader pauses (slider)
 *  5. Speak chapter numbers / titles (radio)
 *  6. Font scale override (slider)
 *  7. Override system reading direction (radio)
 *  8. About this subscreen (info row)
 *
 * The legacy long-scroll [SettingsScreen] does NOT mirror these rows
 * (deliberate — accessibility is a new section in v0.5.42, and the
 * legacy page is frozen for the v0.5.x line). Users searching for an
 * a11y knob in the "All settings" escape hatch land here via the
 * Settings hub instead.
 */
@Composable
fun AccessibilitySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Accessibility", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                // 1. High contrast theme — Phase 2 swaps Library
                //    Nocturne for a higher-contrast variant. Auto-on
                //    overlay when TalkBack is detected is also Phase 2
                //    (reads [AccessibilityStateBridge.state]).
                SettingsSwitchRow(
                    title = "High contrast theme",
                    subtitle = "Use a higher-contrast variant of Library Nocturne. " +
                        "Auto-enables when TalkBack is active.",
                    checked = s.a11yHighContrast,
                    onCheckedChange = viewModel::setA11yHighContrast,
                )

                // 2. Reduced motion — Phase 2 grep's for AnimatedVisibility
                //    / tween() and folds [LocalReducedMotion.current ||
                //    a11yReducedMotion] at every call site. The
                //    [LocalReducedMotion] CompositionLocal still reads
                //    the system "Remove animations" setting; this toggle
                //    layers on top so users who don't want to flip the
                //    OS-level setting can still suppress storyvox's
                //    motion.
                SettingsSwitchRow(
                    title = "Reduced motion",
                    subtitle = "Disable non-essential animations (chip slides, brass shimmer, page-transition curves). " +
                        "Auto-enables when the system \"Remove animations\" setting is on.",
                    checked = s.a11yReducedMotion,
                    onCheckedChange = viewModel::setA11yReducedMotion,
                )

                // 3. Larger touch targets — Phase 2 widens clickable
                //    minimums from 48dp to 64dp at every SettingsRow /
                //    BrassButton / ChapterCard tap surface. Switch
                //    Access auto-on is Phase 2 (reads
                //    [AccessibilityStateBridge.isSwitchAccessActive]).
                SettingsSwitchRow(
                    title = "Larger touch targets",
                    subtitle = "Increase tap-target minimums from 48dp to 64dp where feasible. " +
                        "Auto-enables when Switch Access is active.",
                    checked = s.a11yLargerTouchTargets,
                    onCheckedChange = viewModel::setA11yLargerTouchTargets,
                )

                // 4. Screen-reader pauses — extra inter-sentence pause
                //    when TalkBack is active. The slider stores the
                //    user's intent in ms; Phase 2's TalkBack adapter
                //    consults this when scheduling the next sentence's
                //    TTS chunk after the previous one ends. Outside
                //    TalkBack the value is inert.
                val pauseMin = 0f
                val pauseMax = 1500f
                val pauseValue = s.a11yScreenReaderPauseMs.toFloat()
                    .coerceIn(pauseMin, pauseMax)
                SettingsSliderBlock(
                    title = "Screen-reader pauses",
                    valueLabel = "${s.a11yScreenReaderPauseMs} ms",
                    subtitle = "Extra pause between sentences when TalkBack is active.",
                    slider = {
                        Slider(
                            value = pauseValue,
                            onValueChange = { viewModel.setA11yScreenReaderPauseMs(it.toInt()) },
                            valueRange = pauseMin..pauseMax,
                            // Tick every 100 ms — 15 steps across the
                            // 0-1500 range so haptic snap matches a
                            // reasonable a11y-pacing granularity.
                            steps = 14,
                            modifier = Modifier.semantics {
                                contentDescription = "Screen-reader inter-sentence pause"
                                stateDescription = "${pauseValue.toInt()} milliseconds"
                            },
                        )
                    },
                )

                // 5. Speak chapter numbers / titles — three radio
                //    options. The current selection persists as a
                //    SpeakChapterMode enum; Phase 2's TalkBack adapter
                //    branches on this when generating chapter-header
                //    announcements.
                val speakModeOptions = listOf(
                    SpeakChapterMode.Both to "Both",
                    SpeakChapterMode.NumbersOnly to "Numbers only",
                    SpeakChapterMode.TitlesOnly to "Titles only",
                )
                val speakSelectedIndex = speakModeOptions
                    .indexOfFirst { it.first == s.a11ySpeakChapterMode }
                    .coerceAtLeast(0)
                SettingsSegmentedBlock(
                    title = "Speak chapter numbers / titles",
                    subtitle = "Controls how TalkBack reads chapter headers.",
                    options = speakModeOptions.map { it.second },
                    selectedIndex = speakSelectedIndex,
                    onSelected = { idx ->
                        viewModel.setA11ySpeakChapterMode(speakModeOptions[idx].first)
                    },
                )

                // 6. Font scale override — multiplier on top of Android's
                //    system font scale. Phase 2 wires this into the
                //    Compose typography pipeline (likely via a
                //    CompositionLocal-overriding LayoutDirection-aware
                //    wrapper at the NavHost root).
                val fontMin = 0.85f
                val fontMax = 1.5f
                val fontValue = s.a11yFontScaleOverride.coerceIn(fontMin, fontMax)
                SettingsSliderBlock(
                    title = "Font scale override",
                    valueLabel = "${"%.2f".format(fontValue)}×",
                    subtitle = "Multiplier on top of Android's system font scale.",
                    slider = {
                        Slider(
                            value = fontValue,
                            onValueChange = { viewModel.setA11yFontScaleOverride(it) },
                            valueRange = fontMin..fontMax,
                            modifier = Modifier.semantics {
                                contentDescription = "Font scale override"
                                stateDescription = "%.2f times".format(fontValue)
                            },
                        )
                    },
                )

                // 7. Reading direction override — escape hatch for RTL
                //    testing and locale-mismatch overrides. Phase 2 wires
                //    this into LayoutDirection at the NavHost root.
                val directionOptions = listOf(
                    ReadingDirection.FollowSystem to "Follow system",
                    ReadingDirection.ForceLtr to "Force LTR",
                    ReadingDirection.ForceRtl to "Force RTL",
                )
                val directionSelectedIndex = directionOptions
                    .indexOfFirst { it.first == s.a11yReadingDirection }
                    .coerceAtLeast(0)
                SettingsSegmentedBlock(
                    title = "Override system reading direction",
                    subtitle = "Escape hatch for RTL testing and locale-mismatch overrides.",
                    options = directionOptions.map { it.second },
                    selectedIndex = directionSelectedIndex,
                    onSelected = { idx ->
                        viewModel.setA11yReadingDirection(directionOptions[idx].first)
                    },
                )
            }

            // 8. About this subscreen — non-clickable info row at the
            //    bottom. The "Learn more" link is wired to a TODO since
            //    `docs/accessibility.md` lands with the Phase 2 outreach
            //    + documentation pass; until then the row reads as
            //    static help copy.
            SettingsGroupCard {
                SettingsRow(
                    title = "About these settings",
                    subtitle = "These settings improve storyvox for users who rely on TalkBack, " +
                        "Switch Access, or other accessibility tools. Most adapt automatically " +
                        "when assistive services are detected.",
                )
                SettingsLinkRow(
                    title = "Learn more",
                    subtitle = "Coming with the Phase 2 documentation pass.",
                    onClick = {
                        // TODO — Phase 2 wires this to docs/accessibility.md
                        // (or its hosted equivalent). Today the doc
                        // doesn't exist; the link is a visible
                        // placeholder so reviewers see the affordance
                        // the section will eventually carry.
                    },
                )
            }

            // Phase 2 hook — a Phase 2 agent that wants to render the
            // "auto-detected" state (e.g. a brass note that says
            // "TalkBack is active") will collect from
            // [AccessibilityStateBridge.state] and render here. Phase 1
            // doesn't surface live state in the UI; the bridge data is
            // available via Hilt to any Phase 2 consumer that wants it.
            @Suppress("UnusedExpression")
            Unit  // placeholder anchor for the Phase 2 live-state row
        }
    }
}
