package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.BUFFER_DANGER_MULTIPLIER
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun SettingsScreen(
    onOpenVoiceLibrary: () -> Unit,
    onOpenSignIn: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val s = state.settings ?: return

    Scaffold { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SectionHeader("Voices")
        Text(
            "Storyvox uses an in-process neural TTS engine. Pick a voice or download more in the library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BrassButton(label = "Voice library", onClick = onOpenVoiceLibrary, variant = BrassButtonVariant.Primary)

        Divider()
        SectionHeader("Reading")
        Slider(
            value = s.defaultSpeed,
            onValueChange = viewModel::setSpeed,
            valueRange = 0.5f..3.0f,
        )
        Text("Speed ${"%.2f".format(s.defaultSpeed)}×", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = s.defaultPitch,
            onValueChange = viewModel::setPitch,
            // Narration-friendly band — matches the in-context pitch slider
            // in AudiobookView. Beyond ±15% TTS sounds robotic.
            valueRange = 0.85f..1.15f,
            steps = 29, // 0.01 per step
        )
        Text("Pitch ${"%.2f".format(s.defaultPitch)}×", style = MaterialTheme.typography.bodySmall)

        Divider()
        // Issue #98 — "Performance & buffering" home for every setting that
        // trades upfront wait + memory for smoother playback on slow devices.
        // Order intentionally goes from the cheapest knobs (boolean modes) to
        // the most exploratory (the buffer slider, which goes well past the
        // recommended max for the LMK probe in #84). Punctuation cadence sits
        // at the bottom because it's a cadence preference more than a perf
        // trade-off, but it also lives in the perf trade space (Off skips
        // synthesis time per sentence boundary).
        SectionHeader("Performance & buffering")
        Text(
            "Settings that trade upfront wait + memory for smoother playback. Useful on slower devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )

        // Mode A — Warm-up Wait. Toggle, default ON. When ON the UI shows a
        // brass spinner + freezes the scrubber while the voice engine is
        // loading + producing the first sentence. When OFF the UI behaves as
        // if playback started immediately; listener accepts silence at chapter
        // start. Wired through PlaybackModeConfig + AppBindings.toUi.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Warm-up Wait", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (s.warmupWait) {
                        "Wait for the voice to warm up before playback starts."
                    } else {
                        "Start playback immediately; accept silence at chapter start."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.warmupWait, onCheckedChange = viewModel::setWarmupWait)
        }

        // Mode B — Catch-up Pause. Toggle, default ON. When ON, mid-stream
        // underrun pauses AudioTrack and surfaces "Buffering…" until the
        // queue refills (PR #77's pause-buffer-resume). When OFF the consumer
        // drains through the underrun: listener may hear dead air, but never
        // sees the buffering spinner. EngineStreamingSource is untouched.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Catch-up Pause", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (s.catchupPause) {
                        "Pause briefly when the voice falls behind, then resume cleanly."
                    } else {
                        "Drain through underruns; no buffering spinner."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.catchupPause, onCheckedChange = viewModel::setCatchupPause)
        }

        // Buffer Headroom — migrated from the standalone "Audio buffer"
        // section. Same control, same #84 LMK-probe semantics.
        BufferSlider(
            chunks = s.playbackBufferChunks,
            onChunksChange = viewModel::setPlaybackBufferChunks,
        )

        // Punctuation Cadence — issue #109 widened the original 3-stop
        // selector (#93) into a continuous slider so users who want
        // slower-than-Long or faster-than-Off-style cadence can dial it in.
        // Range 0×..4× matches the engine's existing internal clamp; tick
        // labels anchor the legacy stops (Off, Normal, Long) and the new
        // 4× ceiling.
        PunctuationPauseSlider(
            multiplier = s.punctuationPauseMultiplier,
            onMultiplierChange = viewModel::setPunctuationPauseMultiplier,
        )

        Divider()
        SectionHeader("Theme")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            ThemeOverride.entries.forEach { mode ->
                val variant = if (s.themeOverride == mode) BrassButtonVariant.Primary else BrassButtonVariant.Secondary
                BrassButton(label = mode.name, onClick = { viewModel.setTheme(mode) }, variant = variant)
            }
        }

        Divider()
        SectionHeader("Downloads")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = s.downloadOnWifiOnly, onCheckedChange = viewModel::setWifiOnly)
        }
        Text("Poll every ${s.pollIntervalHours}h", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = s.pollIntervalHours.toFloat(),
            onValueChange = { viewModel.setPollHours(it.toInt().coerceIn(1, 24)) },
            valueRange = 1f..24f,
            steps = 22,
        )

        Divider()
        SectionHeader("Account")
        if (s.isSignedIn) {
            BrassButton(label = "Sign out", onClick = viewModel::signOut, variant = BrassButtonVariant.Secondary)
        } else {
            BrassButton(
                label = "Sign in",
                onClick = onOpenSignIn,
                variant = BrassButtonVariant.Primary,
            )
            Text(
                "Sign-in unlocks Premium chapters and your Follows list. Anonymous browsing works for all public chapters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Divider()
        MemoryPalaceSection(
            palace = s.palace,
            probe = state.palaceProbe,
            probing = state.palaceProbing,
            onSetHost = viewModel::setPalaceHost,
            onSetApiKey = viewModel::setPalaceApiKey,
            onClear = viewModel::clearPalaceConfig,
            onTest = viewModel::testPalaceConnection,
        )

        Divider()
        SectionHeader("About")
        // Realm-sigil version. The "name" field is a deterministic
        // adjective+noun drawn from the fantasy realm word list, keyed on
        // the build's git hash. Same hash → same name across rebuilds.
        Text(
            text = "storyvox v${s.sigil.versionName}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = s.sigil.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = buildString {
                append(s.sigil.branch)
                if (s.sigil.dirty) append(" · dirty")
                append(" · built ")
                append(s.sigil.built.take(10)) // YYYY-MM-DD only
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.lg))
    }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/**
 * Memory Palace section for the Settings screen (#79).
 *
 * Two text fields (host, optional API key) plus a Test/Clear row. The
 * status pill above the fields shows the last probe result; user-typed
 * edits clear the previous status (the address has changed; previous
 * verdict no longer authoritative).
 *
 * Spec: docs/superpowers/specs/2026-05-08-mempalace-integration-design.md.
 */
@Composable
private fun MemoryPalaceSection(
    palace: UiPalaceConfig,
    probe: PalaceProbeResult?,
    probing: Boolean,
    onSetHost: (String) -> Unit,
    onSetApiKey: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val spacing = LocalSpacing.current
    SectionHeader("Memory Palace")
    Text(
        "Browse and listen to your local MemPalace as fictions. Home network only — " +
            "the palace stays put when you're off the LAN.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Status pill — reflects the last probe result, or guides the user
    // when the host field is empty.
    val (statusText, statusColor) = when {
        !palace.isConfigured -> "Set host to enable" to MaterialTheme.colorScheme.onSurfaceVariant
        probe == null -> "Tap “Test connection” to verify" to MaterialTheme.colorScheme.onSurfaceVariant
        probe is PalaceProbeResult.Reachable ->
            "Connected · daemon ${probe.daemonVersion}" to MaterialTheme.colorScheme.primary
        probe is PalaceProbeResult.Unreachable ->
            "Off home network · ${probe.message}" to MaterialTheme.colorScheme.error
        probe is PalaceProbeResult.NotConfigured ->
            "Set host to enable" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        statusText,
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
    )

    // Host field. Local state lets the user type freely; the persisted
    // setter fires onValueChange so changes are picked up across config
    // recreations. We don't debounce — DataStore is fine with the rate.
    var hostInput by remember(palace.host) { mutableStateOf(palace.host) }
    OutlinedTextField(
        value = hostInput,
        onValueChange = {
            hostInput = it
            onSetHost(it)
        },
        label = { Text("Palace host (e.g. 10.0.6.50:8085)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    // API key — masked. Stored in EncryptedSharedPreferences alongside
    // Royal Road cookies. Empty is fine for unauthenticated daemons.
    var apiKeyInput by remember(palace.apiKey) { mutableStateOf(palace.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = {
            apiKeyInput = it
            onSetApiKey(it)
        },
        label = { Text("API key (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (probing) {
            // Inline spinner; the BrassButton doesn't have a loading state
            // and adding one for one site felt heavy. 16dp matches the
            // text height on either side.
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Testing…", style = MaterialTheme.typography.bodySmall)
        } else {
            BrassButton(
                label = "Test connection",
                onClick = onTest,
                variant = BrassButtonVariant.Primary,
            )
        }
        if (palace.isConfigured) {
            BrassButton(
                label = "Clear",
                onClick = {
                    hostInput = ""
                    apiKeyInput = ""
                    onClear()
                },
                variant = BrassButtonVariant.Secondary,
            )
        }
    }
}

/** Average sentence duration in seconds at 1.0× speed. Empirical, used only
 *  for the human-readable "≈ N seconds" hint under the slider. The actual
 *  knob is queue depth (chunks). */
private const val AVG_SENTENCE_SEC: Float = 2.5f

/** Approximate bytes per chunk @ 22050 Hz mono 16-bit, 2.5 s avg.
 *  22050 × 2 × 2.5 ≈ 110 KB. Used for the "≈ N MB" hint. */
private const val APPROX_BYTES_PER_CHUNK: Int = 110_000

/**
 * Settings → Audio buffer slider.
 *
 * The buffer is the pre-synthesized PCM queue depth fed to AudioTrack. On
 * fast voices / fast CPUs the default of 8 chunks is plenty; on slow voices
 * (Piper-high) and slow CPUs (Helio P22T) the producer falls behind and the
 * listener hears mid-sentence underruns. Letting users dial up the queue
 * trades RAM for resilience.
 *
 * The mechanical max ([BUFFER_MAX_CHUNKS]) intentionally goes well past
 * where we believe Android's Low Memory Killer will start marking the app —
 * issue #84 is the experimental probe to find that line. Past the
 * [BUFFER_RECOMMENDED_MAX_CHUNKS] tick we (a) intensify the warning copy and
 * (b) shift the slider track color amber → red so the user knows they've
 * crossed into experimental territory.
 */
@Composable
private fun BufferSlider(
    chunks: Int,
    onChunksChange: (Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    val pastTick = chunks > BUFFER_RECOMMENDED_MAX_CHUNKS
    val danger = chunks > BUFFER_RECOMMENDED_MAX_CHUNKS * BUFFER_DANGER_MULTIPLIER

    // Two distinct color states above the tick (amber → red) so the user has
    // a tactile sense of "I'm in unknown territory" → "I'm in dangerous
    // unknown territory". Below the tick we use the brand primary.
    val amber = Color(0xFFE0A040)
    val red = Color(0xFFD05030)
    val activeColor = when {
        danger -> red
        pastTick -> amber
        else -> MaterialTheme.colorScheme.primary
    }
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val approxSeconds = (chunks * AVG_SENTENCE_SEC).toInt()
    val approxMb = (chunks.toLong() * APPROX_BYTES_PER_CHUNK / 1_048_576L).toInt()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // Header row: current value + recommended-max indicator.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Buffer: $chunks chunks (~${approxSeconds}s, ~${approxMb} MB)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Recommended max: $BUFFER_RECOMMENDED_MAX_CHUNKS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Slider(
            value = chunks.toFloat(),
            onValueChange = { onChunksChange(it.toInt().coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)) },
            valueRange = BUFFER_MIN_CHUNKS.toFloat()..BUFFER_MAX_CHUNKS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = inactiveColor,
            ),
        )

        // Recommended-max tick label, spatially anchored to its position
        // along the slider via a leading spacer. Material3 Slider doesn't
        // expose tick rendering for non-stepped sliders; this gives the
        // user a visible "this is where the line is" without painting onto
        // the slider's canvas.
        TickMarker(
            tickValue = BUFFER_RECOMMENDED_MAX_CHUNKS,
            min = BUFFER_MIN_CHUNKS,
            max = BUFFER_MAX_CHUNKS,
        )

        // Always-on note text. Issue #84 — substance is verbatim from the
        // issue body's "Why we have a recommended max" / "Why we can't just
        // fix the delay" paragraphs.
        Text(
            text = "Pre-synthesizes extra audio ahead of where you're listening. " +
                "A larger buffer hides moments where the voice engine can't keep up — " +
                "useful on high-quality voices like Piper-high that synthesize slower " +
                "than realtime on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
        Text(
            text = "Why we have a recommended max: Past a certain size, Android may " +
                "kill the app in the background while you're not actively using it. " +
                "The recommended max is set just below where we currently believe " +
                "that line is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Why we can't just \"fix\" the delay: The bottleneck is the voice " +
                "model running on this CPU — not the buffer logic. The structural fix " +
                "is pre-rendering chapters to disk so playback reads finished audio " +
                "instead of synthesizing live. That work is on the roadmap (#86 / PCM " +
                "cache PRs C-H).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Past-the-tick intensified warning. Issue #84 update — JP wants the
        // probe to be visible so users above the tick know they're helping
        // measure the LMK threshold.
        if (pastTick) {
            Text(
                text = "Past the recommended max: Android may kill the app in the " +
                    "background. Help us find the exact limit by reporting what works.",
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) red else amber,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * A discrete label rendered at the slider's tick fraction. Two-row layout:
 *  - Spacer that takes up `tickFraction × parentWidth` on the left
 *  - Small caret-and-label group anchored at that x
 *
 * Doesn't paint onto the slider canvas (Material3 Slider's track-painter
 * customization is verbose for what we need); just renders below the slider
 * at the correct horizontal offset.
 */
@Composable
private fun TickMarker(
    tickValue: Int,
    min: Int,
    max: Int,
) {
    val fraction = ((tickValue - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Eat the left fraction of the row's width.
        Box(modifier = Modifier.weight(fraction.coerceAtLeast(0.001f)))
        Text(
            text = "▲ $tickValue",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight((1f - fraction).coerceAtLeast(0.001f)))
    }
}

/**
 * Settings → Punctuation Cadence slider (issue #109).
 *
 * Continuous multiplier on the inter-sentence silence storyvox splices after
 * each TTS sentence. The base pause table (350/200/120/60 ms by terminator)
 * lives in `EngineStreamingSource.trailingPauseMs` and is scaled by this
 * value before being emitted as PCM zeros.
 *
 * Range is [PUNCTUATION_PAUSE_MIN_MULTIPLIER]..[PUNCTUATION_PAUSE_MAX_MULTIPLIER]
 * (0×..4×) — wider than the legacy 3-stop selector's 0×/1×/1.75× because
 * the engine has always coerced to 0..4 internally and #109 surfaces that
 * full range. Tick labels anchor the legacy stops + the new ceiling so
 * users who liked "Long" can find it precisely (1.75×).
 *
 * Same brass-styled aesthetic as [BufferSlider] — primary-colored thumb +
 * active track, surface-variant inactive track. No "warning zone": every
 * point on this slider is mechanically safe; it's purely a cadence
 * preference. Single decimal-place readout (e.g. "1.8×") is enough
 * precision for a perceptual knob.
 */
@Composable
private fun PunctuationPauseSlider(
    multiplier: Float,
    onMultiplierChange: (Float) -> Unit,
) {
    val spacing = LocalSpacing.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text("Pause after . , ? ! ; :", style = MaterialTheme.typography.bodyMedium)
        Text(
            "How long to pause between sentences. 0× makes the reader sprint; " +
                "1× is the audiobook default; 1.75× matches the old \"Long\" stop; " +
                "4× gives narration full theatrical room to breathe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Pause after punctuation: ${"%.2f".format(multiplier)}×",
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = multiplier,
            onValueChange = {
                onMultiplierChange(
                    it.coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER),
                )
            },
            valueRange = PUNCTUATION_PAUSE_MIN_MULTIPLIER..PUNCTUATION_PAUSE_MAX_MULTIPLIER,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

        // Tick labels at the legacy stops + the new 4× ceiling. Spatial
        // anchoring uses the same weight-trick as [TickMarker] so the labels
        // sit under their actual slider positions without painting onto the
        // canvas. Material3 Slider's `steps` parameter would draw real ticks
        // but only at evenly-spaced fractions of the range; our stops aren't
        // evenly spaced (0/1/1.75/4 fractions are 0, 0.25, 0.4375, 1) so
        // we render them as a separate row.
        PunctuationPauseTickLabels()
    }
}

/**
 * Anchored row of legacy-stop labels under [PunctuationPauseSlider]. Each
 * label sits at its true fractional position along the [0..4] range using
 * weight spacers, mirroring [TickMarker]'s technique. Labels include the
 * legacy stop names (Off / Normal / Long) so users coming from the 3-stop
 * selector can find their preferred cadence at a glance.
 */
@Composable
private fun PunctuationPauseTickLabels() {
    val total = PUNCTUATION_PAUSE_MAX_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER
    val offFrac = ((PUNCTUATION_PAUSE_OFF_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total)
        .coerceIn(0f, 1f)
    val normalFrac = ((PUNCTUATION_PAUSE_NORMAL_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total)
        .coerceIn(0f, 1f)
    val longFrac = ((PUNCTUATION_PAUSE_LONG_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total)
        .coerceIn(0f, 1f)

    // Distances between adjacent ticks along the row; the trailing slack
    // after "Max" pads to the right edge of the parent.
    val toNormal = (normalFrac - offFrac).coerceAtLeast(0.001f)
    val toLong = (longFrac - normalFrac).coerceAtLeast(0.001f)
    val toMax = (1f - longFrac).coerceAtLeast(0.001f)

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "▲ Off",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(toNormal))
        Text(
            text = "▲ 1×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(toLong))
        Text(
            text = "▲ Long",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(toMax))
        Text(
            text = "▲ 4×",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
