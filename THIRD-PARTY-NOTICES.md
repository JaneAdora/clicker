# Third-party notices

Clicker itself is licensed under either the MIT license or the Apache License,
Version 2.0, at your option (see `LICENSE-MIT` and `LICENSE-APACHE`). It builds on
the work below.

## Bundled in this repository

| Item | Where | License |
|---|---|---|
| Anek Gurmukhi (font) | `android/app/src/main/res/font/` | SIL Open Font License 1.1, text in `android/app/src/main/assets/licenses/AnekGurmukhi-OFL.txt` |
| Fira Code (font) | `android/app/src/main/res/font/` | SIL Open Font License 1.1, text in `android/app/src/main/assets/licenses/FiraCode-OFL.txt` |
| `polo.proto`, `remotemessage.proto` | `crates/clicker-core/proto/` | Protocol definitions from the Android Open Source Project (Apache License 2.0) and the `androidtv-remote` project by louis49 (MIT). Sources are named at the top of each file. |
| Gradle wrapper | `android/gradlew`, `android/gradle/wrapper/` | Apache License 2.0 |

## Linked into the Android app

| Library | License |
|---|---|
| AndroidX (Activity, Lifecycle, Compose, Material 3, DataStore) | Apache License 2.0 |
| Kotlin standard library and kotlinx.coroutines | Apache License 2.0 |
| Java Native Access (JNA) | Dual licensed, LGPL 2.1 or later, or Apache License 2.0. Clicker uses it under the Apache License 2.0. |
| UniFFI (generated bindings and runtime) | Mozilla Public License 2.0 |

## Rust crates

The Rust crates Clicker depends on are listed in `Cargo.lock`. The direct
dependencies include tokio, rustls, tokio-rustls, ring, rcgen, rsa, prost, sha2,
x509-parser, serde, toml, anyhow, uniffi, ratatui, crossterm and mdns-sd, each
under MIT, Apache 2.0, ISC, BSD or MPL 2.0 terms. Run
`cargo metadata --format-version 1` or a tool such as `cargo-about` for the exact
license of every crate in the tree.

## Protocol reference

The Remote protocol v2 implementation was written with reference to the
open-source `androidtvremote2` project by tronikos (Apache License 2.0).
