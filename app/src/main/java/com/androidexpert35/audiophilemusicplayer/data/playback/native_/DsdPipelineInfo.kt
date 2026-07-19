package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate

/**
 * Diagnostic snapshot of the DSD transport selected for the current track.
 *
 * Captures both the original source characteristics and the effective transport
 * negotiated by the output pipeline so telemetry can reveal native DSD,
 * full-rate DoP, or the explicit DSD256 → DSD128 DoP fallback.
 *
 * @property sourceFormat Human-readable DSD container label such as `"DSF"`, `"DSDIFF"`, or `"WavPack DSD"`.
 * @property sourceDsdRate Original one-bit DSD rate read from the source file.
 * @property outputMode Transport strategy selected for playback.
 * @property effectiveDsdRate Effective one-bit DSD rate reaching the sink. This may differ from [sourceDsdRate]
 *   when the app transparently decimates DSD256 to DSD128 for DoP.
 * @property dopPcmRate PCM carrier rate in Hertz used for DoP, or `null` when native DSD is active.
 */
data class DsdPipelineInfo(
    val sourceFormat: String,
    val sourceDsdRate: DsdRate,
    val outputMode: DsdOutputMode,
    val effectiveDsdRate: DsdRate,
    val dopPcmRate: Int? = null,
)

