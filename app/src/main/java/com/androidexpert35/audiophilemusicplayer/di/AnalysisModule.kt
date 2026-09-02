package com.androidexpert35.audiophilemusicplayer.di

import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.FFmpegStationarySampler
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.StationarySampler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the offline signal-measurement pass into the graph.
 *
 * Kept apart from `RepositoryModule` because what is bound here is not a repository: it
 * is the native measurement pass itself, behind an interface so the orchestrator's policy
 * can be exercised on the JVM without loading `audiophile_native`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AnalysisModule {

    /**
     * Binds the FFmpeg-backed measurement pass as the singleton [StationarySampler].
     *
     * @param impl The FFmpeg + libavfilter implementation.
     * @return The measurement pass seen by the rest of the graph.
     */
    @Binds
    @Singleton
    abstract fun bindStationarySampler(impl: FFmpegStationarySampler): StationarySampler
}
