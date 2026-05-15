package `in`.jphe.storyvox.source.slack.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.slack.SlackSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SlackHttp

/**
 * Dedicated OkHttp client for the Slack Web API. Tight connect
 * timeout (slack.com is on a fast global edge), generous read
 * timeout (a 200-message `conversations.history` page can be ~300KB
 * once file/attachment metadata is inlined). Connect retries handle
 * the occasional transient TLS hiccup we see on cellular.
 *
 * Slack uses HTTP/2 keep-alive; OkHttp pools connections so a
 * fictionDetail → chapter flow shares the same TLS handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SlackHttpModule {

    @Provides
    @Singleton
    @SlackHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideSlackApi(
        @SlackHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.slack.config.SlackConfig,
    ): `in`.jphe.storyvox.source.slack.net.SlackApi =
        `in`.jphe.storyvox.source.slack.net.SlackApi(client, config)
}

/**
 * Issue #454 — contributes [SlackSource] into the multi-source
 * `Map<String, FictionSource>` so the repository can route
 * `sourceId = "slack"` fictions to it. Legacy Phase-2 dual-wire
 * binding — the matching `@SourcePlugin` annotation on
 * [SlackSource] adds the registry-driven descriptor binding
 * alongside it. Phase 3 removes this Module once all backends
 * migrate; until then, both bindings coexist (matches
 * `:source-discord`, `:source-telegram` pattern).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SlackBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.SLACK)
    abstract fun bindFictionSource(impl: SlackSource): FictionSource

    /** Public-visibility wrapper around the internal SlackApi so
     *  :app can render the Settings authentication probe + channel
     *  list without depending on the internal wire types. */
    @Binds
    @Singleton
    abstract fun bindSlackWorkspaceDirectory(
        impl: `in`.jphe.storyvox.source.slack.SlackWorkspaceDirectoryImpl,
    ): `in`.jphe.storyvox.source.slack.SlackWorkspaceDirectory
}
