package `in`.jphe.storyvox.feature.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.UiEngineInstallProgress
import `in`.jphe.storyvox.feature.api.UiEngineState
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * First-launch gate that wraps the rest of the app. If VoxSherpa is missing
 * or below `BuildConfig.VOXSHERPA_MIN_VERSION`, we render a sigil-themed
 * install sheet over everything; otherwise the gate is invisible and
 * [content] renders normally.
 *
 * The gate re-checks the engine state on each composition (cheap PackageInfo
 * lookup), so when the user comes back from the OS install dialog the gate
 * dismisses itself without app restart. The user can also bypass the gate
 * (e.g., to use system-default TTS) — bypass is sticky for the session.
 */
@Composable
fun EngineGate(
    content: @Composable () -> Unit,
) {
    val vm: EngineGateViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bypassed by vm.bypassed.collectAsStateWithLifecycle()

    // Re-probe on every lifecycle resume — the user may have just come back
    // from the OS uninstall/install dialog, in which case PackageInfo has
    // changed under us. LaunchedEffect(Unit) alone doesn't cover this:
    // recomposition doesn't re-run keyed effects.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (bypassed || (state.installed && state.isUpToDate)) {
        content()
        return
    }

    EngineMissingScreen(
        state = state,
        progress = progress,
        onInstall = vm::install,
        onUninstallExisting = vm::uninstallExisting,
        onSkip = vm::bypass,
        onDismissProgress = vm::dismissProgress,
    )
}

@HiltViewModel
class EngineGateViewModel @Inject constructor(
    private val voices: VoiceProviderUi,
) : ViewModel() {
    private val _state = MutableStateFlow(voices.engineState().toUi())
    val state: StateFlow<UiEngineState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<UiEngineInstallProgress?>(null)
    val progress: StateFlow<UiEngineInstallProgress?> = _progress.asStateFlow()

    /** Sticky session-only opt-out so a user without VoxSherpa can still use
     *  the system fallback TTS without staring at the gate. */
    private val _bypassed = MutableStateFlow(false)
    val bypassed: StateFlow<Boolean> = _bypassed.asStateFlow()

    fun refresh() {
        _state.value = voices.engineState().toUi()
    }

    fun install() {
        if (_progress.value is UiEngineInstallProgress.Resolving ||
            _progress.value is UiEngineInstallProgress.Downloading) return
        _progress.value = UiEngineInstallProgress.Resolving
        viewModelScope.launch {
            voices.downloadAndInstallEngine().collect { p ->
                _progress.value = p
                if (p is UiEngineInstallProgress.LaunchingInstaller) {
                    // OS dialog took over — recheck once we get composition back.
                    refresh()
                }
            }
        }
    }

    fun dismissProgress() {
        _progress.value = null
        refresh()
    }

    fun bypass() {
        _bypassed.value = true
    }

    fun uninstallExisting() {
        voices.uninstallExistingEngine()
    }

    private fun UiEngineState.toUi(): UiEngineState = this
}

@Composable
private fun EngineMissingScreen(
    state: UiEngineState,
    progress: UiEngineInstallProgress?,
    onInstall: () -> Unit,
    onUninstallExisting: () -> Unit,
    onSkip: () -> Unit,
    onDismissProgress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MagicSkeletonTile(
                modifier = Modifier.size(width = 180.dp, height = 240.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 96.dp,
            )
            Spacer(Modifier.height(spacing.lg))
            val (title, subtitle) = when {
                !state.installed -> "Voice engine required" to
                    "Storyvox uses VoxSherpa for the offline Library Nocturne voices. It's a one-time install."
                !state.isUpToDate -> "Voice engine update needed" to
                    "VoxSherpa ${state.installedVersionName} from upstream is installed, but storyvox needs the dry-run-fix build. Android can't replace it directly because the two builds are signed with different keys — uninstall the old one first, then install the fork."
                else -> "Voice engine ready" to "" // unreachable; gate dismisses itself
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))

            if (progress != null) {
                ProgressBlock(progress = progress, onDismiss = onDismissProgress)
            } else if (state.installed && !state.isUpToDate) {
                // Update path: signature-mismatched upstream is in the way.
                // Lead with the uninstall step; once the package is gone the
                // gate re-evaluates and presents the fresh-install path.
                BrassButton(
                    label = "Uninstall existing VoxSherpa",
                    onClick = onUninstallExisting,
                    variant = BrassButtonVariant.Primary,
                )
                Spacer(Modifier.height(spacing.sm))
                BrassButton(
                    label = "Continue without updating",
                    onClick = onSkip,
                    variant = BrassButtonVariant.Text,
                )
            } else {
                // Fresh-install path: no existing package, just download.
                BrassButton(
                    label = "Download voice engine",
                    onClick = onInstall,
                    variant = BrassButtonVariant.Primary,
                )
                Spacer(Modifier.height(spacing.sm))
                BrassButton(
                    label = "Continue without it",
                    onClick = onSkip,
                    variant = BrassButtonVariant.Text,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "Storyvox will fall back to your system TTS engine. Audio quality is noticeably lower.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProgressBlock(
    progress: UiEngineInstallProgress,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (progress) {
            UiEngineInstallProgress.Resolving -> {
                Text("Finding the latest VoxSherpa release…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(spacing.xs))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is UiEngineInstallProgress.Downloading -> {
                val pct = if (progress.totalBytes > 0L) {
                    (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                } else 0f
                val mb = progress.bytesRead / 1_000_000
                val totalMb = progress.totalBytes / 1_000_000
                Text(
                    "Downloading… $mb MB / $totalMb MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(spacing.xs))
                if (progress.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            UiEngineInstallProgress.LaunchingInstaller -> {
                Text(
                    "Confirm the install in the Android dialog, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.sm))
                BrassButton(
                    label = "I installed it",
                    onClick = onDismiss,
                    variant = BrassButtonVariant.Primary,
                )
            }
            is UiEngineInstallProgress.Failed -> {
                Text(
                    progress.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.sm))
                BrassButton(
                    label = "Try again",
                    onClick = onDismiss,
                    variant = BrassButtonVariant.Secondary,
                )
            }
        }
    }
}

