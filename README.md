# SignalBerry

A native Signal messenger client for BlackBerry 10 devices, built to be fast and
light on hardware from 2013.

<p align="center">
  <img src="screenshots/bb-ml.jpg" width="45%" alt="Message list (light mode)"/>
  &nbsp;&nbsp;
  <img src="screenshots/bb-chat.jpg" width="45%" alt="Chat (dark mode)"/>
</p>

SignalBerry runs inside the BlackBerry 10 Android runtime (Android 4.3 / API 18).
It speaks to your own [signal-cli](https://github.com/AsamK/signal-cli) instance
through the [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api)
Docker image, with a small companion [SignalBerry Bridge](https://github.com/cengizozel/SignalBerryBridge)
that persists history and serves a catch-up feed. You host both yourself, so your
messages stay on your hardware and your server.

## Why this exists

I care about BlackBerry because I care about digital minimalism. I like physical
keyboards, I like a phone that does not pull me into infinite scrolling, and I
like that these devices come from a time when designs were not all the same slab.
There is also real satisfaction in seeing how much life is left in an old device,
and in being part of the small community that keeps these phones useful.

The catch is that the modern Signal app does not run on BlackBerry 10, and Signal
has no third party client story. SignalBerry is my attempt to have a proper Signal
client on the device I actually want to carry.

## How it compares to Instant

The only other practical way to use Signal on a BlackBerry today is
[Instant](https://instant.waitberry.com), a Matrix client. It is a genuinely
impressive piece of work, more than a year of effort, fully featured, and I
respect it a lot. It reaches Signal through a Matrix to Signal bridge, which means
your messages travel through a Matrix homeserver and a bridge before they reach
you, and some of that lives on infrastructure you may not run yourself. It is also
closed source. In daily use it is heavy: it lags, and opening a conversation can
take a while.

SignalBerry takes a narrower path. It talks to Signal through signal-cli on a
server you control, with fewer hops and no Matrix layer in between. It is open
source, so you can read exactly what it does, and it is built to open a chat
instantly rather than after a spinner. The trade is that SignalBerry only does
Signal, where Instant bridges many networks. Different goals, and I am grateful
Instant exists either way.

## How it works

There are two things you set up.

1. **The server**, from the [bridge repo](https://github.com/cengizozel/SignalBerryBridge).
   One `docker compose up` starts everything it needs: signal-cli-rest-api (the
   actual Signal client, registered as a linked device on your account, port 5000)
   and the SignalBerry Bridge (a small Python service that writes every incoming
   message into SQLite and serves a monotonic change feed, port 9099).
2. **SignalBerry** (the Android app). It holds one WebSocket to signal-cli for
   realtime receive, sends over the REST API, and reports its own sends back to
   the bridge so both sides agree on history.

The bridge is what makes the app feel instant. signal-cli does not store anything,
so without the bridge a client that was offline would miss whatever happened while
it was away. The bridge records everything, including reactions to old messages,
edits, deletes, quotes, and receipt statuses, and tags each change with an
ever increasing sequence number. On reconnect the app asks for everything after
the last number it saw, and replays it exactly once. No polling, no full resync,
no guessing.

A deliberate quirk worth knowing: signal-cli does not echo a linked device's own
sends back to that device. So the app tells the bridge about each message it
sends, and the bridge folds those into the same history, which is why your sent
messages and their delivery and read receipts line up correctly across restarts.

For the deeper design rationale, see [docs/REDESIGN.md](docs/REDESIGN.md).

## Features

- Realtime messaging over a single WebSocket, with change feed catch-up that
  replays anything missed while offline, exactly once.
- Message status, from pending to sent to delivered to read, with failed sends
  offering tap to retry.
- Replies, emoji reactions (add and remove), and edits with history, all synced.
- Delete for everyone (leaving a "Message deleted" placeholder), local delete,
  and per conversation wipe.
- Disappearing messages: expiry timers are honored and messages are scrubbed
  when their timer runs out.
- Group chats: messages, media, replies, reactions, edits, delete for everyone,
  per member sender names, group avatars, and typing a member mention.
- Media: images with zoom, save, and share; video with thumbnails, tap to
  download, and an in app player; voice notes with a real waveform, scrubbing,
  and pitch preserving speed control; uploads stream with constant memory.
- Voice messages: record, send, and play back inline.
- Conversation list with snippets, smart timestamps, unread badges, mute per
  chat, local rename, and a Note to Self thread with its own icon.
- Search across conversations, and full message history search inside a chat
  with match navigation.
- Hardware keyboard friendly: Enter sends by default, and it is a setting you
  can turn off for newline behaviour.
- Notifications per sender with counts, restart on boot, offline gap catch up,
  and mark as read on open.
- Light and dark mode.

## Setup

You need the two Docker containers running on a machine your phone can reach.
The [bridge repo](https://github.com/cengizozel/SignalBerryBridge) ships a
`docker-compose.yml` that starts both.

**1. Start the server.**

```bash
git clone https://github.com/cengizozel/SignalBerryBridge
cd SignalBerryBridge
echo "SIGNAL_NUMBER=+12223334444" > .env
docker compose up -d --build
```

This brings up `signal-api` (signal-cli-rest-api) on port 5000 and `signal-bridge`
on port 9099.

**2. Link your Signal account.** Open this on the Docker host and scan the QR
from your phone under Signal, Settings, Linked Devices, the plus button:

```
http://YOUR_HOST:5000/v1/qrcodelink?device_name=signal-api
```

Confirm it linked:

```bash
curl http://YOUR_HOST:5000/v1/accounts
```

**3. Connect the app.** Install the APK (grab it from
[Releases](https://github.com/cengizozel/SignalBerry/releases)), open SignalBerry,
and fill in the connect screen. What you enter depends on whether your phone is on the same network as the
server or reaching it over the internet.

On your home network, point the app straight at the services:

- **Your Signal number** in E.164 format, for example `+12223334444`.
- **signal-cli URL**: `YOUR_HOST:5000`
- **Bridge URL**: `YOUR_HOST:9099`
- Leave the bridge token unchecked.

Over the internet, do not expose port 5000 directly. See the next section, then
enter your public addresses and check the bridge token box. Tap Connect.

### Ports, and using it away from home

There are two ways to reach signal-cli, and the difference is authentication:

- **Port 5000** is signal-cli-rest-api itself, with no authentication. Anything
  that can reach it can send and read your messages, so use it only on a trusted
  home network.
- **Port 5001** is a bundled auth proxy that sits in front of signal-cli and checks
  a shared token, forwarding only valid requests through to it. This is the one you
  expose to the internet, never 5000. (5001 is the default; if that port is already
  taken on your machine, set `API_AUTH_PORT` and use that value instead.)

The bridge on port 9099 is separate and has its own built-in token check, so it
needs no proxy.

So at home you point the app at `5000` and `9099` with no token. To go remote you
expose the proxy and the bridge through any transport you like (a Cloudflare Tunnel
is the practical choice on BlackBerry, since the device cannot run a VPN), then
point the app at those public addresses with the bridge token checked. The full
walkthrough, including the token, the proxy, the tunnel, and an optional Cloudflare
Access layer, is in the bridge repo at [docs/REMOTE_ACCESS.md](https://github.com/cengizozel/SignalBerryBridge/blob/main/docs/REMOTE_ACCESS.md).

## Security and privacy

The whole point of this app is to use Signal without handing your messages to a
third party, so security gets real attention. See [docs/SECURITY.md](docs/SECURITY.md)
for the full picture. In short:

- **You host everything.** Your messages live on your signal-cli server and your
  bridge. There is no SignalBerry account, no analytics, and no telemetry.
- **Token authentication for remote use.** When the services are exposed to the
  internet, every request must carry a shared bearer token. The bridge enforces
  it in process, and a small bundled proxy enforces the same token in front of
  signal-cli-rest-api, which has no auth of its own. Without the token, requests
  are rejected before reaching your server.
- **Modern TLS on a 2013 device.** The BlackBerry runtime only negotiates TLS 1.0
  with old ciphers, which modern servers reject. The app bundles
  [Conscrypt](https://github.com/google/conscrypt) and installs it at startup, so
  it speaks TLS 1.3 with current certificates, verified on a real Q10.
- **On device hygiene.** Read receipts are off by default. `adb backup` is
  disabled so the message database cannot be pulled off the device that way.
  Logging out wipes the local database, attachments, caches, and keys.
- **Transport agnostic.** The token protects the services no matter how you expose
  them, so you are not locked into any one provider. Cloudflare Access is an
  optional extra edge layer, not a requirement.

A fair limitation to state plainly: the token is a shared secret embedded in the
app, so this is single user security, not per device identity. That is the right
fit for a phone you own. The data path on the device and to your own server is
yours end to end.

## Challenges worth documenting

Targeting a 2013 device through a sandboxed Android runtime produced a few
problems that shaped the code.

- **The font draws very little.** The BlackBerry 10 font renders Unicode 6.0 era
  emoji but not newer ones, and many plain symbols come out as blank boxes. UI
  glyphs are therefore either picked from the set the device actually has (checked
  against the device font) or drawn in code on a canvas, like the play and pause
  controls and the Note to Self notepad icon.
- **Old TLS.** Covered above. The fix was bundling Conscrypt rather than fighting
  the platform stack.
- **No VPN on the device.** The BlackBerry 10 Android runtime does not grant the
  VPN capability, so Tailscale, WireGuard, and similar cannot run on the phone.
  This is why an outbound tunnel from the server is the practical remote path.
- **No MediaPlayer speed control before API 23.** Voice note playback with a
  waveform and adjustable speed is a custom player built on MediaCodec and
  AudioTrack, with a WSOLA time stretcher so faster playback keeps the original
  pitch instead of sounding sped up.
- **Media formats.** The Q10 decoder is limited, so video and some image and
  audio formats may not play on device, with graceful fallbacks where possible.

## Building

Plain Java with AppCompat, no Compose and no Material library, so it stays light
on the runtime. minSdk 18, target and compile SDK 36, built with the Gradle
wrapper and the Android SDK.

```bash
./gradlew :app:assembleDebug      # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # runs the JVM unit tests
```

The pure logic that has historically caused subtle bugs has unit tests, covering
peer key resolution, the timestamp ladder, and the voice note time stretcher.

## Limitations

- "Delete for me" on your phone cannot propagate here. signal-cli (up to 0.14.5)
  does not expose the deleteForMe sync message at all, so there is nothing for the
  app to act on. "Delete for everyone" works in every direction, including groups.
- Voice and video calls are not supported.
- Stickers are not rendered.
- The bridge keeps its own copy of expired disappearing messages server side. The
  app honors expiry on device; bridge side expiry is a planned follow up.

## Credits

Built on [signal-cli](https://github.com/AsamK/signal-cli) by AsamK and the
[signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) image by
bbernhard. Conscrypt by Google. Thanks to the BlackBerry community for keeping
these devices alive.
