# clicker

A terminal remote for Android TV / Google TV, written in Rust. It speaks the
**Android TV Remote protocol v2** directly over your LAN — the same TLS-on-TCP
mechanism the official phone remote apps use — so there's **no ADB, no developer
mode, and no companion app**. Pair once with the on-screen PIN, then drive the TV
from a terminal.

As far as I can tell this is the first from-scratch Rust implementation of the v2
pairing + command protocol (TLS client cert, protobuf messages, the SHA-256
pairing-secret handshake). If you've been trying to talk to an Android TV from
Rust, the protocol code in [`src/`](src/) is the interesting part.

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
       Ⓣ TCL apps [5]

            ▶ Play/Pause [space]
                 ■ Stop [x]

          << Rew [,]      >> FF [.]
         |< Prev [;]     >| Next [']

            █████░░░░░░░░░░░   32

        [q] quit   [/] keycode probe
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
  protobuf messages: key injections, app-launch links, volume state, and a
  ping/pong keepalive.

clicker implements both. Notable build choices: the [`ring`](https://github.com/briansmith/ring)
rustls provider (no `cmake` needed) and [`protox`](https://github.com/andrewhickman/protox)
for pure-Rust protobuf compilation (no system `protoc` needed) — so `cargo build`
just works with no external toolchain.

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

1. `clicker` with no existing config prompts for the **TV's IP address** (a startup
   modal — type the IP, then `Enter`). You can find it under the TV's
   *Settings → Network*.
2. The TV displays a **6-character PIN**. clicker opens a PIN modal — type it and
   press `Enter`.
3. On success the TV trusts clicker's certificate, `paired = true` is saved, and
   subsequent runs connect straight to the remote.

The link glyph in the header tracks state: `○` down → `◐` connecting/pairing → `●`
connected.

## Keys

Every binding is a single un-shifted key.

| Key | Action | | Key | Action |
|---|---|---|---|---|
| `↑ ↓ ← →` | D-pad | | `1` | Netflix |
| `Enter` | Select / OK | | `2` | YouTube |
| `Esc` / `Backspace` | Back | | `3` | Disney+ |
| `h` | Home | | `4` | Max |
| `o` | Options / Menu | | `5` | TCL apps |
| `p` | Power | | `Space` | Play / Pause |
| `s` | Settings | | `x` | Stop |
| `i` | Input / Source | | `,` / `.` | Rewind / Fast-fwd |
| `v` | Voice / Assistant | | `;` / `'` | Previous / Next |
| `+` / `-` | Volume up / down | | `q` | Quit |
| `m` | Mute | | `/` | Keycode probe |
| `PgUp` / `PgDn` | Channel up / down | | `k` | Text entry *(planned, v1.1)* |

`q` and `Ctrl-C` quit from every mode. App shortcuts (`1`–`5`) send
`RemoteAppLinkLaunchRequest` deep links; Netflix and YouTube are reliable, the
others are best-known URLs and may need tuning per TV. [`src/keymap.rs`](src/keymap.rs)
is the source of truth.

## Keycode probe

Some buttons depend on the specific TV — the generic Input/Source keycode (`178`),
for instance, does nothing on certain TCL sets. Press `/` to open the **keycode
probe**: type any raw [Android keycode](https://developer.android.com/reference/android/view/KeyEvent)
and `Enter` sends it directly, so you can discover what your TV actually responds
to. The modal lists codes worth trying (the direct-HDMI codes `243`–`245` often
work where the generic input doesn't).

## Config & security

Runtime state lives in `~/.config/clicker/`, **never** in the repo:

- `config.toml` — TV host, name, paired flag, last volume
- `cert.pem` / `key.pem` — the client certificate the TV trusts

Treat `cert.pem` / `key.pem` like a credential: anyone with that key pair can
control your paired TV. They're generated locally on first run and are covered by
`.gitignore` (along with `config.toml` and `*.pem` / `*.key`) so they can't be
committed by accident. Nothing clicker writes at runtime ever lands in the repo.

## Status

Tested against a TCL Android TV with the RC802V remote layout. The core remote —
pairing, D-pad, volume/mute, transport, app launch, keepalive, auto-reconnect — is
working. Text/IME entry (`k`) is stubbed for v1.1.

clicker started life as a member of a personal Rust TUI suite, so it borrows that
suite's ratatui styling, but it's self-contained and has no suite dependency — it
builds and runs on its own.

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or
[MIT license](LICENSE-MIT) at your option. Unless you explicitly state otherwise,
any contribution intentionally submitted for inclusion shall be dual licensed as
above, without any additional terms or conditions.
