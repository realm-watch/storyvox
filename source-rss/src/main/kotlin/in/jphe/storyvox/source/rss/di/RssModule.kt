package `in`.jphe.storyvox.source.rss.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.rss.RssSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RssHttp

/**
 * OkHttpClient for RSS fetches. Separate from the app-wide client so
 * a slow feed server doesn't poison shared timeouts. 10s connect /
 * 30s read is generous because some serial-fiction Substacks
 * legitimately take that long for cold reads.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RssHttpModule {

    @Provides
    @Singleton
    @RssHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
}

/**
 * Contributes [RssSource] into the multi-source `Map<String,
 * FictionSource>`. With this binding active, the segmented source
 * picker in Browse gets an "RSS" entry, and any persisted fiction
 * with sourceId="rss" routes through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RssBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.RSS)
    abstract fun bindFictionSource(impl: RssSource): FictionSource
}
