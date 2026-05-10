package `in`.jphe.storyvox.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Q&A chat surface attached to a fiction. The user types questions
 * about plot, characters, pacing, and writing craft; the AI streams
 * its replies in. One chat history per fiction (deterministic session
 * id `chat:<fictionId>`) so closing and re-opening picks back up.
 *
 * Library Nocturne aesthetic: brass-on-warm-dark bubbles, primary
 * surface for user turns, surfaceVariant for assistant. Streaming
 * tokens render with a brass blinking-cursor block character — same
 * visual vocabulary as the Recap modal so the in-flight signal is
 * consistent across AI surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Input prefill seeded by the reader's long-press character lookup
    // (#188). The VM emits this once via the `prefill` StateFlow; the
    // ChatInput observes it, copies into its local TextFieldValue, then
    // calls `consumePrefill()` so a subsequent recomposition can't
    // overwrite the user's edits.
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()

    // Auto-scroll to the latest content. We anchor on (turn count,
    // streaming length) so a slow stream still keeps the bottom in
    // view rather than only firing once when the assistant turn
    // finalises. User-driven scrolling isn't blocked — the next
    // delta will pull them back; if that becomes annoying we can
    // gate on `!listState.isScrollInProgress` later.
    LaunchedEffect(state.turns.size, state.streaming?.length) {
        val total = state.turns.size + (if (state.streaming != null) 1 else 0)
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val barTitle = state.fictionTitle?.let { "Ask the AI · $it" } ?: "Ask the AI"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        barTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
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
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.noProvider -> EmptyStateNoProvider(
                    onOpenSettings = onOpenAiSettings,
                    modifier = Modifier.weight(1f),
                )
                state.turns.isEmpty() && state.streaming == null -> EmptyStatePrompt(
                    fictionTitle = state.fictionTitle,
                    modifier = Modifier.weight(1f),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = spacing.md,
                        vertical = spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(state.turns, key = { "${it.role}:${it.text.hashCode()}" }) { turn ->
                        TurnBubble(
                            turn = turn,
                            isReadingThis = state.readingText == turn.text,
                            isAnythingReading = state.readingText != null,
                            onReadAloud = { viewModel.readAloud(turn.text) },
                            onStopReadAloud = viewModel::stopReadAloud,
                        )
                    }
                    state.streaming?.let { partial ->
                        item(key = "streaming") { StreamingBubble(partial) }
                    }
                }
            }

            state.error?.let { err ->
                ErrorBanner(
                    error = err,
                    onDismiss = viewModel::dismissError,
                    onOpenSettings = onOpenAiSettings,
                )
            }

            if (!state.noProvider) {
                ChatInput(
                    enabled = state.streaming == null,
                    onSend = viewModel::send,
                    prefill = prefill,
                    onPrefillConsumed = viewModel::consumePrefill,
                )
            }
        }
    }
}

// ── Bubbles ────────────────────────────────────────────────────────

@Composable
private fun TurnBubble(
    turn: ChatTurn,
    isReadingThis: Boolean = false,
    isAnythingReading: Boolean = false,
    onReadAloud: () -> Unit = {},
    onStopReadAloud: () -> Unit = {},
) {
    val isUser = turn.role == ChatTurn.Role.User
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(containerColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
            )
        }
        // Issue #214 — assistant turns get a Read-aloud / Stop
        // affordance below the bubble. Hidden on user turns (no point
        // reading what the user typed back to them) and on the
        // streaming partial (bubble has its own cursor, the read
        // would beat the response).
        if (!isUser && turn.text.isNotBlank()) {
            ReadAloudButton(
                isReadingThis = isReadingThis,
                isAnythingReading = isAnythingReading,
                onReadAloud = onReadAloud,
                onStopReadAloud = onStopReadAloud,
            )
        }
    }
}

@Composable
private fun ReadAloudButton(
    isReadingThis: Boolean,
    isAnythingReading: Boolean,
    onReadAloud: () -> Unit,
    onStopReadAloud: () -> Unit,
) {
    // Three-state button: this-is-reading (Stop), nothing-reading (Play),
    // another-bubble-reading (Play, dimmed). The dimmed state is
    // tappable — pressing Play on bubble B while bubble A reads will
    // stop A and start B, which matches user intent ("read THAT one").
    val (icon, label, onClick) = when {
        isReadingThis -> Triple(Icons.Outlined.Stop, "Stop reading", onStopReadAloud)
        else -> Triple(Icons.Outlined.VolumeUp, "Read aloud", onReadAloud)
    }
    val tint = if (isReadingThis) {
        MaterialTheme.colorScheme.primary
    } else if (isAnythingReading) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    androidx.compose.material3.TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 0.dp,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/**
 * Mid-stream assistant bubble — same shape as the finalised assistant
 * bubble but with a blinking brass cursor appended. Honors
 * LocalReducedMotion: cursor stays static at full opacity for users
 * with reduce-motion, matching the rest of Library Nocturne.
 */
@Composable
private fun StreamingBubble(text: String) {
    val reducedMotion = LocalReducedMotion.current
    val cursorAlpha = if (reducedMotion) {
        1f
    } else {
        val infinite = rememberInfiniteTransition(label = "chat-cursor")
        val alpha by infinite.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        alpha
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "▌",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(cursorAlpha),
                )
            }
        }
    }
}

// ── Empty states ───────────────────────────────────────────────────

@Composable
private fun EmptyStateNoProvider(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Pick a provider in Settings → AI to start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        BrassButton(
            label = "Open Settings",
            onClick = onOpenSettings,
            variant = BrassButtonVariant.Primary,
        )
    }
}

@Composable
private fun EmptyStatePrompt(
    fictionTitle: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (fictionTitle != null) {
                "Ask anything about \"$fictionTitle\" — plot, characters, " +
                    "pacing, craft. The librarian won't spoil what's ahead."
            } else {
                "Ask anything about this fiction. The librarian won't " +
                    "spoil what's ahead."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Input + error banner ───────────────────────────────────────────

/** Pre-fill chips for the most common chat asks (#213). Tap inserts
 *  the prompt into the input field but does NOT auto-send — the user
 *  can edit / append before sending. Visible only when input is empty
 *  so they don't clutter the chat once a conversation is rolling.
 *
 *  Order is touch-frequency: 'What did I miss?' is the dominant ask
 *  for resumed listening; 'Who is X?' is character lookup; the rest
 *  catch the long tail. Localized labels are a future follow-up. */
private val QuickActionPrompts: List<String> = listOf(
    "What did I miss?",
    "Who is this character?",
    "Explain that",
    "Where are we?",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatInput(
    enabled: Boolean,
    onSend: (String) -> Unit,
    /** One-shot starter text from the long-press lookup deep-link
     *  (#188). When non-null+non-blank, the input field is seeded with
     *  this value (replacing whatever the user had typed — but this
     *  only fires on first emission per nav, so a typing user never
     *  sees their text wiped mid-flow). After applying we call
     *  [onPrefillConsumed] so the VM clears the latch. */
    prefill: String? = null,
    onPrefillConsumed: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var text by remember { mutableStateOf("") }

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            text = prefill
            onPrefillConsumed()
        }
    }

    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        if (enabled && text.isBlank()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                QuickActionPrompts.forEach { prompt ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = { text = prompt },
                        label = { Text(prompt, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text("Ask a question…") },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    val toSend = text.trim()
                    if (toSend.isNotEmpty()) {
                        onSend(toSend)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: ChatError,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val showSettings = error is ChatError.NotConfigured ||
        error is ChatError.AuthFailed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (showSettings) {
            BrassButton(
                label = "Settings",
                onClick = onOpenSettings,
                variant = BrassButtonVariant.Text,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
