//! §4.3 remote-control connection orchestrator (:6466).
//!
//! `run_connection` is LIVE/INTEGRATION code (needs a real TV); it is written to
//! COMPILE against the prost-generated `crate::proto::remotemessage` types. The
//! one pure seam — `message_to_event`, mapping an inbound `RemoteMessage` to an
//! optional `TvEvent` — is unit-tested below.
//!
//! prost facts (proto3) used here:
//!   * Every `RemoteMessage` sub-field is `Option<T>`.
//!   * `RemoteConfigure.code1` is `i32`; `device_info` is `Option<RemoteDeviceInfo>`.
//!   * `RemoteDeviceInfo` fields are plain (`unknown1: i32`, the rest `String`).
//!   * `RemoteKeyInject.key_code: i32`, `direction: i32`.
//!   * `RemoteDirection` is a TOP-LEVEL enum (`RemoteDirection::Short as i32`).
//!   * `RemoteSetActive.active: i32`.
//!   * `RemoteSetVolumeLevel.volume_level`/`volume_max` are `u32`, `volume_muted: bool`.
//!   * `RemotePingRequest`/`RemotePingResponse.val1: i32`.
//!   * `RemoteAppLinkLaunchRequest.app_link: String`.

use prost::Message;
use tokio::io::{ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio::sync::mpsc::{Receiver, Sender};
use tokio_rustls::client::TlsStream;

use crate::cert::ClientIdentity;
use crate::config::{self, Config};
use crate::framing::{read_msg, write_msg};
use crate::proto::remotemessage as rm;
use crate::types::{RemoteKey, TvCmd, TvEvent};

const REMOTE_PORT: u16 = 6466;

/// Features clicker is willing to negotiate. The active set is the bitwise AND of
/// this and the server's advertised features, so we never claim a feature the TV
/// does not support (don't echo the server's bits, and don't echo our own blindly).
/// Typed `i32` to match the generated `RemoteConfigure.code1` / `RemoteSetActive.active`.
const CLIENT_FEATURES: i32 = -1; // all bits set (0xFFFF_FFFF as i32)

/// Pure: decode-side mapping of an inbound RemoteMessage to an optional UI event.
/// Unit-tested below; keeps the select loop free of branching logic.
pub fn message_to_event(msg: &rm::RemoteMessage) -> Option<TvEvent> {
    if let Some(v) = &msg.remote_set_volume_level {
        return Some(TvEvent::VolumeChanged {
            level: v.volume_level as u8,
            max: v.volume_max as u8,
            muted: v.volume_muted,
        });
    }
    None
}

fn encode(msg: rm::RemoteMessage) -> Vec<u8> {
    let mut buf = Vec::with_capacity(msg.encoded_len());
    msg.encode(&mut buf).expect("encode RemoteMessage");
    buf
}

/// Build a RemoteMessage carrying a key inject (SHORT press) for `key`.
fn key_message(key: RemoteKey) -> rm::RemoteMessage {
    let mut m = rm::RemoteMessage::default();
    m.remote_key_inject = Some(rm::RemoteKeyInject {
        // key_code is an i32 enum field; the RemoteKey::keycode() integers match
        // RemoteKeyCode's tag values directly (no keymap indirection).
        key_code: key.keycode(),
        // RemoteDirection is a TOP-LEVEL enum in remotemessage.proto.
        direction: rm::RemoteDirection::Short as i32,
    });
    m
}

/// Build a RemoteMessage carrying an app-link launch.
fn applink_message(link: String) -> rm::RemoteMessage {
    let mut m = rm::RemoteMessage::default();
    m.remote_app_link_launch_request = Some(rm::RemoteAppLinkLaunchRequest { app_link: link });
    m
}

/// The connection task (orchestrator). Owns the TLS socket; bridges mpsc channels.
///
/// LIVE: requires a real TV; not unit-tested.
pub async fn run_connection(
    cfg: Config,
    id: ClientIdentity,
    mut cmd_rx: Receiver<TvCmd>,
    ev_tx: Sender<TvEvent>,
) {
    let host = match cfg.host.clone() {
        Some(h) => h,
        None => {
            let _ = ev_tx.send(TvEvent::Error("no TV host configured".into())).await;
            return;
        }
    };

    // --- pairing phase (§4.2) if not yet paired ---
    let mut cfg = cfg;
    if !cfg.paired {
        match pair_flow(&host, &id, &mut cmd_rx, &ev_tx).await {
            Ok(()) => {
                cfg.paired = true;
                if let Err(e) = config::save(&cfg) {
                    let _ = ev_tx.send(TvEvent::Error(format!("save config: {e}"))).await;
                }
                let _ = ev_tx.send(TvEvent::PairingOk).await;
            }
            Err(e) => {
                let _ = ev_tx.send(TvEvent::PairingFailed(e.to_string())).await;
                return;
            }
        }
    }

    // --- remote connect + serve, with reconnect on socket error ---
    loop {
        match serve_once(&host, &cfg, &id, &mut cmd_rx, &ev_tx).await {
            Ok(()) => {
                // cmd channel closed (UI quit) -> exit task cleanly
                let _ = ev_tx.send(TvEvent::Disconnected).await;
                return;
            }
            Err(e) => {
                let _ = ev_tx.send(TvEvent::Disconnected).await;
                let _ = ev_tx.send(TvEvent::Error(e.to_string())).await;
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                // loop -> reconnect
            }
        }
    }
}

/// Run pairing: emit PairingRequired, await SubmitPin, finish (§4.2).
async fn pair_flow(
    host: &str,
    id: &ClientIdentity,
    cmd_rx: &mut Receiver<TvCmd>,
    ev_tx: &Sender<TvEvent>,
) -> anyhow::Result<()> {
    let pairing = crate::pairing::begin(host, id).await?;
    ev_tx.send(TvEvent::PairingRequired).await.ok();

    // Wait for the UI to deliver the typed PIN.
    let pin = loop {
        match cmd_rx.recv().await {
            Some(TvCmd::SubmitPin(p)) => break p,
            Some(_) => continue, // ignore keys while modal is up
            None => anyhow::bail!("cmd channel closed during pairing"),
        }
    };

    pairing.finish(&pin).await
}

/// One full remote session: connect 6466, handshake, then select loop.
async fn serve_once(
    host: &str,
    cfg: &Config,
    id: &ClientIdentity,
    cmd_rx: &mut Receiver<TvCmd>,
    ev_tx: &Sender<TvEvent>,
) -> anyhow::Result<()> {
    let (stream, _server_cert) = crate::tls::connect(host, REMOTE_PORT, id).await?;
    let (mut rd, mut wr): (ReadHalf<TlsStream<TcpStream>>, WriteHalf<TlsStream<TcpStream>>) =
        tokio::io::split(stream);

    // §4.3.2 handshake: <- RemoteConfigure -> echo device_info + feature bits
    handshake(&mut rd, &mut wr).await?;
    ev_tx
        .send(TvEvent::Connected {
            name: cfg.name.clone().unwrap_or_else(|| "Android TV".into()),
        })
        .await
        .ok();

    // §4.3.3-5 serve loop
    loop {
        tokio::select! {
            // inbound from the TV
            framed = read_msg(&mut rd) => {
                let bytes = framed?; // socket error -> reconnect
                let msg = rm::RemoteMessage::decode(&bytes[..])?;

                // keepalive: answer ping immediately (§4.3.3)
                if let Some(ping) = &msg.remote_ping_request {
                    let mut pong = rm::RemoteMessage::default();
                    pong.remote_ping_response = Some(rm::RemotePingResponse { val1: ping.val1 });
                    write_msg(&mut wr, &encode(pong)).await?;
                    continue;
                }
                // state updates -> UI events (volume, …)
                if let Some(ev) = message_to_event(&msg) {
                    ev_tx.send(ev).await.ok();
                }
            }
            // outbound from the UI
            cmd = cmd_rx.recv() => {
                match cmd {
                    Some(TvCmd::Key(k)) => write_msg(&mut wr, &encode(key_message(k))).await?,
                    Some(TvCmd::LaunchApp(url)) => {
                        write_msg(&mut wr, &encode(applink_message(url))).await?
                    }
                    // a stray PIN after pairing: ignore
                    Some(TvCmd::SubmitPin(_)) => {}
                    None => return Ok(()), // UI dropped the sender -> clean exit
                }
            }
        }
    }
}

/// §4.3.2: respond to RemoteConfigure with the MASKED feature set, then
/// RemoteSetActive (with the active feature BITMASK, not a boolean).
async fn handshake(
    rd: &mut ReadHalf<TlsStream<TcpStream>>,
    wr: &mut WriteHalf<TlsStream<TcpStream>>,
) -> anyhow::Result<()> {
    // <- RemoteConfigure
    let first = rm::RemoteMessage::decode(&read_msg(rd).await?[..])?;
    let server_cfg = first
        .remote_configure
        .ok_or_else(|| anyhow::anyhow!("expected RemoteConfigure first"))?;

    // Mask requested features by what the server actually supports — never echo
    // the server's bits and never claim a feature it didn't advertise.
    let active_features = CLIENT_FEATURES & server_cfg.code1;

    // -> RemoteConfigure with device_info + the MASKED feature set
    let mut reply = rm::RemoteMessage::default();
    reply.remote_configure = Some(rm::RemoteConfigure {
        code1: active_features,
        device_info: Some(rm::RemoteDeviceInfo {
            model: "clicker".into(),
            vendor: "clicker".into(),
            unknown1: 1,
            unknown2: "1".into(),
            package_name: "clicker".into(),
            app_version: "1.0".into(),
        }),
    });
    write_msg(wr, &encode(reply)).await?;

    // <- RemoteSetActive
    let active_msg = rm::RemoteMessage::decode(&read_msg(rd).await?[..])?;
    let _active = active_msg
        .remote_set_active
        .ok_or_else(|| anyhow::anyhow!("expected RemoteSetActive"))?;

    // -> RemoteSetActive carrying the MASKED active feature BITMASK (not `true`,
    // and not the server's echoed `active.active`).
    let mut active_reply = rm::RemoteMessage::default();
    active_reply.remote_set_active = Some(rm::RemoteSetActive {
        active: active_features,
    });
    write_msg(wr, &encode(active_reply)).await?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn volume_message_maps_to_event() {
        let mut msg = rm::RemoteMessage::default();
        msg.remote_set_volume_level = Some(rm::RemoteSetVolumeLevel {
            volume_level: 32,
            volume_max: 100,
            volume_muted: false,
            // any other generated fields default-construct.
            ..Default::default()
        });
        let ev = message_to_event(&msg).expect("volume msg -> event");
        match ev {
            TvEvent::VolumeChanged { level, max, muted } => {
                assert_eq!(level, 32);
                assert_eq!(max, 100);
                assert!(!muted);
            }
            other => panic!("wrong event: {other:?}"),
        }
    }

    #[test]
    fn ping_message_maps_to_no_event() {
        // a ping is handled inline by the loop, not surfaced as a TvEvent
        let mut msg = rm::RemoteMessage::default();
        msg.remote_ping_request = Some(rm::RemotePingRequest { val1: 7, ..Default::default() });
        assert!(message_to_event(&msg).is_none());
    }
}
