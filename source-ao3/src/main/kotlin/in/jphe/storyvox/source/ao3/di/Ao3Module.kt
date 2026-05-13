package `in`.jphe.storyvox.source.ao3.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.ao3.Ao3Source
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Issue #381 — Hilt wiring for the AO3 fiction backend.
 *
 * Mirrors [GutenbergModule][in.jphe.storyvox.source.gutenberg.di.GutenbergHttpModule]
 * exactly — dedicated OkHttp client with generous read timeouts
 * (AO3 EPUBs span 4 KB drabbles to 100 MB epics), dedicated cache
 * directory scoped to its own qualifier so future cleanup passes
 * don't confuse AO3 downloads with anyone else's bytes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ao3Cache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ao3Http

@Module
@InstallIn(SingletonComponent::class)
internal object Ao3HttpModule {

    @Provides
    @Singleton
    @Ao3Http
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // EPUB downloads dominate the read budget. AO3's longest
            // works (multi-million-word epics) take measurable time
            // even on fast connections — 60s matches the Gutenberg
            // client's headroom and absorbs the occasional slow
            // response without surfacing a spurious timeout.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideAo3Api(
        @Ao3Http client: OkHttpClient,
    ): Ao3Api = Ao3Api(client)

    @Provides
    @Singleton
    @Ao3Cache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "ao3").apply { mkdirs() }
}

/**
 * Contributes [Ao3Source] into the multi-source `Map<String,
 * FictionSource>`. Persisted fictions with sourceId="ao3" route
 * through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class Ao3Bindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.AO3)
    abstract fun bindFictionSource(impl: Ao3Source): FictionSource
}
