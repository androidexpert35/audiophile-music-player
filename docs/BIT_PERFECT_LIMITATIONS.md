# Audiophile — Bit-Perfect Pipeline: Known Limitations & HAL Ceiling

This document enumerates the **hard limits** imposed by the Android platform
on any attempt to deliver truly bit-perfect audio from a user-space app. The
Audiophile engine (FFmpeg JNI decoder + `AudioTrack` with `FLAG_DIRECT`) goes
as far as is possible *without* root, a custom kernel, or a USB DAC driven by
raw UAC2 access. Everything below represents a ceiling we cannot break.

## 1. `AUDIO_OUTPUT_FLAG_DIRECT` is advisory

Setting `AudioTrack.Builder.setFlags(FLAG_DIRECT)` is a **hint** to the HAL,
not a guarantee. On most consumer Android devices the vendor HAL ignores the
flag and routes the stream through the AudioFlinger mixer, which resamples
everything to the mixer's native rate (typically 48 kHz). The
`PipelinePathReport` emitted by `AudioTrackSink` captures what was actually
negotiated — check `usedDirectFlag` and compare `sampleRateHz` against
`nativeOutputSampleRateHz` to confirm.

## 2. Android 13+ may strip `FLAG_DIRECT` from non-system apps

Some OEM ROMs (ColorOS, OneUI, select Xiaomi skins) silently strip the direct
flag during `AudioTrack.Builder.build()` unless the caller is signed with the
platform key. The three-rung fallback chain in `AudioTrackSink` is mandatory
to keep audio flowing; the telemetry UI is the only way a user can tell they
are no longer on the direct path.

## 3. Only Android 14's `setPreferredMixerAttributes(BIT_PERFECT)`
truly bypasses the Flinger mixer — and even then, the HAL downstream of it
may still resample. We apply this preference automatically via
`UsbBitPerfectRouter`; the bit-perfect telemetry badge (`isBitPerfect=true`)
is `true` only when **either** the Android 14+ mixer preference was confirmed
by the router callback, **or** the custom USB host `UsbAudioSink` is active
(raw UAC2 isochronous path, bypasses AudioFlinger entirely).

Direct USB transport does not by itself make processed audio bit-perfect. When SUE,
Hi-Res Dynamic Remaster, or explicit SoXR resampling changes the decoded samples,
the stream still bypasses AudioFlinger through libusb,
but telemetry must report direct processed output rather than bit-perfect source
identity. For processed libusb PCM, the displayed output depth comes from the selected
UAC2 alternate setting's valid resolution (for example 24 valid bits in a 32-bit
subslot), not from the pre-DSP decoder depth. With no USB DAC connected, the same
enhancement pipeline remains available and terminates at the platform `AudioTrack`
fallback.

### ColorOS / OPPO / OnePlus / Realme — `MIXER_BEHAVIOR_BIT_PERFECT` blocked

`AudioManager.getSupportedMixerAttributes()` on ColorOS returns **only**
`MIXER_BEHAVIOR_DEFAULT` profiles (confirmed empirically on OPPO CPH2791,
Android 16 / ColorOS 15). The `audiopolicymanager_vendor` service intercepts
USB audio policy before Android's standard AudioFlinger sees it, stripping all
`BIT_PERFECT` behavior entries from the advertised mixer profile list. This is
an OEM HAL restriction — no user-space workaround exists for the
`setPreferredMixerAttributes` path.

**What this means in practice:** `isBitPerfect` will remain `false` on these
devices via the API 34+ mixer path. However, when `FLAG_DIRECT` is granted
*and* the `PipelinePathReport` shows the stream landed on a USB output device
(`TYPE_USB_DEVICE` or `TYPE_USB_HEADSET`), the path is classified as
**`DIRECT_SUPPORTED`** rather than `OEM_WARNING`. This reflects a physical
reality of the USB stack: isochronous transfer is handled entirely by the
kernel UAC2 driver — OEM user-space DSP daemons run on the application
processor and cannot intercept the USB packet queue at that kernel layer. The
DAC's own display showing the source sample rate and bit depth is the expected
physical confirmation.

Note: `AudioTrack.isDirectPlaybackSupported()` also returns `false` on
ColorOS for high-res formats, even when FLAG_DIRECT is actively being granted
and the AudioTrack is opened at the source rate — this is another broken API
in the vendor HAL. The USB device-type check is the reliable signal for USB
DAC paths; the HAL API is only used as a secondary signal for non-USB outputs.

Note: `nativeOutputSampleRateHz` in `PipelinePathReport` always holds
**the system mixer's preferred rate** (typically 48 kHz, from
`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`). It does **not** represent the
rate of the active FLAG_DIRECT stream. A `nativeOutputSampleRateHz=48000`
reading alongside `sampleRateHz=192000` + `usedDirectFlag=true` is normal and
expected — those are two independent paths. Do not interpret this mismatch as
evidence of resampling on a FLAG_DIRECT path.

**`OEM_WARNING`** is reserved for non-USB outputs (analog headphone jack,
internal speaker) on known-OEM devices where FLAG_DIRECT was granted but
neither USB routing nor the HAL API confirmed an unprocessed path. On analog
signal chains, a SoC-side DSP can be in the path between the HAL and the
amplifier, and the brand heuristic is the only available indicator.

## 4. DSD / DoP requires the custom libusb route

`AudioTrack` still exposes no `ENCODING_DSD_*` carrier, so the Android HAL path
cannot transport DSD bit-perfectly. Audiophile can, however, use the user-granted
USB device file descriptor with libusb and drive a compatible UAC2 endpoint
directly:

- dedicated RAW/Type-IV endpoints use Native DSD_U32;
- PCM endpoints with a four-byte subslot can carry DoP 1.1;
- a DAC with both may begin in Native DSD and fall back to DoP after a bounded
  early-stall observation;
- a DoP-only DAC starts directly in DoP at the correct carrier rate.

This capability is device- and descriptor-dependent. If neither endpoint is
available, FFmpeg decimates DSD to PCM for audibility; that fallback is correctly
reported as processed, not bit-perfect DSD.

## 4a. Software volume is exact only at unity

No digital attenuation can preserve source bits: multiplying by a gain below one
changes the samples. On the custom USB path, 100% volume is exact unity and has no
hidden boost. Below 100%, S16 sources are scaled directly into the DAC's 32-bit
container so attenuation uses the available low bits instead of first collapsing
back to 16-bit. Endpoint-declared padding (for example 24 valid bits in a 32-bit
subslot) is still honoured.

Telemetry and product language must distinguish “bit-perfect at unity” from
“high-precision software attenuation”; the latter is not source-bit identity.

## 5. 24-bit packed PCM is fragmented

`ENCODING_PCM_24BIT_PACKED` (API 31+) is advertised by the platform but its
HAL acceptance is inconsistent even on high-end devices. The pipeline
therefore deliberately **routes 24-bit sources through
`ENCODING_PCM_32BIT`** (zero-padded in the low byte). This is lossless and
sample-perfect; the HAL sees a stream it understands universally.

## 6. Offload is deliberately abandoned

The old ExoPlayer pipeline depended on DSP offload for power savings but was
plagued by the offload-sleeping deadlock (fresh-install first play stuck on
pause, post-pause resume stalls, partial telemetry readings). The new native
pipeline **never** uses offload. The trade-off is ~5–15% higher CPU usage
during playback (heavily model-dependent). On modern SoCs this is
imperceptible; on low-end chips it may slightly reduce battery life during
long listening sessions. We consider correctness more important than power.

## 7. `content://` URIs require file descriptors

The engine resolves MediaStore `content://` URIs by calling
`ContentResolver.openFileDescriptor` and handing FFmpeg the path
`/proc/self/fd/<fd>`. This works on every real local file but **fails** for:

- Cloud-backed MediaStore entries (Drive, OneDrive, iCloud sync).
- DRM-wrapped tracks.
- Files on removable storage that have been unmounted since index time.

The engine reports a failed load as `EnginePlaybackState.ERROR`; the UI
should surface this to the user.

## 8. Gapless has two tiers

- **True gapless** (no audible transition) on engine-fed sinks when the next
  track shares sample rate, PCM encoding, and channel count with the current
  one. The sink is reused; writes continue uninterrupted. The raw libusb PCM
  sink is excluded because its pump owns an independent native decoder handle;
  it reloads the USB session at EOF so a stale EOF cannot terminate the next
  track.
- **Session-rebuild transition** (brief silence while the sink is rebuilt) when
  any of those three dimensions change, or whenever the raw libusb decoder-pump
  path is active. This is required to reopen `AudioTrack` with new parameters or
  to replace the native libusb decoder handle safely.

## 9. Vendor FFmpeg builds

Shipping FFmpeg ourselves means we are responsible for tracking upstream
security patches. The `prebuilt/` tree is explicitly excluded from the
version-controlled source; CI should rebuild from pinned FFmpeg sources on
every release build. See `app/src/main/cpp/prebuilt/README.md` for the
recommended audio-only configuration.
