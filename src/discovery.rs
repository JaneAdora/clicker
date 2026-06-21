//! mDNS discovery of Android TVs on the LAN (P2).
//!
//! Browses the `_androidtvremote2._tcp.local.` service (the same type Home
//! Assistant's integration uses) and emits a `TvEvent::DiscoveredDevice` per
//! resolved TV. Best-effort: any failure (no multicast, daemon error) is silently
//! non-fatal — the device picker always offers manual IP entry as a fallback,
//! which matters on Android/Termux where multicast is often unreliable.

use std::net::IpAddr;
use std::time::Duration;

use tokio::sync::mpsc::Sender;

use crate::types::TvEvent;

const SERVICE: &str = "_androidtvremote2._tcp.local.";
/// How long one discovery sweep runs before shutting the daemon down. The picker
/// re-runs discovery each time it opens, so a bounded sweep keeps things tidy.
const SWEEP: Duration = Duration::from_secs(8);

/// A resolved TV: display name + host address.
#[derive(Clone, Debug, PartialEq)]
pub struct Found {
    pub name: String,
    pub host: String,
}

/// Pull the instance (display) name out of an mDNS fullname and pair it with the
/// resolved address. `"Living Room._androidtvremote2._tcp.local."` -> `"Living Room"`.
pub fn parse_service(fullname: &str, addr: IpAddr) -> Found {
    let name = fullname
        .split("._androidtvremote2")
        .next()
        .unwrap_or(fullname)
        .to_string();
    Found {
        name,
        host: addr.to_string(),
    }
}

/// Browse for Android TVs for one bounded sweep, sending a `DiscoveredDevice` for
/// each resolved service. Errors are swallowed (best-effort). Shuts the mDNS
/// daemon down when the sweep ends.
pub async fn browse(tx: Sender<TvEvent>) {
    let Ok(mdns) = mdns_sd::ServiceDaemon::new() else {
        return;
    };
    let receiver = match mdns.browse(SERVICE) {
        Ok(r) => r,
        Err(_) => {
            let _ = mdns.shutdown();
            return;
        }
    };

    let deadline = tokio::time::sleep(SWEEP);
    tokio::pin!(deadline);

    loop {
        tokio::select! {
            _ = &mut deadline => break,
            ev = receiver.recv_async() => {
                match ev {
                    Ok(mdns_sd::ServiceEvent::ServiceResolved(info)) => {
                        if let Some(addr) = info.get_addresses().iter().next() {
                            let f = parse_service(info.get_fullname(), *addr);
                            let _ = tx
                                .send(TvEvent::DiscoveredDevice { name: f.name, host: f.host })
                                .await;
                        }
                    }
                    Ok(_) => {}
                    Err(_) => break, // daemon gone
                }
            }
        }
    }

    let _ = mdns.shutdown();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_instance_name() {
        let f = parse_service(
            "Living Room._androidtvremote2._tcp.local.",
            "192.168.1.50".parse().unwrap(),
        );
        assert_eq!(f.name, "Living Room");
        assert_eq!(f.host, "192.168.1.50");
    }

    #[test]
    fn falls_back_to_fullname_when_unsplittable() {
        let f = parse_service("weird-name", "10.0.0.5".parse().unwrap());
        assert_eq!(f.name, "weird-name");
        assert_eq!(f.host, "10.0.0.5");
    }
}
