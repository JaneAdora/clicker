# clicker v2 — design spec

- **Date:** 2026-06-21
- **Status:** Approved (brainstorm) — pending implementation plan
- **Supersedes/extends:** `2026-06-04-clicker-design.md` (v1 core protocol + TUI)

## Context & motivation

clicker v1 is a working native Rust Android TV Remote (protocol v2): TLS pairing on
`:6467`, remote control on `:6466`, a TCL-RC802V-mirrored TUI, keycode probe. It is
single-TV (one flat config + one cert) and Jane-specific in places (hardcoded app
shortcuts, a TCL placeholder).

Jane now uses clicker daily over Termux and on the laptop in preference to a paid
phone app — lower latency, better UI. The goal of v2 is to make it **useful for more
than just Jane**: friendlier onboarding, multiple TVs, real text entry, richer input,
and configurable shortcuts — while keeping the lean, low-latency feel.

## Goals

1. **Multi-TV + friendly pairing.** Discover TVs on the LAN by name (no typing IPs),
   keep a saved list, switch between them. One shared client cert paired with each TV.
2. **Typing/search mode.** Flip to a mode and type; text mirrors live into the TV's
   focused field (IME), so search no longer means arrow-key pecking.
3. **Configurable app shortcuts** across all ten number keys (0–9), with sensible
   built-in defaults and per-slot override in config.
4. **Swipe → D-pad.** Left/right (and native up/down) swipe drive the D-pad, not just
   Termux's vertical-swipe-to-arrow behavior.
5. **Generalization.** Defaults that work with zero config; remove Jane-specific bits.

## Non-goals (deferred — see §11)

In-app shortcut editor · tap→OK / long-press→Back touch gestures · `crates.io`
publish · any per-TV client cert (we deliberately share one identity across TVs).

## Decisions locked in brainstorm

- **Scope:** one v2 design, built in ordered phases (§3).
- **Discovery:** mDNS auto-discovery, with manual IP entry as fallback.
- **Typing mode:** live IME mirror (letters appear on the TV as typed).
- **Shortcuts:** config-file driven with curated defaults now; in-app editor later.
- **Swipe:** swipes → D-pad (all four directions) now; full touch-remote later.
- **Config model:** single `config.toml`, auto-migrated from the v1 flat config
  (option A). clicker owns the file.

## 3. Phasing

| Phase | Feature | Depends on |
|---|---|---|
| **P1** | Config/data model v2 + multi-TV registry (shared cert) + migration | — |
| **P2** | mDNS discovery + device-picker mode + pairing via picker | P1 |
| **P3** | Typing/search mode (live IME mirror) | P1 |
| **P4** | Swipe → D-pad (mouse capture) | — (independent) |
| **P5** | Configurable 0–9 shortcuts + curated defaults | P1 |

Each phase ships independently usable. P4 is independent of the config work and can
land any time; P2/P3/P5 build on P1's config model.

## 4. Config & data model (P1)

### 4.1 Schema

`~/.config/clicker/config.toml` becomes a device registry plus a shortcuts table. The
shared client identity stays as `cert.pem` / `key.pem` in the same directory.

```toml
last_device = "living-room"     # auto-connect target on launch (optional)

[[device]]
id        = "living-room"       # stable slug; generated from name on first save
name      = "Living Room"       # display name (from mDNS TXT or pairing)
host      = "192.168.0.157"
paired    = true
last_volume = 13                # per-device

[[device]]
id        = "bedroom"
name      = "Bedroom TV"
host      = "192.168.0.42"
paired    = true

[shortcuts]                     # digit -> app; absent digits fall back to defaults
"1" = { label = "Netflix", kind = "url",     target = "https://www.netflix.com/title" }
"2" = { label = "YouTube", kind = "url",     target = "https://www.youtube.com" }
"3" = { label = "Disney+", kind = "package", target = "com.disney.disneyplus" }
"4" = { label = "Max",     kind = "package", target = "com.wbd.stream" }
"5" = { label = "Amazon",  kind = "package", target = "com.amazon.amazonvideo.livingroom" }
# 8, 9, 0 left open by default
```

### 4.2 Types

- `DeviceEntry { id: String, name: String, host: String, paired: bool, last_volume: u8 }`
- `Shortcut { label: String, kind: LaunchKind, target: String }`,
  `enum LaunchKind { Url, Package }`
- `Config { last_device: Option<String>, devices: Vec<DeviceEntry>, shortcuts: BTreeMap<String, Shortcut> }`
- Helpers: `active_device()`, `device_mut(id)`, `upsert_device()`, `set_last_device()`,
  `shortcut(digit) -> Shortcut` (returns the configured entry or the built-in default).

### 4.3 Migration

On load, if the file matches the **v1 flat shape** (`host`/`name`/`paired`/
`last_volume` at top level, no `[[device]]`), convert it into a single `DeviceEntry`
(`id` slugged from `name`, e.g. `"android-tv"`), set `last_device` to it, and rewrite
in v2 form. Jane's current pairing and cert keep working with no re-pair. Migration is
covered by a unit test (legacy string in → expected v2 `Config` out).

### 4.4 Persistence

clicker writes the file on: pairing success, connect, volume change, device add/rename,
and `last_device` change. Writes are whole-file (clicker owns it; comments are not
preserved — accepted per decision A).

## 5. Discovery, multi-TV & pairing (P2)

### 5.1 Discovery

- New `discovery.rs`: browse `_androidtvremote2._tcp.local.` via `mdns-sd`
  (pure-Rust, no system deps). Each found service yields `{ name, host, port }`
  (name from the instance/TXT). Emitted to the UI as `TvEvent::DiscoveredDevice`.
- Discovery runs as a task started when the picker opens (and optionally at launch if
  no `last_device`). It is best-effort; failures are non-fatal.
- **Termux caveat:** multicast may be limited on some Android/Wi-Fi stacks. The manual
  fallback (§5.2) always works; documented in README.

### 5.2 Device-picker mode

- New `InputMode::DevicePicker { rows, selected, discovering }`. Opened with `d`.
- Rows = saved devices (with `●/○` connected marker) ∪ freshly discovered ones not yet
  saved, plus a trailing **"＋ Enter IP manually"** row → routes to the existing
  `HostEntry` flow.
- Inside the picker, arrow keys move the selection (in Normal mode arrows are the
  D-pad; the picker captures them while open). Enter = connect (pair first if
  unpaired); `esc` = close.
- Selecting an unpaired/new device runs the existing PIN pairing flow, then saves it.

### 5.3 Connection target

`remote.rs` connects to the **active device's** host. Switching devices in the picker
tears down the current connection task and starts a new one for the selected host.

## 6. Typing / search mode (P3)

### 6.1 Behavior

- New `InputMode::TextInput { buffer: String, field_active: bool }`, entered with `k`
  (replaces the v1.1 stub toast).
- Each edit to `buffer` is sent to the TV's focused field via `RemoteImeBatchEdit`
  (replace field contents with `buffer`), so text appears **live** on the TV.
  Backspace edits the buffer; **Enter** submits (IME action / `KEYCODE_ENTER`);
  **Esc** exits the mode.
- If no field is active, the modal shows a hint: *"focus a search box on the TV first."*

### 6.2 Protocol plumbing

- `remote.rs` handles inbound `RemoteImeShowRequest` / field-status messages → tracks
  the active field + `ime_counter`, emits `TvEvent::TextFieldActive(bool)`.
- New `TvCmd::SetImeText(String)` (build `RemoteImeBatchEdit` from buffer + counter)
  and `TvCmd::SubmitText`.
- The IME counter handshake is the one protocol unknown; if a TV doesn't surface a
  clean field status, the mode still sends batch edits but the hint stays neutral.
  This is validated against a real TV in the manual checklist.

## 7. App shortcuts (P5)

- Shortcuts read from `Config.shortcuts` for digits `0`–`9` (not hardcoded consts).
- `Shortcut.kind`: `Url` → existing `RemoteAppLinkLaunchRequest` with the URL;
  `Package` → app-link launch by Android package name (more reliable across TVs than
  web URLs for several apps).
- Ships a curated default map across keys **1–0**: `1` Netflix, `2` YouTube,
  `3` Disney+, `4` Max, `5` Amazon (Prime Video), `6` Hulu, `7` Spotify; `8`, `9`, `0`
  left open. Works out of the box; any slot overridable in config. The TCL placeholder
  is removed.
- `ui/body.rs` renders the on-screen app list from config, not a fixed list.

## 8. Swipe → D-pad (P4)

- `main.rs` adds `EnableMouseCapture` on enter / `DisableMouseCapture` on exit (and in
  the panic hook teardown).
- `app.rs` handles `Event::Mouse`: map `MouseEventKind::ScrollUp/Down/Left/Right`
  (and drag-based swipe deltas where the terminal reports them) → D-pad Up/Down/Left/
  Right `TvCmd::Key`. Pure mapping fn is unit-tested.
- Tradeoff documented: terminal text selection then requires Shift held (standard for
  mouse-capturing TUIs). Tap→OK / long-press→Back deferred to the later touch phase.

## 9. Architecture impact (summary)

- **types.rs:** `InputMode` += `DevicePicker`, `TextInput`; `TvCmd` += `SetImeText`,
  `SubmitText`, richer `LaunchApp`; `TvEvent` += `DiscoveredDevice`, `TextFieldActive`;
  new `DeviceEntry`, `Shortcut`, `LaunchKind`.
- **config.rs:** registry schema + migration + helpers.
- **discovery.rs (new):** mDNS browse task.
- **remote.rs:** connect to active device; IME inbound/outbound; app-link by package.
- **app.rs:** `handle_picker_key`, `handle_text_input_key`, `Event::Mouse` handling,
  field-active state, device-switch teardown/restart.
- **main.rs:** mouse capture lifecycle.
- **keymap.rs:** `d` → picker, `k` → real typing mode, digits resolve via config.
- **ui/:** new picker render + text-input render; app list from config; footer/body
  hints updated (`[d] devices`, `[k] type`).
- **Cargo.toml:** add `mdns-sd`.

## 10. Testing

Unit (pure logic, no TV):
- Config v1→v2 migration; shortcut parse + default fallback; mouse→D-pad mapping;
  IME batch-edit construction from buffer; slug generation.
- `TestBackend` snapshots: device-picker mode, text-input mode (active + no-field).

Manual checklist (real TV):
- Discovery lists the TV by name; pair via picker; switch between two TVs.
- Typing mode mirrors live into a search box; Enter submits; Esc exits.
- Swipe L/R + U/D drive the D-pad in Termux and a desktop terminal.
- Each configured 0–9 shortcut launches its app (URL and package kinds).
- v1 config auto-migrates with no re-pair.

## 11. Distribution & deferred

- **Distribution:** the `suite-term` git dependency blocks a `crates.io` publish. To
  enable `cargo install clicker` for strangers, either vendor the few theme helpers
  clicker uses or gate `suite-term` behind an optional feature. Slotted to P5/later;
  flagged so it is not a surprise.
- **Deferred:** in-app shortcut editor; tap→OK / long-press→Back; crates.io publish;
  per-TV certs.

## 12. Open questions / risks

- **IME field-status handshake** (P3) is the main protocol unknown — exact counter
  semantics confirmed against a real TV during implementation.
- **mDNS in Termux** multicast reliability — mitigated by the manual fallback.
- **Mouse-reporting differences** across terminals (Termux vs xterm vs kitty) — the
  mapping handles scroll events; drag-swipe support is best-effort per terminal.
