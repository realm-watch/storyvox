package `in`.jphe.storyvox.source.github.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.github.GitHubSource
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

/**
 * Contributes [GitHubSource] into the multi-source `Map<String,
 * FictionSource>` from PR #35. With this binding active,
 * `addByUrl(github URL)` flows end-to-end through the data layer:
 * `UrlRouter` returns sourceId="github", `FictionRepository.addByUrl`
 * looks up `sources[SourceIds.GITHUB]`, and `GitHubSource
 * .fictionDetail` resolves the manifest + chapters.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GitHubBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.GITHUB)
    abstract fun bindFictionSource(impl: GitHubSource): FictionSource
}
