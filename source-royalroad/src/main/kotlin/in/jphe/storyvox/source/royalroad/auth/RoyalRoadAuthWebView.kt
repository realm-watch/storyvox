package `in`.jphe.storyvox.source.royalroad.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds

/**
 * Composable that hosts a WebView pointed at Royal Road's login page.
 *
 * We never see the password. We watch every navigation; once the cookie set
 * for the host contains `.AspNetCore.Identity.Application`, login succeeded.
 * Capture all royalroad.com cookies, hand them to the caller via [onSession],
 * and the host activity persists them via EncryptedSharedPreferences.
 *
 * Cancellation: caller dismisses by removing this composable from composition;
 * the AndroidView's onDispose tears down the WebView cleanly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoyalRoadAuthWebView(
    modifier: Modifier = Modifier,
    onSession: (SessionCookies) -> Unit,
    onCancelled: () -> Unit = {},
) {
    val capturedHandler = remember { CapturedSession(onSession) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = RoyalRoadIds.USER_AGENT
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url ?: return
                        val cookies = readCookiesFor(RoyalRoadIds.BASE_URL)
                        if (cookies.containsKey(IDENTITY_COOKIE)) {
                            capturedHandler.deliver(cookies)
                        }
                    }
                }
                loadUrl("${RoyalRoadIds.BASE_URL}/account/login")
            }
        },
        onRelease = { wv ->
            if (!capturedHandler.delivered) onCancelled()
            wv.destroy()
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
        }
    }
}

private const val IDENTITY_COOKIE = ".AspNetCore.Identity.Application"

private fun readCookiesFor(url: String): Map<String, String> {
    val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
    return raw.split(";")
        .mapNotNull { entry ->
            val trimmed = entry.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) null else trimmed.substring(0, eq) to trimmed.substring(eq + 1)
        }
        .toMap()
}

data class SessionCookies(val cookies: Map<String, String>)

private class CapturedSession(private val onSession: (SessionCookies) -> Unit) {
    var delivered: Boolean = false
        private set

    fun deliver(cookies: Map<String, String>) {
        if (delivered) return
        delivered = true
        onSession(SessionCookies(cookies))
    }
}
