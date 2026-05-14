package `in`.jphe.storyvox.source.kvmr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.kvmr.KvmrSource
import javax.inject.Singleton

/**
 * Issue #374 — contributes [KvmrSource] into the multi-source
 * `Map<String, FictionSource>`. Adds a "KVMR" entry to the
 * segmented Browse source picker; persisted fictions with
 * sourceId="kvmr" route through this source.
 *
 * KVMR is a network-zero source on the Hilt side — no OkHttp client,
 * no JSON catalog API. The only outbound HTTP is the stream fetch
 * itself, which Media3 / ExoPlayer handle inside `:core-playback`.
 * Pattern for future single-URL audio sources: this module + the
 * source file together total ~150 lines.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class KvmrBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.KVMR)
    abstract fun bindFictionSource(impl: KvmrSource): FictionSource
}
