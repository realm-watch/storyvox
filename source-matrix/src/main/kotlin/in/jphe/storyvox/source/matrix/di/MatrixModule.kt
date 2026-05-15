package `in`.jphe.storyvox.source.matrix.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.matrix.MatrixSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MatrixHttp

/**
 * Issue #457 — dedicated OkHttp client for the Matrix Client-Server
 * API. Matrix homeservers vary wildly in latency profile (matrix.org
 * on a global CDN vs. a self-hosted Synapse on a home server in a
 * different country), so the read timeout is generous. Federation
 * dispatch for profile lookups means a single request may have to
 * wait on a homeserver-to-homeserver fanout in the worst case.
 *
 * Matrix homeservers use HTTP/2 keep-alive when available; OkHttp
 * pools connections so a popular() → fictionDetail() → chapter()
 * flow shares the same TLS handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object MatrixHttpModule {

    @Provides
    @Singleton
    @MatrixHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            // Slightly looser connect timeout than Discord's: a
            // self-hosted Synapse on a home server can take longer
            // on initial TLS handshake than discord.com's edge.
            .connectTimeout(10, TimeUnit.SECONDS)
            // Generous read timeout — federation profile lookups
            // can stall when the user's home homeserver is slow
            // to respond.
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideMatrixApi(
        @MatrixHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.matrix.config.MatrixConfig,
    ): `in`.jphe.storyvox.source.matrix.net.MatrixApi =
        `in`.jphe.storyvox.source.matrix.net.MatrixApi(client, config)
}

/**
 * Issue #457 — contributes [MatrixSource] into the multi-source
 * `Map<String, FictionSource>` so the repository can route
 * `sourceId = "matrix"` fictions to it. Legacy Phase-2 dual-wire
 * binding — the matching `@SourcePlugin` annotation on
 * [MatrixSource] adds the registry-driven descriptor binding
 * alongside it. Phase 3 removes this Module once all backends
 * migrate; until then both bindings coexist (matches `:source-discord`
 * + `:source-telegram` pattern).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MatrixBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.MATRIX)
    abstract fun bindFictionSource(impl: MatrixSource): FictionSource

    /** Public-visibility wrapper around the internal MatrixApi so
     *  :app can render the room-picker / whoami confirmation in a
     *  future Settings PR without depending on the internal wire
     *  types. */
    @Binds
    @Singleton
    abstract fun bindMatrixJoinedRoomsDirectory(
        impl: `in`.jphe.storyvox.source.matrix.MatrixJoinedRoomsDirectoryImpl,
    ): `in`.jphe.storyvox.source.matrix.MatrixJoinedRoomsDirectory
}
