package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.BUFFER_DANGER_MULTIPLIER
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onOpenVoiceLibrary: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenGitHubSignIn: () -> Unit,
    onOpenGitHubRevoke: () -> Unit = {},
    onOpenPronunciationDict: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val s = state.settings ?: return

    Scaffold { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        // ── 1. Voice & Playback ──────────────────────────────────────
        // The auditory knobs a listener touches *for this story, this
        // session*: which voice, how fast, how pitched, how to say tricky
        // names. Most-touched section → first.
        SettingsSectionHeader("Voice & Playback", icon = Icons.Outlined.RecordVoiceOver)
        SettingsGroupCard {
            SettingsLinkRow(
                title = "Voice library",
                subtitle = "Pick a voice and hear samples.",
                onClick = onOpenVoiceLibrary,
            )
            SettingsSliderBlock(
                title = "Speed",
                valueLabel = "${"%.2f".format(s.defaultSpeed)}×",
                slider = {
                    Slider(
                        value = s.defaultSpeed,
                        onValueChange = viewModel::setSpeed,
                        valueRange = 0.5f..3.0f,
                        modifier = Modifier.semantics {
                            contentDescription = "Default speech speed"
                            stateDescription = "%.2f times".format(s.defaultSpeed)
                        },
                    )
                },
            )
            SettingsSliderBlock(
                title = "Pitch",
                valueLabel = "${"%.2f".format(s.defaultPitch)}×",
                slider = {
                    Slider(
                        value = s.defaultPitch,
                        onValueChange = viewModel::setPitch,
                        // Narration-friendly band — matches AudiobookView. Hard
                        // floor at 0.6: Sonic introduces artifacts below ~0.7.
                        valueRange = 0.6f..1.4f,
                        steps = 79,
                        modifier = Modifier.semantics {
                            contentDescription = "Default pitch"
                            stateDescription = "%.2f, neutral at one".format(s.defaultPitch)
                        },
                    )
                },
            )
            SettingsLinkRow(
                title = "Pronunciation",
                subtitle = "Teach the voice how to say specific names and words.",
                onClick = onOpenPronunciationDict,
            )
        }

        // ── 2. Reading ───────────────────────────────────────────────
        // Visual reading knobs. Theme today; future home for font size
        // override, sentence highlight intensity, page-turn animation.
        SettingsSectionHeader("Reading", icon = Icons.Outlined.MenuBook)
        SettingsGroupCard {
            SettingsSegmentedBlock(
                title = "Theme",
                subtitle = "System matches the device's day/night.",
                options = ThemeOverride.entries.map { it.name },
                selectedIndex = ThemeOverride.entries.indexOf(s.themeOverride).coerceAtLeast(0),
                onSelected = { idx -> viewModel.setTheme(ThemeOverride.entries[idx]) },
            )
        }

        // ── 3. Performance & buffering ───────────────────────────────
        // Trade upfront wait + memory for smoother playback. Order:
        // cheapest knobs (booleans) → most exploratory (buffer slider).
        // Punctuation cadence sits last — cadence preference that
        // also lives in the perf trade space (#98).
        SettingsSectionHeader("Performance & buffering", icon = Icons.Outlined.Speed)
        SettingsGroupCard {
            // Mode A — Warm-up Wait. Default ON. ON: brass spinner +
            // frozen scrubber while engine warms up. OFF: silent start.
            SettingsSwitchRow(
                title = "Warm-up Wait",
                subtitle = if (s.warmupWait) {
                    "Wait for the voice to warm up before playback starts."
                } else {
                    "Start playback immediately; accept silence at chapter start."
                },
                checked = s.warmupWait,
                onCheckedChange = viewModel::setWarmupWait,
            )
            // Mode B — Catch-up Pause. Default ON. ON: pause+resume on
            // underrun (PR #77). OFF: drain through underrun.
            SettingsSwitchRow(
                title = "Catch-up Pause",
                subtitle = if (s.catchupPause) {
                    "Pause briefly when the voice falls behind, then resume cleanly."
                } else {
                    "Drain through underruns; no buffering spinner."
                },
                checked = s.catchupPause,
                onCheckedChange = viewModel::setCatchupPause,
            )
            // Issue #85 — Voice Determinism preset. ON = VoxSherpa
            // calmed VITS defaults (replay-stable). OFF = sherpa-onnx
            // upstream Piper defaults (more variable prosody). Flips
            // force a model reload — handled by EnginePlayer.
            SettingsSwitchRow(
                title = "Voice Determinism",
                subtitle = if (s.voiceSteady) {
                    "Steady — identical text plays the same each time."
                } else {
                    "Expressive — slight variation, fuller prosody."
                },
                checked = s.voiceSteady,
                onCheckedChange = viewModel::setVoiceSteady,
            )
            // Buffer slider keeps its custom rendering — colored
            // amber/red past the recommended tick is the whole point.
            BufferSlider(
                chunks = s.playbackBufferChunks,
                onChunksChange = viewModel::setPlaybackBufferChunks,
            )
            // Punctuation cadence — #109 continuous slider (was 3-stop
            // in #93). Range 0..4× matches the engine's internal clamp.
            PunctuationPauseSlider(
                multiplier = s.punctuationPauseMultiplier,
                onMultiplierChange = viewModel::setPunctuationPauseMultiplier,
            )
        }

        // ── 4. AI ────────────────────────────────────────────────────
        // Smart features — Recap, character lookup, Q&A chat in Reader.
        // Configure-once-per-provider; positioned between perf (engine
        // tuning) and library (network syncing).
        SettingsSectionHeader("AI", icon = Icons.Outlined.AutoAwesome)
        SettingsGroupCard {
        AiSection(
            ai = s.ai,
            probeOutcome = state.probeOutcome,
            onSetProvider = viewModel::setAiProvider,
            onSetClaudeKey = viewModel::setClaudeApiKey,
            onSetClaudeModel = viewModel::setClaudeModel,
            onSetOpenAiKey = viewModel::setOpenAiApiKey,
            onSetOpenAiModel = viewModel::setOpenAiModel,
            onSetOllamaBaseUrl = viewModel::setOllamaBaseUrl,
            onSetOllamaModel = viewModel::setOllamaModel,
            onSetVertexKey = viewModel::setVertexApiKey,
            onSetVertexModel = viewModel::setVertexModel,
            onSetFoundryKey = viewModel::setFoundryApiKey,
            onSetFoundryEndpoint = viewModel::setFoundryEndpoint,
            onSetFoundryDeployment = viewModel::setFoundryDeployment,
            onSetFoundryServerless = viewModel::setFoundryServerless,
            onSetBedrockAccessKey = viewModel::setBedrockAccessKey,
            onSetBedrockSecretKey = viewModel::setBedrockSecretKey,
            onSetBedrockRegion = viewModel::setBedrockRegion,
            onSetBedrockModel = viewModel::setBedrockModel,
            onSetSendChapterText = viewModel::setSendChapterTextEnabled,
            onTestConnection = viewModel::testAiConnection,
            onClearProbeOutcome = viewModel::clearProbeOutcome,
            onResetAi = viewModel::resetAiSettings,
        )
        }

        // ── 5. Library & Sync ────────────────────────────────────────
        // Network preferences for keeping the library current. Renamed
        // from "Downloads" — "Library & Sync" matches storyvox's bottom-
        // tab language and reads as "what storyvox does in the
        // background to keep the library current."
        SettingsSectionHeader("Library & Sync", icon = Icons.Outlined.LibraryBooks)
        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Wi-Fi only",
                subtitle = "Don't poll on cellular.",
                checked = s.downloadOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnly,
            )
            SettingsSliderBlock(
                title = "Update check interval",
                valueLabel = "Every ${s.pollIntervalHours}h",
                slider = {
                    Slider(
                        value = s.pollIntervalHours.toFloat(),
                        onValueChange = { viewModel.setPollHours(it.toInt().coerceIn(1, 24)) },
                        valueRange = 1f..24f,
                        steps = 22,
                        modifier = Modifier.semantics {
                            contentDescription = "Update check interval"
                            stateDescription = "Every ${s.pollIntervalHours} hours"
                        },
                    )
                },
            )
        }

        // ── 6. Account ───────────────────────────────────────────────
        // Sign-in surfaces for fiction sources. Renamed from "Sources" —
        // the sources themselves don't have settings worth listing here
        // anymore (the feature is sign-in / sign-out + OAuth state).
        SettingsSectionHeader("Account", icon = Icons.Outlined.AccountCircle)
        SettingsGroupCard {
            // Royal Road row — preserves the v0.4.x "Account" surface,
            // labeled per-source so GitHub can sit beside it. Issue #91.
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
            // GitHub row (#91). Always shown — sign-in is additive
            // (lifts the anon 60 req/hr cap to 5,000 req/hr).
            GitHubSignInRow(
                state = s.github,
                onSignIn = onOpenGitHubSignIn,
                onSignOut = viewModel::signOutGitHub,
                onOpenRevokePage = onOpenGitHubRevoke,
            )
        }

        // ── 7. Memory Palace ─────────────────────────────────────────
        // Post-spec section — the palace is a fiction source with its
        // own host/key config (substantial enough to keep separate from
        // Account, which is just sign-in flows).
        SettingsSectionHeader("Memory Palace", icon = Icons.Outlined.AutoStories)
        SettingsGroupCard {
            MemoryPalaceSection(
                palace = s.palace,
                probe = state.palaceProbe,
                probing = state.palaceProbing,
                onSetHost = viewModel::setPalaceHost,
                onSetApiKey = viewModel::setPalaceApiKey,
                onClear = viewModel::clearPalaceConfig,
                onTest = viewModel::testPalaceConnection,
            )
        }

        // ── 8. About ─────────────────────────────────────────────────
        // Trailing metadata. Realm-sigil "name" is a deterministic
        // adjective+noun drawn from the fantasy realm word list, keyed
        // on the build's git hash. Same hash → same name across
        // rebuilds. The sigil is the brass payoff at the bottom of the
        // screen — give it the trailing slot for that beat.
        SettingsSectionHeader("About", icon = Icons.Outlined.Info)
        SettingsGroupCard {
            SettingsRow(
                title = "storyvox v${s.sigil.versionName}",
                subtitle = buildString {
                    append(s.sigil.branch)
                    if (s.sigil.dirty) append(" · dirty")
                    append(" · built ")
                    append(s.sigil.built.take(10))
                },
                trailing = {
                    Text(
                        text = s.sigil.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }

        Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.lg))
    }
    }
}

/**
 * Sources → GitHub row. Issue #91.
 *
 * Three states matching [UiGitHubAuthState]:
 *  - Anonymous → "Sign in to GitHub" CTA + scope explainer.
 *  - SignedIn → "Signed in as @login" + Sign-out button + revoke deep-link.
 *  - Expired → "Session expired — sign in again" CTA + same revoke link.
 *
 * Spec § Settings UI surface, lines 384-395 of the design doc.
 */
@Composable
private fun GitHubSignInRow(
    state: UiGitHubAuthState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenRevokePage: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        "GitHub",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = spacing.sm),
    )
    when (state) {
        UiGitHubAuthState.Anonymous -> {
            BrassButton(
                label = "Sign in to GitHub",
                onClick = onSignIn,
                variant = BrassButtonVariant.Primary,
            )
            Text(
                "Lifts the anonymous 60 req/hr cap to 5,000 req/hr and unlocks your repository " +
                    "readmes as fictions. We ask for read:user public_repo only — never write " +
                    "access, never private repos by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is UiGitHubAuthState.SignedIn -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.login?.let { "Signed in as @$it" } ?: "Signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                BrassButton(
                    label = "Sign out",
                    onClick = onSignOut,
                    variant = BrassButtonVariant.Secondary,
                )
            }
            Text(
                text = "Granted scope: ${state.scopes}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrassButton(
                label = "Revoke at github.com…",
                onClick = onOpenRevokePage,
                variant = BrassButtonVariant.Text,
            )
            Text(
                "Sign-out clears the token from this device. To remove storyvox's authorization " +
                    "on GitHub's side, use the link above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UiGitHubAuthState.Expired -> {
            Text(
                "Session expired — sign in again to recover.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            BrassButton(
                label = "Sign in to GitHub",
                onClick = onSignIn,
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

/**
 * Memory Palace section for the Settings screen (#79).
 *
 * Two text fields (host, optional API key) plus a Test/Clear row. The
 * status pill above the fields shows the last probe result; user-typed
 * edits clear the previous status (the address has changed; previous
 * verdict no longer authoritative).
 *
 * Spec: docs/superpowers/specs/2026-05-08-mempalace-integration-design.md.
 */
@Composable
private fun MemoryPalaceSection(
    palace: UiPalaceConfig,
    probe: PalaceProbeResult?,
    probing: Boolean,
    onSetHost: (String) -> Unit,
    onSetApiKey: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Header is now emitted by the call site (SettingsScreen) so it can sit
    // outside the SettingsGroupCard. Internal copy + controls follow.
    Text(
        "Browse and listen to your local MemPalace as fictions. Home network only — " +
            "the palace stays put when you're off the LAN.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Status pill — reflects the last probe result, or guides the user
    // when the host field is empty.
    val (statusText, statusColor) = when {
        !palace.isConfigured -> "Set host to enable" to MaterialTheme.colorScheme.onSurfaceVariant
        probe == null -> "Tap “Test connection” to verify" to MaterialTheme.colorScheme.onSurfaceVariant
        probe is PalaceProbeResult.Reachable ->
            "Connected · daemon ${probe.daemonVersion}" to MaterialTheme.colorScheme.primary
        probe is PalaceProbeResult.Unreachable ->
            "Off home network · ${probe.message}" to MaterialTheme.colorScheme.error
        probe is PalaceProbeResult.NotConfigured ->
            "Set host to enable" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        statusText,
        style = MaterialTheme.typography.bodySmall,
        color = statusColor,
    )

    // Host field. Local state lets the user type freely; the persisted
    // setter fires onValueChange so changes are picked up across config
    // recreations. We don't debounce — DataStore is fine with the rate.
    var hostInput by remember(palace.host) { mutableStateOf(palace.host) }
    OutlinedTextField(
        value = hostInput,
        onValueChange = {
            hostInput = it
            onSetHost(it)
        },
        label = { Text("Palace host (e.g. 10.0.6.50:8085)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    // API key — masked. Stored in EncryptedSharedPreferences alongside
    // Royal Road cookies. Empty is fine for unauthenticated daemons.
    var apiKeyInput by remember(palace.apiKey) { mutableStateOf(palace.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = {
            apiKeyInput = it
            onSetApiKey(it)
        },
        label = { Text("API key (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (probing) {
            // Inline spinner; the BrassButton doesn't have a loading state
            // and adding one for one site felt heavy. 16dp matches the
            // text height on either side.
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Testing…", style = MaterialTheme.typography.bodySmall)
        } else {
            BrassButton(
                label = "Test connection",
                onClick = onTest,
                variant = BrassButtonVariant.Primary,
            )
        }
        if (palace.isConfigured) {
            BrassButton(
                label = "Clear",
                onClick = {
                    hostInput = ""
                    apiKeyInput = ""
                    onClear()
                },
                variant = BrassButtonVariant.Secondary,
            )
        }
    }
}

/** Average sentence duration in seconds at 1.0× speed. Empirical, used only
 *  for the human-readable "≈ N seconds" hint under the slider. The actual
 *  knob is queue depth (chunks). */
private const val AVG_SENTENCE_SEC: Float = 2.5f

/** Approximate bytes per chunk @ 22050 Hz mono 16-bit, 2.5 s avg.
 *  22050 × 2 × 2.5 ≈ 110 KB. Used for the "≈ N MB" hint. */
private const val APPROX_BYTES_PER_CHUNK: Int = 110_000

/**
 * Settings → Audio buffer slider.
 *
 * The buffer is the pre-synthesized PCM queue depth fed to AudioTrack. On
 * fast voices / fast CPUs the default of 8 chunks is plenty; on slow voices
 * (Piper-high) and slow CPUs (Helio P22T) the producer falls behind and the
 * listener hears mid-sentence underruns. Letting users dial up the queue
 * trades RAM for resilience.
 *
 * The mechanical max ([BUFFER_MAX_CHUNKS]) intentionally goes well past
 * where we believe Android's Low Memory Killer will start marking the app —
 * issue #84 is the experimental probe to find that line. Past the
 * [BUFFER_RECOMMENDED_MAX_CHUNKS] tick we (a) intensify the warning copy and
 * (b) shift the slider track color amber → red so the user knows they've
 * crossed into experimental territory.
 */
@Composable
private fun BufferSlider(
    chunks: Int,
    onChunksChange: (Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    val pastTick = chunks > BUFFER_RECOMMENDED_MAX_CHUNKS
    val danger = chunks > BUFFER_RECOMMENDED_MAX_CHUNKS * BUFFER_DANGER_MULTIPLIER

    // Two distinct color states above the tick (amber → red) so the user has
    // a tactile sense of "I'm in unknown territory" → "I'm in dangerous
    // unknown territory". Below the tick we use the brand primary.
    val amber = Color(0xFFE0A040)
    val red = Color(0xFFD05030)
    val activeColor = when {
        danger -> red
        pastTick -> amber
        else -> MaterialTheme.colorScheme.primary
    }
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val approxSeconds = (chunks * AVG_SENTENCE_SEC).toInt()
    val approxMb = (chunks.toLong() * APPROX_BYTES_PER_CHUNK / 1_048_576L).toInt()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // Header row: current value + recommended-max indicator.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Buffer: $chunks chunks (~${approxSeconds}s, ~${approxMb} MB)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Recommended max: $BUFFER_RECOMMENDED_MAX_CHUNKS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Slider(
            value = chunks.toFloat(),
            onValueChange = { onChunksChange(it.toInt().coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)) },
            valueRange = BUFFER_MIN_CHUNKS.toFloat()..BUFFER_MAX_CHUNKS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = inactiveColor,
            ),
            // TalkBack #160 — sighted users see the live readout above the
            // slider; TalkBack users get the same information here. The
            // recommended-max ceiling matters for the past-tick warning, so
            // it's spelled out in the state description.
            modifier = Modifier.semantics {
                contentDescription = "Playback buffer size"
                stateDescription = "$chunks chunks, recommended max $BUFFER_RECOMMENDED_MAX_CHUNKS"
            },
        )

        // Recommended-max tick label, spatially anchored to its position
        // along the slider via a leading spacer. Material3 Slider doesn't
        // expose tick rendering for non-stepped sliders; this gives the
        // user a visible "this is where the line is" without painting onto
        // the slider's canvas.
        TickMarker(
            tickValue = BUFFER_RECOMMENDED_MAX_CHUNKS,
            min = BUFFER_MIN_CHUNKS,
            max = BUFFER_MAX_CHUNKS,
        )

        // Always-on note text. Issue #84 — substance is verbatim from the
        // issue body's "Why we have a recommended max" / "Why we can't just
        // fix the delay" paragraphs.
        Text(
            text = "Pre-synthesizes extra audio ahead of where you're listening. " +
                "A larger buffer hides moments where the voice engine can't keep up — " +
                "useful on high-quality voices like Piper-high that synthesize slower " +
                "than realtime on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
        Text(
            text = "Why we have a recommended max: Past a certain size, Android may " +
                "kill the app in the background while you're not actively using it. " +
                "The recommended max is set just below where we currently believe " +
                "that line is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Why we can't just \"fix\" the delay: The bottleneck is the voice " +
                "model running on this CPU — not the buffer logic. The structural fix " +
                "is pre-rendering chapters to disk so playback reads finished audio " +
                "instead of synthesizing live. That work is on the roadmap (#86 / PCM " +
                "cache PRs C-H).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Past-the-tick intensified warning. Issue #84 update — JP wants the
        // probe to be visible so users above the tick know they're helping
        // measure the LMK threshold.
        if (pastTick) {
            Text(
                text = "Past the recommended max: Android may kill the app in the " +
                    "background. Help us find the exact limit by reporting what works.",
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) red else amber,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * A discrete label rendered at the slider's tick fraction. Delegates
 * placement to [SliderTickLabels] so the visual ▲ caret aligns with the
 * Material3 Slider thumb position for the given value (rather than the
 * raw row-width fraction, which the legacy weight-spacer layout used and
 * which drifted by the label's intrinsic width — same root cause Tessa
 * fixed for the punctuation slider in #146).
 *
 * Doesn't paint onto the slider canvas (Material3 Slider's track-painter
 * customization is verbose for what we need); renders below the slider
 * at the thumb-anchored horizontal offset.
 */
@Composable
private fun TickMarker(
    tickValue: Int,
    min: Int,
    max: Int,
) {
    val fraction = ((tickValue - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    SliderTickLabels(ticks = listOf("▲ $tickValue" to fraction))
}

/**
 * Settings → Punctuation Cadence slider (issue #109).
 *
 * Continuous multiplier on the inter-sentence silence storyvox splices after
 * each TTS sentence. The base pause table (350/200/120/60 ms by terminator)
 * lives in `EngineStreamingSource.trailingPauseMs` and is scaled by this
 * value before being emitted as PCM zeros.
 *
 * Range is [PUNCTUATION_PAUSE_MIN_MULTIPLIER]..[PUNCTUATION_PAUSE_MAX_MULTIPLIER]
 * (0×..4×) — wider than the legacy 3-stop selector's 0×/1×/1.75× because
 * the engine has always coerced to 0..4 internally and #109 surfaces that
 * full range. Tick labels anchor the legacy stops + the new ceiling so
 * users who liked "Long" can find it precisely (1.75×).
 *
 * Same brass-styled aesthetic as [BufferSlider] — primary-colored thumb +
 * active track, surface-variant inactive track. No "warning zone": every
 * point on this slider is mechanically safe; it's purely a cadence
 * preference. Single decimal-place readout (e.g. "1.8×") is enough
 * precision for a perceptual knob.
 */
@Composable
private fun PunctuationPauseSlider(
    multiplier: Float,
    onMultiplierChange: (Float) -> Unit,
) {
    val spacing = LocalSpacing.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text("Pause after . , ? ! ; :", style = MaterialTheme.typography.bodyMedium)
        Text(
            "How long to pause between sentences. 0× makes the reader sprint; " +
                "1× is the audiobook default; 1.75× matches the old \"Long\" stop; " +
                "4× gives narration full theatrical room to breathe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Pause after punctuation: ${"%.2f".format(multiplier)}×",
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = multiplier,
            onValueChange = {
                onMultiplierChange(
                    it.coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER),
                )
            },
            valueRange = PUNCTUATION_PAUSE_MIN_MULTIPLIER..PUNCTUATION_PAUSE_MAX_MULTIPLIER,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            // TalkBack #160 — "Off" is the visible label at multiplier 0,
            // so we mirror that for screen-reader users; otherwise read
            // the multiplier directly with a "times" suffix to match the
            // visible "Pause after punctuation: 1.50×" line above.
            modifier = Modifier.semantics {
                contentDescription = "Pause after punctuation"
                stateDescription = if (multiplier <= PUNCTUATION_PAUSE_MIN_MULTIPLIER) {
                    "Off"
                } else {
                    "%.2f times".format(multiplier)
                }
            },
        )

        // Tick labels at the legacy stops + the new 4× ceiling. Spatial
        // anchoring uses the same weight-trick as [TickMarker] so the labels
        // sit under their actual slider positions without painting onto the
        // canvas. Material3 Slider's `steps` parameter would draw real ticks
        // but only at evenly-spaced fractions of the range; our stops aren't
        // evenly spaced (0/1/1.75/4 fractions are 0, 0.25, 0.4375, 1) so
        // we render them as a separate row.
        PunctuationPauseTickLabels()
    }
}

/**
 * Anchored row of legacy-stop labels under [PunctuationPauseSlider]. Each
 * label sits at its true fractional position along the [0..4] range, with
 * placement delegated to [SliderTickLabels]. Labels include the legacy
 * stop names (Off / Normal / Long) so users coming from the 3-stop
 * selector can find their preferred cadence at a glance.
 */
@Composable
private fun PunctuationPauseTickLabels() {
    val total = PUNCTUATION_PAUSE_MAX_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER
    SliderTickLabels(
        ticks = listOf(
            "▲ Off" to ((PUNCTUATION_PAUSE_OFF_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ 1×" to ((PUNCTUATION_PAUSE_NORMAL_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ Long" to ((PUNCTUATION_PAUSE_LONG_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ 4×" to 1f,
        ),
    )
}

/**
 * Reusable row of slider tick labels, anchored to their true fractional
 * positions along the slider track. Extracted from the punctuation
 * slider (#139, Tessa's #146) so the buffer slider's `▲ N` recommended-
 * max marker can share the same thumb-aligned placement math instead of
 * the legacy weight-spacer trick.
 *
 * Why not [Row] with weight spacers? With multiple labels in a single
 * [Row], Compose's weight system divides only the *remaining* width
 * after measuring unweighted children (the Texts themselves), so each
 * label drifts right by the cumulative widths of preceding labels.
 * With four labels under the punctuation slider the drift visibly
 * mismatched the thumb position (#139). Even the single-label
 * [TickMarker] case for the buffer slider's `▲ 64` marker had the same
 * intrinsic-width-leak — by 64-on-a-1500-wide-range the drift was
 * subpixel, but at higher tick fractions or smaller parent widths the
 * label slid right of the thumb just like the punctuation case.
 *
 * We also account for the M3 [Slider]'s internal track padding (half
 * the thumb width = 10dp on each side), which the surrounding Column
 * does not inherit. Fraction 0 in the parent layout maps to
 * track-x = 0 + padding, not to the leftmost pixel of the parent.
 *
 * Each entry is `(label, fraction)` where `fraction ∈ [0, 1]` is the
 * position along the slider track. Labels render in the order given;
 * the last entry is right-aligned so its trailing characters don't
 * overflow the parent edge.
 */
@Composable
private fun SliderTickLabels(
    ticks: List<Pair<String, Float>>,
) {
    if (ticks.isEmpty()) return

    // M3 Slider reserves half the thumb diameter as track padding on
    // each side (default thumb is 20dp, so 10dp). Hardcoded here
    // because SliderDefaults doesn't expose this constant publicly. If
    // the thumb size ever changes, the labels will drift by ≤10dp —
    // visible only on the extreme ends — so this stays a load-bearing
    // constant.
    val trackPaddingDp = SLIDER_TRACK_PADDING_DP

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ticks.forEach { (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val rowWidth = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val trackPaddingPx = trackPaddingDp.dp.toPx().toInt()

        layout(rowWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                val frac = ticks[i].second
                // The right-align rule exists to keep the rightmost-of-
                // many label from running off the parent's right edge
                // when its preceding labels have already consumed track
                // space. With only one tick there's nothing preceding,
                // so we anchor at the thumb-x just like a middle tick —
                // this is what makes the buffer slider's `▲ 64` align
                // with the thumb at value 64 instead of pinning to the
                // right edge of the parent.
                val isLast = ticks.size > 1 && i == ticks.lastIndex
                val x = computeTickLabelX(
                    rowWidthPx = rowWidth,
                    trackPaddingPx = trackPaddingPx,
                    fraction = frac,
                    labelWidthPx = placeable.width,
                    isLast = isLast,
                )
                placeable.place(x, 0)
            }
        }
    }
}

/** Half the M3 default thumb diameter; track padding on each side. */
private const val SLIDER_TRACK_PADDING_DP: Int = 10

/**
 * Pure placement math for [SliderTickLabels], extracted for unit
 * testing. Returns the integer x-offset (in pixels) at which a tick label
 * should be placed inside the parent so its visual ▲ caret sits at the
 * slider thumb position for the given [fraction].
 *
 * Anchoring rule:
 *  - The leftmost label and all middle labels are **left-aligned** at
 *    `trackPaddingPx + fraction × trackWidthPx`. This puts the leading ▲
 *    character at the slider's thumb-x for that value.
 *  - The rightmost label is **right-aligned** to the parent so its full
 *    text stays on screen (the ▲ ends up slightly right of the thumb's
 *    track-x by half-a-thumb, which is the correct visual since the
 *    thumb itself extends right of trackEnd by half its width).
 *
 * All labels are clamped so they never overflow the parent on the right.
 */
internal fun computeTickLabelX(
    rowWidthPx: Int,
    trackPaddingPx: Int,
    fraction: Float,
    labelWidthPx: Int,
    isLast: Boolean,
): Int {
    if (isLast) {
        // Right-align: label's right edge at parent's right edge.
        return (rowWidthPx - labelWidthPx).coerceAtLeast(0)
    }
    val trackWidthPx = (rowWidthPx - 2 * trackPaddingPx).coerceAtLeast(0)
    val anchorX = trackPaddingPx + (fraction.coerceIn(0f, 1f) * trackWidthPx).toInt()
    // Clamp so multi-character middle labels don't overflow the right edge.
    return anchorX.coerceIn(0, (rowWidthPx - labelWidthPx).coerceAtLeast(0))
}

/**
 * Settings → AI section. Provider selector + per-provider config +
 * Test connection + privacy toggle. Issue #81.
 *
 * Inline (not a sub-screen) for v1 — matches the existing Settings
 * structure where every category is a section divider in one
 * scrollable list. We can promote to a sub-screen if the AI
 * controls cross ~5 toggles (matching where the Voices section is
 * heading).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSection(
    ai: UiAiSettings,
    probeOutcome: ProbeOutcome?,
    onSetProvider: (UiLlmProvider?) -> Unit,
    onSetClaudeKey: (String?) -> Unit,
    onSetClaudeModel: (String) -> Unit,
    onSetOpenAiKey: (String?) -> Unit,
    onSetOpenAiModel: (String) -> Unit,
    onSetOllamaBaseUrl: (String) -> Unit,
    onSetOllamaModel: (String) -> Unit,
    onSetVertexKey: (String?) -> Unit,
    onSetVertexModel: (String) -> Unit,
    onSetFoundryKey: (String?) -> Unit,
    onSetFoundryEndpoint: (String) -> Unit,
    onSetFoundryDeployment: (String) -> Unit,
    onSetFoundryServerless: (Boolean) -> Unit,
    onSetBedrockAccessKey: (String?) -> Unit,
    onSetBedrockSecretKey: (String?) -> Unit,
    onSetBedrockRegion: (String) -> Unit,
    onSetBedrockModel: (String) -> Unit,
    onSetSendChapterText: (Boolean) -> Unit,
    onTestConnection: (UiLlmProvider) -> Unit,
    onClearProbeOutcome: () -> Unit,
    onResetAi: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Header is now emitted by the call site (SettingsScreen) so it can sit
    // outside the SettingsGroupCard. Internal copy + controls follow.
    Text(
        "Smart features (Recap, character lookup, …) ask an AI to answer " +
            "questions about what you're reading. Pick a provider, then enable a feature. " +
            "Local providers (Ollama) keep your text on your network; cloud providers " +
            "(Claude, OpenAI) send it to that company's servers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Provider three-stop. We surface only the implemented providers
    // as live buttons; spec-only ones get a single "Coming soon"
    // line below so the design intent is visible without those rows
    // being tappable yet.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        ProviderChip(label = "Off", selected = ai.provider == null) {
            onSetProvider(null)
        }
        ProviderChip(label = "Claude", selected = ai.provider == UiLlmProvider.Claude) {
            onSetProvider(UiLlmProvider.Claude)
        }
        ProviderChip(label = "OpenAI", selected = ai.provider == UiLlmProvider.OpenAi) {
            onSetProvider(UiLlmProvider.OpenAi)
        }
        ProviderChip(label = "Ollama", selected = ai.provider == UiLlmProvider.Ollama) {
            onSetProvider(UiLlmProvider.Ollama)
        }
        ProviderChip(label = "Vertex", selected = ai.provider == UiLlmProvider.Vertex) {
            onSetProvider(UiLlmProvider.Vertex)
        }
        ProviderChip(label = "Foundry", selected = ai.provider == UiLlmProvider.Foundry) {
            onSetProvider(UiLlmProvider.Foundry)
        }
        ProviderChip(label = "Bedrock", selected = ai.provider == UiLlmProvider.Bedrock) {
            onSetProvider(UiLlmProvider.Bedrock)
        }
    }
    Text(
        "Coming soon: Anthropic Teams. " +
            "See the design spec at docs/superpowers/specs/2026-05-08-ai-integration-design.md.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (ai.provider) {
        UiLlmProvider.Claude -> ClaudeProviderRows(
            ai = ai,
            onSetClaudeKey = onSetClaudeKey,
            onSetClaudeModel = onSetClaudeModel,
        )
        UiLlmProvider.OpenAi -> OpenAiProviderRows(
            ai = ai,
            onSetOpenAiKey = onSetOpenAiKey,
            onSetOpenAiModel = onSetOpenAiModel,
        )
        UiLlmProvider.Ollama -> OllamaProviderRows(
            ai = ai,
            onSetOllamaBaseUrl = onSetOllamaBaseUrl,
            onSetOllamaModel = onSetOllamaModel,
        )
        UiLlmProvider.Vertex -> VertexProviderRows(
            ai = ai,
            onSetVertexKey = onSetVertexKey,
            onSetVertexModel = onSetVertexModel,
        )
        UiLlmProvider.Foundry -> AzureFoundryProviderRows(
            ai = ai,
            onSetFoundryKey = onSetFoundryKey,
            onSetFoundryEndpoint = onSetFoundryEndpoint,
            onSetFoundryDeployment = onSetFoundryDeployment,
            onSetFoundryServerless = onSetFoundryServerless,
        )
        UiLlmProvider.Bedrock -> BedrockProviderRows(
            ai = ai,
            onSetAccessKey = onSetBedrockAccessKey,
            onSetSecretKey = onSetBedrockSecretKey,
            onSetRegion = onSetBedrockRegion,
            onSetModel = onSetBedrockModel,
        )
        UiLlmProvider.Teams -> {
            // Selected via the (currently disabled) chip; defensive
            // catch-all that surfaces a friendly message rather than
            // letting the user fall into a half-built UI.
            Text(
                "${ai.provider!!.displayName} is in the design spec but not yet " +
                    "implemented. Pick another provider for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        null -> { /* Off — nothing more to show */ }
    }

    if (ai.provider != null && ai.provider.implemented) {
        BrassButton(
            label = "Test connection",
            onClick = { onTestConnection(ai.provider) },
            variant = BrassButtonVariant.Secondary,
        )
        ProbeOutcomeMessage(probeOutcome, onClearProbeOutcome)
    }

    if (ai.provider != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Send chapter text to AI", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Required for Recap. Off means the feature is disabled even with a provider configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ai.sendChapterTextEnabled,
                onCheckedChange = onSetSendChapterText,
            )
        }
        BrassButton(
            label = "Forget all AI settings",
            onClick = onResetAi,
            variant = BrassButtonVariant.Text,
        )
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BrassButton(
        label = label,
        onClick = onClick,
        variant = if (selected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeProviderRows(
    ai: UiAiSettings,
    onSetClaudeKey: (String?) -> Unit,
    onSetClaudeModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            if (ai.claudeKeyConfigured) "Claude API key — set"
            else "Claude API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Claude key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetClaudeKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.claudeKeyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = { onSetClaudeKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        Text(
            "Model: ${ai.claudeModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Model picker as a row of Brass chips. Hardcoded list for v1.
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("claude-haiku-4.5", "claude-sonnet-4.6", "claude-opus-4.6").forEach { m ->
                BrassButton(
                    label = m.removePrefix("claude-"),
                    onClick = { onSetClaudeModel(m) },
                    variant = if (ai.claudeModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "Estimated cost: ~\$0.005 per recap on Haiku 4.5. Anthropic console is the source of truth for usage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenAiProviderRows(
    ai: UiAiSettings,
    onSetOpenAiKey: (String?) -> Unit,
    onSetOpenAiModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            if (ai.openAiKeyConfigured) "OpenAI API key — set" else "OpenAI API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new OpenAI key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetOpenAiKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.openAiKeyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = { onSetOpenAiKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        Text(
            "Model: ${ai.openAiModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("gpt-4o-mini", "gpt-4o").forEach { m ->
                BrassButton(
                    label = m,
                    onClick = { onSetOpenAiModel(m) },
                    variant = if (ai.openAiModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OllamaProviderRows(
    ai: UiAiSettings,
    onSetOllamaBaseUrl: (String) -> Unit,
    onSetOllamaModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var urlDraft by remember(ai.ollamaBaseUrl) { mutableStateOf(ai.ollamaBaseUrl) }
    var modelDraft by remember(ai.ollamaModel) { mutableStateOf(ai.ollamaModel) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("Ollama server URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text("Ollama model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        BrassButton(
            label = "Save",
            onClick = {
                onSetOllamaBaseUrl(urlDraft.trim())
                onSetOllamaModel(modelDraft.trim())
            },
            variant = BrassButtonVariant.Primary,
        )
        Text(
            "Default URL is intentionally a placeholder — fix it to your LAN host (e.g. " +
                "http://10.0.6.50:11434) before testing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VertexProviderRows(
    ai: UiAiSettings,
    onSetVertexKey: (String?) -> Unit,
    onSetVertexModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            if (ai.vertexKeyConfigured) "Vertex API key — set"
            else "Vertex API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Vertex (Gemini) key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetVertexKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.vertexKeyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = { onSetVertexKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        Text(
            "Model: ${ai.vertexModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite").forEach { m ->
                BrassButton(
                    label = m.removePrefix("gemini-"),
                    onClick = { onSetVertexModel(m) },
                    variant = if (ai.vertexModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "Generate a key at aistudio.google.com/app/apikey. " +
                "Flash is the cheapest, Pro is the smartest, Flash-Lite is the lightest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AzureFoundryProviderRows(
    ai: UiAiSettings,
    onSetFoundryKey: (String?) -> Unit,
    onSetFoundryEndpoint: (String) -> Unit,
    onSetFoundryDeployment: (String) -> Unit,
    onSetFoundryServerless: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var endpointDraft by remember(ai.foundryEndpoint) { mutableStateOf(ai.foundryEndpoint) }
    var deploymentDraft by remember(ai.foundryDeployment) { mutableStateOf(ai.foundryDeployment) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // ── Mode toggle ────────────────────────────────────────────
        // Picked first so the deployment-id field's hint copy can
        // adapt. Default is Deployed (the more common Azure path —
        // an Azure OpenAI Service resource with named deployments).
        Text(
            "Mode",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            ProviderChip(
                label = "Deployed",
                selected = !ai.foundryServerless,
            ) { onSetFoundryServerless(false) }
            ProviderChip(
                label = "Serverless",
                selected = ai.foundryServerless,
            ) { onSetFoundryServerless(true) }
        }
        Text(
            if (ai.foundryServerless)
                "Serverless: one /models/chat/completions URL, model id in the body. " +
                    "Use for the Azure model catalog (Llama / Phi / DeepSeek / Grok / …)."
            else
                "Deployed: per-deployment /openai/deployments/{name}/... URL. " +
                    "Use for an Azure OpenAI Service resource — type the deployment name " +
                    "you set in the Azure portal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Endpoint URL ──────────────────────────────────────────
        OutlinedTextField(
            value = endpointDraft,
            onValueChange = { endpointDraft = it },
            label = { Text("Endpoint URL") },
            placeholder = {
                Text(
                    if (ai.foundryServerless) "https://my-project.services.ai.azure.com"
                    else "https://my-resource.openai.azure.com",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // ── API key (encrypted) ───────────────────────────────────
        Text(
            if (ai.foundryKeyConfigured) "Foundry API key — set"
            else "Foundry API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Foundry api-key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // ── Deployment / model id ─────────────────────────────────
        OutlinedTextField(
            value = deploymentDraft,
            onValueChange = { deploymentDraft = it },
            label = {
                Text(if (ai.foundryServerless) "Model id" else "Deployment name")
            },
            placeholder = {
                Text(if (ai.foundryServerless) "gpt-4o" else "gpt-4o-prod")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (ai.foundryServerless) {
            // Catalog model chips for serverless. Deployed mode shows
            // no chips — the deployment name is entirely the user's
            // (it's whatever they typed in the Azure portal).
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("gpt-4o", "gpt-4o-mini", "Llama-3.3-70B-Instruct").forEach { m ->
                    BrassButton(
                        label = m,
                        onClick = { deploymentDraft = m; onSetFoundryDeployment(m) },
                        variant = if (ai.foundryDeployment == m)
                            BrassButtonVariant.Primary
                        else
                            BrassButtonVariant.Secondary,
                    )
                }
            }
        }

        // ── Save / clear ──────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    onSetFoundryEndpoint(endpointDraft.trim())
                    onSetFoundryDeployment(deploymentDraft.trim())
                    if (keyDraft.isNotBlank()) {
                        onSetFoundryKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.foundryKeyConfigured) {
                BrassButton(
                    label = "Clear key",
                    onClick = { onSetFoundryKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BedrockProviderRows(
    ai: UiAiSettings,
    onSetAccessKey: (String?) -> Unit,
    onSetSecretKey: (String?) -> Unit,
    onSetRegion: (String) -> Unit,
    onSetModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var accessDraft by remember { mutableStateOf("") }
    var secretDraft by remember { mutableStateOf("") }
    var showAccess by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // ── Access key ────────────────────────────────────────────
        Text(
            if (ai.bedrockAccessKeyConfigured) "AWS access key id — set"
            else "AWS access key id — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = accessDraft,
            onValueChange = { accessDraft = it },
            label = { Text("Paste new AWS access key id (AKIA…)") },
            visualTransformation = if (showAccess) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showAccess) "Hide" else "Show",
                onClick = { showAccess = !showAccess },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (accessDraft.isNotBlank()) {
                        onSetAccessKey(accessDraft)
                        accessDraft = ""
                        showAccess = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.bedrockAccessKeyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = { onSetAccessKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        // ── Secret key ────────────────────────────────────────────
        Text(
            if (ai.bedrockSecretKeyConfigured) "AWS secret access key — set"
            else "AWS secret access key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = secretDraft,
            onValueChange = { secretDraft = it },
            label = { Text("Paste new AWS secret access key") },
            visualTransformation = if (showSecret) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showSecret) "Hide" else "Show",
                onClick = { showSecret = !showSecret },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (secretDraft.isNotBlank()) {
                        onSetSecretKey(secretDraft)
                        secretDraft = ""
                        showSecret = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (ai.bedrockSecretKeyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = { onSetSecretKey(null) },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        // ── Region picker ─────────────────────────────────────────
        Text(
            "Region: ${ai.bedrockRegion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            // Hardcoded Bedrock regions — see BedrockModels.regions in :core-llm.
            listOf("us-east-1", "us-west-2", "eu-central-1", "ap-northeast-1").forEach { r ->
                BrassButton(
                    label = r,
                    onClick = { onSetRegion(r) },
                    variant = if (ai.bedrockRegion == r) BrassButtonVariant.Primary
                        else BrassButtonVariant.Secondary,
                )
            }
        }
        // ── Model picker ──────────────────────────────────────────
        Text(
            "Model: ${ai.bedrockModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            // Curated subset of cloud-chat-assistant's BEDROCK_MODELS map.
            listOf(
                "claude-haiku-4.5",
                "claude-sonnet-4.6",
                "nova-lite",
                "llama4-maverick-17b",
            ).forEach { m ->
                BrassButton(
                    label = m,
                    onClick = { onSetModel(m) },
                    variant = if (ai.bedrockModel == m) BrassButtonVariant.Primary
                        else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "Bedrock charges per-token; per-region pricing varies. AWS console is " +
                "the source of truth for usage. SigV4 signing happens in-app — " +
                "no AWS SDK is bundled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProbeOutcomeMessage(probe: ProbeOutcome?, onClear: () -> Unit) {
    if (probe == null) return
    LaunchedEffect(probe) {
        // Auto-clear after 8 seconds so the message doesn't linger
        // forever after the user has read it.
        delay(8_000)
        onClear()
    }
    val color = when (probe) {
        is ProbeOutcome.Ok -> MaterialTheme.colorScheme.primary
        is ProbeOutcome.Failure -> MaterialTheme.colorScheme.error
    }
    val text = when (probe) {
        is ProbeOutcome.Ok -> "Connection OK."
        is ProbeOutcome.Failure -> probe.message
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
