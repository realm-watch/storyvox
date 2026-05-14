package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #440 — Settings hub screen.
 *
 * v0.5.36 wired the gear icon directly to [SettingsScreen], a 3,600-line
 * flat-scroll page that opened on the Voice & Playback section with no
 * top-of-page map. New users had no way to discover what Settings
 * contained without scrolling past every card; the top bar still read
 * "Voice & Playback" while the user scrolled through Reading, Performance,
 * AI, etc., so the title disagreed with what was actually on screen.
 *
 * This hub screen is the new gear-icon destination: a short list of
 * section cards, each carrying a one-line subtitle that previews its
 * contents and routes to either a dedicated subscreen
 * ([PluginManagerScreen][in.jphe.storyvox.feature.settings.plugins.PluginManagerScreen],
 * voice library, pronunciation dictionary, debug, AI sessions) or the
 * existing long [SettingsScreen] for sections that haven't been broken
 * out yet.
 *
 * The long [SettingsScreen] is intentionally preserved as a "Show all
 * Settings" landing for cards without a dedicated subscreen — splitting
 * every section into its own composable would be a much larger refactor
 * than #440's scope (the hub is the headline fix; the per-section
 * subscreens are a follow-up). A dedicated row on the hub points at
 * that long page explicitly so the affordance isn't lost.
 *
 * ## Section row order
 *
 * Most-touched first (matches the section ribbon order in
 * [SettingsScreen]):
 *
 * 1. Voice & Playback — voice, speed, cadence, pitch.
 * 2. Voice library — dedicated subscreen.
 * 3. Reading — auto-advance, sleep timer, reader pause-resume.
 * 4. Performance — buffering, parallel synth, decoder choice.
 * 5. AI — chat model, grounding, recap.
 * 6. AI sessions — dedicated subscreen.
 * 7. Plugins — registry-driven plugin manager (#404 surface).
 * 8. Pronunciation dictionary — dedicated subscreen.
 * 9. Account — Royal Road / GitHub / Anthropic Teams sign-ins.
 * 10. Memory Palace — daemon host config + probe.
 * 11. Developer — Debug screen + advanced toggles.
 * 12. About — version sigil + open-source notices.
 * 13. All settings (legacy long page) — escape hatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onNavigateBack: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenPluginManager: () -> Unit,
    onOpenAiSessions: () -> Unit,
    onOpenPronunciationDict: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // The hub renders as a single brass-edged group card. Same
            // brass surface as the rest of Settings — SettingsGroupCard
            // wraps Card(surfaceContainerHigh, shapes.large) and a 1-dp
            // inter-row peek. One card with many link rows reads as a
            // navigation index rather than a fragmented card grid.
            SectionHeading(
                label = "Storyvox",
                icon = Icons.Outlined.AutoAwesome,
                descriptor = "Pick a section to configure.",
            )
            SettingsGroupCard {
                // Voice & Playback — most-touched, first.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Voice & Playback",
                    subtitle = "Voice, speed, cadence, pitch.",
                    onClick = onOpenAllSettings,
                )
                // Voice library — dedicated subscreen.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Voice library",
                    subtitle = "Pick a voice and hear samples.",
                    onClick = onOpenVoiceLibrary,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Reading",
                    subtitle = "Auto-advance, sleep timer, resume policy.",
                    onClick = onOpenAllSettings,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Speed,
                    title = "Performance",
                    subtitle = "Buffer, parallel synth, decoder choice.",
                    onClick = onOpenAllSettings,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "AI",
                    subtitle = "Chat model, grounding, recap.",
                    onClick = onOpenAllSettings,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoStories,
                    title = "AI sessions",
                    subtitle = "Review past chats and delete history.",
                    onClick = onOpenAiSessions,
                )
                // Plugin manager (#404). Dedicated subscreen with its own
                // search + filter chips + capability legend.
                SettingsHubRow(
                    icon = Icons.Outlined.Extension,
                    title = "Plugins",
                    subtitle = "Toggle backends — Fiction, Audio streams, Voice bundles.",
                    onClick = onOpenPluginManager,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = "Pronunciation dictionary",
                    subtitle = "Per-word phonetic overrides.",
                    onClick = onOpenPronunciationDict,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AccountCircle,
                    title = "Account",
                    subtitle = "Royal Road, GitHub, Anthropic Teams.",
                    onClick = onOpenAllSettings,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Memory Palace",
                    subtitle = "Daemon host, probe, integration.",
                    onClick = onOpenAllSettings,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Developer",
                    subtitle = "Debug overlay, log ring, advanced toggles.",
                    onClick = onOpenDebug,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version, sigil, open-source notices.",
                    onClick = onOpenAllSettings,
                )
                // Escape hatch — the legacy flat-scroll SettingsScreen
                // still works; users who want the old experience reach
                // it via this row. Subtitle pre-empts confusion: "yes,
                // everything you used to scroll through is still here".
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = "All settings",
                    subtitle = "Every setting on one long page (legacy).",
                    onClick = onOpenAllSettings,
                )
            }
        }
    }
}

/**
 * A hub link row. Shaped like [SettingsLinkRow] but with a brass-tinted
 * leading icon — wraps the [SettingsRow] primitive directly so we don't
 * have to widen [SettingsLinkRow]'s contract for the hub.
 *
 * Visible to [SettingsHubScreenSmokeTest] (`internal`) so the test can
 * count rows and assert their click handlers all wire through.
 */
@Composable
internal fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Issue #440 — the canonical Settings hub row catalog. Tests pin this
 * list so an accidental reorder or removal of a row in
 * [SettingsHubScreen] surfaces here first.
 *
 * The list is constructed lazily because the icon objects live in the
 * Compose runtime classloader; consumers either iterate it (tests) or
 * use it as documentation of the hub's intended shape. The actual
 * rendering in [SettingsHubScreen] mirrors this list manually, since
 * row-specific click handlers don't fit cleanly into a data class.
 *
 * Order matches the row order in [SettingsHubScreen]'s kdoc.
 */
data class SettingsHubSection(val title: String, val subtitle: String)

val SettingsHubSections: List<SettingsHubSection> = listOf(
    SettingsHubSection("Voice & Playback", "Voice, speed, cadence, pitch."),
    SettingsHubSection("Voice library", "Pick a voice and hear samples."),
    SettingsHubSection("Reading", "Auto-advance, sleep timer, resume policy."),
    SettingsHubSection("Performance", "Buffer, parallel synth, decoder choice."),
    SettingsHubSection("AI", "Chat model, grounding, recap."),
    SettingsHubSection("AI sessions", "Review past chats and delete history."),
    SettingsHubSection("Plugins", "Toggle backends — Fiction, Audio streams, Voice bundles."),
    SettingsHubSection("Pronunciation dictionary", "Per-word phonetic overrides."),
    SettingsHubSection("Account", "Royal Road, GitHub, Anthropic Teams."),
    SettingsHubSection("Memory Palace", "Daemon host, probe, integration."),
    SettingsHubSection("Developer", "Debug overlay, log ring, advanced toggles."),
    SettingsHubSection("About", "Version, sigil, open-source notices."),
    SettingsHubSection("All settings", "Every setting on one long page (legacy)."),
)
