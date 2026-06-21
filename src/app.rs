// src/app.rs
use crate::config::{Config, DeviceEntry};
use crate::types::{InputMode, LinkState, TvCmd, TvEvent};
use std::time::{Duration, Instant};
use tokio::sync::mpsc::Sender;

const TOAST_TTL: Duration = Duration::from_secs(3);

pub struct App {
    pub config: Config,
    pub tv_name: String,
    pub link: LinkState,
    pub volume: u8,
    pub volume_max: u8,
    pub muted: bool,
    pub mode: InputMode,
    pub transient: Option<(String, Instant)>,
    pub cmd_tx: Sender<TvCmd>,
}

impl App {
    pub fn new(config: Config, cmd_tx: Sender<TvCmd>) -> Self {
        let (tv_name, volume) = match config.active_device() {
            Some(d) => (d.name.clone(), d.last_volume.unwrap_or(0)),
            None => ("(no TV)".to_string(), 0),
        };
        App {
            config,
            tv_name,
            link: LinkState::Down,
            volume,
            volume_max: 100,
            muted: false,
            mode: InputMode::Normal,
            transient: None,
            cmd_tx,
        }
    }

    pub fn apply_tv_event(&mut self, ev: TvEvent) {
        match ev {
            TvEvent::Connected { name } => {
                self.tv_name = name;
                self.link = LinkState::Connected;
                self.mode = InputMode::Normal;
            }
            TvEvent::Disconnected => {
                self.link = LinkState::Down;
                self.toast("disconnected");
            }
            TvEvent::VolumeChanged { level, max, muted } => {
                self.volume = level;
                self.volume_max = max;
                self.muted = muted;
            }
            TvEvent::PairingRequired => {
                self.link = LinkState::Pairing;
                self.mode = InputMode::PinEntry {
                    entered: String::new(),
                    error: None,
                };
            }
            TvEvent::PairingOk => {
                self.mode = InputMode::Normal;
                self.toast("paired");
            }
            TvEvent::PairingFailed(msg) => {
                // Stay in PinEntry, clear the buffer, surface the error.
                self.mode = InputMode::PinEntry {
                    entered: String::new(),
                    error: Some(msg),
                };
            }
            TvEvent::Error(msg) => {
                self.link = LinkState::Down;
                self.toast(msg);
            }
        }
    }

    /// Expire the toast after TOAST_TTL.
    pub fn tick(&mut self) {
        if let Some((_, at)) = &self.transient {
            if at.elapsed() >= TOAST_TTL {
                self.transient = None;
            }
        }
    }

    pub fn toast(&mut self, msg: impl Into<String>) {
        self.transient = Some((msg.into(), Instant::now()));
    }

    /// Current toast text, if any (for the header/footer render).
    pub fn transient_str(&self) -> Option<&str> {
        self.transient.as_ref().map(|(s, _)| s.as_str())
    }
}

// ===========================================================================
// I3: async event loop (`run`) + synchronous key dispatch (`handle_key`,
// `handle_pin_key`). tokio lives entirely inside `run`; `main()` stays sync.
// ===========================================================================

use crate::cert::ClientIdentity;
use crate::config;
use crate::keymap::{self, Action};
use crate::remote;
use crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use futures::StreamExt;
use ratatui::backend::Backend;
use ratatui::Terminal;
use tokio::sync::mpsc;

/// Bounded so a wedged connection task can never make the draw path block;
/// key handlers `try_send` and drop (with a toast) if the channel is full.
const CMD_CHANNEL: usize = 64;
const EVENT_CHANNEL: usize = 64;
/// Render + keepalive tick. Not an animation clock — just toast expiry and a
/// pulse so the connection task's keepalive has a UI heartbeat.
const TICK: Duration = Duration::from_millis(250);

/// The async event loop. tokio lives entirely in here; `main()` stays sync.
/// Persist config; surface a failure via a toast instead of silently dropping it.
fn save_or_toast(app: &mut App) {
    if let Err(e) = config::save(&app.config) {
        app.toast(format!("config save failed: {e}"));
    }
}

pub async fn run<B: Backend>(
    terminal: &mut Terminal<B>,
    cfg: Config,
    id: ClientIdentity,
) -> anyhow::Result<()> {
    // `App` holds a command sender, but the REAL connection channel is minted by
    // `spawn_connection` so that switching TVs can swap in a fresh channel (the
    // old design moved the single receiver once and could never replace it). The
    // placeholder receiver is dropped immediately; nothing reads it.
    let (cmd_tx0, _placeholder_rx) = mpsc::channel::<TvCmd>(CMD_CHANNEL);
    let (ev_tx, mut ev_rx) = mpsc::channel(EVENT_CHANNEL);

    let mut app = App::new(cfg, cmd_tx0);

    let mut conn: Option<tokio::task::JoinHandle<()>> = None;
    if app.config.active_device().is_some() {
        conn = Some(spawn_connection(&mut app, &id, &ev_tx)?);
    } else {
        // First-run: no device yet → capture the TV IP in a DISTINCT HostEntry
        // modal (not the PIN modal) before connecting.
        app.mode = InputMode::HostEntry {
            entered: String::new(),
        };
        app.toast("Enter the TV's IP address");
    }

    let mut events = crossterm::event::EventStream::new();
    let mut ticker = tokio::time::interval(TICK);

    terminal.draw(|f| crate::ui::render(f, &app))?;

    loop {
        let mut dirty = false;
        tokio::select! {
            maybe = events.next() => {
                match maybe {
                    Some(Ok(Event::Key(key))) if key.kind == KeyEventKind::Press => {
                        match handle_key(&mut app, key) {
                            KeyOutcome::Quit => break,
                            KeyOutcome::HostEntered(host) => {
                                // Register a device for this IP (name is provisional —
                                // the real name arrives via Connected) and connect.
                                let dev_id = app.config.unique_id(&config::slugify(&host));
                                app.config.upsert_device(DeviceEntry {
                                    id: dev_id,
                                    name: host.clone(),
                                    host: host.clone(),
                                    paired: false,
                                    last_volume: None,
                                });
                                app.tv_name = host;
                                save_or_toast(&mut app);
                                if let Some(h) = conn.take() {
                                    h.abort();
                                }
                                conn = Some(spawn_connection(&mut app, &id, &ev_tx)?);
                                dirty = true;
                            }
                            KeyOutcome::Redraw => dirty = true,
                            KeyOutcome::Ignored => {}
                        }
                    }
                    Some(Ok(Event::Resize(_, _))) => dirty = true,
                    Some(Ok(_)) => {}
                    Some(Err(_)) | None => break, // stdin closed → exit cleanly
                }
            }
            Some(ev) = ev_rx.recv() => {
                // Persist the fields the config claims to remember. `name` and
                // `last_volume` are otherwise never written, so the config would
                // never self-heal. A simple save per change is fine at this volume.
                // App is the SOLE config writer. Persist onto the ACTIVE device
                // entry (the connection task no longer saves, so it can't clobber
                // the registry/shortcuts from a stale clone).
                match &ev {
                    TvEvent::PairingOk => {
                        let changed = app
                            .config
                            .active_device_mut()
                            .map(|d| !std::mem::replace(&mut d.paired, true))
                            .unwrap_or(false);
                        if changed {
                            save_or_toast(&mut app);
                        }
                    }
                    TvEvent::Connected { name } => {
                        let changed = app
                            .config
                            .active_device_mut()
                            .map(|d| {
                                if d.name != *name {
                                    d.name = name.clone();
                                    true
                                } else {
                                    false
                                }
                            })
                            .unwrap_or(false);
                        if changed {
                            save_or_toast(&mut app);
                        }
                    }
                    TvEvent::VolumeChanged { level, .. } => {
                        let changed = app
                            .config
                            .active_device_mut()
                            .map(|d| {
                                if d.last_volume != Some(*level) {
                                    d.last_volume = Some(*level);
                                    true
                                } else {
                                    false
                                }
                            })
                            .unwrap_or(false);
                        if changed {
                            save_or_toast(&mut app);
                        }
                    }
                    _ => {}
                }
                app.apply_tv_event(ev);
                dirty = true;
            }
            _ = ticker.tick() => {
                app.tick(); // expire 3s toast
                dirty = true;
            }
        }
        if dirty {
            terminal.draw(|f| crate::ui::render(f, &app))?;
        }
    }

    if let Some(h) = conn {
        h.abort();
    }
    Ok(())
}

/// `load_or_generate` already produced one identity; the connection task needs
/// an owned copy. Re-load from disk (cheap, one-time) rather than deriving Clone
/// on the DER types. Always succeeds here because cert/key were just written.
fn id_clone(_id: &ClientIdentity) -> anyhow::Result<ClientIdentity> {
    crate::cert::load_or_generate(&config::dir())
}

/// Mint a FRESH command channel, repoint `app.cmd_tx` at it, and spawn the
/// connection task reading the other end. A new channel per call is what lets
/// device switching abort the old task and start a clean one (the old design
/// moved the single receiver exactly once and could never hand a new task a
/// receiver). Reads host/pairing from `app.config.active_device()`.
fn spawn_connection(
    app: &mut App,
    id: &ClientIdentity,
    ev_tx: &mpsc::Sender<TvEvent>,
) -> anyhow::Result<tokio::task::JoinHandle<()>> {
    let (cmd_tx, cmd_rx) = mpsc::channel::<TvCmd>(CMD_CHANNEL);
    app.cmd_tx = cmd_tx;
    Ok(tokio::spawn(remote::run_connection(
        app.config.clone(),
        id_clone(id)?,
        cmd_rx,
        ev_tx.clone(),
    )))
}

enum KeyOutcome {
    Quit,
    Redraw,
    Ignored,
    HostEntered(String),
}

/// Dispatch a keypress by the current `InputMode`. Synchronous: only `try_send`
/// onto the cmd channel, never `.await` — the draw path must never block.
fn handle_key(app: &mut App, key: KeyEvent) -> KeyOutcome {
    // GLOBAL quit, available in EVERY mode (Normal, Help, HostEntry, PinEntry).
    // Without this, Esc in PinEntry would drop to Normal while the connection task
    // blocks forever waiting for a SubmitPin that can no longer arrive — the only
    // escape from a stuck pairing modal is to quit, so quit must always work.
    // `q` quits in text-entry modes too (an IP/PIN never contains a bare 'q'); use
    // Ctrl-C as the universal belt-and-suspenders quit.
    if matches!(key.code, KeyCode::Char('q'))
        || (matches!(key.code, KeyCode::Char('c')) && key.modifiers.contains(KeyModifiers::CONTROL))
    {
        return KeyOutcome::Quit;
    }

    match &app.mode {
        // First-run host capture and PIN capture share the same keystroke handling.
        InputMode::HostEntry { .. } | InputMode::PinEntry { .. } => handle_pin_key(app, key),
        InputMode::KeyProbe { .. } => handle_probe_key(app, key),
        InputMode::Normal => match keymap::map_normal(key) {
            Some(Action::Quit) => KeyOutcome::Quit,
            Some(Action::EnterProbe) => {
                app.mode = InputMode::KeyProbe {
                    entered: String::new(),
                    last: None,
                };
                KeyOutcome::Redraw
            }
            Some(Action::EnterTextMode) => {
                // IME text entry lands in v1.1; say so rather than silently ignoring.
                app.toast("text entry (k) — coming in v1.1");
                KeyOutcome::Redraw
            }
            Some(Action::Cmd(cmd)) => {
                if app.cmd_tx.try_send(cmd).is_err() {
                    app.toast("link busy — key dropped");
                }
                KeyOutcome::Redraw
            }
            None => KeyOutcome::Ignored,
        },
    }
}

/// Text-capture keystroke handling shared by the first-run host prompt
/// (`InputMode::HostEntry`) and the pairing PIN modal (`InputMode::PinEntry`).
/// Backspace deletes, printable chars append, Enter submits. Esc is a deliberate
/// no-op here (see below). The two modes are distinguished by the variant itself,
/// not by inspecting `config.host`.
fn handle_pin_key(app: &mut App, key: KeyEvent) -> KeyOutcome {
    // Which text-capture modal are we in, and what is its current buffer?
    let (is_host, mut buf) = match &app.mode {
        InputMode::HostEntry { entered } => (true, entered.clone()),
        InputMode::PinEntry { entered, .. } => (false, entered.clone()),
        _ => return KeyOutcome::Ignored,
    };

    // Helper: rebuild the correct variant for the mode we're in.
    let rebuild = |entered: String, error: Option<String>| {
        if is_host {
            InputMode::HostEntry { entered }
        } else {
            InputMode::PinEntry { entered, error }
        }
    };

    match key.code {
        KeyCode::Esc => {
            // DELIBERATE no-op. Dropping to Normal here would strand the connection
            // task: in PinEntry it waits forever for a SubmitPin, and in HostEntry
            // there is no host to connect to. The modal stays open; quit with `q`
            // or Ctrl-C (handled globally in `handle_key`) to leave.
            KeyOutcome::Ignored
        }
        KeyCode::Backspace => {
            buf.pop();
            app.mode = rebuild(buf, None);
            KeyOutcome::Redraw
        }
        KeyCode::Enter => {
            if buf.is_empty() {
                // Empty host: keep waiting silently. Empty PIN: show an error line.
                app.mode = rebuild(buf, Some("empty".into()));
                return KeyOutcome::Redraw;
            }
            if is_host {
                // First-run host prompt: hand the IP back to run() to save + spawn
                // the connection task, then drop the modal (run() picks the next
                // mode — pairing will reopen a PinEntry modal via PairingRequired).
                app.mode = InputMode::Normal;
                KeyOutcome::HostEntered(buf)
            } else {
                // Pairing PIN path: ship the PIN to the connection task.
                if app.cmd_tx.try_send(TvCmd::SubmitPin(buf.clone())).is_err() {
                    app.mode = InputMode::PinEntry {
                        entered: buf,
                        error: Some("link busy — retry".into()),
                    };
                    return KeyOutcome::Redraw;
                }
                // Keep the modal up; the task replies PairingOk/PairingFailed,
                // and apply_tv_event closes it or sets the error line.
                app.mode = InputMode::PinEntry {
                    entered: String::new(),
                    error: None,
                };
                KeyOutcome::Redraw
            }
        }
        KeyCode::Char(c) => {
            // Host prompt accepts IP/hostname chars; PIN accepts hex digits.
            // Accept any printable, non-control char; validation happens on submit
            // (and, for the PIN, via the TV's PairingFailed response).
            if !c.is_control() {
                buf.push(c);
            }
            app.mode = rebuild(buf, None);
            KeyOutcome::Redraw
        }
        _ => KeyOutcome::Ignored,
    }
}

/// Keycode-probe modal: type any Android keycode and fire it (SHORT press). A
/// debug escape hatch for buttons whose standard keycode the TV ignores (e.g. the
/// input/source selector on some TVs). Esc leaves; q/Ctrl-C still quit globally.
fn handle_probe_key(app: &mut App, key: KeyEvent) -> KeyOutcome {
    let (mut entered, last) = match &app.mode {
        InputMode::KeyProbe { entered, last } => (entered.clone(), last.clone()),
        _ => return KeyOutcome::Ignored,
    };
    match key.code {
        KeyCode::Esc => {
            app.mode = InputMode::Normal;
            KeyOutcome::Redraw
        }
        KeyCode::Backspace => {
            entered.pop();
            app.mode = InputMode::KeyProbe { entered, last };
            KeyOutcome::Redraw
        }
        KeyCode::Char(c) if c.is_ascii_digit() => {
            entered.push(c);
            app.mode = InputMode::KeyProbe { entered, last };
            KeyOutcome::Redraw
        }
        KeyCode::Enter => {
            let last = match entered.parse::<i32>() {
                Ok(code) => {
                    if app.cmd_tx.try_send(TvCmd::RawKey(code)).is_err() {
                        Some("link busy — not sent".to_string())
                    } else {
                        Some(format!("sent keycode {code}"))
                    }
                }
                Err(_) => Some("enter a number".to_string()),
            };
            app.mode = InputMode::KeyProbe {
                entered: String::new(),
                last,
            };
            KeyOutcome::Redraw
        }
        _ => KeyOutcome::Ignored,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;

    fn test_app() -> App {
        let (tx, _rx) = tokio::sync::mpsc::channel(8);
        App::new(Config::default(), tx)
    }

    #[test]
    fn connected_sets_link_and_name() {
        let mut app = test_app();
        app.apply_tv_event(TvEvent::Connected {
            name: "Living Room TV".into(),
        });
        assert_eq!(app.link, LinkState::Connected);
        assert_eq!(app.tv_name, "Living Room TV");
    }

    #[test]
    fn volume_changed_updates_fields() {
        let mut app = test_app();
        app.apply_tv_event(TvEvent::VolumeChanged {
            level: 42,
            max: 100,
            muted: true,
        });
        assert_eq!(app.volume, 42);
        assert_eq!(app.volume_max, 100);
        assert!(app.muted);
    }

    #[test]
    fn pairing_required_enters_pin_mode() {
        let mut app = test_app();
        app.apply_tv_event(TvEvent::PairingRequired);
        assert_eq!(app.link, LinkState::Pairing);
        assert!(matches!(
            app.mode,
            InputMode::PinEntry { error: None, .. }
        ));
    }

    #[test]
    fn pairing_failed_sets_error() {
        let mut app = test_app();
        app.apply_tv_event(TvEvent::PairingFailed("bad pin".into()));
        match &app.mode {
            InputMode::PinEntry { error: Some(e), .. } => assert_eq!(e, "bad pin"),
            _ => panic!("expected PinEntry with error"),
        }
    }

    #[test]
    fn pairing_ok_returns_to_normal_and_toasts() {
        let mut app = test_app();
        app.apply_tv_event(TvEvent::PairingRequired);
        app.apply_tv_event(TvEvent::PairingOk);
        assert!(matches!(app.mode, InputMode::Normal));
        assert_eq!(app.transient_str(), Some("paired"));
    }

    #[test]
    fn tick_expires_old_toast() {
        let mut app = test_app();
        app.toast("hi");
        assert!(app.transient.is_some());
        // Inject an Instant ~10s in the past so it is already expired.
        let past = Instant::now() - Duration::from_secs(10);
        app.transient = Some(("hi".into(), past));
        app.tick();
        assert!(app.transient.is_none());
    }

    #[test]
    fn tick_keeps_fresh_toast() {
        let mut app = test_app();
        app.toast("hi");
        app.tick();
        assert!(app.transient.is_some());
    }
}
