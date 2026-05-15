package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.settings.components.StatusPill
import `in`.jphe.storyvox.feature.settings.components.StatusTone
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Account subscreen (follow-up to #440 / #467).
 *
 * Sign-in surfaces for fiction sources that need an account:
 *  - Royal Road (WebView cookie auth — #91).
 *  - GitHub (Device Flow OAuth + scope toggle — #91 / #203).
 *
 * The Notion / Discord / Outline token-paste rows still live under
 * the "Library & Sync" sources section because they're per-plugin
 * config, not per-account sign-ins. Anthropic Teams OAuth lives
 * under AI because it's an AI-provider auth, not a fiction-source
 * sign-in. The legacy long-scroll page preserves the same split.
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenGitHubSignIn: () -> Unit,
    onOpenGitHubRevoke: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Account", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                StatusPill(
                    text = if (s.isSignedIn) "Royal Road · signed in" else "Royal Road · not signed in",
                    tone = if (s.isSignedIn) StatusTone.Connected else StatusTone.Neutral,
                )
                if (s.isSignedIn) {
                    SettingsRow(
                        title = "Royal Road",
                        subtitle = "Signed in",
                        trailing = {
                            BrassButton(
                                label = "Sign out",
                                onClick = viewModel::signOut,
                                variant = BrassButtonVariant.Secondary,
                            )
                        },
                    )
                    // Issue #178 — two-way tag-sync row. Only
                    // surfaces when signed in to RR because the
                    // affordance is meaningless without a session.
                    RoyalRoadTagSyncRow()
                } else {
                    SettingsRow(
                        title = "Royal Road",
                        subtitle = "Sign-in unlocks Premium chapters and your Follows list.",
                        trailing = {
                            BrassButton(
                                label = "Sign in",
                                onClick = onOpenSignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        },
                    )
                }

                StatusPill(
                    text = when (val g = s.github) {
                        UiGitHubAuthState.Anonymous -> "GitHub · not signed in"
                        is UiGitHubAuthState.SignedIn ->
                            g.login?.let { "GitHub · signed in as @$it" }
                                ?: "GitHub · signed in"
                        UiGitHubAuthState.Expired -> "GitHub · session expired"
                    },
                    tone = when (s.github) {
                        UiGitHubAuthState.Anonymous -> StatusTone.Neutral
                        is UiGitHubAuthState.SignedIn -> StatusTone.Connected
                        UiGitHubAuthState.Expired -> StatusTone.Error
                    },
                )
                GitHubSignInRow(
                    state = s.github,
                    privateReposEnabled = s.githubPrivateReposEnabled,
                    onSignIn = onOpenGitHubSignIn,
                    onSignOut = viewModel::signOutGitHub,
                    onOpenRevokePage = onOpenGitHubRevoke,
                    onSetPrivateReposEnabled = viewModel::setGitHubPrivateReposEnabled,
                )
            }
        }
    }
}
