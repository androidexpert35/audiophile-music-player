# Feasibility — On-device audio classifier → DSP preset selection

> Status: **investigation only**. No implementation code exists for anything described
> here. Written against the repository state of commit `e9409eb` (branch `develop`).
> Companion reading: [`ai/native-audio.md`](ai/native-audio.md),
> [`ai/playback.md`](ai/playback.md), [`BIT_PERFECT_LIMITATIONS.md`](BIT_PERFECT_LIMITATIONS.md).
> The §5 plan is broken into implementable tickets in
> [`tickets-audio-analysis.md`](tickets-audio-analysis.md).

The proposal under evaluation: ship YAMNet (`.tflite`, Apache-2.0, ~14 MB, 3.73 M params)
via LiteRT, run it at track load over N sampled windows, aggregate to one stable content
label, cache it per file, and use that label to pick the parameters of the two existing
DSP engines (SUE lossy restoration, Hi-Res Dynamic Remaster).

**Verdict up front: the caching and insertion-point design is sound; the windowed-sampling
design is not (§2.6); the classifier is the wrong input (§8).** Every DSP parameter these
two engines expose is a low-order function of a measurable signal property, and this chain's
decision functions all fall in the class where exact measurement dominates learned
estimation. The measurement half of this idea can ship with zero new dependencies and zero
APK growth, using filters already inside the FFmpeg binary this app ships today.

§7 builds the strongest available case *for* an ML-first design across seven paths and
kills six of them. The seventh — preference modelling — is genuinely load-bearing and is a
different feature (§8.2). A small bypass-predicate model survives at ~1.8 MB, not 14 MB.

Revision note: §1.7, §2.4, §2.5, §5 and §7–§8 supersede the first draft. Four of its claims
were wrong or overstated and are corrected in place, each marked.

---

## 1. Inventory

### 1.1 FFmpeg build

FFmpeg is **not built by this repository**. It is a prebuilt shared-library drop.

| Fact | Value |
|---|---|
| Version | **7.1.4** (string in `libavutil.so`) |
| Producer | `ffmpeg-android-maker`, NDK r29, `aarch64-linux-android33-clang` |
| Configure flags | `--enable-cross-compile --target-os=android --enable-shared --disable-static --disable-vulkan --enable-libsoxr` |
| Install script | [`scripts/install-ffmpeg-prebuilt.ps1`](../scripts/install-ffmpeg-prebuilt.ps1) |
| Consumed by | [`app/src/main/cpp/CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt) via feature detection on `jniLibs/<ABI>/libavformat.so` |

That flag list is the *entire* configure line. There is no `--disable-everything`, no
`--enable-decoder=...` allowlist, no `--disable-avdevice`, no `--disable-swscale`. This
is a **stock full FFmpeg build** with libsoxr added and Vulkan removed.

Consequences, all verified against the shipped binaries:

- **`avfilter` is compiled and shipped.** `libavfilter.so` is 3 485 576 B (arm64) /
  4 450 512 B (x86_64) and is linked by `CMakeLists.txt:126`.
- **The full default audio filter set is present.** Confirmed by string probe of
  `libavfilter.so`: `aresample`, `aexciter`, `equalizer`, `stereotools`, `lowpass`,
  `alimiter`, `compand`, `aformat`, `anull` (all currently used) **plus**
  `astats`, `aspectralstats`, `ebur128`, `loudnorm`, `dynaudnorm`, `acompressor`,
  `firequalizer`, `superequalizer`, `anequalizer`, `crossfeed`, `arnndn`, `anlmdn`,
  `afftfilt`, `afir`, `asupercut`, `asubcut`, `bass`, `treble`, `volumedetect`,
  `silencedetect`, `speechnorm`, `deesser`, `agate`, `surround`, `sofalizer`.
  **This is the single most important finding of the inventory: `astats`,
  `aspectralstats` and `ebur128` are already inside the APK, already linked, and cost
  nothing to start using.**
- **No `--enable-ladspa`, no `--enable-lv2`.** Neither is a default; both would appear
  explicitly in the configure string. Neither is present.
- **`libsoxr` is embedded inside `libswresample`**, exposed only through the lavfi
  `aresample=...:resampler=soxr:precision=33` option. There is no standalone soxr JNI
  bridge, despite three stale comments claiming one exists
  (`ffmpeg_bridge.cpp:71-74` mentions "soxr_bridge.cpp / SoxResamplerStage"; no such
  file or class is in the tree).
- **Video decoders are shipped in an audio player.** `h264`, `hevc`, `av1`, `vp9`,
  `mjpeg`, `prores` all resolve in `libavcodec.so`. `libswscale.so` (1.1 MB arm64) and
  `libavdevice.so` (65 KB) are packaged and never loaded.

**Build-reproducibility problem.** `install-ffmpeg-prebuilt.ps1` copies exactly four
libraries — `avcodec avformat avutil swresample` (line 37) — but `CMakeLists.txt` links
five (`avfilter` too), and `jniLibs/` actually contains seven plus libusb. It also
defaults to `@('arm64-v8a', 'armeabi-v7a', 'x86_64')` (line 30) while
`app/build.gradle.kts:47` builds only `arm64-v8a` and `x86_64`, and it writes into
`app/src/main/cpp/prebuilt/` which `CMakeLists.txt` no longer reads. Running that script
on a clean checkout produces a tree that does not build. The `.so` files currently in
`jniLibs/` are the only artifact keeping the project compilable, and nothing in the repo
can regenerate them.

### 1.2 Audio pipeline

Two engines behind `AudioEngineManager` (`data/playback/engine/AudioEngineManager.kt`),
hot-swapped at runtime. **`EngineType.STANDARD` is the default at app start** — the
whole DSP surface discussed in this document is reachable only after the user opts into
the audiophile engine.

```
                    AudioPlaybackService (Media3 MediaSessionService)
                                     |
                            AudioEngineManager  (Mutex-serialised hot-swap)
                     +---------------+----------------+
                     |                                |
             StandardEngine                   AudiophileEngine
             (ExoPlayer, offload OFF)                 |
             -- no DSP, no analysis --                |
                                          BitPerfectPlaybackEngine
                                          (ONE HandlerThread,
                                           Process.THREAD_PRIORITY_AUDIO --
                                           every load, seek, enqueue and
                                           write-loop tick runs here)
                                                      |
                                        BitPerfectSessionLoader
                                        T1/T2 bit-perfect -> T3 PCM fallback
                                                      |
                             +------------------------+---------------+
                             |                                        |
                        format.isDsd                              PCM source
                             |                                        |
              BitPerfectSinkRouter                     BitPerfectPcmRatePolicy
              -> native DSD | DoP candidates           -> PlaybackSampleRatePlan
                             |                                        |
                             |                          buildSueStageIfNeeded()
                             |                          (SueBridge.nativeCreate)
                             |                                        |
                             |              +-------------------------+----------+
                             |              |  <== THE ONLY DSP INSERTION POINT  |
                             |              |  sue_bridge.cpp lavfi graph, one   |
                             |              |  of three mutually exclusive modes:|
                             |              |   - SUE          (lossy sources)   |
                             |              |   - Hi-Res DAR   (lossless sources)|
                             |              |   - force-48k    (soxr only)       |
                             |              +-------------------------+----------+
                             |                                        |
                             +----------------+-----------------------+
                                              |
                                   AudiophileOutputSink
                     +------------------------+------------------------+
                     |                        |                        |
          LibusbPcmAudioSink       LibusbPcmEnhancedSink        AudioTrackSink
          LibusbDsdAudioSink       (Kotlin float32 write         (FLAG_DIRECT)
          (native decoder pump)     loop -> JNI f32->S32LE)
                     |                        |
       DecoderToRingBridge          EngineSwapBridge
       (native pump thread)         .nativeWriteToRingBuffer
                     +------------+-----------+
                                  |
                        SpscRingBuffer (128 KB ~ 43 ms @192 kHz/32-bit stereo)
                                  |
                        IsoTransferPool ISO callback
                                  |
                            USB DAC (libusb UAC2)
```

**Where DSP lives:** exactly one place — the lavfi graph built by
`sue_bridge.cpp`, created once per track load in `buildSueStageIfNeeded()`
(`BitPerfectEnhancementPipeline.kt:181`) from inside `BitPerfectSessionLoader`
(`BitPerfectSessionLoader.kt:112` for tier 1/2, `:254` for tier 3). There is no second
DSP site. There is no per-frame parameter update path — `SueBridge.nativeReset()` only
rebuilds the graph identically after a seek. **The "no runtime parameter changes"
constraint is already structurally enforced; nothing needs to be added to satisfy it.**

DSD tier-1/tier-2 never reaches the DSP stage at all (`BitPerfectSessionLoader.kt:186`,
`sueStage = null`, `pathType = PathType.DSD`).

### 1.3 SUE lossy engine — what it actually is

It is **not** custom DSP. It is a printf-assembled lavfi filter string, built in
`sue_bridge.cpp:415` `build_filter_chain()`:

```
[stage0]  aresample=osr=<2x target if target<=48k>:resampler=soxr:precision=33
[stage1]  aexciter=level_in=1.0:level_out=1.0:amount=A:drive=D:blend=B:freq=F
[stage2]  equalizer=f=10500:width_type=o:width=4.0:g=G10
          equalizer=f=14500:width_type=o:width=3.0:g=G14      (skipped for AAC-HE)
[stage3]  stereotools=mlev=1.0:slev=1.15                       (conditional)
[stage4]  lowpass=f=19500:poles=1
          alimiter=limit=0.95:attack=5:release=50:level=0
[stage5]  aresample=osr=<target>:resampler=soxr:precision=33 , aformat=sample_fmts=flt
```

Parameters, all **hardcoded** in `profile_to_dsp_params()` (`sue_bridge.cpp:220-278`):

| Profile | `amount` | `drive` | `freq` | `air_10k` | `air_14k` | widening |
|---|---|---|---|---|---|---|
| AGGRESSIVE | 2.2 | 9.0 | 8000 | 0.50 | 0.70 | yes |
| MODERATE | 1.8 | 8.5 | 8500 | 0.40 | 0.60 | yes |
| LIGHT | 1.0 | 6.5 | 10000 | 0.30 | 0.35 | no |
| SUBTLE | 0.5 | 5.0 | 12000 | 0.15 | 0.15 | no |
| BYPASS | — | — | — | — | — | — |

Profile selection is a 4x5 lookup table `PROFILE_MATRIX` (`sue_bridge.cpp:161`) indexed
by *codec tier* x *bitrate bucket*. Bitrate comes from
`AVCodecContext->bit_rate` via `FFmpegDecoder.nativeGetBitrateBps`.
`SUE_APODIZING_LOWPASS_HZ` is a fixed `19500` (`sue_bridge.cpp:124`).

**The same matrix is duplicated in Kotlin** at `SueProfileResolver.kt:83-111`, with a
comment at line 52 saying "keep the two in sync". Two hand-maintained copies of the same
decision table in two languages.

**UI surface: one boolean.** `settings_sue_title` = "Lossy Audio Restoration", a toggle.
Nothing else is user-adjustable. `SueInfo`/`SueStatus` expose the resolved profile name
read-only for the telemetry card.

### 1.4 Hi-Res Dynamic Remaster — what it actually is

Same mechanism, different string. `sue_bridge.cpp:354` `build_hires_remaster_chain()`:

```
volume=<replayGainDb>dB
compand=attacks=0.01:decays=0.3:soft-knee=4:points=-80/-80|-30/-30|-3/-0.5|0/-0.5
[,aresample=osr=<2x source>:resampler=soxr:precision=33]     (only if source <= 48 kHz)
,alimiter=limit=0.95:attack=5:release=50:level=0
,aformat=sample_fmts=flt
```

Every number in that string is a compile-time constant **except `replayGainDb`**, which
is the only input-derived DSP parameter anywhere in the codebase. It comes from
`extract_replaygain_db()` (`ffmpeg_bridge.cpp:725`), which reads the
`REPLAYGAIN_TRACK_PEAK` **tag** and computes
`gain = clamp(-20*log10(peak) - 3.0, -6.0, 0.0)`.

When the tag is absent — which is the common case for ripped FLAC, for every WAV, and
for most ALAC — it returns the blind default `-3.0 dB` (`ffmpeg_bridge.cpp:692`).

Gating: lossless only, and skipped when `format.isAlreadyNativeHiRes()`
(>=24-bit or >48 kHz). UI surface: one boolean.

### 1.5 Existing signal analysis

**None.** There is no FFT, no spectrum estimation, no peak/RMS measurement, no crest
factor, no cutoff detection, no loudness measurement anywhere in `cpp/` or in Kotlin.

The only thing resembling analysis is `extract_replaygain_db()`, and it does not analyse
the signal — it parses an `AVDictionary` string tag. `av_dict_get` on
`REPLAYGAIN_TRACK_PEAK`, `strtof`, done.

Everything the DSP believes about a track — its spectral cutoff, its headroom, its
dynamic range, its stereo width — is **inferred from container metadata**
(codec name, `bit_rate`, `bits_per_raw_sample`) or **assumed from a constant**.

### 1.6 Threading and buffering

| Item | Value | Source |
|---|---|---|
| Audio thread | one `HandlerThread`, `Process.THREAD_PRIORITY_AUDIO` | `BitPerfectPlaybackEngine.kt:108` |
| What runs on it | `loadTrack`, `loadAndPlay`, `enqueueNext`, `play/pause/stop/seek/release`, `reloadWithCurrentSettings`, and every write-loop iteration | `:235-468` |
| PCM scratch buffer | direct `ByteBuffer`, 64 KiB floor, sized to `bytesPerFrame x 4096` | `BitPerfectTransportBuffers.kt:90-94` |
| Decode granularity | 4096 frames ~ **93 ms @44.1 kHz**, ~21 ms @192 kHz | same |
| Native ring | `SpscRingBuffer` **128 KB ~ 43 ms** @192 kHz/32-bit stereo | `usb_audio_bridge.cpp:1025` |
| Recoverable-error retry | 10 ms | `BitPerfectPlaybackEngine.kt:1373` |
| libusb EOF poll | 50 ms | `:1382` |
| Idle sink release | 120 000 ms | `:1407` |

**There is no pre-analysis step today.** The closest thing is `BitPerfectGaplessQueue`,
which during playback of track N opens a **second** `FFmpegDecoder` for track N+1
(`doEnqueueNext`, `BitPerfectPlaybackEngine.kt:602`) to compare formats — but that work
also happens **on the audio thread, while audio is playing**, with 43 ms of ring
downstream. That is the single most dangerous place in this codebase to add work.

### 1.7 Persistence

Room, `AudiophileDatabase` **version 13**, 9 entities, 12 migrations.

`tracks` (`TrackEntity.kt`) is the per-track table. Primary key: `id: Long`.

Track identity:

- **MediaStore tracks**: `id` is the MediaStore `_ID`. Stable while the file stays
  indexed; a delete + re-add produces a new id.
- **DSD tracks** (`.dsf`/`.dff`, invisible to MediaStore): synthetic negative id,
  `DsdFileScanner.kt:207`:
  ```kotlin
  val stableId = -(uriString.hashCode().toLong().and(0x7FFF_FFFFL) + 1L)
  ```
  A **31-bit Java string hash of the SAF URI**. Artist and album ids use the same scheme
  on the artist/album *name* (`:309`, `:311`).

There is **no content hash, no file digest, no MBID, no AcoustID** anywhere in the repo.
`fileSizeBytes` and `dateAdded` are stored and would be the cheap building blocks of a
real cache key.

**On the 31-bit hash — corrected, this was overstated in the first draft.** The birthday
maths is right but the threshold is unreachable: 50 % collision probability needs ~54 000
DSD entries and 9 % needs ~20 000. A DSD64 album is 2–3 GB, so a large DSD collection is
hundreds to low thousands of tracks. At 2 000 entries the collision probability is
`1 − exp(−n²/2M) ≈ 0.09 %`. That is not a real risk and should not be used to justify
work.

The real reason to introduce a content key is **cache invalidation**, not collision. A
per-track analysis result must be discarded when the *audio* changes and retained when it
does not — and `TrackEntity.id` cannot express either. A MediaStore delete + re-add mints
a new id for identical audio (cache lost for no reason); a file replaced in place keeps
its id (stale analysis silently applied to different audio).

That also corrects a flaw in this document's own earlier Step 1 proposal. "Digest of the
first and last 64 KiB" is wrong: the first 64 KiB is where ID3v2 / Vorbis comments live,
so a tag edit — adding artwork, fixing a spelling — would invalidate an analysis of audio
that never changed. The key must be over data that moves only when the samples move:
`fileSizeBytes` + a digest at fixed fractional offsets into the file (say 25 % and 75 %),
which are almost certainly inside the audio payload, or `fileSizeBytes` + `durationMs` +
`sampleRateHz` if a digest is judged not worth the I/O.

`MediaIndexRepositoryImpl.scanAndIndexMedia()` is a `callbackFlow` on `@IoDispatcher`
emitting `MediaIndexingProgress` — the natural host for batch analysis, but note it is
app-scoped, not a `WorkManager` job. **`androidx.work` is not a dependency of this
project.** The only declared service is `AudioPlaybackService`
(`foregroundServiceType="mediaPlayback"`).

### 1.8 ABI and APK size

ABIs: **`arm64-v8a`, `x86_64`** (`app/build.gradle.kts:47`).
`docs/ai/native-audio.md` still claims `armeabi-v7a` is shipped — stale.

Measured on `app/release/`:

| Artifact | Size |
|---|---|
| `app-release.apk` (universal) | **108 861 619 B ~ 103.8 MiB** |
| `app-release.aab` | **41 304 920 B ~ 39.4 MiB** |

Composition of the universal APK:

| Component | Size |
|---|---|
| `classes.dex` … `classes4.dex` (4 files) | **58 178 076 B ~ 55.5 MiB** |
| `lib/x86_64/` total | **25.8 MiB** |
| `lib/arm64-v8a/` total | **21.1 MiB** |
| — `libavcodec.so` (arm64) | 12 462 256 B |
| — `libavfilter.so` (arm64) | 3 485 576 B |
| — `libavformat.so` (arm64) | 2 458 688 B |
| — `libc++_shared.so` (arm64) | 1 253 544 B |
| — `libswscale.so` (arm64, **unused**) | 1 125 832 B |
| — `libavutil.so` (arm64) | 682 288 B |
| — `libswresample.so` (arm64) | 250 240 B |
| — `libaudiophile_native.so` (arm64) | 187 200 B |
| — `libusb1.0.so` (arm64) | 105 104 B |
| — `libavdevice.so` (arm64, **unused**) | 64 952 B |
| `resources.arsc` | 909 324 B |

`isMinifyEnabled = false` in the release build type (`app/build.gradle.kts:99`).
R8 is off. 55 MiB of dex on an app of this scope is the signature of
`androidx.compose.material.icons.extended` plus no shrinking.

---

## 2. Evaluation

### 2.1 Where would the PCM window extractor attach?

**Not** inside `BitPerfectPlaybackEngine`. Everything in that class runs on the audio
`HandlerThread`; adding decode + inference there would starve a write loop that has
43 ms of ring downstream.

The correct shape is a standalone component in `data/playback/` (or a new
`data/analysis/`) that:

1. runs on `@IoDispatcher`;
2. constructs its **own** `FFmpegDecoder` instance. This is safe and requires no new
   native code: the decoder's contract is "one instance = one native session, all calls
   from the same thread" (`FFmpegDecoder.kt:14-24`) — it is *not* a singleton and the
   native `Session` struct carries no global state. `BitPerfectGaplessQueue` already
   proves two concurrent decoders coexist;
3. uses the existing `open(path, forcePcm = true)` + `seekTo()` + `readNextBuffer()`
   surface, unchanged.

So: **no separate decode implementation is needed, and no new JNI entry point is needed
for extraction.** What *is* needed is a `content://` → path resolution outside the
engine — `BitPerfectUriResolver` (`resolveUriToPath`) is currently reached through
engine-private code, so it would have to be lifted to a shared helper.

The result must reach the DSP as a **pure cache read**, pushed into
`BitPerfectSessionLoader` alongside `sueEnabled`/`hiResEnabled`. It must never be a Room
query executed from `doLoadTrack` or `doEnqueueNext` — both run on the audio thread, and
`doEnqueueNext` runs *while audio is playing*.

Consequence you have to accept up front: **on a cache miss, the first play of a track
cannot use the analysis.** The only alternatives are delaying playback (unacceptable) or
changing parameters mid-track (violates your own constraint #3). The feature is
therefore "analyse in background, apply from the next play", or "analyse at index time".

### 2.2 YAMNet wants mono float32 @16 kHz. Can the existing resampler do it?

Partly, and the part it can't do is the part that matters.

- **Rate conversion: yes, already available, no new code.** `SueBridge.nativeCreate`
  with `isForce48kResampleOnly = true` builds
  `aresample=osr=<targetSampleRateHz>:resampler=soxr:precision=33:cutoff=0.91:dither_method=triangular_hp,aformat=sample_fmts=flt`
  (`sue_bridge.cpp:379`). `targetSampleRateHz` is a plain parameter — passing 16000
  works. Output is already interleaved float32.
- **Channel downmix: no.** The graph preserves channel count end to end; `abuffer` is
  configured from `av_channel_layout_default(channels)` and nothing collapses to mono.
  You would either add `,pan=mono|c0=0.5*c0+0.5*c1` to that chain or average the two
  float channels in Kotlin. Kotlin averaging over ~15 s of 16 kHz audio is ~240 k float
  adds — irrelevant.
- **Quality: soxr VHQ precision=33 for a 16 kHz classifier input is comically
  overqualified.** YAMNet's first op is a 25 ms / 10 ms STFT into 64 mel bins.
  Anti-alias quality below ~90 dB is irrelevant to it. Using the VHQ path here costs real
  CPU for no measurable benefit; `swr` default sinc, or even a cheap polyphase, is the
  right tool. If you reuse the force-48k path you inherit precision=33 whether you want
  it or not.
- **Cost, honestly measured against what dominates:** resampling is not the expensive
  part. Decoding is. For a 44.1 kHz FLAC, decoding 3 x 5 s of audio is ~15 s of decode
  work at maybe 100–300x realtime → 50–150 ms. For a DSD256 source it is far worse:
  tier-3 forces the `volume → lowpass → alimiter → aformat=flt → aresample(soxr, 88200)`
  graph (`ffmpeg_bridge.cpp:461`) before you get any PCM at all, and only then do you
  decimate 88 200 → 16 000.

One correction to the premise: reusing the *existing* resampler here means reusing
`SueBridge`, which means the analysis path takes a dependency on the DSP engine's JNI
surface. That is backwards — the analyser should not be able to touch the playback DSP
context at all. A dedicated small lavfi graph (or plain `swr_convert`) is cleaner than
reusing `sue_bridge`, even though `sue_bridge` technically already does it.

### 2.3 Which of the two engines benefits more, and from which parameters?

**Hi-Res Dynamic Remaster, by a wide margin — and not because of a genre label.**

The Hi-Res chain has a single input-derived parameter (`replayGainDb`) sourced from a tag
most files lack, and a `compand` curve that is **identical for every lossless track that
passes the gate**. That curve applies ~+2.5 dB of upward expansion between −30 and −3 dB
to a 1975 jazz master with 18 dB PLR and to a 2010 loudness-war master with 6 dB PLR,
equally. For the first one, that is unrequested colouration of a master that never needed
it. The feature's own dialog copy says it exists to undo the Loudness War — but it has no
way to tell whether a given file is a victim of it.

Parameters that should become measured:

| Chain stage | Today | Should be driven by |
|---|---|---|
| `volume=<gain>dB` | tag, else blind −3.0 | measured sample / true peak |
| `compand` slope & knee | fixed `-30/-30\|-3/-0.5` | measured PLR (crest) — skip entirely above ~14 dB |
| whether to run at all | codec + bit-depth gate | measured clipping ratio — bypass an already-clipped master |

**SUE benefits second, and its defect is sharper but narrower.** `exciter_freq` is
anchored to "half the codec's expected low-pass cutoff", where *expected* means "looked
up from the bitrate bucket". That inference breaks on:

- **transcodes** — a 320 kbps MP3 re-encoded from a 128 kbps source has a real cutoff
  near 16 kHz but reports 320 kbps → column 4 → `BYPASS`. The file that most needs
  restoration is the one guaranteed to receive none;
- **VBR** — `AVCodecContext->bit_rate` for VBR MP3/AAC is an average or, in some
  containers, 0. `bitrate_to_column()` maps 0 to column 1 with a comment calling it
  "conservative"; for a VBR-V0 file that is a two-column error;
- **non-LAME encoders** — the whole cutoff table is calibrated on LAME defaults
  (`sue_bridge.cpp:150-160`). Fraunhofer, Shine, and every hardware encoder differ.

The fix is one number: **the frequency above which the file has no meaningful energy.**
That single measurement replaces the entire codec-tier x bitrate matrix as the driver of
`exciter_freq`, and it makes `SUE_APODIZING_LOWPASS_HZ = 19500` adaptive instead of fixed.

### 2.4 Counter-argument to the classifier hypothesis

Your hypothesis is *content label → DSP preset*. Against the actual code, it does not
hold. Four objections, in descending order of how much they matter:

**(a) No parameter in this codebase is a function of genre.** Go through them:
`exciter_amount/drive/freq`, `air_gain_10k/14k`, `slev`, `lowpass f`, `alimiter limit`,
`volume dB`, `compand points`. Every one is a response to a *defect* — bandwidth loss,
image collapse, lost headroom, squashed crest — and every defect is directly observable
in the signal. "This is jazz" does not tell you the file's cutoff. "This file rolls off
at 16.1 kHz" does.

**(b) YAMNet is an audio-event classifier, not a music classifier.** Its 521 AudioSet
classes are dominated by speech, animals, vehicles, tools, environmental sounds. On a
library that is 100 % music, the overwhelming majority of that output space is dead, and
top-1 will be `Music` on nearly every window. The genre classes that do exist
(`Rock music`, `Hip hop music`, `Jazz`, …) are among the noisiest labels in AudioSet and
carry low per-class AP; they were never intended as a genre taxonomy. You would ship
14 MB to be told "Music", with a low-confidence genre guess attached.

**(c) The one thing YAMNet is genuinely good at, you have not asked it for.** It
separates `Speech` / `Music` / `Applause` / `Silence` / `Vinyl noise` reliably — those
are high-AP classes. That is real signal, and it maps to real decisions: bypass all DSP
on spoken word and audiobooks, do not excite crowd noise on a live album, do not treat
surface noise as "air". If a classifier ships, *that* is its job description — not genre.

**(d) On long files the aggregation premise is self-defeating.** For a 2-hour DJ set,
"N windows → one stable label → fixed parameters" is only coherent because you sampled
too few windows to notice the file is heterogeneous. The honest answer for a mix is that
the parameters *should* vary across the file — which your own constraint #3 forbids. The
feature is therefore intrinsically wrong for exactly the content where a classifier
sounds most appealing.

**Explicit split — measure vs. classify:**

Measured features split into **two classes with different sampling requirements**. The
first draft of this document collapsed them into one "N sampled windows" bucket, which
was a design error — see §2.6.

| **Class S — stationary.** Estimates of a central tendency. 3–5 windows suffice. |
|---|
| Real spectral cutoff — highest frequency with energy above the noise floor (`aspectralstats` rolloff, or one FFT) |
| Spectral tilt — HF/LF energy ratio (`aspectralstats` centroid / slope) |
| Stereo correlation and mid/side energy ratio (`astats` on M/S, or `aphasemeter`) |
| Noise-floor level and spectrum in quiet passages |
| DC offset (`astats` DC_offset) |
| SBR/PS presence — **already available**, `nativeGetCodecProfileName`, zero cost |

| **Class I — integral / extremal.** Defined over the whole program. Sampling biases them. |
|---|
| Sample peak and true peak — a *maximum*; any subsample underestimates it |
| Integrated loudness, LUFS (`ebur128` I) — R128 gating is defined over the whole program |
| Crest factor / PLR = peak − loudness — inherits the bias of both terms |
| Clipping ratio and flat-top run lengths (`astats` Flat_factor / Peak_count) — a count over all samples |
| Silence lead-in / lead-out (`silencedetect`) — positional, needs the head and tail specifically |

| Could reasonably be **classified** (needs a model; none of it drives a DSP knob today) |
|---|
| Speech vs. music vs. applause vs. silence vs. surface noise → *bypass* decisions |
| Live-recording detection (sustained crowd/applause) → suppress stereo widening |
| Spoken-word / audiobook detection → suppress all enhancement |

| Should remain the **user's** (a measurement cannot decide taste) |
|---|
| SUE on/off, Hi-Res on/off |
| Whether upward expansion is wanted at all on a correctly-mastered file |
| Global enhancement strength, if it is ever exposed |

Note what the middle column is: three bypass predicates. Not one of them selects a
parameter value. That is the entire realistic contribution of a 14 MB classifier to this
codebase as it stands.

### 2.5 Concrete risks

**APK size.** Model 14 MB (asset, ABI-agnostic, counted once in the AAB) + LiteRT
runtime native libs, realistically 1.5–3 MB per ABI x 2 ABIs. Total **~ +17–20 MB to the
AAB, ~ +15 MB to a delivered per-device install**. Against a 39.4 MiB AAB that is roughly
+40 %. It is not the binding constraint on a project that ships `libswscale.so` and
h264/hevc/av1 decoders in a music player and builds release with R8 off — but "we are
already wasteful" is not a budget.

**Perceived track load time.** Only if the analysis is ever synchronous with load, which
it must not be. With the cache-read design the load path cost is one `Long`-keyed Room
lookup, done off the audio thread and pushed in. The real exposure is the **cold-start
path**: first inference after process start pays interpreter construction plus a 14 MB
mmap / page-in — 100–300 ms typical, and it must never land on the audio thread or during
`doEnqueueNext`.

**Battery — corrected upward by an order of magnitude.** The first draft costed only the
windowed pass and was wrong for everything in Class I.

- *Class S, windowed:* 10 000 tracks x (seek + decode ~15 s + resample) ~ 150–400 ms/track
  ~ **25–65 minutes** of sustained CPU. This number stands.
- *Class I, full-file:* every sample must be decoded and fed through `ebur128` + `astats`.
  A 4-minute track at ~200x realtime is **~1.2 s**, not 150 ms; at a more realistic
  80–200x for the combined decode + filter pipeline (and lower for 24/96 or DSD), call it
  **1.2–3 s/track**. Over 10 000 tracks that is **3.3–8.3 hours of sustained CPU**, plus
  reading the entire library off storage.

The second figure changes the deployment story, not just the estimate. Class I is not an
opportunistic background task; it is an overnight-on-charger, resumable, multi-session job
with explicit progress. `androidx.work` with `requiresCharging` / `requiresBatteryNotLow`
stops being a nicety and becomes mandatory — a new dependency, a `Configuration.Provider`,
and a Hilt worker factory, none of which exist today.

**Long files (2-hour DJ set).** Container seeking is cheap; the aggregation is what
breaks (see 2.4(d)). Also note DSF/DSDIFF seeks land on packet boundaries and
`ffmpeg_bridge.cpp` trims a channel-aligned prefix — an analyser doing many seeks
exercises that path far harder than playback ever does.

**Short tracks.** Anything under ~3 s (interludes, album intros, hidden tracks, gapless
album fragments) cannot fill N windows. Needs an explicit "too short → no analysis, fall
back to today's behaviour" branch, not a silent partial result.

**DSD.** Tier-1/tier-2 DSD never reaches the DSP stage. Analysing a DSD file to pick DSP
parameters that will never be applied is pure waste, and forcing tier-3 decode just to
analyse it is expensive. DSD must be excluded from analysis by construction.

**Standard engine.** The ExoPlayer path has no DSP at all, and it is the default.
Analysis results are inert for any user who never enables the audiophile engine.

**Bit-perfect.** Genuinely low risk, *if* the analyser is a separate `FFmpegDecoder` on
`@IoDispatcher`, never touches the playing session, and no analysis-derived value ever
enters a path where `sueStage == null`. Bit-perfect passthrough today is exactly the
`sueStage == null` case, so the property is preserved by construction as long as the
analysis result is a parameter *to* `SueBridge.nativeCreate` and never a reason *to call
it*. That invariant needs a test.

**Gapless.** Untouched, as long as nothing new executes inside `doEnqueueNext`.

### 2.6 Sampling bias — why Class I cannot be windowed

This is a correctness bug in the first draft's design, not a refinement.

**True peak is a maximum.** Sampling 15 s of a 240 s track observes ~6 % of the samples.
The maximum of a subsample is a biased-low estimator of the population maximum, and the
bias is not small: on a typical master the absolute peak occurs in one chorus or one
cymbal crash. Miss it and you overestimate available headroom.

Trace that through the actual chain. `extract_replaygain_db` computes
`gain = clamp(−20·log₁₀(peak) − 3.0, −6.0, 0.0)`, and that `gain` is Stage 1 of
`build_hires_remaster_chain` — the `volume=` node. Underestimated peak → overestimated
headroom → too little attenuation → the `compand` expansion stage pushes transients into
`alimiter` at `limit=0.95`, and the limiter absorbs what should have been headroom. The
audible result is limiter pumping on exactly the loudest moments of the track. **The one
parameter Step 3 exists to fix is the one that windowing gets wrong.**

**Integrated LUFS is gated over the whole program.** EBU R128's relative gate is computed
from the ungated mean of the entire file. Estimate it from three windows and the value
moves with where you sampled: land on a quiet intro and you underestimate loudness,
overestimate PLR, and skip expansion on a track that needed it. PLR = peak − loudness
inherits both biases, in the same direction, and PLR is the proposed gate for whether
Hi-Res engages at all.

**Recommended strategy: two passes, and the expensive one is partly free.**

- **Pass A (Class S, windowed, cheap).** 3–5 windows, ~150–400 ms/track. Can run
  opportunistically, including right after a track finishes playing. Feeds SUE (Step 6),
  which needs only stationary features.
- **Pass B (Class I, full-file, expensive).** Only required for tracks eligible for the
  Hi-Res path — lossless and not `isAlreadyNativeHiRes()`. That is a strict subset of the
  library, which cuts the 3.3–8.3 hour figure proportionally. Prioritise by what the user
  actually plays rather than walking the library in index order.
- **Pass B can be free on some paths.** When the track plays through the Kotlin write
  loop, every decoded sample already passes through `transportBuffers.pcmBuffer` in
  `writeLoopIteration` (`BitPerfectPlaybackEngine.kt:826-828`). A running max plus an
  R128 pre-filter over that buffer is ~4 ops/sample — negligible against the decode
  already happening. Measure on play N, persist at EOF, apply from play N+1. This
  respects the "no runtime parameter change" constraint exactly, because the measurement
  never feeds the graph it is measured under.

  **Caveat that limits this, and it is a real one:** on the pure bit-perfect libusb path
  Kotlin never sees a sample. `LibusbPcmAudioSink.write()` and `LibusbDsdAudioSink.write()`
  are literal no-ops (`override fun write(buffer, size): Int = size`,
  `LibusbPcmAudioSink.kt:209`, `LibusbDsdAudioSink.kt:523`), and `writeLoopIteration`
  early-returns for `LibusbOutputSink` into a 50 ms EOF poll
  (`BitPerfectPlaybackEngine.kt:812-820`) — the native pump in `decoder_to_ring_bridge.cpp`
  owns the data. Free measurement there would require instrumenting the native pump, i.e.
  adding work to the most sensitive path in the codebase. Do not do that. Accept that
  Pass B is free on `AudioTrackSink` and `LibusbPcmEnhancedSink`, and costs a full offline
  pass on the bit-perfect libusb path — which is also the path where the DSP is most often
  bypassed anyway, so the loss is smaller than it looks.

---

## 3. Proposed pipeline with insertion point

```
  +--------------- OFFLINE / BACKGROUND (@IoDispatcher, never the audio thread) -------+
  |                                                                                    |
  |   trigger: library index pass  --or--  post-playback opportunistic pass             |
  |                    |                                                                |
  |        +-----------v------------+                                                   |
  |        | TrackAnalysisScheduler |  skips: DSD, duration < 3 s, cache hit             |
  |        +-----------+------------+                                                   |
  |                    |                                                                |
  |        +-----------v------------------------------------------+                     |
  |        | own FFmpegDecoder instance (forcePcm = true)          |                     |
  |        | seek -> read N windows -> interleaved float32         |                     |
  |        +-----------+------------------------------------------+                     |
  |                    |                                                                |
  |        +-----------v--------------+        +--------------------------------+       |
  |        |  STEPS 2-3: MEASUREMENT  |        |  STEP 8 (optional, deferred):  |       |
  |        |  lavfi astats +          |        |  downmix mono -> 16 kHz ->     |       |
  |        |  aspectralstats +        |        |  LiteRT YAMNet -> aggregate    |       |
  |        |  ebur128                 |        |  -> speech/music/applause only |       |
  |        |  (already in the APK)    |        |  (+14 MB, new dependency)      |       |
  |        +-----------+--------------+        +---------------+----------------+       |
  |                    |                                        |                        |
  |                    +--------------+-------------------------+                        |
  |                                   v                                                  |
  |                    Room: track_analysis (new table, DB v14)                           |
  |                    key = contentHash(size + head/tail digest), NOT TrackEntity.id      |
  +-----------------------------------+--------------------------------------------------+
                                      |  pure cache read, resolved OFF the audio thread
                                      |  and passed in as a parameter
  ------------------------------------v---------------------------------------------------
                          BitPerfectSessionLoader.tryLoadBitPerfectSession(
                              path, sueEnabled, hiResEnabled,
                              analysis: TrackAnalysis?          <== NEW, nullable
                          )
                                      |
                          buildSueStageIfNeeded(...)
                                      |
                          SueBridge.nativeCreate(...)   <== INSERTION POINT
                                      |
                          sue_bridge.cpp: build_filter_chain / build_hires_remaster_chain
                          now parameterised by measured values instead of constants
                                      |
                          ====== everything downstream UNCHANGED ======
                          AudiophileOutputSink -> SpscRingBuffer -> ISO -> USB DAC
```

`analysis` is nullable and every consumer must behave exactly as today when it is `null`.
That is the whole compatibility story: no analysis, no change.

---

## 4. Parameter → source table

| DSP parameter | Location | Source today | Proposed source | Rationale |
|---|---|---|---|---|
| `volume=<gain>dB` (Hi-Res) | `sue_bridge.cpp:354` | `REPLAYGAIN_TRACK_PEAK` tag, else −3.0 | **measure** — true peak | Tag is absent on most rips; the blind −3.0 attenuates files that need no protection |
| `compand` points (Hi-Res) | `sue_bridge.cpp:363` | fixed curve | **measure** — PLR / crest | Expanding an 18 dB-PLR master is unrequested colouration; only loudness-war masters justify it |
| Hi-Res engage / bypass | `BitPerfectEnhancementPipeline.kt:60` | bit-depth + rate gate | **measure** — clipping ratio + PLR | An already-clipped master gets its distortion amplified |
| `exciter_freq` (SUE) | `sue_bridge.cpp:236-260` | bitrate bucket → assumed LAME cutoff | **measure** — real spectral cutoff | Wrong for transcodes, VBR, and non-LAME encoders |
| `exciter_amount` / `drive` | same | profile constant | **measure** — cutoff distance from 20 kHz | How much is missing is measurable; how much to add follows from it |
| `air_gain_10k` / `air_gain_14k` | same | profile constant | **measure** — spectral tilt | Boosting HF on an already-bright master overshoots the reference |
| `lowpass f` (19500 fixed) | `sue_bridge.cpp:124` | constant | **measure** — cutoff + margin | A 16 kHz-cutoff file gains nothing from a 19.5 kHz apodising filter |
| `stereotools slev` | `sue_bridge.cpp:497` | bitrate <= 128 heuristic | **measure** — M/S energy ratio, correlation | The actual question is "is the image collapsed", which is measurable |
| widening suppression on live/crowd | — | not implemented | **classify** | Applause is broadband and decorrelated; measurement mistakes it for a wide image |
| all-DSP bypass for spoken word | — | not implemented | **classify** | Audiobooks / podcasts are the one case where YAMNet clearly beats measurement |
| SUE on/off, Hi-Res on/off | Settings | **user** | **user** | Taste |
| Target sample rate | `PlaybackSampleRatePlanner.kt` | rate policy | unchanged | Not a quality knob |
| Everything on the DSD and bit-perfect paths | — | — | **nothing** | Non-negotiable |

Count the rows: **eight measurement-driven, two classifier-driven, and both classifier
rows are bypass predicates.**

---

## 5. Incremental plan

### 5.0 Not part of this plan — do it anyway, and first

**The FFmpeg build reproducibility problem is not a prerequisite of this feature. It is an
independent, urgent defect** and the first draft was wrong to file it as Step 0, which
implicitly made it this feature's problem to solve.

The `.so` files in `jniLibs/` are the only artifact that makes this project compile, and
no script in the repository can regenerate them: `install-ffmpeg-prebuilt.ps1` copies four
of the seven libraries, targets an ABI list that no longer matches
`app/build.gradle.kts:47`, and writes to a `prebuilt/` directory `CMakeLists.txt` no longer
reads. `jniLibs/` is not in `.gitignore`, so the binaries are committed — which is what has
been masking the problem. But a `git clean -xfd` on a tree where they were ever removed, a
fresh CI runner provisioned from a filtered checkout, or an FFmpeg CVE requiring a version
bump all land in the same place: **an unbuildable project with no path forward except
reconstructing the toolchain from a comment.**

Fix it now, independently of any decision about audio analysis: pin the
`ffmpeg-android-maker` invocation (version 7.1.4, the exact configure line from §1.1) in a
script or CI job that reproduces the current binaries, and repair or delete the existing
PowerShell script. While there, fix the `armeabi-v7a` claim at `docs/ai/native-audio.md:230`
and delete the dead `soxr_bridge.cpp` / `SoxResamplerStage` references at
`ffmpeg_bridge.cpp:71-74`.

*Verify: a clean clone with `jniLibs/` emptied rebuilds to byte-comparable libraries.*

### 5.1 The feature plan

Each step is independently shippable, independently verifiable, and independently
revertable. Steps 1–5 add **no new dependency and no APK growth**.

**Step 1 — a real cache key.**
Add `audioKey: String` to `TrackEntity` (DB v14): `fileSizeBytes` + a digest taken at
fixed fractional offsets (25 % and 75 % of the file), which are almost certainly inside the
audio payload. **Not** the first 64 KiB — that is where ID3v2 / Vorbis comments live, and
keying on it would invalidate the analysis every time the user fixes a spelling or adds
artwork. The purpose is invalidation correctness (§1.7), not collision avoidance.
*Verify: re-index a library and confirm keys are unchanged; rewrite a track's tags and
confirm the key is unchanged; re-encode a track and confirm the key changes.*

**Step 2 — Class S measurement, windowed, no DSP change (the honest baseline).**
New `data/analysis/` component + one JNI entry point running a measurement-only lavfi graph
(`aspectralstats,astats` with `metadata=1`, read back from frame metadata) over 3–5 sampled
windows. Persist to a new `track_analysis` table. **Change no DSP parameter.** Surface the
values in the existing telemetry dialog.
*Verify: measured cutoff of a known 128 kbps LAME file lands near 17.2 kHz.*

**Step 3 — Class I measurement, full-file, still no DSP change.**
`ebur128` + `astats` over every sample. Ships first in its **free form**: accumulate
running peak and R128 loudness inside `writeLoopIteration` for tracks playing through
`AudioTrackSink` / `LibusbPcmEnhancedSink`, persist at EOF, and only commit the result when
sample coverage is ≥ 95 % contiguous (a seek-heavy listen must not commit a partial
measurement). Offline full-file analysis for the libusb-pump path is deferred to Step 7.
*Verify: the measured true peak matches `REPLAYGAIN_TRACK_PEAK` on files that carry the
tag — a free ground-truth oracle, and the check that proves windowing was biased. Confirm
a windowed estimate on the same files is systematically lower.*

**Step 4 — split `sue_bridge.cpp` and de-positionalise the JNI boundary.**
*This step was missing from the first draft, which asserted the split was mandatory in §6.8
and then proposed adding a fourteenth positional parameter in the next step.*

Split the file's three unrelated engines into three graph builders behind three entry
points (`nativeCreateSue`, `nativeCreateHiRes`, `nativeCreateResampler`), retiring the
boolean mode routing at `sue_bridge.cpp:721-731`. Replace the thirteen positional
parameters of `nativeCreate` with a single descriptor object marshalled once — a Kotlin
data class mapped to a C struct, or discrete setters on the handle before a `configure()`
call. Pure refactor: **no behavioural change, byte-identical `filter_str` output.**
*Verify: log the generated `filter_str` for a corpus of tracks before and after; require
exact string equality.*

**Step 5 — wire measurements into Hi-Res Dynamic Remaster only.**
Replace the tag-or-−3.0 gain with measured true peak. Make the `compand` curve a function
of measured PLR, and bypass expansion above ~14 dB PLR. Behind a setting, defaulting off
until A/B'd. Highest quality gain per line of code in the whole plan. Depends on Step 3
(Class I) and Step 4 (somewhere to put the new inputs).
*Verify: a hot 2010 master and a 1975 jazz master now receive visibly different graphs.*

**Step 6 — wire measurements into SUE.**
Drive `exciter_freq`, `amount`, `drive`, `air_gain_*` and the apodising lowpass from
measured cutoff and tilt (Class S only — SUE needs no integral features). **Collapse
`PROFILE_MATRIX` to one source of truth** in the same step: the C++ table at
`sue_bridge.cpp:161` and the Kotlin mirror at `SueProfileResolver.kt:83` must not both
survive contact with a new input.
*Verify: a 320 kbps transcode of a 128 kbps source stops resolving to `BYPASS`.*

**Step 7 — background scheduling and Class I backfill.**
Add `androidx.work`, analyse on charge + battery-not-low, resumable with progress, hooked
into the index pass. This is what makes the 3.3–8.3 hour Class I figure (§2.5) tractable
and what covers the libusb-pump path that Step 3 cannot measure for free. First step that
adds a dependency.
*Verify: a 1 000-track library backfills on charge without thermal throttling; the job
resumes correctly after process death.*

**Step 8 — classifier, if and only if Steps 2–7 shipped and a gap remains.**
See §7 and §8. If it happens at all it is Silero VAD (~1.8 MB, MIT) for the spoken-word
bypass, not YAMNet for genre. Ship behind a setting.
*Verify: the falsification criteria in §8.3 have actually been met by observation.*

---

## 6. Why this might not be worth doing

Written to be uncomfortable, because that is the useful version.

**The classifier specifically:**

1. **It answers a question the DSP does not ask.** Eight of the ten parameter rows in
   §4 are measurement-driven. The two classifier rows are bypass predicates, not
   parameter selectors. You would be shipping 14 MB and a new ML runtime to turn two
   features *off* more accurately.
2. **YAMNet on an all-music library is close to a constant function.** Top-1 will be
   `Music`. The genre sub-classes are AudioSet's weakest labels. If you actually want
   genre, YAMNet's 1024-d embedding plus a small trained head is the technique — and you
   have no labelled data, which turns a two-week feature into a data-collection project.
3. **Steps 2–6 capture most of the available quality gain at roughly 2 % of the cost.**
   No new dependency, no APK growth, no licensing question, filters already compiled into
   the binary you ship today. If you build Step 2 and the measured values turn out to
   change nothing audible, that is a cheap and decisive answer — and it is also the
   experiment that tells you whether Step 8 was ever going to matter.
4. **The audience is narrow.** `EngineType.STANDARD` is the default. The user must enable
   the audiophile engine *and* SUE or Hi-Res *and* be playing a source that passes the
   gates. Everyone else carries the 14 MB for nothing.
5. **On the content where a classifier sounds most compelling — long mixes, live sets,
   compilations — the "one stable label per file" premise is exactly wrong**, and your own
   constraint #3 forbids the only correct response.

**The whole idea, including the measurement half:**

6. **These two engines are colouration features by construction.** SUE adds harmonics
   that were never recorded; Hi-Res Remaster expands dynamics the mastering engineer
   chose to compress. Better parameters make a more *defensible* colouration, not a more
   faithful reproduction. In an app whose central claim is bit-perfect playback, the
   highest-integrity option for both is `BYPASS` — and the codebase already knows this,
   which is why the SUE matrix is full of `BYPASS` cells and why the profile parameters
   have been walked downward twice (visible in the "the previous curve…" comments at
   `sue_bridge.cpp:335` and `:296`).
7. **There is unglamorous work ahead of this in the queue.** The native build is not
   reproducible from a clean checkout (§1.1). The release APK is 103 MiB with R8 off and
   ships video decoders. A decision table is hand-duplicated across two languages. DSD
   track identity is a 31-bit string hash. Adding an ML pipeline on top of that is
   building the second floor before the foundation is poured.
8. **`sue_bridge.cpp` should be split before anything new is added to it.** One
   1 172-line file owns three unrelated engines — SUE, Hi-Res Remaster, and the force-48k
   resampler — behind a single `nativeCreate` with **thirteen** parameters and boolean
   mode routing (`sue_bridge.cpp:721-731`). Adding an analysis struct as parameter
   fourteen is how that file becomes permanently unmaintainable. Split it into three
   graph builders with three entry points first; it is a mechanical refactor and it makes
   every step above smaller.

**The strongest case for proceeding**, stated fairly: `extract_replaygain_db` shows the
architecture already accepts a per-track measured parameter and threads it cleanly from
`ffmpeg_bridge` → Kotlin → `SueBridge.nativeCreate` → the lavfi string. Steps 2–6
generalise a path that already exists and works. That is a low-risk, high-confidence
change. Step 8 is a different kind of bet entirely, and it should be evaluated on its own
evidence after Steps 2–6 have produced some.

---

## 7. Steelman — is there a design where classification is load-bearing?

### 7.0 The methodological objection, conceded in form

The first draft evaluated the classifier against the parameter set that exists. That test
is partly circular: `exciter_freq`, `air_gain_*`, `slev` and the `compand` points were
designed by someone who did not have a classifier, so of course none of them consumes one.
"No current knob wants a category" is weak evidence for "no knob could."

The objection has a limit, though, and it is worth stating before the steelman rather than
after it. The DSP chain of a player is not an open design space. It can only apply signal
transformations to a stereo PCM stream, and the quality criterion for choosing their
parameters is defined over the signal and the listener. That constrains what a semantic
category can contribute far more than the GPU analogy suggests — a GPU adds a capability
that single-threaded code lacks, whereas a classifier is itself a function of the same
signal the measurements read. Whether that constraint is fatal is exactly what §7 tests
and §8 answers.

**Note on figures.** Model sizes and inference times below are from memory and are
approximate. Every one of them must be verified against the actual artifact before it is
used to justify a decision. They are good enough to separate "plausible" from "off by an
order of magnitude", which is all they are used for here.

### 7.1 Genre-conditional tonal targets (EQ matching)

**What it solves.** Move a track's long-term average spectrum toward a reference curve
typical of its genre — the logic behind mastering assistants (iZotope Ozone Master
Assistant, LANDR). Note the premise names Sonarworks, which is a different thing: that
corrects the *transducer*, using a measured target for a specific headphone. It is a
fidelity tool. Genre targets are not.

**This is genuinely the only candidate in the whole list that is a function of genre and
not of the signal.** So structurally it is the strongest pro-classifier case, and it
deserves the most careful kill.

Three problems, in ascending order of severity.

*Third: it inverts the app's premise.* A genre target deliberately moves the signal away
from what the mastering engineer chose, toward a population mean. Every other stage in
this app is defended as repairing damage (lost bandwidth, lost headroom). This one has no
damage to point at — a master that deviates from its genre's mean is not defective, and
the deviation is frequently the artistic point. This is an aesthetic objection, not a
technical one, but it is the app's stated identity and it should not be waved through.

*Second: the statistics probably do not support it.* The published work on spectral
characteristics of commercial recordings that I am aware of finds broadly consistent
spectral tilt across popular music, with genre differences of a few dB in specific bands
and **within-genre spread comparable to or larger than between-genre spread**. Where a
target is that noisy, applying the genre mean to an individual track is wrong in magnitude
more often than it is right. The dominant covariates of spectral balance are era and
mastering loudness, not genre — and both are measurable.

*First, and decisive: the classifier is not the cheapest source of the label.*
`TrackEntity.genre` already exists in the database, populated from file tags by the scanner.
For any decision that is genuinely genre-conditional, **a tag written by the label or the
user is better ground truth than YAMNet's inference over 0.96 s patches.** The classifier's
marginal value collapses to the untagged subset of the library. And the version of this
feature that actually works commercially — Ozone's Match EQ — is *reference matching*: the
user picks a track whose balance they want, and the EQ matches it. That needs no genre and
no classifier at all.

**Cost to build the curves.** A licensed corpus with reliable genre labels, LTAS computed
per track and averaged per genre. The computation is trivial; the corpus is the problem,
and it is not a technical problem. Plus a matching-EQ stage that does not exist in the app
today — this is a new DSP stage, not a new parameter for an existing one.

**Verdict: does not survive.** Not because the idea is incoherent — it is the most coherent
one here — but because the label is already in the database, the target is too noisy to be
worth hitting, and the useful variant needs no model.

### 7.2 Defect classification instead of genre

**What it solves.** Distinguish digital clipping / tape saturation / codec artifacts /
vinyl rip / analogue noise floor. The premise is right that these want opposite treatments
and right that they are the highest-value question in the whole document. The premise is
wrong that measurement struggles — it picks the one statistic that does not discriminate.

Crest factor genuinely cannot separate a saturated tape master from a clipped digital one.
But these are not the discriminating features:

| Defect | Discriminating measurement | Already available? |
|---|---|---|
| Digital clipping | **Run-length histogram of consecutive identical near-full-scale samples.** Hard clipping is flat-topped; nothing else is. Plus broadband odd-harmonic splatter with *inharmonic* alias products folded below Nyquist. | `astats` Flat_factor / Peak_count, plus one FFT |
| Tape saturation | Soft compressive curve, third-harmonic dominant and *harmonic* (no aliasing), level-dependent HF loss, and the tell nothing else has: **wow and flutter** — 0.5–6 Hz pitch modulation, visible as sideband jitter on sustained tones | FFT + tracking a stable partial |
| Vinyl rip | **Inter-channel correlation → ~1.0 below ~150 Hz** (elliptical EQ / mono bass, mandatory to keep the stylus in the groove), sub-30 Hz rumble, impulsive crackle with high short-time kurtosis, groove-echo pre-echo | `astats` on M/S, banded correlation |
| Codec artifacts | Spectral cutoff at a bin boundary with a hard zero above it; transcode-from-lossy shows the cutoff *and* a lossless container | already in Step 2 + `nativeGetCodecName` |
| Analogue noise floor | Level and spectral shape in `silencedetect`-identified quiet passages | `astats` + `silencedetect` |

Every one of these is a low-order statistic of the waveform, computable with filters
already in the shipped binary. The reason they look hard is that crest factor is the wrong
place to look.

**Models.** There is no pretrained, permissively licensed "audio defect classifier". You
would build the dataset. And the ground truth is the problem: nobody labels commercial
masters as "tape-saturated" vs "digitally clipped", so you would synthesise the labels by
applying clipping and saturation models to clean audio. A model trained on synthetic
clipping generalises poorly to real mastering chains, which apply limiting, saturation and
clipping in series through analogue and digital stages. You would be training on a
distribution you invented.

**Verdict: does not survive, and it is the clearest loss for ML in the list.** The task is
real and valuable; measurement wins it outright; the ML route needs data that does not
exist.

### 7.3 Embeddings + nearest neighbour against a hand-annotated reference set

The most technically serious proposal, and it fails for a specific and interesting reason.

**The training objective is adversarial to the target.** YAMNet's 1024-d penultimate layer
is optimised for AudioSet event classification. A network that must answer "is there a
guitar" is trained to be *invariant* to level, EQ and dynamics — because a guitar
compressed, EQ'd or quiet is still a guitar. Mastering style **is** level, EQ and dynamics.
The embedding is, by construction, trained to discard precisely the information you want to
retrieve. This is not a claim that it discards all of it — invariance is never perfect —
but you would be mining a representation for its residual leakage rather than reading a
representation built for the job.

**The geometry is also against you.** A few hundred reference points in 1024-d is deep in
the distance-concentration regime: nearest and farthest neighbour distances converge, and
kNN degrades toward returning arbitrary points. Mitigable with PCA to ~20–50 dimensions or
a learned metric (triplet loss on your annotations, which a few hundred triplets can fit) —
but a projection can only surface information the embedding retained. If the invariance
did its job, no projection recovers it.

**How small can the reference set be?** Wrong question, and this is the crux. The binding
constraint is not sample count but whether the metric aligns with the concept. If it does,
a few hundred anchors is plenty for a coarse 5–10 class distinction. If it does not, ten
thousand will not help.

**And then the retrieved concept collapses.** Suppose it works and the nearest neighbour
returns "reference #47, annotated *hot 2010 master*". The DSP action is to apply the hot-
master treatment. But "hot master" is defined by PLR — which Step 3 measures directly, with
no model, exactly. The kNN result is a noisy estimator of a quantity you can compute.

**Unless the annotation is not a property of the signal.** If you annotate *"tracks like
this should get treatment X"* — a preference, not a description — then the target variable
is genuinely not a function of the signal, because it depends on the listener. See §8.2.

**Alternatives with better-aligned objectives** (CLAP-family audio encoders, contrastive
models trained with production-related text) are in the 150–200 MB range and blow the
budget by an order of magnitude. Licenses vary by component and would need checking.

**Verdict: does not survive as a content model. Survives as a preference model**, which is
a different feature — §8.2.

### 7.4 Voice detection / transient density for de-esser, presence, `compand` timing

Asked for an honest accounting of what ML actually adds here. The honest answer is: less
than it looks, but not nothing, and the residual is worth taking.

*Available without ML, all mature and cheap:*

- **Transient density** — spectral-flux or complex-domain onset detection, then onsets per
  second. Standard, decades old, no model.
- **`compand` attack/release** — derived from inter-onset-interval statistics. A dense
  percussive track and a sustained ambient one produce obviously different IOI
  distributions.
- **Sibilance detection** — 5–10 kHz band energy relative to broadband, short-time. This is
  literally how every de-esser's detector works.

*The genuine ML edge:* classical VAD (energy + zero-crossing) is useless on music, because
music is not silence. Deciding "is there a human voice in this mix" versus "is there a
saxophone" is hard by measurement.

*But the DSP does not need to know that.* A de-esser should trigger on measured sibilance,
not on "voice detected" — measurement is the more direct and more correct trigger. Gating
de-essing on a voice classifier would make it *worse*, because it would skip sibilant
content that is not voice and fire on voices with no sibilance problem.

**The one place the model wins is the bypass predicate**, and here there is a concrete
improvement over the first draft's recommendation. For "is this spoken word rather than
music", **Silero VAD** — MIT licence, roughly 1.8 MB, sub-millisecond per 30 ms chunk — does
the job at about **one eighth the size of YAMNet**, and it is the right tool for it. If any
model ships, it should be this one, not YAMNet.

**Verdict: mostly does not survive; a small, well-scoped piece does.** And it corrects the
first draft: the recommended model for the bypass predicates is Silero VAD, ~1.8 MB, not
YAMNet at 14 MB.

### 7.5 Stem separation

The numbers, since they were asked for rather than a hand-wave.

| Model | Licence | Approx. size | Approx. speed |
|---|---|---|---|
| Demucs v4 (`htdemucs`) | MIT | ~80 MB single model; the bag-of-models variant is several hundred MB | Slower than realtime on mobile ARM CPU — expect a 4-minute track to take minutes, not seconds |
| Spleeter | MIT | ~75 MB (2-stem), ~150 MB+ (4-stem) | Faster (spectrogram U-Net), perhaps 1–3x realtime on mobile; TF1 SavedModel, non-trivial to convert to LiteRT |
| Open-Unmix | MIT | ~34 MB per target, ~137 MB for four | Between the two |

Licences are fine. Size is 5–10x the entire YAMNet budget. Speed is 2–3 orders of magnitude
worse than any other option here.

**But the decisive objection is structural, not budgetary.** Everything else in this
document produces a *number* that is cached and reused for free forever. Stem separation
produces *audio*. To use separated stems in playback you must either store them — 4x the
library size — or separate in real time, which is impossible by the numbers above. And
separation artifacts (spectral leakage, phase smearing in the residual) would be baked into
the output of an application whose central claim is bit-perfect reproduction. You would add
far more distortion than every enhancement stage combined removes.

There is one legitimate narrow use: separate **offline, measure per-stem properties, throw
the stems away** — "vocal crest factor", "bass level relative to mix". Separation quality
matters much less for measurement than for listening. But the compute cost stands, for
measurements no current DSP stage consumes.

**Verdict: does not survive.** Correctly anticipated as out of budget, and the reason is
worse than budget.

### 7.6 Two more paths worth naming

**(6) No-reference perceptual quality prediction.** The natural "just predict how damaged
this file is" idea. It fails structurally: the good objective metrics (ViSQOL, Apache-2.0;
PEAQ) are **full-reference** — they need the undamaged original, which by definition you do
not have for a lossy file. No-reference music quality models are research-grade, mostly
trained on speech or on codec-artifact detection at bitrates far below what this app sees.
Worth naming precisely because it sounds like the ideal fit and is ruled out by a fact
about the problem, not about the models.

**(7) Learned parameter regression.** Train audio → DSP parameters end to end. Requires
ground-truth optimal parameters, which requires listening tests at scale. No such dataset
exists and you cannot bootstrap one from a few hundred annotations. Dead on data.

### 7.7 Steelman summary

| Path | Load-bearing use of category? | Killed by | Survives? |
|---|---|---|---|
| 1. Genre tonal targets | **Yes** — the only true one | Genre tag already in DB is better ground truth; within-genre LTAS spread ≈ between-genre; contradicts fidelity premise; useful variant is reference matching, needs no model | No |
| 2. Defect classification | Yes | Measurement wins outright once you use flat-top run length, alias inharmonicity, LF correlation, wow/flutter instead of crest factor; no pretrained model; ground truth would have to be synthesised | No |
| 3. Embedding + kNN | Yes | YAMNet is trained to be invariant to mastering style; distance concentration at n≈300 in 1024-d; retrieved concept collapses to a measurable one | Not as a content model |
| 4. Voice / transients | Partly | Onset detection and band-energy give most of it; de-esser should trigger on measured sibilance, not on a category | **Small piece survives** — Silero VAD, ~1.8 MB MIT, for the spoken-word bypass |
| 5. Stem separation | Yes | 80–150 MB, slower than realtime, and it outputs audio rather than a cached number — 4x storage or impossible | No |
| 6. No-reference quality | Yes | Good metrics are full-reference; you have no reference | No |
| 7. Parameter regression | Yes | No ground-truth dataset, cannot be bootstrapped | No |

---

## 8. Verdict

### 8.1 The position: (B), structurally — with one carve-out that is a different feature

The structural argument, stated so it can be attacked.

**Premise 1 — the bottleneck.** A classifier is itself a function of the signal:
`x → C(x) → θ`. Any parameter that is a deterministic function of signal properties gains
nothing from routing through a category, because the category is computed from the same
signal and is a lower-dimensional summary of it. In information terms, conditioning on `x`
makes `C(x)` uninformative about anything `x` determines.

**Premise 2 — where that argument is weak, and the honest form of it.** Premise 1 does not
prove measurement wins, because a learned estimator can be a *better estimator* of a
complex function of `x` than a hand-designed feature. Category-as-bottleneck is not fatal
when the target function is hard to compute analytically. So the real question is not
"does the category add information" but **"is the parameter-determining function complex
enough that a learned estimator beats a measured one?"**

**Premise 3 — and here the answer is no, for this specific DSP chain.** Every parameter in
`build_filter_chain` and `build_hires_remaster_chain` is a low-order function of a
well-understood physical quantity with an exact estimator:

| Parameter | Determining quantity | Estimator |
|---|---|---|
| `exciter_freq`, `lowpass f` | spectral cutoff | rolloff of the magnitude spectrum — exact |
| `volume` | true peak | maximum over samples — exact |
| `compand` slope | PLR | peak − integrated loudness — exact, standardised (EBU R128) |
| `slev` | image width | mid/side energy ratio — exact |
| Hi-Res bypass | clipping | flat-top run length — exact |

These are textbook cases where DSP measurement is exact and machine learning is an
approximation of an exactly computable quantity. **This is the structural claim: not that
category is uninformative in general, but that this chain's decision functions are all in
the class where measurement dominates learning.** It is contingent on the chain, not on
YAMNet, and it would fail the moment the chain grew a stage whose parameter is not
analytically derivable.

**Corollary that makes the position falsifiable rather than rhetorical.** The claim is
equivalent to: *measured features are a sufficient statistic for the DSP decision.* That is
a testable proposition — see §8.3.

### 8.2 What survives, stated plainly

Two things, and neither is the proposed feature.

**(i) A bypass predicate, from a 1.8 MB model, not a 14 MB one.** Spoken word / audiobook
detection. Silero VAD, MIT, ~1.8 MB. This is Step 8 and it is optional. It corrects the
first draft, which recommended YAMNet for the same job at eight times the size.

**(ii) Preference modelling — the one design where classification is genuinely
load-bearing, and it is a different feature.** The structural argument in §8.1 says the DSP
decision is determined by the signal. That is true only while the objective is *"what does
this recording need"*. If the objective becomes *"what does this listener want"*, the target
variable is no longer a function of the signal alone — it depends on the listener, and no
measurement of `x` can recover it. That is genuinely conditionally independent information,
which is exactly the bar set for a verdict of (A).

In that framing a design does exist: user annotates or rates treatment preference per
track → learn a mapping from an audio representation to preferred treatment → generalise to
unheard tracks. Classification is load-bearing because preference is not measurable from
the signal.

**Why it is still not recommended here.** It requires a rating/annotation UI that does not
exist; enough labelled tracks (realistically 50–200) to generalise; and it turns a fidelity
feature into a personalisation feature, which is a product decision far outside the scope of
this document. It is also the point at which YAMNet's embedding might legitimately be the
right representation, since preference plausibly correlates with instrumentation and content
type — the things YAMNet *does* encode.

**So: (B) for the app as it is defined today. (A) becomes available if and only if the goal
changes from repairing recordings to matching a listener.** That is a clean line and it is
not contingent on any model's training quality.

### 8.3 Falsification criteria — what would change this verdict

Concrete observations, in rough order of how likely they are to be met.

1. **The sufficiency test (the direct one).** After Steps 2–3, take pairs of tracks whose
   measured features (cutoff, tilt, M/S ratio, PLR, true peak, clipping ratio) agree within
   tolerance, and blind-A/B them for whether they want the same DSP treatment. **If such
   pairs are common and disagree on treatment, measured features are not a sufficient
   statistic and §8.1 is falsified** — something outside the measurement vector carries the
   residual, and category is a candidate. If pairs that measure alike also sound alike under
   the same treatment, the verdict holds. This test is cheap and it is the reason Step 2
   exists as a no-DSP-change baseline.
2. **A new stage with a non-analytic parameter.** If the chain grows a de-esser whose
   threshold should track perceived annoyance, or any stage whose optimum is not a closed-
   form function of a measurable quantity, Premise 3 no longer covers the chain and the
   argument must be re-run for that stage.
3. **The LTAS test for §7.1, runnable today with no ML at all.** Compute the long-term
   average spectrum for the library grouped by the existing `TrackEntity.genre` tag. If
   between-genre variance clearly dominates within-genre variance, genre-conditional tonal
   targets are real — and the next question becomes whether tags cover enough of the library,
   not whether to ship a classifier.
4. **A better-aligned embedding.** A permissively licensed model under ~20 MB trained to
   *preserve* production and mastering characteristics rather than be invariant to them
   would materially weaken §7.3, because the representation would no longer be adversarial
   to the target.
5. **A product pivot toward personalisation.** Per-track preference memory, a rating UI, or
   "learn what I like" as a feature goal flips the verdict to (A) via §8.2 immediately, with
   no new evidence required.

Criterion 1 is the one that matters. It is the experiment that would tell you the first
draft's verdict was wrong for a real reason rather than a rhetorical one, and Steps 2–3 are
designed to make it cheap to run.
