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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.BUFFER_DANGER_MULTIPLIER
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.PunctuationPause
import `in`.jphe.storyvox.feature.api.ThemeOverride
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

        // Issue #90: three-stop selector for the inter-sentence silence
        // splice. Same brass-button-row aesthetic as the Theme picker so
        // it feels like a sibling control.
        Text("Pause after . , ? ! ; :", style = MaterialTheme.typography.bodyMedium)
        Text(
            "How long to pause between sentences. Off makes the reader sprint; Long gives narration room to breathe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PunctuationPause.entries.forEach { mode ->
                val variant = if (s.punctuationPause == mode) BrassButtonVariant.Primary else BrassButtonVariant.Secondary
                BrassButton(label = mode.name, onClick = { viewModel.setPunctuationPause(mode) }, variant = variant)
            }
        }

        Divider()
        SectionHeader("Audio buffer")
        BufferSlider(
            chunks = s.playbackBufferChunks,
            onChunksChange = viewModel::setPlaybackBufferChunks,
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
