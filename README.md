# Rotation Beats

A one-tap rhythm runner for Android. **The whole world rotates 90° on every bar.** You only tap
to jump. The wall in front of you is always the floor after the next turn, so every level is
built around where the floor *will* be.

- **Tap** (or hold) to jump. Every jump lasts exactly one beat.
- Every 4 beats the world turns clockwise. The wall ahead becomes your floor, and the floor
  becomes the wall behind you.
- **A hole in the wall ahead** turns into a pit right where you land, so be mid-jump when the
  world turns: your height at the turn becomes how far along the new floor you land.
- The pause button sits at the top right, next to the bar clock. You can resume where you
  left off, retry the level, or go back to the menu.
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

Without signing secrets, local release builds are signed with the debug key so they can still
be side-loaded.

Optional: `SCREENSHOTS=1 ./gradlew testDebugUnitTest` renders real game frames to
`app/build/shots` using Robolectric (it downloads about 350 MB of Android runtime jars).

## Releases (and Obtainium)

Every push to `main` runs `.github/workflows/release.yml`. It tests, builds a signed APK, and
publishes a GitHub release tagged `v1.0.<run number>` with the APK attached. The run number is
also the `versionCode`, so each release is an upgrade. Pushes to other branches just build and
upload the APK as a CI artifact.

To use it in Obtainium, add the app with the repository URL
(`https://github.com/CoPokBl/RotationBeats`); the default GitHub source settings work as they are.

### One-time signing setup

1. Create a release key (keep the file and passwords somewhere safe, because losing them means
   users have to reinstall to update):
   ```bash
   keytool -genkeypair -v -keystore rotationbeats-release.jks -alias rotationbeats \
     -keyalg RSA -keysize 4096 -validity 36500
   ```
2. Base64 it for GitHub:
   ```bash
   base64 -w0 rotationbeats-release.jks > keystore.b64   # macOS: base64 -i rotationbeats-release.jks -o keystore.b64
   ```
3. In the repository, go to **Settings → Secrets and variables → Actions → New repository
   secret** and add:
   - `SIGNING_KEYSTORE_BASE64`: the contents of `keystore.b64`
   - `SIGNING_STORE_PASSWORD`: the keystore password
   - `SIGNING_KEY_ALIAS`: `rotationbeats` (or whatever alias you used)
   - `SIGNING_KEY_PASSWORD`: the key password (the same as the store password unless you chose
     a different one)
4. Delete `keystore.b64`. The release workflow refuses to publish if the secrets are missing
   or the APK comes out debug-signed.

To sign locally, set `SIGNING_KEYSTORE` (path to the `.jks`) plus the three password and alias
variables above before running `./gradlew assembleRelease`.

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
