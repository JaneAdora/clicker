# clicker

A terminal Android TV / Google TV remote, native to Jane's Rust TUI suite
(`roam`, `glance`, `wt`, `suite-term`, `rsuite`). Drives the TV over the LAN with
the **Android TV Remote protocol v2** (TLS on TCP 6466/6467) — the same mechanism
the phone remote apps use. **No developer mode, no ADB.**

## Install

Built and installed by rsuite (see the `[[launcher]]` stanza in
`dashboard-suite/suite.toml`):

```
rsuite install clicker      # cargo build --release in ~/projects/clicker
```

or directly:

```
cargo build --release
cp target/release/clicker ~/.local/bin/
```

## First run

1. `clicker` with no `~/.config/clicker/config.toml` prompts for the **TV IP**
   (a startup modal — type the IP, `Enter`).
2. The TV shows a **6-character PIN**. clicker opens the PIN modal — type it,
   `Enter`.
3. On success the client cert is now trusted by the TV; `paired = true` and the
   host/name are saved. Subsequent runs connect straight to the remote.

Config lives at `~/.config/clicker/config.toml` (host, name, paired, last_volume)
plus `cert.pem` / `key.pem` in the same directory.

## Keys

| Key | Action | Key | Action |
|---|---|---|---|
| `↑ ↓ ← →` | D-pad | `Space` | Play/Pause |
| `Enter` | Select | `n` / `p` | Next / Previous |
| `Backspace` | Back | `,` / `.` | Rewind / Fast-fwd |
| `Home` / `g` | Home | `s` | Stop |
| `o` | Menu | `PgUp` / `PgDn` | Channel +/− |
| `+` / `-` | Volume +/− | `Shift+P` | Power |
| `m` | Mute | `?` | Help overlay |
| `q` | Quit | `Ctrl-C` | Quit (any mode) |

(`keymap.rs` is the source of truth; Jane owns the final mapping.) `q` and
`Ctrl-C` quit from **every** mode, including the host/PIN modals. `Esc` inside the
host or PIN modal is a deliberate no-op — it would otherwise strand the connection
task waiting on a PIN that can no longer arrive.

## rsuite registration

clicker is a **launcher** (a standalone bin, not a glance panel), so it registers
in the `[[launcher]]` block of `dashboard-suite/suite.toml`, alongside
`roam`/`wt`/`recall`. It has its own repo (not a workspace member), so rsuite
runs `cargo build --release` at the repo root and installs
`target/release/clicker` into `~/.local/bin`.

Add this stanza to `dashboard-suite/suite.toml` **after testing** (immediately
after the `roam` launcher block, above the `# --- glance panels ---` divider):

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

`default = false` keeps it out of the always-installed set (it needs a TV to be
useful), matching `recall`/`1p`/`health`.

## Manual / integration test checklist

The protocol needs a real TV; these are run by hand against the Living Room TV.

- [ ] **Pair against the real TV.** Fresh `~/.config/clicker/` (back up + remove
      `config.toml`/`cert.pem`/`key.pem`). Launch, enter the TV IP, enter the
      on-screen PIN. Expect: link glyph goes `○` → `◐` → `●`, TV name appears in
      the header, `paired = true` written to config.
- [ ] **Bad PIN rejected cleanly.** Re-pair, type a wrong PIN. Expect: the modal
      shows the error line in alert color and stays open; no crash, no terminal
      corruption.
- [ ] **Every button.** With the cursor visible on the TV home screen, exercise
      each binding and confirm the TV responds: D-pad `↑↓←→`, `Enter` select,
      `Backspace` back, `Home`/`g` home, `o` menu, `Space` play/pause, `n`/`p`
      next/prev, `,`/`.` rewind/ff, `s` stop, `PgUp`/`PgDn` channel +/−,
      `Shift+P` power (toggles the TV — run last).
- [ ] **Volume bar tracks `RemoteSetVolumeLevel`.** Press `+`/`-` and `m`. Expect:
      the on-screen volume bar moves in lock-step with the TV's own OSD, color
      thresholds apply (muted → dim, `vol ≥ 70` → pink, else lavender), and
      `last_volume` is persisted.
- [ ] **Keepalive holds idle.** Pair, then leave clicker untouched for **several
      minutes** (5+). Expect: link stays `●` connected — the connection task is
      answering `RemotePingRequest` with `RemotePingResponse` on its own; no
      disconnect, no UI freeze.
- [ ] **Reconnect after TV sleep/wake.** Put the TV to sleep (`Shift+P` or the
      real remote), wait for the link glyph to drop to `○` (down) with a toast.
      Wake the TV. Expect: clicker reconnects automatically (glyph returns to
      `●`) without restarting clicker; the task swallows the socket error into a
      `TvEvent` rather than panicking.
- [ ] **Clean exit.** `q` from any state. Expect: alt screen left, raw mode
      disabled, cursor restored, no garbled terminal; config saved.
