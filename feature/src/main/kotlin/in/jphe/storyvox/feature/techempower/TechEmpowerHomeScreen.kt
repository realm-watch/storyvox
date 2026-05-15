package `in`.jphe.storyvox.feature.techempower

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.ui.R as UiR
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #517 — TechEmpower Home screen. The dedicated landing for
 * "I'm here for TechEmpower's resources" — reached via the
 * brass-edged TechEmpower hero card pinned at the top of Library.
 *
 * Structure (top to bottom):
 *  - Brass-on-warm-dark top-app-bar with back arrow + help icons
 *    (phone for 211 / 988, forum for Discord) on the right.
 *  - Hero: TechEmpower sun-disk logo + mission tagline.
 *  - Brass-edged card grid (5 cards):
 *      1. Browse Resources → opens Browse (Notion's anonymous-mode
 *         four-fiction layout is the default tile set today).
 *      2. Peer Support Discord → ACTION_VIEW Discord invite URL.
 *      3. Call 211 → ACTION_DIAL tel:211 (United Way social services).
 *      4. Emergency Help → 3-button card (988 / 211 / 911) — see
 *         issue #516 and [TechEmpowerEmergencyCard].
 *      5. About TechEmpower → drill-down to [TechEmpowerAboutScreen].
 *  - Featured guides strip: horizontal row of the 8 hand-curated
 *    TechEmpower guide chapters (read directly off the anonymous
 *    Notion delegate's curated list — keeps this screen plumbing-
 *    free; if the guide list grows, this strip grows with it).
 *
 * Why no Hilt VM: the surface is entirely static content (constants
 * + intents) plus the curated guides list. Adding a VM here would be
 * a no-op layer between the screen and the constants module.
 *
 * Library Nocturne palette throughout — JP's design call #3
 * explicitly excludes TechEmpower warm-earth-tones from the storyvox
 * engine UI. TechEmpower's branding lands through *content* (logo +
 * copy), not theme.
 *
 * v0.5.51 — first pass.
 * v0.5.52 — added Emergency Help card (issue #516).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechEmpowerHomeScreen(
    onBack: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenFiction: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "TechEmpower",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Library",
                        )
                    }
                },
                actions = {
                    // Same paired phone + Discord brass icons as the
                    // Library top-app-bar — see [TechEmpowerHelpIcons]
                    // kdoc for the design rationale.
                    TechEmpowerHelpIcons()
                },
            )
        },
    ) { scaffoldPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            // ─── Hero (full span) ─────────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                TechEmpowerHero()
            }

            // ─── Get-help + content cards ─────────────────────────
            item {
                TechEmpowerCard(
                    title = "Browse Resources",
                    body = "Free tech guides, EBT support, and digital safety — read or listen.",
                    icon = null,
                    onClick = onOpenBrowse,
                )
            }
            item {
                TechEmpowerCard(
                    title = "Peer Support",
                    body = "Join our Discord community. Real people, no scripts.",
                    icon = Icons.Filled.Forum,
                    onClick = { launchDiscord(context) },
                )
            }
            item {
                TechEmpowerCard(
                    title = "Call 211",
                    body = "Local help — housing, food, utilities, mental health referrals via United Way.",
                    icon = Icons.Filled.Phone,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_DIAL,
                                    Uri.parse(
                                        TechEmpowerLinks.telUri(
                                            TechEmpowerLinks.PRIMARY_HELP_NUMBER,
                                        ),
                                    ),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }
            // ─── Emergency Help (issue #516) ──────────────────────
            // Full-width because the card stacks three brass-edged
            // sub-buttons vertically; squeezing it into a 160dp grid
            // cell would push the third number off-screen. Spanning
            // the full row width keeps the 988 / 211 / 911 trio
            // visible without scrolling on the Flip3 cover.
            item(span = { GridItemSpan(maxLineSpan) }) {
                TechEmpowerEmergencyCard()
            }
            item {
                TechEmpowerCard(
                    title = "About TechEmpower",
                    body = "Our mission, donate, partnerships, and contact.",
                    icon = Icons.Filled.Info,
                    onClick = onOpenAbout,
                )
            }

            // ─── Featured guides strip (full span) ────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeaturedGuidesStrip(onOpenFiction = onOpenFiction)
            }
        }
    }
}

/**
 * Hero section — sun-disk logo + mission tagline. Reads as the
 * "you've arrived at TechEmpower" landmark. Logo is rendered at a
 * fixed 96dp so it has presence without dominating the card grid
 * underneath.
 */
@Composable
private fun TechEmpowerHero() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Image(
            painter = painterResource(id = UiR.drawable.techempower_sun),
            contentDescription = "TechEmpower sun-disk logo",
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.large),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = TechEmpowerLinks.MISSION_TAGLINE,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "A 501(c)(3) nonprofit. storyvox is built on TechEmpower's mission.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Brass-edged card used for the four primary actions on TechEmpower
 * Home. 1.5dp brass outline reads as "this is the lead action" without
 * shouting; same vocabulary as the brass-edged cards on the Settings
 * hub and Plugin manager landing.
 *
 * The accompanying [icon] is optional — Browse Resources has no
 * single-icon analogue (it's "all of TechEmpower's content"), and a
 * card without an icon balances the grid layout. The other three
 * cards have a leading icon that anchors the affordance.
 */
@Composable
private fun TechEmpowerCard(
    title: String,
    body: String,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MaterialTheme.shapes.large)
            .background(substrate)
            .border(
                width = 1.5.dp,
                color = brass.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = brass,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(spacing.xs))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = brass,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
        }
    }
}

/**
 * Issue #516 — Emergency Help card. Brass-edged outer container with
 * three brass-edged sub-buttons (988 / 211 / 911), each dialling its
 * respective number via `ACTION_DIAL` so the user lands in the system
 * dialer with the number pre-filled but NOT auto-placed.
 *
 * Why a card with three buttons instead of three separate top-level
 * cards: the trio reads as one cognitive unit ("emergency numbers")
 * and bundling them under one heading lets the user scan all three
 * affordances together. Three separate cards would be visually
 * indistinguishable from the regular brass-edged action cards and
 * lose the "this is the emergency section" signal.
 *
 * Visual ranking is 988 → 211 → 911 (per issue #516 spec, not numeric
 * order): 988 is the most likely first-touch for someone in immediate
 * distress, 211 is everyday social services, 911 is true life-or-
 * limb. 911 last keeps it discoverable without inviting a stray tap
 * — accidental 911 misdials waste dispatch capacity.
 *
 * The outer card has no click handler; only the sub-buttons dial.
 * Tapping the card's title row or the gap between sub-buttons does
 * nothing — by design. The user has to land their tap on a specific
 * number, and the brass edge around each sub-button telegraphs that.
 *
 * V1 ships US-only. The number constants live in [TechEmpowerLinks];
 * localising for CA/UK/AU is a v2 follow-up (see issue #516 + the
 * [TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER] kdoc).
 */
@Composable
private fun TechEmpowerEmergencyCard() {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(substrate)
            .border(
                width = 1.5.dp,
                color = brass.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
            )
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Header row: warning icon + title. Warning glyph anchors the
        // "this is the emergency section" semantic — brass-tinted to
        // match the card edge, no red-on-red panic colouring (the
        // Library Nocturne palette doesn't have a panic-red, and the
        // dialer itself surfaces the call confirmation, so we don't
        // need to scream the affordance).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = brass,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = "Emergency Help",
                style = MaterialTheme.typography.titleMedium,
                color = brass,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "Three US helplines. Each opens the dialer with the number pre-filled — tap the green button to actually place the call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ─── 988 — Suicide & Crisis Lifeline (top of the list) ────
        EmergencyDialButton(
            number = TechEmpowerLinks.CRISIS_HELP_NUMBER,
            label = "Suicide & Crisis Lifeline",
            body = "National 24/7 mental health crisis support.",
        )
        // ─── 211 — United Way social services ─────────────────────
        EmergencyDialButton(
            number = TechEmpowerLinks.PRIMARY_HELP_NUMBER,
            label = "United Way social services",
            body = "Housing, food, utilities, mental health referrals.",
        )
        // ─── 911 — Emergency dispatch (last on purpose) ───────────
        EmergencyDialButton(
            number = TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER,
            label = "Emergency dispatch",
            body = "Life-threatening situations — police, fire, ambulance.",
        )
    }
}

/**
 * Issue #516 — single brass-edged dial button inside
 * [TechEmpowerEmergencyCard]. The number renders large-and-brass on
 * the left (the affordance — it's what the user is looking for and
 * what they'll tap), with label + body stacked to the right.
 *
 * Tap fires `ACTION_DIAL` (not `ACTION_CALL`) — `ACTION_DIAL` works
 * without the `CALL_PHONE` runtime permission and lands the user in
 * the system dialer with the number pre-filled but NOT auto-placed.
 * The user has to hit the green button to actually place the call;
 * this is the right UX for "emergency menu" affordances where a
 * stray tap shouldn't dispatch police.
 *
 * `runCatching` swallows `ActivityNotFoundException` — a device
 * without a dialer activity (rare, but tablets without telephony)
 * just no-ops the button. We don't show a toast because (a) the
 * other two buttons might still work, and (b) the user can always
 * find the number written on the card and dial manually from
 * another device.
 *
 * `Role.Button` plus a content description on the icon-less row
 * gives TalkBack users "988, Suicide and Crisis Lifeline, button" —
 * matching the visual reading order top-to-bottom.
 */
@Composable
private fun EmergencyDialButton(
    number: String,
    label: String,
    body: String,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = brass.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(
                role = Role.Button,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_DIAL,
                                Uri.parse(TechEmpowerLinks.telUri(number)),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                        )
                    }
                },
            )
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // The number itself is the primary visual + the tap target's
        // raison d'être — render it large-and-brass so it reads at a
        // glance from arm's length. SemiBold (not Bold) because the
        // Library Nocturne typography ramp tops out at SemiBold for
        // body-adjacent surfaces; bumping to Bold would clash with the
        // titleMedium "Emergency Help" header above.
        Text(
            text = number,
            style = MaterialTheme.typography.headlineSmall,
            color = brass,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Start,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = brass,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Horizontal strip of the eight hand-curated TechEmpower guides. The
 * fiction id is `notion:guides` (the anonymous-mode delegate's
 * PageList entry), and each chapter inside is the row payload — but
 * for this strip we go one level deeper: open the fiction-detail
 * page for `notion:guides`, where the user picks a chapter.
 *
 * We don't surface chapter-level deep-links from this strip because
 * the strip's job is to advertise the *category*, not commit the user
 * to a specific guide. The single-tap-to-fiction-detail pattern
 * matches every other "open fiction" surface in the app.
 *
 * If the curated guide list ever grows past ~12 entries, LazyRow's
 * built-in horizontal scrolling absorbs the growth without changing
 * the rest of the screen.
 */
@Composable
private fun FeaturedGuidesStrip(onOpenFiction: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "Featured guides",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Step-by-step help for free internet, EBT, password safety, and more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        // Pull the curated guide titles inline so the strip doesn't
        // need to suspend on a Notion API round-trip just to render
        // its title row — these labels are the SAME labels that
        // `:source-notion`'s [NotionDefaults.techempowerFictions]
        // serves as chapter titles for the "Guides" PageList fiction.
        // Keeping them inline here means the strip renders instantly
        // on cold launch (no network), and the actual *content* is
        // still served by the Notion source when the user taps.
        val guideTitles = listOf(
            "How to use TechEmpower.org",
            "Free internet",
            "EV incentives",
            "EBT balance",
            "EBT spending",
            "Findhelp",
            "Password manager",
            "Free cell service",
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(guideTitles) { title ->
                GuideChip(
                    title = title,
                    onClick = {
                        // Open the "Guides" fiction at fiction-detail
                        // depth. The Notion fiction id format is
                        // `notion:<sectionId>`; the anonymous delegate
                        // owns the section-id space.
                        onOpenFiction("notion:guides")
                    },
                )
            }
        }
    }
}

/**
 * Single brass-tinted chip in the featured-guides strip. Reads as a
 * leaf affordance — wide enough for two lines of title, narrow enough
 * that ~3 fit on the Flip3 cover width.
 */
@Composable
private fun GuideChip(title: String, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 88.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = brass.copy(alpha = 0.40f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = brass,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}
