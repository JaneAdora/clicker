// src/app.rs
use crate::config::Config;
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
        let tv_name = config
            .name
            .clone()
            .unwrap_or_else(|| "(no TV)".to_string());
        let volume = config.last_volume.unwrap_or(0);
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
