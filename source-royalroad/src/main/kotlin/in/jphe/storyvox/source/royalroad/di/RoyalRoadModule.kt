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
import okhttp3.OkHttpClient
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
    abstract fun bindFictionSource(impl: RoyalRoadSource): FictionSource

    @Binds
    @Singleton
    abstract fun bindSessionHydrator(impl: RoyalRoadSessionHydrator): SessionHydrator
}
