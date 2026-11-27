# Security

## How Clicker trusts a TV

Android TV and Google TV sets use self-signed certificates for the Remote
protocol, so there is no certificate authority to check them against. Clicker
therefore accepts any certificate the TV presents during the TLS handshake and
relies on two other things:

- **Pairing.** A TV only accepts remote commands from a client whose certificate
  it has paired with. Pairing needs the six-character code the TV shows on its own
  screen, which proves you are in front of it.
- **Trust on first use.** After the first successful connection, Clicker records
  the SHA-256 fingerprint of the TV's certificate. On later connections a TV at
  the same address that presents a different certificate is treated as a different
  TV: the Android app stops and asks before doing anything, and nothing is sent to
  it first.

What this means in practice:

- Someone already on your local network could impersonate a TV you have never
  connected to. They cannot impersonate one you have connected to before without
  that TV's private key.
- Network discovery (mDNS) is not authenticated. The Android app never changes a
  saved TV's address because of a discovery result alone. A new address is used
  only after the device there presents the saved fingerprint.
- Use Clicker on networks you trust. It makes no connections outside your local
  network and has no accounts, analytics or cloud service.

## The client key

Clicker generates one RSA key pair and certificate on first run. Anyone holding
that key can control the TVs paired with it, so treat it like a password.

- Terminal app: `~/.config/clicker/key.pem`, written with owner-only permissions.
- Android app: the app's private storage. Backup and device transfer are turned
  off for it, so the key does not leave the phone.

## What the Android app keeps private

- Pairing codes are held in memory only and are never logged.
- Text typed to the TV is never logged or saved, and the app asks the keyboard
  not to learn from it.
- Release builds log connection states only, with no TV names or addresses.

## Reporting a problem

Please open an issue at https://github.com/JaneAdora/clicker/issues. If the
report is sensitive, say so in the issue without the details and the maintainer
will arrange a private channel.
