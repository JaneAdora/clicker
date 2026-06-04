# clicker — Design Spec

**A terminal Android TV remote, native to Jane's Rust TUI suite.**

Date: 2026-06-04
Status: Design — reviewed (Codex pass 1 + reference cross-check incorporated)
Project home: `/home/jane/projects/clicker/`

---

## 1. Goal

Control an Android TV / Google TV from a Linux laptop over the LAN, the same way a
third-party phone remote app does — but as a keyboard-driven terminal app that looks
and installs like a native member of the existing suite (`roam`, `glance`, `wt`,
`suite-term`, `rsuite`).

**Non-goals (v1):** screen mirroring, voice input, multi-TV management UI. One TV,
configured once, driven from the keyboard.

## 2. Approach decision

Two mechanisms can drive an Android TV:

1. **Android TV Remote protocol v2** — the pre-installed "Android TV Remote Service"
   on the TV, TLS on TCP 6466/6467, paired once with an on-screen PIN. This is what
   the phone apps use. **No developer mode, no ADB.**
2. **ADB** (port 5555) — requires enabling developer options; heavier; better for
   mirroring (scrcpy) than for a remote.

**Chosen: implement protocol v2 natively in Rust.** It is the closest match to the
phone-app experience and needs nothing enabled on the TV. There is **no maintained
Rust crate** for protocol v2 (implementations exist in Python `androidtvremote2`, Go
`atvremote`, Node `androidtv-remote-cli`, Java, iOS), so clicker ports the protocol.
This is more work than wrapping a library, but the published `.proto` files and the
reference implementations de-risk it, and the result is the only known Rust
implementation.

**Reference sources** (read-only, for porting):
- Remote messages + keycodes: `github.com/tronikos/androidtvremote2`
  (`remotemessage.proto`, `TvKeys.txt`).
- Pairing messages: `polo.proto` (same repo). Pairing handshake + hash verified
  against `pairing.py` (tronikos) and `PairingManager.js` (louis49) — see §4.2.

## 3. Architecture

Single binary `clicker`, async on `tokio`. Two run modes:

- **First run / not paired:** generate an RSA client cert → TLS-connect to the TV's
  **pairing** port → walk the pairing handshake → user types the PIN the TV shows →
  cert is now trusted on the TV, saved to disk.
- **Normal run:** load the saved cert → TLS-connect to the **remote** port → enter the
  TUI; keypresses become TV commands in real time.

### Module layout

| Module | One job | Key deps |
|---|---|---|
| `config.rs` | Load/save TV host + cert paths + last volume (`~/.config/clicker/config.toml`) | `serde`, `toml`, `dirs` |
| `cert.rs` | Generate & load the **RSA** client cert/key | `rsa`, `rcgen`, `rustls-pemfile` |
| `tls.rs` | rustls client config that **accepts the TV's self-signed cert** and captures it | `tokio-rustls`, `rustls` |
| `framing.rs` | Read/write **varint-length-delimited** protobuf over the stream | `prost`, `tokio` |
| `proto/` (generated) | `polo.proto` + `remotemessage.proto` → Rust types | `prost-build` (in `build.rs`) |
| `pairing.rs` | The pairing handshake + PIN→secret hash | `sha2`, `x509-parser` |
| `remote.rs` | Connect handshake, keepalive/ping loop, `send_key()` / `launch_app()` API | — |
| `keymap.rs` | Keyboard key → Android keycode (**owner: Jane**) | `crossterm` |
| `theme.rs` | Suite palette + Style builders (copied from roam) | `ratatui` |
| `ui/` (`header.rs`, `body.rs`, `footer.rs`, `modal.rs`) | Suite-styled widgets | `ratatui` |
| `app.rs` | App state, `InputMode` modal state machine, event application | — |
| `main.rs` | suite-term panic hook + terminal lifecycle + `tokio::select!` loop | `crossterm`, `suite-term`, `anyhow`, `futures` |

### Data flow at runtime

`main` spawns a **TV connection task** that owns the TLS socket. It answers the TV's
keepalive pings on its own and exchanges two `mpsc` channels with the UI:

- `TvCmd` (UI → task): `Key(RemoteKey)`, `VolUp`, `VolDown`, `ToggleMute`,
  `LaunchApp(url)`, `SubmitPin(String)`.
- `TvEvent` (task → UI): `Connected{name}`, `Disconnected`, `VolumeChanged(u8)`,
  `Muted(bool)`, `PairingRequired`, `PairingOk`, `PairingFailed(msg)`, `Error(msg)`.

The TUI loop never touches the socket; the connection task never touches the keyboard.
Key handlers are **synchronous** and only `try_send` onto `TvCmd` — the draw path never
blocks (preserves the suite's render invariant even though the app is async).

**Why tokio (not threads):** a TLS stream cannot be cleanly split into independent
read/write halves under blocking I/O (rustls keeps shared state; a blocking reader
thread would hold the lock the writer needs). `tokio_rustls` + `tokio::io::split` +
`tokio::select!` over {keyboard `EventStream`, socket channel, ping/render tick} solves
the concurrent read/write/timeout problem without threads or a mutex.

## 4. Protocol v2 details

All messages are **varint-length-delimited protobuf**: write a varint of the encoded
length, then the message bytes; read symmetrically.

### 4.1 Client certificate — must be RSA

The pairing secret is computed from RSA **modulus + exponent** of both certificates.
`rcgen` defaults to ECDSA, which has no modulus/exponent — so clicker must generate an
**RSA-2048** key (public exponent 65537) with the `rsa` crate, then build the
self-signed cert via `rcgen`. Match the reference cert params
(`certificate_generator.py`): CN + DNS SAN = a hostname (e.g. `clicker`),
**basic constraints CA:TRUE, pathlen 0**, serial 1000, ~10-year validity, SHA-256
signature. Persist cert/key at `~/.config/clicker/`; reuse across sessions (pairing is
one-time).

**rcgen 0.14 API** (the old `params.alg` / `params.key_pair` form is gone):

```rust
let key_pair = rcgen::KeyPair::from_pkcs8_pem_and_sign_algo(&rsa_pkcs8_pem, &rcgen::PKCS_RSA_SHA256)?;
let mut params = rcgen::CertificateParams::new(vec!["clicker".into()])?;
params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Constrained(0));
// + serial 1000, 10-year validity, distinguished name CN=clicker …
let cert = params.self_signed(&key_pair)?;   // -> cert.pem(); key_pair.serialize_pem()
```

### 4.2 Pairing handshake (pairing port, 6467)

1. TLS-connect with the RSA client cert (server cert is self-signed → accept any).
2. → `PairingRequest { service_name = "clicker", client_name = "clicker" }`
3. ← `PairingRequestAck`
4. → `Options { encoding = ENCODING_TYPE_HEXADECIMAL, symbol_length = 6, role = ROLE_TYPE_INPUT }`
   (enum names from `polo.proto`)
5. ← server **`Options`** — the server's own options. **There is no `PairingOptionAck`
   message**; the server replies with an `Options` of its own.
6. → `PairingConfiguration { encoding, client_role }`
7. ← `PairingConfigurationAck` — **TV now displays a 6-hex-char PIN**
8. User enters the PIN.
9. Compute (see byte-encoding note below):
   `secret = SHA256( client_n ‖ client_e ‖ server_n ‖ server_e ‖ code_bytes[1..] )`
   where `code_bytes = fromhex(6-char PIN)` (3 bytes); then verify
   `secret[0] == code_bytes[0]` (the first byte is a checksum).
10. → `PairingSecret { secret }`
11. ← `PairingSecretAck` — paired; the TV trusts our client cert from now on.

> **#1 correctness risk — modulus/exponent byte encoding.** The reference hashes the
> integers as **minimal unsigned big-endian with NO leading zero byte** (`pairing.py`
> uses `bytes.fromhex(f"{n:X}")` for moduli and `bytes.fromhex(f"0{e:X}")` for
> exponents; for a standard 2048-bit modulus this is 256 bytes, and `e=65537` is
> `01 00 01`). **Pitfall:** `x509-parser` returns the server cert's `modulus`/`exponent`
> as DER INTEGER bytes, which **may carry a leading `0x00` sign byte** — strip a single
> leading `0x00` before hashing, or the hash is wrong. Our own cert's `n`/`e` come from
> the `rsa` crate (`BigUint::to_bytes_be()` — already minimal, no sign byte). This is
> the single most likely place for a Rust port to fail; it gets a dedicated
> known-vector unit test (§9).

### 4.3 Remote connection (remote port, 6466)

1. TLS-connect with the now-trusted client cert.
2. Handshake: ← `RemoteConfigure` → → `RemoteConfigure { device_info + feature bits }`;
   ← `RemoteSetActive` → → `RemoteSetActive { active }`, where **`active` is a feature
   bitmask, not a boolean** — echo the active feature bits, not `true`.
3. **Keepalive:** ← `RemotePingRequest { val1 }` → → `RemotePingResponse { val1 }`.
   Must always be ready to answer, even while idle — this is why the socket needs its
   own task.
4. **Send button:** → `RemoteKeyInject { key_code, direction = SHORT }`.
5. **Receive state:** `RemoteSetVolumeLevel { volume_level, volume_max, volume_muted }`
   → drives the on-screen volume bar; `RemoteImeKeyInject` / `RemoteTextFieldStatus`
   → used by v1.1 text input.
6. **Launch app (optional):** → `RemoteAppLinkLaunchRequest { app_link }`.

### 4.4 Verified Rust API notes

- **TLS (rustls 0.23 / tokio-rustls):** install a custom verifier via
  `ClientConfig::builder().dangerous().with_custom_certificate_verifier(Arc::new(V))`.
  `V` must implement **all four** of `verify_server_cert`, `verify_tls12_signature`,
  `verify_tls13_signature`, and `supported_verify_schemes` (delegate the two signature
  methods + schemes to `rustls`' default crypto provider). `verify_server_cert`
  **copies `end_entity` (the server's DER cert) into shared state** for the pairing hash
  and returns `Ok(ServerCertVerified::assertion())`. Set the client cert/key with
  `.with_client_auth_cert(chain, key)`.
- **Server pubkey extraction:** parse the captured DER with `x509-parser`, read the RSA
  `modulus`/`exponent`, and **strip a single leading `0x00`** (see §4.2).
- **Concurrency:** `tokio::io::split` the `tokio_rustls::client::TlsStream` into read +
  write halves owned by the one connection task; varint-frame on each half.

## 5. Buttons & keymap

**Full remote in v1.** Every button is a `RemoteKeyInject` keycode — all cheap. The
canonical Android keycodes live in `keymap.rs` (e.g. `HOME=3`, `BACK=4`, `DPAD_UP=19`
…`DPAD_CENTER=23`, `VOLUME_UP=24`, `VOLUME_DOWN=25`, `POWER=26`, `MENU=82`,
`MEDIA_PLAY_PAUSE=85`, `MEDIA_STOP=86`, `MEDIA_NEXT=87`, `MEDIA_PREVIOUS=88`,
`MEDIA_REWIND=89`, `MEDIA_FAST_FORWARD=90`, `VOLUME_MUTE=164`, `CHANNEL_UP=166`,
`CHANNEL_DOWN=167`).

**`keymap.rs` is Jane's contribution** — it's pure taste/muscle-memory and the natural
place for the build to hand off. Draft mapping (to be finalized by Jane):

| Laptop key | TV button | Laptop key | TV button |
|---|---|---|---|
| `↑ ↓ ← →` | D-pad | `Space` | Play/Pause |
| `Enter` | Select (center) | `n` / `p` | Next / Previous |
| `Backspace` | Back | `,` / `.` | Rewind / Fast-fwd |
| `Home` or `g` | Home | `s` | Stop |
| `o` | Menu / Options | `PgUp` / `PgDn` | Channel +/− |
| `+` / `-` | Volume +/− | `Shift+P` | Power (deliberate) |
| `m` | Mute | `i` | Type mode (v1.1) → `Esc` exits |
| `?` | Help overlay | `q` | Quit clicker |

Power is bound to `Shift+P` (not a bare key) to avoid an accidental power-off.

## 6. Text input — v1.1

Deferred so v1 ships a reliable button remote. Typing into the TV is a **stateful IME
protocol**, not letter keycodes: the TV reports the focused field
(`RemoteTextFieldStatus`: value, selection, `field_counter`); clicker sends
`RemoteImeBatchEdit { ime_counter, field_counter, edit_info: [RemoteEditInfo{ insert,
RemoteImeObject{ start, end, value } }] }`, echoing the correct counters. Counter
desync is the classic bug, which is why it gets its own milestone tested live against
the real TV. UI: `i` toggles a one-line input mode (`Esc` exits); buffered send on
`Enter` first, live per-keystroke if the counter sync proves reliable.

## 7. TUI, styling & suite integration

Derived from a study of `suite-term`, `glance`, `roam`, and `rsuite`. Every convention
cites its suite source.

### 7.1 Suite membership

- **Depend on `suite-term`** at the pinned rev, features `clipboard` + `panic-hook`
  (same line `glance`/`roam`/`wt` use):
  ```toml
  suite-term = { git = "https://github.com/JaneAdora/suite-term", rev = "eec40bcd1516156d5245ef73aeb4a5aef243d497", features = ["clipboard", "panic-hook"] }
  ```
  `suite-term` provides **only** `install_panic_hook()` — no terminal wrapper, and it
  does **not** re-export ratatui/crossterm. So clicker declares its own
  `ratatui = "0.29"` and `crossterm = { version = "0.28", features = ["event-stream"] }`.
  The `event-stream` feature is **required** for the async input loop (it pulls
  `futures-core`); cargo unifies this to suite-term's same `0.28.x`, so there is no
  second crossterm in the tree. Hand-roll terminal setup/restore like `roam/src/main.rs`.
- **Own `theme.rs`, copied from `roam/src/ui/theme.rs`** — the simpler variant. (`glance`
  adds brightness scaling + extra raw colors we don't need; `roam`'s is the clean base.)
  The three core values `pink`/`lavender`/`magenta` are byte-identical across `glance`
  and `roam`; copy roam's and add `sage`/`amber` for link state. Read the shared
  `~/.config/dashboard-suite/theme.toml` override so re-skinning the suite re-skins clicker.
- **Config** at `~/.config/clicker/config.toml` (toml + serde) — a deliberate, noted
  divergence from roam's `state.json` (the rest of the suite's only structured file is
  `theme.toml`). The shared `theme.toml` is still hand-parsed, not serde'd.
- **`[profile.release]`** = `lto = "thin"`, `codegen-units = 1`, `strip = true` (suite
  standard).
- **rsuite registration:** one `[[launcher]]` stanza in `dashboard-suite/suite.toml`:
  ```toml
  [[launcher]]
  name = "clicker"
  summary = "Android TV remote"
  repo = "clicker"
  url = "https://github.com/JaneAdora/clicker"
  artifact = "clicker"
  bin = "clicker"
  requires = []        # native protocol, no external binary (no adb)
  default = false
  ```
  clicker is a **launcher** (standalone bin), not a glance panel.

### 7.2 Palette & styles (`theme.rs`)

Canonical values: `pink #e88b9f`, `lavender #c5a3ff`, `magenta #ff6ec7`. Convention:
headers = lavender BOLD; focused/keycaps/alert = magenta BOLD; active = pink BOLD; dim =
lavender DIM; status/toast = magenta. For connection state the 3-color palette has no
green, so reuse glance's `sage #9be195` (ok) and `amber #ffd96e` (warn) verbatim as
local raw consts (not user-overridable):

```rust
pub fn link_ok()      -> Style { Style::default().fg(sage()).add_modifier(Modifier::BOLD) } // connected
pub fn link_pending() -> Style { Style::default().fg(amber()) }                              // connecting/pairing
pub fn link_down()    -> Style { alert() }                                                   // disconnected
```

Render code never touches `Color::Rgb` directly — only the Style builders (suite rule).

### 7.3 Widgets

- **No outer app border.** Header/body/footer drawn directly; body gets manual 1-col
  side padding; `Borders::ALL` is reserved for modals (glance convention).
- **Panel chrome:** single-edge `Borders::BOTTOM`, `border_style = dim()`, title as a
  single Span with leading+trailing space, left-aligned (glance `cpu.rs`/`net.rs`).
- **Footer key-hint bar:** keycap in magenta-bold, ` label` raw, hints joined by
  `SEP = "  │  "` in `dim()`; toast as a 2nd line in `status()` (roam `ui/footer.rs`).
- **Modal:** `Borders::ALL`, `border_style = pane_header()` (lavender bold), title in
  magenta-bold, `Clear` first into `centered_rect` — copied from `roam/src/ui/mod.rs`.

### 7.4 App skeleton (async, reconciled with the suite's sync loop)

`main()` is **synchronous** like roam (panic-hook **first** → raw mode → alt screen +
`SetTitle("clicker")` → restore-before-unwrap) and bridges to async by building a Tokio
runtime and `block_on`-ing the loop — **not** `#[tokio::main]` (that would make `main`
itself async and break parity with the suite):

```rust
fn main() -> anyhow::Result<()> {
    // …args, suite_term panic hook, enable_raw_mode, EnterAlternateScreen, Terminal…
    let rt = tokio::runtime::Runtime::new()?;
    let result = rt.block_on(run(&mut terminal, &mut app));   // async loop lives here
    // …LeaveAlternateScreen, disable_raw_mode, show_cursor, THEN propagate result…
}
```

tokio lives **entirely inside** `run()`:

```rust
loop {
    let mut dirty = false;
    tokio::select! {
        maybe = events.next() => { /* crossterm EventStream → handle_key (sync, try_send) */ }
        Some(ev) = tv_rx.recv() => { app.apply_tv_event(ev); dirty = true; }
        _ = ping.tick() => { app.tick(); /* expire toast, keepalive */ dirty = true; }
    }
    if dirty { terminal.draw(|f| render(f, app))?; }
}
```

Redraw only on input / socket event / tick (suite's "don't render when nothing
changed" economy). No fast animation tick.

### 7.5 Screens

- **Header:** `clicker` (magenta bold) + TV name (lavender bold) + link glyph
  (`●` sage connected / `◐` amber pairing / `○` magenta-bold down).
- **Body:** a D-pad + full-button cheatsheet (keycaps magenta, labels dim — same
  vocabulary as the footer) + a threshold-colored volume bar
  (muted → dim; `vol ≥ 70` → pink; else lavender).
- **Footer:** the suite key-hint bar listing all bindings.
- **PIN modal (first-run pairing):** roam's `centered_rect` recipe; masked input;
  error line in `alert()` on a bad PIN. Opened on `TvEvent::PairingRequired`.

```
        ┌─────┐
        │  ↑  │        ⏎  select
   ┌────┼─────┼────┐   m  mute
   │ ←  │  ●  │  → │   +  vol up
   └────┼─────┼────┘   -  vol down
        │  ↓  │
        └─────┘
```

## 8. Config

`~/.config/clicker/config.toml`:

```toml
host = "192.168.1.54"      # TV IP (or mDNS name)
name = "Living Room TV"    # display name, learned at pairing
cert_path = "cert.pem"     # relative to ~/.config/clicker/
key_path  = "key.pem"
last_volume = 32           # restored into the UI on launch
```

First run with no `host`: prompt for the TV IP (a startup modal), then pair.

## 9. Testing

The protocol needs a real TV for integration, but the fiddly pure logic is unit-tested:

- **Pairing hash** (`pairing.rs`): known-vector test — fixed client/server
  modulus+exponent + PIN → expected SHA-256 and checksum byte. This is the highest-risk
  unit and gets the most attention.
- **Varint framing** (`framing.rs`): encode→decode round-trip across boundary lengths
  (0, 1, 127, 128, 300, …).
- **Keymap** (`keymap.rs`): key event → expected `RemoteKey`.
- **Manual/integration:** pair against the real TV; verify each button; verify the
  volume bar tracks `RemoteSetVolumeLevel`; verify keepalive holds the connection idle
  for minutes; verify reconnect after the TV sleeps/wakes.

## 10. Build sequence (phasing)

**v1 — reliable button remote**
1. Project scaffold + Cargo deps + `build.rs` (prost) + vendored `.proto` files.
2. `cert.rs` (RSA cert gen/load) + `framing.rs` (+ unit tests).
3. `tls.rs` (accept-self-signed client config, capture server cert).
4. `pairing.rs` (handshake + hash) + the hash unit test. **Milestone: pair with the TV.**
5. `remote.rs` (handshake, keepalive task, `send_key`). **Milestone: arrow keys move the TV.**
6. `theme.rs` + `ui/*` + `app.rs` + `main.rs` (suite-styled TUI, full keymap, volume bar,
   PIN modal). **Milestone: full button remote.**
7. `config.rs` (toml). rsuite `suite.toml` stanza. **Milestone: suite member.**

**v1.1 — text input**
8. IME batch-edit text mode (`i` toggle), tested live for counter sync.

**Later (optional)**
9. App-launch shortcuts (Netflix/YouTube via `RemoteAppLinkLaunchRequest`).

## 11. Open risks

1. **Async vs the suite's sync loop** — resolved by confining tokio to `run()` and
   keeping `main()`/render/key-handlers in the suite's shape (§7.4).
2. **crossterm version skew** — pin to exactly `0.28` to match suite-term.
3. **Pairing modulus/exponent byte encoding** — minimal unsigned big-endian; unit test
   against a known vector (§4.2, §7).
4. **Config divergence from roam's JSON** — deliberate (toml + serde); noted.
5. **IME counter desync** (v1.1) — the reason text input is its own milestone.
6. **TV asleep / connection drop** — the socket task degrades to `link_down()` and the
   UI offers reconnect; the task swallows its own errors into `TvEvent` rather than
   panicking.

## Appendix — crate list

`tokio` (rt-multi-thread, macros, net, time, sync), `tokio-rustls`, `rustls`,
`rustls-pemfile`, `rcgen`, `rsa`, `prost`, `prost-build` (build-dep), `sha2`,
`x509-parser`, `crossterm` (0.28, event-stream), `ratatui` (0.29), `suite-term`
(clipboard, panic-hook), `dirs`, `serde`, `toml`, `anyhow`, `futures`.
