//! UniFFI facade over `clicker-core` for the Android app.
//!
//! This file holds the types Kotlin sees and thin wrappers. The logic lives in
//! `controller.rs`, which is plain Rust and runs in host tests with no TV.

use std::sync::Arc;

use clicker_core::types::RemoteKey;

mod controller;

use controller::Controller;

uniffi::setup_scaffolding!();

/// A remote-control button.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Key {
    Up,
    Down,
    Left,
    Right,
    Select,
    Home,
    Back,
    Menu,
    Power,
    VolUp,
    VolDown,
    Mute,
    PlayPause,
    Next,
    Prev,
    Rewind,
    FastForward,
    Stop,
    ChannelUp,
    ChannelDown,
    Settings,
    Input,
    Assist,
}

impl From<Key> for RemoteKey {
    fn from(k: Key) -> Self {
        match k {
            Key::Up => RemoteKey::Up,
            Key::Down => RemoteKey::Down,
            Key::Left => RemoteKey::Left,
            Key::Right => RemoteKey::Right,
            Key::Select => RemoteKey::Select,
            Key::Home => RemoteKey::Home,
            Key::Back => RemoteKey::Back,
            Key::Menu => RemoteKey::Menu,
            Key::Power => RemoteKey::Power,
            Key::VolUp => RemoteKey::VolUp,
            Key::VolDown => RemoteKey::VolDown,
            Key::Mute => RemoteKey::Mute,
            Key::PlayPause => RemoteKey::PlayPause,
            Key::Next => RemoteKey::Next,
            Key::Prev => RemoteKey::Prev,
            Key::Rewind => RemoteKey::Rewind,
            Key::FastForward => RemoteKey::FastForward,
            Key::Stop => RemoteKey::Stop,
            Key::ChannelUp => RemoteKey::ChannelUp,
            Key::ChannelDown => RemoteKey::ChannelDown,
            Key::Settings => RemoteKey::Settings,
            Key::Input => RemoteKey::Input,
            Key::Assist => RemoteKey::Assist,
        }
    }
}

/// A saved TV.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub host: String,
    pub paired: bool,
    pub last_volume: Option<u8>,
}

/// One app shortcut slot. `slot` is "1".."9" or "0"; an empty `target` is an
/// empty slot.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ShortcutSlot {
    pub slot: String,
    pub label: String,
    pub target: String,
}

/// Something the UI should react to.
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum RemoteEvent {
    Connecting { device_id: String },
    Connected { device_id: String, name: String },
    Disconnected { reason: Option<String> },
    PairingRequired,
    /// The pairing code did not go through; the code dialog stays open.
    /// `wrong_code` is true when the TV refused the code or did not answer
    /// it (it then shows a fresh code), false when the TV stopped taking
    /// pairing connections (off, or off the network).
    PairingFailed { message: String, wrong_code: bool },
    PairingOk,
    Volume { level: u8, max: u8, muted: bool },
    TextFieldActive { active: bool },
    DevicesChanged,
    DifferentTv { host: String },
}

/// Exported flat: Kotlin sees `RemoteException.Storage(message)` and
/// `RemoteException.UnknownDevice(message)`, with the `Display` text as the
/// exception message. A non-flat variant with a field named `message`
/// generates Kotlin that clashes with `Throwable.message` and does not compile.
#[derive(Debug, PartialEq, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RemoteError {
    Storage { message: String },
    UnknownDevice,
}

impl std::fmt::Display for RemoteError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RemoteError::Storage { message } => f.write_str(message),
            RemoteError::UnknownDevice => write!(f, "unknown device"),
        }
    }
}

impl std::error::Error for RemoteError {}

/// Implemented in Kotlin. Called from a Rust worker thread; it may call back
/// into `Remote`.
#[uniffi::export(foreign)]
pub trait RemoteListener: Send + Sync {
    fn on_event(&self, event: RemoteEvent);
}

/// The Android-facing remote. Owns a tokio runtime and the controller.
#[derive(uniffi::Object)]
pub struct Remote {
    controller: Controller,
    runtime: Option<tokio::runtime::Runtime>,
}

#[uniffi::export]
impl Remote {
    #[uniffi::constructor]
    pub fn new(data_dir: String, listener: Arc<dyn RemoteListener>) -> Result<Arc<Self>, RemoteError> {
        let storage = |e: std::io::Error| RemoteError::Storage { message: e.to_string() };
        std::fs::create_dir_all(&data_dir).map_err(storage)?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("clicker-rt")
            .enable_all()
            .build()
            .map_err(storage)?;
        let sink: controller::EventSink = Arc::new(move |ev| listener.on_event(ev));
        let controller = Controller::new(data_dir.into(), runtime.handle().clone(), sink);
        // RSA keygen on first run takes a moment; start it now so the first
        // connect does not wait for it.
        controller.preload_identity();
        Ok(Arc::new(Remote { controller, runtime: Some(runtime) }))
    }

    pub fn devices(&self) -> Vec<Device> {
        self.controller.devices()
    }

    pub fn active_device_id(&self) -> Option<String> {
        self.controller.active_device_id()
    }

    pub fn add_device(&self, name: String, host: String, service_name: Option<String>) -> Device {
        self.controller.add_device(name, host, service_name)
    }

    /// Always adds a new, unpaired TV, even at a host a saved TV already
    /// has. Does not change the active device or connect.
    pub fn add_new_device(&self, name: String, host: String, service_name: Option<String>) -> Device {
        self.controller.add_new_device(name, host, service_name)
    }

    pub fn rename_device(&self, id: String, name: String) -> Result<(), RemoteError> {
        self.controller.rename_device(id, name)
    }

    pub fn remove_device(&self, id: String) -> Result<(), RemoteError> {
        self.controller.remove_device(id)
    }

    /// A TV seen by discovery. Returns the saved device id if `service_name`
    /// matches one (updating its host when it changed), else None.
    pub fn note_discovered(&self, service_name: String, host: String) -> Option<String> {
        self.controller.note_discovered(service_name, host)
    }

    pub fn connect(&self, device_id: String) -> Result<(), RemoteError> {
        self.controller.connect(device_id)
    }

    pub fn disconnect(&self) {
        self.controller.disconnect()
    }

    /// Returns false when not connected; the key is dropped.
    pub fn send_key(&self, key: Key) -> bool {
        self.controller.send_key(key)
    }

    pub fn launch(&self, slot: String) -> bool {
        self.controller.launch(slot)
    }

    pub fn submit_pin(&self, pin: String) {
        self.controller.submit_pin(pin)
    }

    pub fn set_text(&self, text: String) -> bool {
        self.controller.set_text(text)
    }

    pub fn submit_text(&self) -> bool {
        self.controller.submit_text()
    }

    /// Always 10 slots, in order 1..9, 0.
    pub fn shortcuts(&self) -> Vec<ShortcutSlot> {
        self.controller.shortcuts()
    }

    pub fn set_shortcut(&self, slot: String, label: String, target: String) -> Result<(), RemoteError> {
        self.controller.set_shortcut(slot, label, target)
    }

    pub fn clear_shortcut(&self, slot: String) -> Result<(), RemoteError> {
        self.controller.clear_shortcut(slot)
    }
}

impl Drop for Remote {
    fn drop(&mut self) {
        // Do not block the Kotlin thread on a keygen still running in the
        // blocking pool; let the runtime wind down on its own.
        if let Some(rt) = self.runtime.take() {
            rt.shutdown_background();
        }
    }
}
