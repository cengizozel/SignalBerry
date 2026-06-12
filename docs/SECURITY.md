# Security and privacy

SignalBerry exists so you can use Signal on a BlackBerry without routing your
messages through anyone else. This document explains what that buys you, what the
real protections are, and the honest limits.

## Threat model

SignalBerry is a self hosted personal tool. The assumptions are:

- You run the server (signal-cli-rest-api and the bridge), on a machine you
  control.
- You are the only user. Other people are not meant to reach your server.
- The repository and the built APK are public. They must contain no secrets.

What it defends against:

- Anyone on the public internet finding and using your exposed services.
- Your message database being pulled off the phone through device backup.
- Third parties seeing your message contents in transit when you go remote.

What it does not try to be:

- A multi user system with per device identity. The remote auth is a single
  shared secret, which is the right fit for a phone you own.
- A replacement for Signal's own end to end encryption. That still happens
  between Signal clients. signal-cli is a normal linked device on your account,
  and SignalBerry talks to it.

## Where your data lives

- Message contents and history live in your signal-cli server and in the bridge's
  SQLite database, both on hardware you run.
- On the phone, the app keeps a local copy in its private app storage so chats
  open instantly.
- There is no SignalBerry account, no analytics, no telemetry, and no call home.
  The app only ever contacts the addresses you configure.

## Remote access protection

When the services are only on your home WiFi, they are reachable solely from your
own network. The moment you expose them to the internet, two things protect them.

**A shared bearer token.** You set `SB_AUTH_TOKEN` on the server. The app sends it
as `Authorization: Bearer <token>` on every request. Two enforcers check it:

- The bridge checks it in process and returns 401 to anything without it. The
  comparison is constant time so the check does not leak the token by timing.
- signal-cli-rest-api has no authentication of its own, so a small bundled nginx
  proxy sits in front of it and enforces the same token, including on the
  WebSocket upgrade. You expose the proxy, never signal-cli's raw port.

Set the token and every request must carry it, so a stranger who discovers your
address is rejected before reaching your data. Leaving the token unset runs the
server open, which is fine on a trusted home network and never for the internet.

**Optional Cloudflare Access.** If you tunnel through Cloudflare you can add a
service token at Cloudflare's edge, which rejects unauthorized requests before
they ever reach your server. This is an extra layer on top of the bearer token,
not a requirement. The design is transport agnostic: the token protects the
services whether you use Cloudflare, a VPN, a VPS proxy, or a plain port forward.

## Transport encryption on a 2013 device

The BlackBerry 10 Android runtime only negotiates TLS 1.0 with old ciphers, which
modern endpoints reject outright, and its certificate store predates today's
certificate authorities. Rather than weaken the server to match, the app bundles
[Conscrypt](https://github.com/google/conscrypt) (Google's BoringSSL based TLS
provider) and installs it as the top security provider at startup. The app then
negotiates TLS 1.3 with modern ciphers and validates current certificates, which
was verified to work on a real Q10. On a plain home LAN over http there is no TLS
to worry about; Conscrypt only matters once you go remote over https.

## On device hygiene

- Read receipts are off by default, so you do not silently leak read status. There
  is a setting to enable them, with a warning, for people who want them.
- `android:allowBackup="false"` is set, so the message database cannot be pulled
  off the device through `adb backup` or cloud backup.
- Logging out wipes the local database, the attachment store, caches, and the
  saved keys and addresses, leaving nothing behind on the device.
- Per conversation purge and a full purge clear both the device and the bridge,
  with the bridge running VACUUM so deleted content does not linger in free pages.

## Secrets and the public repo

Tokens, Cloudflare credentials, and your phone number are never committed. They
live only in the server's `.env` (gitignored) and the app's local preferences,
entered on the connect screen. The repository carries placeholders only.

## Honest limitations

- The remote token is a single shared secret embedded in the app. Anyone who can
  extract it from the APK can impersonate you. For a personal single device setup
  this is an acceptable trade. Hardening beyond it would mean client certificates,
  which is overkill here.
- "Delete for me" performed on your phone cannot be propagated to SignalBerry,
  because signal-cli does not expose that sync message. "Delete for everyone"
  works everywhere.
- The bridge keeps its own copy of expired disappearing messages server side. The
  app honors expiry on the device; bridge side expiry is a planned follow up.
