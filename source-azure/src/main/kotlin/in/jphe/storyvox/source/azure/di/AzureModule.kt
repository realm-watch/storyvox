package `in`.jphe.storyvox.source.azure.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the OkHttpClient used by `AzureSpeechClient`. Distinct
 * from `@LlmHttp`, `@RoyalRoadHttp`, and `@GitHubHttp` because the
 * Azure client wants:
 *
 *  - A short-ish read timeout (60 s) — synthesizing a long sentence
 *    takes well under a second; anything longer than 60 s indicates
 *    a stuck connection that the user wants the OS to surface as an
 *    error rather than wait through.
 *  - **Header redaction** of the `Ocp-Apim-Subscription-Key` header
 *    in the logging interceptor. The other HTTP clients don't carry
 *    an Azure key so they don't redact this header by default; we
 *    don't want a future "log all requests at HEADERS level" debug
 *    pass to leak the key.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AzureHttp

/**
 * Hilt graph for `:source-azure`. Provides the [OkHttpClient] used by
 * [in.jphe.storyvox.source.azure.AzureSpeechClient].
 *
 * `AzureCredentials` and `AzureSpeechClient` themselves are
 * `@Singleton @Inject constructor` — Hilt picks them up automatically
 * via `@Inject` plus `@InstallIn(SingletonComponent::class)` on this
 * module.
 *
 * The encrypted [SharedPreferences] that `AzureCredentials` reads
 * comes from `:core-data`'s `DataModule.provideEncryptedPrefs` — the
 * same instance that holds Royal Road cookies, the GitHub PAT, and
 * the LLM keys. No new master key, no new encrypted file.
 */
@Module
@InstallIn(SingletonComponent::class)
object AzureModule {

    @Provides
    @Singleton
    @AzureHttp
    fun provideAzureHttp(): OkHttpClient {
        // BASIC level logs request line + response code only — enough to
        // diagnose "is the request reaching Azure" without leaking SSML
        // bodies (chapter text isn't secret, but volume is high) or
        // header values. The redactHeader call belt-and-braces removes
        // the subscription-key header even if a later debug pass bumps
        // the level to HEADERS.
        val logger = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Ocp-Apim-Subscription-Key")
            redactHeader("Authorization") // defensive; Azure doesn't use this header
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // OkHttp's default connection pool reuses connections for
            // 5 minutes — exactly what we want for sentence-by-sentence
            // synthesis. The TLS handshake amortizes across all sentences
            // in a chapter so per-sentence latency drops to one
            // round-trip after the first.
            .retryOnConnectionFailure(true)
            .addInterceptor(logger)
            .build()
    }
}
