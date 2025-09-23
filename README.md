# SignalBerry (WIP)

> **Not ready for regular use.** Can send & receive messages via a local Signal REST API, but expect bugs and missing features.

## Features

* **Server Connect**: enter API host (e.g., `HOST:PORT`) and your Signal number; saved locally.
* **Contacts**: fetched from the Signal REST API.
* **Realtime Chat**: WebSocket receive (json-rpc mode), send via `/v2/send`.
* **Message Status**: 🕒 pending → ✓ sent → ✓✓ delivered/read.
* **UI**: bubble chat with RecyclerView; simple, fast, minimal.
* **Persistence**: per-chat history stored locally.

## Setup

### 1) Run the Signal REST API (Docker)

Use **one** of the following.

**docker-compose.yml**

```yaml
version: "3"
services:
  signal-api:
    image: bbernhard/signal-cli-rest-api:latest
    container_name: signal-api
    restart: always
    ports:
      - "5000:8080"              # map HOST:5000 -> container:8080
    environment:
      - MODE=json-rpc            # WebSocket mode (recommended)
      - LOG_LEVEL=debug          # optional
    volumes:
      - $HOME/.local/share/signal-api:/home/.local/share/signal-cli
```

Start it:

```bash
docker compose up -d
```

**Or** run directly:

```bash
docker run -d --name signal-api --restart=always \
  -p 5000:8080 \
  -v $HOME/.local/share/signal-api:/home/.local/share/signal-cli \
  -e MODE=json-rpc \
  -e LOG_LEVEL=debug \
  bbernhard/signal-cli-rest-api:latest
```

**Link the container as a device**

1. On the machine running Docker, open:
   `http://HOST:PORT/v1/qrcodelink?device_name=signal-api`
2. In Signal on your phone: **Settings → Linked devices → “+” → scan** the QR code.

### 2) Run the Android app

1. Open the project in Android Studio and run on a device on the same network as the Docker host.
2. On first launch:

    * Enter **API Host** as `HOST:PORT` (the address you exposed above).
    * Enter **your Signal phone number** (E.164 format).
    * Tap **Connect**, then choose a contact and start chatting.

## Notes

* Intended for **local development** only; no TLS or authentication.
* No background notifications; app must be open to receive.
* Attachments, groups, avatars, and rich features are not implemented yet.
