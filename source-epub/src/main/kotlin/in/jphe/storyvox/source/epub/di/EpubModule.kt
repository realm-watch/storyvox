package `in`.jphe.storyvox.source.epub.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.epub.EpubSource
import javax.inject.Singleton

/**
 * Contributes [EpubSource] into the multi-source `Map<String,
 * FictionSource>` (#235). With this binding active, the segmented
 * source picker in Browse gets a "Local Books" entry, and any
 * persisted fiction with sourceId="epub" routes through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class EpubBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.EPUB)
    abstract fun bindFictionSource(impl: EpubSource): FictionSource
}
