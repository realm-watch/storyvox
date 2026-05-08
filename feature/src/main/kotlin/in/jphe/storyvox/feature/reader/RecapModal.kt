package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * The Chapter Recap modal. Streams the librarian's response into a
 * scrollable text body; brass cursor blinks at the end while the
 * stream is active. Cancel / Try again / Close buttons depending on
 * state.
 *
 * State machine — see [RecapUiState]:
 *  - Hidden — modal not rendered.
 *  - Loading — "Asking the librarian…" + spinner; Cancel is live.
 *  - Streaming — partial text + blinking cursor; Cancel is live.
 *  - Done — full text + Close button (and a "Read aloud" button
 *    that's greyed out with "coming soon" tooltip — the synth-from-
 *    arbitrary-text path lands in a follow-up PR).
 *  - Error — error message + recovery action (Settings link / Try
 *    again / Close).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapModal(
    state: RecapUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state is RecapUiState.Hidden) return

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Header — subtitle copy switches with state so the
            // empty/error case doesn't read as "we're working on it"
            // when the body literally says "set up AI first" (#153).
            // Loading + Streaming use the present-progressive in-flight
            // copy; Done describes what's now on screen; Error /
            // unconfigured falls back to a neutral descriptor of the
            // feature itself.
            Column {
                Text(
                    text = "Recap so far",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = subtitleFor(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopStart,
            ) {
                when (state) {
                    is RecapUiState.Loading -> LoadingBody()
                    is RecapUiState.Streaming -> StreamingBody(state.text)
                    is RecapUiState.Done -> Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is RecapUiState.Error -> ErrorBody(state)
                    RecapUiState.Hidden -> Unit  // unreachable
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                when (state) {
                    is RecapUiState.Loading,
                    is RecapUiState.Streaming -> {
                        BrassButton(
                            label = "Cancel",
                            onClick = onCancel,
                            variant = BrassButtonVariant.Secondary,
                        )
                    }
                    is RecapUiState.Done -> {
                        BrassButton(
                            label = "Close",
                            onClick = onCancel,
                            variant = BrassButtonVariant.Secondary,
                        )
                        // Read-aloud is P1 — surfaced greyed-out with a
                        // tooltip-on-press in a follow-up PR. For PR-1
                        // we leave the slot empty so the modal doesn't
                        // promise something we don't deliver.
                    }
                    is RecapUiState.Error -> {
                        when (state.kind) {
                            RecapUiState.ErrorKind.NotConfigured,
                            RecapUiState.ErrorKind.AuthFailed -> {
                                BrassButton(
                                    label = "Open Settings",
                                    onClick = onOpenSettings,
                                    variant = BrassButtonVariant.Primary,
                                )
                                BrassButton(
                                    label = "Close",
                                    onClick = onCancel,
                                    variant = BrassButtonVariant.Secondary,
                                )
                            }
                            RecapUiState.ErrorKind.Transport,
                            RecapUiState.ErrorKind.ProviderError -> {
                                BrassButton(
                                    label = "Try again",
                                    onClick = onRetry,
                                    variant = BrassButtonVariant.Primary,
                                )
                                BrassButton(
                                    label = "Close",
                                    onClick = onCancel,
                                    variant = BrassButtonVariant.Secondary,
                                )
                            }
                        }
                    }
                    RecapUiState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagicSpinner()
        Text(
            text = "  Asking the librarian…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Streaming text + a blinking brass cursor. The cursor is a unicode
 * block character "▌" that fades in and out via an infinite alpha
 * animation; we honor LocalReducedMotion (no animation = static
 * cursor at full opacity) per the rest of Library Nocturne.
 */
@Composable
private fun StreamingBody(text: String) {
    val reducedMotion = LocalReducedMotion.current
    val cursorAlpha = if (reducedMotion) {
        1f
    } else {
        val infinite = rememberInfiniteTransition(label = "recap-cursor")
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

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "▌",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(cursorAlpha),
        )
    }
}

/**
 * Subtitle copy for the [RecapModal] header, picked by state. Issue #153 —
 * the legacy hard-coded "Asking the librarian about the last few chapters."
 * read as in-progress regardless of state, which clashed with the body's
 * "Set up AI in Settings to use Recap." in the unconfigured/error case.
 *
 * - **Loading / Streaming** — present-progressive, matches the spinner /
 *   blinking-cursor in-flight visual.
 * - **Done** — past tense, matches the "this is the recap" body.
 * - **Error** (NotConfigured / AuthFailed / Transport / ProviderError) —
 *   neutral feature-descriptor, so the user reads the body's recovery copy
 *   without a contradictory "we're already on it" subtitle.
 *
 * Internal so [`PunctuationPauseTickPlacementTest`-style] unit testing
 * could cover the mapping if it grows beyond five branches.
 */
internal fun subtitleFor(state: RecapUiState): String = when (state) {
    is RecapUiState.Loading,
    is RecapUiState.Streaming -> "Asking the librarian about the last few chapters."
    is RecapUiState.Done -> "Here's what happened in the last few chapters."
    is RecapUiState.Error -> "Quick chapter summaries from your AI provider."
    RecapUiState.Hidden -> ""  // unreachable — modal returns early when Hidden
}

@Composable
private fun ErrorBody(state: RecapUiState.Error) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    // Suppress "unused parameter" — Color is used via colorScheme.
    @Suppress("UNUSED_VARIABLE") val unused: Color = MaterialTheme.colorScheme.error
}
