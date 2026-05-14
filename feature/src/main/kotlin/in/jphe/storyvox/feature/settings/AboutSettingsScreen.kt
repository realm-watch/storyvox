package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → About subscreen (follow-up to #440 / #467).
 *
 * Build identity card for bug reports: version + sigil name (the
 * deterministic adjective+noun realm-sigil derived from the build's
 * git hash), branch, dirty flag, build date, and the v0.5.00
 * graduation milestone pill when the build qualifies.
 *
 * The legacy long-scroll [SettingsScreen] renders the same content
 * inside its About section card; this subscreen surfaces it behind
 * a dedicated route for users who reach Settings → About via the
 * hub.
 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "About", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = "storyvox v${s.sigil.versionName}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = s.sigil.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = buildString {
                            append(s.sigil.branch)
                            if (s.sigil.dirty) append(" · dirty")
                            append(" · built ")
                            append(s.sigil.built.take(10))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isV0500MilestoneBuild(s.sigil.versionName)) {
                        MilestoneBadgePill()
                    }
                }
            }
        }
    }
}
