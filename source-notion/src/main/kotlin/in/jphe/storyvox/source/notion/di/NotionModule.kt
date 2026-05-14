package `in`.jphe.storyvox.source.notion.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.notion.NotionSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotionHttp

/**
 * Dedicated OkHttp client for the Notion REST API. Generous read
 * timeout because block-children fetches on long pages can compose
 * into 10-20 paginated round-trips and Notion's edge can be slow
 * during peak hours. Connect timeout stays tight — api.notion.com is
 * Cloudflare-fronted and reliably reachable.
 *
 * Notion uses HTTP/2 keep-alive; OkHttp pools connections so a
 * fictionDetail → chapter flow shares the same TLS handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NotionHttpModule {

    @Provides
    @Singleton
    @NotionHttp
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
    fun provideNotionApi(
        @NotionHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.notion.config.NotionConfig,
    ): `in`.jphe.storyvox.source.notion.net.NotionApi =
        `in`.jphe.storyvox.source.notion.net.NotionApi(client, config)
}

/**
 * Issue #233 — contributes [NotionSource] into the multi-source
 * `Map<String, FictionSource>`. Adds a "Notion" entry to the
 * segmented source picker; persisted fictions with sourceId="notion"
 * route through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotionBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.NOTION)
    abstract fun bindFictionSource(impl: NotionSource): FictionSource
}
