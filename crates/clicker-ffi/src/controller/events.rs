//! Core `TvEvent` handling: generation filtering, persistence and mapping to
//! `RemoteEvent`.

use clicker_core::types::TvEvent;

use std::time::Instant;

use super::{Controller, State, VOLUME_SAVE_EVERY};
use crate::RemoteEvent;

/// What the UI is told when a candidate host turns out to be a different TV.
const CANDIDATE_REJECTED: &str = "TV not reachable";

impl Controller {
    /// Apply a core event as if it came from the current connection.
    #[cfg(test)]
    pub(crate) fn handle_tv_event(&self, ev: TvEvent) {
        let generation = self.lock().generation;
        self.handle_tv_event_for(generation, ev);
    }

    /// Apply a core event from the connection of `generation`. Events from an
    /// older generation are dropped. Persistence happens here (sole writer);
    /// a failed save cannot be reported to anyone, so it is ignored and the
    /// next successful save catches up.
    pub(crate) fn handle_tv_event_for(&self, generation: u64, ev: TvEvent) {
        let mut st = self.lock();
        if st.generation != generation {
            return;
        }
        let mut events = Vec::new();
        let mut dirty = false;
        match ev {
            TvEvent::Connected { name } => {
                st.connected = true;
                if let Some(d) = st.cfg.active_device() {
                    let name = if d.name.is_empty() { name } else { d.name.clone() };
                    events.push(RemoteEvent::Connected { device_id: d.id.clone(), name });
                }
            }
            TvEvent::Disconnected => {
                st.connected = false;
                dirty = st.volume_unsaved;
            }
            TvEvent::Error(msg) => {
                st.connected = false;
                dirty = st.volume_unsaved;
                // A candidate host that cannot be reached is forgotten, so the
                // next connect goes to the saved host.
                if st.conn.as_ref().is_some_and(|c| c.candidate) {
                    if let Some(id) = st.cfg.last_device.clone() {
                        st.candidates.remove(&id);
                    }
                }
                events.push(RemoteEvent::Disconnected { reason: Some(msg) });
            }
            TvEvent::ServerIdentity { host, fingerprint } => {
                on_identity(&mut st, host, fingerprint, &mut events, &mut dirty);
            }
            TvEvent::VolumeChanged { level, max, muted } => {
                let changed = st.cfg.active_device_mut().is_some_and(|d| d.last_volume.replace(level) != Some(level));
                if changed {
                    // Every volume step lands here; write at most once per
                    // window, and the rest when the link drops.
                    st.volume_unsaved = true;
                    let now = Instant::now();
                    if st.volume_saved_at.is_none_or(|t| now.duration_since(t) >= VOLUME_SAVE_EVERY) {
                        st.volume_saved_at = Some(now);
                        dirty = true;
                    }
                }
                events.push(RemoteEvent::Volume { level, max, muted });
            }
            TvEvent::PairingRequired => events.push(RemoteEvent::PairingRequired),
            TvEvent::PairingOk => {
                if let Some(d) = st.cfg.active_device_mut() {
                    dirty = !std::mem::replace(&mut d.paired, true);
                }
                events.push(RemoteEvent::PairingOk);
            }
            // The code itself failed (refused, or no answer to it).
            TvEvent::PairingFailed(message) => events.push(RemoteEvent::PairingFailed { message, wrong_code: true }),
            // The TV went away while its code was up.
            TvEvent::PairingUnreachable(message) => {
                events.push(RemoteEvent::PairingFailed { message, wrong_code: false })
            }
            TvEvent::TextFieldActive(active) => events.push(RemoteEvent::TextFieldActive { active }),
            TvEvent::DiscoveredDevice { .. } => {}
        }
        if dirty {
            let _ = self.save(&mut st);
        }
        drop(st);
        self.emit(events);
    }
}

/// The TV at the far end presented `fingerprint`. Decide whether it is the
/// active device, a different saved device, or an unknown TV.
fn on_identity(st: &mut State, host: String, fingerprint: String, events: &mut Vec<RemoteEvent>, dirty: &mut bool) {
    let Some(active) = st.cfg.active_device() else { return };
    let (active_id, saved_fp) = (active.id.clone(), active.fingerprint.clone());
    // The host this connection actually dialled: a candidate, or the saved host.
    let (target, candidate) = match &st.conn {
        Some(c) => (c.host.clone(), c.candidate),
        None => (active.host.clone(), false),
    };
    if host != target {
        return; // late event from a connection to another host
    }

    if candidate {
        // Discovery is unauthenticated: a candidate host is adopted only when
        // the TV there proves it is the saved one.
        st.candidates.remove(&active_id);
        if saved_fp.as_deref() == Some(fingerprint.as_str()) {
            if let Some(d) = st.cfg.active_device_mut() {
                d.host = host;
            }
            if let Some(c) = st.conn.as_mut() {
                c.candidate = false;
            }
            *dirty = true;
            events.push(RemoteEvent::DevicesChanged);
        } else {
            st.teardown();
            *dirty |= st.volume_unsaved;
            events.push(RemoteEvent::Disconnected { reason: Some(CANDIDATE_REJECTED.into()) });
        }
        return;
    }

    if saved_fp.as_deref() == Some(fingerprint.as_str()) {
        return;
    }
    let known = st
        .cfg
        .devices
        .iter_mut()
        .find(|d| d.id != active_id && d.fingerprint.as_deref() == Some(fingerprint.as_str()));
    if let Some(other) = known {
        // The TV at this address is one we already know, now on a new IP.
        // Move the host onto it and keep the connection. The active entry is
        // left as it is.
        other.host = host;
        st.cfg.last_device = Some(other.id.clone());
        *dirty = true;
        events.push(RemoteEvent::DevicesChanged);
    } else if saved_fp.is_none() {
        if let Some(d) = st.cfg.active_device_mut() {
            d.fingerprint = Some(fingerprint);
        }
        *dirty = true;
    } else {
        // The link is gone: say so first, so the UI leaves "Connecting…"
        // whatever the user then answers.
        st.teardown();
        *dirty |= st.volume_unsaved;
        events.push(RemoteEvent::Disconnected { reason: None });
        events.push(RemoteEvent::DifferentTv { host });
    }
}
