//! Persisted clicker config: `~/.config/clicker/config.toml`.
//!
//! Deliberate divergence from roam's `state.json` — the rest of the suite's
//! only structured file is `theme.toml`, but clicker wants serde + toml here
//! (noted in spec §7.1). Load is tolerant: a missing or malformed file yields
//! `Config::default()` so first run never errors.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Config {
    /// TV IP or mDNS name. `None` on first run → triggers the host prompt (I2/I3).
    pub host: Option<String>,
    /// Display name, learned at pairing (e.g. "Living Room TV").
    pub name: Option<String>,
    /// Whether the saved client cert is already trusted by the TV.
    pub paired: bool,
    /// Last volume level, restored into the UI on launch.
    pub last_volume: Option<u8>,
}

/// `~/.config/clicker`. Falls back to `./.clicker` if there is no home dir
/// (matches roam's defensive `unwrap_or_else` style in `resolve_start`).
pub fn dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("clicker")
}

fn config_path() -> PathBuf {
    dir().join("config.toml")
}

/// Tolerant load: missing file, unreadable file, or bad TOML → default.
pub fn load() -> Config {
    let path = config_path();
    let Ok(text) = std::fs::read_to_string(&path) else {
        return Config::default();
    };
    toml::from_str(&text).unwrap_or_default()
}

/// Serialize to TOML and write `~/.config/clicker/config.toml`, creating the
/// directory if needed.
pub fn save(cfg: &Config) -> anyhow::Result<()> {
    let dir = dir();
    std::fs::create_dir_all(&dir)?;
    let text = toml::to_string_pretty(cfg)?;
    std::fs::write(config_path(), text)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `save` then `load` must round-trip every field. We point `dir()` at a
    /// temp dir by overriding `$XDG_CONFIG_HOME` (which `dirs::config_dir`
    /// honors on Linux), so the test never touches the real `~/.config`.
    #[test]
    fn save_then_load_roundtrips() {
        let tmp = std::env::temp_dir().join(format!("clicker-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        // SAFETY: single-threaded test; we restore nothing because each test
        // process gets a unique temp dir keyed on the pid.
        unsafe { std::env::set_var("XDG_CONFIG_HOME", &tmp); }

        let cfg = Config {
            host: Some("192.168.1.54".into()),
            name: Some("Living Room TV".into()),
            paired: true,
            last_volume: Some(32),
        };
        save(&cfg).expect("save");

        let got = load();
        assert_eq!(got.host.as_deref(), Some("192.168.1.54"));
        assert_eq!(got.name.as_deref(), Some("Living Room TV"));
        assert!(got.paired);
        assert_eq!(got.last_volume, Some(32));

        let _ = std::fs::remove_dir_all(&tmp);
    }

    /// A missing file must yield `Config::default()`, not an error.
    #[test]
    fn missing_file_is_default() {
        let tmp = std::env::temp_dir().join(format!("clicker-missing-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        unsafe { std::env::set_var("XDG_CONFIG_HOME", &tmp); }

        let got = load();
        assert!(got.host.is_none());
        assert!(got.name.is_none());
        assert!(!got.paired);
        assert!(got.last_volume.is_none());
    }
}
