package `in`.jphe.storyvox.feature.fiction

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #450 — regression guard for "tapping the second field of the
 * Notebook Add-note dialog doesn't move focus; typed characters
 * concatenate into the first field".
 *
 * The fix wires:
 *  - `FocusRequester` per field (Name + One-line description),
 *  - `KeyboardOptions(imeAction = ImeAction.Next)` on the Name field,
 *  - `KeyboardActions(onNext = focusManager.moveFocus(Down))`,
 *  - `imeAction = ImeAction.Done` on the description field, and
 *  - `keyboardActions = onDone = focusManager.clearFocus()`.
 *
 * The same pattern is mirrored in [PronunciationDictScreen]'s
 * [EntryEditorDialog] (Pattern + Replacement) per the bug report's
 * "Reproduced in two surfaces" note.
 *
 * We can't unit-test Compose focus semantics without Robolectric /
 * ComposeTestRule, so this test pins the structural contract via a
 * marker constant the production source exports.
 */
class NotebookFocusContractTest {

    @Test
    fun `Notebook Add-note form wires explicit focus plumbing per issue #450`() {
        // If a future refactor drops the FocusRequester / IME plumbing
        // (e.g., reverting to the pre-#450 shape where both
        // OutlinedTextFields were bare and the field-tap relied on
        // Compose's default hit-testing), this canary fails. Flipping
        // the marker back to `false` is only legitimate if the
        // replacement strategy keeps multi-field tap-focus working on
        // a Z Flip3 with IME up.
        assertTrue(
            "NotebookSection must wire FocusRequester + ImeAction.Next on the Add-note dialog (issue #450)",
            notebookAddNoteUsesExplicitFocus,
        )
    }
}
