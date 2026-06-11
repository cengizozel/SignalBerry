# SignalBerry

A Signal messenger client for BlackBerry Android devices.

<p align="center">
  <img src="screenshots/bb-ml.jpg" width="45%" alt="Message list (light mode)"/>
  &nbsp;&nbsp;
  <img src="screenshots/bb-chat.jpg" width="45%" alt="Chat (dark mode)"/>
</p>

Built on top of [signal-cli](https://github.com/AsamK/signal-cli) via the [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api) Docker image, with a companion [SignalBerry Bridge](https://github.com/cengizozel/SignalBerryBridge) for offline message persistence.

## Architecture

```
Signal Network
     │
     ▼
signal-cli-rest-api  (Docker, port 5000)
     │  WebSocket /v1/receive
     ▼
SignalBerry Bridge   (Docker, port 9099)
     │  SQLite + mod_seq change feed (/v2/changes)
     ▼
SignalBerry Android  (single WebSocket + change-feed catch-up)
```

The app holds one WebSocket to signal-cli-rest-api for real-time receive and
sends over its REST API. The bridge persists every message — with reactions,
edits, deletes, quotes and receipt statuses — and serves a monotonic change
feed, so anything that happened while the app was closed (including reactions
to old messages and read receipts) is replayed exactly once on reconnect. The
app reports its own sends to the bridge, giving both sides a complete history.

## Features

- **Realtime messaging** — single WebSocket; change-feed catch-up replays anything missed offline, exactly once
- **Message status** — … pending → ✓ sent → ✓✓ delivered → ✓✓ read (blue); failed sends offer tap-to-retry
- **Replies, reactions, edits** — quote-reply, emoji reactions (add/remove), edit with history, all synced across devices
- **Delete** — delete for everyone (with "Message deleted" placeholders), local delete, per-conversation wipe
- **Disappearing messages** — expiry timers honored: messages are scrubbed after their timer runs out
- **Media** — images (zoom / save / share), video (thumbnails, tap-to-download, in-app player), audio and files; uploads stream with constant memory
- **Conversation list** — snippets, timestamps, unread badges, mute per chat
- **Search** — conversations by name, plus full message-history search inside a chat with match navigation
- **Unread handling** — jump-to-first-unread divider; badge clears only when you actually reach the bottom; jump-to-latest button with unseen counter
- **Hardware keyboard** — Enter sends, Alt/Enter inserts a newline (BlackBerry-first)
- **Notifications** — per-sender with counts, restart-on-boot, offline-gap catch-up notifications, mark-read on open
- **Privacy** — read receipts off by default, `adb backup` disabled, logout wipes all local data
- **Light / dark mode** — toggle in Settings

## Setup

You need two Docker containers running on a machine reachable from your Android device. The easiest way is to use the [SignalBerry Bridge](https://github.com/cengizozel/SignalBerryBridge) repo, which ships a `docker-compose.yml` that starts both.

### 1. Clone the bridge repo

```bash
git clone https://github.com/cengizozel/SignalBerryBridge
cd SignalBerryBridge
```

### 2. Set your Signal number

Create a `.env` file in the bridge directory:

```
SIGNAL_NUMBER=+12223334444
```

### 3. Start the stack

```bash
docker compose up -d --build
```

This starts:
- `signal-api` — signal-cli-rest-api on port `5000`
- `signal-bridge` — SignalBerry Bridge on port `9099`

### 4. Link your Signal account

Open this URL in a browser on the Docker host:

```
http://YOUR_HOST:5000/v1/qrcodelink?device_name=signal-api
```

On your phone: **Signal → Settings → Linked Devices → "+" → scan the QR code.**

Verify it worked:

```bash
curl http://YOUR_HOST:5000/v1/accounts
```

### 5. Connect the Android app

1. Open SignalBerry on your device.
2. Enter `YOUR_HOST:5000` as the **API Host** and your Signal number in E.164 format.
3. Tap **Connect** — your contacts will load and you can start chatting.

## Notes

- The device running Docker and the Android device must be on the same network (or the Docker ports must be reachable).
- No TLS or authentication — intended for local / trusted network use only.
- A background service handles real-time delivery and notifications when the app is not in the foreground.
- Groups, voice/video calls, and stickers are not supported (groups are planned).
- "Delete for me" on your phone cannot propagate here: signal-cli (≤0.14.5) does not expose the deleteForMe sync message. "Delete for everyone" works.
- The bridge retains its copy of expired disappearing messages server-side (app-side expiry is honored); bridge-side expiry is a planned follow-up.
