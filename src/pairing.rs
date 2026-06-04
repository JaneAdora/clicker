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
