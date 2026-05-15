package `in`.jphe.storyvox.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.repository.FictionLibraryListener
import `in`.jphe.storyvox.playback.cache.PrerenderTriggers

/**
 * PR-F (#86) — bind the cross-module seam between [FictionRepository]
 * (in :core-data) and [PrerenderTriggers] (in :core-playback) so a
 * `FictionRepository.addToLibrary` / `removeFromLibrary` call dispatches
 * to the playback layer's pre-render scheduler without :core-data
 * depending on :core-playback.
 *
 * Lives in :app because :app is the only Gradle module that depends
 * on both :core-data and :core-playback. Keeping the binding here
 * avoids a circular dep (core-data → core-playback → core-data) and
 * keeps the playback layer free of FictionRepository.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CacheBindingsModule {

    @Binds
    abstract fun bindFictionLibraryListener(
        impl: PrerenderTriggers,
    ): FictionLibraryListener
}
