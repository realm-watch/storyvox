package `in`.jphe.storyvox.playback.tts

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * First-run consent for picking a TTS engine. Aurora hosts this in the actual screen;
 * we provide it as a Composable atom co-located with its controller logic. The
 * `installUrl` is consumed by the caller's `onInstallVoxSherpa` handler — typically
 * launching an `ACTION_VIEW` intent — so this composable doesn't need to render it.
 */
@Composable
fun EngineConsentDialog(
    isVoxSherpaInstalled: Boolean,
    onUseVoxSherpa: () -> Unit,
    onUseSystemDefault: () -> Unit,
    onInstallVoxSherpa: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a voice engine") },
        text = {
            Text(
                if (isVoxSherpaInstalled) {
                    "VoxSherpa is installed and recommended for richer narration. " +
                        "You can switch later in Settings."
                } else {
                    "Install VoxSherpa for neural voices, or continue with the " +
                        "system default. You can switch later in Settings."
                },
            )
        },
        confirmButton = {
            if (isVoxSherpaInstalled) {
                TextButton(onClick = { onUseVoxSherpa(); onDismiss() }) {
                    Text("Use VoxSherpa")
                }
            } else {
                TextButton(onClick = { onInstallVoxSherpa() }) {
                    Text("Install VoxSherpa")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onUseSystemDefault(); onDismiss() }) {
                Text("Use system default")
            }
        },
    )
}
