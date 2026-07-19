package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioPathStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.TelemetryStatus
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileSecondary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold


/** Returns whether any app-owned DSP stage is actively transforming samples. */
internal fun hasActiveProcessing(telemetry: AudioTelemetry): Boolean =
    telemetry.sueStatus?.isActive == true || telemetry.sueStatus?.isHiResRemasterActive == true

/** Resolves the display state for the lossy-restoration stage. */
internal fun resolveLossyRestorerState(sueStatus: SueStatus?): ProcessingStageState = when {
    sueStatus == null || !sueStatus.isEnabled -> ProcessingStageState.OFF
    sueStatus.isActive -> ProcessingStageState.PROCESSING
    !sueStatus.isProvisioned -> ProcessingStageState.UNAVAILABLE
    sueStatus.intensityProfile.equals("BYPASS", ignoreCase = true) -> ProcessingStageState.BYPASSED
    !sueStatus.isLossy -> ProcessingStageState.ARMED
    else -> ProcessingStageState.ARMED
}

/** Resolves the display state for the Hi-Res Dynamic Remaster stage. */
internal fun resolveHiResRemasterState(sueStatus: SueStatus?): ProcessingStageState = when {
    sueStatus == null || !sueStatus.isHiResRemasterEnabled -> ProcessingStageState.OFF
    sueStatus.isHiResRemasterActive -> ProcessingStageState.PROCESSING
    !sueStatus.isProvisioned -> ProcessingStageState.UNAVAILABLE
    sueStatus.isLossy -> ProcessingStageState.ARMED
    else -> ProcessingStageState.BYPASSED
}

/** Resolves bit-perfect status, including the direct USB bypass override. */
internal fun resolvedBitPerfectStatus(telemetry: AudioTelemetry): TelemetryStatus =
    if (telemetry.bitPerfectDiagnostics?.isDirectUsbBypass == true) {
        TelemetryStatus.ACTIVE
    } else {
        telemetry.isBitPerfect
    }

/** Maps a processing state to the color used by stage cards and status chips. */
internal fun processingStateColor(state: ProcessingStageState, activeColor: Color): Color = when (state) {
    ProcessingStageState.OFF -> Color.White.copy(alpha = 0.34f)
    ProcessingStageState.ARMED -> AudiophileSecondary
    ProcessingStageState.BYPASSED -> Color.White.copy(alpha = 0.50f)
    ProcessingStageState.UNAVAILABLE -> AudiophileTertiary
    ProcessingStageState.PROCESSING -> activeColor
}

/** Returns a localized short label for a processing stage state. */
@Composable
internal fun processingStateLabel(state: ProcessingStageState): String = stringResource(
    when (state) {
        ProcessingStageState.OFF -> R.string.telemetry_processing_off
        ProcessingStageState.ARMED -> R.string.telemetry_processing_armed
        ProcessingStageState.BYPASSED -> R.string.telemetry_processing_bypassed
        ProcessingStageState.UNAVAILABLE -> R.string.telemetry_processing_unavailable
        ProcessingStageState.PROCESSING -> R.string.telemetry_processing_active
    }
)

/** Returns a localized summary for the currently active DSP stage. */
@Composable
internal fun activeProcessingLabel(telemetry: AudioTelemetry): String = when {
    telemetry.sueStatus?.isActive == true -> stringResource(R.string.telemetry_processing_lossy_restorer)
    telemetry.sueStatus?.isHiResRemasterActive == true -> stringResource(R.string.telemetry_processing_hires_remaster)
    else -> stringResource(R.string.telemetry_pipeline_passthrough)
}

/** Returns a localized supporting explanation for the Lossy Audio Restorer state. */
@Composable
internal fun lossyRestorerSupportingText(sueStatus: SueStatus?): String = when {
    sueStatus?.isActive == true -> stringResource(R.string.telemetry_lossy_restorer_processing)
    sueStatus?.isEnabled == true && !sueStatus.isLossy -> stringResource(R.string.telemetry_lossy_restorer_armed_lossless)
    sueStatus?.isEnabled == true && sueStatus.intensityProfile.equals("BYPASS", ignoreCase = true) ->
        stringResource(R.string.telemetry_lossy_restorer_bypassed_profile)
    sueStatus?.failureReason?.isNotBlank() == true -> sueStatus.failureReason
    else -> stringResource(R.string.telemetry_lossy_restorer_off)
}

/** Returns a localized supporting explanation for the Hi-Res Dynamic Remaster state. */
@Composable
internal fun hiResRemasterSupportingText(sueStatus: SueStatus?): String = when {
    sueStatus?.isHiResRemasterActive == true -> stringResource(R.string.telemetry_hires_remaster_processing)
    sueStatus?.isHiResRemasterEnabled == true && sueStatus.isLossy -> stringResource(R.string.telemetry_hires_remaster_armed_lossy)
    sueStatus?.isHiResRemasterEnabled == true -> stringResource(R.string.telemetry_hires_remaster_armed_hires)
    else -> stringResource(R.string.telemetry_hires_remaster_off)
}

/** Returns the localized output transport family for the pipeline summary. */
@Composable
internal fun outputPipelineLabel(streamInfo: OutputStreamInfo): String = when (streamInfo) {
    is OutputStreamInfo.Dsd -> stringResource(R.string.telemetry_pipeline_dsd_transport)
    is OutputStreamInfo.Pcm -> stringResource(R.string.telemetry_pipeline_pcm_output)
    OutputStreamInfo.Unknown -> stringResource(R.string.telemetry_unavailable)
}

/** Returns the compact output format shape for the pipeline summary. */
internal fun outputPipelineShape(streamInfo: OutputStreamInfo): String = when (streamInfo) {
    is OutputStreamInfo.Dsd -> streamInfo.pcmOutput?.let { pcm ->
        formatPcmShape(pcm.bitDepth, pcm.sampleRateHz)
    } ?: formatDsdRate(streamInfo.sourceDsdRate)
    is OutputStreamInfo.Pcm -> formatPcmShape(streamInfo.bitDepth, streamInfo.sampleRateHz)
    OutputStreamInfo.Unknown -> "—"
}

/** Returns the accent color for the current output path. */
internal fun outputAccentColor(telemetry: AudioTelemetry): Color = when {
    telemetry.bitPerfectDiagnostics?.isDirectUsbBypass == true -> HiResGold
    telemetry.isBitPerfect == TelemetryStatus.ACTIVE -> HiResGold
    telemetry.isDirectPlayback == TelemetryStatus.ACTIVE -> AudiophilePrimary
    else -> AudiophileSecondary
}

/** Maps an [AudioPathStatus] to the label used by output and diagnostics cards. */
@Composable
internal fun AudioPathStatus.toDisplayLabel(): String = when (this) {
    AudioPathStatus.DIRECT_BIT_PERFECT -> stringResource(R.string.telemetry_path_status_bit_perfect)
    AudioPathStatus.DIRECT_SUPPORTED -> stringResource(R.string.telemetry_path_status_direct)
    AudioPathStatus.OEM_WARNING -> stringResource(R.string.telemetry_path_status_oem_warning)
    AudioPathStatus.RESAMPLED -> stringResource(R.string.telemetry_path_status_resampled)
    AudioPathStatus.UNKNOWN -> stringResource(R.string.telemetry_path_status_unknown)
}

