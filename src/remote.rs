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
use crate::config::{Config, DeviceEntry};
use crate::framing::{read_msg, write_msg};
use crate::proto::remotemessage as rm;
use crate::types::{RemoteKey, TvCmd, TvEvent};

const REMOTE_PORT: u16 = 6466;

/// Features clicker is willing to negotiate. The active set is the bitwise AND of
/// this and the server's advertised features, so we never claim a feature the TV
/// does not support (don't echo the server's bits, and don't echo our own blindly).
/// Typed `i32` to match the generated `RemoteConfigure.code1` / `RemoteSetActive.active`.
///
/// This is an EXPLICIT mask of only the features clicker actually implements —
/// NOT `-1` (which would over-advertise IME/voice/etc. we don't handle). The bit
/// values come from the tronikos `androidtvremote2` reference `RemoteFeature`
/// constants (the proto here carries no `RemoteFeature` enum to derive them from):
///   PING = 1, KEY = 2, POWER = 32, VOLUME = 64, APP_LINK = 512.
/// PING | KEY | POWER | VOLUME | APP_LINK = 1 | 2 | 32 | 64 | 512 = 611.
const CLIENT_FEATURES: i32 = 0b1001100011; // = 611

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

/// Build a RemoteMessage carrying a key inject (SHORT press) for a raw Android
/// keycode. RemoteDirection is a TOP-LEVEL enum in remotemessage.proto.
fn inject(code: i32) -> rm::RemoteMessage {
    let mut m = rm::RemoteMessage::default();
    m.remote_key_inject = Some(rm::RemoteKeyInject {
        key_code: code,
        direction: rm::RemoteDirection::Short as i32,
    });
    m
}

/// Build a RemoteMessage carrying a key inject (SHORT press) for `key`.
fn key_message(key: RemoteKey) -> rm::RemoteMessage {
    inject(key.keycode())
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
    let device = match cfg.active_device() {
        Some(d) => d.clone(),
        None => {
            let _ = ev_tx.send(TvEvent::Error("no TV selected".into())).await;
            return;
        }
    };
    let host = device.host.clone();

    // --- pairing phase (§4.2) if not yet paired. App is the SOLE config writer,
    //     so we do NOT save here: emit PairingOk and the UI persists paired=true
    //     onto the active device entry (saving here from a stale clone would
    //     clobber the device registry / shortcuts). ---
    if !device.paired {
        // Open the PIN modal once; a wrong PIN keeps it open and re-runs `begin()`
        // (the TV shows a fresh code) instead of stranding the UI in a dead modal.
        let _ = ev_tx.send(TvEvent::PairingRequired).await;
        loop {
            match pair_attempt(&host, &id, &mut cmd_rx).await {
                PairStep::Paired => {
                    let _ = ev_tx.send(TvEvent::PairingOk).await;
                    break;
                }
                PairStep::Retry(msg) => {
                    let _ = ev_tx.send(TvEvent::PairingFailed(msg)).await;
                    // loop: re-begin → the TV displays a new PIN → await a new SubmitPin
                }
                PairStep::Aborted => return, // UI dropped the channel (quit) during pairing
            }
        }
    }

    // --- remote connect + serve, with reconnect on socket error ---
    loop {
        match serve_once(&host, &device, &id, &mut cmd_rx, &ev_tx).await {
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

/// Outcome of one pairing attempt: paired, a retryable failure (wrong PIN / connect
/// error — the caller re-begins so the TV shows a fresh code), or aborted (the UI
/// dropped the command channel, e.g. the user quit).
enum PairStep {
    Paired,
    Retry(String),
    Aborted,
}

/// One pairing attempt: open a fresh pairing session to :6467 (the TV displays a
/// PIN), await the typed PIN from the UI, and submit it (§4.2). PairingRequired is
/// emitted once by the caller, not here, so retries don't reset the open modal.
async fn pair_attempt(host: &str, id: &ClientIdentity, cmd_rx: &mut Receiver<TvCmd>) -> PairStep {
    let pairing = match crate::pairing::begin(host, id).await {
        Ok(p) => p,
        Err(e) => return PairStep::Retry(format!("pairing connect failed: {e}")),
    };

    // Wait for the UI to deliver the typed PIN.
    let pin = loop {
        match cmd_rx.recv().await {
            Some(TvCmd::SubmitPin(p)) => break p,
            Some(_) => continue, // ignore keys while modal is up
            None => return PairStep::Aborted,
        }
    };

    match pairing.finish(&pin).await {
        Ok(()) => PairStep::Paired,
        Err(e) => PairStep::Retry(e.to_string()),
    }
}

/// Inbound queue depth: how many decoded RemoteMessages the dedicated read task
/// may buffer ahead of the main loop. The TV's traffic is bursty-but-tiny
/// (pings, volume) so a small bound is plenty and bounds memory if the main loop
/// ever stalls on a `wr` write.
const INBOUND_CHANNEL: usize = 64;

/// Dedicated READ TASK: owns the read half `rd` exclusively, loops
/// `framing::read_msg`, decodes each frame to a `RemoteMessage`, and forwards it
/// over `inbound_tx`. This is the fix for the TLS-stream-corruption bug: the raw
/// framing read future now lives in a task that ALWAYS runs to completion on each
/// frame, so a partially-consumed varint/frame can never be lost to a cancelled
/// `select!` branch. On read error / EOF the loop ends; dropping `inbound_tx`
/// closes the channel, which the main loop observes as `recv() -> None` and
/// treats as disconnect.
///
/// LIVE: requires a real TV; not unit-tested.
async fn read_task(
    mut rd: ReadHalf<TlsStream<TcpStream>>,
    inbound_tx: Sender<rm::RemoteMessage>,
) {
    loop {
        let bytes = match read_msg(&mut rd).await {
            Ok(b) => b,
            Err(_) => return, // socket error / EOF -> end task, close channel
        };
        let msg = match rm::RemoteMessage::decode(&bytes[..]) {
            Ok(m) => m,
            Err(_) => return, // malformed frame: stream desync -> bail, reconnect
        };
        if inbound_tx.send(msg).await.is_err() {
            return; // main loop gone -> nothing to do
        }
    }
}

/// One full remote session: connect 6466, spawn the read task, handshake (waiting
/// for `remote_start`), then run the steady-state single-writer select loop.
///
/// SINGLE WRITER: this function is the only owner of the write half `wr`. The read
/// task owns `rd`. No half is shared, so there is no read/write contention and no
/// way for a cancelled read to strand bytes.
async fn serve_once(
    host: &str,
    device: &DeviceEntry,
    id: &ClientIdentity,
    cmd_rx: &mut Receiver<TvCmd>,
    ev_tx: &Sender<TvEvent>,
) -> anyhow::Result<()> {
    let (stream, _server_cert) = crate::tls::connect(host, REMOTE_PORT, id).await?;
    let (rd, mut wr): (ReadHalf<TlsStream<TcpStream>>, WriteHalf<TlsStream<TcpStream>>) =
        tokio::io::split(stream);

    // Spawn the dedicated read task; from here on all inbound messages arrive on
    // `inbound_rx`, never by reading `rd` directly.
    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<rm::RemoteMessage>(INBOUND_CHANNEL);
    let reader = tokio::spawn(read_task(rd, inbound_tx));

    // Run the handshake + wait-for-remote_start phase. Anything that goes wrong
    // (channel closed early = read task died, protocol violation) is a session
    // error: abort the reader and propagate so the outer loop reconnects.
    let result = serve_handshake_and_loop(device, cmd_rx, ev_tx, &mut wr, &mut inbound_rx).await;
    reader.abort();
    result
}

/// Handshake (§4.3.2) → wait for `remote_start` (§4.3.x) → steady-state loop.
/// Split out so `serve_once` can always abort the read task afterwards.
async fn serve_handshake_and_loop(
    device: &DeviceEntry,
    cmd_rx: &mut Receiver<TvCmd>,
    ev_tx: &Sender<TvEvent>,
    wr: &mut WriteHalf<TlsStream<TcpStream>>,
    inbound_rx: &mut Receiver<rm::RemoteMessage>,
) -> anyhow::Result<()> {
    // --- §4.3.2 handshake: <- RemoteConfigure -> echo device_info + feature bits,
    //     <- RemoteSetActive -> echo masked active mask. ---
    handshake(wr, inbound_rx).await?;

    // --- Wait for the TV's `remote_start` before declaring Connected. The
    //     reference does not treat the remote as ready until `remote_start`
    //     arrives. While waiting we still answer pings so the link stays alive. ---
    loop {
        let msg = inbound_rx
            .recv()
            .await
            .ok_or_else(|| anyhow::anyhow!("read task ended before remote_start"))?;
        if let Some(ping) = &msg.remote_ping_request {
            answer_ping(wr, ping.val1).await?;
            continue;
        }
        if let Some(start) = &msg.remote_start {
            // `started` is informational; arrival of remote_start is the gate.
            let _ = start.started;
            break;
        }
        // Any other message before remote_start (e.g. an early volume update) is
        // surfaced but does not flip us to Connected yet.
        if let Some(ev) = message_to_event(&msg) {
            ev_tx.send(ev).await.ok();
        }
    }

    // remote_start seen → NOW we're ready to accept commands.
    let name = if device.name.is_empty() {
        "Android TV".to_string()
    } else {
        device.name.clone()
    };
    ev_tx.send(TvEvent::Connected { name }).await.ok();

    // --- §4.3.3-5 steady-state serve loop. Single writer (`wr`); inbound arrives
    //     only via `inbound_rx`. No raw framing read future in this select. ---
    loop {
        tokio::select! {
            // inbound, already framed + decoded by the read task
            inbound = inbound_rx.recv() => {
                let Some(msg) = inbound else {
                    // read task ended (socket error / EOF) -> disconnect, reconnect
                    return Ok(());
                };

                // keepalive: answer ping immediately (§4.3.3)
                if let Some(ping) = &msg.remote_ping_request {
                    answer_ping(wr, ping.val1).await?;
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
                    Some(TvCmd::Key(k)) => write_msg(wr, &encode(key_message(k))).await?,
                    Some(TvCmd::RawKey(code)) => write_msg(wr, &encode(inject(code))).await?,
                    Some(TvCmd::LaunchApp(url)) => {
                        write_msg(wr, &encode(applink_message(url))).await?
                    }
                    // a stray PIN after pairing: ignore
                    Some(TvCmd::SubmitPin(_)) => {}
                    None => return Ok(()), // UI dropped the sender -> clean exit
                }
            }
        }
    }
}

/// -> RemotePingResponse echoing the request's `val1` (§4.3.3). Single-writer.
async fn answer_ping(wr: &mut WriteHalf<TlsStream<TcpStream>>, val1: i32) -> anyhow::Result<()> {
    let mut pong = rm::RemoteMessage::default();
    pong.remote_ping_response = Some(rm::RemotePingResponse { val1 });
    write_msg(wr, &encode(pong)).await?;
    Ok(())
}

/// §4.3.2: respond to RemoteConfigure with the MASKED feature set, then
/// RemoteSetActive (with the active feature BITMASK, not a boolean). Inbound
/// messages are taken from `inbound_rx` (fed by the dedicated read task); the
/// write half `wr` is used exclusively here.
async fn handshake(
    wr: &mut WriteHalf<TlsStream<TcpStream>>,
    inbound_rx: &mut Receiver<rm::RemoteMessage>,
) -> anyhow::Result<()> {
    // <- RemoteConfigure
    let first = inbound_rx
        .recv()
        .await
        .ok_or_else(|| anyhow::anyhow!("read task ended before RemoteConfigure"))?;
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
    let active_msg = inbound_rx
        .recv()
        .await
        .ok_or_else(|| anyhow::anyhow!("read task ended before RemoteSetActive"))?;
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
