# About this project

Audiophile is built and maintained by **[androidexpert35](https://github.com/androidexpert35)**
— an Android engineer and an audiophile, in that order of appearance but not of
priority.

## Why this exists

Every "audiophile" Android app I could find asked for a subscription, gated its best
features behind a paywall, or turned out to be a skin over the stock player with a fancy
EQ bolted on. None of them actually fought the platform for bit-perfect output — they
just trusted `AudioTrack` and hoped for the best.

This project started from a simpler, more stubborn question: *what does it actually take
to get real, unprocessed audio out of an Android phone and into a DAC, with nothing in
between?* The answer turned out to be "bypass most of Android's audio stack yourself,"
which is what the [dual playback engine](docs/FEATURES.md#1-the-dual-engine-model) and
the [raw USB path](docs/FEATURES.md#2-total-android-bypass--the-usb-dac-path) in this
repo are. Everything else — the [Lossy Audio Restoration](docs/FEATURES.md#4-lossy-audio-restoration)
and [Hi-Res Dynamic Remaster](docs/FEATURES.md#5-hi-res-dynamic-remaster) engines,
DSD/DoP support, the telemetry panel — grew out of the same instinct: build the thing an
audiophile would actually want, because I am one, and I wanted it too.

## No catch

- **It's free.** Not "free with a trial," not "free tier." The whole thing, including
  the bit-perfect USB path and both DSP engines, at no cost.
- **No ads.** None planned, ever.
- **No account, no telemetry sent anywhere.** The "telemetry" this app has is a
  diagnostic panel *for you*, showing what your own phone is doing with your own audio —
  it doesn't phone home. See [`docs/FEATURES.md §7`](docs/FEATURES.md#7-telemetry--showing-its-work).
- **No premium tier.** There's nothing to unlock. What's in the repo is what's in the
  app.

I'm not trying to build a product with a funnel. I built the player I wanted to exist,
and I'm putting it out there because other people who care about this stuff as much as I
do might want it too.

## Transparency

Every technical claim this project makes about its own audio path is written up in
[`docs/FEATURES.md`](docs/FEATURES.md), including the parts that don't always work —
see [known ceilings](docs/FEATURES.md#8-known-ceilings) and
[`docs/BIT_PERFECT_LIMITATIONS.md`](docs/BIT_PERFECT_LIMITATIONS.md) for the honest list
of what Android's platform simply won't let any app guarantee, on every device, without
exception. If something here is wrong or has drifted from the code, please open an issue
— the docs are meant to be checkable, not just readable.

## Get in touch

Bug reports, feature ideas, and DAC compatibility reports are all welcome via
[GitHub Issues](https://github.com/androidexpert35/Audiophile-Music-Player/issues).
