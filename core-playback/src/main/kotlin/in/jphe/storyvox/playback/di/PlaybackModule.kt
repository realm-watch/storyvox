package `in`.jphe.storyvox.playback.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.playback.DefaultPlaybackController
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.SleepTimer
import `in`.jphe.storyvox.playback.TtsVolumeRamp
import `in`.jphe.storyvox.playback.VolumeRamp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackController(impl: DefaultPlaybackController): PlaybackController

    @Binds
    @Singleton
    abstract fun bindVolumeRamp(impl: TtsVolumeRamp): VolumeRamp

    companion object {
        @Provides
        @Singleton
        fun providePauseAction(controller: dagger.Lazy<PlaybackController>): SleepTimer.PauseAction =
            SleepTimer.PauseAction { controller.get().pause() }
    }
}
