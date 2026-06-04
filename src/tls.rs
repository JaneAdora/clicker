use std::sync::{Arc, Mutex};

use anyhow::Context;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

use crate::cert::ClientIdentity;

/// A rustls verifier that accepts ANY server certificate (the TV is self-signed)
/// and copies the presented end-entity DER into shared state so the pairing code
/// can read the server's RSA modulus/exponent (§4.2, §4.4).
#[derive(Debug)]
pub struct CapturingVerifier {
    captured: Arc<Mutex<Option<Vec<u8>>>>,
    provider: Arc<CryptoProvider>,
}

impl CapturingVerifier {
    pub fn new(captured: Arc<Mutex<Option<Vec<u8>>>>) -> Self {
        Self {
            captured,
            provider: rustls::crypto::CryptoProvider::get_default()
                .expect("default crypto provider installed")
                .clone(),
        }
    }
}

impl ServerCertVerifier for CapturingVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        *self.captured.lock().unwrap() = Some(end_entity.as_ref().to_vec());
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// TLS-connect to `host:port` presenting the client cert/key from `id`,
/// accepting the TV's self-signed cert. Returns the stream plus the captured
/// server cert DER (§4.4).
pub async fn connect(
    host: &str,
    port: u16,
    id: &ClientIdentity,
) -> anyhow::Result<(TlsStream<TcpStream>, Vec<u8>)> {
    // Ensure a default crypto provider exists (idempotent; ignore "already set").
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

    let captured: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
    let verifier = Arc::new(CapturingVerifier::new(captured.clone()));

    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(vec![id.cert_der.clone()], id.key_der.clone_key())
        .context("install client auth cert")?;

    let connector = TlsConnector::from(Arc::new(config));

    let tcp = TcpStream::connect((host, port))
        .await
        .with_context(|| format!("tcp connect {host}:{port}"))?;

    // The TV's cert has no real hostname; supply a placeholder ServerName.
    let server_name = ServerName::try_from("clicker").context("server name")?;
    let stream = connector
        .connect(server_name, tcp)
        .await
        .context("tls handshake")?;

    let server_cert = captured
        .lock()
        .unwrap()
        .take()
        .context("verifier did not capture a server cert")?;

    Ok((stream, server_cert))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use std::time::SystemTime;

    #[test]
    fn verifier_captures_end_entity_der() {
        // Install a provider so signature_verification_algorithms resolves.
        let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();

        let captured: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
        let verifier = CapturingVerifier::new(captured.clone());

        let fake_der = vec![0x30u8, 0x82, 0x01, 0x02, 0xDE, 0xAD, 0xBE, 0xEF];
        let cert = CertificateDer::from(fake_der.clone());
        let name = ServerName::try_from("clicker").unwrap();
        let now = UnixTime::since_unix_epoch(
            SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap(),
        );

        let result = verifier.verify_server_cert(&cert, &[], &name, &[], now);
        assert!(result.is_ok(), "self-signed cert must be accepted");

        let stored = captured.lock().unwrap().clone();
        assert_eq!(stored, Some(fake_der), "end-entity DER must be captured verbatim");
    }
}
