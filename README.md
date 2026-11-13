# TriMet — a Light Phone tool

A [Light Phone III](https://thelightphone.com) tool for Portland's [TriMet](https://trimet.org)
transit system. It has two jobs:

- **Live arrivals by stop ID.** Save the stops you use (by their TriMet stop ID) and see upcoming
  arrivals, refreshed about every 30 seconds. Each row shows the route, how many minutes away the
  vehicle is (or its scheduled time when no estimate is available), the destination sign, and any
  detour, drop-off-only, or disruption note.
- **Trip planning.** Plan a trip between two places. Enter a numeric stop ID for a saved stop, or
  free text for an address or landmark (TriMet geocodes it). If TriMet can't resolve a place
  cleanly, the tool lists the nearby matches and lets you pick one.

All data comes from TriMet's public web services
([developer.trimet.org](https://developer.trimet.org/ws_docs/)):
arrivals V2 and the Trip Planner. The registered AppID lives in
[`TriMetApi.kt`](tool/src/main/kotlin/com/tthayer/lighttrimet/TriMetApi.kt); it is a public
application identifier, not a secret.

This is an independent project. It is not affiliated with or endorsed by TriMet.

## Layout

```
tool/                              the tool itself (this is the code that ships)
  lighttool.toml                   id, label, version, permissions
  build.gradle.kts
  src/main/kotlin/com/tthayer/lighttrimet/
    HomeScreen.kt                  saved stops + live previews (the initial screen)
    StopScreen.kt                  one stop's full arrival board
    TripPlanScreen.kt              trip planner: from/to entry and results
    AddStopScreen.kt               add a stop by ID
    AboutScreen.kt                 attribution
    TriMetApi.kt                   HTTP client for arrivals + trip planner
    TriMetModels.kt                API models and app data types
    TriMetXml.kt                   Trip Planner XML parsing
    TriMetFormatting.kt            time/distance/duration formatting
    SavedStops.kt                  DataStore-backed list of saved stops
    Components.kt                   shared UI rows and icons
  src/test/kotlin/...              unit tests for parsing and formatting
```

Everything else (`sdk/`, `plugin/`, `lint-rules/`, `gradle/`, `builder/`, `docs/`, `examples/`) is
the [light-sdk](https://github.com/lightphone/light-sdk) scaffold that the tool builds against.
The Light build server ignores those directories and builds `tool/` against a pinned copy of the
SDK. Keep them only so you can build locally; the `upstream` remote points at the SDK repo so you
can `git pull upstream main` for updates.

## Build and test

```sh
# Point Gradle at your Android SDK (local.properties is git-ignored)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :tool:testDebugUnitTest   # unit tests
./gradlew :tool:assembleDebug       # debug APK
./gradlew :tool:lintDebug           # SDK lint rules (restricted APIs, etc.)
```

The debug APK lands at `tool/build/outputs/apk/debug/tool-debug.apk`.

### Run it

Start an Android emulator sized like a Light Phone III (1080 x 1240, Android API 34, no Google Play
Services) and install the APK:

```sh
adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```

For this tool (arrivals + trip planning over the network) a plain emulator is enough. Receiving
push notifications and requesting special permissions needs LightOS itself, so for those, run the
LightOS emulator app as a system app. The full instructions are in the SDK's
[emulator docs](docs/system_app/README.md); the recipe that works locally:

```sh
# 1. AVD: create it with a writable system partition (see the note below on sizing)
avdmanager create avd -n lp3 -k "system-images;android-34;default;arm64-v8a"

# 2. Boot with a writable system, then unlock it
emulator -avd lp3 -writable-system -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
adb root && adb remount

# 3. Generate the platform test key (one time; see docs/system_app/README.md for the openssl steps)
#    -> sdk/emulator/keys/platform.jks

# 4. Build and install the emulator app as a privileged system app
./gradlew :sdk:emulator:assembleDebug
adb shell mkdir -p /system/priv-app/LightOSEmulator
adb push sdk/emulator/build/outputs/apk/debug/emulator-debug.apk \
    /system/priv-app/LightOSEmulator/LightOSEmulator.apk
adb reboot
adb shell dumpsys package com.thelightphone.sdk.emulator | grep sharedUser   # -> android.uid.system/1000

# 5. Point the tool at the emulator server and install it
#    set serverPackage = "com.thelightphone.sdk.emulator" in tool/lighttool.toml
./gradlew :tool:assembleDebug -DlightSdk.allowAltServerPackage=true
adb install -r tool/build/outputs/apk/debug/tool-debug.apk
```

Two things that cost time:

- **`/system` on the AOSP image is an overlay on a ~199 MB `scratch` partition**, independent of
  `disk.systemPartition.size`. The emulator APK is ~92 MB and does fit, but it is close — push it
  right after `adb remount` and before anything else writes to `/system`.
- The LightOS server rejects tokens from tools it hasn't approved (`Rejected GetToken from
  unverified caller`). Open the emulator's **Settings → Allowed Tools → All Tools** (or upload your
  signing key's SHA-256 to the Tool Manager's Developer Keys) so a dev-signed build is accepted.

## Using it

1. On the home screen, tap **+** and enter a stop ID (find it on trimet.org or on the sign at the
   stop). The tool validates the ID and saves the stop.
2. The home screen shows the next few arrivals at each saved stop. Tap a stop for the full board;
   it refreshes every 30 seconds or when you tap the refresh icon. The trash button on a stop's
   board removes it (with a confirm step).
3. Tap **PLAN TRIP** (from the home screen, from a stop, or on its own) and fill in **From** and
   **To**. Use a stop ID, or type a place name. Accept the suggested match if TriMet asks which
   place you meant.

## Submitting to Light

The tool id (`com.tthayer.lighttrimet`) and version are in `tool/lighttool.toml`. To publish, push
to a public GitHub repo and queue a build from the
[Light dashboard](https://dashboard.thelightphone.com/); bump `versionCode` for each submission.
See the [light-sdk README](https://github.com/lightphone/light-sdk) and this repo's
[`TOOL_GUIDELINES.md`](TOOL_GUIDELINES.md) for the review process.
