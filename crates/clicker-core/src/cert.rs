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

/// Create `dir` (and parents). On Unix a directory this call creates gets mode
/// 0o700; an existing directory is left as it is.
fn create_private_dir(dir: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
    }
    #[cfg(not(unix))]
    fs::create_dir_all(dir)?;
    Ok(())
}

/// Write the private key. On Unix the file is created with mode 0o600, so it is
/// never readable by others, not even briefly.
fn write_private_file(path: &Path, contents: &str) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::io::Write;
        // `.mode()` only applies when the file is created; a stale file keeps its
        // old (possibly loose) mode, so remove it first.
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
        use std::os::unix::fs::OpenOptionsExt;
        let mut f = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)?;
        f.write_all(contents.as_bytes())?;
    }
    #[cfg(not(unix))]
    fs::write(path, contents)?;
    Ok(())
}

/// On Unix, tighten an existing key file that has any group/other bits to 0o600.
fn tighten_key_permissions(path: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = fs::metadata(path)?.permissions().mode();
        if mode & 0o077 != 0 {
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

/// Load `cert.pem`/`key.pem` from `dir` if present; otherwise generate a fresh
/// RSA-2048 self-signed CA cert, persist it, and return the identity.
pub fn load_or_generate(dir: &Path) -> anyhow::Result<ClientIdentity> {
    create_private_dir(dir)?;
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");

    let (cert_pem, key_pkcs8_pem): (String, String) =
        if cert_path.exists() && key_path.exists() {
            tighten_key_permissions(&key_path)?;
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
            write_private_file(&key_path, &key_pkcs8_pem)?;
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

    #[cfg(unix)]
    #[test]
    fn key_file_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("cfg");
        let first = load_or_generate(&dir).unwrap();
        let key = dir.join("key.pem");
        let mode = |p: &Path| fs::metadata(p).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&key), 0o600);
        assert_eq!(mode(&dir), 0o700);

        fs::set_permissions(&key, fs::Permissions::from_mode(0o644)).unwrap();
        let second = load_or_generate(&dir).unwrap();
        assert_eq!(mode(&key), 0o600);
        assert_eq!(first.modulus, second.modulus);
    }

    #[cfg(unix)]
    #[test]
    fn regenerated_key_over_loose_stale_file_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tempfile::tempdir().unwrap();
        let key = tmp.path().join("key.pem");
        fs::write(&key, "junk").unwrap();
        fs::set_permissions(&key, fs::Permissions::from_mode(0o644)).unwrap();
        load_or_generate(tmp.path()).unwrap();
        assert_eq!(fs::metadata(&key).unwrap().permissions().mode() & 0o777, 0o600);
    }
}
