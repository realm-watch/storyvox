package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class BrassButtonVariant { Primary, Secondary, Text }

@Composable
fun BrassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BrassButtonVariant = BrassButtonVariant.Primary,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val sem = Modifier.semantics { role = Role.Button }
    // Disabled colors stay in the brass family rather than falling through to
    // M3's default `onSurface * 0.12 / 0.38`, which renders as cool grey and
    // breaks the brass aesthetic during reachable disabled flows (e.g.,
    // VoicePickerGate during voice download).
    val brass = MaterialTheme.colorScheme.primary
    val onBrass = MaterialTheme.colorScheme.onPrimary
    when (variant) {
        BrassButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.buttonColors(
                containerColor = brass,
                contentColor = onBrass,
                disabledContainerColor = brass.copy(alpha = 0.12f),
                disabledContentColor = onBrass.copy(alpha = 0.38f),
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }

        BrassButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = brass,
                disabledContentColor = brass.copy(alpha = 0.38f),
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }

        BrassButtonVariant.Text -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.textButtonColors(
                contentColor = brass,
                disabledContentColor = brass.copy(alpha = 0.38f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}
