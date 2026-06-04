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

/// Tolerant load: missing file, unreadable file, or bad TOML → default.
pub fn load() -> Config {
    load_from(&dir())
}

/// Serialize to TOML and write `~/.config/clicker/config.toml`, creating the
/// directory if needed.
pub fn save(cfg: &Config) -> anyhow::Result<()> {
    save_to(&dir(), cfg)
}

/// Tolerant load from an explicit config directory. `dir/config.toml` missing,
/// unreadable, or holding bad TOML all yield `Config::default()`. Factoring the
/// path out of `load()` lets tests target a unique temp dir without touching the
/// shared `XDG_CONFIG_HOME` env var (which would race under parallel `cargo test`).
fn load_from(dir: &std::path::Path) -> Config {
    let path = dir.join("config.toml");
    let Ok(text) = std::fs::read_to_string(&path) else {
        return Config::default();
    };
    toml::from_str(&text).unwrap_or_default()
}

/// Serialize to TOML and write `dir/config.toml`, creating `dir` if needed.
/// `save()` delegates here; tests call it with an explicit temp dir so they
/// never mutate process-global env.
fn save_to(dir: &std::path::Path, cfg: &Config) -> anyhow::Result<()> {
    std::fs::create_dir_all(dir)?;
    let text = toml::to_string_pretty(cfg)?;
    std::fs::write(dir.join("config.toml"), text)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `save_to` then `load_from` must round-trip every field. Each test gets its
    /// OWN `tempfile::tempdir()`, and we exercise the path-parameterized helpers
    /// directly — no `XDG_CONFIG_HOME` mutation. The old version set that global
    /// env var, so two config tests running concurrently under default-parallel
    /// `cargo test` would clobber each other's path and fail intermittently.
    /// Driving an explicit path makes the suite deterministically green.
    #[test]
    fn save_then_load_roundtrips() {
        let tmp = tempfile::tempdir().expect("tempdir");

        let cfg = Config {
            host: Some("192.168.1.54".into()),
            name: Some("Living Room TV".into()),
            paired: true,
            last_volume: Some(32),
        };
        save_to(tmp.path(), &cfg).expect("save");

        let got = load_from(tmp.path());
        assert_eq!(got.host.as_deref(), Some("192.168.1.54"));
        assert_eq!(got.name.as_deref(), Some("Living Room TV"));
        assert!(got.paired);
        assert_eq!(got.last_volume, Some(32));
    }

    /// A missing file must yield `Config::default()`, not an error. Uses a fresh
    /// empty temp dir (no global env mutation), so it can run in parallel safely.
    #[test]
    fn missing_file_is_default() {
        let tmp = tempfile::tempdir().expect("tempdir");

        let got = load_from(tmp.path());
        assert!(got.host.is_none());
        assert!(got.name.is_none());
        assert!(!got.paired);
        assert!(got.last_volume.is_none());
    }

    /// The public `dir()`-backed `save()`/`load()` wrappers still delegate to the
    /// helpers correctly. Guard against accidental divergence of the wrappers.
    #[test]
    fn public_wrappers_delegate_to_helpers() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let cfg = Config {
            host: Some("10.0.0.2".into()),
            ..Config::default()
        };
        save_to(tmp.path(), &cfg).expect("save_to");
        let got = load_from(tmp.path());
        assert_eq!(got.host.as_deref(), Some("10.0.0.2"));
    }
}
