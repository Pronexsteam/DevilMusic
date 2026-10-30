# Preview 0.0.17 validation

Checked on 30 October 2026. This records evidence and limits, not an all-device certification.

## Local build

- JDK 17, Gradle 8.13, SDK 36, build-tools 36.0.0.
- Full local suite: 115 application tests (111 executed, four opt-in live-network checks skipped) and 32 pure Kotlin core tests, no failures. Debug lint and release lint complete with no errors; existing warnings remain (including a feature-check warning for a diagnostic JavaScript isolate whose supported features are checked separately).
- Signed release APK uses the normal package, version code 17, `0.0.17-preview`, minimum Android API 26 and no debuggable flag. The preview signing certificate is retained for compatibility with prior local normal installs. It has the historical `Android Debug` subject; the release build itself is not debuggable.

## Device checks

Xiaomi tablet 23073RPBFG, native 1200 × 1920 at density 280. QA package has its own app data.

- Native record material displays actual online album art. One shared 768 × 768 RGBA material texture is decoded (about 2.25 MiB), rather than running a Blender scene or loading an animation sequence. Every record uses the same disc-relative artwork crop.
- Portrait/landscape player, explicit Vinyl/Lyrics controls, retained player presentation, touch turning and Home rearrangement have been exercised on the tablet. Compact 640 × 1136 layout and long-list edge dragging were also checked in the preceding UI pass. This is not a test of every phone or foldable.
- On this network, a fresh-process tap on Genesis by Justice reached MediaSession PLAYING in 2.894 seconds. A subsequent selection of Die For You by The Weeknd took 2.310 seconds. Measurements include ADB input/poll overhead. Playback position continued advancing beyond 20 seconds. These are two samples, not latency percentiles or universal startup guarantees.
- A PSS snapshot of the QA process after real playback and UI checks was about 233 MiB. This is one process snapshot, not a memory ceiling, long-session leak test or measurement of the non-debuggable release.
- The supplied lyrics for Die For You had line timestamps and no word timestamps. The UI reports that distinction. Word-glow support is covered by timed-word fixtures; there is no claim that this real song received word sync.

- Native playlists were created, renamed, opened and played on the QA device. Explicit likes persisted separately from recommendation feedback; corrupt-file preservation and imported-like exclusions are unit-tested.
- Justice artist lookup correctly offered namesakes, opened the selected artist with photo, songs and albums, and returned to the original playlist.
- A warmed artist-scroll pass logged 25 memory hits and one network attempt; a Home scroll pass logged 13 memory hits and one network attempt. These small samples do not prove every cover is cached.
- The final debug Home scroll sample recorded 12 missed-deadline frames out of 392 (3.06%), with 27 ms median and 38 ms p95. Occasional stutter remains; this is not a claim of perfect scrolling or a release-build benchmark. Previously seen images are primed from memory before first draw in the final code.

## Algorithms and limits

Shuffle tests exercise Media3 traversal at 1,420, 5,000 and 10,000 items, stable identifiers, queue edits and restore. A previous isolated 10,000-item tablet run exercised next/previous and restart. The permutation does not repeat items within a normal pass; explicit selection, Play next and repeat modes intentionally change traversal.

Home ranking uses decaying artist affinity, a recent-session boost, explicit feedback, recent-exposure penalties, current/recent-track exclusions and an artist repetition cap. It mixes familiar tracks with discovery. Candidate selection is still limited by the public catalog/search inputs; this is not a collaborative recommendation model equivalent to YouTube Music.

Artwork uses bounded downloads and sampled decoding up to 1024 px, with smaller thumbnail targets and a shared 12 MiB thumbnail cache and a disposable 64 MiB compressed-image disk cache. Missing high-resolution provider art falls back to available images. Audio resolution and short next-track preloading remain native; no yt-dlp child process is launched by Android.

## Publication checks

The initial Android publication uses the locally validated source and signed APK. CI is configured for pull requests and explicit manual dispatch, not for redundant initial push/tag/release builds. The separate Deviload documentation PR must pass its four protected platform checks; those are not replaced by Android tests.
