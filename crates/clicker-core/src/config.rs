//! Persisted clicker config: `~/.config/clicker/config.toml`.
//!
//! v2 schema: a device registry (one shared client cert paired with many TVs)
//! plus a `[shortcuts]` table. Load is tolerant — a missing or malformed file
//! yields `Config::default()` so first run never errors — and auto-migrates the
//! v1 flat shape (`host`/`name`/`paired`/`last_volume` at the top level) into a
//! single `[[device]]`. clicker owns the file (whole-file writes).

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

/// How an app shortcut launches. Both kinds currently serialize their `target`
/// string into `RemoteAppLinkLaunchRequest.app_link`; per the Android TV Remote
/// integration, `app_link` accepts either a package id (Play-Store devices) or a
/// deep-link URL. The enum keeps config expressive and future launch paths open.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LaunchKind {
    #[default]
    Url,
    Package,
}

/// One configurable app shortcut (a digit 0-9 -> app).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Shortcut {
    pub label: String,
    #[serde(default)]
    pub kind: LaunchKind,
    pub target: String,
}

/// One paired/known TV. The shared client cert is paired with each of these.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct DeviceEntry {
    /// Stable registry key (slug of `name`, or mDNS MAC when known).
    pub id: String,
    /// Display name (from mDNS or pairing).
    pub name: String,
    /// TV IP or hostname.
    pub host: String,
    /// Whether the shared client cert is trusted by this TV.
    #[serde(default)]
    pub paired: bool,
    /// Per-device last volume, restored into the UI on connect.
    pub last_volume: Option<u8>,
    /// mDNS service instance name, when the TV was found by discovery.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub service_name: Option<String>,
    /// SHA-256 fingerprint (lowercase hex) of the TV's certificate, learned on
    /// first connect. Identifies the TV across IP changes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fingerprint: Option<String>,
}

/// Turn a display name into a stable registry id: lowercase, runs of
/// non-alphanumerics collapse to a single `-`, trimmed; empty -> `"tv"`.
pub fn slugify(name: &str) -> String {
    let mut s = String::new();
    let mut prev_dash = false;
    for c in name.chars() {
        if c.is_ascii_alphanumeric() {
            s.push(c.to_ascii_lowercase());
            prev_dash = false;
        } else if !prev_dash {
            s.push('-');
            prev_dash = true;
        }
    }
    let trimmed = s.trim_matches('-');
    if trimmed.is_empty() {
        "tv".into()
    } else {
        trimmed.to_string()
    }
}

/// Built-in 1-0 app shortcuts (digits "8","9","0" left open by default).
pub fn default_shortcuts() -> BTreeMap<String, Shortcut> {
    let url = |label: &str, t: &str| Shortcut {
        label: label.into(),
        kind: LaunchKind::Url,
        target: t.into(),
    };
    // Defaults are all deep-link URLs: a bare package name as the app-link is
    // rejected by some TVs (they close the connection), whereas a well-formed URL
    // is at worst a no-op. `kind = "package"` is still supported for TVs that
    // accept it — set it explicitly in config if yours does.
    BTreeMap::from([
        ("1".into(), url("Netflix", "https://www.netflix.com/title")),
        ("2".into(), url("YouTube", "https://www.youtube.com")),
        ("3".into(), url("Disney+", "https://www.disneyplus.com")),
        ("4".into(), url("Max", "https://play.max.com")),
        ("5".into(), url("Amazon", "https://app.primevideo.com")),
        ("6".into(), url("Hulu", "https://www.hulu.com")),
        ("7".into(), url("Spotify", "https://open.spotify.com")),
    ])
}

/// The v2 config: a device registry + per-digit app shortcuts. Every field is
/// `#[serde(default)]` so partial / hand-edited files load. The `devices` array
/// serializes as `[[device]]`.
#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Config {
    /// Auto-connect target on launch (a `DeviceEntry.id`).
    #[serde(default)]
    pub last_device: Option<String>,
    /// Known TVs.
    #[serde(default, rename = "device")]
    pub devices: Vec<DeviceEntry>,
    /// Digit -> app override. Absent digits fall back to `default_shortcuts()`.
    #[serde(default)]
    pub shortcuts: BTreeMap<String, Shortcut>,
}

impl Config {
    /// Build a single-device config from the v1 flat fields.
    fn from_v1(name: Option<String>, host: String, paired: bool, last_volume: Option<u8>) -> Self {
        let name = name.unwrap_or_else(|| "Android TV".into());
        let id = slugify(&name);
        let mut cfg = Config::default();
        cfg.devices.push(DeviceEntry {
            id: id.clone(),
            name,
            host,
            paired,
            last_volume,
            ..Default::default()
        });
        cfg.last_device = Some(id);
        cfg
    }

    /// The currently-selected device (by `last_device`), if any.
    pub fn active_device(&self) -> Option<&DeviceEntry> {
        let id = self.last_device.as_deref()?;
        self.devices.iter().find(|d| d.id == id)
    }

    /// Mutable view of the active device, for in-place persistence updates.
    pub fn active_device_mut(&mut self) -> Option<&mut DeviceEntry> {
        let id = self.last_device.clone()?;
        self.devices.iter_mut().find(|d| d.id == id)
    }

    /// A registry id derived from `base` that does not collide with an existing
    /// device (suffix `-2`, `-3`, … on collision) — so two same-named TVs don't
    /// overwrite each other.
    pub fn unique_id(&self, base: &str) -> String {
        if !self.devices.iter().any(|d| d.id == base) {
            return base.to_string();
        }
        for n in 2.. {
            let cand = format!("{base}-{n}");
            if !self.devices.iter().any(|d| d.id == cand) {
                return cand;
            }
        }
        unreachable!()
    }

    /// Insert or replace a device (matched by id), and make it active.
    pub fn upsert_device(&mut self, d: DeviceEntry) {
        self.last_device = Some(d.id.clone());
        if let Some(slot) = self.devices.iter_mut().find(|x| x.id == d.id) {
            *slot = d;
        } else {
            self.devices.push(d);
        }
    }

    /// The shortcut for `digit` — the config override, else the built-in default,
    /// else `None` (an open slot). An override with an empty `target` is a cleared
    /// slot: it hides the built-in default and yields `None`.
    pub fn shortcut(&self, digit: char) -> Option<Shortcut> {
        let key = digit.to_string();
        if let Some(s) = self.shortcuts.get(&key) {
            return (!s.target.is_empty()).then(|| s.clone());
        }
        default_shortcuts().get(&key).cloned()
    }
}

/// `~/.config/clicker`. Falls back to `./.clicker` if there is no home dir.
pub fn dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("clicker")
}

/// Tolerant load: missing file, unreadable file, or bad TOML → default.
pub fn load() -> Config {
    load_from(&dir())
}

/// Serialize to TOML and write `~/.config/clicker/config.toml`.
pub fn save(cfg: &Config) -> anyhow::Result<()> {
    save_to(&dir(), cfg)
}

/// The v1 flat shape, for migration detection + parsing.
#[derive(Deserialize)]
struct V1Flat {
    host: Option<String>,
    name: Option<String>,
    #[serde(default)]
    paired: bool,
    last_volume: Option<u8>,
}

/// Tolerant load from an explicit config directory. Detects the v1 flat shape by
/// a top-level `host` key (so a v2 file holding only custom shortcuts is NOT
/// mistaken for v1), migrates it into a single device, and persists the migrated
/// form back to disk. Path-parameterized so tests target a unique temp dir.
pub fn load_from(dir: &std::path::Path) -> Config {
    let path = dir.join("config.toml");
    // A temporary file left by a write that never finished holds nothing we
    // want; the real file is still the last complete save.
    let _ = std::fs::remove_file(dir.join(TMP_NAME));
    let Ok(text) = std::fs::read_to_string(&path) else {
        return Config::default();
    };

    // v1 detection: a top-level `host = ...` key is only present in the flat shape.
    let is_v1 = toml::from_str::<toml::Value>(&text)
        .ok()
        .and_then(|v| v.as_table().map(|t| t.contains_key("host")))
        .unwrap_or(false);

    if is_v1 {
        if let Ok(v1) = toml::from_str::<V1Flat>(&text) {
            if let Some(host) = v1.host {
                let cfg = Config::from_v1(v1.name, host, v1.paired, v1.last_volume);
                let _ = save_to(dir, &cfg); // persist the migrated form
                return cfg;
            }
        }
    }

    toml::from_str::<Config>(&text).unwrap_or_default()
}

/// Where a save is written before it replaces `config.toml`.
const TMP_NAME: &str = "config.toml.tmp";

/// Serialize to TOML and write `dir/config.toml`, creating `dir` if needed.
/// The text goes to `config.toml.tmp` first and is then renamed over
/// `config.toml`, so a write cut short (a crash, a full disk) never leaves a
/// truncated config, which would load as the default and lose every TV. On
/// Unix the file is readable by the user only, since it names their TVs.
pub fn save_to(dir: &std::path::Path, cfg: &Config) -> anyhow::Result<()> {
    use std::io::Write;
    std::fs::create_dir_all(dir)?;
    let text = toml::to_string_pretty(cfg)?;
    let tmp = dir.join(TMP_NAME);
    // A left-over file would keep its old mode; start from a fresh one.
    let _ = std::fs::remove_file(&tmp);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let written = (|| -> std::io::Result<()> {
        let mut file = options.open(&tmp)?;
        file.write_all(text.as_bytes())?;
        file.sync_all()?;
        std::fs::rename(&tmp, dir.join("config.toml"))
    })();
    if written.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    written?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugify_makes_stable_ids() {
        assert_eq!(slugify("Living Room TV"), "living-room-tv");
        assert_eq!(slugify("Android TV"), "android-tv");
        assert_eq!(slugify("  !!  "), "tv");
        assert_eq!(slugify("Bedroom"), "bedroom");
    }

    #[test]
    fn migrates_v1_flat_config() {
        let tmp = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            tmp.path().join("config.toml"),
            "host = \"192.168.1.50\"\nname = \"Android TV\"\npaired = true\nlast_volume = 13\n",
        )
        .unwrap();

        let cfg = load_from(tmp.path());
        assert_eq!(cfg.devices.len(), 1);
        let d = &cfg.devices[0];
        assert_eq!(d.id, "android-tv");
        assert_eq!(d.host, "192.168.1.50");
        assert!(d.paired);
        assert_eq!(d.last_volume, Some(13));
        assert_eq!(cfg.last_device.as_deref(), Some("android-tv"));

        // Migration must persist the v2 form back to disk: a re-load is already v2
        // (no top-level `host`), so it still has exactly one device.
        let again = load_from(tmp.path());
        assert_eq!(again.devices.len(), 1);
        assert_eq!(again.active_device().unwrap().host, "192.168.1.50");
    }

    #[test]
    fn v2_config_roundtrips() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let mut cfg = Config::default();
        cfg.upsert_device(DeviceEntry {
            id: "bedroom".into(),
            name: "Bedroom".into(),
            host: "192.168.0.42".into(),
            paired: true,
            last_volume: Some(20),
            ..Default::default()
        });
        save_to(tmp.path(), &cfg).expect("save");

        let got = load_from(tmp.path());
        assert_eq!(got.active_device().unwrap().host, "192.168.0.42");
        assert_eq!(got.last_device.as_deref(), Some("bedroom"));
    }

    #[test]
    fn v2_config_with_only_shortcuts_is_not_migrated() {
        // A v2 file with shortcuts but no devices must NOT be treated as v1.
        let tmp = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            tmp.path().join("config.toml"),
            "[shortcuts]\n\"8\" = { label = \"Plex\", kind = \"package\", target = \"com.plexapp.android\" }\n",
        )
        .unwrap();
        let cfg = load_from(tmp.path());
        assert!(cfg.devices.is_empty());
        assert_eq!(cfg.shortcut('8').unwrap().label, "Plex");
    }

    #[test]
    fn missing_file_is_default() {
        let tmp = tempfile::tempdir().expect("tempdir");
        let got = load_from(tmp.path());
        assert!(got.devices.is_empty());
        assert!(got.last_device.is_none());
    }

    #[test]
    fn shortcut_falls_back_to_default() {
        let cfg = Config::default();
        assert_eq!(cfg.shortcut('1').unwrap().label, "Netflix");
        assert_eq!(cfg.shortcut('7').unwrap().label, "Spotify");
        assert!(cfg.shortcut('8').is_none()); // open slot
    }

    #[test]
    fn unique_id_suffixes_on_collision() {
        let mut cfg = Config::default();
        cfg.upsert_device(DeviceEntry {
            id: "android-tv".into(),
            name: "Android TV".into(),
            host: "1.1.1.1".into(),
            ..Default::default()
        });
        assert_eq!(cfg.unique_id("android-tv"), "android-tv-2");
        assert_eq!(cfg.unique_id("bedroom"), "bedroom");
    }

    #[test]
    fn shortcut_override_beats_default() {
        let mut cfg = Config::default();
        cfg.shortcuts.insert(
            "1".into(),
            Shortcut {
                label: "Jellyfin".into(),
                kind: LaunchKind::Package,
                target: "org.jellyfin.androidtv".into(),
            },
        );
        assert_eq!(cfg.shortcut('1').unwrap().label, "Jellyfin");
    }

    #[test]
    fn old_config_without_new_fields_loads() {
        let cfg: Config = toml::from_str(
            "last_device = \"tv\"\n[[device]]\nid = \"tv\"\nname = \"TV\"\nhost = \"192.0.2.10\"\npaired = true\n",
        )
        .unwrap();
        assert_eq!(cfg.devices[0].fingerprint, None);
        assert_eq!(cfg.devices[0].service_name, None);
    }

    fn one_tv() -> Config {
        let mut cfg = Config::default();
        cfg.upsert_device(DeviceEntry {
            id: "den".into(),
            name: "Den".into(),
            host: "192.0.2.10".into(),
            paired: true,
            ..Default::default()
        });
        cfg
    }

    #[test]
    fn interrupted_write_loads_the_previous_good_config() {
        let tmp = tempfile::tempdir().expect("tempdir");
        save_to(tmp.path(), &one_tv()).expect("save");
        // A write that died half way leaves garbage in the temporary file only.
        let stale = tmp.path().join("config.toml.tmp");
        std::fs::write(&stale, "last_device = \"den\"\n[[device]]\nid = \"de").unwrap();

        let got = load_from(tmp.path());
        assert_eq!(got.active_device().map(|d| d.host.as_str()), Some("192.0.2.10"));
        assert!(got.active_device().unwrap().paired);
        assert!(!stale.exists(), "a stale temporary file is removed on load");
    }

    #[test]
    fn save_replaces_the_file_whole_and_leaves_no_temporary() {
        let tmp = tempfile::tempdir().expect("tempdir");
        save_to(tmp.path(), &one_tv()).expect("save");
        save_to(tmp.path(), &Config::default()).expect("save again");
        assert!(!tmp.path().join("config.toml.tmp").exists());
        assert!(load_from(tmp.path()).devices.is_empty());
    }

    #[cfg(unix)]
    #[test]
    fn saved_config_is_private_to_the_user() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tempfile::tempdir().expect("tempdir");
        save_to(tmp.path(), &one_tv()).expect("save");
        let mode = std::fs::metadata(tmp.path().join("config.toml")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
    }

    #[test]
    fn empty_target_is_a_cleared_slot() {
        let mut cfg = Config::default();
        cfg.shortcuts.insert(
            "1".into(),
            Shortcut { label: String::new(), kind: LaunchKind::Url, target: String::new() },
        );
        assert!(cfg.shortcut('1').is_none()); // overrides the Netflix default
        assert!(cfg.shortcut('2').is_some());
    }
}
