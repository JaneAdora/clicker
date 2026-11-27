# Android release build

The release APK is for sideloading onto your own phone. It is not published to the
Play Store. It is minified with R8, signed with your own release key, and carries
the Rust library for `arm64-v8a` (phones) and `x86_64` (the emulator).

Set up the toolchain first (docs/android/SETUP.md), then run every command below
from `android/` after `source android/env.sh` at the repo root.

## Signing variables

The release build reads its signing key from four environment variables. Nothing
about the key is written into the repo.

| Variable | Meaning |
|---|---|
| `CLICKER_KEYSTORE` | Absolute path to the keystore file |
| `CLICKER_KEYSTORE_PASSWORD` | The keystore password |
| `CLICKER_KEY_ALIAS` | The alias of the key inside the keystore |
| `CLICKER_KEY_PASSWORD` | The key password. Optional; defaults to the keystore password |

If any of the first three is missing, `./gradlew assembleRelease` stops before it
builds anything and names the missing variables. It never falls back to the debug
key. Debug builds (`assembleDebug`, `installDebug`, `testDebugUnitTest`) do not
read these variables at all.

## Where the keystore lives

Keep the keystore outside the repo, in a private directory of your own. The
examples here call it `/path/to/keys/release.jks`; use your own path and file name.
The repo ignores `*.jks` and `*.keystore` as a backstop, but the file should never
be inside the working tree in the first place. Keep the password in 1Password, not
in a file next to the keystore.

To create the keystore once (keytool prompts for the password, so it never lands in
your shell history):

```sh
mkdir -p /path/to/keys && chmod 700 /path/to/keys
keytool -genkeypair -keystore /path/to/keys/release.jks \
  -storetype PKCS12 -alias clicker -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Clicker"
```

Back up the keystore file somewhere safe as well (see "If the keystore is lost").

## Building with 1Password

Put the variables in an env file of `op://` references. It holds no secrets, only
pointers, but keep it outside the repo anyway, for example
`/path/to/keys/release.env`. Replace `<vault>` and `<item>` with your own
vault and item names; the field names must match the fields on the item.

```sh
CLICKER_KEYSTORE=/path/to/keys/release.jks
CLICKER_KEYSTORE_PASSWORD=op://<vault>/<item>/password
CLICKER_KEY_ALIAS=op://<vault>/<item>/alias
```

Then build:

```sh
op run --env-file=/path/to/keys/release.env -- ./gradlew assembleRelease
```

The signed APK is `android/app/build/outputs/apk/release/app-release.apk`.

Check it before installing:

```sh
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
unzip -l app/build/outputs/apk/release/app-release.apk | grep libclicker_ffi.so
```

`apksigner` should print your certificate (not `CN=Android Debug`), and the listing
should show `lib/arm64-v8a/libclicker_ffi.so`.

## Installing

Turn on USB debugging on the phone (Settings > About phone, tap Build number seven
times, then Settings > System > Developer options > USB debugging), plug it in and
accept the prompt. `adb devices` should list it.

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

If a debug build is already on the phone, the install fails with a signature
mismatch, because the debug build is signed with the debug key. Uninstall it first
with `adb uninstall com.janeadora.clicker`. This deletes the app's data: paired TVs
and shortcut pictures are lost, and each TV needs its PIN again.

## Updating

Raise `versionCode` (and `versionName`) in `android/app/build.gradle.kts`, build
again with the same keystore, and run the same `adb install -r` command. The app
keeps its paired TVs, shortcuts and settings. Android refuses an APK whose
`versionCode` is lower than the installed one.

## If the keystore is lost

Android only installs an update signed with the same key as the installed app. If
the keystore or its password is lost, no new build can replace the installed app:
you have to uninstall it (losing its paired TVs, shortcuts and settings) before a
build signed with a new key will install. Keep a backup of the keystore file and
keep the password in 1Password.

## What R8 keeps

The Kotlin side reaches the Rust core through UniFFI's generated bindings, which call
the native library through JNA, and JNA works by reflection. `android/app/proguard-rules.pro`
keeps JNA, every class that extends or implements a JNA type (the bindings'
structures and callbacks), the `uniffi` package, and the runtime annotations JNA
reads (`@Structure.FieldOrder`). If a release build crashes where the debug build
does not, check logcat for `NoSuchMethodError`, `NoSuchFieldError` or
`UnsatisfiedLinkError` first; a missing keep rule is the likely cause.
