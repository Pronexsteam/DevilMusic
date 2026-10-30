# Building Devil Music

Use JDK 17 and Android SDK 36 with build-tools 36.0.0. Gradle 8.13 is pinned by the wrapper. Set `ANDROID_HOME` to your SDK, or create an untracked `local.properties` with `sdk.dir`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows use `gradlew.bat`. A normal debug build uses package `dev.devilmusic.androidproof`. For an isolated test copy use `-PisolatedQa=true`; this adds `.qa` and does not share the normal app's data. Debug builds use the local Android debug identity unless `ANDROID_DEBUG_KEYSTORE_FILE` is supplied. A different signing identity cannot update an existing install.

For a signed non-debuggable release, set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` in the local environment, then run:

```sh
./gradlew :app:assembleRelease
```

Signing keys are never committed. Official preview updates retain the signing identity used by the earlier normal preview installs. Building from source with your own key produces a separate signing identity; do not uninstall your existing app without exporting its collection first.

`app/` contains the native UI, playback service and integrations. `core/` contains shared pure Kotlin models and policy tests. Gradle resolves the exact dependencies declared in the build files. The unmodified NewPipe Extractor dependency is `v0.26.5`, commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef`. Its exact source archive is provided beside the APK. Retained YouTube.js assets carry their MIT notice; Manrope carries its OFL notice.

`design/vinyl/render_material.py` builds the original Blender scene and transparent runtime material. Run it in Blender's background mode to regenerate the material, then copy `vinyl-material.png` into `app/src/main/res/drawable-nodpi/vinyl_material.png`. The APK contains no album-art test image.

CI runs the Android local suite on pull requests or an explicit manual dispatch. It does not run again on merge, tag or release. Initial preview packaging uses the validated local source tree and signed artifact.
