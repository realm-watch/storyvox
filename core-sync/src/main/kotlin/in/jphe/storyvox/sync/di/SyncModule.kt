package `in`.jphe.storyvox.sync.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import `in`.jphe.storyvox.sync.BuildConfig
import `in`.jphe.storyvox.sync.client.DisabledBackend
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.OkHttpInstantTransport
import `in`.jphe.storyvox.sync.client.WsInstantBackend
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstoneStore
import `in`.jphe.storyvox.sync.domain.BookmarksSyncer
import `in`.jphe.storyvox.sync.domain.FollowsSyncer
import `in`.jphe.storyvox.sync.domain.LibrarySyncer
import `in`.jphe.storyvox.sync.domain.PlaybackPositionSyncer
import `in`.jphe.storyvox.sync.domain.PronunciationDictSyncer
import javax.inject.Singleton

/**
 * Hilt wiring for the `:core-sync` module.
 *
 * The DI graph:
 *  - [InstantBackend]: the data-plane stub. Resolves to [WsInstantBackend]
 *    when [BuildConfig.INSTANTDB_APP_ID] is a real value; to
 *    [DisabledBackend] when it's the sentinel placeholder. The sentinel
 *    keeps the app building on CI runners that don't have the secret.
 *  - [InstantClient]: the auth-plane HTTP client.
 *  - [InstantSession]: token storage.
 *  - [Set<Syncer>]: multibound, populated by each per-domain syncer
 *    contributing itself via `@IntoSet`. The [SyncCoordinator] injects
 *    the full set and dispatches per-domain.
 *
 * Why multibinds and not a hand-rolled "register every syncer in
 * onCreate": multibinds is the idiomatic Hilt seam for "plug in N
 * implementations of an interface and have the framework hand the
 * consumer the full set." Adding a new syncer is then a single `@Binds`
 * + `@IntoSet` line — no risk of forgetting to register, and the
 * coordinator's signature documents the contract.
 *
 * SecretsSyncer is wired in [`:app`] instead of here because it
 * depends on the EncryptedSharedPreferences provider that lives in
 * `:core-data` and on a passphrase provider that lives in
 * `:feature` (Settings). Keeping it out of this module avoids a
 * circular dep.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideHttpTransport(): InstantHttpTransport = OkHttpInstantTransport()

    @Provides
    @Singleton
    fun provideInstantClient(transport: InstantHttpTransport): InstantClient =
        InstantClient(appId = BuildConfig.INSTANTDB_APP_ID, transport = transport)

    @Provides
    @Singleton
    fun provideInstantSession(prefs: SharedPreferences): InstantSession =
        InstantSession(prefs)

    @Provides
    @Singleton
    fun provideInstantBackend(): InstantBackend {
        val appId = BuildConfig.INSTANTDB_APP_ID
        return if (appId.isBlank() || appId == WsInstantBackend.PLACEHOLDER_APP_ID) {
            DisabledBackend()
        } else {
            WsInstantBackend(appId)
        }
    }

    /** Bookmark / pronunciation / playback-position / library / follows
     *  syncers — every concrete [Syncer] gets contributed to the
     *  multibound set the coordinator consumes. New domains land here
     *  with a single `@IntoSet` line. */
    @Provides @IntoSet
    fun provideLibrarySyncer(impl: LibrarySyncer): Syncer = impl

    @Provides @IntoSet
    fun provideFollowsSyncer(impl: FollowsSyncer): Syncer = impl

    @Provides @IntoSet
    fun providePlaybackPositionSyncer(impl: PlaybackPositionSyncer): Syncer = impl

    @Provides @IntoSet
    fun providePronunciationDictSyncer(impl: PronunciationDictSyncer): Syncer = impl

    @Provides @IntoSet
    fun provideBookmarksSyncer(impl: BookmarksSyncer): Syncer = impl
}
