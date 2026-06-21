# OpenCode Remote — Relay

Relay server for [OpenCode Remote](https://github.com/fnc12/opencode/issues/10): control your
OpenCode sessions from the iOS app, even when your OpenCode server lives at home or on a VPS
behind NAT.

## How it works

```
 iOS app  ──HTTP/SSE──▶  relay  ◀──WebSocket (dial-out)──  connector ──HTTP──▶  OpenCode server
 (phone)               (public)                          (your machine)        (127.0.0.1:4096)
```

The OpenCode server is usually behind NAT, so the relay cannot dial *in*. Instead a small
**connector** runs next to the OpenCode server and dials *out* to the relay over a single
WebSocket. The relay multiplexes every iOS request — including long-lived SSE event streams —
over that one socket.

This package contains the **relay** (`cmd/relay`) and the **connector** (`cmd/connector`).
Provider-agnostic push (APNs + FCM) is tracked in [#3](https://github.com/fnc12/opencode/issues/3).

## Protocol

One WebSocket carries many concurrent logical requests ("streams"). Frames are length-prefixed
binary records (`internal/tunnel/frame.go`):

```
[Type:1][StreamID:8][Len:4][Payload:Len]   big-endian
```

A connector registers with `TypeRegister {tunnelId, token}` and gets `TypeRegisterAck`. The relay
opens a stream per HTTP request with `TypeRequest`; the connector replies with `TypeResponseHead`,
zero or more `TypeData` chunks (flushed immediately, so SSE passes through unbuffered), then
`TypeEnd` (or `TypeError`).

## Endpoints

| Method | Path           | Purpose                                            |
|--------|----------------|----------------------------------------------------|
| GET    | `/healthz`     | Liveness; reports live tunnel count                |
| GET    | `/connector`   | WebSocket; a connector registers a tunnel          |
| POST   | `/api/devices` | Register a device for push `{tunnelId, provider, token}` (`provider` ∈ `apns`/`fcm`) |
| DELETE | `/api/devices` | Unregister a device (same body)                    |
| *      | `/t/{id}/...`  | Proxied to the connector for tunnel `{id}`         |

## Push (session-finished notifications)

When APNs and/or FCM are configured, the relay subscribes to each tunnel's OpenCode
event stream (`/global/event`) and, on a `session.idle` / `session.status:idle` event, pushes a
notification to every device registered for that tunnel. Pushes are deduplicated per session.

Both senders are stdlib-only (no SDKs): APNs uses token-based `.p8` auth (ES256 JWT over HTTP/2);
FCM uses a service-account JSON (RS256 JWT → OAuth2 → FCM v1). Device registrations persist to a
JSON file (`RELAY_STORE_PATH`); swap `push.Store` for sqlite/postgres later behind the same interface.

| Env                    | Meaning                                                        |
|------------------------|----------------------------------------------------------------|
| `APNS_KEY_PATH`        | Path to the `.p8` auth key (enables APNs)                       |
| `APNS_KEY_ID`          | 10-char key id                                                 |
| `APNS_TEAM_ID`         | 10-char team id                                                |
| `APNS_TOPIC`           | App bundle id (`apns-topic`)                                    |
| `APNS_ENDPOINT`        | Override host (use the APNs sandbox host for dev builds)        |
| `FCM_SERVICE_ACCOUNT`  | Path to the Google service-account JSON (enables FCM)           |
| `RELAY_STORE_PATH`     | Device registry file (default `devices.json`)                  |

> Never commit `.p8` keys or service-account JSON (public repo). Mount them as files and point the env at them.

## Run

```sh
go run ./cmd/relay
```

| Env                    | Default | Meaning                                                              |
|------------------------|---------|---------------------------------------------------------------------|
| `RELAY_ADDR`           | `:8080` | Listen address                                                      |
| `RELAY_SHARED_SECRET`  | —       | If set, connector tokens must equal it. Empty = any non-empty token (dev only). Per-tunnel tokens land with #3. |

### Connector

Runs next to the OpenCode server and dials out to the relay:

```sh
RELAY_URL=ws://relay.example.com/connector \
TUNNEL_ID=my-machine \
TUNNEL_TOKEN=$RELAY_SHARED_SECRET \
go run ./cmd/connector
```

| Env                 | Default                  | Meaning                                                         |
|---------------------|--------------------------|----------------------------------------------------------------|
| `RELAY_URL`         | — (required)             | `ws(s)://` URL of the relay's `/connector` endpoint            |
| `TUNNEL_ID`         | — (required)             | Tunnel id this connector serves; clients reach it at `/t/{id}` |
| `TUNNEL_TOKEN`      | — (required)             | Auth token presented to the relay                              |
| `OPENCODE_URL`      | `http://127.0.0.1:4096`  | Local OpenCode server base URL                                 |
| `OPENCODE_PASSWORD` | —                        | Injected as Basic `admin:<pw>` when the client sent no `Authorization` |

The connector auto-reconnects with backoff, and cancels the upstream request
(e.g. an SSE `/event` stream) when the client disconnects.

> Never commit secrets: keep `TUNNEL_TOKEN` / `OPENCODE_PASSWORD` in the environment, not in the repo.

## Test

```sh
go test ./...
```
