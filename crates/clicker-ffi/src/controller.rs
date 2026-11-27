//! The Android counterpart of the TUI's `App`: owns the config (sole writer),
//! runs the core connection task, and maps core events to `RemoteEvent`s.
//!
//! Locking rule: the state lock is never held while the event sink runs or
//! across an await. Methods collect events under the lock and emit them after
//! releasing it, so a listener may call straight back into the controller.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant};

use clicker_core::cert::{self, ClientIdentity};
use clicker_core::config::{self, Config, DeviceEntry, LaunchKind, Shortcut};
use clicker_core::remote::run_connection;
use clicker_core::types::{TvCmd, TvEvent};
use tokio::sync::{mpsc, OnceCell};
use tokio::task::JoinHandle;

use crate::{Device, Key, RemoteError, RemoteEvent, ShortcutSlot};

pub type EventSink = Arc<dyn Fn(RemoteEvent) + Send + Sync>;

const CMD_CHANNEL: usize = 32;
const EVENT_CHANNEL: usize = 64;
const SLOTS: [char; 10] = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'];
/// A volume change is written to disk at most this often; the latest one
/// is also written when the link drops.
const VOLUME_SAVE_EVERY: Duration = Duration::from_secs(5);

/// The live connection: its command sender, the tasks to abort on teardown,
/// and the host it dialled.
struct Conn {
    cmd_tx: mpsc::Sender<TvCmd>,
    tasks: Vec<JoinHandle<()>>,
    host: String,
    /// True while `host` is an unverified discovery candidate.
    candidate: bool,
}

impl Conn {
    fn abort(self) {
        for t in self.tasks {
            t.abort();
        }
    }
}

struct State {
    cfg: Config,
    /// Bumped on every connect and teardown. Events tagged with an older
    /// generation come from an aborted connection and are dropped.
    generation: u64,
    conn: Option<Conn>,
    /// True only between the core's `Connected` and the next `Disconnected`.
    connected: bool,
    /// Device id to a host seen by discovery but not yet verified by
    /// fingerprint. In memory only; never written to the config.
    candidates: HashMap<String, String>,
    /// A volume level is in `cfg` but not yet on disk.
    volume_unsaved: bool,
    /// When a volume change was last written.
    volume_saved_at: Option<Instant>,
}

impl State {
    /// Abort any live connection and start a new generation.
    fn teardown(&mut self) -> u64 {
        if let Some(c) = self.conn.take() {
            c.abort();
        }
        self.connected = false;
        self.generation += 1;
        self.generation
    }
}

struct Inner {
    dir: PathBuf,
    rt: tokio::runtime::Handle,
    sink: EventSink,
    identity: Arc<OnceCell<ClientIdentity>>,
    state: Mutex<State>,
    /// Config writes so far; tests read it to count saves.
    saves: AtomicUsize,
}

pub struct Controller {
    inner: Arc<Inner>,
}

fn storage(e: impl std::fmt::Display) -> RemoteError {
    RemoteError::Storage { message: e.to_string() }
}

fn to_device(d: &DeviceEntry) -> Device {
    Device {
        id: d.id.clone(),
        name: d.name.clone(),
        host: d.host.clone(),
        paired: d.paired,
        last_volume: d.last_volume,
    }
}

fn slot_char(slot: &str) -> Result<char, RemoteError> {
    let slot = slot.trim();
    let mut chars = slot.chars();
    match (chars.next(), chars.next()) {
        (Some(c), None) if c.is_ascii_digit() => Ok(c),
        _ => Err(storage(format!("invalid shortcut slot {slot:?}"))),
    }
}

/// `ClientIdentity` is not `Clone`; every field is, so copy it by hand.
fn copy_identity(id: &ClientIdentity) -> ClientIdentity {
    ClientIdentity {
        cert_der: id.cert_der.clone(),
        key_der: id.key_der.clone_key(),
        modulus: id.modulus.clone(),
        exponent: id.exponent.clone(),
    }
}

async fn load_identity(cell: &OnceCell<ClientIdentity>, dir: &Path) -> Result<ClientIdentity, String> {
    let dir = dir.to_path_buf();
    let id = cell
        .get_or_try_init(|| async move {
            tokio::task::spawn_blocking(move || cert::load_or_generate(&dir))
                .await
                .map_err(|e| e.to_string())?
                .map_err(|e| format!("client identity: {e}"))
        })
        .await?;
    Ok(copy_identity(id))
}

impl Controller {
    pub fn new(dir: PathBuf, rt: tokio::runtime::Handle, sink: EventSink) -> Self {
        let cfg = config::load_from(&dir);
        let state = State {
            cfg,
            generation: 0,
            conn: None,
            connected: false,
            candidates: HashMap::new(),
            volume_unsaved: false,
            volume_saved_at: None,
        };
        Controller {
            inner: Arc::new(Inner {
                dir,
                rt,
                sink,
                identity: Arc::new(OnceCell::new()),
                state: Mutex::new(state),
                saves: AtomicUsize::new(0),
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.inner.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn emit(&self, events: Vec<RemoteEvent>) {
        for ev in events {
            (self.inner.sink)(ev);
        }
    }

    /// Write the whole config. Whatever volume was pending is on disk after.
    fn save(&self, st: &mut State) -> Result<(), RemoteError> {
        self.inner.saves.fetch_add(1, Ordering::SeqCst);
        config::save_to(&self.inner.dir, &st.cfg).map_err(storage)?;
        st.volume_unsaved = false;
        Ok(())
    }

    /// Write a volume level that is still only in memory, if there is one.
    fn flush_volume(&self, st: &mut State) {
        if st.volume_unsaved {
            let _ = self.save(st);
        }
    }

    /// Load or generate the client identity in the background.
    pub fn preload_identity(&self) {
        let cell = self.inner.identity.clone();
        let dir = self.inner.dir.clone();
        self.inner.rt.spawn(async move {
            let _ = load_identity(&cell, &dir).await;
        });
    }

    pub fn devices(&self) -> Vec<Device> {
        self.lock().cfg.devices.iter().map(to_device).collect()
    }

    pub fn active_device_id(&self) -> Option<String> {
        self.lock().cfg.active_device().map(|d| d.id.clone())
    }

    pub fn add_device(&self, name: String, host: String, service_name: Option<String>) -> Device {
        let (name, host) = (name.trim().to_string(), host.trim().to_string());
        let st = self.lock();
        // After a known-device switch two entries can share a host. Prefer the
        // one whose identity is known, then the active one, over a stale entry.
        let active = st.cfg.last_device.clone();
        let same_host = || st.cfg.devices.iter().filter(|d| d.host == host);
        let existing = same_host()
            .find(|d| d.fingerprint.is_some())
            .or_else(|| same_host().find(|d| Some(&d.id) == active.as_ref()))
            .or_else(|| same_host().next());
        if let Some(d) = existing {
            return to_device(d);
        }
        self.insert_device(st, name, host, service_name)
    }

    /// Always create a new, unpaired entry, even when another device already
    /// has this host (the "add it as a new TV" path after `DifferentTv`).
    /// Does not touch other devices, change the active device, or connect.
    pub fn add_new_device(&self, name: String, host: String, service_name: Option<String>) -> Device {
        let (name, host) = (name.trim().to_string(), host.trim().to_string());
        let st = self.lock();
        self.insert_device(st, name, host, service_name)
    }

    /// Push a new entry built from trimmed `name` and `host`, save, and emit
    /// `DevicesChanged`.
    fn insert_device(
        &self,
        mut st: MutexGuard<'_, State>,
        name: String,
        host: String,
        service_name: Option<String>,
    ) -> Device {
        let id = st.cfg.unique_id(&config::slugify(&name));
        let entry = DeviceEntry { id, name, host, service_name, ..Default::default() };
        let device = to_device(&entry);
        st.cfg.devices.push(entry);
        // The signature has no error channel; a failed save leaves the device
        // in memory and the next successful save writes it.
        let _ = self.save(&mut st);
        drop(st);
        self.emit(vec![RemoteEvent::DevicesChanged]);
        device
    }

    pub fn rename_device(&self, id: String, name: String) -> Result<(), RemoteError> {
        let name = name.trim().to_string();
        if name.is_empty() {
            return Err(storage("device name is empty"));
        }
        let mut st = self.lock();
        let d = st.cfg.devices.iter_mut().find(|d| d.id == id).ok_or(RemoteError::UnknownDevice)?;
        d.name = name;
        self.save(&mut st)?;
        drop(st);
        self.emit(vec![RemoteEvent::DevicesChanged]);
        Ok(())
    }

    pub fn remove_device(&self, id: String) -> Result<(), RemoteError> {
        let mut st = self.lock();
        let idx = st.cfg.devices.iter().position(|d| d.id == id).ok_or(RemoteError::UnknownDevice)?;
        st.cfg.devices.remove(idx);
        st.candidates.remove(&id);
        let mut events = Vec::new();
        if st.cfg.last_device.as_deref() == Some(id.as_str()) {
            st.cfg.last_device = None;
            if st.conn.is_some() {
                st.teardown();
                events.push(RemoteEvent::Disconnected { reason: None });
            }
        }
        // Emit even if the save fails: the connection is already torn down.
        let saved = self.save(&mut st);
        drop(st);
        events.push(RemoteEvent::DevicesChanged);
        self.emit(events);
        saved
    }

    /// A TV seen by discovery. Discovery is unauthenticated, so a new host for
    /// a saved device is only remembered as a candidate, and only for a device
    /// with a saved fingerprint to check it against. A device without one
    /// returns None so the UI lists the TV as new.
    pub fn note_discovered(&self, service_name: String, host: String) -> Option<String> {
        let mut st = self.lock();
        let d = st.cfg.devices.iter().find(|d| d.service_name.as_deref() == Some(service_name.as_str()))?;
        let id = d.id.clone();
        if d.host == host {
            st.candidates.remove(&id);
        } else if d.fingerprint.is_some() {
            st.candidates.insert(id.clone(), host);
        } else {
            return None;
        }
        Some(id)
    }

    pub fn connect(&self, device_id: String) -> Result<(), RemoteError> {
        let mut st = self.lock();
        if !st.cfg.devices.iter().any(|d| d.id == device_id) {
            return Err(RemoteError::UnknownDevice);
        }
        // Tear the old connection down first so none of its events can land
        // on the new device.
        let generation = st.teardown();
        // `run_connection` reads its target from `active_device()`, so set it
        // before cloning the config. Persisting it is a convenience for the
        // next launch; a failed save does not stop the connect. Reconnecting
        // to the same TV changes nothing on disk, so it writes nothing, apart
        // from a volume level still pending from the old link.
        let changed = st.cfg.last_device.as_deref() != Some(device_id.as_str());
        st.cfg.last_device = Some(device_id.clone());
        if changed {
            let _ = self.save(&mut st);
        } else {
            self.flush_volume(&mut st);
        }
        let mut cfg = st.cfg.clone();
        let candidate = st.candidates.get(&device_id).cloned();
        let Some(dev) = cfg.active_device_mut() else { return Err(RemoteError::UnknownDevice) };
        if let Some(h) = &candidate {
            // Dial the candidate without writing it anywhere; it is adopted
            // only after the fingerprint check in `on_identity`.
            dev.host = h.clone();
        }
        let host = dev.host.clone();
        let (cmd_tx, cmd_rx) = mpsc::channel(CMD_CHANNEL);
        let (ev_tx, mut ev_rx) = mpsc::channel(EVENT_CHANNEL);

        let cell = self.inner.identity.clone();
        let dir = self.inner.dir.clone();
        let conn_task = self.inner.rt.spawn(async move {
            match load_identity(&cell, &dir).await {
                Ok(id) => run_connection(cfg, id, cmd_rx, ev_tx).await,
                Err(msg) => {
                    let _ = ev_tx.send(TvEvent::Error(msg)).await;
                }
            }
        });

        let weak: Weak<Inner> = Arc::downgrade(&self.inner);
        let forward_task = self.inner.rt.spawn(async move {
            while let Some(ev) = ev_rx.recv().await {
                let Some(inner) = weak.upgrade() else { break };
                Controller { inner }.handle_tv_event_for(generation, ev);
            }
        });

        st.conn = Some(Conn { cmd_tx, tasks: vec![conn_task, forward_task], host, candidate: candidate.is_some() });
        drop(st);
        self.emit(vec![RemoteEvent::Connecting { device_id }]);
        Ok(())
    }

    pub fn disconnect(&self) {
        let mut st = self.lock();
        st.teardown();
        self.flush_volume(&mut st);
        drop(st);
        self.emit(vec![RemoteEvent::Disconnected { reason: None }]);
    }

    /// Send `cmd` if connected (or always, with `force`). False if dropped.
    fn send(&self, cmd: TvCmd, force: bool) -> bool {
        let st = self.lock();
        match &st.conn {
            Some(c) if st.connected || force => c.cmd_tx.try_send(cmd).is_ok(),
            _ => false,
        }
    }

    pub fn send_key(&self, key: Key) -> bool {
        self.send(TvCmd::Key(key.into()), false)
    }

    pub fn launch(&self, slot: String) -> bool {
        let target = {
            let st = self.lock();
            slot_char(&slot).ok().and_then(|c| st.cfg.shortcut(c)).map(|s| s.target)
        };
        match target {
            Some(t) => self.send(TvCmd::LaunchApp(t), false),
            None => false,
        }
    }

    pub fn submit_pin(&self, pin: String) {
        self.send(TvCmd::SubmitPin(pin), true);
    }

    pub fn set_text(&self, text: String) -> bool {
        self.send(TvCmd::SetImeText(text), false)
    }

    pub fn submit_text(&self) -> bool {
        self.send(TvCmd::SubmitText, false)
    }

    pub fn shortcuts(&self) -> Vec<ShortcutSlot> {
        let st = self.lock();
        SLOTS
            .iter()
            .map(|&c| {
                let (label, target) = st.cfg.shortcut(c).map(|s| (s.label, s.target)).unwrap_or_default();
                ShortcutSlot { slot: c.to_string(), label, target }
            })
            .collect()
    }

    pub fn set_shortcut(&self, slot: String, label: String, target: String) -> Result<(), RemoteError> {
        let c = slot_char(&slot)?;
        let (label, target) = (label.trim().to_string(), target.trim().to_string());
        if target.is_empty() {
            return Err(storage("shortcut target is empty"));
        }
        let kind = if target.contains("://") { LaunchKind::Url } else { LaunchKind::Package };
        let mut st = self.lock();
        st.cfg.shortcuts.insert(c.to_string(), Shortcut { label, kind, target });
        self.save(&mut st)
    }

    pub fn clear_shortcut(&self, slot: String) -> Result<(), RemoteError> {
        let c = slot_char(&slot)?;
        let empty = Shortcut { label: String::new(), kind: LaunchKind::Url, target: String::new() };
        let mut st = self.lock();
        st.cfg.shortcuts.insert(c.to_string(), empty);
        self.save(&mut st)
    }

    /// Install a command channel with no tasks, dialled at the active device's
    /// saved host, so tests can drive events and read commands.
    #[cfg(test)]
    fn attach_for_test(&self) -> (u64, mpsc::Receiver<TvCmd>) {
        let (cmd_tx, cmd_rx) = mpsc::channel(CMD_CHANNEL);
        let mut st = self.lock();
        let generation = st.teardown();
        let host = st.cfg.active_device().map(|d| d.host.clone()).unwrap_or_default();
        st.conn = Some(Conn { cmd_tx, tasks: Vec::new(), host, candidate: false });
        (generation, cmd_rx)
    }
}

mod events;
#[cfg(test)]
mod tests;
