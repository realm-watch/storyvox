package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceGender
import `in`.jphe.storyvox.playback.voice.VoiceLibrarySection
import `in`.jphe.storyvox.playback.voice.flagForLanguage
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
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
                // Issues #275 + #276 — all settings sub-screens get a
                // back arrow on the left of the TopAppBar, matching the
                // Sessions screen pattern (which is already canonical).
                // Voice library had a TopAppBar but no navigationIcon;
                // Pronunciation had no TopAppBar at all and a bare
                // 'Back' BrassButton mid-screen — three different
                // patterns for the same role across three screens.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val favorites = state.favorites
        val installedByEngine = state.installedByEngine
        val availableByEngine = state.availableByEngine
        val installedTotal = installedByEngine.values.sumOf { tiers -> tiers.values.sumOf { it.size } }
        val availableTotal = availableByEngine.values.sumOf { tiers -> tiers.values.sumOf { it.size } }
        val availableHasKokoro = availableByEngine.containsKey(VoiceEngine.Kokoro)
        val isEmpty = favorites.isEmpty() &&
            installedTotal == 0 && availableTotal == 0

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
            // STARRED — surfaces the user's pinned voices above
            // everything else. Hidden entirely when empty so the screen
            // doesn't render a "no starred voices" stub for first-time users.
            if (favorites.isNotEmpty()) {
                item { SectionHeader("★ Starred", count = favorites.size) }
                itemsIndexed(favorites, key = { _, item -> "fav-${item.id}" }) { index, voice ->
                    val downloading = state.currentDownload
                    val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                    VoiceRow(
                        voice = voice,
                        isActive = voice.id == state.activeVoiceId,
                        isFavorite = true,
                        downloadingProgress = rowProgress,
                        onTap = { if (downloading == null || voice.isInstalled) viewModel.onRowTapped(voice) },
                        onLongPress = if (voice.isInstalled) ({ viewModel.requestDelete(voice) }) else null,
                        onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                        modifier = Modifier
                            .animateItem()
                            .cascadeReveal(index = index, key = "fav-${voice.id}"),
                    )
                }
                item { Spacer(modifier = Modifier.height(spacing.md)) }
            }

            // INSTALLED — split into tier sub-sections (Studio → Low). When
            // the user has nothing installed and no favourites, surface
            // a one-line nudge under the empty Installed header rather
            // than skipping it entirely; preserves the screen's existing
            // mental model.
            item { SectionHeader("Installed", count = installedTotal) }
            if (installedTotal == 0) {
                item {
                    Text(
                        "No voices installed yet. Pick one below to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            } else {
                installedByEngine.forEach { (engine, tiers) ->
                    val engineCount = tiers.values.sumOf { it.size }
                    val engineKey = EngineKey(VoiceLibrarySection.Installed, engine.toCoreId())
                    val isCollapsed = engineKey in state.collapsedEngines
                    item(key = "i-engine-${engine.name}") {
                        EngineSubHeader(
                            engine = engine,
                            count = engineCount,
                            isCollapsed = isCollapsed,
                            onToggle = {
                                viewModel.toggleEngineCollapsed(VoiceLibrarySection.Installed, engine)
                            },
                        )
                    }
                    if (!isCollapsed) {
                        tiers.forEach { (tier, voicesInTier) ->
                            item(key = "i-${engine.name}-tier-${tier.name}") {
                                TierSubHeader(tier = tier, count = voicesInTier.size)
                            }
                            itemsIndexed(
                                voicesInTier,
                                key = { _, item -> "i-${item.id}" },
                            ) { index, voice ->
                                VoiceRow(
                                    voice = voice,
                                    isActive = voice.id == state.activeVoiceId,
                                    isFavorite = voice.id in state.favoriteIds,
                                    downloadingProgress = null,
                                    onTap = { viewModel.onRowTapped(voice) },
                                    onLongPress = { viewModel.requestDelete(voice) },
                                    onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                                    modifier = Modifier
                                        .animateItem()
                                        .cascadeReveal(index = index, key = voice.id),
                                )
                            }
                        }
                    }
                }
            }

            if (availableTotal > 0) {
                item {
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader("Available", count = availableTotal, dim = true)
                }
                if (availableHasKokoro) {
                    item { KokoroBundleNote() }
                }
                availableByEngine.forEach { (engine, tiers) ->
                    val engineCount = tiers.values.sumOf { it.size }
                    val engineKey = EngineKey(VoiceLibrarySection.Available, engine.toCoreId())
                    val isCollapsed = engineKey in state.collapsedEngines
                    item(key = "a-engine-${engine.name}") {
                        EngineSubHeader(
                            engine = engine,
                            count = engineCount,
                            dim = true,
                            isCollapsed = isCollapsed,
                            onToggle = {
                                viewModel.toggleEngineCollapsed(VoiceLibrarySection.Available, engine)
                            },
                        )
                    }
                    if (!isCollapsed) {
                        tiers.forEach { (tier, voicesInTier) ->
                            item(key = "a-${engine.name}-tier-${tier.name}") {
                                TierSubHeader(tier = tier, count = voicesInTier.size, dim = true)
                            }
                            val downloading = state.currentDownload
                            itemsIndexed(
                                voicesInTier,
                                key = { _, item -> "a-${item.id}" },
                            ) { index, voice ->
                                val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                                VoiceRow(
                                    voice = voice,
                                    isActive = false,
                                    isFavorite = voice.id in state.favoriteIds,
                                    downloadingProgress = rowProgress,
                                    onTap = { if (downloading == null) viewModel.onRowTapped(voice) },
                                    onLongPress = null,
                                    onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                                    modifier = Modifier
                                        .animateItem()
                                        .cascadeReveal(index = index, key = voice.id),
                                )
                            }
                        }
                    }
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

/** Engine label + count rendered under a [SectionHeader] (Piper /
 *  Kokoro). Visually slightly louder than the per-tier sub-header
 *  beneath it so the read order is Section → Engine → Tier → Row.
 *  Empty engine groups never reach this composable — the ViewModel's
 *  [groupByEngineThenTier] drops them.
 *
 *  Tappable per #130 — the whole row toggles the collapse state and
 *  the trailing chevron flips between [Icons.Outlined.ExpandMore]
 *  (collapsed) and [Icons.Outlined.ExpandLess] (expanded). The
 *  chevron lives at the row's end via a `Spacer(weight = 1f)` so
 *  the label/count stay left-aligned even on wide screens. */
@Composable
private fun EngineSubHeader(
    engine: VoiceEngine,
    count: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    dim: Boolean = false,
) {
    val baseColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    val label = when (engine) {
        VoiceEngine.Piper -> "Piper"
        VoiceEngine.Kokoro -> "Kokoro"
        // Azure HD voices land in their own sub-header so the cloud
        // round-trip story is one glance away. Catalog labels carry a
        // ☁️ glyph, but the section header restates "Azure" plainly so
        // a user scanning the library doesn't need to decode a single
        // emoji to know what's cloud vs local.
        VoiceEngine.Azure -> "Azure (Cloud)"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = baseColor.copy(alpha = 0.95f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelMedium,
            color = baseColor.copy(alpha = 0.65f),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (isCollapsed) "Expand $label" else "Collapse $label",
            tint = baseColor.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Tier label + count rendered under an [EngineSubHeader] (Studio /
 *  High / Medium / Low). Visually quieter than the engine sub-header so
 *  the Section → Engine grouping reads first; the tier label is a
 *  refinement, not a peer. */
@Composable
private fun TierSubHeader(tier: QualityLevel, count: Int, dim: Boolean = false) {
    val baseColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val (label, accent) = tierDisplay(tier)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        if (accent.isNotEmpty()) {
            Text(
                accent,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.size(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = baseColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelSmall,
            color = baseColor.copy(alpha = 0.55f),
        )
    }
}

/** Map a tier to its (display label, optional emoji accent). Studio
 *  earns the trophy; the rest stay text-only so the visual hierarchy
 *  reads top-to-bottom without competing decorations. */
private fun tierDisplay(tier: QualityLevel): Pair<String, String> = when (tier) {
    QualityLevel.Studio -> "Studio" to "🎙️"
    QualityLevel.High -> "High" to ""
    QualityLevel.Medium -> "Medium" to ""
    QualityLevel.Low -> "Low" to ""
}

@Composable
private fun VoiceRow(
    voice: UiVoiceInfo,
    isActive: Boolean,
    isFavorite: Boolean,
    /** null = not downloading; -1f = indeterminate; 0..1 = determinate */
    downloadingProgress: Float?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
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
                FavoriteToggle(
                    isFavorite = isFavorite,
                    onToggle = onToggleFavorite,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Title: "<flag> <name>". Flag is derived from
                        // language code at render time (see #128) so the
                        // catalog stays render-agnostic and a flag mapping
                        // change reaches every row in one edit.
                        //
                        // #250 — `weight(1f, fill = false) + maxLines + ellipsis`
                        // so long names (live Azure roster e.g.
                        // "☁️ Ava (Dragon HD) · en-US · Dragon HD")
                        // truncate gracefully instead of pushing the
                        // ActiveChip into a "ACTIV/E" wrap. fill = false
                        // means the title takes only the space it needs
                        // when short, leaving room for the chip; with
                        // weight(1f, fill = true) it would always be
                        // full-width and the chip would never sit beside
                        // a short name like "Aria".
                        Text(
                            "${flagForLanguage(voice.language)} ${voice.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isAvailable && downloadingProgress == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            // Issue #263 — Azure variant suffixes (Multilingual /
                            // Turbo / Studio / HD) fall exactly where the
                            // 1-line cutoff used to clip on Flip3's 1080px
                            // inner display, so users couldn't distinguish
                            // 'Adam Multilingual' from 'Adam Studio'. Allow
                            // two lines; ellipsis still kicks in past that
                            // for the rare 30+ char name.
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.size(spacing.xs))
                            ActiveChip()
                        }
                    }
                    // Subtitle: "<Engine> · <Tier> · <Gender>". Gender
                    // segment is dropped when [VoiceGender.Unknown] so
                    // the line collapses to "Engine · Tier" rather than
                    // showing an empty trailing dot. Size/language data
                    // moved out of the subtitle in #128 — language is
                    // already encoded in the title flag, and per-voice
                    // size is more useful in the delete-confirm dialog
                    // (the only place it directly drives a decision).
                    Text(
                        text = voiceSubtitle(voice),
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
                // Negative progress = the upstream signal has no
                // Content-Length yet (sherpa-onnx HEAD probe in flight),
                // so we render the indeterminate brass comet. Otherwise
                // the determinate fill smooth-animates as bytes roll in.
                BrassProgressBar(
                    progress = if (downloadingProgress < 0f) null else downloadingProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Star-toggle leading the row. Filled = starred (pinned to Starred
 *  section), outlined = not. The whole row is wrapped in a parent
 *  `combinedClickable` (tap = activate/download, long-press = delete);
 *  in Compose, `combinedClickable` keeps the pointer event during the
 *  long-press timeout window, which can starve a nested `IconButton`'s
 *  own clickable — that's the regression #106 reports. We sidestep the
 *  arbitration by giving the toggle its own `Box.clickable` directly
 *  (no nested IconButton's gesture detector to compete with) and
 *  letting Compose's standard hit-testing route taps to the deepest
 *  descendant that has a clickable modifier. The bounded ripple inside
 *  the round clip keeps the visual affordance equivalent to a Material
 *  IconButton without the long-press race. */
@Composable
private fun FavoriteToggle(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    val tint = if (isFavorite) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (isFavorite) "Remove from starred" else "Add to starred",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
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

/** Trailing action surfaced on each [VoiceRow]. Three states only:
 *  Downloading (text spinner copy), Installed-but-not-active (Activate
 *  button), and Available (Download button). The active+installed case
 *  used to render an "In use" Text — dropped in #127 because the brass
 *  border + ACTIVE chip in the title row already mark active state, so
 *  the trailing label was redundant clutter. Active+installed rows now
 *  show no trailing action; the row is still tap-targeted via the
 *  enclosing `combinedClickable` (a no-op while active, since
 *  `onRowTapped` only switches if id != active). */
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
        voice.isInstalled && isActive -> Unit
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

/** Compose the per-row subtitle: `<Engine> · <Tier> · <Gender>`.
 *  Gender is dropped from the line when [VoiceGender.Unknown] (a few
 *  Piper multi-speaker corpora carry no gender metadata) so the
 *  subtitle collapses cleanly to `<Engine> · <Tier>` rather than
 *  showing an empty trailing segment. Pulled out of [VoiceRow] so
 *  the format is unit-testable from a JVM test without spinning up
 *  the screen — see [voicelibrary] tests. */
internal fun voiceSubtitle(voice: UiVoiceInfo): String {
    val engineLabel = when (voice.engineType) {
        is EngineType.Piper -> "Piper"
        is EngineType.Kokoro -> "Kokoro"
        is EngineType.Azure -> "Azure"
    }
    val tierLabel = when (voice.qualityLevel) {
        QualityLevel.Studio -> "Studio"
        QualityLevel.High -> "High"
        QualityLevel.Medium -> "Medium"
        QualityLevel.Low -> "Low"
    }
    val genderLabel = when (voice.gender) {
        VoiceGender.Female -> "Female"
        VoiceGender.Male -> "Male"
        VoiceGender.Unknown -> null
    }
    val parts = listOfNotNull(engineLabel, tierLabel, genderLabel)
    return parts.joinToString(separator = "  ·  ")
}
