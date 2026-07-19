package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine] strategy backed by the in-process FFmpeg + AudioTrack
 * pipeline implemented in [BitPerfectPlaybackEngine].
 *
 * every call forwards to the underlying engine and
 * every `StateFlow` is re-exposed directly. The only behaviour contributed
 * by this layer is adapting [BitPerfectPlaybackEngine]'s public API to the
 * [AudioPlayerEngine] contract so [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager] can uniformly observe
 * every strategy through the same interface.
 *
 * @property core Shared bit-perfect playback core, owning the audio thread,
 *   native decoder, and direct AudioTrack sink.
 */
@Singleton
class AudiophileEngine @Inject internal constructor(
    private val core: BitPerfectPlaybackEngine,
) : AudioPlayerEngine {

    override val engineType: EngineType = EngineType.AUDIOPHILE

    override val state = core.state
    override val positionMs = core.positionMs
    override val durationMs = core.durationMs
    override val currentUri = core.currentUri
    override val currentFormat = core.currentFormat
    override val pathReport = core.pathReport

    override val lastErrorMessage: String?
        get() = core.lastErrorMessage

    override fun load(uri: String, startPositionMs: Long, autoPlay: Boolean) {
        if (autoPlay) core.loadAndPlay(uri, startPositionMs)
        else core.loadTrack(uri, startPositionMs)
    }

    override fun enqueueNext(uri: String?) {
        core.enqueueNext(uri)
    }

    override fun play() { core.play() }
    override fun pause() { core.pause() }
    override fun stop() { core.stop() }
    override fun seekTo(positionMs: Long) { core.seekTo(positionMs) }
    override fun release() { core.release() }

    /**
     * Synchronously stops playback and releases the claimed USB DAC interface
     * so the standard engine can take over routing immediately.
     */
    suspend fun stopAndReleaseOutput() {
        core.stopAndReleaseOutput()
    }

    /**
     * Immediately releases the USB output sink when audio focus is permanently
     * lost, so other apps can use the DAC without waiting for the idle timeout.
     *
     * The decoder and playhead state are preserved; [play] transparently
     * rebuilds the sink when the user returns.
     *
     * @see BitPerfectPlaybackEngine.releaseUsbSinkNow
     */
    fun releaseUsbSinkNow() {
        core.releaseUsbSinkNow()
    }


    /**
     * Reloads the current track from the current playhead position so that
     * newly-changed settings (e.g. the SoX resampler toggle) take effect
     * immediately without waiting for the next manual track change.
     *
     * @see BitPerfectPlaybackEngine.reloadWithCurrentSettings
     */
    fun reloadWithCurrentSettings() {
        core.reloadWithCurrentSettings()
    }

    /**
     * Returns `true` when the current audiophile track already suppresses the
     * standalone SoX stage because the internal enhancement path owns any
     * necessary resampling.
     */
    fun isStandaloneSoxSuppressedForCurrentTrack(): Boolean {
        return core.isStandaloneSoxSuppressedForCurrentTrack()
    }

    override fun setListener(listener: AudioPlayerEngine.Listener?) {
        core.setListener(listener)
    }
}
