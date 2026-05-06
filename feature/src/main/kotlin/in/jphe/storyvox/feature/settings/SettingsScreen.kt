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
import androidx.compose.material3.LinearProgressIndicator
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
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiEngineInstallProgress
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun SettingsScreen(
    onOpenVoicePicker: () -> Unit,
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
        SectionHeader("TTS engine")
        EngineSection(
            installed = state.isVoxSherpaInstalled,
            isUpToDate = state.isEngineUpToDate,
            installedVersion = state.installedEngineVersion,
            engineLabel = s.ttsEngine,
            progress = state.installProgress,
            onInstall = viewModel::installVoxSherpa,
            onDismissProgress = viewModel::dismissInstallProgress,
        )
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

/**
 * Three states bundled into one composable so the order of buttons + status
 * lines stays consistent regardless of which condition the user is in:
 *  - missing → "Download voice engine" CTA (primary)
 *  - present but stale → "Update voice engine" CTA (primary)
 *  - present and up-to-date → engine label + version line, no button
 *
 * While [progress] is non-null we render a progress bar in place of the CTA.
 */
@Composable
private fun EngineSection(
    installed: Boolean,
    isUpToDate: Boolean,
    installedVersion: String?,
    engineLabel: String,
    progress: UiEngineInstallProgress?,
    onInstall: () -> Unit,
    onDismissProgress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when {
        !installed -> Text(
            "VoxSherpa is not installed. Storyvox uses it for the offline Library Nocturne voices.",
            style = MaterialTheme.typography.bodyMedium,
        )
        !isUpToDate -> Text(
            "VoxSherpa $installedVersion is installed, but storyvox needs the dry-run-fix build. Update to keep playback reliable.",
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> Text(
            "Engine: $engineLabel · v$installedVersion",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (progress != null) {
        InstallProgressRow(progress, onDismiss = onDismissProgress)
    } else if (!installed) {
        BrassButton(
            label = "Download voice engine",
            onClick = onInstall,
            variant = BrassButtonVariant.Primary,
        )
    } else if (!isUpToDate) {
        BrassButton(
            label = "Update voice engine",
            onClick = onInstall,
            variant = BrassButtonVariant.Primary,
        )
    }
}

@Composable
private fun InstallProgressRow(
    progress: UiEngineInstallProgress,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (progress) {
        UiEngineInstallProgress.Resolving -> {
            Text("Finding latest VoxSherpa release…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is UiEngineInstallProgress.Downloading -> {
            val pct = if (progress.totalBytes > 0L) {
                (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val mb = progress.bytesRead / 1_000_000
            val totalMb = progress.totalBytes / 1_000_000
            Text(
                "Downloading… ${mb} MB / ${totalMb} MB",
                style = MaterialTheme.typography.bodySmall,
            )
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        UiEngineInstallProgress.LaunchingInstaller -> {
            Text(
                "Confirm the install in the Android dialog. You can return to storyvox after.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            BrassButton(label = "Done", onClick = onDismiss, variant = BrassButtonVariant.Text)
        }
        is UiEngineInstallProgress.Failed -> {
            Text(
                progress.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            BrassButton(label = "Dismiss", onClick = onDismiss, variant = BrassButtonVariant.Text)
        }
    }
}
