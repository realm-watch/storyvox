package `in`.jphe.storyvox.source.royalroad.di

import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.royalroad.RoyalRoadSource
import `in`.jphe.storyvox.source.royalroad.auth.RoyalRoadSessionHydrator
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.net.RateLimitedClient
import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadCookieJar
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class RoyalRoadHttp

@Module
@InstallIn(SingletonComponent::class)
internal object RoyalRoadHttpModule {

    @Provides @Singleton @RoyalRoadHttp
    fun provideClient(jar: RoyalRoadCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            // Tab A7 Lite is the constraint device. With no explicit
            // timeouts OkHttp falls back to 10 s/10 s/10 s, and combined
            // with retryOnConnectionFailure(true) below the worst-case
            // stall on Wi-Fi-off / flaky-cellular reaches ~30 s — long
            // enough that Browse / FictionDetail sit at "blank cream"
            // before the upstream Failure surfaces and ErrorBlock
            // renders. Snappier numbers cap the perceived latency:
            //   connectTimeout:  TCP handshake (incl. TLS pre-negotiation
            //                    on a flaky link).
            //   readTimeout:     between socket reads while a response
            //                    streams.
            //   callTimeout:     hard cap on the whole call (DNS +
            //                    connect + TLS + read + retries) — the
            //                    safety net that bounds the worst case
            //                    even when retryOnConnectionFailure
            //                    elects to retry.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", RoyalRoadIds.USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()

    @Provides @Singleton
    fun provideRateLimitedClient(@RoyalRoadHttp http: OkHttpClient): RateLimitedClient =
        RateLimitedClient(http)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RoyalRoadBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey("royalroad")
    abstract fun bindFictionSource(impl: RoyalRoadSource): FictionSource

    @Binds
    @Singleton
    abstract fun bindSessionHydrator(impl: RoyalRoadSessionHydrator): SessionHydrator
}
