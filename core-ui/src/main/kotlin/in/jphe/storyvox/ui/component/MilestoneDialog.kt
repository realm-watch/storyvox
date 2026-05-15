package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.jphe.storyvox.ui.theme.BrassRamp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.theme.PlumRamp

/**
 * v0.5.00 graduation milestone — the one-time "thank-you" card.
 *
 * Library Nocturne hushed-and-warm aesthetic: deep purple substrate
 * (matching the player background), a brass hairline border, a faint
 * brass sigil glyph in the upper-left, EB-Garamond serif heading,
 * cream body text. A single brass-filled Continue button at the
 * bottom-right. Dismissable by tapping Continue OR tapping outside
 * the card.
 *
 * Animation: fades in over 400ms with a hint of scale (0.95 → 1.0)
 * via `LinearOutSlowInEasing` — the same curve the chapter-card
 * skeleton uses on settle. No confetti inside this dialog; confetti
 * is a separate easter-egg tied to the first natural chapter
 * completion ([MilestoneConfetti]). Both surfaces have independent
 * one-time DataStore gates — see
 * [`in`.jphe.storyvox.feature.api.MilestoneState].
 *
 * The copy is intentionally hand-tuned for v0.5.00 and not
 * templated. Each major milestone gets its own dialog instance with
 * its own copy; reuse across versions would mean stale wording.
 */
@Composable
fun MilestoneDialog(
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current

    // Run the fade+scale on first composition. The variable starts
    // false and flips to true in a LaunchedEffect so AnimatedVisibility
    // sees the false→true edge and plays the enter transition.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // #486 Phase 2 / #480 — fade+scale enter under reduced motion
        // collapses to a snap. The dialog still appears, just without
        // the 400ms ease-in.
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = visible,
            enter = if (reducedMotion) {
                androidx.compose.animation.EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
                    )
            },
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .padding(horizontal = spacing.lg),
                shape = MaterialTheme.shapes.large,
                // Deep purple — matches the Library Nocturne player
                // backdrop without falling back to the M3 dialog's
                // surfaceContainerHigh, which would render as plain
                // dark grey and break the brass-on-plum motif.
                color = MilestoneSubstrate,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(width = 1.dp, color = BrassRamp.Brass500.copy(alpha = 0.55f)),
            ) {
                Box {
                    // Faint brass sigil glyph in the upper-left — same
                    // visual posture as the realm-sigil About card's
                    // brass mark, scaled to 24dp and dropped to 0.22
                    // alpha so it reads as a watermark, not a header.
                    // a11y (#483): the sigil glyph is a decorative
                    // watermark — its size *is* its identity (a 24dp
                    // mark with a 0.22 alpha). Bound to bodyLarge
                    // (18sp on the typography ramp) so it still rides
                    // the system font-scale, just via the ramp rather
                    // than a raw literal.
                    Text(
                        text = "✦",
                        modifier = Modifier
                            .padding(start = spacing.md, top = spacing.sm)
                            .alpha(0.22f),
                        color = BrassRamp.Brass400,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Column(
                        modifier = Modifier
                            .padding(horizontal = spacing.lg, vertical = spacing.lg)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        // 🎉 + heading. Single emoji, brass-toned serif
                        // heading. The serif comes from BookBodyFamily
                        // (EB Garamond) via headlineSmall — the same
                        // serif that signs the About card.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            // a11y (#483): the emoji header reads at
                            // titleLarge — 22sp on the typography ramp.
                            // Bound to the theme token rather than a
                            // raw `fontSize = 22.sp` so system-wide
                            // font-scale and theme overrides flow.
                            Text(
                                text = "🎉",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "storyvox 0.5.00",
                                style = MaterialTheme.typography.headlineSmall,
                                color = BrassRamp.Brass300,
                            )
                        }
                        // The thank-you body. Cream text on plum, with
                        // an extra .copy on lineHeight so the
                        // multi-paragraph layout breathes — bodyMedium
                        // is tuned for dense settings rows, not for a
                        // quiet card that wants room around it.
                        Text(
                            text = "A small light, kept.",
                            // a11y (#483): titleMedium is 16sp/24lh on
                            // the ramp — anchor the body lead-in there
                            // instead of `.copy(fontSize = 16.sp)`.
                            style = MaterialTheme.typography.titleMedium,
                            color = MilestoneCream,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                        Text(
                            text = "A storefront for the stories that don't fit the bigger " +
                                "storefronts — yours, hand-tuned, in your pocket.",
                            // a11y (#483): bodyMedium is already
                            // 14sp/20lh — the +2lh was for breathing
                            // room around the multi-paragraph body, but
                            // the theme ramp owns that decision now.
                            style = MaterialTheme.typography.bodyMedium,
                            color = MilestoneCream.copy(alpha = 0.88f),
                        )
                        Text(
                            text = "Thanks for being early.",
                            // a11y (#483): bodyMedium is already
                            // 14sp/20lh — the +2lh was for breathing
                            // room around the multi-paragraph body, but
                            // the theme ramp owns that decision now.
                            style = MaterialTheme.typography.bodyMedium,
                            color = MilestoneCream.copy(alpha = 0.88f),
                        )
                        // Signature line, right-aligned, italic-ish via
                        // the serif family + a softer alpha. No em-dash
                        // surgery here — the inline en-dash is a stable
                        // glyph in EB Garamond.
                        Text(
                            text = "— the storyvox crew",
                            style = MaterialTheme.typography.bodySmall,
                            color = MilestoneCream.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.xs),
                            textAlign = TextAlign.End,
                        )
                        // Continue button — bottom-right, single brass-
                        // filled affordance. Tapping it (or tapping
                        // outside the card via Dialog dismissOnClickOutside)
                        // calls onDismiss, which the caller wires to
                        // markMilestoneDialogSeen + close.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.sm),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            BrassButton(
                                label = "Continue",
                                onClick = onDismiss,
                                variant = BrassButtonVariant.Primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Deep-purple substrate. A blend of Plum700 toward black so the
 *  card reads as "candlelit alcove" rather than "M3 dialog". Pulled
 *  out as a const so the preview and the production mount share the
 *  same swatch. */
private val MilestoneSubstrate = Color(0xFF1F1424)

/** Cream text — same on-plum cream tone the player uses for
 *  chapter-title strings, lifted here so we don't reach across to
 *  the Reader theme for one color. */
private val MilestoneCream = BrassRamp.Brass100

// region Previews

@Preview(name = "Milestone dialog — idle (dark)", widthDp = 400, heightDp = 600)
@Composable
private fun PreviewMilestoneDialogDark() = LibraryNocturneTheme(darkTheme = true) {
    // Dialog can't render at the top level of a Preview (no platform
    // window). Render the surface directly so designers can see the
    // card chrome + spacing in Android Studio's preview pane.
    Box(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(320.dp),
            shape = MaterialTheme.shapes.large,
            color = MilestoneSubstrate,
            border = BorderStroke(1.dp, BrassRamp.Brass500.copy(alpha = 0.55f)),
        ) {
            Box {
                Text(
                    text = "✦",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).alpha(0.22f),
                    color = BrassRamp.Brass400,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "🎉", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "storyvox 0.5.00",
                            style = MaterialTheme.typography.headlineSmall,
                            color = BrassRamp.Brass300,
                        )
                    }
                    Text(
                        text = "A small light, kept.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MilestoneCream,
                    )
                    Text(
                        text = "A storefront for the stories that don't fit the bigger " +
                            "storefronts — yours, hand-tuned, in your pocket.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MilestoneCream.copy(alpha = 0.88f),
                    )
                    Text(
                        text = "Thanks for being early.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MilestoneCream.copy(alpha = 0.88f),
                    )
                    Text(
                        text = "— the storyvox crew",
                        style = MaterialTheme.typography.bodySmall,
                        color = MilestoneCream.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textAlign = TextAlign.End,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        BrassButton(
                            label = "Continue",
                            onClick = {},
                            variant = BrassButtonVariant.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Milestone dialog — idle (light)", widthDp = 400, heightDp = 600)
@Composable
private fun PreviewMilestoneDialogLight() = LibraryNocturneTheme(darkTheme = false) {
    // The card stays deep-purple regardless of system theme — the
    // milestone surface is intentionally on-brand brass-on-plum and
    // doesn't flip with the rest of the app. (Same posture as the
    // player background, which also stays dark in light mode.) We
    // wrap in a paper-cream scrim so the contrast still reads in the
    // light-mode preview pane.
    Box(
        modifier = Modifier
            .background(PlumRamp.Plum100.copy(alpha = 0.85f))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(320.dp),
            shape = MaterialTheme.shapes.large,
            color = MilestoneSubstrate,
            border = BorderStroke(1.dp, BrassRamp.Brass500.copy(alpha = 0.55f)),
        ) {
            Box(modifier = Modifier.size(width = 320.dp, height = 280.dp)) {
                Text(
                    text = "✦",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp).alpha(0.22f),
                    color = BrassRamp.Brass400,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "🎉", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "storyvox 0.5.00",
                            style = MaterialTheme.typography.headlineSmall,
                            color = BrassRamp.Brass300,
                        )
                    }
                    Text(
                        text = "A small light, kept.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MilestoneCream,
                    )
                }
            }
        }
    }
}

// endregion
