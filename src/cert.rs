// /home/jane/projects/clicker/src/cert.rs
use std::path::Path;

use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding};
use rsa::traits::PublicKeyParts;
use rsa::{RsaPrivateKey, RsaPublicKey};
use std::fs;

pub struct ClientIdentity {
    pub cert_der: rustls::pki_types::CertificateDer<'static>,
    pub key_der: rustls::pki_types::PrivateKeyDer<'static>,
    pub modulus: Vec<u8>,  // minimal big-endian, NO leading zero byte
    pub exponent: Vec<u8>, // minimal big-endian, NO leading zero byte
}

/// Load `cert.pem`/`key.pem` from `dir` if present; otherwise generate a fresh
/// RSA-2048 self-signed CA cert, persist it, and return the identity.
pub fn load_or_generate(dir: &Path) -> anyhow::Result<ClientIdentity> {
    fs::create_dir_all(dir)?;
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");

    let (cert_pem, key_pkcs8_pem): (String, String) =
        if cert_path.exists() && key_path.exists() {
            (
                fs::read_to_string(&cert_path)?,
                fs::read_to_string(&key_path)?,
            )
        } else {
            // 1) RSA-2048 keypair, public exponent 65537 (rsa crate default).
            let mut rng = rand::thread_rng();
            let priv_key = RsaPrivateKey::new(&mut rng, 2048)?;
            let key_pkcs8_pem = priv_key
                .to_pkcs8_pem(LineEnding::LF)?
                .to_string();

            // 2) Self-signed CA cert via rcgen 0.14, signed by the RSA key.
            let key_pair = rcgen::KeyPair::from_pkcs8_pem_and_sign_algo(
                &key_pkcs8_pem,
                &rcgen::PKCS_RSA_SHA256,
            )?;
            let mut params = rcgen::CertificateParams::new(vec!["clicker".to_string()])?;
            params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Constrained(0));
            params.serial_number = Some(rcgen::SerialNumber::from(1000u64));
            // ~10-year validity (rcgen 0.14 otherwise picks a very wide default range).
            params.not_before = rcgen::date_time_ymd(2024, 1, 1);
            params.not_after = rcgen::date_time_ymd(2034, 1, 1);
            params
                .distinguished_name
                .push(rcgen::DnType::CommonName, "clicker");
            let cert = params.self_signed(&key_pair)?;
            let cert_pem = cert.pem();

            fs::write(&cert_path, &cert_pem)?;
            fs::write(&key_path, &key_pkcs8_pem)?;
            (cert_pem, key_pkcs8_pem)
        };

    // Recover modulus/exponent from the persisted private key (minimal big-endian,
    // no leading zero byte — BigUint::to_bytes_be() is already minimal).
    let priv_key = RsaPrivateKey::from_pkcs8_pem(&key_pkcs8_pem)?;
    let pub_key = RsaPublicKey::from(&priv_key);
    let modulus = pub_key.n().to_bytes_be();
    let exponent = pub_key.e().to_bytes_be();

    // Parse the PEMs into rustls DER types.
    let cert_der = {
        let mut rd = std::io::BufReader::new(cert_pem.as_bytes());
        let cert = rustls_pemfile::certs(&mut rd)
            .next()
            .ok_or_else(|| anyhow::anyhow!("no certificate in cert.pem"))??;
        cert
    };
    let key_der = {
        let mut rd = std::io::BufReader::new(key_pkcs8_pem.as_bytes());
        let key = rustls_pemfile::private_key(&mut rd)?
            .ok_or_else(|| anyhow::anyhow!("no private key in key.pem"))?;
        key
    };

    Ok(ClientIdentity {
        cert_der,
        key_der,
        modulus,
        exponent,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persisted_identity_is_stable_across_loads() {
        let tmp = tempfile::tempdir().unwrap();

        let first = load_or_generate(tmp.path()).unwrap();
        // exponent 65537 => minimal big-endian 0x01 0x00 0x01
        assert_eq!(first.exponent, vec![1, 0, 1]);
        // RSA-2048 modulus is 256 bytes (no leading zero sign byte)
        assert_eq!(first.modulus.len(), 256);
        // no leading zero byte on the minimal big-endian modulus
        assert_ne!(first.modulus[0], 0);

        // files were persisted
        assert!(tmp.path().join("cert.pem").exists());
        assert!(tmp.path().join("key.pem").exists());

        // second call must LOAD the same key, not regenerate
        let second = load_or_generate(tmp.path()).unwrap();
        assert_eq!(
            first.modulus, second.modulus,
            "second load regenerated the key instead of reusing the persisted one"
        );
        assert_eq!(first.exponent, second.exponent);
    }
}
