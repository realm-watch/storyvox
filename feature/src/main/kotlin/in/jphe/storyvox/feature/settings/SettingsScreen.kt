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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
