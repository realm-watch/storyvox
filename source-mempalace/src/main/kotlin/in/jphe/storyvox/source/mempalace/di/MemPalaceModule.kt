package `in`.jphe.storyvox.source.mempalace.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.mempalace.MemPalaceSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PalaceHttp

/**
 * Provides the OkHttpClient used by [`PalaceDaemonApi`]. Qualified
 * [PalaceHttp] so it doesn't collide with the unqualified app-wide
 * client (or the @GitHubHttp / @RoyalRoadHttp ones).
 *
 * Tighter timeouts than the other source clients — palace-daemon is
 * on the LAN, so a slow connect means we're off-network rather than
 * "the internet is slow." 1.5s connect timeout flips reachability
 * fast; reads get 30s because cold `/graph` on a 150K palace can
 * legitimately take that long.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PalaceHttpModule {

    @Provides
    @Singleton
    @PalaceHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
}

/**
 * Contributes [MemPalaceSource] into the multi-source `Map<String,
 * FictionSource>`. With this binding active, the segmented source
 * picker in Browse gets a "Memory Palace" entry, and any persisted
 * fiction with sourceId="mempalace" routes through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MemPalaceBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.MEMPALACE)
    abstract fun bindFictionSource(impl: MemPalaceSource): FictionSource
}
