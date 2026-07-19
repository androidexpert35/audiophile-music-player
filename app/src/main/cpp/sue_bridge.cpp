// ─────────────────────────────────────────────────────────────────────────────
// sue_bridge.cpp
//
// JNI implementation for SueBridge.kt — the Sonic Upscaling Enhancer (SUE).
//
// SUE is an additive DSP stage inserted BEFORE the libsoxr resampler in the
// Audio File Mode (FFmpeg decoder) pipeline:
//
//   FFmpeg decode (PCM) → [SUE] → [libsoxr resampler] → AudioTrack output
//
// Architecture:
//   - Only active for lossy-compressed sources (MP3, AAC, OGG, Opus, WMA).
//   - Bypass is zero-cost: when isLossy=false the Kotlin layer never calls
//     nativeCreate, so no filter graph is allocated.
//   - Implements an audiophile DSP pipeline using FFmpeg libavfilter:
//       1. Oversampling to ≥2× the target carrier when target ≤ 48 kHz, so the
//          exciter's distortion products never fold back across Nyquist
//       2. Harmonic excitation (aexciter) tuned near the codec's expected
//          low-pass cutoff (LAME: ~16.4 kHz @96 kbps … ~20.5 kHz @320 kbps)
//       3. Gentle high-band air contouring, biased toward the near-cutoff band
//       4. Mild stereo widening — only for AGGRESSIVE/MODERATE profiles where
//          intensity-stereo image collapse is plausible (≤128 kbps); M/S joint
//          stereo at higher bitrates preserves the image, so widening there
//          would overshoot the lossless reference. Always disabled for
//          AAC-HE v2 / Parametric Stereo.
//       5. Soft apodizing low-pass + true-peak limiter (at the oversampled rate)
//       6. Downsample back to the target carrier (soxr VHQ) when oversampled
//       7. Adaptive intensity (resolved via codec tier × bitrate matrix)
//
//   Loudness normalization (loudnorm / dynaudnorm) was removed — SUE is
//   strictly an additive harmonic-restoration stage; volume is left to the
//   OS / user.
//
// Adaptive intensity resolution:
//   - Codec efficiency tier maps codecs to TIER_LOW / TIER_MID / TIER_HIGH / TIER_ULTRA
//   - Bitrate range selects one of: AGGRESSIVE, MODERATE, LIGHT, SUBTLE, or BYPASS
//   - AAC-HE special handling: Layer 2 skipped, blend=0.5, to avoid double-
//     processing the SBR spectral band reconstruction already in the stream.
//
// Thread safety:
//   All calls on a given handle MUST originate from the same audio thread —
//   BitPerfectPlaybackEngine's THREAD_PRIORITY_AUDIO HandlerThread.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <memory>
#include <new>
#include <string>

#define SUE_TAG   "SUE"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, SUE_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  SUE_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, SUE_TAG, __VA_ARGS__)

static thread_local std::string g_last_init_error;

static void set_last_init_error(const std::string &message) {
    g_last_init_error = message;
}

static void clear_last_init_error() {
    g_last_init_error.clear();
}

extern "C" {
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libavutil/frame.h>
#include <libavutil/avstring.h>
}

// ─── Android AudioFormat constants (must match SueBridge.kt) ─────────────────
static constexpr int ENCODING_PCM_16BIT = 2;
static constexpr int ENCODING_PCM_FLOAT = 4;
static constexpr int ENCODING_PCM_32BIT = 22;

// ─── Codec tier constants (must match SueCodecTier.kt) ───────────────────────
static constexpr int TIER_LOW   = 0;   // MP3, WMA-lossy — highest artifact rate
static constexpr int TIER_MID   = 1;   // AAC-LC, OGG Vorbis
static constexpr int TIER_HIGH  = 2;   // AAC-HE v1/v2 (SBR/PS)
static constexpr int TIER_ULTRA = 3;   // Opus — near-transparent

// Default pre-expansion headroom applied to lossless tracks when no
// REPLAYGAIN_TRACK_PEAK tag is present.  −3.0 dB assumes the worst case
// (peak = 1.0) and creates exactly the room the Hi-Res Remaster expansion
// stage can add back (+2.5 dB at full scale, peaks land at −0.5 dBFS).
static constexpr float HIRES_REMASTER_DEFAULT_GAIN_DB = -3.0f;

// Bounds for the headroom gain accepted from the Kotlin layer.  The gain is a
// pure pre-expansion attenuation: never positive, and never below −6 dB even
// for files whose TRACK_PEAK reports inter-sample clipping (> 1.0).
static constexpr float HIRES_REMASTER_MIN_GAIN_DB = -6.0f;
static constexpr float HIRES_REMASTER_MAX_GAIN_DB = 0.0f;

// ─── Intensity profiles ───────────────────────────────────────────────────────
typedef enum {
    PROFILE_AGGRESSIVE = 0,
    PROFILE_MODERATE   = 1,
    PROFILE_LIGHT      = 2,
    PROFILE_SUBTLE     = 3,
    PROFILE_BYPASS     = 4,
} SueIntensityProfile;

// DSP parameter set for a resolved intensity profile.
struct SueDspParams {
    float   exciter_amount;
    float   exciter_drive;
    int     exciter_freq;
    float   air_gain_10k;
    float   air_gain_14k;
    bool    skip_layer2;    // true for AAC-HE (SBR already applied)
    float   exciter_blend;  // 0.0 → even harmonics only; 0.5 for AAC-HE
    bool    enable_stereo_widening;
};

static constexpr int SUE_APODIZING_LOWPASS_HZ = 19500;

static const char *profile_name(SueIntensityProfile profile);

// Codec-specific override flags resolved once per track load.
static constexpr int SUE_FLAG_SKIP_LAYER2_EQ              = 1 << 0;
static constexpr int SUE_FLAG_AAC_HE_ODD_HARMONICS_BLEND  = 1 << 1;
static constexpr int SUE_FLAG_DISABLE_MID_SIDE_WIDENING   = 1 << 2;

// ─── Utility: sample format from Android encoding constant ───────────────────
static AVSampleFormat encoding_to_avsamplefmt(int encoding) {
    switch (encoding) {
        case ENCODING_PCM_16BIT: return AV_SAMPLE_FMT_S16;
        case ENCODING_PCM_32BIT: return AV_SAMPLE_FMT_S32;
        case ENCODING_PCM_FLOAT: return AV_SAMPLE_FMT_FLT;
        default:
            ALOGW("encoding_to_avsamplefmt: unknown encoding %d — assuming FLT", encoding);
            return AV_SAMPLE_FMT_FLT;
    }
}

// ─── Profile resolution logic ─────────────────────────────────────────────────

// Bidimensional matrix: codec_tier × bitrate_range → SueIntensityProfile.
// Columns: ≤96, 97-128, 129-192, 193-256, ≥257
//
// Calibrated against the measured behaviour of real encoders:
//   - LAME MP3 low-pass defaults: ~16.4 kHz @96, ~17.2 kHz @128, ~18.6 kHz @192,
//     ~19.7 kHz @256, ~20.5 kHz @320 — at ≥257 kbps the source keeps essentially
//     the full audible band, so any additive DSP moves it AWAY from the lossless
//     reference → BYPASS.
//   - AAC-HE (SBR) already synthesises the high band in the decoder; excitation
//     on top of the reconstruction accentuates the metallic SBR artifact →
//     SUBTLE at the bitrates where HE profiles are actually used, BYPASS above.
//   - Opus is fullband (20 kHz) from ~64-96 kbps and effectively transparent at
//     128 kbps in published listening tests; its residual artifacts (pre-echo,
//     tonal noise) are not repairable by excitation → SUBTLE only ≤96 kbps.
static constexpr SueIntensityProfile PROFILE_MATRIX[4][5] = {
    // TIER_LOW  (MP3, WMA)
    { PROFILE_AGGRESSIVE, PROFILE_AGGRESSIVE, PROFILE_LIGHT,    PROFILE_SUBTLE, PROFILE_BYPASS },
    // TIER_MID  (AAC-LC, Vorbis)
    { PROFILE_AGGRESSIVE, PROFILE_MODERATE,   PROFILE_MODERATE, PROFILE_SUBTLE, PROFILE_BYPASS },
    // TIER_HIGH (AAC-HE)
    { PROFILE_SUBTLE,     PROFILE_SUBTLE,     PROFILE_SUBTLE,   PROFILE_BYPASS, PROFILE_BYPASS },
    // TIER_ULTRA (Opus)
    { PROFILE_SUBTLE,     PROFILE_BYPASS,     PROFILE_BYPASS,   PROFILE_BYPASS, PROFILE_BYPASS },
};

// Maps a bitrate in kbps to a column index (0-4) for the profile matrix.
static int bitrate_to_column(int bitrateKbps) {
    if (bitrateKbps <=   0) return 1;   // unknown → treat as ≤128 (conservative)
    if (bitrateKbps <=  96) return 0;
    if (bitrateKbps <= 128) return 1;
    if (bitrateKbps <= 192) return 2;
    if (bitrateKbps <= 256) return 3;
    return 4;
}

// Resolves the final SueIntensityProfile for the given codec tier and bitrate.
static SueIntensityProfile resolve_profile(int codecTier, int bitrateKbps, const char *filename) {
    if (codecTier < TIER_LOW || codecTier > TIER_ULTRA) {
        ALOGW("resolve_profile: unknown codec tier %d — defaulting to TIER_MID", codecTier);
        codecTier = TIER_MID;
    }

    int col = bitrate_to_column(bitrateKbps);

    // Bitrate metadata unavailable: tier-aware fallback (never AGGRESSIVE).
    // MODERATE is only safe for the inefficient codecs; an Opus or AAC-HE file
    // with missing bitrate metadata is still near-transparent / SBR-driven and
    // must not receive MP3-grade excitation.
    if (bitrateKbps <= 0) {
        const SueIntensityProfile fallback =
            (codecTier == TIER_HIGH || codecTier == TIER_ULTRA)
                ? PROFILE_SUBTLE
                : PROFILE_MODERATE;
        ALOGW("SUE: bitrate metadata unavailable for '%s', tier=%d → fallback profile %s",
              filename ? filename : "<unknown>", codecTier, profile_name(fallback));
        return fallback;
    }

    return PROFILE_MATRIX[codecTier][col];
}

// Fills a SueDspParams structure for the resolved intensity profile.
// The isAacHe flag enables the AAC-HE SBR-aware override path.
static int sue_codec_special_flags(bool isAacHe, bool isAacHeV2) {
    if (!isAacHe) return 0;

    int flags = SUE_FLAG_SKIP_LAYER2_EQ | SUE_FLAG_AAC_HE_ODD_HARMONICS_BLEND;
    if (isAacHeV2) {
        flags |= SUE_FLAG_DISABLE_MID_SIDE_WIDENING;
    }
    return flags;
}

static SueDspParams profile_to_dsp_params(SueIntensityProfile profile, int specialFlags) {
    SueDspParams p{};
    p.skip_layer2    = false;
    p.exciter_blend  = 0.0f;
    p.enable_stereo_widening = (specialFlags & SUE_FLAG_DISABLE_MID_SIDE_WIDENING) == 0;

    // exciter_freq is anchored to HALF the codec's expected low-pass cutoff so
    // the exciter's 2nd harmonics land in the band the encoder actually removed
    // (LAME cutoffs: ~16.4 kHz @96 kbps, ~17.2 kHz @128, ~18.6 kHz @192,
    // ~19.7 kHz @256).  Excitation from lower fundamentals (the old 6.5/7.5 kHz)
    // distorted spectrum the file still carries intact, colouring the sound away
    // from the lossless reference instead of toward it.
    //
    // air_gain: the 10.5 kHz band is where lossy quantisation noise / "birdies"
    // are strongest — boosting it makes artifacts MORE audible, so the emphasis
    // is now biased toward the 14.5 kHz near-cutoff band and reduced overall.
    switch (profile) {
        case PROFILE_AGGRESSIVE:
            // ≤96 kbps → cutoff ~15.5-16.4 kHz → 2nd harmonics from 16 kHz.
            p.exciter_amount = 2.2f; p.exciter_drive = 9.0f; p.exciter_freq = 8000;
            p.air_gain_10k = 0.50f; p.air_gain_14k = 0.70f;
            break;
        case PROFILE_MODERATE:
            // 97-128 kbps → cutoff ~17.2 kHz → 2nd harmonics from 17 kHz.
            p.exciter_amount = 1.8f; p.exciter_drive = 8.5f; p.exciter_freq = 8500;
            p.air_gain_10k = 0.40f; p.air_gain_14k = 0.60f;
            break;
        case PROFILE_LIGHT:
            // 129-192 kbps → cutoff ~18.6 kHz; only the topmost band is missing.
            p.exciter_amount = 1.0f; p.exciter_drive = 6.5f; p.exciter_freq = 10000;
            p.air_gain_10k = 0.30f; p.air_gain_14k = 0.35f;
            // M/S joint stereo at this bitrate preserves the image — widening
            // would overshoot the lossless reference.
            p.enable_stereo_widening = false;
            break;
        case PROFILE_SUBTLE:
            // 193-256 kbps → cutoff ~19.7 kHz; near-transparent source.
            p.exciter_amount = 0.5f; p.exciter_drive = 5.0f; p.exciter_freq = 12000;
            p.air_gain_10k = 0.15f; p.air_gain_14k = 0.15f;
            p.enable_stereo_widening = false;
            break;
        case PROFILE_BYPASS:
            // Caller should check profile == BYPASS before proceeding.
            break;
    }

    // AAC-HE SBR-aware special handling:
    // The encoder already applied Spectral Band Replication during encoding.
    // Skipping Layer 2 (Spectral EQ) avoids double-boosting the reconstructed
    // high-frequency content. Using blend=0.5 introduces a small proportion of
    // odd harmonics to complement — not duplicate — the SBR reconstruction.
    if ((specialFlags & SUE_FLAG_SKIP_LAYER2_EQ) != 0) {
        p.skip_layer2 = true;
    }
    if ((specialFlags & SUE_FLAG_AAC_HE_ODD_HARMONICS_BLEND) != 0) {
        p.exciter_blend = 0.5f;
    }

    return p;
}

// ─── Profile name for logging ─────────────────────────────────────────────────
static const char *profile_name(SueIntensityProfile profile) {
    switch (profile) {
        case PROFILE_AGGRESSIVE: return "AGGRESSIVE";
        case PROFILE_MODERATE:   return "MODERATE";
        case PROFILE_LIGHT:      return "LIGHT";
        case PROFILE_SUBTLE:     return "SUBTLE";
        case PROFILE_BYPASS:     return "BYPASS";
        default:                 return "UNKNOWN";
    }
}

// ─── Hi-Res Dynamic Remaster filter chain builder ────────────────────────────
//
// Builds the lavfi filter chain for the lossless Hi-Res Dynamic Remaster path.
//
// Design goal: move a CD-quality master toward what its hi-res remaster would
// sound like.  Real hi-res remasters differ from loudness-war CD masters mainly
// by having MORE dynamic range, so the dynamics stage below performs a *gentle
// upward expansion* of the transient region — never compression.  (The previous
// curve -80/-80|-40/-40|-20/-16|0/-2 had slope 0.7 in the top 20 dB, i.e. a
// 1.43:1 compressor that squashed crest factor by ~5 dB — the exact opposite
// of the feature's intent.)
//
// DSP stage order — Gain → Expansion → Upsampling → True-Peak Limit → Float
// ──────────────────────────────────────────────────────────────────────────────
//   Stage 1 — volume=<headroomGainDb>dB
//     Peak-derived pre-expansion headroom computed from REPLAYGAIN_TRACK_PEAK
//     by ffmpeg_bridge's extract_replaygain_db and passed in via nativeCreate.
//     Formula: gain = clamp(headroom_db − 3.0, −6.0, 0.0)
//       where headroom_db = −20 × log₁₀(track_peak).
//     This creates exactly the ~3 dB of room the expansion needs on
//     hot-mastered files (peak ≈ 1.0 → −3 dB) and applies nothing on files
//     that already have ≥3 dB of headroom.  Falls back to −3.0 dB (assume
//     peak = 1.0) when no tag is present.  REPLAYGAIN_TRACK_GAIN is
//     intentionally not used — it would lower the output by 8–12 dB on
//     typical hot-mastered CD sources.
//
//   Stage 2 — compand (applied at the NATIVE sample rate)
//     points=-80/-80|-30/-30|-3/-0.5|0/-0.5
//       Unity below −30 dB (noise floor and ambience untouched); slope ≈1.09
//       (1:1.09 upward expansion) from −30 to −3 dB, so transients gain up to
//       ~2.5 dB and full-scale peaks land at −0.5 dBFS after the −3 dB
//       pre-gain; flat above −3 dB as a safety ceiling.
//     attacks=0.01 (10 ms) — the detector follows transients quickly enough
//       for them to receive the expansion boost.
//     decays=0.3 (300 ms) — slow release; gain moves ≤2.5 dB over ≥300 ms,
//       which is below the audibility threshold for pumping (the previous
//       80 ms decay modulated gain at bass-note rate).
//     soft-knee=4 — rounds the joints so the slope changes are inaudible.
//
//   Stage 3 — aresample=osr=<2×source>:resampler=soxr:precision=33  [optional]
//     Only inserted when inputSampleRateHz ≤ 48 000 Hz.
//       44 100 Hz  →  88 200 Hz  (44.1 kHz clock family)
//       48 000 Hz  →  96 000 Hz  (48 kHz clock family)
//       > 48 000 Hz →  stage omitted (already oversampled)
//     Placed AFTER compand so the expander operates with native resolution,
//     and BEFORE alimiter so the limiter sees the inter-sample peaks that
//     SoXR's sinc reconstruction filter generates during the ×2 step.
//
//   Stage 4 — alimiter=limit=0.95:attack=5:release=50  (True-Peak guard)
//     Placed AFTER aresample so it operates at the oversampled rate and can
//     catch inter-sample peaks (ISPs) that only materialise between native
//     samples during SoXR sinc interpolation.  attack/release use FFmpeg's
//     defaults (5 ms lookahead, 50 ms release): the previous 0.1 ms/10 ms made
//     the limiter act as a clipper and modulate gain within a single bass
//     cycle (a 50 Hz period is 20 ms), producing intermodulation distortion.
//
//   Stage 5 — aformat=sample_fmts=flt
//     Packed 32-bit float for zero-copy delivery to the AudioTrack sink.
//     No dithering: TPDF dither into float32 is a no-op (widening to a
//     larger format produces no quantisation error to shape).
//
static bool build_hires_remaster_chain(char *buf, size_t bufSize,
                                       int inputSampleRateHz, float replayGainDb) {
    // Build the optional upsampling stage as a comma-prefixed fragment so it
    // slots cleanly between compand and alimiter regardless of whether it's
    // present.  When the source is already above 48 000 Hz the string is empty.
    char upsample_stage[96] = "";
    if (inputSampleRateHz > 0 && inputSampleRateHz <= 48000) {
        const int targetRate = inputSampleRateHz * 2;
        snprintf(upsample_stage, sizeof(upsample_stage),
                 ",aresample=osr=%d:resampler=soxr:precision=33", targetRate);
    }

    // Correct order: volume → compand(native) → [upsample] → alimiter(True-Peak) → float.
    int written = snprintf(buf, bufSize,
        "volume=%.2fdB"                                                        // Stage 1: pre-expansion headroom
        ",compand=attacks=0.01:decays=0.3:soft-knee=4"
        ":points=-80/-80|-30/-30|-3/-0.5|0/-0.5"                               // Stage 2: gentle upward expansion
        "%s"                                                                   // Stage 3: optional ×2 upsampling
        ",alimiter=limit=0.95:attack=5:release=50:level=0"                     // Stage 4: True-Peak guard post-upsample
        ",aformat=sample_fmts=flt",                                            // Stage 5: packed float32 output
        replayGainDb,
        upsample_stage);
    return (written > 0 && static_cast<size_t>(written) < bufSize);
}

static bool build_force48k_resample_chain(char *buf, size_t bufSize,
                                          int targetSampleRateHz) {
    int written = snprintf(
        buf,
        bufSize,
        "aresample=osr=%d:resampler=soxr:precision=33:cutoff=0.91:dither_method=triangular_hp"
        ",aformat=sample_fmts=flt",
        targetSampleRateHz);
    return (written > 0 && static_cast<size_t>(written) < bufSize);
}

// ─── Resampler offset correction ─────────────────────────────────────────────
//
// Applies a one-step downgrade when a high-quality downstream resampler
// (libsoxr CHQ) is active.  Upsampling with high-quality interpolation
// slightly accentuates high-frequency content already synthesised by the
// SUE exciter, making the combined effect harsher than intended.  Stepping
// down one profile level compensates for that accentuation.
//
// The floor is SUBTLE — no path leads to BYPASS via offset so no active
// filter graph is silently disabled by this correction.
//
// Applied AFTER matrix resolution — not by modifying the matrix itself.
static SueIntensityProfile sue_apply_resampler_offset(SueIntensityProfile profile) {
    if (profile == PROFILE_AGGRESSIVE) return PROFILE_MODERATE;
    if (profile == PROFILE_MODERATE) return PROFILE_LIGHT;
    if (profile == PROFILE_LIGHT) return PROFILE_SUBTLE;
    return profile;
}

// ─── Filter graph string builder ──────────────────────────────────────────────

// Builds the lavfi filter graph description string based on the resolved DSP
// parameters. The string is assembled in the caller's buf of size bufSize.
// The integrated libsoxr VHQ backend (resampler=soxr:precision=33) is always
// used for Stage 0 upsampling — no swresample fallback is present.
static bool build_filter_chain(
    char *buf, size_t bufSize,
    const SueDspParams &p,
    SueIntensityProfile profile,
    int inputSampleRateHz,
    int targetSampleRateHz,
    int channelCount)
{
    if (profile == PROFILE_BYPASS) {
        // Bypass: null passthrough — the Kotlin side screens for BYPASS and
        // creates no handle; this path is a safety net.
        snprintf(buf, bufSize, "anull");
        return true;
    }

    // Stage 0 — Oversampled working rate for the nonlinear section.
    // aexciter has no internal oversampling: at a 44.1/48 kHz working rate its
    // distortion products above Nyquist fold back as inharmonic aliasing that
    // the gentle 1-pole 19.5 kHz lowpass cannot remove.  The chain therefore
    // always runs the exciter at ≥2× when the target carrier is ≤48 kHz and
    // downsamples back to the target in Stage 5.  Above 48 kHz the target rate
    // already provides ample Nyquist headroom.
    // The integrated libsoxr VHQ backend (resampler=soxr:precision=33) is used
    // unconditionally — it provides maximum FIR precision even for integer ratios
    // (e.g. 44100→48000) and eliminates any systematic phase error at irrational
    // ratios compared to the default swresample sinc engine.
    const int workRateHz = (targetSampleRateHz <= 48000)
        ? targetSampleRateHz * 2
        : targetSampleRateHz;
    char stage0[96] = "";
    if (inputSampleRateHz != workRateHz) {
        snprintf(stage0, sizeof(stage0),
                 "aresample=osr=%d:resampler=soxr:precision=33,",
                 workRateHz);
    }

    // Stage 1 — Harmonic Excitation (aexciter).
    // amount, drive, and freq scale with profile intensity; higher intensity
    // starts excitation lower in the spectrum where codec damage is more severe
    // at low bitrates.
    // *** ORDERING CONTRACT: aexciter is ALWAYS Stage 1.
    //     stereotools (Stage 3) is intentionally placed *after* aexciter so it
    //     widens the image of the already-enhanced harmonics, not of the dry
    //     input signal.  Swapping these two stages would reduce perceived width.
    char stage1[256];
    snprintf(stage1, sizeof(stage1),
             "aexciter=level_in=1.0:level_out=1.0:amount=%.2f:drive=%.2f:blend=%.2f:freq=%d",
             p.exciter_amount, p.exciter_drive, p.exciter_blend, p.exciter_freq);

    // Stage 2 — Conservative high-band air contour.
    // A true multiband upward expander via mcompand would be ideal here, but the
    // lavfi string becomes significantly more fragile across FFmpeg builds.
    // Instead, use two very wide, low-gain peaking filters above 10 kHz so the
    // added air stays smooth and lifts the noise floor far less than the old
    // narrow 12 kHz / 16 kHz peaking EQ pair.
    char stage2[256] = "";
    if (!p.skip_layer2) {
        snprintf(stage2, sizeof(stage2),
                 ",equalizer=f=10500:width_type=o:width=4.0:g=%.2f"
                 ",equalizer=f=14500:width_type=o:width=3.0:g=%.2f",
                 p.air_gain_10k, p.air_gain_14k);
    }

    // Stage 3 — Stereo widening to counter intensity-stereo image collapse.
    // Only AGGRESSIVE/MODERATE profiles enable this (≤128 kbps, where intensity
    // stereo is plausible): M/S joint stereo at higher bitrates preserves the
    // image essentially losslessly, so widening there would make the image
    // WIDER than the lossless reference.  Never applied to AAC-HE v2 /
    // Parametric Stereo, where the decoder is already reconstructing the field.
    // The former bass=g=0.8:f=80 compensation was removed together with the
    // blanket widening: lossy codecs do not lose low-frequency content, so a
    // fixed bass boost is coloration under the "closest to lossless" criterion.
    // If A/B listening against lossless masters shows bass recession when the
    // widener IS active, reintroduce a mid-channel-only compensation here.
    // *** ORDERING CONTRACT: stereotools is ALWAYS Stage 3 — placed squarely
    //     after aexciter (Stage 1) and the optional Layer-2 EQ (Stage 2).
    //     The improved stereo width the user hears ("less closed, more open")
    //     is specifically because mlev=1.0:slev=1.15 operates on the already-
    //     harmonically-enriched signal.  Placing it before the exciter would
    //     spread the dry signal and receive no benefit from the added harmonics.
    char stage3[128] = "";
    if (p.enable_stereo_widening && channelCount >= 2) {
        snprintf(stage3, sizeof(stage3), ",stereotools=mlev=1.0:slev=1.15");
    }

    // Stage 4 — Soft apodising roll-off + TRUE PEAK LIMITER.
    // *** ORDERING CONTRACT ***
    // The lowpass and alimiter are the LAST two active DSP stages in the chain,
    // placed after the exciter, EQ, and stereo widener:
    //   a) lowpass strips inter-modulation / aliasing products above ~19.5 kHz
    //      generated by the additive stages — moving it earlier would attenuate
    //      the very harmonics the exciter is trying to add.
    //   b) alimiter is placed AFTER the lowpass so it guards against the combined
    //      additive energy (exciter + EQ + stereotools) on near-0 dBFS sources.
    //      A pre-lowpass limiter would see the un-rolled harmonics and over-correct.
    // alimiter timing uses FFmpeg defaults (attack=5 ms lookahead, release=50 ms):
    // the previous 0.1 ms/10 ms made the limiter behave as a clipper and
    // modulate gain within a single bass cycle when it engaged.
    char stage4[128];
    snprintf(stage4, sizeof(stage4),
             ",lowpass=f=%d:poles=1"
             ",alimiter=limit=0.95:attack=5:release=50:level=0",
             SUE_APODIZING_LOWPASS_HZ);

    // Stage 5 — Downsample back to the target carrier when the nonlinear
    // section ran oversampled, then final format normalisation.
    // The alimiter in Stage 4 runs at the oversampled rate so it sees the
    // inter-sample peaks; limit=0.95 leaves headroom for the small overshoot a
    // linear-phase downsample can reintroduce.  The graph stays float32, so no
    // dither_method is applied (dither into float is invalid for lavfi).
    char stage5[160];
    if (workRateHz != targetSampleRateHz) {
        snprintf(stage5, sizeof(stage5),
                 ",aresample=osr=%d:resampler=soxr:precision=33"
                 ",aformat=sample_fmts=flt",
                 targetSampleRateHz);
    } else {
        snprintf(stage5, sizeof(stage5), ",aformat=sample_fmts=flt");
    }

    int written = snprintf(buf, bufSize, "%s%s%s%s%s%s", stage0, stage1, stage2, stage3, stage4, stage5);
    return (written > 0 && (size_t)written < bufSize);
}

// ─── Internal context ─────────────────────────────────────────────────────────

struct SueCtx {
    AVFilterGraph   *filter_graph   = nullptr;
    AVFilterContext *src_ctx        = nullptr;   // abuffer source
    AVFilterContext *sink_ctx       = nullptr;   // abuffersink sink
    AVFrame         *in_frame       = nullptr;   // reused input frame
    AVFrame         *out_frame      = nullptr;   // reused output frame
    int              channels       = 0;
    int              sample_rate    = 0;
    int              target_sample_rate = 0;
    int              input_encoding = 0;         // Android ENCODING_PCM_*
    AVSampleFormat   input_av_fmt   = AV_SAMPLE_FMT_FLT;
    // Profile & logging metadata (immutable, set at init)
    SueIntensityProfile profile     = PROFILE_MODERATE;
    char             profile_str[24]{};
    char             filter_str[2048]{};
    // ReplayGain value used by the Hi-Res Remaster path.  Stored for logging
    // and potential graph rebuild (e.g. nativeReset).
    float            replaygain_db  = HIRES_REMASTER_DEFAULT_GAIN_DB;
};

// Tears down all AVFilter resources inside ctx without freeing the struct itself.
static void teardown_filter_graph(SueCtx *ctx) {
    if (ctx->in_frame)  { av_frame_free(&ctx->in_frame);  }
    if (ctx->out_frame) { av_frame_free(&ctx->out_frame); }
    // avfilter_graph_free also frees all child contexts.
    if (ctx->filter_graph) { avfilter_graph_free(&ctx->filter_graph); }
    ctx->src_ctx      = nullptr;
    ctx->sink_ctx     = nullptr;
    ctx->filter_graph = nullptr;
}

// Builds and configures the filter graph for ctx. Assumes profile_str and
// filter_str are already populated by the caller.
// Returns true on success, false on any AVFilter error.
static bool build_filter_graph(SueCtx *ctx) {
    teardown_filter_graph(ctx);   // ensure clean state for any rebuild

    // ── Create graph container ────────────────────────────────────────────────
    ctx->filter_graph = avfilter_graph_alloc();
    if (!ctx->filter_graph) {
        set_last_init_error("avfilter_graph_alloc failed");
        ALOGE("build_filter_graph: avfilter_graph_alloc failed");
        return false;
    }

    // ── Build abuffer source args ─────────────────────────────────────────────
    // Describes the format of PCM frames we will push into the graph.
    // The channel layout is derived from the channel count.
    AVChannelLayout ch_layout{};
    av_channel_layout_default(&ch_layout, ctx->channels);

    char ch_layout_str[64];
    av_channel_layout_describe(&ch_layout, ch_layout_str, sizeof(ch_layout_str));
    av_channel_layout_uninit(&ch_layout);

    char abuffer_args[256];
    snprintf(abuffer_args, sizeof(abuffer_args),
             "sample_rate=%d:sample_fmt=%s:channel_layout=%s",
             ctx->sample_rate,
             av_get_sample_fmt_name(ctx->input_av_fmt),
             ch_layout_str);

    // ── Create input abuffer ──────────────────────────────────────────────────
    int ret = avfilter_graph_create_filter(
        &ctx->src_ctx,
        avfilter_get_by_name("abuffer"),
        "sue_src",
        abuffer_args,
        nullptr,
        ctx->filter_graph);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("abuffer create failed: ") + errbuf);
        ALOGE("build_filter_graph: abuffer create failed: %s", errbuf);
        teardown_filter_graph(ctx);
        return false;
    }

    // ── Create output abuffersink configured for interleaved float output ─────
    ret = avfilter_graph_create_filter(
        &ctx->sink_ctx,
        avfilter_get_by_name("abuffersink"),
        "sue_sink",
        nullptr,
        nullptr,
        ctx->filter_graph);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("abuffersink create failed: ") + errbuf);
        ALOGE("build_filter_graph: abuffersink create failed: %s", errbuf);
        teardown_filter_graph(ctx);
        return false;
    }

    // The filter chain itself ends with aformat=sample_fmts=flt, so the sink no
    // longer needs an additional sample_fmts constraint here.

    // ── Parse the filter chain string between src and sink ────────────────────
    // avfilter_graph_parse2 expects just the filter chain (no src/sink), then we
    // manually link the src output to the chain input and chain output to sink.
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();
    if (!outputs || !inputs) {
        set_last_init_error("avfilter_inout_alloc failed");
        ALOGE("build_filter_graph: avfilter_inout_alloc failed");
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        teardown_filter_graph(ctx);
        return false;
    }

    outputs->name       = av_strdup("in");
    outputs->filter_ctx = ctx->src_ctx;
    outputs->pad_idx    = 0;
    outputs->next       = nullptr;

    inputs->name       = av_strdup("out");
    inputs->filter_ctx = ctx->sink_ctx;
    inputs->pad_idx    = 0;
    inputs->next       = nullptr;

    ret = avfilter_graph_parse_ptr(ctx->filter_graph, ctx->filter_str, &inputs, &outputs, nullptr);
    avfilter_inout_free(&outputs);
    avfilter_inout_free(&inputs);

    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("avfilter_graph_parse_ptr failed: ") + errbuf +
                            " filter='" + ctx->filter_str + "'");
        ALOGE("build_filter_graph: avfilter_graph_parse_ptr failed: %s (filter_str='%s')",
              errbuf, ctx->filter_str);
        teardown_filter_graph(ctx);
        return false;
    }

    // ── Configure and validate the filter graph ───────────────────────────────
    ret = avfilter_graph_config(ctx->filter_graph, nullptr);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("avfilter_graph_config failed: ") + errbuf +
                            " filter='" + ctx->filter_str + "'");
        ALOGE("build_filter_graph: avfilter_graph_config failed: %s (filter='%s')",
              errbuf, ctx->filter_str);
        teardown_filter_graph(ctx);
        return false;
    }

    // ── Pre-allocate reusable frames ──────────────────────────────────────────
    ctx->in_frame  = av_frame_alloc();
    ctx->out_frame = av_frame_alloc();
    if (!ctx->in_frame || !ctx->out_frame) {
        set_last_init_error("av_frame_alloc failed");
        ALOGE("build_filter_graph: av_frame_alloc failed");
        teardown_filter_graph(ctx);
        return false;
    }

    ALOGD("SUE filter graph ready: profile=%s channels=%d inRate=%d targetRate=%d enc=%d filter='%s'",
          ctx->profile_str, ctx->channels, ctx->sample_rate, ctx->target_sample_rate, ctx->input_encoding,
          ctx->filter_str);
    return true;
}

// ─── JNI entry points ─────────────────────────────────────────────────────────

// ── nativeCreate ──────────────────────────────────────────────────────────────
//
// Routing cop for the dual-engine architecture:
//
//   isForce48kResampleOnly == true → Force-48k passthrough resampler
//     build a minimal libsoxr VHQ graph that converts the current PCM carrier
//     to the requested non-USB target rate (48 kHz in the app policy)
//
//   isLosslessSource == false  →  Lossy path (SUE engine)
//     isSueEnabled == true  → build SUE harmonic-excitation graph
//     isSueEnabled == false → transparent bypass (return 0L)
//     (isHiResEnabled is completely ignored on the lossy path)
//
//   isLosslessSource == true   →  Lossless path (Hi-Res Remaster engine)
//     isHiResEnabled == true  → build Hi-Res Remaster graph (96 kHz oversampling)
//     isHiResEnabled == false → transparent bypass (return 0L)
//     (isSueEnabled is completely ignored on the lossless path)
//
extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeCreate(
    JNIEnv *, jclass,
    jboolean isForce48kResampleOnly,
    jint codecTier, jint bitrateKbps, jint sampleRateHz, jint targetSampleRateHz, jint channelCount, jint inputEncoding,
    jboolean downstreamHqResamplerActive, jint specialFlagsFromResolver,
    jboolean isSueEnabled, jboolean isHiResEnabled, jboolean isLosslessSource,
    jfloat replayGainDb)
{
    clear_last_init_error();

    if (sampleRateHz <= 0 || targetSampleRateHz <= 0 || channelCount <= 0) {
        set_last_init_error("invalid init params");
        ALOGE("nativeCreate: invalid params (inRate=%d targetRate=%d ch=%d)", sampleRateHz, targetSampleRateHz, channelCount);
        return 0L;
    }

    if (static_cast<bool>(isForce48kResampleOnly)) {
        std::unique_ptr<SueCtx> ctx(new (std::nothrow) SueCtx());
        if (!ctx) {
            set_last_init_error("out of memory allocating SueCtx for force-48k path");
            ALOGE("nativeCreate: out of memory (force-48k path)");
            return 0L;
        }

        ctx->channels = static_cast<int>(channelCount);
        ctx->sample_rate = static_cast<int>(sampleRateHz);
        ctx->target_sample_rate = static_cast<int>(targetSampleRateHz);
        ctx->input_encoding = static_cast<int>(inputEncoding);
        ctx->input_av_fmt = encoding_to_avsamplefmt(static_cast<int>(inputEncoding));
        ctx->profile = PROFILE_BYPASS;
        strncpy(ctx->profile_str, "FORCE_48K", sizeof(ctx->profile_str) - 1);

        if (!build_force48k_resample_chain(
                ctx->filter_str,
                sizeof(ctx->filter_str),
                ctx->target_sample_rate)) {
            set_last_init_error("force-48k filter chain string construction failed");
            ALOGE("nativeCreate: force-48k filter chain construction failed");
            return 0L;
        }

        if (!build_filter_graph(ctx.get())) {
            ALOGE("nativeCreate: force-48k filter graph initialization failed");
            return 0L;
        }

        clear_last_init_error();
        ALOGD("Force-48k init: inRate=%dHz outRate=%dHz ch=%d enc=%d filter='%s'",
              sampleRateHz, ctx->target_sample_rate, channelCount, inputEncoding, ctx->filter_str);
        return reinterpret_cast<jlong>(ctx.release());
    }

    // ── Lossless path — strict feature-flag routing ───────────────────────────
    //
    // isLosslessSource == true covers FLAC / WAV / ALAC and any other source
    // whose bit-depth is ≤ 16-bit PCM (ENCODING_PCM_16BIT).
    //
    // TWO MUTUALLY EXCLUSIVE branches:
    //
    //   ┌─ isHiResEnabled = true  ──────────────────────────────────────────────
    //   │  Hi-Res Dynamic Remaster path.
    //   │  ALL of the following are EXCLUSIVELY active in this branch:
    //   │    • replayGainDb  (TRACK_PEAK-derived pre-expansion headroom)
    //   │    • −3.0 dB fallback (HIRES_REMASTER_DEFAULT_GAIN_DB)
    //   │    • volume lavfi stage
    //   │    • compand gentle upward expander (1:1.09 above −30 dB)
    //   │    • ×2 SoXR upsampling (44.1→88.2 kHz / 48→96 kHz)
    //   │    • alimiter True-Peak guard
    //   │
    //   └─ isHiResEnabled = false ──────────────────────────────────────────────
    //      Standard lossless passthrough.
    //      NONE of the above are applied. replayGainDb is not read. No filter
    //      graph is allocated. The PCM stream reaches the DAC byte-for-byte
    //      identical to the decoded output — same as before this feature existed.
    //
    if (static_cast<bool>(isLosslessSource)) {
        if (static_cast<bool>(isHiResEnabled)) {
            // ── ADVANCED DSP BRANCH ─────────────────────────────────────────
            // Everything below is gated on "Hi-Res Dynamic Remaster" being ON.
            // None of this code is reachable when the feature flag is false.

            std::unique_ptr<SueCtx> ctx(new (std::nothrow) SueCtx());
            if (!ctx) {
                set_last_init_error("out of memory allocating SueCtx for Hi-Res path");
                ALOGE("nativeCreate: out of memory (Hi-Res path)");
                return 0L;
            }

            ctx->channels           = static_cast<int>(channelCount);
            ctx->sample_rate        = static_cast<int>(sampleRateHz);
            // Hi-Res Remaster output rate follows the integer-multiplier rule:
            //   source ≤ 48 000 Hz → sampleRateHz × 2
            //     (44 100 → 88 200 Hz; stays in the 44.1 kHz clock family)
            //     (48 000 → 96 000 Hz; stays in the 48 kHz clock family)
            //   source > 48 000 Hz → unchanged (already at or above oversampling floor)
            // This matches SueStage.outputSampleRateHz on the Kotlin side.
            ctx->target_sample_rate = (static_cast<int>(sampleRateHz) > 0 && static_cast<int>(sampleRateHz) <= 48000)
                ? static_cast<int>(sampleRateHz) * 2
                : static_cast<int>(sampleRateHz);
            ctx->input_encoding     = static_cast<int>(inputEncoding);
            ctx->input_av_fmt       = encoding_to_avsamplefmt(static_cast<int>(inputEncoding));
            // Peak-derived pre-expansion headroom from FFmpegDecoder
            // (REPLAYGAIN_TRACK_PEAK).  0.0 dB is a legitimate value (file
            // already has ≥3 dB of headroom), so no sentinel check — the value
            // is only clamped to the sane attenuation range.  The native
            // extractor itself falls back to −3.0 dB when no tag is present.
            // This assignment is ONLY reached when isHiResEnabled == true.
            ctx->replaygain_db      = std::max(HIRES_REMASTER_MIN_GAIN_DB,
                                               std::min(HIRES_REMASTER_MAX_GAIN_DB,
                                                        static_cast<float>(replayGainDb)));
            strncpy(ctx->profile_str, "HIRES_REMASTER", sizeof(ctx->profile_str) - 1);

            if (!build_hires_remaster_chain(ctx->filter_str, sizeof(ctx->filter_str),
                                            sampleRateHz, ctx->replaygain_db)) {
                set_last_init_error("hi-res remaster filter chain string construction failed");
                ALOGE("nativeCreate: hi-res remaster filter chain construction failed");
                return 0L;
            }

            if (!build_filter_graph(ctx.get())) {
                ALOGE("nativeCreate: hi-res remaster filter graph initialization failed");
                return 0L;
            }

            clear_last_init_error();
            ALOGD("HiRes Remaster init: inRate=%dHz outRate=%dHz (x2=%s) ch=%d enc=%d rg=%.2fdB filter='%s'",
                  sampleRateHz, ctx->target_sample_rate,
                  (sampleRateHz <= 48000) ? "yes" : "no (passthrough rate)",
                  channelCount, inputEncoding, ctx->replaygain_db, ctx->filter_str);
            return reinterpret_cast<jlong>(ctx.release());

        } else {
            // ── STANDARD PASSTHROUGH BRANCH ─────────────────────────────────
            // Hi-Res Dynamic Remaster is OFF.
            //
            // No replayGainDb is read.  No −2 dB fallback is applied.
            // No volume, compand, alimiter, or upsampling filter graph is built.
            // The decoded PCM passes through to the AudioTrack sink unmodified,
            // exactly as it did before this feature was introduced.
            clear_last_init_error();
            ALOGD("nativeCreate: lossless source + isHiResEnabled=false → clean passthrough (no DSP)");
            return 0L;
        }
    }

    // ── Lossy path: Sonic Upscaling Enhancer (SUE) ────────────────────────────
    if (!static_cast<bool>(isSueEnabled)) {
        // User disabled SUE — transparent bypass, no error.
        clear_last_init_error();
        ALOGD("nativeCreate: lossy source + isSueEnabled=false → transparent bypass");
        return 0L;
    }

    const int effectiveTargetSampleRateHz = std::max(static_cast<int>(sampleRateHz), static_cast<int>(targetSampleRateHz));

    std::unique_ptr<SueCtx> ctx(new (std::nothrow) SueCtx());
    if (!ctx) {
        set_last_init_error("out of memory allocating SueCtx");
        ALOGE("nativeCreate: out of memory");
        return 0L;
    }

    ctx->channels       = static_cast<int>(channelCount);
    ctx->sample_rate    = static_cast<int>(sampleRateHz);
    ctx->target_sample_rate = effectiveTargetSampleRateHz;
    ctx->input_encoding = static_cast<int>(inputEncoding);
    ctx->input_av_fmt   = encoding_to_avsamplefmt(static_cast<int>(inputEncoding));

    const int resolverSpecialFlags = static_cast<int>(specialFlagsFromResolver);
    const bool isAacHeV2 = (resolverSpecialFlags & SUE_FLAG_DISABLE_MID_SIDE_WIDENING) != 0;
    const bool isAacHe =
        (resolverSpecialFlags & (SUE_FLAG_SKIP_LAYER2_EQ | SUE_FLAG_AAC_HE_ODD_HARMONICS_BLEND)) != 0 ||
        static_cast<int>(codecTier) == TIER_HIGH;

    // Step 1 — Resolve the intensity profile from the codec tier × bitrate matrix.
    const SueIntensityProfile resolvedProfile = resolve_profile(
        static_cast<int>(codecTier),
        static_cast<int>(bitrateKbps),
        nullptr);

    // Step 2 — Apply the downstream-resampler offset when libsoxr CHQ is active.
    const SueIntensityProfile profile = static_cast<bool>(downstreamHqResamplerActive)
        ? sue_apply_resampler_offset(resolvedProfile)
        : resolvedProfile;

    if (static_cast<bool>(downstreamHqResamplerActive) && profile != resolvedProfile) {
        ALOGD("nativeCreate: downstream HQ resampler active — profile offset %s→%s (tier=%d bitrate=%dkbps)",
              profile_name(resolvedProfile), profile_name(profile),
              static_cast<int>(codecTier), static_cast<int>(bitrateKbps));
    }

    ctx->profile = profile;
    strncpy(ctx->profile_str, profile_name(profile), sizeof(ctx->profile_str) - 1);

    // BYPASS: the Kotlin layer should screen for this; return 0L so isActive=false.
    if (profile == PROFILE_BYPASS) {
        clear_last_init_error();
        ALOGD("nativeCreate: codec tier=%d bitrate=%d kbps → BYPASS profile (resolved=%s offset=%s) — stage inactive",
              static_cast<int>(codecTier), static_cast<int>(bitrateKbps),
              profile_name(resolvedProfile), profile_name(profile));
        return 0L;
    }

    // Resolve DSP parameters for the profile.
    const int specialFlags = resolverSpecialFlags != 0
        ? resolverSpecialFlags
        : sue_codec_special_flags(isAacHe, isAacHeV2);
    const SueDspParams params = profile_to_dsp_params(profile, specialFlags);

    // Build the filter chain string using the integrated libsoxr VHQ backend.
    // There is no fallback path: libsoxr is integrated into Expert MG and must
    // always be present; the old swresample retry logic has been removed.
    if (!build_filter_chain(
            ctx->filter_str,
            sizeof(ctx->filter_str),
            params,
            profile,
            sampleRateHz,
            effectiveTargetSampleRateHz,
            channelCount)) {
        set_last_init_error("filter chain string construction failed");
        ALOGE("nativeCreate: filter chain string construction failed — SUE inactive");
        return 0L;
    }

    // Build and configure the AVFilter graph.
    if (!build_filter_graph(ctx.get())) {
        ALOGE("nativeCreate: filter graph initialization failed — SUE inactive (integrated libsoxr path)");
        return 0L;
    }

    ALOGD("SUE init [integrated libsoxr VHQ]: tier=%d bitrate=%dkbps inRate=%dHz targetRate=%dHz ch=%d enc=%d profile=%s (resolved=%s downstreamHqResampler=%d) aacHe=%d aacHeV2=%d flags=0x%x",
          static_cast<int>(codecTier), static_cast<int>(bitrateKbps),
          sampleRateHz, effectiveTargetSampleRateHz, channelCount, inputEncoding,
          ctx->profile_str, profile_name(resolvedProfile),
          static_cast<int>(downstreamHqResamplerActive),
          static_cast<int>(isAacHe), static_cast<int>(isAacHeV2), static_cast<unsigned int>(specialFlags));

    clear_last_init_error();

    return reinterpret_cast<jlong>(ctx.release());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeConsumeLastInitError(
    JNIEnv *env, jclass)
{
    std::string message = g_last_init_error;
    g_last_init_error.clear();
    return env->NewStringUTF(message.c_str());
}

// ── nativeProcessBytes ────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeProcessBytes(
    JNIEnv *env, jclass,
    jlong   handle,
    jobject inputBuffer,  jint inputEncoding, jint inputFrames,
    jobject outputBuffer, jint outputMaxFrames)
{
    if (handle == 0L) return -1;
    auto *ctx = reinterpret_cast<SueCtx *>(handle);
    if (inputFrames <= 0 || outputMaxFrames < 0 || ctx->channels <= 0) {
        ALOGE("nativeProcessBytes: invalid frame/channel count in=%d outMax=%d ch=%d",
              static_cast<int>(inputFrames),
              static_cast<int>(outputMaxFrames),
              ctx->channels);
        return -1;
    }

    // Rebuild the filter graph if the input encoding changed mid-session.
    // In normal operation this never happens — encoding is fixed per track.
    if (static_cast<int>(inputEncoding) != ctx->input_encoding) {
        ALOGW("nativeProcessBytes: input encoding changed %d→%d — rebuilding filter graph",
              ctx->input_encoding, static_cast<int>(inputEncoding));
        ctx->input_encoding = static_cast<int>(inputEncoding);
        ctx->input_av_fmt   = encoding_to_avsamplefmt(static_cast<int>(inputEncoding));
        if (!build_filter_graph(ctx)) {
            ALOGE("nativeProcessBytes: rebuild failed on encoding change");
            return -1;
        }
    }

    const auto *in_ptr  = static_cast<const uint8_t *>(env->GetDirectBufferAddress(inputBuffer));
    auto       *out_ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(outputBuffer));
    const jlong input_capacity = env->GetDirectBufferCapacity(inputBuffer);
    const jlong output_capacity = env->GetDirectBufferCapacity(outputBuffer);
    const int input_bytes_per_sample = av_get_bytes_per_sample(ctx->input_av_fmt);
    const int64_t required_input_bytes =
            static_cast<int64_t>(inputFrames) * ctx->channels * input_bytes_per_sample;
    const int64_t required_output_bytes =
            static_cast<int64_t>(outputMaxFrames) * ctx->channels * sizeof(float);
    if (!in_ptr || !out_ptr || input_capacity < required_input_bytes ||
        output_capacity < required_output_bytes || input_bytes_per_sample <= 0) {
        ALOGE("nativeProcessBytes: non-direct ByteBuffer — use allocateDirect()");
        return -1;
    }

    // ── Push input frame to the abuffer source ────────────────────────────────
    AVFrame *in = ctx->in_frame;
    av_frame_unref(in);
    in->nb_samples     = static_cast<int>(inputFrames);
    in->sample_rate    = ctx->sample_rate;
    in->format         = ctx->input_av_fmt;
    av_channel_layout_default(&in->ch_layout, ctx->channels);

    // For interleaved formats (S16/S32/FLT), data[0] points to the raw buffer.
    in->data[0]      = const_cast<uint8_t *>(in_ptr);
    in->linesize[0]  = static_cast<int>(inputFrames) *
                       av_get_bytes_per_sample(ctx->input_av_fmt) * ctx->channels;
    in->extended_data = in->data;

    int ret = av_buffersrc_add_frame_flags(ctx->src_ctx, in,
                                           AV_BUFFERSRC_FLAG_KEEP_REF |
                                           AV_BUFFERSRC_FLAG_NO_CHECK_FORMAT);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("nativeProcessBytes: av_buffersrc_add_frame_flags failed: %s", errbuf);
        return -1;
    }

    // ── Pull processed frames from the abuffersink ────────────────────────────
    // Accumulates all output frames into out_ptr sequentially.  In steady state
    // the filter graph produces approximately the same number of frames as input.
    int total_frames_out = 0;

    while (true) {
        AVFrame *out = ctx->out_frame;
        av_frame_unref(out);

        const int remaining_frames =
                static_cast<int>(outputMaxFrames) - total_frames_out;
        if (remaining_frames <= 0) {
            break;
        }
        ret = av_buffersink_get_samples(ctx->sink_ctx, out, remaining_frames);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;   // no more frames available in this push cycle
        }
        if (ret < 0) {
            char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
            ALOGE("nativeProcessBytes: av_buffersink_get_frame failed: %s", errbuf);
            av_frame_unref(out);
            return total_frames_out > 0 ? total_frames_out : -1;
        }

        // The sink is constrained to AV_SAMPLE_FMT_FLT (interleaved float).
        const int bytes_per_frame = ctx->channels * static_cast<int>(sizeof(float));
        const int copy_frames     = out->nb_samples;

        memcpy(out_ptr + static_cast<size_t>(total_frames_out) * bytes_per_frame,
               out->data[0],
               static_cast<size_t>(copy_frames) * bytes_per_frame);

        total_frames_out += copy_frames;
        av_frame_unref(out);
    }

    return total_frames_out;
}

// ── nativeFlushBytes ──────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeFlushBytes(
    JNIEnv *env, jclass,
    jlong   handle,
    jobject outputBuffer, jint outputMaxFrames)
{
    if (handle == 0L) return 0;
    auto *ctx = reinterpret_cast<SueCtx *>(handle);
    if (!ctx->src_ctx) return 0;
    if (outputMaxFrames < 0 || ctx->channels <= 0) return -1;

    auto *out_ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(outputBuffer));
    const jlong output_capacity = env->GetDirectBufferCapacity(outputBuffer);
    const int64_t required_output_bytes =
            static_cast<int64_t>(outputMaxFrames) * ctx->channels * sizeof(float);
    if (!out_ptr || output_capacity < required_output_bytes) {
        ALOGE("nativeFlushBytes: non-direct ByteBuffer");
        return -1;
    }

    // Signal end-of-stream to the source; the filters will flush their internal
    // delay lines and push any remaining buffered frames to the sink.
    int ret = av_buffersrc_add_frame_flags(ctx->src_ctx, nullptr, 0);
    if (ret < 0 && ret != AVERROR_EOF) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGW("nativeFlushBytes: send EOF to src failed: %s", errbuf);
    }

    int total_frames_out = 0;
    while (true) {
        AVFrame *out = ctx->out_frame;
        av_frame_unref(out);

        const int remaining_frames =
                static_cast<int>(outputMaxFrames) - total_frames_out;
        if (remaining_frames <= 0) break;
        ret = av_buffersink_get_samples(ctx->sink_ctx, out, remaining_frames);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) break;

        const int bytes_per_frame = ctx->channels * static_cast<int>(sizeof(float));
        const int copy_frames     = out->nb_samples;

        memcpy(out_ptr + static_cast<size_t>(total_frames_out) * bytes_per_frame,
               out->data[0],
               static_cast<size_t>(copy_frames) * bytes_per_frame);

        total_frames_out += copy_frames;
        av_frame_unref(out);
    }

    return total_frames_out;
}

// ── nativeReset ───────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeReset(
    JNIEnv *, jclass,
    jlong handle)
{
    if (handle == 0L) return;
    auto *ctx = reinterpret_cast<SueCtx *>(handle);

    // Rebuild the filter graph from scratch to clear all internal delay lines.
    // This is the safest approach: the filter graph may hold stateful data in
    // IIR filters and resamplers that must be cleared after a seek to avoid
    // audible artefacts from pre-seek content.
    if (!build_filter_graph(ctx)) {
        ALOGW("nativeReset: filter graph rebuild failed — stage may produce artefacts");
    } else {
        ALOGD("nativeReset: filter graph rebuilt for seek (profile=%s)", ctx->profile_str);
    }
}

// ── nativeDestroy ─────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeDestroy(
    JNIEnv *, jclass,
    jlong handle)
{
    if (handle == 0L) return;
    auto *ctx = reinterpret_cast<SueCtx *>(handle);
    ALOGD("nativeDestroy: releasing SUE context (profile=%s)", ctx->profile_str);
    teardown_filter_graph(ctx);
    delete ctx;
}
