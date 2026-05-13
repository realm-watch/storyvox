package `in`.jphe.storyvox.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.migration.ALL_MIGRATIONS
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.AuthRepositoryImpl
import `in`.jphe.storyvox.data.repository.ChapterDownloadScheduler
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ChapterRepositoryImpl
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.FictionRepositoryImpl
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.repository.FollowsRepositoryImpl
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.HistoryRepositoryImpl
import `in`.jphe.storyvox.data.repository.LibraryRepository
import `in`.jphe.storyvox.data.repository.LibraryRepositoryImpl
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepositoryImpl
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.ShelfRepositoryImpl
import `in`.jphe.storyvox.data.repository.WorkManagerChapterDownloadScheduler
import javax.inject.Singleton

/**
 * Hilt graph for `:core-data`. Note that `FictionSource` itself is NOT bound
 * here — it lives in `:source-royalroad` (Oneiros) under its own
 * `@InstallIn(SingletonComponent::class)` module. We just inject it into our
 * repository impls.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): StoryvoxDatabase =
        Room.databaseBuilder(ctx, StoryvoxDatabase::class.java, StoryvoxDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun fictionDao(db: StoryvoxDatabase): FictionDao = db.fictionDao()
    @Provides fun chapterDao(db: StoryvoxDatabase): ChapterDao = db.chapterDao()
    @Provides fun chapterHistoryDao(db: StoryvoxDatabase): ChapterHistoryDao = db.chapterHistoryDao()
    @Provides fun playbackDao(db: StoryvoxDatabase): PlaybackDao = db.playbackDao()
    @Provides fun authDao(db: StoryvoxDatabase): AuthDao = db.authDao()
    @Provides fun llmSessionDao(db: StoryvoxDatabase): LlmSessionDao = db.llmSessionDao()
    @Provides fun llmMessageDao(db: StoryvoxDatabase): LlmMessageDao = db.llmMessageDao()
    @Provides fun fictionShelfDao(db: StoryvoxDatabase): FictionShelfDao = db.fictionShelfDao()

    @Provides
    @Singleton
    fun provideEncryptedPrefs(@ApplicationContext ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            "storyvox.secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds @Singleton
    abstract fun bindFictionRepository(impl: FictionRepositoryImpl): FictionRepository

    @Binds @Singleton
    abstract fun bindChapterRepository(impl: ChapterRepositoryImpl): ChapterRepository

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindPlaybackPositionRepository(
        impl: PlaybackPositionRepositoryImpl,
    ): PlaybackPositionRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindFollowsRepository(impl: FollowsRepositoryImpl): FollowsRepository

    @Binds @Singleton
    abstract fun bindShelfRepository(impl: ShelfRepositoryImpl): ShelfRepository

    @Binds @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds @Singleton
    abstract fun bindChapterDownloadScheduler(
        impl: WorkManagerChapterDownloadScheduler,
    ): ChapterDownloadScheduler
}
