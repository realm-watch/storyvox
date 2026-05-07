package `in`.jphe.storyvox.source.github.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class GitHubHttp

/**
 * Provides the OkHttpClient used by [`in`.jphe.storyvox.source.github
 * .net.GitHubApi]. Qualified [GitHubHttp] so it doesn't collide with
 * the unqualified app-wide client (or the @RoyalRoadHttp one).
 *
 * **NOT** wired into a `FictionSource` Hilt binding yet — that lands
 * in step 3d when [`in`.jphe.storyvox.source.github.GitHubSource]
 * actually returns real [`in`.jphe.storyvox.data.source.model
 * .FictionDetail] data. Today the client is only consumed by
 * GitHubApi and that's only consumed by GitHubSource which throws on
 * every call. The module is here so the dependency graph compiles
 * once GitHubSource starts returning real data — no plumbing change
 * needed at that point.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GitHubHttpModule {

    @Provides
    @Singleton
    @GitHubHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
}
