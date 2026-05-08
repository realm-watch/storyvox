package `in`.jphe.storyvox.auth

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.auth.AuthViewModel
import `in`.jphe.storyvox.feature.auth.CaptureState
import `in`.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthWebView

/**
 * Hosts the Royal Road login WebView and pipes captured cookies into
 * [AuthViewModel]. Lives in `:app` so it can depend on both `:feature`
 * (for the Hilt-injected ViewModel) and `:source-royalroad` (for the
 * actual WebView Composable). `:feature` stays free of source-specific
 * code.
 *
 * Lifecycle:
 *  - User taps "Sign in" → navigation pushes this screen.
 *  - WebView loads `https://www.royalroad.com/account/login`.
 *  - User completes the form (or cancels via the back arrow).
 *  - On `.AspNetCore.Identity.Application` cookie appearance, the WebView
 *    fires [AuthViewModel.captureCookies]. Once the VM transitions to
 *    [CaptureState.Captured], we trigger [onSignedIn] which the NavHost
 *    wires to `popBackStack()`.
 */
@Composable
fun AuthWebViewScreen(
    onSignedIn: () -> Unit,
    onCancelled: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val capture by viewModel.captureState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(capture) {
        if (capture is CaptureState.Captured) {
            // Toast outlives the Composable's destruction, unlike a Snackbar
            // anchored to a Scaffold that's about to be popped.
            Toast.makeText(context, "Signed in to Royal Road", Toast.LENGTH_SHORT).show()
            onSignedIn()
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            RoyalRoadAuthWebView(
                onSession = { session -> viewModel.captureCookies(session.cookies) },
                onCancelled = onCancelled,
            )

            FilledIconButton(
                onClick = onCancelled,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Cancel sign-in")
            }

            if (capture is CaptureState.Capturing) {
                CircularProgressIndicator(
                    // TalkBack #160 — without contentDescription the spinner
                    // announces nothing, leaving a screen-reader user
                    // wondering if the OAuth flow froze. Mirror the visible
                    // semantics of "we're working on it".
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Loading sign-in page" },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
