# Protocol Documentation

> Minecraft 26.1.2 Protocol 775 + EaglerCraft WebSocket Extension Protocol Reference

---

## Table of Contents

- [Protocol Overview](#protocol-overview)
- [Handshake Flow](#handshake-flow)
- [Packet Reference](#packet-reference)
- [Data Components (Item Serialization)](#data-components-item-serialization)
- [Compression](#compression)
- [EaglerCraft WebSocket Extension Protocol](#eaglercraft-websocket-extension-protocol)
- [Rate Limiting](#rate-limiting)
- [Error Handling](#error-handling)

---

## Protocol Overview

### Protocol Version

| Field | Value |
|-------|-------|
| Protocol Version | 775 |
| Minecraft Version | 26.1.2 |
| Transport | WebSocket (binary frames) |
| Compression | permessage-deflate (WebSocket extension) |
| Byte Order | Big-endian (network byte order) |

### State Machine

The protocol operates as a state machine with four states:

```
┌─────────────┐    Handshake    ┌─────────────┐    Login Success    ┌────────────────┐
│   HANDSHAKE  │───────────────►│    LOGIN     │───────────────────►│ CONFIGURATION  │
└─────────────┘                 └─────────────┘                     └───────┬────────┘
                                                                            │
                                                                  Config Finish
                                                                  + Acknowledge
                                                                            │
                                                                            ▼
                                                                  ┌────────────────┐
                                                                  │      PLAY      │
                                                                  └────────────────┘
```

| State | Direction | Description |
|-------|-----------|-------------|
| HANDSHAKE | Client → Server | Initial handshake with protocol version and server address |
| LOGIN | Bidirectional | Authentication, encryption negotiation |
| CONFIGURATION | Bidirectional | Registry sync, feature flags, resource pack offers |
| PLAY | Bidirectional | Gameplay packets (movement, chunks, entities, etc.) |

### Data Types

| Type | Size | Description |
|------|------|-------------|
| `Boolean` | 1 byte | 0x00 = false, 0x01 = true |
| `Byte` | 1 byte | Signed 8-bit integer |
| `Unsigned Byte` | 1 byte | Unsigned 8-bit integer |
| `Short` | 2 bytes | Signed 16-bit integer (big-endian) |
| `Unsigned Short` | 2 bytes | Unsigned 16-bit integer (big-endian) |
| `Int` | 4 bytes | Signed 32-bit integer (big-endian) |
| `Long` | 8 bytes | Signed 64-bit integer (big-endian) |
| `Float` | 4 bytes | IEEE 754 single-precision (big-endian) |
| `Double` | 8 bytes | IEEE 754 double-precision (big-endian) |
| `String` | VarInt + UTF-8 | Length-prefixed UTF-8 string (max 131072 chars) |
| `VarInt` | 1-5 bytes | Variable-length signed 32-bit integer |
| `VarLong` | 1-10 bytes | Variable-length signed 64-bit integer |
| `Identifier` | String | Namespaced identifier (e.g., `minecraft:stone`) |
| `NBT` | Variable | Named Binary Tag compound |
| `Position` | 8 bytes | Long-encoded (x: 26 bits, y: 12 bits, z: 26 bits) |
| `UUID` | 16 bytes | 128-bit universally unique identifier |
| `BitSet` | VarInt + bytes | Fixed-length bit set |

### VarInt Encoding

```
Value Range      Encoding
─────────────────────────────
0-127            0xxxxxxx (1 byte)
128-16383        1xxxxxxx 0xxxxxxx (2 bytes)
16384-2097151    1xxxxxxx 1xxxxxxx 0xxxxxxx (3 bytes)
2097152-268435455  1xxxxxxx 1xxxxxxx 1xxxxxxx 0xxxxxxx (4 bytes)
268435456+       1xxxxxxx 1xxxxxxx 1xxxxxxx 1xxxxxxx xxxxxxxx (5 bytes)
```

Each byte uses 7 bits for data and 1 bit (MSB) as a continuation flag.

---

## Handshake Flow

### Step 1: Client Handshake

The client initiates the connection by sending a Handshake packet:

```
Client → Server: Handshake (0x00)
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ VarInt: 0x00 │ VarInt: 775  │ String: addr │ UShort: port │
│ (packet ID)  │ (protocol)   │ (server addr)│ (server port)│
└──────────────┴──────────────┴──────────────┴──────────────┘
                                               ┌──────────────┐
                                               │ VarInt: 1    │
                                               │ (next state) │
                                               └──────────────┘

Next State:
  1 = Status (server ping/MOTD)
  2 = Login
```

### Step 2: Login

```
Client → Server: Login Hello (0x00)
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ VarInt: 0x00 │ UUID: 128bit │ String: name │ VarInt: 0    │
│ (packet ID)  │ (player UUID)│ (username)   │ (properties) │
└──────────────┴──────────────┴──────────────┴──────────────┘

Server → Client: Login Success (0x02)
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ VarInt: 0x02 │ UUID: 128bit │ String: name │ VarInt: N    │
│ (packet ID)  │ (player UUID)│ (username)   │ (properties) │
└──────────────┴──────────────┴──────────────┴──────────────┘
  For each property:
  ┌──────────────┬──────────────┬──────────────┐
  │ String: key  │ String: val  │ Boolean: sig │
  └──────────────┴──────────────┴──────────────┘
    If signed:
    ┌──────────────┐
    │ String: sig  │
    └──────────────┘
```

### Step 3: Configuration Phase

After Login Success, the connection enters the Configuration state
(packet IDs per the tables below):

```
Server → Client: Plugin Message (Config 0x00)
  - Channel: "minecraft:brand"
  - Data: UTF-8 string (server brand name)

Server → Client: Registry Data (Config 0x06)
  - Dynamic registry entries (biomes, dimensions, etc.)

Server → Client: Feature Flags (Config 0x07)
  - Feature flag identifiers

Server → Client: Known Packs (Config 0x08)
  - Resource pack sync offers

Client → Server: Known Packs (Config 0x05)
  - Client acknowledges known packs

Server → Client: Finish Configuration (Config 0x02)
  - Signals end of configuration

Client → Server: Acknowledge Finish Configuration (Config 0x02)
  - Client acknowledges, transitions to Play
```

### Step 4: Play

```
Server → Client: Login (Play) (0x2C)
  - Entity ID, game mode, dimension, spawn position, etc.
  - This is the first Play packet after configuration
```

---

## Packet Reference

### Handshake State

| ID | Name | Direction | Description |
|----|------|-----------|-------------|
| 0x00 | Handshake | C→S | Protocol version, server address, next state |

### Status State

| ID | Name | Direction | Description |
|----|------|-----------|-------------|
| 0x00 | Status Request | C→S | Request server status |
| 0x00 | Status Response | S→C | Server MOTD, player count, version info |
| 0x01 | Ping Request | C→S | Latency measurement payload |
| 0x01 | Pong Response | S→C | Echo of ping payload |

### Login State

| ID | Name | Direction | Description |
|----|------|-----------|-------------|
| 0x00 | Login Hello | C→S | Username and UUID |
| 0x01 | Login Acknowledge | S→C | Server acknowledges login (→ Config) |
| 0x02 | Login Success | S→C | UUID, username, properties |
| 0x03 | Login Disconnect | S→C | Disconnect with reason |
| 0x04 | Login Plugin Request | S→C | Custom plugin message request |
| 0x05 | Login Plugin Response | C→S | Custom plugin message response |

### Configuration State (Server → Client)

| ID | Name | Description |
|----|------|-------------|
| 0x00 | Plugin Message | Custom data channel |
| 0x01 | Disconnect | Disconnect with reason |
| 0x02 | Finish Configuration | End of configuration |
| 0x03 | Keep Alive | Connection keepalive |
| 0x04 | Ping | Latency measurement |
| 0x05 | Reset Chat | Clear chat history |
| 0x06 | Registry Data | Dynamic registry entries |
| 0x07 | Feature Flags | Enabled feature flags |
| 0x08 | Known Packs | Resource pack sync |

### Configuration State (Client → Server)

| ID | Name | Description |
|----|------|-------------|
| 0x00 | Client Information | Locale, render distance, chat settings |
| 0x01 | Plugin Message | Custom data channel |
| 0x02 | Acknowledge Finish Configuration | Ready for play state |
| 0x03 | Keep Alive | Connection keepalive |
| 0x04 | Pong | Latency response |
| 0x05 | Known Packs | Client-known resource packs |

### Play State (Key Packets)

**Server → Client (selected):**

| ID | Name | Description |
|----|------|-------------|
| 0x00 | Bundle Item | Start/end of bundled packets |
| 0x01 | Spawn Entity | Create new entity |
| 0x02 | Spawn Experience Orb | Create XP orb |
| 0x03 | Entity Animation | Entity animation event |
| 0x04 | Award Stat | Statistic achievement |
| 0x05 | Block Update | Single block change |
| 0x06 | Block Entity Data | Block entity update |
| 0x07 | Block Destruction Stage | Block break animation |
| 0x0C | Close Container | Close inventory |
| 0x0D | Set Container Content | Full inventory sync |
| 0x0E | Set Container Slot | Single slot update |
| 0x14 | Plugin Message | Custom data channel |
| 0x1A | Disconnect | Kick with reason |
| 0x1B | Disguised Chat | System chat message (no header) |
| 0x1C | Entity Event | Entity status event |
| 0x1E | Explosion | Explosion with particles |
| 0x1F | Unload Chunk | Remove chunk from client |
| 0x20 | Game Event | Rain, win game, demo, etc. |
| 0x21 | Open Screen | Show container screen |
| 0x22 | Set Display Chat Preview | Chat preview toggle |
| 0x23 | Set Entity Link | Leash/passenger link |
| 0x24 | Set Equipment | Entity equipment update |
| 0x26 | Set Experience | Player XP bar update |
| 0x27 | Set Health | Player health & food update |
| 0x28 | Chunk Batch Finished | Batch of chunks complete |
| 0x2A | Login (Play) | Initial game state |
| 0x2C | Keep Alive | Connection keepalive |
| 0x2E | Level Chunk with Light | Chunk data + light data |
| 0x30 | Particle | Spawn particles |
| 0x31 | Player Info Update | Player list update |
| 0x38 | Player Position | Move player position |
| 0x3B | Remove Entities | Destroy entities |
| 0x3C | Remove Resource Pack | Remove resource pack |
| 0x3E | Respawn | Respawn / dimension change |
| 0x40 | Set Center Chunk | Center chunk for rendering |
| 0x4A | Sound | Play sound event |
| 0x4F | System Chat Message | Chat/system message |
| 0x55 | Update Advancements | Advancement updates |
| 0x57 | Update Recipes | Recipe book updates |
| 0x5C | Tab Complete | Tab completion results |
| 0x5E | Update Tags | Tag registry updates |

**Client → Server (selected):**

| ID | Name | Description |
|----|------|-------------|
| 0x00 | Teleport Confirm | Accept server position |
| 0x01 | Block Entity Tag Query | Query block entity NBT |
| 0x05 | Chat Command | Execute slash command |
| 0x06 | Chat Command Signed | Signed slash command |
| 0x07 | Chat Message | Send chat message |
| 0x08 | Chat Preview | Request chat preview |
| 0x09 | Client Command | Respawn, stats, etc. |
| 0x0A | Client Information | Settings update |
| 0x0B | Command Suggestion Request | Tab completion |
| 0x0C | Click Container Button | Container button click |
| 0x0D | Click Container | Container slot click |
| 0x0F | Interact | Interact with entity |
| 0x11 | Keep Alive | Connection keepalive |
| 0x12 | Lock Difficulty | Lock difficulty |
| 0x13 | Move Player Pos | Position update |
| 0x14 | Move Player Pos Rot | Position + rotation update |
| 0x15 | Move Player Rot | Rotation only update |
| 0x16 | Move Player Status Only | On ground flag |
| 0x17 | Move Vehicle | Vehicle position update |
| 0x18 | Paddle Boat | Boat paddle input |
| 0x19 | Pick Item | Pick block/item |
| 0x1A | Place Recipe | Place recipe in grid |
| 0x1B | Player Abilities | Ability flags |
| 0x1C | Player Action | Dig, drop, swap, etc. |
| 0x1E | Player Command | Start/stop sneaking, sprinting |
| 0x1F | Player Input | Vehicle input |
| 0x20 | Pong | Latency response |
| 0x22 | Set Carried Item | Held item slot |
| 0x23 | Set Creative Mode Slot | Creative inventory action |
| 0x26 | Swing | Arm swing animation |
| 0x2A | Use Item On | Use item on block |
| 0x2B | Use Item | Use item in air |

---

## Data Components (Item Serialization)

### Item Stack Format

Items are serialized using data components instead of NBT:

```
Item Stack:
┌──────────────────┐
│ VarInt: item ID   │  (0 = empty slot)
│                   │  If item ID != 0:
│ VarInt: count     │  (1-64 typically)
│                   │
│ Item Components:  │
│ ┌────────────────┐│
│ │ VarInt: num    ││  Number of present components
│ │                ││
│ │ For each:      ││
│ │ ┌──────────────┤│
│ │ │VarInt:typeID ││  Component type identifier
│ │ │Bytes: data   ││  Codec-encoded component data
│ │ └──────────────┤│
│ └────────────────┘│
│                   │
│ Removed Components│  (for template overrides)
│ ┌────────────────┐│
│ │ VarInt: num    ││  Number of removed components
│ │                ││
│ │ For each:      ││
│ │ ┌──────────────┤│
│ │ │VarInt:typeID ││  Component type to remove
│ │ └──────────────┤│
│ └────────────────┘│
└──────────────────┘
```

### Common Data Component Types

| Type ID | Name | Codec | Description |
|---------|------|-------|-------------|
| 0 | `minecraft:custom_name` | Component (JSON) | Custom display name |
| 1 | `minecraft:item_name` | Component (JSON) | Item name override |
| 2 | `minecraft:lore` | List[Component] | Tooltip lore lines |
| 3 | `minecraft:enchantments` | Compound | Applied enchantments |
| 4 | `minecraft:custom_data` | NBT Compound | Custom NBT data |
| 5 | `minecraft:repair_cost` | VarInt | Anvil repair cost |
| 6 | `minecraft:attribute_modifiers` | Compound | Attribute modifiers |
| 7 | `minecraft:unbreakable` | Boolean + Boolean | Unbreakable flag + show |
| 8 | `minecraft:hide_tooltip` | Unit | Hide tooltip flag |
| 9 | `minecraft:hide_additional_tooltip` | Unit | Hide extra tooltip |
| 10 | `minecraft:hide_dyed_bar` | Unit | Hide dye bar |
| 11 | `minecraft:damage` | Int | Current damage value |
| 12 | `minecraft:max_damage` | Int | Maximum damage |
| 13 | `minecraft:fire_resistant` | Unit | Fire resistant flag |
| 14 | `minecraft:trim` | Compound | Armor trim |
| 15 | `minecraft:dyed_color` | Int + Boolean | Dye color + show |
| 16 | `minecraft:map_color` | Int | Map color |
| 17 | `minecraft:map_id` | VarInt | Map ID reference |
| 18 | `minecraft:writable_book_content` | Compound | Book pages (editable) |
| 19 | `minecraft:written_book_content` | Compound | Book pages (locked) |
| 20 | `minecraft:potion_contents` | Compound | Potion effects |
| 21 | `minecraft:suspicious_stew_effects` | List | Suspicious stew effects |
| 22 | `minecraft:banner_patterns` | List | Banner pattern layers |
| 23 | `minecraft:bundle_contents` | List[ItemStack] | Bundle stored items |
| 24 | `minecraft:jukebox_playable` | Compound | Jukebox song |
| 25 | `minecraft:food` | Compound | Food properties |
| 26 | `minecraft:tool` | Compound | Tool properties |
| 27 | `minecraft:container` | List[ItemStack] | Container items |
| 28 | `minecraft:consumable` | Compound | Consumable properties |

---

## Compression

### permessage-deflate

EaglercraftX uses the WebSocket `permessage-deflate` extension for compression instead of the vanilla MC zlib compression.

**WebSocket Upgrade Negotiation:**

```http
GET /websocket HTTP/1.1
Host: play.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover
```

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover
```

**Configuration Parameters:**

| Parameter | Default | Recommended | Description |
|-----------|---------|-------------|-------------|
| `server_no_context_takeover` | false | true | Server resets LZ77 context each message |
| `client_no_context_takeover` | false | true | Client resets LZ77 context each message |
| `server_max_window_bits` | 15 | 15 | Server LZ77 window size |
| `client_max_window_bits` | 15 | 15 | Client LZ77 window size |

**Why permessage-deflate over vanilla zlib:**

1. **Shared context** — The deflate context is maintained across frames, improving compression ratios
2. **Lower overhead** — No per-packet zlib headers (2 bytes each)
3. **Hardware acceleration** — Modern browsers use hardware-accelerated zlib
4. **Browser-native** — No need to implement zlib in JavaScript

---

## EaglerCraft WebSocket Extension Protocol

### Overview

EaglerCraft extends the standard Minecraft protocol with custom WebSocket subprotocol messages for browser-specific features.

### Subprotocol Identification

The WebSocket connection uses the `binary` subprotocol:

```javascript
const ws = new WebSocket("wss://play.example.com/websocket", "binary");
ws.binaryType = "arraybuffer";
```

### Extension Packet Format

EaglerCraft extension packets are sent as regular Minecraft plugin messages on channel `EAG|1.8` (legacy) or `eagler:1.8` (modern, but naming is retained for compatibility).

**Extension packet header:**

```
┌──────────────┬──────────────┐
│ String:      │ VarInt:      │
│ "eagler:1.8" │ sub-pkt ID   │
│ (channel)    │              │
└──────────────┴──────────────┘
```

### Extension Packet Types

| Sub-ID | Name | Direction | Description |
|--------|------|-----------|-------------|
| 0x01 | Skin Upload | C→S | Upload custom skin image |
| 0x02 | Cape Upload | C→S | Upload custom cape image |
| 0x03 | Voice Signal | C→S | Voice chat signal/ICE candidate |
| 0x04 | Voice Signal | S→C | Voice chat signal/SDP answer |
| 0x05 | Server Info | S→C | Server brand, version, MOTD |
| 0x06 | Client Version | C→S | Client version string |
| 0x07 | Update Certs | S→C | Update authentication certificates |
| 0x08 | Set Skin URL | S→C | Set another player's skin URL |
| 0x09 | Set Cape URL | S→C | Set another player's cape URL |

### Skin Upload Packet (0x01)

```
Client → Server:
┌──────────────┬──────────────┬──────────────┬──────────────┐
│ String:      │ VarInt: 0x01 │ VarInt:      │ Byte[]:      │
│ "eagler:1.8" │ (sub ID)     │ skin length  │ skin data    │
│              │              │ (bytes)      │ (PNG bytes)  │
└──────────────┴──────────────┴──────────────┴──────────────┘
                                              ┌──────────────┐
                                              │ VarInt:      │
                                              │ model type   │
                                              │ (0=classic,  │
                                              │  1=slim)     │
                                              └──────────────┘
```

Skin image constraints:
- Format: PNG
- Size: 64×64 (classic) or 64×64 (slim)
- Max file size: 131072 bytes (128 KB)
- Must be valid RGBA PNG

### Server Info Packet (0x05)

```
Server → Client:
┌──────────────┬──────────────┬──────────────┐
│ String:      │ VarInt: 0x05 │ String:      │
│ "eagler:1.8" │ (sub ID)     │ brand name   │
│              │              │              │
└──────────────┴──────────────┴──────────────┘
                              ┌──────────────┐
                              │ String:      │
                              │ version      │
                              └──────────────┘
                              ┌──────────────┐
                              │ String:      │
                              │ MOTD (JSON)  │
                              └──────────────┘
```

---

## Rate Limiting

### Client-Side Rate Limiting

The EaglercraftX client implements rate limiting to prevent flooding the server:

```javascript
eaglercraftXOpts.network.rateLimit = {
    packetsPerSecond: 500,     // Max outbound packets/sec
    bytesPerSecond: 1048576,   // Max outbound bytes/sec (1MB)
    burstAllowance: 50         // Extra packets allowed in burst
};
```

### Server-Side Rate Limiting

The EaglercraftXBungee/Velocity proxy enforces:

| Rate Limit | Default | Description |
|------------|---------|-------------|
| Login rate | 5/min/IP | Max login attempts per minute per IP |
| Connection rate | 10/min/IP | Max new connections per minute per IP |
| Packet rate | 500/sec/conn | Max packets per second per connection |
| Chat rate | 10/sec/conn | Max chat messages per second |
| Movement rate | 40/sec/conn | Max movement packets per second |
| Skin upload rate | 1/min/conn | Max skin uploads per minute |

Violations result in:
1. **First offense:** Warning + temporary rate limit (1 second)
2. **Second offense:** Connection throttled for 10 seconds
3. **Third offense:** Connection kicked with reason "Rate limit exceeded"

---

## Error Handling

### Disconnect Reasons

The server may disconnect the client at any state with a JSON Component reason:

```json
{
    "translate": "disconnect.kicked",
    "with": ["Rate limit exceeded"]
}
```

Common disconnect reasons:

| Reason | State | Description |
|--------|-------|-------------|
| `disconnect.lost` | Any | Connection lost |
| `disconnect.kicked` | Play | Kicked by operator or plugin |
| `disconnect.timeout` | Any | Keep-alive timeout |
| `disconnect.closed` | Any | Server shutting down |
| `disconnect.genericReason` | Any | Generic error |
| `multiplayer.disconnect.invalid_player_data` | Login | Invalid player data |
| `multiplayer.disconnect.invalid_signature` | Login | Chat signature verification failed |
| `multiplayer.disconnect.out_of_order_chat` | Play | Out-of-order chat packets |
| `multiplayer.disconnect.banned` | Login | Player is banned |
| `multiplayer.disconnect.kicked` | Play | Kicked from server |

### Auto-Reconnect

The client automatically attempts to reconnect after disconnection:

```
Disconnect Detected
    │
    ▼
Wait 3 seconds (initial delay)
    │
    ▼
Attempt Reconnect ──────► Success ────► Resume Game
    │
    ▼ Failed
Double delay (6s)
    │
    ▼
Attempt Reconnect ──────► Success ────► Resume Game
    │
    ▼ Failed
Double delay (12s)
    │
    ▼
... (max delay: 30 seconds)
    │
    ▼
After 10 failed attempts ────► Show "Connection Lost" screen
```

### Keep-Alive Mechanism

Both client and server send Keep Alive packets at regular intervals:

- **Server → Client:** Every 15 seconds
- **Client → Server:** Must respond within 30 seconds
- **Timeout:** If no response after 30 seconds, server disconnects the client

```
Server                          Client
  │                               │
  │── Keep Alive (0x2C) ────────►│  random Long payload
  │◄── Keep Alive (0x11) ───────│  same Long payload
  │                               │
  │  (15 seconds later)           │
  │                               │
  │── Keep Alive (0x2C) ────────►│
  │◄── Keep Alive (0x11) ───────│
```

---

## Gateway Field Reference (26.1.2, pinned)

> The exact field layouts the 26.1.2 browser client and the Java
> gateway (`gateway/PlaceholderServer`) both implement. Where a
> packet above is only named, this section pins its bytes. Anything
> not listed here is not used by the 26.1.2 gateway. Reference
> implementations: the client's `net.minecraft.network` package and
> the gateway's `net.lax1dude.eaglercraft.v2_6.placeholder_server`
> package - the two must stay byte-compatible.

### Conventions

| Rule | Value |
|------|-------|
| Frame | one WebSocket binary message = one packet: `[VarInt packetId][payload]` |
| Byte order | big-endian (network order) |
| Strings | `VarInt` UTF-8 **byte** length + UTF-8 bytes, max 131072 chars |
| Position | 8-byte Long: x 26 bits, z 26 bits, y 12 bits (`((x&0x3FFFFFF)<<38) \| ((z&0x3FFFFFF)<<12) \| (y&0xFFF)`) |
| Compression | permessage-deflate is **optional**; servers without it answer with plain frames (the browser falls back automatically) |
| UUID | two Longs: most-significant first |

### Handshake (C→S 0x00)

| Field | Type | Notes |
|-------|------|-------|
| protocol | VarInt | `775` for 26.1.2 |
| addr | String | hostname (no scheme) |
| port | UShort | explicit port, else 80 (ws://) / 443 (wss://) |
| next state | VarInt | 1 = Status, 2 = Login |

A mismatched protocol version is answered with Login Disconnect
`{"translate":"multiplayer.disconnect.outdated_client","with":["775"]}`.

### Status Response (S→C 0x00)

A UTF-8 JSON object with the vanilla shape:
`{"version":{"name":"26.1.2","protocol":775},"players":{"online":n,"max":m},"description":{"text":"MOTD"}}`.
Ping (C→S 0x01) / Pong (S→C 0x01) carry one Long, echoed verbatim;
the server closes after the pong.

### Login

| Packet | Fields |
|--------|--------|
| Login Hello (C→S 0x00) | UUID, String name, VarInt property count (=0; each property: key, value, has-sig, [sig]) |
| Login Acknowledge (S→C 0x01) | empty - informational, sent before Login Success |
| Login Success (S→C 0x02) | UUID, String name, VarInt property count. Both sides enter CONFIGURATION after this packet |
| Login Disconnect (S→C 0x03) | String JSON chat component |

Usernames must match `[A-Za-z0-9_]{1,16}`. The gateway issues the
offline-mode UUID (`nameUUIDFromBytes("OfflinePlayer:<name>")`).

### Configuration

| Packet | Fields |
|--------|--------|
| Client Information (C→S 0x00) | String locale, Byte view distance, VarInt chat mode, Boolean chat colors, UByte skin parts, VarInt main hand, Boolean text filtering, Boolean allow server listings |
| Plugin Message (both, 0x00 / C 0x01) | String channel + raw bytes. `minecraft:brand` payload = String brand |
| Registry Data (S→C 0x06) | VarInt registry count; per registry: String id, VarInt entry count, per entry: String id |
| Feature Flags (S→C 0x07) | VarInt count + String flags |
| Known Packs (S→C 0x08 / C→S 0x05) | VarInt count; per pack: String namespace, String id, String version |
| Keep Alive (S 0x03 / C 0x03) | Long token, echoed |
| Ping / Pong (S 0x04 / C 0x04) | Long token, echoed |
| Finish Configuration (S→C 0x02) | empty |
| Acknowledge Finish Configuration (C→S 0x02) | empty - both sides enter PLAY |

### Play - server → client

| Packet | Fields |
|--------|--------|
| Login (Play) (0x2A) | Int entity id, UByte game mode (0 survival, 1 creative), String dimension (`minecraft:overworld`), Long hashed seed, UByte difficulty, Boolean hardcore, String level type (`flat`), VarInt view distance, Long day time, Long game time, Double spawn x/y/z |
| Level Chunk with Light (0x2E) | Int chunk x, Int chunk z, VarInt section count; per section: Byte **absolute section y** (`(y+64)>>4`), VarInt palette length, VarInt palette ids (client runtime ids; index 0 is air), then **4096 bytes** - one palette index per voxel in YZX order `i = ((y&15)<<8) \| ((z&15)<<4) \| (x&15)` |
| Block Update (0x05) | Position, VarInt block id |
| Set Container Slot (0x0E) | UByte window (0 = player inventory), Short slot (0-8 hotbar, 9-35 main), VarInt item id (client id space), VarInt count |
| Unload Chunk (0x1F) | Int chunk x, Int chunk z |
| Player Position (0x38) | Double x/y/z, Float yaw/pitch, Byte flags (bit0 +x, bit1 +y, bit2 +z, bit3 +yaw, bit4 +pitch relative), VarInt teleport id. Client confirms with Teleport Confirm (C→S 0x00, VarInt id) |
| Keep Alive (0x2C) | Long token; client echoes on C→S 0x11. Server sends every 15s, times out after 30s |
| System Chat (0x4F) | String JSON chat component (gateway sends `{"text": ...}`) |
| Disconnect (0x1A) | String JSON chat component |

### Play - client → server

| Packet | Fields |
|--------|--------|
| Teleport Confirm (0x00) | VarInt teleport id |
| Chat Message (0x07) | String (max 256 chars) |
| Keep Alive (0x11) | Long token (echo) |
| Move Player Pos (0x13) | Double x/y/z, Boolean on ground |
| Move Player Pos Rot (0x14) | Double x/y/z, Float yaw/pitch, Boolean on ground |
| Move Player Rot (0x15) | Float yaw/pitch, Boolean on ground |
| Move Player Status Only (0x16) | Boolean on ground |
| Player Action (0x1C) | VarInt status (0 start, 1 abort, 2 finish digging), Position, Byte face |
| Set Carried Item (0x22) | Short slot (0-8) |
| Swing (0x26) | VarInt hand (0 = main) |
| Use Item On (0x2A) | Position (the clicked block), Byte face |

Block faces use the vanilla 3D ids: 0 down (-Y), 1 up (+Y), 2 north
(-Z), 3 south (+Z), 4 west (-X), 5 east (+X). Use Item On targets the
face's neighbor cube.

### Movement rule

The client sends exactly one movement packet per tick following the
vanilla choice rule: Pos Rot when moved and turned, Pos when moved
only, Rot when turned only, Status Only when only the on-ground flag
changed, nothing when nothing changed (a full Pos Rot always follows
a teleport snap).

### The 26.1.2 gateway world

| Constant | Value |
|----------|-------|
| Base terrain | vanilla superflat preset: bedrock y=-64, dirt y=-63/-62, grass y=-61, air above |
| Trees | deterministic oak ring (trunks at fixed offsets from spawn, 4 logs + vanilla leaf shape) |
| Spawn | (0.5, -60, 0.5) - feet on the grass layer |
| View distance | 4 chunks (streamed on join + on chunk-boundary crossings) |
| Unload distance | 6 chunks |
| Reach | 7 blocks (eye position to block center, vanilla dig/place validation) |
| Drops | grass → dirt, stone → cobblestone, leaves → nothing, others → themselves |
| Block ids | the client's runtime id space verbatim (`Blocks.java`); the id table lives there |

Block digging is server-authoritative: the client's START/ABORT/FINISH
Player Actions drive it, the world change comes back as a Block Update
and the drop as a Set Container Slot. Placement is Use Item On; the
server validates reach, target face, empty destination and player
overlap, places, and syncs the carried slot.
