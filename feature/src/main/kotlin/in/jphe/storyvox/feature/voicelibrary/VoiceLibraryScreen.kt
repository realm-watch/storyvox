package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun VoiceLibraryScreen(
    onBack: () -> Unit,
    viewModel: VoiceLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice library") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val featured = state.featured
        val installed = state.installed
        val available = state.available
        val isEmpty = featured.isEmpty() && installed.isEmpty() && available.isEmpty()

        if (isEmpty) {
            EmptyState(modifier = Modifier.padding(padding).fillMaxSize().padding(spacing.md))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.md),
        ) {
            if (featured.isNotEmpty()) {
                item { SectionHeader("⭐ Featured", count = featured.size) }
                itemsIndexed(featured, key = { _, item -> "f-${item.id}" }) { index, voice ->
                    val downloading = state.currentDownload
                    val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                    VoiceRow(
                        voice = voice,
                        isActive = voice.id == state.activeVoiceId,
                        downloadingProgress = rowProgress,
                        onTap = { if (downloading == null || voice.isInstalled) viewModel.onRowTapped(voice) },
                        onLongPress = if (voice.isInstalled) ({ viewModel.requestDelete(voice) }) else null,
                        modifier = Modifier
                            .animateItem()
                            .cascadeReveal(index = index, key = voice.id),
                    )
                }
                item { Spacer(modifier = Modifier.height(spacing.md)) }
            }

            item { SectionHeader("Installed", count = installed.size) }
            if (installed.isEmpty()) {
                item {
                    Text(
                        "No voices installed yet. Pick one below to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            } else {
                itemsIndexed(installed, key = { _, item -> "i-${item.id}" }) { index, voice ->
                    VoiceRow(
                        voice = voice,
                        isActive = voice.id == state.activeVoiceId,
                        downloadingProgress = null,
                        onTap = { viewModel.onRowTapped(voice) },
                        onLongPress = { viewModel.requestDelete(voice) },
                        modifier = Modifier
                            .animateItem()
                            .cascadeReveal(index = index, key = voice.id),
                    )
                }
            }

            if (available.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader("Available", count = available.size, dim = true)
                }
                if (available.any { it.engineType is EngineType.Kokoro }) {
                    item { KokoroBundleNote() }
                }
                itemsIndexed(available, key = { _, item -> "a-${item.id}" }) { index, voice ->
                    val downloading = state.currentDownload
                    val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                    VoiceRow(
                        voice = voice,
                        isActive = false,
                        downloadingProgress = rowProgress,
                        onTap = { if (downloading == null) viewModel.onRowTapped(voice) },
                        onLongPress = null,
                        modifier = Modifier
                            .animateItem()
                            .cascadeReveal(index = index, key = voice.id),
                    )
                }
            }
        }
    }

    val pending = state.pendingDelete
    if (pending != null) {
        DeleteConfirmDialog(
            voice = pending,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** Explains the unusual storage model of Kokoro voices once, inline in
 *  the Available list. The 53 Kokoro speakers all share one ~380 MB
 *  bundled download — picking any of them downloads the model once,
 *  and the remaining 52 then activate instantly. Inference is heavier
 *  than Piper, so on modest hardware a small inter-sentence pause is
 *  expected. Heading off both UX surprises upfront. */
@Composable
private fun KokoroBundleNote() {
    val spacing = LocalSpacing.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "🌐 Kokoro voices share one bundle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "All 53 Kokoro speakers (English, Spanish, French, Hindi, Italian, Japanese, Portuguese, Chinese) share one ~380 MB bundle (model + speakers + tokens). The first Kokoro voice you pick downloads it; every Kokoro voice after that activates instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Kokoro inference is heavier than Piper — on modest hardware you may notice a small pause between sentences while the next chunk renders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int, dim: Boolean = false) {
    val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun VoiceRow(
    voice: UiVoiceInfo,
    isActive: Boolean,
    /** null = not downloading; -1f = indeterminate; 0..1 = determinate */
    downloadingProgress: Float?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (isActive) brass else outline.copy(alpha = 0.35f)
    val isAvailable = !voice.isInstalled

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            voice.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isAvailable && downloadingProgress == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.size(spacing.xs))
                            ActiveChip()
                        }
                    }
                    Text(
                        text = "${voice.language}  ·  ${formatBytes(voice.sizeBytes)}  ·  ${voice.qualityLevel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowAction(
                    voice = voice,
                    isActive = isActive,
                    isDownloading = downloadingProgress != null,
                    onTap = onTap,
                )
            }
            if (downloadingProgress != null) {
                Spacer(modifier = Modifier.size(spacing.xxs))
                if (downloadingProgress < 0f) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { downloadingProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveChip() {
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(brass.copy(alpha = 0.18f))
            .border(width = 1.dp, color = brass, shape = RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = brass,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "ACTIVE",
            style = MaterialTheme.typography.labelSmall,
            color = brass,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RowAction(
    voice: UiVoiceInfo,
    isActive: Boolean,
    isDownloading: Boolean,
    onTap: () -> Unit,
) {
    when {
        isDownloading -> Text(
            "Downloading…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        voice.isInstalled && isActive -> Text(
            "In use",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        voice.isInstalled -> BrassButton(
            label = "Activate",
            onClick = onTap,
            variant = BrassButtonVariant.Secondary,
        )
        else -> BrassButton(
            label = "Download",
            onClick = onTap,
            variant = BrassButtonVariant.Primary,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    voice: UiVoiceInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${voice.displayName}?") },
        text = {
            Text(
                "Frees ${formatBytes(voice.sizeBytes)}. You can re-download anytime from this screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            BrassButton(
                label = "Delete",
                onClick = onConfirm,
                variant = BrassButtonVariant.Primary,
            )
        },
        dismissButton = {
            BrassButton(
                label = "Cancel",
                onClick = onDismiss,
                variant = BrassButtonVariant.Text,
            )
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape),
            shape = CircleShape,
            glyphSize = 96.dp,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Text(
            "Voices loading…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            "Catalog is being summoned. This shouldn't take more than a moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
