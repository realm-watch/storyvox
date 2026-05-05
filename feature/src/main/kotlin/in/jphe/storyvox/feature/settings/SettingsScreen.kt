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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun SettingsScreen(
    onOpenVoicePicker: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val s = state.settings ?: return

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SectionHeader("TTS engine")
        if (!state.isVoxSherpaInstalled) {
            Text(
                "VoxSherpa is not installed. Install it for the best Library Nocturne voices.",
                style = MaterialTheme.typography.bodyMedium,
            )
            BrassButton(label = "Install VoxSherpa", onClick = viewModel::installVoxSherpa, variant = BrassButtonVariant.Secondary)
        } else {
            Text("Engine: ${s.ttsEngine}", style = MaterialTheme.typography.bodyMedium)
        }
        BrassButton(label = "Default voice…", onClick = onOpenVoicePicker, variant = BrassButtonVariant.Text)

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
            valueRange = 0.5f..2.0f,
        )
        Text("Pitch ${"%.2f".format(s.defaultPitch)}×", style = MaterialTheme.typography.bodySmall)

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
            BrassButton(label = "Sign in", onClick = viewModel::signIn, variant = BrassButtonVariant.Primary)
        }

        Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.lg))
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
