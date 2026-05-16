package `in`.jphe.storyvox.feature.techempower

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Issue #517 — paired brass-tinted help icons (Phone + Discord Forum)
 * surfaced in the top-app-bar on Library and TechEmpower Home. One-tap
 * affordances for "I need help right now" — JP's design call locks
 * Discord + 211 as the primary peer-support + social-services
 * surfaces, with 988 (Suicide & Crisis Lifeline) reachable via
 * long-press on the phone icon.
 *
 * Why a shared composable: the icon pair appears identically on both
 * surfaces (Library + TechEmpower Home), and centralising the layout +
 * intent wiring means a behavioural change (e.g., swap the phone
 * long-press target from 988 to 911) is a one-file edit instead of
 * keeping two copies in sync.
 *
 * Why a `Box.combinedClickable` instead of `IconButton`: IconButton
 * doesn't expose a long-click slot, so the standard Material 3
 * pattern for "tap + long-press behave differently" is to drop to a
 * Box and apply `Modifier.combinedClickable`. The Box keeps the same
 * 48dp touch-target size IconButton uses by default, and the implicit
 * `LocalIndication` ripple renders identically to IconButton.
 *
 * Brass tint comes from `MaterialTheme.colorScheme.primary` — same
 * brass-on-warm-dark palette as every other top-app-bar action in the
 * app.
 *
 * v0.5.51 — first pass alongside the TechEmpower brand integration.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TechEmpowerHelpIcons() {
    val context = LocalContext.current

    // Issue #546 — fallback dialog state for the top-app-bar phone
    // icon. Without this, a tap on a WiFi-only tablet routes to the
    // contact picker. The shared [NoTelephonyDialog]-equivalent path
    // is owned by [TechEmpowerHomeScreen]; here we use the same
    // [dialOrSurfaceFallback] helper and surface the dialog locally
    // so Library users get the same protection.
    var noTelephonyTarget by remember { mutableStateOf<EmergencyTarget?>(null) }

    // ─── Call 211 (primary) / 988 (long-press) ───────────────────────
    // tel: URIs go through ACTION_DIAL so the user lands in the dialer
    // with the number pre-filled but NOT auto-dialled — accidental
    // top-app-bar taps surface as "huh, the dialer opened" rather than
    // an actual phone call. ACTION_CALL would require CALL_PHONE
    // permission and is the wrong UX for an in-bar shortcut.
    //
    // Issue #546 — both tap and long-press route through
    // [dialOrSurfaceFallback] so devices without telephony land on the
    // explanatory fallback dialog instead of the AOSP contact picker.
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    dialOrSurfaceFallback(
                        context = context,
                        target = EmergencyTarget.Help211,
                        onNoTelephony = { noTelephonyTarget = it },
                    )
                },
                onLongClick = {
                    dialOrSurfaceFallback(
                        context = context,
                        target = EmergencyTarget.Crisis988,
                        onNoTelephony = { noTelephonyTarget = it },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Phone,
            contentDescription =
                "Call 211 for help. Long-press for the 988 Suicide and Crisis Lifeline.",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }

    // Issue #533 — 8dp gap between the phone and Discord icons. The
    // two 48dp Boxes used to pack flush together, making mis-taps
    // trivial on the Flip3 (1080dp narrow). Spacer keeps both icons
    // at their full 48dp tap targets while giving the user enough
    // visual + finger separation to choose one deliberately.
    Spacer(Modifier.width(8.dp))

    // ─── Open peer-support Discord ───────────────────────────────────
    // The Forum icon is the most thematically-correct standard Material
    // icon for "community chat" (Discord-specific icons aren't in the
    // M3 set). Two-step launcher: tries `discord://invite/{slug}` first
    // so installed Discord apps open native, falls back to the HTTPS
    // URL otherwise. See [launchDiscord] for the rationale.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                role = Role.Button,
                onClick = { launchDiscord(context) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Forum,
            contentDescription = "Open the TechEmpower peer-support Discord.",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }

    // Issue #546 — render the fallback dialog when telephony is
    // absent. Same content/affordances as [TechEmpowerHomeScreen]'s
    // dialog so users see consistent recovery regardless of which
    // entry point they tapped (top-bar icon vs Emergency Help card).
    val target = noTelephonyTarget
    if (target != null) {
        NoTelephonyFallbackDialog(
            target = target,
            onDismiss = { noTelephonyTarget = null },
        )
    }
}
