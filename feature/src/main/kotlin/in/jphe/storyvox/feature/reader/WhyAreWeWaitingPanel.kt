package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.playback.diagnostics.WaitReason
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * "Why are we waiting?" — the user-facing magical panel that surfaces a
 * typed [WaitReason] whenever no audio is reaching the speakers. Lives
 * above the cover in [AudiobookView]; renders in Library Nocturne brass
 * with a slow-pulsing sigil, the reason headline, an optional thin
 * progress bar, a secondary explanatory line, and (for recoverable
 * reasons) a brass-bordered Retry chip.
 *
 * The panel is bound by [AnimatedVisibility] to `reason != null`, so it
 * slides into view when audio stops and slides out when playback
 * resumes — never popping the rest of the player UI.
 *
 * Accessibility: the whole panel is a polite live region; TalkBack
 * announces a new reason as it arrives. Each variant carries its own
 * `contentDescription` because the secondary line + retry chip add
 * context the headline alone can't convey.
 */
@Composable
fun WhyAreWeWaitingPanel(
    reason: WaitReason?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current

    AnimatedVisibility(
        visible = reason != null,
        enter = fadeIn(tween(motion.standardDurationMs, easing = motion.standardEasing)) +
            expandVertically(tween(motion.standardDurationMs, easing = motion.standardEasing)),
        exit = fadeOut(tween(motion.standardDurationMs, easing = motion.standardEasing)) +
            shrinkVertically(tween(motion.standardDurationMs, easing = motion.standardEasing)),
        modifier = modifier,
    ) {
        // Snapshot the reason so the composable body doesn't recompose
        // back to null mid-exit-animation; AnimatedVisibility keeps the
        // last non-null reason on screen during exit.
        val r = reason ?: return@AnimatedVisibility

        val brass = MaterialTheme.colorScheme.primary
        // 5 % brass washed onto the surface background — visible against
        // the Library Nocturne dark surface without competing with the
        // cover for attention.
        val panelBg = brass.copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.surface)
        val borderColor = brass.copy(alpha = 0.30f)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(panelBg)
                .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(5.dp))
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "${r.message}. ${secondaryLineFor(r)}"
                },
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // Headline row — pulsing brass sigil + EB Garamond headline.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                PulsingBrassSigil(
                    color = brass,
                    reducedMotion = reducedMotion,
                )
                Text(
                    text = r.message,
                    color = brass,
                    fontFamily = FontFamily.Serif, // EB Garamond proxy — Library Nocturne maps Serif → EB Garamond
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            // Optional thin progress bar. 2 dp tall at 70 % opacity per
            // spec; only shown when the reason carries a known fraction.
            r.progressFraction?.let { frac ->
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .alpha(0.70f),
                    color = brass,
                    trackColor = brass.copy(alpha = 0.15f),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            // Secondary line — small Inter (proxy: SansSerif) 12 sp at
            // brass-50, explains *what's happening* in one sentence.
            Text(
                text = secondaryLineFor(r),
                color = brass.copy(alpha = 0.65f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Optional action chip — brass-bordered, right-aligned. Only
            // rendered when the reason is user-actionable.
            if (r.isUserActionable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val (label, action) = chipLabelAndActionFor(r, onRetry, onOpenSettings)
                    BrassActionChip(
                        label = label,
                        onClick = action,
                        borderColor = borderColor,
                        textColor = brass,
                    )
                }
            }
        }
    }
}

/**
 * Pulsing brass sigil — a 12 dp dot that fades 0.5 → 1.0 → 0.5 alpha on a
 * 1.5 s period. Honors reduced motion: when on, the sigil holds at the
 * mid-alpha so the visual still reads as "we're watching for output"
 * without animation.
 */
@Composable
private fun PulsingBrassSigil(
    color: Color,
    reducedMotion: Boolean,
) {
    val alpha = if (reducedMotion) 0.75f else {
        val transition = androidx.compose.animation.core
            .rememberInfiniteTransition(label = "why-waiting-sigil")
        val a by transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sigil-alpha",
        )
        a
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(6.dp))
            .background(color),
    )
}

/**
 * Brass-bordered action chip used for the panel's optional retry /
 * settings affordance. Keeps the visual lighter than a Material Button
 * (which would compete with the play control downstream).
 */
@Composable
private fun BrassActionChip(
    label: String,
    onClick: () -> Unit,
    borderColor: Color,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

/**
 * Per-variant secondary line copy. Each variant gets one sentence that
 * explains *what* storyvox is waiting on, in plain language. EB Garamond
 * headline + Inter explanation = library card aesthetic.
 */
internal fun secondaryLineFor(reason: WaitReason): String = when (reason) {
    is WaitReason.WarmingVoice ->
        "Voice models load once per chapter session."
    is WaitReason.LoadingChapter ->
        "Fetching the chapter text from its source."
    is WaitReason.BufferingNextSentence ->
        "The pipeline is rendering the next sentence's audio."
    is WaitReason.NetworkSlow ->
        "The chapter source is responding slowly."
    is WaitReason.FocusLost ->
        "Audio focus returns when the other audio ends."
    WaitReason.AudioRouteChange ->
        "Adjusting for the new output device."
    WaitReason.DeviceMuted ->
        "Raise the media volume to hear playback."
    is WaitReason.VoiceDownloadFailed ->
        "Voice download didn't complete. Check your connection."
    is WaitReason.AudioOutputStuck ->
        "If this persists, tap retry to nudge the pipeline."
}

/**
 * Per-variant label + action for the optional retry chip. Pairs with
 * [WaitReason.isUserActionable] — only the variants where actionable is
 * true reach this function.
 */
internal fun chipLabelAndActionFor(
    reason: WaitReason,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
): Pair<String, () -> Unit> = when (reason) {
    WaitReason.DeviceMuted -> "Open settings" to onOpenSettings
    is WaitReason.AudioOutputStuck -> "Retry" to onRetry
    is WaitReason.VoiceDownloadFailed -> "Retry" to onRetry
    is WaitReason.NetworkSlow -> "Retry" to onRetry
    is WaitReason.FocusLost -> "Resume" to onRetry
    WaitReason.AudioRouteChange -> "Resume" to onRetry
    else -> "Retry" to onRetry
}
