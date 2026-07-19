# `docs/ai/` — Per-Module Engineering Guides

These are the **deep, authoritative** coding guides for Audiophile Music Player, split by
architectural module. They are the single source of truth that all AI tooling shares.

Start at the repo-root [`AGENTS.md`](../../AGENTS.md) — it's the entry point and the
index. The tool-specific entry files ([`/CLAUDE.md`](../../CLAUDE.md) for Claude Code,
[`/.github/copilot-instructions.md`](../../.github/copilot-instructions.md) for GitHub
Copilot) are thin pointers back to `AGENTS.md` and these docs, so content lives in one
place and never drifts.

| File | Covers |
|------|--------|
| [`architecture.md`](architecture.md) | Layering, package map, dual-engine orientation, where code belongs |
| [`domain.md`](domain.md) | Models, use cases, `Resource`/`ResourceError` |
| [`data.md`](data.md) | Repositories, Room (+ migrations), MediaStore scan, remote APIs, settings |
| [`playback.md`](playback.md) | Media3 service, dual engine, telemetry, USB routing (Kotlin) |
| [`native-audio.md`](native-audio.md) | C++/JNI, FFmpeg, libusb, DSD/DoP, CMake, bit-perfect |
| [`presentation.md`](presentation.md) | Compose, MVVM/UDF, `BaseViewModel`, navigation, theming |
| [`di.md`](di.md) | Hilt modules and qualifiers |
| [`testing.md`](testing.md) | Test conventions + build/verify commands |
| [`conventions.md`](conventions.md) | Kotlin, KDoc, file layout, `callbackFlow`, forbidden patterns |

**Keep these current.** When you add a module, a Room migration, an engine coordinator,
or a JNI entry point, update the matching doc in the same change.
