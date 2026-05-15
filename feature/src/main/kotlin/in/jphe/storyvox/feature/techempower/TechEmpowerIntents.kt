package `in`.jphe.storyvox.feature.techempower

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import `in`.jphe.storyvox.data.TechEmpowerLinks

/**
 * Issue #517 — shared intent launchers for the TechEmpower surfaces.
 * Centralised so every "open Discord" entry point (top-app-bar icon,
 * TechEmpower Home card, About page) launches the same way.
 *
 * Discord launcher uses a two-step pattern:
 *  1. Try `discord://invite/{slug}` — Discord registers the
 *     `discord://` scheme on installed devices, so this opens the
 *     native app directly and the user lands inside the invite flow
 *     without a browser round-trip.
 *  2. On [ActivityNotFoundException] (Discord not installed),
 *     fall back to the HTTPS `discord.gg/{slug}` URL which opens in
 *     the user's browser → Discord's "Open in app or join via web"
 *     landing page.
 *
 * Why catch ActivityNotFoundException instead of querying the
 * PackageManager first: `queryIntentActivities` requires a
 * `<queries>` block in the manifest to inspect specific packages on
 * Android 11+, and a single try/catch avoids that manifest churn.
 * The fallthrough cost is one failed intent dispatch, not a UX
 * hiccup.
 */
internal fun launchDiscord(context: Context) {
    val deepLink = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(TechEmpowerLinks.DISCORD_INVITE_DEEPLINK),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(deepLink)
    } catch (_: ActivityNotFoundException) {
        // Discord not installed — fall through to the HTTPS URL.
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(TechEmpowerLinks.DISCORD_INVITE_URL),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}
