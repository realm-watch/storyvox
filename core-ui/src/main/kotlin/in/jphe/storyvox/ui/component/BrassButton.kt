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
    when (variant) {
        BrassButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }

        BrassButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }

        BrassButtonVariant.Text -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}
