use super::*;
use clicker_core::config::{self, Config, DeviceEntry, LaunchKind};
use clicker_core::types::{RemoteKey, TvCmd};
use std::sync::Mutex;

struct Fx {
    tmp: tempfile::TempDir,
    _rt: tokio::runtime::Runtime,
    c: Controller,
    events: Arc<Mutex<Vec<RemoteEvent>>>,
}

impl Fx {
    fn reload(&self) -> Config {
        config::load_from(self.tmp.path())
    }
    fn events(&self) -> Vec<RemoteEvent> {
        self.events.lock().unwrap().clone()
    }
    fn clear_events(&self) {
        self.events.lock().unwrap().clear();
    }
}

fn fx_with(cfg: Config) -> Fx {
    let tmp = tempfile::tempdir().unwrap();
    config::save_to(tmp.path(), &cfg).unwrap();
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    let events = Arc::new(Mutex::new(Vec::new()));
    let ev = events.clone();
    let sink: EventSink = Arc::new(move |e| ev.lock().unwrap().push(e));
    let c = Controller::new(tmp.path().to_path_buf(), rt.handle().clone(), sink);
    Fx { tmp, _rt: rt, c, events }
}

fn fx() -> Fx {
    fx_with(Config::default())
}

fn dev(id: &str, host: &str, fp: Option<&str>) -> DeviceEntry {
    DeviceEntry {
        id: id.into(),
        name: id.to_uppercase(),
        host: host.into(),
        fingerprint: fp.map(Into::into),
        ..Default::default()
    }
}

/// A: fp "aa" at .10 (active); B: fp "bb" at .11.
fn two_tvs() -> Fx {
    let mut cfg = Config::default();
    cfg.devices.push(dev("b", "192.0.2.11", Some("bb")));
    cfg.upsert_device(dev("a", "192.0.2.10", Some("aa")));
    fx_with(cfg)
}

fn identity(host: &str, fp: &str) -> TvEvent {
    TvEvent::ServerIdentity { host: host.into(), fingerprint: fp.into() }
}

#[test]
fn add_device_persists_and_dedupes_host() {
    let f = fx();
    let first = f.c.add_device("TV".into(), "192.0.2.10".into(), None);
    let again = f.c.add_device("Other".into(), "192.0.2.10".into(), None);
    assert_eq!(again, first);
    assert_eq!(f.c.devices().len(), 1);
    assert_eq!(f.reload().devices.len(), 1);
    assert!(f.events().contains(&RemoteEvent::DevicesChanged));
}

#[test]
fn same_name_gets_unique_id() {
    let f = fx();
    let a = f.c.add_device("TV".into(), "192.0.2.10".into(), None);
    let b = f.c.add_device("TV".into(), "192.0.2.11".into(), Some("TV svc".into()));
    assert_eq!((a.id.as_str(), b.id.as_str()), ("tv", "tv-2"));
    assert_eq!(f.reload().devices[1].service_name.as_deref(), Some("TV svc"));
}

/// A saved, paired TV "a" at .10 with fingerprint "aa", found by discovery as
/// "Living Room".
fn discovered_tv(fp: Option<&str>) -> Fx {
    let mut cfg = Config::default();
    let mut d = dev("a", "192.0.2.10", fp);
    d.service_name = Some("Living Room".into());
    d.paired = true;
    cfg.upsert_device(d);
    fx_with(cfg)
}

#[test]
fn discovery_never_persists_host_without_fingerprint_match() {
    let f = discovered_tv(Some("aa"));
    let got = f.c.note_discovered("Living Room".into(), "192.0.2.44".into());
    assert_eq!(got.as_deref(), Some("a"));
    assert_eq!(f.c.devices()[0].host, "192.0.2.10");
    assert_eq!(f.reload().devices[0].host, "192.0.2.10");
    assert!(f.events().is_empty());
    assert_eq!(f.c.lock().candidates.get("a").map(String::as_str), Some("192.0.2.44"));
}

#[test]
fn device_without_fingerprint_is_not_healed() {
    let f = discovered_tv(None);
    assert_eq!(f.c.note_discovered("Living Room".into(), "192.0.2.44".into()), None);
    assert_eq!(f.reload().devices[0].host, "192.0.2.10");
    assert!(f.c.lock().candidates.is_empty());
    // The same host is still recognised.
    assert_eq!(f.c.note_discovered("Living Room".into(), "192.0.2.10".into()).as_deref(), Some("a"));
}

// `connect` spawns its tasks on the fixture's current-thread runtime, which no
// test drives, so they never run: no socket and no keygen. Events are fed in
// by hand through `handle_tv_event`.

#[test]
fn candidate_host_is_persisted_after_fingerprint_match() {
    let f = discovered_tv(Some("aa"));
    f.c.note_discovered("Living Room".into(), "192.0.2.44".into());
    f.c.connect("a".into()).unwrap();
    assert_eq!(f.c.lock().conn.as_ref().map(|c| c.host.clone()).as_deref(), Some("192.0.2.44"));
    assert_eq!(f.reload().devices[0].host, "192.0.2.10"); // not written before the check
    f.c.handle_tv_event(identity("192.0.2.44", "aa"));
    assert_eq!(f.reload().devices[0].host, "192.0.2.44");
    assert!(f.reload().devices[0].paired);
    assert!(f.events().contains(&RemoteEvent::DevicesChanged));
    assert!(f.c.lock().candidates.is_empty());
}

#[test]
fn candidate_with_wrong_fingerprint_is_dropped_and_saved_host_kept() {
    let mut cfg = Config::default();
    cfg.devices.push(dev("b", "192.0.2.11", Some("zz"))); // a known device must not be switched to
    let mut a = dev("a", "192.0.2.10", Some("aa"));
    a.service_name = Some("Living Room".into());
    cfg.upsert_device(a);
    let f = fx_with(cfg);
    f.c.note_discovered("Living Room".into(), "192.0.2.44".into());
    f.c.connect("a".into()).unwrap();
    f.c.handle_tv_event(identity("192.0.2.44", "zz"));
    assert_eq!(
        f.events().last(),
        Some(&RemoteEvent::Disconnected { reason: Some("TV not reachable".into()) })
    );
    assert!(!f.events().iter().any(|e| matches!(e, RemoteEvent::DifferentTv { .. } | RemoteEvent::DevicesChanged)));
    let cfg = f.reload();
    assert_eq!(cfg.last_device.as_deref(), Some("a"));
    assert_eq!(cfg.devices.iter().map(|d| d.host.as_str()).collect::<Vec<_>>(), ["192.0.2.11", "192.0.2.10"]);
    assert!(f.c.lock().candidates.is_empty());
    // The connection was aborted, and the next connect dials the saved host.
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    assert!(!f.c.send_key(Key::Home));
    f.c.connect("a".into()).unwrap();
    assert_eq!(f.c.lock().conn.as_ref().map(|c| c.host.clone()).as_deref(), Some("192.0.2.10"));
}

#[test]
fn candidate_connect_error_drops_candidate() {
    let f = discovered_tv(Some("aa"));
    f.c.note_discovered("Living Room".into(), "192.0.2.44".into());
    f.c.connect("a".into()).unwrap();
    f.c.handle_tv_event(TvEvent::Disconnected);
    f.c.handle_tv_event(TvEvent::Error("TV not reachable at 192.0.2.44".into()));
    assert!(f.c.lock().candidates.is_empty());
    assert_eq!(f.reload().devices[0].host, "192.0.2.10");
}

#[test]
fn identity_from_saved_host_is_ignored_while_dialling_candidate() {
    let f = discovered_tv(Some("aa"));
    f.c.note_discovered("Living Room".into(), "192.0.2.44".into());
    f.c.connect("a".into()).unwrap();
    f.clear_events();
    f.c.handle_tv_event(identity("192.0.2.10", "zz"));
    assert!(f.events().is_empty());
}

#[test]
fn connect_proceeds_when_save_fails_and_old_events_are_dropped() {
    let f = two_tvs();
    let (old, _rx) = f.c.attach_for_test();
    // Make config.toml a directory so every save fails.
    let path = f.tmp.path().join("config.toml");
    std::fs::remove_file(&path).unwrap();
    std::fs::create_dir(&path).unwrap();
    f.c.connect("b".into()).unwrap();
    assert_eq!(f.c.active_device_id().as_deref(), Some("b"));
    assert_eq!(f.events(), vec![RemoteEvent::Connecting { device_id: "b".into() }]);
    f.c.handle_tv_event_for(old, TvEvent::PairingOk);
    f.c.handle_tv_event_for(old, TvEvent::Connected { name: "A".into() });
    assert!(f.c.devices().iter().all(|d| !d.paired));
    assert_eq!(f.events().len(), 1);
    assert!(!f.c.send_key(Key::Up));
}

#[test]
fn note_discovered_unknown_returns_none() {
    let f = fx();
    f.c.add_device("TV".into(), "192.0.2.10".into(), Some("Bedroom".into()));
    assert_eq!(f.c.note_discovered("Kitchen".into(), "192.0.2.12".into()), None);
    assert_eq!(f.c.devices()[0].host, "192.0.2.10");
}

#[test]
fn pairing_ok_persists_paired() {
    let f = two_tvs();
    f.c.handle_tv_event(TvEvent::PairingOk);
    assert!(f.reload().active_device().unwrap().paired);
    assert_eq!(f.events(), vec![RemoteEvent::PairingOk]);
}

#[test]
fn first_identity_is_recorded() {
    let mut cfg = Config::default();
    cfg.upsert_device(dev("a", "192.0.2.10", None));
    let f = fx_with(cfg);
    f.c.handle_tv_event(identity("192.0.2.10", "f00d"));
    assert_eq!(f.reload().devices[0].fingerprint.as_deref(), Some("f00d"));
}

#[test]
fn stale_identity_from_other_host_is_ignored() {
    let mut cfg = Config::default();
    cfg.upsert_device(dev("a", "192.0.2.10", None));
    let f = fx_with(cfg);
    f.c.handle_tv_event(identity("192.0.2.99", "f00d"));
    assert_eq!(f.reload().devices[0].fingerprint, None);
    assert!(f.events().is_empty());
}

#[test]
fn identity_mismatch_switches_to_known_device() {
    let f = two_tvs();
    let (_gen, _rx) = f.c.attach_for_test();
    f.c.handle_tv_event(identity("192.0.2.10", "bb"));
    assert_eq!(f.c.active_device_id().as_deref(), Some("b"));
    let b = f.c.devices().into_iter().find(|d| d.id == "b").unwrap();
    assert_eq!(b.host, "192.0.2.10");
    assert!(f.events().contains(&RemoteEvent::DevicesChanged));
    assert_eq!(f.reload().last_device.as_deref(), Some("b"));
    // The core's Connected still carries A's name from its config clone.
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    assert_eq!(
        f.events().last(),
        Some(&RemoteEvent::Connected { device_id: "b".into(), name: "B".into() })
    );
}

#[test]
fn identity_mismatch_unknown_reports_different_tv() {
    let f = two_tvs();
    let (_gen, _rx) = f.c.attach_for_test();
    f.c.handle_tv_event(identity("192.0.2.10", "zz"));
    assert_eq!(
        f.events().last(),
        Some(&RemoteEvent::DifferentTv { host: "192.0.2.10".into() })
    );
    let a = f.reload();
    let a = a.active_device().unwrap();
    assert_eq!((a.id.as_str(), a.host.as_str()), ("a", "192.0.2.10"));
    assert_eq!(a.fingerprint.as_deref(), Some("aa"));
    // The connection was aborted: a late Connected is dropped.
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    assert!(!f.c.send_key(Key::Home));
}

#[test]
fn send_key_while_disconnected_returns_false() {
    let f = two_tvs();
    let (_gen, mut rx) = f.c.attach_for_test();
    assert!(!f.c.send_key(Key::Up));
    assert!(rx.try_recv().is_err());
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    assert!(f.c.send_key(Key::Up));
    assert!(matches!(rx.try_recv(), Ok(TvCmd::Key(RemoteKey::Up))));
    f.c.handle_tv_event(TvEvent::Disconnected);
    assert!(!f.c.send_key(Key::Up));
    assert!(!f.c.set_text("x".into()));
    assert!(!f.c.submit_text());
    // submit_pin sends even while not connected (pairing happens before Connected).
    f.c.submit_pin("1234".into());
    assert!(matches!(rx.try_recv(), Ok(TvCmd::SubmitPin(p)) if p == "1234"));
}

#[test]
fn disconnected_then_error_reports_reason() {
    let f = two_tvs();
    f.c.handle_tv_event(TvEvent::Disconnected);
    assert!(f.events().is_empty());
    f.c.handle_tv_event(TvEvent::Error("TV not reachable at 192.0.2.10".into()));
    f.c.handle_tv_event(TvEvent::Error("no TV selected".into()));
    assert_eq!(
        f.events(),
        vec![
            RemoteEvent::Disconnected { reason: Some("TV not reachable at 192.0.2.10".into()) },
            RemoteEvent::Disconnected { reason: Some("no TV selected".into()) },
        ]
    );
}

#[test]
fn events_from_an_old_generation_are_dropped() {
    let f = two_tvs();
    let (old, _rx) = f.c.attach_for_test();
    f.c.disconnect();
    assert_eq!(f.events(), vec![RemoteEvent::Disconnected { reason: None }]);
    let (_new, _rx2) = f.c.attach_for_test();
    f.clear_events();
    f.c.handle_tv_event_for(old, TvEvent::Connected { name: "A".into() });
    f.c.handle_tv_event_for(old, TvEvent::PairingOk);
    f.c.handle_tv_event_for(old, TvEvent::VolumeChanged { level: 5, max: 100, muted: false });
    assert!(f.events().is_empty());
    assert!(!f.c.send_key(Key::Up));
    let cfg = f.reload();
    let a = cfg.active_device().unwrap();
    assert!(!a.paired);
    assert_eq!(a.last_volume, None);
}

#[test]
fn shortcuts_returns_ten_in_order() {
    let f = fx();
    let s = f.c.shortcuts();
    let slots: Vec<&str> = s.iter().map(|x| x.slot.as_str()).collect();
    assert_eq!(slots, ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]);
    assert_eq!(s[0].label, "Netflix");
    assert_eq!(s[7].target, "");
}

#[test]
fn clear_then_reload_keeps_slot_empty() {
    let f = fx();
    f.c.clear_shortcut("1".into()).unwrap();
    assert_eq!(f.c.shortcuts()[0].target, "");
    assert!(f.reload().shortcut('1').is_none());
}

#[test]
fn set_shortcut_rejects_empty_target() {
    let f = fx();
    let err = f.c.set_shortcut("8".into(), "Plex".into(), "   ".into()).unwrap_err();
    assert!(matches!(err, RemoteError::Storage { .. }));
    assert_eq!(f.c.shortcuts()[7].target, "");
}

#[test]
fn set_shortcut_trims_and_picks_kind() {
    let f = fx();
    f.c.set_shortcut("8".into(), " Plex ".into(), " com.plexapp.android ".into()).unwrap();
    f.c.set_shortcut("9".into(), "Web".into(), "https://example.com".into()).unwrap();
    let cfg = f.reload();
    let plex = cfg.shortcut('8').unwrap();
    assert_eq!((plex.label.as_str(), plex.target.as_str()), ("Plex", "com.plexapp.android"));
    assert_eq!(plex.kind, LaunchKind::Package);
    assert_eq!(cfg.shortcut('9').unwrap().kind, LaunchKind::Url);
}

#[test]
fn launch_sends_target_and_rejects_empty_slot() {
    let f = two_tvs();
    let (_gen, mut rx) = f.c.attach_for_test();
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    assert!(f.c.launch("2".into()));
    assert!(matches!(rx.try_recv(), Ok(TvCmd::LaunchApp(t)) if t == "https://www.youtube.com"));
    assert!(!f.c.launch("8".into()));
    assert!(rx.try_recv().is_err());
}

#[test]
fn volume_event_maps_and_persists() {
    let f = two_tvs();
    f.c.handle_tv_event(TvEvent::VolumeChanged { level: 13, max: 100, muted: false });
    assert_eq!(f.events(), vec![RemoteEvent::Volume { level: 13, max: 100, muted: false }]);
    assert_eq!(f.reload().active_device().unwrap().last_volume, Some(13));
}

#[test]
fn remove_active_device_clears_active() {
    let f = two_tvs();
    f.c.remove_device("a".into()).unwrap();
    assert_eq!(f.c.active_device_id(), None);
    assert_eq!(f.c.devices().len(), 1);
    let cfg = f.reload();
    assert_eq!(cfg.last_device, None);
    assert_eq!(cfg.devices.len(), 1);
    assert_eq!(f.c.remove_device("a".into()), Err(RemoteError::UnknownDevice));
}

#[test]
fn connect_unknown_device_is_rejected() {
    let f = two_tvs();
    assert_eq!(f.c.connect("nope".into()), Err(RemoteError::UnknownDevice));
    assert!(f.events().is_empty());
}

#[test]
fn add_device_trims_name_and_host() {
    let f = fx();
    let d = f.c.add_device("  Den TV ".into(), " 192.0.2.10 ".into(), None);
    assert_eq!((d.id.as_str(), d.name.as_str(), d.host.as_str()), ("den-tv", "Den TV", "192.0.2.10"));
    assert_eq!(f.c.add_device("x".into(), "192.0.2.10".into(), None), d);
    assert_eq!(f.reload().devices.len(), 1);
}

#[test]
fn rename_trims_and_rejects_empty_name() {
    let f = two_tvs();
    f.c.rename_device("a".into(), "  Den  ".into()).unwrap();
    assert_eq!(f.reload().active_device().unwrap().name, "Den");
    assert!(matches!(f.c.rename_device("a".into(), "   ".into()), Err(RemoteError::Storage { .. })));
    assert_eq!(f.reload().active_device().unwrap().name, "Den");
}

#[test]
fn first_identity_held_by_another_device_switches_instead_of_duplicating() {
    let mut cfg = Config::default();
    cfg.devices.push(dev("b", "192.0.2.11", Some("bb")));
    cfg.upsert_device(dev("a", "192.0.2.10", None));
    let f = fx_with(cfg);
    f.c.handle_tv_event(identity("192.0.2.10", "bb"));
    let cfg = f.reload();
    assert_eq!(cfg.last_device.as_deref(), Some("b"));
    let a = cfg.devices.iter().find(|d| d.id == "a").unwrap();
    let b = cfg.devices.iter().find(|d| d.id == "b").unwrap();
    assert_eq!(a.fingerprint, None);
    assert_eq!(b.host, "192.0.2.10");
    assert_eq!(f.events(), vec![RemoteEvent::DevicesChanged]);
}

#[test]
fn add_device_host_dedup_prefers_identified_or_active_entry() {
    // After a known-device switch, stale "a" and identified "b" share .10.
    let mut cfg = Config::default();
    cfg.devices.push(dev("a", "192.0.2.10", None));
    cfg.devices.push(dev("b", "192.0.2.10", Some("bb")));
    let f = fx_with(cfg);
    assert_eq!(f.c.add_device("TV".into(), "192.0.2.10".into(), None).id, "b");

    // With no fingerprints, the active entry wins.
    let mut cfg = Config::default();
    cfg.devices.push(dev("a", "192.0.2.10", None));
    cfg.upsert_device(dev("c", "192.0.2.10", None));
    let f = fx_with(cfg);
    assert_eq!(f.c.add_device("TV".into(), "192.0.2.10".into(), None).id, "c");
}

#[test]
fn removing_active_device_while_connected_disconnects() {
    let f = two_tvs();
    let (old, _rx) = f.c.attach_for_test();
    f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
    f.clear_events();
    let before = f.c.lock().generation;
    f.c.remove_device("a".into()).unwrap();
    assert_eq!(
        f.events(),
        vec![RemoteEvent::Disconnected { reason: None }, RemoteEvent::DevicesChanged]
    );
    assert!(f.c.lock().generation > before);
    f.clear_events();
    f.c.handle_tv_event_for(old, TvEvent::VolumeChanged { level: 9, max: 100, muted: false });
    assert!(f.events().is_empty());
    assert!(!f.c.send_key(Key::Up));
}

/// A saved, paired TV "a" at .10 with fingerprint "aa", active.
fn paired_tv_a() -> Fx {
    let mut a = dev("a", "192.0.2.10", Some("aa"));
    a.paired = true;
    let mut cfg = Config::default();
    cfg.upsert_device(a);
    fx_with(cfg)
}

#[test]
fn add_new_device_always_creates_an_entry() {
    let f = paired_tv_a();
    let b = f.c.add_new_device(" Den ".into(), " 192.0.2.10 ".into(), Some("Den svc".into()));
    assert_eq!(
        b,
        Device { id: "den".into(), name: "Den".into(), host: "192.0.2.10".into(), paired: false, last_volume: None }
    );
    assert_eq!(f.events(), vec![RemoteEvent::DevicesChanged]);
    assert_eq!(f.c.active_device_id().as_deref(), Some("a"));
    assert!(f.c.lock().conn.is_none());
    let cfg = f.reload();
    assert_eq!(cfg.devices.len(), 2);
    let a = &cfg.devices[0];
    assert_eq!((a.host.as_str(), a.fingerprint.as_deref(), a.paired), ("192.0.2.10", Some("aa"), true));
    let saved_b = &cfg.devices[1];
    assert_eq!((saved_b.fingerprint.as_deref(), saved_b.service_name.as_deref()), (None, Some("Den svc")));
    // Same name again still gets its own id.
    assert_eq!(f.c.add_new_device("Den".into(), "192.0.2.10".into(), None).id, "den-2");
}

#[test]
fn new_device_at_occupied_host_records_its_own_identity() {
    let f = paired_tv_a();
    let b = f.c.add_new_device("192.0.2.10".into(), "192.0.2.10".into(), None);
    f.c.connect(b.id.clone()).unwrap();
    f.c.handle_tv_event(TvEvent::PairingRequired);
    f.c.handle_tv_event(TvEvent::PairingOk);
    f.c.handle_tv_event(identity("192.0.2.10", "zz"));
    assert!(!f.events().iter().any(|e| matches!(e, RemoteEvent::DifferentTv { .. })));
    assert_eq!(f.c.active_device_id(), Some(b.id.clone()));
    let cfg = f.reload();
    let a = cfg.devices.iter().find(|d| d.id == "a").unwrap();
    assert_eq!((a.fingerprint.as_deref(), a.paired), (Some("aa"), true));
    let nb = cfg.devices.iter().find(|d| d.id == b.id).unwrap();
    assert_eq!((nb.fingerprint.as_deref(), nb.paired), (Some("zz"), true));
    // A later add_device for the shared host reuses an entry.
    f.c.add_device("TV".into(), "192.0.2.10".into(), None);
    assert_eq!(f.reload().devices.len(), 2);
}

#[test]
fn new_device_at_host_where_old_tv_answers_switches_back() {
    let f = paired_tv_a();
    let b = f.c.add_new_device("192.0.2.10".into(), "192.0.2.10".into(), None);
    f.c.connect(b.id.clone()).unwrap();
    f.clear_events();
    f.c.handle_tv_event(identity("192.0.2.10", "aa"));
    assert_eq!(f.events(), vec![RemoteEvent::DevicesChanged]);
    assert_eq!(f.c.active_device_id().as_deref(), Some("a"));
    let cfg = f.reload();
    let nb = cfg.devices.iter().find(|d| d.id == b.id).unwrap();
    assert_eq!((nb.fingerprint.as_deref(), nb.paired), (None, false));
    assert_eq!(f.c.add_device("TV".into(), "192.0.2.10".into(), None).id, "a");
    assert_eq!(f.reload().devices.len(), 2);
}

#[test]
fn different_tv_reports_disconnected_before_the_question() {
    let f = two_tvs();
    let (_gen, _rx) = f.c.attach_for_test();
    f.clear_events();
    f.c.handle_tv_event(identity("192.0.2.10", "zz"));
    // The UI must leave "Connecting…": the torn-down link reads as idle first.
    assert_eq!(
        f.events(),
        vec![
            RemoteEvent::Disconnected { reason: None },
            RemoteEvent::DifferentTv { host: "192.0.2.10".into() },
        ]
    );
    assert!(f.c.lock().conn.is_none());
}

#[test]
fn pairing_failures_say_whether_the_code_was_wrong() {
    let f = two_tvs();
    f.c.handle_tv_event(TvEvent::PairingFailed("PIN checksum mismatch".into()));
    f.c.handle_tv_event(TvEvent::PairingFailed(clicker_core::pairing::FINISH_TIMED_OUT.into()));
    f.c.handle_tv_event(TvEvent::PairingUnreachable("pairing connect failed: TV not reachable at 192.0.2.10:6467".into()));
    assert_eq!(
        f.events(),
        vec![
            RemoteEvent::PairingFailed { message: "PIN checksum mismatch".into(), wrong_code: true },
            RemoteEvent::PairingFailed { message: clicker_core::pairing::FINISH_TIMED_OUT.into(), wrong_code: true },
            RemoteEvent::PairingFailed {
                message: "pairing connect failed: TV not reachable at 192.0.2.10:6467".into(),
                wrong_code: false,
            },
        ]
    );
}

fn saves(f: &Fx) -> usize {
    f.c.inner.saves.load(std::sync::atomic::Ordering::SeqCst)
}

#[test]
fn connect_to_the_active_device_does_not_rewrite_the_config() {
    let f = two_tvs();
    let before = saves(&f);
    f.c.connect("a".into()).unwrap();
    f.c.connect("a".into()).unwrap();
    assert_eq!(saves(&f), before, "last_device did not change");
    f.c.connect("b".into()).unwrap();
    assert_eq!(saves(&f), before + 1);
    assert_eq!(f.reload().last_device.as_deref(), Some("b"));
}

#[test]
fn volume_steps_within_the_window_write_once_and_the_last_is_kept_on_disconnect() {
    let f = two_tvs();
    let (_gen, _rx) = f.c.attach_for_test();
    let before = saves(&f);
    for level in [10, 11, 12, 13] {
        f.c.handle_tv_event(TvEvent::VolumeChanged { level, max: 100, muted: false });
    }
    assert_eq!(saves(&f), before + 1, "one write per window");
    assert_eq!(f.reload().active_device().unwrap().last_volume, Some(10));
    // In memory the latest level is known at once.
    assert_eq!(f.c.devices().iter().find(|d| d.id == "a").unwrap().last_volume, Some(13));
    f.c.disconnect();
    assert_eq!(f.reload().active_device().unwrap().last_volume, Some(13));
    assert_eq!(saves(&f), before + 2);
    // Nothing left to write: a second disconnect does not touch the file.
    f.c.disconnect();
    assert_eq!(saves(&f), before + 2);
}

#[test]
fn pending_volume_is_written_when_the_link_drops() {
    let f = two_tvs();
    let (_gen, _rx) = f.c.attach_for_test();
    f.c.handle_tv_event(TvEvent::VolumeChanged { level: 20, max: 100, muted: false });
    f.c.handle_tv_event(TvEvent::VolumeChanged { level: 21, max: 100, muted: false });
    assert_eq!(f.reload().active_device().unwrap().last_volume, Some(20));
    f.c.handle_tv_event(TvEvent::Disconnected);
    assert_eq!(f.reload().active_device().unwrap().last_volume, Some(21));
}

#[test]
fn no_flow_resets_paired_on_a_device_that_keeps_its_fingerprint() {
    let f = paired_tv_a();
    let paired = |f: &Fx| (f.c.devices()[0].paired, f.reload().devices[0].paired);
    for round in 0..2 {
        f.c.connect("a".into()).unwrap();
        f.c.handle_tv_event(identity("192.0.2.10", "aa"));
        f.c.handle_tv_event(TvEvent::PairingRequired);
        f.c.handle_tv_event(TvEvent::PairingFailed("PIN checksum mismatch".into()));
        f.c.handle_tv_event(TvEvent::PairingUnreachable("pairing connect failed: TV not reachable".into()));
        f.c.handle_tv_event(TvEvent::Connected { name: "A".into() });
        f.c.handle_tv_event(TvEvent::VolumeChanged { level: 5, max: 100, muted: false });
        f.c.handle_tv_event(TvEvent::Disconnected);
        f.c.handle_tv_event(TvEvent::Error("TV stopped responding".into()));
        assert_eq!(paired(&f), (true, true), "round {round}");
        f.c.disconnect();
        f.c.rename_device("a".into(), "Den".into()).unwrap();
        f.c.add_device("Den".into(), "192.0.2.10".into(), None);
        assert_eq!(paired(&f), (true, true), "round {round}");
    }
    assert_eq!(f.reload().devices[0].fingerprint.as_deref(), Some("aa"));
}
