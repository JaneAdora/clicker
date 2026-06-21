# clicker

A terminal remote for Android TV / Google TV, written in Rust. It speaks the
**Android TV Remote protocol v2** directly over your LAN — the same TLS-on-TCP
mechanism the official phone remote apps use — so there's **no ADB, no developer
mode, and no companion app**. Discover your TVs, pair once with the on-screen PIN,
then drive everything from a terminal.

As far as I can tell this is the first from-scratch Rust implementation of the v2
pairing + command protocol (TLS client cert, protobuf messages, the SHA-256
pairing-secret handshake, mDNS discovery, and live IME text entry). If you've been
trying to talk to an Android TV from Rust, the protocol code in [`src/`](src/) is
the interesting part.

```
           clicker Living Room  ●
   [p] ⏻ Power        [s] ⚙ Settings
   [h] ⌂ Home         [o] ≡ Options

                    ┌───┐
                    │ ↑ │
                ┌───┼───┼───┐
                │ ← │ ⏎ │ → │
                └───┼───┼───┘
                    │ ↓ │
                    └───┘

   [esc] ↩ Back       [i] ⊞ Input
   [v] (o) Voice      [k] ⌨ Type
   [+/-] <)) Volume   [PgUp/Dn] ▭ Channel
   [m] <x Mute

       Ⓝ Netflix [1]   Ⓨ YouTube [2]
       Ⓓ Disney+ [3]   Ⓜ Max [4]
       Ⓐ Amazon [5]    Ⓗ Hulu [6]
       Ⓢ Spotify [7]

            ▶ Play/Pause [space]
                 ■ Stop [x]

          << Rew [,]      >> FF [.]
         |< Prev [;]     >| Next [']

            █████░░░░░░░░░░░   32

 [q] quit   [d] devices   [/] keycode probe
```

The on-screen layout mirrors a physical TCL RC802V remote: a vertical, two-column
face with **Enter in the centre of the D-pad** as OK/Select. Every binding is a
single un-shifted key and every key is labelled on screen, so there's nothing to
memorise and nothing behind a pop-out menu.

## How it works

The Android TV Remote v2 protocol runs over two TLS ports on the TV:

- **`:6467` — pairing.** The client presents a self-signed RSA certificate. The TV
  shows a 6-character PIN; the client proves it by hashing
  `SHA-256(client_modulus ‖ client_exponent ‖ server_modulus ‖ server_exponent ‖ code[1..])`
  and checking the first byte against `code[0]`. After that, the TV trusts the
  client cert permanently.
- **`:6466` — remote control.** A long-lived TLS connection carrying length-delimited
  protobuf messages: key injections, app-launch links, IME text edits, volume state,
  and a ping/pong keepalive.
- **Discovery.** TVs advertise `_androidtvremote2._tcp` over mDNS; clicker browses
  for them so you never have to type an IP.

clicker implements all of it. Notable build choices: the
[`ring`](https://github.com/briansmith/ring) rustls provider (no `cmake` needed),
[`protox`](https://github.com/andrewhickman/protox) for pure-Rust protobuf
compilation (no system `protoc` needed), and [`mdns-sd`](https://crates.io/crates/mdns-sd)
for pure-Rust discovery — so `cargo build` just works with no external toolchain.

## Build & install

Requires a stable Rust toolchain. No system `protoc`, no `cmake`, no `adb`.

```sh
git clone https://github.com/JaneAdora/clicker
cd clicker
cargo build --release
cp target/release/clicker ~/.local/bin/      # or anywhere on PATH
```

Or install straight from the checkout:

```sh
cargo install --path .
```

It also builds cleanly on Android under [Termux](https://termux.dev/) — install
Rust with `pkg install rust` (not `rustup`, which can't run on Android).

## First run

1. `clicker` with no saved TV opens the **device picker** and scans the LAN. Pick
   your TV from the list (or choose **＋ Enter IP manually** if discovery is blocked
   on your network — common on some Android/Wi-Fi setups).
2. The TV displays a **6-character PIN**. clicker opens a PIN modal — type it and
   press `Enter`.
3. On success the TV trusts clicker's certificate, the device is saved, and
   subsequent runs reconnect to it automatically.

The link glyph in the header tracks state: `○` down → `◐` connecting/pairing → `●`
connected.

## Keys

Every binding is a single un-shifted key.

| Key | Action | | Key | Action |
|---|---|---|---|---|
| `↑ ↓ ← →` / swipe | D-pad | | `1`–`0` | App shortcuts (configurable) |
| `Enter` | Select / OK | | `Space` | Play / Pause |
| `Esc` / `Backspace` | Back | | `x` | Stop |
| `h` | Home | | `,` / `.` | Rewind / Fast-forward |
| `o` | Options / Menu | | `;` / `'` | Previous / Next |
| `p` | Power | | `d` | Devices / discovery |
| `s` | Settings | | `k` | Type on the TV (live) |
| `i` | Input / Source | | `/` | Keycode probe |
| `v` | Voice / Assistant | | `q` | Quit |
| `+` / `-` / `m` | Volume up / down / mute | | `Ctrl-C` | Quit (any mode) |
| `PgUp` / `PgDn` | Channel up / down | | | |

`q` and `Ctrl-C` quit from every mode (in typing mode, only `Ctrl-C`, so a literal
`q` is typed). [`src/keymap.rs`](src/keymap.rs) is the source of truth.

## Multiple TVs & discovery

Press `d` for the **device picker**. clicker scans the LAN over mDNS
(`_androidtvremote2._tcp`) and lists nearby TVs by name — `●` already paired, `○`
newly discovered — plus an **＋ Enter IP manually** row for networks where multicast
is blocked. Select a TV to connect; pairing runs automatically for a new one. A
single shared client certificate is paired with every TV, and clicker remembers
them all, reconnecting to the last one on launch.

## Typing / search mode

Press `k` to type into the TV's focused field — a search box, a login. What you type
mirrors **live** onto the TV via the remote IME; `Backspace` edits, `Enter` submits,
`Esc` cancels. If no field is focused yet, the modal tells you to focus a search box
on the TV first.

## App shortcuts

Digits `1`–`0` launch apps. Defaults are Netflix, YouTube, Disney+, Max, Amazon,
Hulu, Spotify on `1`–`7` (`8`, `9`, `0` are open). Override or add any slot in
`config.toml`:

```toml
[shortcuts]
"8" = { label = "Plex",        kind = "package", target = "com.plexapp.android" }
"9" = { label = "Crunchyroll", kind = "url",     target = "https://www.crunchyroll.com" }
```

`kind = "url"` sends a deep-link URL; `kind = "package"` sends an Android package id
(launches on Play-Store devices). Both go out as `RemoteAppLinkLaunchRequest`.

## Swipe

On a touch terminal (e.g. Termux), **swipe** up/down/left/right over the remote to
drive the D-pad — clicker turns touch drags into D-pad steps. Mouse-wheel scroll
works too on the desktop. (Mouse capture means terminal text-selection needs Shift
held, as with other full-screen TUIs.)

## Keycode probe

Some buttons depend on the specific TV — the generic Input/Source keycode (`178`),
for instance, does nothing on certain TCL sets. Press `/` to open the **keycode
probe**: type any raw [Android keycode](https://developer.android.com/reference/android/view/KeyEvent)
and `Enter` sends it directly, so you can discover what your TV actually responds
to. The modal lists codes worth trying (the direct-HDMI codes `243`–`245` often
work where the generic input doesn't).

## Config & security

Runtime state lives in `~/.config/clicker/`, **never** in the repo:

- `config.toml` — a device registry (each TV's host, name, paired flag, last
  volume), your last-used TV, and any `[shortcuts]` overrides
- `cert.pem` / `key.pem` — the shared client certificate the TVs trust

```toml
last_device = "living-room"

[[device]]
id = "living-room"
name = "Living Room"
host = "192.168.1.50"
paired = true
```

Treat `cert.pem` / `key.pem` like a credential: anyone with that key pair can
control your paired TVs. They're generated locally on first run and are covered by
`.gitignore` (along with `config.toml` and `*.pem` / `*.key`) so they can't be
committed by accident. Nothing clicker writes at runtime ever lands in the repo. A
v1 single-TV `config.toml` is migrated to the registry automatically on first run —
no re-pairing.

## Status

The full remote works: discovery, multi-TV pairing and switching, D-pad,
volume/mute, transport, configurable app launch, live typing, swipe, keepalive, and
auto-reconnect. Validated against a TCL Android TV (RC802V layout). The IME typing
path and mDNS discovery are protocol-correct but TV- and network-dependent, so
they're best confirmed on your own setup.

clicker started life as a member of a personal Rust TUI suite, so it borrows that
suite's ratatui styling, but it's self-contained and builds and runs on its own.

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT) at your option. Unless you explicitly state otherwise,
any contribution intentionally submitted for inclusion shall be dual licensed as
above, without any additional terms or conditions.
