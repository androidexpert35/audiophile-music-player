package com.androidexpert35.audiophilemusicplayer.di

import com.androidexpert35.audiophilemusicplayer.data.repository.AudioTelemetryRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.LikedSongsRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.LyricsRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.MediaIndexRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.MusicRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.PlaybackPersistenceRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.PlaybackRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.PlaylistRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.RecentlyPlayedRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.RemoteImageRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsRepositoryImpl
import com.androidexpert35.audiophilemusicplayer.domain.repository.AudioTelemetryRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.LyricsRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.RemoteImageRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding repository interfaces to their concrete implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Binds [MusicRepositoryImpl] as the singleton [MusicRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindMusicRepository(
        impl: MusicRepositoryImpl
    ): MusicRepository

    /**
     * Binds [PlaybackRepositoryImpl] as the singleton [PlaybackRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindPlaybackRepository(
        impl: PlaybackRepositoryImpl
    ): PlaybackRepository

    /**
     * Binds [PlaylistRepositoryImpl] as the singleton [PlaylistRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(
        impl: PlaylistRepositoryImpl
    ): PlaylistRepository

    /**
     * Binds [AudioTelemetryRepositoryImpl] as the singleton [AudioTelemetryRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindAudioTelemetryRepository(
        impl: AudioTelemetryRepositoryImpl
    ): AudioTelemetryRepository

    /**
     * Binds [MediaIndexRepositoryImpl] as the singleton [MediaIndexRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindMediaIndexRepository(
        impl: MediaIndexRepositoryImpl
    ): MediaIndexRepository

    /**
     * Binds [PlaybackPersistenceRepositoryImpl] as the singleton [PlaybackPersistenceRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindPlaybackPersistenceRepository(
        impl: PlaybackPersistenceRepositoryImpl
    ): PlaybackPersistenceRepository

    /**
     * Binds [RemoteImageRepositoryImpl] as the singleton [RemoteImageRepository] provider.
     *
     * Scoped as a singleton so the in-memory guard against duplicate concurrent Deezer
     * requests for the same artist or album is effective across the entire app lifecycle.
     */
    @Binds
    @Singleton
    abstract fun bindRemoteImageRepository(
        impl: RemoteImageRepositoryImpl
    ): RemoteImageRepository

    /**
     * Binds [LikedSongsRepositoryImpl] as the singleton [LikedSongsRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindLikedSongsRepository(
        impl: LikedSongsRepositoryImpl
    ): LikedSongsRepository

    /**
     * Binds [RecentlyPlayedRepositoryImpl] as the singleton [RecentlyPlayedRepository] provider.
     */
    @Binds
    @Singleton
    abstract fun bindRecentlyPlayedRepository(
        impl: RecentlyPlayedRepositoryImpl
    ): RecentlyPlayedRepository

    /**
     * Binds [SettingsRepositoryImpl] as the singleton [SettingsRepository] provider.
     *
     * Drives the dual-engine runtime toggle surfaced in the Settings screen.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    /**
     * Binds [LyricsRepositoryImpl] as the singleton [LyricsRepository] provider.
     *
     * Scoped as a singleton so the in-memory cache key computations and the
     * single-flight fetch guard are effective across the entire app lifecycle.
     */
    @Binds
    @Singleton
    abstract fun bindLyricsRepository(
        impl: LyricsRepositoryImpl
    ): LyricsRepository
}

