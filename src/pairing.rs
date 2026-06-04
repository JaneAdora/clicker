use anyhow::{bail, Context};
use sha2::{Digest, Sha256};
use x509_parser::prelude::*;
use x509_parser::public_key::PublicKey;

/// Strip a single leading 0x00 sign byte if present (DER INTEGER artifact).
/// The reference hashes minimal unsigned big-endian with NO leading zero (§4.2).
fn strip_leading_zero(mut bytes: Vec<u8>) -> Vec<u8> {
    if bytes.first() == Some(&0x00) {
        bytes.remove(0);
    }
    bytes
}

/// Parse an X.509 DER cert and return (modulus, exponent) of its RSA public key,
/// each as minimal unsigned big-endian with any single leading 0x00 stripped (§4.4).
pub fn rsa_params_from_cert_der(der: &[u8]) -> anyhow::Result<(Vec<u8>, Vec<u8>)> {
    let (_, cert) = X509Certificate::from_der(der).context("parse x509 cert")?;
    let spki = cert.public_key();
    let rsa = spki.parsed().context("parse subject public key")?;
    let rsa = match rsa {
        PublicKey::RSA(rsa) => rsa,
        _ => bail!("server certificate is not RSA"),
    };
    let modulus = strip_leading_zero(rsa.modulus.to_vec());
    let exponent = strip_leading_zero(rsa.exponent.to_vec());
    Ok((modulus, exponent))
}

/// Compute the pairing secret per §4.2:
///   secret = SHA256( client_n ‖ client_e ‖ server_n ‖ server_e ‖ code_bytes[1..] )
/// where code_bytes = fromhex(6-char PIN). Verifies secret[0] == code_bytes[0]
/// (first PIN byte is a checksum); errors on mismatch.
pub fn compute_secret(
    client_n: &[u8],
    client_e: &[u8],
    server_n: &[u8],
    server_e: &[u8],
    pin: &str,
) -> anyhow::Result<Vec<u8>> {
    let pin = pin.trim();
    if pin.len() != 6 {
        bail!("PIN must be exactly 6 hex characters, got {}", pin.len());
    }
    let code_bytes = hex::decode(pin).context("PIN is not valid hex")?;
    if code_bytes.len() != 3 {
        bail!("PIN must decode to exactly 3 bytes, got {}", code_bytes.len());
    }

    let mut hasher = Sha256::new();
    hasher.update(client_n);
    hasher.update(client_e);
    hasher.update(server_n);
    hasher.update(server_e);
    hasher.update(&code_bytes[1..]); // skip the checksum nibble-pair
    let digest = hasher.finalize();

    if digest[0] != code_bytes[0] {
        bail!(
            "PIN checksum mismatch: hash[0]=0x{:02x} != pin[0]=0x{:02x}",
            digest[0],
            code_bytes[0]
        );
    }

    Ok(digest.to_vec())
}

// ============================================================================
// §4.2 pairing handshake over framed protobuf to :6467.
//
// LIVE/INTEGRATION code: the walk below needs a real TV and is exercised in the
// integration milestone, not in unit tests. It is written to COMPILE against the
// real prost-generated `crate::proto::polo` types. Notable prost facts (proto2):
//   * `OuterMessage.protocol_version` is `u32` (required), `status` is `i32`
//     (required, enum-backed) — NOT `Option<_>`.
//   * Payload fields are `options` / `configuration` / `configuration_ack` /
//     `secret` / `secret_ack` (plus `pairing_request[_ack]`), each `Option<T>`.
//   * `PairingRequest.service_name` is `String` (required); `client_name` is
//     `Option<String>`.
//   * `Options::Encoding.r#type` is `i32`, `symbol_length` is `u32` (both required).
//   * `Configuration.encoding` is `options::Encoding` (required, NOT Option),
//     `client_role` is `i32` (required).
//   * `Secret.secret` is `Vec<u8>` (required, NOT Option).
//   * Status enum variant is `outer_message::Status::Ok` (= 200).
// ============================================================================

use prost::Message;
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;

use crate::cert::ClientIdentity;
use crate::framing::{read_msg, write_msg};
use crate::proto::polo;

const PAIRING_PORT: u16 = 6467;

/// Owns the TLS stream to the pairing port plus everything needed to finish.
pub struct Pairing {
    stream: TlsStream<TcpStream>,
    client_n: Vec<u8>,
    client_e: Vec<u8>,
    server_n: Vec<u8>,
    server_e: Vec<u8>,
}

/// Build an OK `OuterMessage` skeleton (proto2 required `protocol_version` +
/// `status` set; every `Option` payload field defaults to `None`). Callers fill
/// in the one relevant payload field.
fn outer_skeleton() -> polo::OuterMessage {
    polo::OuterMessage {
        protocol_version: 2,
        status: polo::outer_message::Status::Ok as i32,
        ..Default::default()
    }
}

fn encode_outer(msg: &polo::OuterMessage) -> Vec<u8> {
    let mut buf = Vec::with_capacity(msg.encoded_len());
    msg.encode(&mut buf).expect("encode OuterMessage");
    buf
}

fn parse_outer(bytes: &[u8]) -> anyhow::Result<polo::OuterMessage> {
    let m = polo::OuterMessage::decode(bytes).context("decode OuterMessage")?;
    if m.status != polo::outer_message::Status::Ok as i32 {
        bail!("pairing peer returned status {}", m.status);
    }
    Ok(m)
}

/// Connect to the TV pairing port and walk PairingRequest -> ConfigurationAck.
/// Returns once the TV is displaying its 6-hex PIN (§4.2 steps 1-7).
///
/// LIVE: requires a real TV; not unit-tested.
pub async fn begin(host: &str, id: &ClientIdentity) -> anyhow::Result<Pairing> {
    // §4.2.1 TLS connect; capture server cert DER.
    let (mut stream, server_cert) = crate::tls::connect(host, PAIRING_PORT, id).await?;
    let (server_n, server_e) = rsa_params_from_cert_der(&server_cert)?;

    // §4.2.2 -> PairingRequest
    let req = polo::OuterMessage {
        pairing_request: Some(polo::PairingRequest {
            service_name: "clicker".into(),
            client_name: Some("clicker".into()),
        }),
        ..outer_skeleton()
    };
    write_msg(&mut stream, &encode_outer(&req)).await?;

    // §4.2.3 <- PairingRequestAck
    let ack = parse_outer(&read_msg(&mut stream).await?)?;
    if ack.pairing_request_ack.is_none() {
        bail!("expected PairingRequestAck, got {ack:?}");
    }

    // §4.2.4 -> Options { HEXADECIMAL, symbol_length 6, role INPUT }
    let encoding = polo::options::Encoding {
        r#type: polo::options::encoding::EncodingType::Hexadecimal as i32,
        symbol_length: 6,
    };
    let mut opt = polo::Options::default();
    opt.input_encodings.push(encoding);
    opt.preferred_role = Some(polo::options::RoleType::Input as i32);
    let opt_msg = polo::OuterMessage {
        options: Some(opt),
        ..outer_skeleton()
    };
    write_msg(&mut stream, &encode_outer(&opt_msg)).await?;

    // §4.2.5 <- server Options (NOT an ack message)
    let server_opts = parse_outer(&read_msg(&mut stream).await?)?;
    if server_opts.options.is_none() {
        bail!("expected server Options, got {server_opts:?}");
    }

    // §4.2.6 -> Configuration { encoding, client_role }
    let cfg_msg = polo::OuterMessage {
        configuration: Some(polo::Configuration {
            encoding: polo::options::Encoding {
                r#type: polo::options::encoding::EncodingType::Hexadecimal as i32,
                symbol_length: 6,
            },
            client_role: polo::options::RoleType::Input as i32,
        }),
        ..outer_skeleton()
    };
    write_msg(&mut stream, &encode_outer(&cfg_msg)).await?;

    // §4.2.7 <- ConfigurationAck  (TV now shows the PIN)
    let cfg_ack = parse_outer(&read_msg(&mut stream).await?)?;
    if cfg_ack.configuration_ack.is_none() {
        bail!("expected ConfigurationAck, got {cfg_ack:?}");
    }

    let (client_n, client_e) = (id.modulus.clone(), id.exponent.clone());
    Ok(Pairing {
        stream,
        client_n,
        client_e,
        server_n,
        server_e,
    })
}

impl Pairing {
    /// Finish pairing: compute secret from the PIN, send Secret,
    /// await SecretAck (§4.2 steps 8-11).
    ///
    /// LIVE: requires a real TV; not unit-tested.
    pub async fn finish(mut self, pin: &str) -> anyhow::Result<()> {
        let secret = compute_secret(
            &self.client_n,
            &self.client_e,
            &self.server_n,
            &self.server_e,
            pin,
        )?;

        // §4.2.10 -> Secret
        let secret_msg = polo::OuterMessage {
            secret: Some(polo::Secret { secret }),
            ..outer_skeleton()
        };
        write_msg(&mut self.stream, &encode_outer(&secret_msg)).await?;

        // §4.2.11 <- SecretAck
        let ack = parse_outer(&read_msg(&mut self.stream).await?)?;
        if ack.secret_ack.is_none() {
            bail!("expected SecretAck, got {ack:?}");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // KNOWN-ANSWER VECTOR. Expected values produced by this exact python3:
    //   client_n = bytes([0xC0,0xFF,0xEE,0x01]); client_e = bytes([0x01,0x00,0x01])
    //   server_n = bytes([0xDE,0xAD,0xBE,0xEF]); server_e = bytes([0x01,0x00,0x01])
    //   tail     = bytes([0xAB,0xCD])
    //   h = sha256(client_n+client_e+server_n+server_e+tail).digest()
    //   PIN = bytes([h[0]]) + tail  -> "82ABCD"
    const CLIENT_N: [u8; 4] = [0xC0, 0xFF, 0xEE, 0x01];
    const CLIENT_E: [u8; 3] = [0x01, 0x00, 0x01];
    const SERVER_N: [u8; 4] = [0xDE, 0xAD, 0xBE, 0xEF];
    const SERVER_E: [u8; 3] = [0x01, 0x00, 0x01];
    const PIN: &str = "82ABCD";
    // Full 64-char digest for the constants above (verified via the python3 step):
    const EXPECTED_DIGEST: &str =
        "8216d1d283f27463280c6c6b00be3b67aa31b7f8ba0e1e0da69c4bd325889c41";

    #[test]
    fn known_answer_secret() {
        let secret = compute_secret(&CLIENT_N, &CLIENT_E, &SERVER_N, &SERVER_E, PIN).unwrap();
        assert_eq!(hex::encode(&secret), EXPECTED_DIGEST);
        // checksum byte equals the first PIN byte
        assert_eq!(secret[0], 0x82);
    }

    #[test]
    fn checksum_mismatch_errors() {
        // Same tail (ABCD) but wrong checksum nibble-pair (00) must fail.
        let bad_pin = "00ABCD";
        let err = compute_secret(&CLIENT_N, &CLIENT_E, &SERVER_N, &SERVER_E, bad_pin)
            .unwrap_err();
        assert!(
            err.to_string().contains("checksum mismatch"),
            "got: {err}"
        );
    }

    #[test]
    fn strips_single_leading_zero() {
        // input WITH a leading 0x00 -> stripped output; one zero only.
        assert_eq!(strip_leading_zero(vec![0x00, 0xDE, 0xAD]), vec![0xDE, 0xAD]);
        assert_eq!(strip_leading_zero(vec![0x00, 0x00, 0x01]), vec![0x00, 0x01]);
        assert_eq!(strip_leading_zero(vec![0x12, 0x34]), vec![0x12, 0x34]);
    }

    #[test]
    fn bad_length_pin_errors() {
        // 4 hex chars (2 bytes) must be rejected before any hashing.
        let err = compute_secret(&CLIENT_N, &CLIENT_E, &SERVER_N, &SERVER_E, "82AB")
            .unwrap_err();
        assert!(
            err.to_string().contains("6 hex characters"),
            "got: {err}"
        );
        // 8 hex chars (4 bytes) likewise.
        let err = compute_secret(&CLIENT_N, &CLIENT_E, &SERVER_N, &SERVER_E, "82ABCDEF")
            .unwrap_err();
        assert!(err.to_string().contains("6 hex characters"), "got: {err}");
    }
}
