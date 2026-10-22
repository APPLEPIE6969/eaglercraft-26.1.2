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
| S→C  | 0xFF  | kick      | `0xFF` `u16 n` `utf-8 reason (n B)` — followed by `close(1000)`              |

## Rules

1. The **first** frame within `--hs-timeout-ms` (default 10 000) must be the handshake;
   anything else → kick.
2. Handshake may only be sent once → duplicate → kick.
3. Username must be 1–16 chars after trim → otherwise kick.
4. Ping only allowed after handshake → otherwise kick.
5. Unknown packet ids → kick.
6. Text frames → kick (client protocol is binary-only).
7. Frames larger than 1 MiB (`0x100000`, matching the client's `MAX_INCOMING_SIZE`) →
   `close(1009)`.
8. More than `--rate` (default 500) binary frames within a sliding 60 s window → kick.
9. Connections beyond `--max-conn` (default 64) are kicked at open.

## Client brand

Sent by the client in the handshake; the placeholder server only logs it. The server
replies with `EaglerCraftX-26.1.2-Placeholder/1.0.0` plus its MOTD.

## Building & running

```bash
./gradlew :PlaceholderServer:shadowJar            # or build from repo root
java -jar gateway/PlaceholderServer/build/libs/PlaceholderServer-1.0.0.jar \
     --port 8081 --motd "hello" --verbose
```
