package `in`.jphe.storyvox.feature.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Paste-anything sheet for adding a fiction by URL. Accepts Royal Road
 * fiction URLs today, with the GitHub branch wired but stubbed at the
 * data layer until step 3 of the GitHub-source plan lands.
 *
 * UX: input field auto-focused, "Paste" button reads the system
 * clipboard (single-tap fill from a copied URL), Add button submits.
 * The error string from the viewmodel surfaces inline below the field
 * so the user can correct without losing what they typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddByUrlSheet(
    state: AddByUrlSheetState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state === AddByUrlSheetState.Hidden) return

    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    val error: String? = (state as? AddByUrlSheetState.Open)?.error
    val isSubmitting = state === AddByUrlSheetState.Submitting

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                "Add by URL",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )
            // Issue #446 — copy used to read "Paste a Royal Road
            // fiction or chapter URL", which implied RR was the only
            // accepted source. The URL router resolves Royal Road +
            // GitHub today and the parallel magic-link work (#472)
            // adds AO3 / Gutenberg / arXiv / RSS / Wikipedia / Plos /
            // Wikisource / Standard Ebooks / a Readability catch-all.
            // Generalize the hint so any pasted URL feels welcome; the
            // detection-failure message handles the unrecognised case.
            // TODO(#472): the magic-link agent owns the multi-match
            // chooser UI that will replace this single-string entry
            // point; once that lands, this sheet either gets replaced
            // wholesale or grows a candidate-preview row.
            Text(
                "Paste a fiction URL — we'll auto-detect the source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Supported: Royal Road · AO3 · GitHub · Gutenberg · arXiv · " +
                    "RSS · Wikipedia · direct EPUB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("URL") },
                placeholder = { Text("https://…") },
                isError = error != null,
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrassButton(
                    label = "Paste",
                    onClick = {
                        readClipboardText(context)?.let { clip ->
                            if (clip.isNotBlank()) input = clip
                        }
                    },
                    variant = BrassButtonVariant.Secondary,
                    enabled = !isSubmitting,
                )
                BrassButton(
                    label = if (isSubmitting) "Adding…" else "Add",
                    onClick = { onSubmit(input.trim()) },
                    variant = BrassButtonVariant.Primary,
                    enabled = !isSubmitting && input.isNotBlank(),
                )
            }
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()
}
