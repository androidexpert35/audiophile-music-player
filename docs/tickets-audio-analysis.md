# Implementation tickets — measured-signal analysis for the DSP chain

> Derived from [`feasibility-audio-classifier.md`](feasibility-audio-classifier.md) §5.
> Each ticket is a self-contained prompt: copy the **Prompt** block into a fresh session.
> Read the linked module docs before writing code — that is a repository rule, not advice.

---

## How to use this file

- Tickets are ordered by dependency. Do not start one whose blockers are open.
- **AUD-00** is independent of the feature and should be done first regardless.
- **AUD-30** is a decision gate, not a code ticket. Everything after it is conditional on
  its outcome.
- Every prompt assumes the working directory is the repository root.

### Constraints that apply to every ticket

Paste these into any session that does not already have them:

```
Repository rules that override defaults (see AGENTS.md and docs/ai/):
- Layers: Presentation -> Domain -> Data. Domain is pure Kotlin. Framework types
  (Cursor, Uri, Player, MediaItem, UsbDevice, JNI handles) never leave Data.
- No `!!`, no GlobalScope, no hardcoded Dispatchers.IO (inject @IoDispatcher).
- Compose only, no XML layouts. One public type per file. Complete KDoc whose first
  line states business purpose, not the symbol name.
- Use cases have no @Inject; they are wired in di/UseCaseModule.kt.
- Every register*/unregister* callback API must be wrapped in a callbackFlow.
- NEVER add work to the audio HandlerThread in BitPerfectPlaybackEngine
  (created at BitPerfectPlaybackEngine.kt:108, Process.THREAD_PRIORITY_AUDIO).
  Every method posting through its Handler runs there, including doLoadTrack (:481)
  and doEnqueueNext (:602). The native SPSC ring downstream is 128 KB, about 43 ms
  at 192 kHz/32-bit stereo (usb_audio_bridge.cpp:1025). There is no slack.
- Do not degrade bit-perfect or DSD playback. Do not add resampling, gain, dithering
  or format coercion to any path that does not already have it.
- When you change native code or playback behaviour, update docs/ai/native-audio.md
  and docs/ai/playback.md in the same change.

Build commands (Windows): .\gradlew.bat :app:compileDebugKotlin
                          .\gradlew.bat :app:testDebugUnitTest --tests "*SomeTest"
A full :app:assembleDebug also runs the CMake/FFmpeg build and is slow — only run it
when native code changed.
```

### Dependency graph

```
AUD-00  (independent, do first)
AUD-01 ──┬─ AUD-10 ─┬─ AUD-12 ── AUD-13 ──┐
         │          │                      ├── AUD-30 (gate) ──┬── AUD-40 ── AUD-41
         └─ AUD-11 ─┘                      │                    ├── AUD-42
                       AUD-20 ── AUD-21 ───┘                    ├── AUD-50
                                                                └── AUD-60 (optional)
AUD-31 (refactor) ── required by AUD-40 and AUD-42
```

---

## AUD-00 — Make the FFmpeg native build reproducible

**Blocks:** nothing. **Blocked by:** nothing. **Priority: highest, independent of this feature.**

**Why.** `app/src/main/jniLibs/<ABI>/*.so` are the only existing copies of the native
libraries and no script in the repository can regenerate them. `install-ffmpeg-prebuilt.ps1`
copies 4 of the 7 shipped libraries (line 37), targets an ABI list that no longer matches
`app/build.gradle.kts:47` (line 30), and writes to `app/src/main/cpp/prebuilt/` which
`CMakeLists.txt` no longer reads. The binaries are committed, which has masked this. A
`git clean -xfd` on a tree where they were removed, a filtered CI checkout, or an FFmpeg CVE
requiring a version bump all end in an unbuildable project.

**Prompt**

```
Make the FFmpeg native build reproducible from a clean checkout.

Current state, verified:
- jniLibs/<ABI>/ contains: libavcodec, libavformat, libavfilter, libavutil,
  libswresample, libswscale, libavdevice, libusb1.0 (arm64-v8a and x86_64).
- CMakeLists.txt links avformat avcodec avfilter swresample avutil + libusb.
- The shipped build is FFmpeg 7.1.4, produced by ffmpeg-android-maker with NDK r29.
  Its exact configure line, recovered from the libavutil.so string table, is:
    --enable-cross-compile --target-os=android --enable-shared --disable-static
    --disable-vulkan --enable-libsoxr
  (plus the usual --prefix/--sysroot/--cc/--extra-* toolchain flags)
- scripts/install-ffmpeg-prebuilt.ps1 is stale: 4 libs instead of 7, ABI list
  ('arm64-v8a','armeabi-v7a','x86_64') instead of the two in app/build.gradle.kts:47,
  and it installs into cpp/prebuilt/ which CMake does not read.

Do:
1. Replace or repair the script so it provisions exactly the libraries CMake links
   plus the ones packaged, for exactly the ABIs in app/build.gradle.kts, into
   app/src/main/jniLibs/<ABI>/ only. Delete the cpp/prebuilt/ code path if nothing
   reads it.
2. Record the pinned FFmpeg version and the full configure line in a checked-in file
   (a script, or scripts/ffmpeg-build.md) so the current binaries can be reproduced.
3. Decide and document whether libswscale.so and libavdevice.so should still be
   shipped — nothing in the codebase loads them and they cost 1.2 MB per ABI.
   Do not remove them in this ticket; just record the finding.
4. Fix docs/ai/native-audio.md:230, which claims armeabi-v7a is shipped. It is not.
5. Delete the dead references to "soxr_bridge.cpp / SoxResamplerStage" at
   ffmpeg_bridge.cpp:71-74 and anywhere else they appear. No such file or class exists.

Do not change any C++ or Kotlin behaviour in this ticket.
```

**Done when**
- [ ] A clean clone with `jniLibs/` emptied can be re-provisioned by a documented command.
- [ ] The FFmpeg version and configure line are checked in.
- [ ] `docs/ai/native-audio.md` no longer claims `armeabi-v7a`.
- [ ] No references to `soxr_bridge.cpp` / `SoxResamplerStage` remain.

---

## AUD-01 — Stable audio key on `TrackEntity` (Room v14)

**Blocks:** AUD-10, AUD-11. **Blocked by:** nothing.

**Why.** An analysis result must be invalidated when the audio changes and retained when it
does not. `TrackEntity.id` cannot express either: a MediaStore delete + re-add mints a new id
for identical audio, and a file replaced in place keeps its id. (The 31-bit DSD hash at
`DsdFileScanner.kt:207` is a separate and much smaller issue — see feasibility §1.7; it is
not the reason for this ticket.)

**Prompt**

```
Add a stable audio-content key to the track index so a cached per-track analysis can be
invalidated correctly.

Add `audioKey: String` to TrackEntity (app/src/main/java/.../data/local/entity/TrackEntity.kt)
and migrate AudiophileDatabase from version 13 to 14 (AudiophileDatabase.kt:76, follow the
existing MIGRATION_n_n+1 pattern exactly — there are 12 prior examples).

Key derivation, and the reasoning matters here:
- Use fileSizeBytes + a digest computed at FIXED FRACTIONAL OFFSETS into the file
  (25% and 75%), reading a small window at each.
- Do NOT digest the first N KiB. That is where ID3v2 and Vorbis comments live, so a tag
  edit (adding artwork, fixing a spelling) would invalidate an analysis of audio that
  never changed. Offsets at 25%/75% are almost certainly inside the audio payload.
- The key must be identical across a re-index, and across a MediaStore delete + re-add
  of the same file.

Populate it in the scanners (data/scanner/): MediaStoreScanner and DsdFileScanner.
Compute it on @IoDispatcher. Handle unreadable files by leaving audioKey empty rather
than failing the scan — an empty key means "not analysable", not an error.

Add unit tests: same file content -> same key; tag rewrite -> same key; different audio
-> different key; unreadable file -> empty key, scan continues.

Update docs/ai/data.md with the new column and the migration.
```

**Done when**
- [ ] Room v14 migration exists and follows the established pattern.
- [ ] Re-index produces identical keys; a tag rewrite does not change the key.
- [ ] Scan does not fail on unreadable files.
- [ ] `docs/ai/data.md` updated.

---

## AUD-10 — Native measurement bridge: Class S (stationary features)

**Blocks:** AUD-12. **Blocked by:** AUD-01.

**Why.** The stationary features (real spectral cutoff, tilt, stereo width) are what SUE
actually needs, and `aspectralstats` / `astats` are already compiled into the shipped
`libavfilter.so`. This ticket adds the measurement path and nothing else.

**Prompt**

```
Add a native, measurement-only lavfi bridge that reports stationary signal features for a
window of PCM. Read docs/ai/native-audio.md first.

New files (do NOT put this in sue_bridge.cpp — that file is already overloaded and is
being split in a later ticket):
  app/src/main/cpp/audio_analysis_bridge.cpp
  app/src/main/java/.../data/playback/analysis/AudioAnalysisBridge.kt

Add the .cpp to AUDIPHILE_TRANSPORT_SOURCES or the FFMPEG_AVAILABLE branch in
app/src/main/cpp/CMakeLists.txt as appropriate, and provide a neutral stub behaviour when
FFmpeg is not provisioned (mirror how sue_bridge handles the stub build: return a failure
sentinel, never crash).

Build a lavfi graph of the shape:
  abuffer -> aformat=sample_fmts=flt -> aspectralstats(metadata) -> astats(metadata)
          -> abuffersink
Read the results from the OUTPUT FRAME METADATA dictionary (av_buffersink_get_frame, then
av_dict_get on frame->metadata for the lavfi.aspectralstats.* / lavfi.astats.* keys).

IMPORTANT: verify the exact filter option names and metadata key names against the FFmpeg
7.1.4 build actually shipped in jniLibs/ before relying on them. Do not assume option
syntax from memory or from other FFmpeg versions. If a needed statistic is not exposed as
metadata by this build, say so and stop rather than inventing a workaround.

Features to return (stationary only — no peak, no loudness, no clipping counts; those are
integral measures and belong to a different ticket):
  - spectral rolloff / cutoff estimate (the frequency above which there is no meaningful
    energy)
  - spectral centroid and slope (tilt)
  - per-channel and mid/side energy, and inter-channel correlation
  - noise floor estimate
  - DC offset

Kotlin surface: one class, handle-based like FFmpegDecoder (open -> feed N windows ->
read aggregate -> close), single-owner threading, KDoc stating that all calls on a handle
must come from one thread. Return an immutable data class.

This ticket changes NO DSP behaviour. Nothing consumes the output yet.

Add host-side C++ tests under app/src/main/cpp/tests/ for any pure logic (aggregation
across windows, parsing), following the existing native_core_tests.cpp pattern.
Update docs/ai/native-audio.md with the new source file and JNI entry points.
```

**Done when**
- [ ] Filter option and metadata key names verified against the shipped 7.1.4 build.
- [ ] Stub build still assembles and returns a failure sentinel.
- [ ] Host-side tests for aggregation logic pass.
- [ ] `docs/ai/native-audio.md` lists the new file and entry points.

---

## AUD-11 — `track_analysis` table, DAO and repository

**Blocks:** AUD-12. **Blocked by:** AUD-01.

**Prompt**

```
Add persistence for per-track signal analysis.

New Room entity `TrackAnalysisEntity` in data/local/entity/, table `track_analysis`,
primary key = audioKey (String, from AUD-01). Migrate the database one version forward
following the existing MIGRATION_n_n+1 pattern.

Columns: audioKey, schemaVersion (Int), analysedAtEpochSeconds, plus nullable columns for
each Class S feature from AUD-10 and each Class I feature to be added later (peak,
integratedLufs, plr, clippingRatio). Nullable is deliberate: Class S and Class I are
produced by different passes at different times, so a row can be half-populated.

`schemaVersion` is an INTEGER constant in code, bumped whenever the meaning of any measured
column changes, so stale rows can be recomputed without a Room migration.

Add TrackAnalysisDao (query by audioKey, upsert Class S fields, upsert Class I fields,
count rows missing each class) and a repository in data/repository/ exposing suspend
functions on @IoDispatcher. Domain-facing model must be framework-free.

Add tests: upsert of Class S leaves Class I nulls untouched and vice versa; a
schemaVersion bump makes existing rows read as absent.

Update docs/ai/data.md.
```

**Done when**
- [ ] Partial upserts do not clobber the other class's columns.
- [ ] `schemaVersion` bump invalidates rows without a Room migration.
- [ ] `docs/ai/data.md` updated.

---

## AUD-12 — Analysis orchestrator: window extraction on `@IoDispatcher`

**Blocks:** AUD-13. **Blocked by:** AUD-10, AUD-11.

**Why.** This is the component that actually reads audio. The single hard rule is that it
must never touch the audio thread or the playing decoder.

**Prompt**

```
Add the component that extracts PCM windows from a track and produces a Class S analysis.

New package: data/playback/analysis/ (or data/analysis/ — pick one and be consistent).

Design constraints, all load-bearing:
- Runs on @IoDispatcher. NEVER on BitPerfectPlaybackEngine's audio HandlerThread.
- Constructs its OWN FFmpegDecoder instance. This is safe: the decoder contract is
  "one instance = one native session, all calls from one thread" (FFmpegDecoder.kt:14-24),
  it is not a singleton, and BitPerfectGaplessQueue already proves two decoders coexist.
  Do NOT reach for the engine's decoder.
- Opens with forcePcm = true.
- Skips, and records the reason: DSD sources (they bypass the DSP stage entirely —
  BitPerfectSessionLoader.kt:186), tracks shorter than 3 s, tracks with an empty audioKey,
  and tracks already analysed at the current schemaVersion.
- Samples 3-5 windows spread across the track, avoiding the first and last few seconds.

URI resolution: content:// URIs must be resolved to /proc/self/fd/<n> the way the engine
does. BitPerfectUriResolver currently lives inside the engine package and is reached
through engine-private code — lift it into a shared helper rather than duplicating it, and
keep the existing engine call site working unchanged.

Persist through the AUD-11 repository. Expose a suspend function
`analyseIfNeeded(track): Result<...>` and make it idempotent.

This ticket changes NO DSP behaviour.

Tests: DSD is skipped; a short track is skipped; an already-analysed track at the current
schemaVersion is skipped; a failed decode does not throw to the caller; two concurrent
calls for the same track do not both decode.
```

**Done when**
- [ ] No code path reaches the audio thread; verified by inspection of every call site.
- [ ] `BitPerfectUriResolver` is shared, not duplicated, and the engine still works.
- [ ] Skip rules are covered by tests.

---

## AUD-13 — Surface measured values in telemetry (read-only)

**Blocks:** AUD-30. **Blocked by:** AUD-12.

**Why.** This is what makes the whole approach falsifiable: you need to see the numbers
before deciding whether they justify changing any DSP parameter.

**Prompt**

```
Display the stored Class S analysis for the currently playing track in the existing player
telemetry UI. Read docs/ai/presentation.md and docs/ai/playback.md first.

Follow the existing telemetry path exactly: data-layer snapshot -> domain model ->
use case -> ViewModel -> Composable. AudioTelemetryCollector / AudioTelemetry /
ObserveAudioTelemetryUseCase are the pattern to mirror. Do not let Data-layer types reach
the UI.

Show, when a row exists for the current track: measured cutoff, spectral tilt, stereo
correlation / mid-side ratio, and "not analysed" when absent.

Strictly read-only and diagnostic. No DSP parameter changes in this ticket. The value must
be read off the audio thread and delivered as state, never queried from doLoadTrack or
doEnqueueNext.

Update docs/ai/playback.md's Telemetry section.
```

**Done when**
- [ ] Values appear for analysed tracks and degrade cleanly to "not analysed".
- [ ] No Room query occurs on the audio thread.
- [ ] Telemetry section of `docs/ai/playback.md` updated.

---

## AUD-20 — Class I measurement during playback (the free path)

**Blocks:** AUD-30. **Blocked by:** AUD-11.

**Why.** True peak, integrated LUFS and PLR are *integral* measures — sampling windows
biases them (feasibility §2.6), and an underestimated peak feeds the exact `volume`
parameter that AUD-40 exists to fix. On paths where Kotlin already sees every sample, they
cost almost nothing.

**Prompt**

```
Accumulate integral loudness measurements while a track plays, on paths where the Kotlin
write loop already sees every sample.

Context, verified:
- writeLoopIteration (BitPerfectPlaybackEngine.kt:802-828) decodes into
  transportBuffers.pcmBuffer for AudioTrackSink and LibusbPcmEnhancedSink.
- It early-returns for LibusbOutputSink into a 50 ms EOF poll (:812-820) because the
  native pump owns the data. LibusbPcmAudioSink.write() and LibusbDsdAudioSink.write()
  are literal no-ops (`= size`, LibusbPcmAudioSink.kt:209, LibusbDsdAudioSink.kt:523).
  So this technique CANNOT cover the pure bit-perfect libusb path. Do not attempt to
  instrument decoder_to_ring_bridge.cpp — that is the most timing-sensitive code in the
  project. Offline coverage for that path is AUD-21.

Implement:
- A small accumulator fed from the decoded PCM buffer: running absolute peak, plus an
  EBU R128 pre-filter and block energy for integrated loudness. Budget about 4 ops per
  sample; measure the added time per write-loop iteration and report it.
- Track sample COVERAGE. Commit the result at EOF only when coverage is >= 95% contiguous.
  A seek-heavy or partial listen must be discarded, not persisted — a partial peak is
  worse than no peak because it is confidently wrong.
- Persist via the AUD-11 repository, off the audio thread (hand the finished aggregate to
  a coroutine; do not perform Room I/O inside the write loop).
- Measure on play N, apply from play N+1. Nothing in this ticket may feed the graph that
  is currently running — that would violate the "no runtime parameter change" constraint.

This ticket changes NO DSP behaviour.

Tests: coverage gate rejects a seek-interrupted listen; peak accumulation matches a
reference computed offline on the same buffer sequence; accumulator is reset on track change.
```

**Done when**
- [ ] Added per-iteration cost measured and reported.
- [ ] Coverage gate proven by test.
- [ ] No Room I/O on the audio thread.
- [ ] Zero change to how any track sounds.

---

## AUD-21 — Class I measurement offline (full-file pass)

**Blocks:** AUD-30. **Blocked by:** AUD-20.

**Prompt**

```
Add a full-file offline pass producing the Class I measures for tracks that AUD-20 cannot
cover (the bit-perfect libusb path) or that the user has not played.

Extend the AUD-10 native bridge with an `ebur128 + astats` measurement mode over the whole
decoded stream. Verify the exact ebur128 metadata keys against the shipped FFmpeg 7.1.4
build; do not assume them.

Return: sample peak, true peak, integrated LUFS, PLR, clipping ratio, flat-top run-length
statistics.

Cost is real and must be measured, not estimated: instrument and report actual
seconds-per-track for a 4-minute 16/44.1 FLAC, a 24/96 FLAC, and a DSD64 file on a real
device. The feasibility estimate is 1.2-3 s/track; confirm or correct it. This number
determines whether AUD-50 scheduling is viable as designed.

Restrict eligibility to tracks that can actually use the result: lossless sources that are
not already native hi-res (mirror the predicate in
BitPerfectEnhancementPipeline.kt:60 shouldUseHiResRemasterStage). Analysing anything else
is wasted battery.

Runs on @IoDispatcher. This ticket changes NO DSP behaviour.
```

**Done when**
- [ ] Real per-track timings measured on-device for the three format classes.
- [ ] Eligibility predicate matches the Hi-Res gate.
- [ ] `ebur128` metadata keys verified against the shipped build.

---

## AUD-30 — DECISION GATE: is the measurement vector sufficient?

**Blocks:** everything after it. **Blocked by:** AUD-13, AUD-20, AUD-21.
**This is not a code ticket.**

**Why.** The feasibility document's verdict rests on a falsifiable claim: that measured
features are a sufficient statistic for the DSP decision. This is where you test it, before
spending effort wiring anything up.

**What to do**

1. With AUD-13 shipped, export the measured features for a few hundred tracks from your
   own library.
2. Find pairs whose measurements agree within tolerance (cutoff, tilt, M/S ratio, PLR, true
   peak, clipping ratio).
3. Blind A/B those pairs under the same DSP treatment.

**Outcomes**

- *Pairs that measure alike also sound right under the same treatment* → the verdict holds.
  Proceed to AUD-31, AUD-40, AUD-42.
- *Pairs that measure alike need different treatment, frequently* → the measurement vector
  is not sufficient. Something outside it carries the residual. Re-open the classifier
  question with real evidence, and record which pairs broke it.

Also worth running here, and it needs no code at all: compute the long-term average
spectrum of the library grouped by the existing `TrackEntity.genre` tag. If between-genre
variance clearly dominates within-genre variance, genre-conditional tonal targets are real
(feasibility §7.1) — and the next question is tag coverage, not whether to ship a model.

---

## AUD-31 — Split `sue_bridge.cpp`; replace positional JNI parameters

**Blocks:** AUD-40, AUD-42. **Blocked by:** AUD-30 (only in the sense that it is wasted
work if the gate fails — it is otherwise safe to do at any time).

**Why.** One 1172-line file owns three unrelated engines behind a single `nativeCreate` with
thirteen positional parameters and boolean mode routing (`sue_bridge.cpp:721-731`). Adding
measured inputs as parameters fourteen and fifteen is how it becomes unmaintainable.

**Prompt**

```
Refactor sue_bridge.cpp. PURE REFACTOR: no behavioural change whatsoever.

Current structure:
- PROFILE_MATRIX                    sue_bridge.cpp:161
- profile_to_dsp_params()           :220
- build_hires_remaster_chain()      :354
- build_force48k_resample_chain()   :379
- build_filter_chain()              :415
- nativeCreate(), 13 positional params, boolean mode routing   :721-731

Do:
1. Split the three engines into three entry points — nativeCreateSue,
   nativeCreateHiRes, nativeCreateResampler — retiring the isForce48kResampleOnly /
   isLosslessSource / isSueEnabled / isHiResEnabled routing booleans. Each entry point
   takes only what its own graph builder needs.
2. Replace the positional parameter list with a single descriptor marshalled once:
   a Kotlin data class mapped to a C struct, or discrete setters on the handle followed
   by a configure() call. Pick one and apply it consistently.
3. Update SueBridge.kt, SueStage.kt and BitPerfectEnhancementPipeline.kt call sites.
4. Split the C++ into separate translation units if that falls out naturally; do not
   force it if it complicates the CMake source list.

VERIFICATION IS THE POINT OF THIS TICKET: log the generated filter_str for a corpus of
tracks covering every profile (AGGRESSIVE / MODERATE / LIGHT / SUBTLE / BYPASS), the
Hi-Res path, and the force-48k path, BEFORE and AFTER the refactor. Require EXACT STRING
EQUALITY. Any difference is a bug in the refactor, not an improvement.

Do not change PROFILE_MATRIX values, profile parameters, or any filter argument in this
ticket. Do not collapse the C++/Kotlin matrix duplication here — that is AUD-42.

Update docs/ai/native-audio.md and docs/ai/playback.md.
```

**Done when**
- [ ] `filter_str` is byte-identical before and after across the full profile corpus.
- [ ] No positional parameter list longer than three remains on the JNI boundary.
- [ ] Both docs updated.

---

## AUD-40 — Drive Hi-Res Dynamic Remaster from measured values

**Blocks:** AUD-41. **Blocked by:** AUD-30, AUD-31, AUD-21.

**Why.** Highest quality gain per line of code in the plan. Today the `volume` stage is
derived from a tag most files lack (default −3.0 dB, `ffmpeg_bridge.cpp:692`) and the
`compand` curve is identical for every eligible track.

**Prompt**

```
Make the Hi-Res Dynamic Remaster chain a function of measured signal properties.

Today (sue_bridge.cpp:354, build_hires_remaster_chain):
  volume=<replayGainDb>dB
  ,compand=attacks=0.01:decays=0.3:soft-knee=4:points=-80/-80|-30/-30|-3/-0.5|0/-0.5
  [,aresample=osr=<2x source>:resampler=soxr:precision=33]
  ,alimiter=limit=0.95:attack=5:release=50:level=0
  ,aformat=sample_fmts=flt
replayGainDb comes from extract_replaygain_db (ffmpeg_bridge.cpp:725), which reads the
REPLAYGAIN_TRACK_PEAK tag and falls back to a blind -3.0 dB.

Change:
1. When a measured true peak exists for the track, use it instead of the tag. Keep the
   existing formula and clamps: gain = clamp(-20*log10(peak) - 3.0, -6.0, 0.0).
   Fall back to the tag, then to -3.0, when no measurement exists.
2. Make the compand curve a function of measured PLR. Bypass the expansion entirely above
   about 14 dB PLR — a master that already has that much dynamic range does not need
   upward expansion, and applying it is unrequested colouration.
3. Bypass when the measured clipping ratio indicates an already-clipped master; expanding
   it amplifies existing distortion.

The analysis must arrive as a NULLABLE parameter threaded from BitPerfectSessionLoader
(:112 for tier 1/2, :254 for tier 3) into buildSueStageIfNeeded
(BitPerfectEnhancementPipeline.kt:181). When it is null, behaviour must be EXACTLY what it
is today. That is the compatibility contract.

The value must be resolved off the audio thread and passed in. It must NOT be a Room query
executed from doLoadTrack or doEnqueueNext.

Ship behind a setting, default OFF, until A/B'd.

Tests: null analysis reproduces today's filter_str byte-for-byte; a high-PLR master gets
no expansion stage; a hot master gets the full -3 dB.
Update docs/ai/playback.md and docs/ai/native-audio.md.
```

**Done when**
- [ ] Null analysis produces byte-identical `filter_str` to today.
- [ ] High-PLR and hot masters produce visibly different graphs in the log.
- [ ] Setting exists, defaults off.

---

## AUD-41 — A/B validation of AUD-40, then flip the default

**Blocked by:** AUD-40. Not a code ticket beyond the default flip.

Listen to matched pairs (a loudness-war master and a dynamic master of comparable material)
with the setting on and off. If the difference is not audible on your own system, say so and
leave the default off — that is a legitimate outcome and it is cheaper to accept than to
defend. Record the result in the feasibility document.

---

## AUD-42 — Drive SUE from measured cutoff and tilt; collapse the duplicated matrix

**Blocked by:** AUD-30, AUD-31, AUD-13.

**Why.** `exciter_freq` is anchored to a cutoff *assumed* from the bitrate bucket. That is
wrong for transcodes (a 320 kbps MP3 re-encoded from 128 reports 320, lands in the BYPASS
column, and receives nothing), for VBR (`bit_rate` is an average or 0), and for every
non-LAME encoder.

**Prompt**

```
Make SUE's parameters a function of the measured spectrum rather than of the bitrate bucket.

Change:
1. Drive exciter_freq from the MEASURED spectral cutoff instead of the assumed LAME cutoff
   implied by the bitrate column (sue_bridge.cpp:236-260 documents the current assumption).
2. Drive exciter_amount / drive from how far the measured cutoff sits below 20 kHz.
3. Drive air_gain_10k / air_gain_14k from measured spectral tilt.
4. Make SUE_APODIZING_LOWPASS_HZ (currently the constant 19500, sue_bridge.cpp:124)
   adaptive: measured cutoff plus a margin.
5. Drive the stereotools widening decision from measured mid/side ratio and correlation
   instead of the "bitrate <= 128" heuristic.

At the same time, COLLAPSE THE DUPLICATED DECISION TABLE. PROFILE_MATRIX exists twice:
sue_bridge.cpp:161 and SueProfileResolver.kt:83-111, with a comment at
SueProfileResolver.kt:52 saying "keep the two in sync". Two hand-maintained copies must not
survive contact with a new input. Pick one owner and have the other read from it.

SUE needs Class S features only — no peak, no loudness. Do not make it depend on AUD-21.

Nullable analysis parameter, same contract as AUD-40: null -> exactly today's behaviour.

Test that matters: construct or find a 320 kbps MP3 that is a transcode of a 128 kbps
source. Today it resolves to BYPASS. After this change it must be recognised and treated.
Also test that a genuine 320 kbps encode still resolves to BYPASS.
Update docs/ai/playback.md and docs/ai/native-audio.md.
```

**Done when**
- [ ] Only one copy of the profile decision table exists.
- [ ] A transcode is detected; a genuine high-bitrate encode still bypasses.
- [ ] Null analysis reproduces today's behaviour exactly.

---

## AUD-50 — Background scheduling and Class I backfill

**Blocked by:** AUD-21 (needs its real timings), AUD-30.

**Prompt**

```
Add scheduled background analysis so the library can be backfilled without hurting battery.

Add androidx.work (first new dependency in this plan; add to gradle/libs.versions.toml
following the existing style). Wire a Hilt worker factory and Configuration.Provider —
neither exists in this project yet.

Constraints: requiresCharging, requiresBatteryNotLow. Resumable across process death.
Progress observable so it can be surfaced in Settings.

Prioritisation, in order: tracks the user has actually played, then tracks eligible for the
Hi-Res path (lossless, not already native hi-res), then everything else. Never DSD.

Use the real per-track timings measured in AUD-21 to size batches. The feasibility estimate
is 3.3-8.3 hours of CPU for 10,000 tracks on the Class I pass — if AUD-21 measured
materially worse, say so and reconsider the scope before implementing.

Hook into the existing index pass (MediaIndexRepositoryImpl.scanAndIndexMedia,
MediaIndexRepositoryImpl.kt:87) rather than adding a second discovery mechanism.

Update docs/ai/data.md and docs/ai/di.md.
```

**Done when**
- [ ] Job survives process death and resumes.
- [ ] A 1000-track backfill completes on charge without thermal throttling.
- [ ] Batch sizing uses measured, not estimated, timings.

---

## AUD-60 — OPTIONAL: spoken-word bypass via a small VAD model

**Blocked by:** AUD-30 and an explicit decision that it is worth 1.8 MB.

**Why.** This is the only ML piece that survived the steelman (feasibility §7.4, §8.2), and
it is a bypass predicate, not a parameter selector. If it ships at all it is Silero VAD
(MIT, roughly 1.8 MB), not YAMNet (Apache-2.0, roughly 14 MB) — one eighth the size for the
one job that actually needed a model.

**Do not start this ticket unless** AUD-30 produced evidence that a measurable gap exists,
and you have confirmed on-device that spoken-word content is actually being mis-treated by
the DSP today.

**Prompt (only when the above holds)**

```
Add spoken-word detection as a DSP bypass predicate.

Model: Silero VAD, MIT licence. VERIFY the actual artifact size, licence text, and LiteRT
compatibility before adding it — the ~1.8 MB figure is from memory and must be confirmed.

Scope: one boolean per track — "this is predominantly speech" — cached alongside the other
analysis in track_analysis. When true, bypass SUE and Hi-Res entirely for that track.

It selects NO parameters. If you find yourself feeding its output into a filter argument,
stop: that is outside what the evidence supports.

Runs in the AUD-12 orchestrator, on @IoDispatcher, cold-start cost paid there and never on
the audio thread or during doEnqueueNext.

Ship behind a setting, default off. Measure and report the actual APK/AAB size delta.
```

**Done when**
- [ ] Licence and artifact size verified against the real file, not from memory.
- [ ] Output gates bypass only; no parameter consumes it.
- [ ] Measured AAB size delta recorded.

---

## Not planned

Recorded so they are not re-proposed without new evidence. Reasoning in
[`feasibility-audio-classifier.md`](feasibility-audio-classifier.md) §7.

| Idea | Why not |
|---|---|
| YAMNet genre classification → DSP parameters | No parameter in this chain is a function of genre; §8.1 |
| Genre-conditional tonal EQ targets | `TrackEntity.genre` is already better ground truth than a classifier; within-genre spread too large; contradicts the fidelity premise |
| ML defect classification | Measurement wins outright with the right statistics; no pretrained model; ground truth would have to be synthesised |
| Embedding + kNN over a reference set | YAMNet is trained to be invariant to mastering style; retrieved concept collapses to a measurable one |
| Stem separation | 80–150 MB, slower than realtime, and it outputs audio rather than a cacheable number |
| No-reference quality prediction | The good metrics are full-reference; there is no reference for a lossy file |
