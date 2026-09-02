package com.androidexpert35.audiophilemusicplayer.di

import com.androidexpert35.audiophilemusicplayer.domain.repository.AudioTelemetryRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.LyricsRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.RemoteImageRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ClearPlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ClearQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.CreatePlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.DeletePlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumArtUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistImageUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetLyricsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.HasMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.MoveQueueItemUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMediaStoreChangesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMostPlayedTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveQueueStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveRecentlyPlayedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PausePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTracksNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RecordRecentlyPlayedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RefreshUsbAudioDevicesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReleaseUsbAudioUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RemoveMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReorderPlaylistTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReplacePlaylistTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RequestUsbAudioPermissionUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RestorePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ResumePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SavePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SearchTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SeekToPositionUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetRepeatModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetShuffleModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetTracksLikedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipPreviousUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ToggleLikeSongUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module providing all domain use case instances.
 *
 * Use cases have no `@Inject constructor` so the Domain layer remains
 * completely free of Android / DI framework annotations.
 */
@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    /** Provides persisted library layout preferences backed by [SettingsRepository]. */
    @Provides
    fun provideGetLibraryDisplayPreferencesUseCase(
        settingsRepository: SettingsRepository
    ): GetLibraryDisplayPreferencesUseCase = GetLibraryDisplayPreferencesUseCase(settingsRepository)

    /** Provides the writer for persisted library layout preferences. */
    @Provides
    fun provideSetLibraryDisplayPreferencesUseCase(
        settingsRepository: SettingsRepository
    ): SetLibraryDisplayPreferencesUseCase = SetLibraryDisplayPreferencesUseCase(settingsRepository)

    /** Provides the reactive reader for persisted library layout preferences. */
    @Provides
    fun provideObserveLibraryDisplayPreferencesUseCase(
        settingsRepository: SettingsRepository
    ): ObserveLibraryDisplayPreferencesUseCase =
        ObserveLibraryDisplayPreferencesUseCase(settingsRepository)

    /** Provides the reactive reader for the persisted library section display order. */
    @Provides
    fun provideObserveLibrarySectionOrderUseCase(
        settingsRepository: SettingsRepository
    ): ObserveLibrarySectionOrderUseCase = ObserveLibrarySectionOrderUseCase(settingsRepository)

    /** Provides the writer for the persisted library section display order. */
    @Provides
    fun provideSetLibrarySectionOrderUseCase(
        settingsRepository: SettingsRepository
    ): SetLibrarySectionOrderUseCase = SetLibrarySectionOrderUseCase(settingsRepository)

    /** Provides [ObservePlaylistsUseCase] backed by the local M3U playlist repository. */
    @Provides
    fun provideObservePlaylistsUseCase(
        playlistRepository: PlaylistRepository
    ): ObservePlaylistsUseCase = ObservePlaylistsUseCase(playlistRepository)

    /** Provides [CreatePlaylistUseCase] backed by the local M3U playlist repository. */
    @Provides
    fun provideCreatePlaylistUseCase(
        playlistRepository: PlaylistRepository
    ): CreatePlaylistUseCase = CreatePlaylistUseCase(playlistRepository)

    /** Provides [AddTrackToPlaylistUseCase] backed by the local M3U playlist repository. */
    @Provides
    fun provideAddTrackToPlaylistUseCase(
        playlistRepository: PlaylistRepository
    ): AddTrackToPlaylistUseCase = AddTrackToPlaylistUseCase(playlistRepository)

    /** Provides [AddTracksToPlaylistUseCase] for one atomic multi-track append. */
    @Provides
    fun provideAddTracksToPlaylistUseCase(
        playlistRepository: PlaylistRepository
    ): AddTracksToPlaylistUseCase = AddTracksToPlaylistUseCase(playlistRepository)

    /** Provides [ReorderPlaylistTracksUseCase] backed by the local M3U playlist repository. */
    @Provides
    fun provideReorderPlaylistTracksUseCase(
        playlistRepository: PlaylistRepository
    ): ReorderPlaylistTracksUseCase = ReorderPlaylistTracksUseCase(playlistRepository)

    /** Provides [ReplacePlaylistTracksUseCase] for confirmed playlist edits. */
    @Provides
    fun provideReplacePlaylistTracksUseCase(
        playlistRepository: PlaylistRepository
    ): ReplacePlaylistTracksUseCase = ReplacePlaylistTracksUseCase(playlistRepository)

    /** Provides [DeletePlaylistUseCase] backed by the local M3U playlist repository. */
    @Provides
    fun provideDeletePlaylistUseCase(
        playlistRepository: PlaylistRepository
    ): DeletePlaylistUseCase = DeletePlaylistUseCase(playlistRepository)

    /**
     * Provides [GetTracksUseCase] backed by [MusicRepository].
     *
     * Retrieves the full sorted list of audio tracks from the Room-indexed library.
     */
    @Provides
    fun provideGetTracksUseCase(
        musicRepository: MusicRepository
    ): GetTracksUseCase = GetTracksUseCase(musicRepository)

    /**
     * Provides [ObserveMediaStoreChangesUseCase] backed by [MediaIndexRepository].
     *
     * Delivers a [kotlinx.coroutines.flow.Flow] that emits [Unit] whenever the device's external
     * audio MediaStore changes, enabling reactive re-indexing without polling.
     */
    @Provides
    fun provideObserveMediaStoreChangesUseCase(
        mediaIndexRepository: MediaIndexRepository
    ): ObserveMediaStoreChangesUseCase = ObserveMediaStoreChangesUseCase(mediaIndexRepository)

    /**
     * Provides [ScanAndIndexMediaUseCase] backed by [MediaIndexRepository].
     *
     * Runs the one-time MediaStore scan and populates the Room index,
     * emitting real-time progress updates.
     */
    @Provides
    fun provideScanAndIndexMediaUseCase(
        mediaIndexRepository: MediaIndexRepository
    ): ScanAndIndexMediaUseCase = ScanAndIndexMediaUseCase(mediaIndexRepository)

    /**
     * Provides [IsMediaLibraryIndexedUseCase] backed by [MediaIndexRepository].
     *
     * Returns whether a completed library scan result is already cached in Room.
     */
    @Provides
    fun provideIsMediaLibraryIndexedUseCase(
        mediaIndexRepository: MediaIndexRepository
    ): IsMediaLibraryIndexedUseCase = IsMediaLibraryIndexedUseCase(mediaIndexRepository)

    /**
     * Provides [ObserveMusicFoldersUseCase] backed by [MusicFolderRepository].
     *
     * Streams the locations the library scan is scoped to, so Settings can list them.
     */
    @Provides
    fun provideObserveMusicFoldersUseCase(
        musicFolderRepository: MusicFolderRepository
    ): ObserveMusicFoldersUseCase = ObserveMusicFoldersUseCase(musicFolderRepository)

    /**
     * Provides [HasMusicFoldersUseCase] backed by [MusicFolderRepository].
     *
     * Tells onboarding whether the user still has to point the app at their music.
     */
    @Provides
    fun provideHasMusicFoldersUseCase(
        musicFolderRepository: MusicFolderRepository
    ): HasMusicFoldersUseCase = HasMusicFoldersUseCase(musicFolderRepository)

    /**
     * Provides [AddMusicFolderUseCase] backed by [MusicFolderRepository].
     *
     * Persists a folder grant, widening the scan scope to that tree.
     */
    @Provides
    fun provideAddMusicFolderUseCase(
        musicFolderRepository: MusicFolderRepository
    ): AddMusicFolderUseCase = AddMusicFolderUseCase(musicFolderRepository)

    /**
     * Provides [RemoveMusicFolderUseCase] backed by [MusicFolderRepository].
     *
     * Drops a folder grant so its tracks leave the catalogue on the next scan.
     */
    @Provides
    fun provideRemoveMusicFolderUseCase(
        musicFolderRepository: MusicFolderRepository
    ): RemoveMusicFolderUseCase = RemoveMusicFolderUseCase(musicFolderRepository)

    /**
     * Provides [GetAlbumsUseCase] backed by [MusicRepository].
     *
     * Retrieves the full list of albums from the Room-indexed library.
     */
    @Provides
    fun provideGetAlbumsUseCase(
        musicRepository: MusicRepository
    ): GetAlbumsUseCase = GetAlbumsUseCase(musicRepository)

    /**
     * Provides [GetArtistsUseCase] backed by [MusicRepository].
     *
     * Retrieves the full list of artists from the Room-indexed library.
     */
    @Provides
    fun provideGetArtistsUseCase(
        musicRepository: MusicRepository
    ): GetArtistsUseCase = GetArtistsUseCase(musicRepository)

    /**
     * Provides [SearchTracksUseCase] backed by [MusicRepository].
     *
     * Performs a case-insensitive local search against track title, artist, and album metadata.
     */
    @Provides
    fun provideSearchTracksUseCase(
        musicRepository: MusicRepository
    ): SearchTracksUseCase = SearchTracksUseCase(musicRepository)

    /**
     * Provides [PlayTrackUseCase] backed by [PlaybackRepository].
     *
     * Starts playback of a selected track within the given queue context.
     */
    @Provides
    fun providePlayTrackUseCase(
        playbackRepository: PlaybackRepository
    ): PlayTrackUseCase = PlayTrackUseCase(playbackRepository)

    /** Provides [PlayNextUseCase] for inserting a track after the active queue item. */
    @Provides
    fun providePlayNextUseCase(
        playbackRepository: PlaybackRepository
    ): PlayNextUseCase = PlayNextUseCase(playbackRepository)

    /** Provides [PlayTracksNextUseCase] for ordered collection insertion. */
    @Provides
    fun providePlayTracksNextUseCase(
        playbackRepository: PlaybackRepository
    ): PlayTracksNextUseCase = PlayTracksNextUseCase(playbackRepository)

    /** Provides [AddTrackToQueueUseCase] for appending a track to the active queue. */
    @Provides
    fun provideAddTrackToQueueUseCase(
        playbackRepository: PlaybackRepository
    ): AddTrackToQueueUseCase = AddTrackToQueueUseCase(playbackRepository)

    /** Provides [AddTracksToQueueUseCase] for ordered collection appends. */
    @Provides
    fun provideAddTracksToQueueUseCase(
        playbackRepository: PlaybackRepository
    ): AddTracksToQueueUseCase = AddTracksToQueueUseCase(playbackRepository)

    /** Provides [MoveQueueItemUseCase] for manual active-queue reordering. */
    @Provides
    fun provideMoveQueueItemUseCase(
        playbackRepository: PlaybackRepository
    ): MoveQueueItemUseCase = MoveQueueItemUseCase(playbackRepository)

    /** Provides [ClearQueueUseCase] for removing the active playback queue. */
    @Provides
    fun provideClearQueueUseCase(
        playbackRepository: PlaybackRepository
    ): ClearQueueUseCase = ClearQueueUseCase(playbackRepository)

    /**
     * Provides [PausePlaybackUseCase] backed by [PlaybackRepository].
     *
     * Pauses the currently playing track without releasing the Media3 session.
     */
    @Provides
    fun providePausePlaybackUseCase(
        playbackRepository: PlaybackRepository
    ): PausePlaybackUseCase = PausePlaybackUseCase(playbackRepository)

    /** Provides the explicit command that returns an exclusive USB DAC to Android. */
    @Provides
    fun provideReleaseUsbAudioUseCase(
        playbackRepository: PlaybackRepository
    ): ReleaseUsbAudioUseCase = ReleaseUsbAudioUseCase(playbackRepository)

    /**
     * Provides [ResumePlaybackUseCase] backed by [PlaybackRepository].
     *
     * Resumes playback from the current paused position.
     */
    @Provides
    fun provideResumePlaybackUseCase(
        playbackRepository: PlaybackRepository
    ): ResumePlaybackUseCase = ResumePlaybackUseCase(playbackRepository)

    /**
     * Provides [SeekToPositionUseCase] backed by [PlaybackRepository].
     *
     * Seeks the active track to the specified position in milliseconds.
     */
    @Provides
    fun provideSeekToPositionUseCase(
        playbackRepository: PlaybackRepository
    ): SeekToPositionUseCase = SeekToPositionUseCase(playbackRepository)

    /**
     * Provides [SkipNextUseCase] backed by [PlaybackRepository].
     *
     * Advances the queue to the next track and begins playback.
     */
    @Provides
    fun provideSkipNextUseCase(
        playbackRepository: PlaybackRepository
    ): SkipNextUseCase = SkipNextUseCase(playbackRepository)

    /**
     * Provides [SkipPreviousUseCase] backed by [PlaybackRepository].
     *
     * Returns to the previous track in the queue and begins playback.
     */
    @Provides
    fun provideSkipPreviousUseCase(
        playbackRepository: PlaybackRepository
    ): SkipPreviousUseCase = SkipPreviousUseCase(playbackRepository)

    /**
     * Provides [SetRepeatModeUseCase] backed by [PlaybackRepository].
     *
     * Applies the requested repeat mode (off, one, all) to the active player session.
     */
    @Provides
    fun provideSetRepeatModeUseCase(
        playbackRepository: PlaybackRepository
    ): SetRepeatModeUseCase = SetRepeatModeUseCase(playbackRepository)

    /**
     * Provides [SetShuffleModeUseCase] backed by [PlaybackRepository].
     *
     * Enables or disables shuffle on the active player queue.
     */
    @Provides
    fun provideSetShuffleModeUseCase(
        playbackRepository: PlaybackRepository
    ): SetShuffleModeUseCase = SetShuffleModeUseCase(playbackRepository)

    /**
     * Provides [ObservePlaybackStateUseCase] backed by [PlaybackRepository].
     *
     * Returns a continuous [kotlinx.coroutines.flow.Flow] of [com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState]
     * snapshots reflecting the real-time state of the Media3 player.
     */
    @Provides
    fun provideObservePlaybackStateUseCase(
        playbackRepository: PlaybackRepository
    ): ObservePlaybackStateUseCase = ObservePlaybackStateUseCase(playbackRepository)

    /**
     * Provides [ObserveAudioTelemetryUseCase] backed by [AudioTelemetryRepository].
     *
     * Returns a continuous [kotlinx.coroutines.flow.Flow] of decoded-audio telemetry including
     * sample rate, bit depth, codec, and hardware offload state.
     */
    @Provides
    fun provideObserveAudioTelemetryUseCase(
        audioTelemetryRepository: AudioTelemetryRepository
    ): ObserveAudioTelemetryUseCase = ObserveAudioTelemetryUseCase(audioTelemetryRepository)

    /**
     * Provides [ObserveQueueStateUseCase] backed by [PlaybackRepository].
     *
     * Returns a continuous [kotlinx.coroutines.flow.Flow] of [com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState]
     * snapshots including the current queue ordering, repeat mode, and shuffle mode.
     */
    @Provides
    fun provideObserveQueueStateUseCase(
        playbackRepository: PlaybackRepository
    ): ObserveQueueStateUseCase = ObserveQueueStateUseCase(playbackRepository)

    /**
     * Provides [SavePlaybackStateUseCase] backed by the [PlaybackPersistenceRepository].
     */
    @Provides
    fun provideSavePlaybackStateUseCase(
        playbackPersistenceRepository: PlaybackPersistenceRepository
    ): SavePlaybackStateUseCase = SavePlaybackStateUseCase(playbackPersistenceRepository)

    /** Provides [ClearPlaybackStateUseCase] for deleting a restorable queue session. */
    @Provides
    fun provideClearPlaybackStateUseCase(
        playbackPersistenceRepository: PlaybackPersistenceRepository
    ): ClearPlaybackStateUseCase = ClearPlaybackStateUseCase(playbackPersistenceRepository)

    /**
     * Provides [RestorePlaybackStateUseCase] backed by the [PlaybackPersistenceRepository].
     */
    @Provides
    fun provideRestorePlaybackStateUseCase(
        playbackPersistenceRepository: PlaybackPersistenceRepository
    ): RestorePlaybackStateUseCase = RestorePlaybackStateUseCase(playbackPersistenceRepository)

    /**
     * Provides [GetArtistImageUseCase] backed by the [RemoteImageRepository].
     *
     * Results are cached in Room so network calls occur at most once per artist.
     */
    @Provides
    fun provideGetArtistImageUseCase(
        remoteImageRepository: RemoteImageRepository
    ): GetArtistImageUseCase = GetArtistImageUseCase(remoteImageRepository)

    /**
     * Provides [GetAlbumArtUseCase] backed by the [RemoteImageRepository].
     *
     * Results are cached in Room so network calls occur at most once per album.
     */
    @Provides
    fun provideGetAlbumArtUseCase(
        remoteImageRepository: RemoteImageRepository
    ): GetAlbumArtUseCase = GetAlbumArtUseCase(remoteImageRepository)

    /**
     * Provides [ToggleLikeSongUseCase] backed by [LikedSongsRepository].
     */
    @Provides
    fun provideToggleLikeSongUseCase(
        likedSongsRepository: LikedSongsRepository
    ): ToggleLikeSongUseCase = ToggleLikeSongUseCase(likedSongsRepository)

    /** Provides [SetTracksLikedUseCase] for atomic collection-level liked updates. */
    @Provides
    fun provideSetTracksLikedUseCase(
        likedSongsRepository: LikedSongsRepository
    ): SetTracksLikedUseCase = SetTracksLikedUseCase(likedSongsRepository)

    /**
     * Provides [ObserveLikedSongIdsUseCase] backed by [LikedSongsRepository].
     */
    @Provides
    fun provideObserveLikedSongIdsUseCase(
        likedSongsRepository: LikedSongsRepository
    ): ObserveLikedSongIdsUseCase = ObserveLikedSongIdsUseCase(likedSongsRepository)

    /**
     * Provides [RecordRecentlyPlayedUseCase] backed by [RecentlyPlayedRepository].
     */
    @Provides
    fun provideRecordRecentlyPlayedUseCase(
        recentlyPlayedRepository: RecentlyPlayedRepository
    ): RecordRecentlyPlayedUseCase = RecordRecentlyPlayedUseCase(recentlyPlayedRepository)

    /**
     * Provides [ObserveRecentlyPlayedUseCase] backed by [RecentlyPlayedRepository].
     */
    @Provides
    fun provideObserveRecentlyPlayedUseCase(
        recentlyPlayedRepository: RecentlyPlayedRepository
    ): ObserveRecentlyPlayedUseCase = ObserveRecentlyPlayedUseCase(recentlyPlayedRepository)

    /**
     * Provides [ObserveMostPlayedTracksUseCase] backed by [RecentlyPlayedRepository].
     */
    @Provides
    fun provideObserveMostPlayedTracksUseCase(
        recentlyPlayedRepository: RecentlyPlayedRepository
    ): ObserveMostPlayedTracksUseCase = ObserveMostPlayedTracksUseCase(recentlyPlayedRepository)

    /**
     * Provides [ObserveAudiophileEngineEnabledUseCase] backed by [SettingsRepository].
     */
    @Provides
    fun provideObserveAudiophileEngineEnabledUseCase(
        settingsRepository: SettingsRepository
    ): ObserveAudiophileEngineEnabledUseCase =
        ObserveAudiophileEngineEnabledUseCase(settingsRepository)

    /**
     * Provides [SetAudiophileEngineEnabledUseCase] backed by [SettingsRepository].
     */
    @Provides
    fun provideSetAudiophileEngineEnabledUseCase(
        settingsRepository: SettingsRepository
    ): SetAudiophileEngineEnabledUseCase =
        SetAudiophileEngineEnabledUseCase(settingsRepository)

    /**
     * Provides [ObserveUsbAudioStatusUseCase] backed by [SettingsRepository].
     */
    @Provides
    fun provideObserveUsbAudioStatusUseCase(
        settingsRepository: SettingsRepository
    ): ObserveUsbAudioStatusUseCase = ObserveUsbAudioStatusUseCase(settingsRepository)

    /**
     * Provides [RefreshUsbAudioDevicesUseCase] backed by [SettingsRepository].
     */
    @Provides
    fun provideRefreshUsbAudioDevicesUseCase(
        settingsRepository: SettingsRepository
    ): RefreshUsbAudioDevicesUseCase = RefreshUsbAudioDevicesUseCase(settingsRepository)

    /**
     * Provides [RequestUsbAudioPermissionUseCase] backed by [SettingsRepository].
     */
    @Provides
    fun provideRequestUsbAudioPermissionUseCase(
        settingsRepository: SettingsRepository
    ): RequestUsbAudioPermissionUseCase = RequestUsbAudioPermissionUseCase(settingsRepository)


    /**
     * Provides [GetLyricsUseCase] backed by [LyricsRepository].
     *
     * Lazily fetches synchronized or plain-text lyrics for the currently playing
     * track via the LRCLIB API, with local Room caching to avoid redundant calls.
     */
    @Provides
    fun provideGetLyricsUseCase(
        lyricsRepository: LyricsRepository
    ): GetLyricsUseCase = GetLyricsUseCase(lyricsRepository)

    /**
     * Provides [ObserveSueEnabledUseCase] backed by [SettingsRepository].
     *
     * Returns a continuous [kotlinx.coroutines.flow.Flow] emitting the persisted
     * SUE enabled preference, updating whenever the user toggles the setting.
     */
    @Provides
    fun provideObserveSueEnabledUseCase(
        settingsRepository: SettingsRepository
    ): ObserveSueEnabledUseCase =
        ObserveSueEnabledUseCase(settingsRepository)

    /** Provides the reactive task-removal queue policy for the Settings screen. */
    @Provides
    fun provideObserveClearQueueOnExitUseCase(
        settingsRepository: SettingsRepository
    ): ObserveClearQueueOnExitUseCase = ObserveClearQueueOnExitUseCase(settingsRepository)

    /** Provides the writer for the task-removal queue policy. */
    @Provides
    fun provideSetClearQueueOnExitUseCase(
        settingsRepository: SettingsRepository
    ): SetClearQueueOnExitUseCase = SetClearQueueOnExitUseCase(settingsRepository)

    /**
     * Provides [SetSueEnabledUseCase] backed by [SettingsRepository].
     *
     * Persists the user's SUE preference; the audiophile engine reads the
     * value on the next track load.
     */
    @Provides
    fun provideSetSueEnabledUseCase(
        settingsRepository: SettingsRepository
    ): SetSueEnabledUseCase =
        SetSueEnabledUseCase(settingsRepository)

    /**
     * Provides [ObserveHiResRemasterEnabledUseCase] backed by [SettingsRepository].
     *
     * Returns a continuous [kotlinx.coroutines.flow.Flow] emitting the persisted
     * Hi-Res Dynamic Remaster enabled preference, updating whenever the user
     * toggles the setting.
     */
    @Provides
    fun provideObserveHiResRemasterEnabledUseCase(
        settingsRepository: SettingsRepository
    ): ObserveHiResRemasterEnabledUseCase =
        ObserveHiResRemasterEnabledUseCase(settingsRepository)

    /**
     * Provides [SetHiResRemasterEnabledUseCase] backed by [SettingsRepository].
     *
     * Persists the user's Hi-Res Dynamic Remaster preference; the audiophile
     * engine reads the value on the next lossless track load.
     */
    @Provides
    fun provideSetHiResRemasterEnabledUseCase(
        settingsRepository: SettingsRepository
    ): SetHiResRemasterEnabledUseCase =
        SetHiResRemasterEnabledUseCase(settingsRepository)

}
