# CLAUDE.md

**The canonical engineering guide for this repository is [`AGENTS.md`](AGENTS.md).
Read it first.** It defines the architecture, the global golden rules, and links to the
per-module guides under [`docs/ai/`](docs/ai/). Everything an agent needs is there;
this file only adds a few Claude Code–specific reminders so the rules are not
duplicated (and cannot drift) here.

## Before you start
1. Read [`AGENTS.md`](AGENTS.md).
2. Open the [`docs/ai/`](docs/ai/) doc(s) for the area you're touching **before**
   writing code (architecture, domain, data, playback, native-audio, presentation, di,
   testing, conventions).
3. Keep those docs current when you change the corresponding code.

## Environment notes
- Builds on **Windows, macOS, and Linux**. Use `.\gradlew.bat` on Windows (PowerShell)
  and `./gradlew` on macOS/Linux — substitute the right wrapper in the commands below.
- Fast loop: `./gradlew :app:compileDebugKotlin` then a focused
  `./gradlew :app:testDebugUnitTest --tests "*SomeTest"`
  (Windows: `.\gradlew.bat :app:compileDebugKotlin`, etc.).
- A full `:app:assembleDebug` also runs the native CMake build (FFmpeg/libusb) and is
  slow — only run it when touching native code or when you need an APK.

## Highest-stakes reminders (full rules in `AGENTS.md` / `docs/ai/`)
- Respect the layers; Domain stays pure Kotlin; framework types never leave Data.
- Feature ViewModels extend `BaseViewModel`; UDF only; use cases have **no** `@Inject`.
- Wrap every `register*/unregister*` callback API in a `callbackFlow`.
- **Do not degrade the bit-perfect / USB / DSD audio path** — see
  [`docs/ai/native-audio.md`](docs/ai/native-audio.md) and
  [`docs/BIT_PERFECT_LIMITATIONS.md`](docs/BIT_PERFECT_LIMITATIONS.md).
- No `!!`, no `GlobalScope`, no hardcoded `Dispatchers.IO`, Compose only.
