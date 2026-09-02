package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/** Releases exclusive USB audio ownership while preserving the current playback session. */
class ReleaseUsbAudioUseCase(
    private val playbackRepository: PlaybackRepository,
) {
    /**
     * Pauses playback and waits for the app-owned DAC interface to be closed.
     *
     * @return [Resource.Success] after teardown, or [Resource.Error] when the
     *   playback service cannot complete the release.
     */
    suspend operator fun invoke(): Resource<Unit> = playbackRepository.releaseUsbAudio()
}
