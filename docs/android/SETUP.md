# Android toolchain setup

Recorded 2026-11-24 on Pop!_OS x86_64 (24 cores, KVM available, system adb present).

Prerequisites: JDK 17 (`java -version` reports 17.x) and `rustup` with a working `cargo`.

## Resolved versions

Later tasks read these values.

| Item | Value |
|---|---|
| compileSdk | 37 (SDK platform `android-37.0`, revision 2) |
| Build-tools | 37.0.0 |
| NDK (LTS, r30) | 30.0.16248370 |
| Command-line tools | build 15859902 (`commandlinetools-linux-15859902_latest.zip`) |
| platform-tools | 37.0.3 |
| Emulator | 37.3.10 |
| System image | `system-images;android-37.0;google_apis;x86_64` (revision 8) |
| AVD | `clicker` (device `pixel_7`) |
| cargo-ndk | 4.1.2 |
| Rust | 1.97.1 with targets `aarch64-linux-android`, `x86_64-linux-android` |

Notes:
- Channel check (`sdkmanager --list`, default stable channel, 2026-11-24): platforms 37.0, 37.1 and 37.2 are all stable. Only `37.2-beta1`, `37.2-beta2` and `37.2-beta3` are previews. No build-tools exist for 37.1 or 37.2; the newest stable build-tools is 37.0.0 (`37.0.0-rc1` and `-rc2` are previews). NDK 30.0.16248370 is the stable r30 release (the `rc1` to `rc3` builds are previews).
- Why `android-37.0` and not 37.2: platforms 37.1 and 37.2 are stable and newer, but their only x86_64 system images are `google_apis_ps16k` (16 KB page size) variants, with no plain `google_apis` x86_64 image. The emulator needs the plain image, and 37.0 matches it. compileSdk is 37 for all three, so nothing downstream changes.
- The SDK licenses were accepted with `yes | sdkmanager --licenses`.
- The SDK lives in `$HOME/Android/Sdk` and takes about 8.9 GB (system image 5.3 GB, NDK 2.3 GB, emulator 0.85 GB).
- `sdkmanager` prints a deprecation warning pointing to the newer `android` CLI. It still works.

## Environment

Shell profiles are not edited. Source the repo file in each shell that needs the toolchain. Run it from the repo root, since the path is relative:

```sh
source android/env.sh
```

It exports:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.16248370"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## Commands run

```sh
# 1. Command-line tools (downloaded into a fresh temp dir, SHA-256 checked against the
#    value on https://developer.android.com/studio#command-line-tools-only)
curl -fsSLO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
sha256sum commandlinetools-linux-15859902_latest.zip
# 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
unzip -q commandlinetools-linux-15859902_latest.zip -d x
mkdir -p $HOME/Android/Sdk/cmdline-tools
mv x/cmdline-tools $HOME/Android/Sdk/cmdline-tools/latest

# 2. SDK packages
export ANDROID_HOME=$HOME/Android/Sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "emulator" "platforms;android-37.0" "build-tools;37.0.0" \
  "ndk;30.0.16248370" "system-images;android-37.0;google_apis;x86_64"

# 3. Rust
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# 4. AVD
echo no | avdmanager create avd -n clicker \
  -k "system-images;android-37.0;google_apis;x86_64" -d pixel_7
```

`avdmanager` prints "Could not load devices from .../devices.xml" for this image. The error is harmless; the AVD is created and boots.

## Emulator

Headless boot:

```sh
source android/env.sh
emulator -avd clicker -no-window -no-audio -no-boot-anim -no-snapshot &
echo $! > /tmp/emulator.pid   # may be the launcher PID, not the qemu PID
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
```

Stop it with `adb -s emulator-5554 emu kill`. Prefer this over killing by PID, because the saved PID may belong to the launcher rather than the qemu process. Confirm afterward that no `qemu-system` process remains. Verified: `sys.boot_completed` printed `1` and `adb devices` listed `emulator-5554` (Android 17).

## Cross build check

```sh
source android/env.sh
cargo ndk -t arm64-v8a -t x86_64 build --release -p clicker-core
```

Finished with no errors; `target/aarch64-linux-android/release/libclicker_core.rlib` and `target/x86_64-linux-android/release/libclicker_core.rlib` were produced.

## Not covered

No physical phone is attached. Device testing happens on the emulator until one is.

## Android app build

The Gradle project lives in `android/`. Pinned versions are in `android/gradle/libs.versions.toml`.

| Item | Value | Why |
|---|---|---|
| Android Gradle Plugin | 9.5.2 | Newest stable on Google Maven (2026-11-24). The AGP 9.5 compatibility table lists max API level 37, minimum JDK 17, minimum Gradle 9.6.0 (https://developer.android.com/build/releases/gradle-plugin). |
| Gradle wrapper | 9.9.2 | Current stable release (https://services.gradle.org/versions/current). Runs on JDK 17. `distributionSha256Sum` is set. |
| Kotlin | 2.4.21 | Newest stable. AGP 9 has built-in Kotlin, so only the Compose compiler plugin is applied. |
| Compose BOM | 2026.09.00 (Compose 1.12.1) | The newest BOM, 2026.11.01 (Compose 1.13.0), requires compileSdk 37.1. The SDK here is `android-37.0`, so the build uses the newest BOM that compiles against it. |
| JNA | 5.19.1 (`@aar`) | UniFFI's Kotlin needs 5.12 or newer. |

Build from `android/` after `source android/env.sh` at the repo root:

```sh
./gradlew testDebugUnitTest   # host unit tests
./gradlew installDebug        # to the running emulator
```

`preBuild` depends on two tasks in `app/build.gradle.kts`, both run with the repo root as the working directory:

- `cargoNdk`: `cargo ndk -t arm64-v8a -t x86_64 -o <android/app/src/main/jniLibs> build --release -p clicker-ffi`. It stops with a clear message when `ANDROID_NDK_HOME` or `cargo-ndk` is missing.
- `uniffiBindings`: a host `cargo build -p clicker-ffi`, then `uniffi-bindgen generate --library target/debug/libclicker_ffi.so --language kotlin --no-format` into `android/app/build/generated/uniffi`, added as a generated Kotlin source dir.

`jniLibs/` and the generated bindings are build output and are not committed.

## Local network permission (Android 17)

Source: https://developer.android.com/privacy-and-security/local-network-permission (read 2026-11-24).

- Apps that target Android 17 (API 37) or higher have local network access blocked by default. Outgoing TCP to a LAN address needs the runtime permission `android.permission.ACCESS_LOCAL_NETWORK`, which is in the `NEARBY_DEVICES` permission group. The block sits in the network stack, so it applies to native sockets from Rust too.
- Without the permission, TCP connections typically time out and UDP returns `EPERM`.
- The alternative is the system NSD picker (`DiscoveryRequest.FLAG_SHOW_PICKER`), whose resolved addresses can be used without the permission. A manually typed TV address cannot use that path.
- Apps that target 36 or lower get an implicit grant through `INTERNET` and must not declare or request it.

Clicker targets 37, so the manifest declares `ACCESS_LOCAL_NETWORK` and `MainActivity` requests it on the first Connect (only on API 37 and later). The prompt reads "Allow Clicker to find, connect to, and determine the relative position of nearby devices?" (the Nearby devices group text). If the user declines, the app skips the connect and shows "Clicker needs local network access to reach your TV." Pressing Connect again asks again; if the system no longer shows the prompt, the message stays.
