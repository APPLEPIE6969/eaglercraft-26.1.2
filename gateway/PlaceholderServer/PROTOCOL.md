# PlaceholderServer — Wire Protocol

Binary WebSocket frames only (client `PlatformNetworking` sets `binaryType = 'arraybuffer'`).
Multi-byte integers are **big-endian**; string length fields are `u16` followed by UTF-8 bytes.

## Frames

| Dir  | Id    | Name      | Layout                                                                       |
|------|-------|-----------|------------------------------------------------------------------------------|
| C→S  | 0x01  | handshake | `0x01` `u16 n` `client brand (n B)` `u16 m` `username (m B)`                 |
| S→C  | 0x01  | accept    | `0x01` `u16 n` `server brand (n B)` `u16 m` `motd (m B)`                     |
| C→S  | 0x02  | ping      | `0x02` + opaque payload (echoed verbatim)                                    |
| S→C  | 0x02  | pong      | `0x02` + same payload                                                        |
| C→S  | 0x03  | login     | `0x03` `u16 n` `username (n B)` `u16 m` `auth token (m B, may be empty)`     |
| S→C  | 0x04  | login result | `0x04` `u8 status` `u16 n` `message (n B)` — status: 0 ok, 1 invalid username, 2 name taken, 3 forwarding disabled, 4 rejected |
| C→S  | 0x05  | chat      | `0x05` `u16 n` `utf-8 text (n B)` — client → backend relay                   |
| S→C  | 0x05  | chat      | `0x05` `u16 n` `utf-8 text (n B)` — backend system chat relay                |
| S→C  | 0xFF  | kick      | `0xFF` `u16 n` `utf-8 reason (n B)` — followed by `close(1000)`              |

## Gateway milestone 2: player forwarding

The Bungee/Velocity gateways extend the placeholder behavior: after the
handshake, a client may send `0x03 login`. The gateway then

1. validates the username (`A-Za-z0-9_`, max 16 chars, case-insensitive
   uniqueness per gateway);
2. resolves the backend named by `forwarding_default_server` in the proxy's
   server registry (empty/disabled → result status 3);
3. creates a player session and performs a **real Minecraft-protocol login**
   over TCP towards the backend (handshake with next-state 2, Login Start,
   Set Compression, Login Success — protocol 775, offline-mode);
4. answers `0x04` with status 0 **only once the backend accepted the login**,
   so for the client "status 0" means "session + MC bridge established";
   if the backend is unreachable the client gets status 3 and the session is
   torn down;
5. relays play traffic:
   - backend keepalive is answered by the gateway (transparent to the client);
   - backend system chat (NBT text component) is re-encoded as `0x05` frames;
   - client `0x05` chat is forwarded to the backend as unsigned chat messages
     (chat arriving while the backend login is still in flight is buffered,
     up to 64 frames, and flushed once the bridge is live);
   - backend disconnects kick the session with the decoded reason.

The MC-protocol side lives in `.../placeholder_server/mc/` (pure JDK, shared
by both gateways): `McBridge` (client side into the backend), `McProtocolStub`
(a minimal backend for smoke tests), `McVarInt`, `McNbt`, `McPacketIds`.
Packet ids for the bridge are configurable per deployment (`mc_*` config keys)
because modern Minecraft shifts ids between releases; unknown server packets
are skipped via length framing, which is stable across versions.

## Rules

1. The **first** frame within `--hs-timeout-ms` (default 10 000) must be the handshake;
   anything else → kick.
2. Handshake may only be sent once → duplicate → kick.
3. Username must be 1–16 chars after trim → otherwise kick.
4. Ping only allowed after handshake → otherwise kick.
5. `0x03 login` only allowed after handshake and only once per connection;
   duplicates → kick.
6. `0x05 chat` only allowed after a successful `0x03 login` → otherwise kick.
7. Unknown packet ids → kick.
8. Text frames → kick (client protocol is binary-only).
9. Frames larger than 1 MiB (`0x100000`, matching the client's `MAX_INCOMING_SIZE`) →
   `close(1009)`.
10. More than `--rate` (default 500) binary frames within a sliding 60 s window → kick.
11. Connections beyond `--max-conn` (default 64) are kicked at open.

## Client brand

Sent by the client in the handshake; the placeholder server only logs it. The server
replies with `EaglerCraftX-26.1.2-Placeholder/1.0.0` plus its MOTD.

## Building & running

```bash
./gradlew :PlaceholderServer:shadowJar            # or build from repo root
java -jar gateway/PlaceholderServer/build/libs/PlaceholderServer-1.0.0.jar \
     --port 8081 --motd "hello" --verbose

# Minimal MC backend for gateway smoke tests (chat echo + keepalives):
java -cp gateway/PlaceholderServer/build/libs/PlaceholderServer-1.0.0.jar \
     net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McProtocolStub \
     --port 25577 --verbose

# Standalone gateway (same listener/session/bridge stack as the Bungee
# plugin, no proxy runtime needed) for browser-driven smoke tests:
java -cp gateway/EaglercraftXBungee/build/libs/EaglercraftXBungee-1.3.2.jar \
     net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee.GatewaySmokeMain \
     --port 8081 --server lobby=127.0.0.1:25577 --verbose
```
