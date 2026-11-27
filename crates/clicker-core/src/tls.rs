use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::Context;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

use sha2::{Digest, Sha256};

use crate::cert::ClientIdentity;

/// Upper bound on TCP connect plus TLS handshake. A powered-off TV or a wrong IP
/// otherwise leaves the OS connect timeout (often minutes) as the only limit.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);

/// Upper bound on the protocol exchange after the TLS connect: the pairing
/// walk up to the code on screen, the code check, and the remote handshake
/// up to `remote_start`. A TV that takes the socket and then says nothing
/// would otherwise leave "Connecting…" or "Checking…" up for good.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(6);

/// Run `fut`, failing with an error reading exactly `label` if it has not
/// finished within `limit`. The future's own result, error or value, passes
/// through unchanged when it finishes in time.
pub async fn with_deadline<T>(
    limit: Duration,
    label: impl std::fmt::Display,
    fut: impl std::future::Future<Output = anyhow::Result<T>>,
) -> anyhow::Result<T> {
    match tokio::time::timeout(limit, fut).await {
        Ok(result) => result,
        Err(_) => Err(anyhow::anyhow!("{label}")),
    }
}

/// Lowercase hex SHA-256 of a certificate's DER bytes: a stable identity for a TV.
pub fn fingerprint(cert_der: &[u8]) -> String {
    hex::encode(Sha256::digest(cert_der))
}

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
    let _ = rustls::crypto::ring::default_provider().install_default();

    let captured: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
    let verifier = Arc::new(CapturingVerifier::new(captured.clone()));

    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(vec![id.cert_der.clone()], id.key_der.clone_key())
        .context("install client auth cert")?;

    let connector = TlsConnector::from(Arc::new(config));

    // One deadline covers TCP connect and the TLS handshake. A timeout or any TCP
    // failure (refused, no route) reads as "not reachable"; a handshake failure
    // keeps its own message because it means something else (e.g. not a TV).
    let not_reachable = || anyhow::anyhow!("TV not reachable at {host}:{port}");
    let handshake = async {
        let tcp = TcpStream::connect((host, port))
            .await
            .map_err(|_| not_reachable())?;
        // Key presses are tiny writes; without this, Nagle holds a held key's
        // repeats back behind the previous one's ack.
        let _ = tcp.set_nodelay(true);

        // The TV's cert has no real hostname; supply a placeholder ServerName.
        let server_name = ServerName::try_from("clicker").context("server name")?;
        connector
            .connect(server_name, tcp)
            .await
            .context("tls handshake")
    };
    let stream = tokio::time::timeout(CONNECT_TIMEOUT, handshake)
        .await
        .map_err(|_| not_reachable())??;

    let server_cert = captured
        .lock()
        .unwrap()
        .take()
        .context("verifier did not capture a server cert")?;

    Ok((stream, server_cert))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use std::time::SystemTime;

    #[test]
    fn verifier_captures_end_entity_der() {
        // Install a provider so signature_verification_algorithms resolves.
        let _ = rustls::crypto::ring::default_provider().install_default();

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

    #[test]
    fn fingerprint_is_lowercase_sha256_hex() {
        assert_eq!(
            fingerprint(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    /// A local port with nothing listening: bound, then released.
    pub(crate) fn free_port() -> u16 {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    }

    #[tokio::test]
    async fn refused_connect_reads_as_unreachable_at_once() {
        // The timeout itself is covered by the `with_deadline` tests below.
        let dir = tempfile::tempdir().unwrap();
        let id = crate::cert::load_or_generate(dir.path()).unwrap();
        let port = free_port();
        let t0 = std::time::Instant::now();
        let err = connect("127.0.0.1", port, &id).await.unwrap_err();
        assert!(t0.elapsed() < CONNECT_TIMEOUT, "a refused connect must not wait for the timeout");
        assert_eq!(err.to_string(), format!("TV not reachable at 127.0.0.1:{port}"));
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_turns_a_silent_future_into_the_labelled_error() {
        let t0 = tokio::time::Instant::now();
        let silent = std::future::pending::<anyhow::Result<u8>>();
        let err = with_deadline(HANDSHAKE_TIMEOUT, "TV not reachable at 192.0.2.10:6466", silent)
            .await
            .unwrap_err();
        assert_eq!(err.to_string(), "TV not reachable at 192.0.2.10:6466");
        assert_eq!(t0.elapsed(), HANDSHAKE_TIMEOUT);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_passes_through_a_value_in_time() {
        let quick = async {
            tokio::time::sleep(HANDSHAKE_TIMEOUT - Duration::from_millis(1)).await;
            anyhow::Ok(7u8)
        };
        assert_eq!(with_deadline(HANDSHAKE_TIMEOUT, "late", quick).await.unwrap(), 7);
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_keeps_the_inner_error() {
        let failing = async { Err::<u8, _>(anyhow::anyhow!("expected RemoteConfigure first")) };
        let err = with_deadline(HANDSHAKE_TIMEOUT, "late", failing).await.unwrap_err();
        assert_eq!(err.to_string(), "expected RemoteConfigure first");
    }

    #[test]
    fn handshake_timeout_is_six_seconds() {
        assert_eq!(HANDSHAKE_TIMEOUT, Duration::from_secs(6));
    }
}
