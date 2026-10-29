# Rotation Beats

A one-tap rhythm runner for Android. **The whole world rotates 90° on every bar.** You only tap
to jump. The wall in front of you is always the floor after the next turn, so every level is
built around where the floor *will* be.

- **Tap** (or hold) to jump. Every jump lasts exactly one beat.
- Every 4 beats the world turns clockwise. The wall ahead becomes your floor, and the floor
  becomes the wall behind you.
- **A hole in the wall ahead** turns into a pit right where you land, so be mid-jump when the
  world turns: your height at the turn becomes how far along the new floor you land.
- Each level is 8 bars. Dying restarts the current level straight away. Later levels get faster
  (100 → 145 BPM), tighter and more demanding.

## Clean by design

- No Google Play Services, no ads, no analytics, no network, no permissions.
- No third-party libraries at all, just the Android framework (the release APK is about 60 KB).
- Music and sound effects are synthesised live. The audio output drives the game clock, so
  jumps stay in sync with what you hear, even over Bluetooth.

## How the levels are made

Levels are generated from a fixed seed per level, so a level plays the same every time. Every
bar is checked by an exhaustive solver (`core/Explorer.kt`) that runs the real game physics over
every jump timing the player could choose:

- every way you can land after a turn has to have a way through the bar,
- every corner feature (pit or spikes on the wall ahead) has to leave a timing window big enough
  for the level (about 0.45 beat on level 1, tightening to 0.2 beat later),
- the unit tests play whole levels inside the real game loop with a bot.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # generator, solver, bot playthroughs, synth
```

The release build is signed with the debug key so it can be side-loaded directly. CI
(`.github/workflows/build.yml`) builds it and uploads the APK as an artifact.

Optional: `SCREENSHOTS=1 ./gradlew testDebugUnitTest` renders real game frames to
`app/build/shots` using Robolectric (it downloads about 350 MB of Android runtime jars).

## Layout

| Path | What |
| --- | --- |
| `core/Physics.kt` | Fixed-tick physics in beat units, collisions, the rotation itself |
| `core/Layout.kt` | Segments (one bar each), local frames, the merged collision grid |
| `core/Explorer.kt` | Exhaustive solver over jump timings |
| `core/LevelGen.kt` | Level generator and difficulty curve |
| `core/Run.kt`, `core/Song.kt` | A play session and its tempo map |
| `GameView.kt`, `SegGfx.kt` | Rendering, camera, input, menus |
| `AudioEngine.kt`, `Synth.kt` | Low-latency audio output and the synth |
