package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueProfileResolver
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.isAlreadyNativeHiRes
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo

/**
 * Decides whether a full-file loudness pass over [format] could ever be acted on.
 *
 * A full decode is the most expensive background work in the app, so it is only spent on
 * audio whose result something downstream can read. That set is exactly the one the
 * Hi-Res Dynamic Remaster stage runs on, which is why this mirrors
 * `shouldUseHiResRemasterStage` (BitPerfectEnhancementPipeline.kt) rather than restating
 * its reasoning:
 *
 * - **Lossy sources** are routed to the SUE stage instead, which has no use for an
 *   integral loudness figure.
 * - **Sources already at native hi-res** (≥ 24-bit, or above 48 kHz) bypass the remaster
 *   stage unconditionally, whatever the user preference says.
 * - **DSD** never reaches the DSP stage at all on any bit-perfect transport.
 *
 * The user's Hi-Res toggle is deliberately *not* part of this. The toggle says what to do
 * with a track right now; eligibility says whether a measurement of it would ever be
 * read. Gating a cache on a runtime preference would mean the measurement is missing at
 * precisely the moment the preference is switched on.
 *
 * Note the timing: this takes a decoded [AudioFormatInfo], so it can only be evaluated
 * after the decoder is open. The scan-time codec is not enough — it does not carry bit
 * depth, and it is not always right about a `.dsf`/`.dff` file.
 *
 * @param format Decoded shape reported by the decoder for the source under consideration.
 * @return `true` when an integral measurement of this source is worth taking.
 */
internal fun isEligibleForIntegralAnalysis(format: AudioFormatInfo): Boolean {
    if (format.isDsd || format.isResampledDsd) return false
    val resolution = SueProfileResolver.resolve(format)
    return !resolution.isLossySource && !format.isAlreadyNativeHiRes()
}
