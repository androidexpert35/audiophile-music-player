package com.androidexpert35.audiophilemusicplayer.domain.model.audio

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec.Companion.fromMimeType


/**
 * Enumeration of audio codecs supported by the audiophile playback engine.
 *
 * The engine prioritises lossless codecs (FLAC, ALAC, WAV, DSD variants)
 * for bit-perfect playback while still handling common lossy formats.
 *
 * @property mimeType The MIME type string used by Android's MediaStore / Media3.
 * @property displayName Human-readable label for UI presentation.
 * @property isLossless Whether this codec preserves the original signal without data loss.
 */
enum class AudioCodec(
    val mimeType: String,
    val displayName: String,
    val isLossless: Boolean
) {
    FLAC("audio/flac", "FLAC", true),
    ALAC("audio/alac", "ALAC", true),
    WAV("audio/wav", "WAV/PCM", true),
    AIFF("audio/aiff", "AIFF", true),
    WMA_LOSSLESS("audio/x-ms-wma", "WMA Lossless", true),
    DSD_64("audio/dsd", "DSD64", true),
    DSD_128("audio/dsd", "DSD128", true),
    DSD_256("audio/dsd", "DSD256", true),
    AAC("audio/mp4a-latm", "AAC", false),
    MP3("audio/mpeg", "MP3", false),
    OPUS("audio/opus", "Opus", false),
    VORBIS("audio/vorbis", "Vorbis", false),
    WMA("audio/x-ms-wma", "WMA", false),
    UNKNOWN("", "Unknown", false);

    companion object {
        /**
         * Resolves an [AudioCodec] from a MIME type string.
         *
         * Handles both standard Android MIME types (e.g. `"audio/mpeg"`) and the
         * raw FFmpeg codec name format produced by the native decoder (e.g.
         * `"audio/mp3"`, `"audio/pcm_s24le"`).
         *
         * @param mime The MIME type or `"audio/<ffmpeg_codec_name>"` string.
         * @return The matching [AudioCodec], or [UNKNOWN] if unrecognised.
         */
        fun fromMimeType(mime: String?): AudioCodec = when {
            mime == null -> UNKNOWN
            mime.contains("wmalossless", ignoreCase = true) ||
                mime.contains("wma_lossless", ignoreCase = true) -> WMA_LOSSLESS
            mime.contains("flac", ignoreCase = true) -> FLAC
            mime.contains("alac", ignoreCase = true) -> ALAC
            // "pcm_s16le", "pcm_s24le", "pcm_f32le", "x-wav", "wav" …
            mime.contains("wav", ignoreCase = true) ||
                mime.contains("x-wav", ignoreCase = true) ||
                mime.contains("pcm", ignoreCase = true) -> WAV
            mime.contains("aiff", ignoreCase = true) -> AIFF
            mime.contains("dsd", ignoreCase = true) -> DSD_64
            mime.contains("mp4a", ignoreCase = true) ||
                mime.contains("aac", ignoreCase = true) -> AAC
            // "mpeg" covers "audio/mpeg"; "mp3" covers FFmpeg's raw codec name "audio/mp3"
            mime.contains("mpeg", ignoreCase = true) ||
                mime.contains("mp3", ignoreCase = true) -> MP3
            mime.contains("opus", ignoreCase = true) -> OPUS
            mime.contains("vorbis", ignoreCase = true) -> VORBIS
            mime.contains("wma", ignoreCase = true) ||
                mime.contains("wmav", ignoreCase = true) -> WMA
            else -> UNKNOWN
        }

        /**
         * Resolves an [AudioCodec] directly from an FFmpeg codec short name
         * (e.g. `"flac"`, `"mp3"`, `"pcm_s24le"`).
         *
         * Handles the full set of FFmpeg decoder codec names including those that
         * differ from the codec's common abbreviation:
         *
         * | FFmpeg name        | Resolves to |
         * |--------------------|-------------|
         * | `mp3` / `mp3float` / `mp3adu` / `mp3adufloat` | [MP3] |
         * | `aac` / `aac_lc` / `aac_he` / `aac_he_v2` / `mp4a` / `mpeg4aac` | [AAC] |
         * | `vorbis`           | [VORBIS] |
         * | `opus`             | [OPUS] |
         * | `wmav1` / `wmav2` / `wmapro` | [WMA] |
         * | `wmalossless`      | [WMA_LOSSLESS] |
         * | `flac`             | [FLAC] |
         * | `alac`             | [ALAC] |
         * | `pcm_*` / `wav`    | [WAV] |
         * | `aiff`             | [AIFF] |
         * | `dsd_*`            | [DSD_64] |
         * | `eac3` / `ac3`     | [UNKNOWN] (not in format support list) |
         * | `""` (blank)       | [UNKNOWN] — empty name from a failed FFmpeg probe |
         *
         * An empty or blank [name] is mapped directly to [UNKNOWN] rather than
         * being forwarded to [fromMimeType] which would construct `"audio/"` and
         * match nothing — the explicit blank check avoids a silent fall-through.
         *
         * @param name The FFmpeg codec short name returned by the native layer.
         * @return The matching [AudioCodec], or [UNKNOWN] if unrecognised.
         */
        fun fromCodecName(name: String?): AudioCodec {
            if (name.isNullOrBlank()) return UNKNOWN

            // Fast path: direct name-to-codec mapping for FFmpeg variants that
            // the generic `contains()` strategy in fromMimeType would miss.
            val trimmed = name.trim().lowercase()
            return when {
                // MP3: all decoder variants (mp3float uses floating-point internally
                // but is still an MP3 stream; mp3adu is Audio Data Units framing).
                trimmed == "mp3" ||
                    trimmed.startsWith("mp3float") ||
                    trimmed.startsWith("mp3adu") -> MP3

                // AAC: codec profile suffixes and container-name variants.
                trimmed == "aac" ||
                    trimmed == "aac_lc" ||
                    trimmed == "aac_main" ||
                    trimmed.startsWith("aac_he") ||
                    trimmed == "mp4a" ||
                    trimmed.startsWith("mpeg4aac") -> AAC

                // WMA Lossless must be checked BEFORE generic WMA so the more
                // specific rule wins.
                trimmed.contains("wmalossless") || trimmed.contains("wma_lossless") -> WMA_LOSSLESS
                trimmed.startsWith("wmav") ||
                    trimmed == "wmapro" ||
                    trimmed == "wmavoice" -> WMA

                // Fallback: delegate to the MIME-based resolver which handles the
                // remaining codecs (flac, vorbis, opus, pcm_*, alac, aiff, dsd_*).
                else -> fromMimeType("audio/$trimmed")
            }
        }

        /**
         * Resolves the DSD-specific [AudioCodec] variant for [rate].
         *
         * @param rate Source DSD family.
         * @return Matching DSD [AudioCodec].
         */
        fun fromDsdRate(rate: DsdRate): AudioCodec = when (rate) {
            DsdRate.DSD64 -> DSD_64
            DsdRate.DSD128 -> DSD_128
            DsdRate.DSD256 -> DSD_256
        }
    }
}

