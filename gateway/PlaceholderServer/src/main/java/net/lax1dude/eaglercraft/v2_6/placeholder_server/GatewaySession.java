package net.lax1dude.eaglercraft.v2_6.placeholder_server;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EaglerCraft 26.1.2 gateway - one browser connection's protocol state
 * machine (docs/PROTOCOL.md): HANDSHAKE -&gt; (STATUS | LOGIN) -&gt;
 * CONFIGURATION -&gt; PLAY.
 *
 * <p>STATUS answers the pinned MOTD JSON + ping/pong. LOGIN validates
 * the username, acknowledges, and hands out the offline-mode UUID.
 * CONFIGURATION runs the vanilla sequence (brand plugin message,
 * registry data, feature flags, known packs, finish) and enters PLAY
 * on the client's ack. PLAY streams the world: the superflat + trees
 * {@link GatewayWorld} around the player, then block updates for every
 * dig/place, inventory slots for drops, keep-alives every 15s, and the
 * vanilla move-packet handling.</p>
 */
public class GatewaySession {

        private static final Logger LOG = LoggerFactory.getLogger(GatewaySession.class);

        // ==== states ====
        private static final int STATE_HANDSHAKE = 0;
        private static final int STATE_STATUS = 1;
        private static final int STATE_LOGIN = 2;
        private static final int STATE_CONFIGURATION = 3;
        private static final int STATE_PLAY = 4;

        // ==== handshake ====
        private static final int HS_HANDSHAKE = 0x00;
        public static final int PROTOCOL_VERSION = 775;

        // ==== status ====
        private static final int S_STATUS_REQUEST = 0x00;
        private static final int S_STATUS_RESPONSE = 0x00;
        private static final int S_PING = 0x01;
        private static final int S_PONG = 0x01;

        // ==== login ====
        private static final int L_HELLO = 0x00;
        private static final int L_PLUGIN_RESPONSE = 0x05;
        private static final int S_L_ACKNOWLEDGE = 0x01;
        private static final int S_L_SUCCESS = 0x02;
        private static final int S_L_DISCONNECT = 0x03;
        private static final int S_L_PLUGIN_REQUEST = 0x04;

        // ==== configuration ====
        private static final int SC_PLUGIN_MESSAGE = 0x00;
        private static final int SC_DISCONNECT = 0x01;
        private static final int SC_FINISH = 0x02;
        private static final int SC_KEEP_ALIVE = 0x03;
        private static final int SC_PING = 0x04;
        private static final int SC_REGISTRY_DATA = 0x06;
        private static final int SC_FEATURE_FLAGS = 0x07;
        private static final int SC_KNOWN_PACKS = 0x08;
        private static final int CC_CLIENT_INFORMATION = 0x00;
        private static final int CC_PLUGIN_MESSAGE = 0x01;
        private static final int CC_ACK_FINISH = 0x02;
        private static final int CC_KEEP_ALIVE = 0x03;
        private static final int CC_PONG = 0x04;
        private static final int CC_KNOWN_PACKS = 0x05;

        // ==== play ====
        private static final int P_TELEPORT_CONFIRM = 0x00;
        private static final int P_CHAT_MESSAGE = 0x07;
        private static final int P_KEEP_ALIVE = 0x11;
        private static final int P_MOVE_POS = 0x13;
        private static final int P_MOVE_POS_ROT = 0x14;
        private static final int P_MOVE_ROT = 0x15;
        private static final int P_MOVE_STATUS_ONLY = 0x16;
        private static final int P_PLAYER_ACTION = 0x1C;
        private static final int P_SET_CARRIED_ITEM = 0x22;
        private static final int P_SWING = 0x26;
        private static final int P_USE_ITEM_ON = 0x2A;
        private static final int P_BLOCK_UPDATE = 0x05;
        private static final int P_SET_CONTAINER_SLOT = 0x0E;
        private static final int P_UNLOAD_CHUNK = 0x1F;
        private static final int P_DISCONNECT = 0x1A;
        private static final int P_LOGIN = 0x2A;
        private static final int P_KEEP_ALIVE_S2C = 0x2C;
        private static final int P_CHUNK = 0x2E;
        private static final int P_PLAYER_POSITION = 0x38;
        private static final int P_SYSTEM_CHAT = 0x4F;

        // ==== player action statuses ====
        private static final int ACTION_START = 0;
        private static final int ACTION_ABORT = 1;
        private static final int ACTION_FINISH = 2;

        // ==== world/session constants (pinned in docs/PROTOCOL.md) ====
        public static final int VIEW_RADIUS = 4;   // chunks streamed ahead
        public static final int UNLOAD_RADIUS = 6; // chunks dropped behind
        public static final double MAX_INTERACT_RANGE = 7.0D; // block reach
        public static final long KEEP_ALIVE_INTERVAL_MS = 15_000;
        public static final long KEEP_ALIVE_TIMEOUT_MS = 30_000;

        private final PlaceholderServer server;
        private final WebSocket socket;
        private final GatewayWorld world;

        private int state = STATE_HANDSHAKE;
        private String username;
        private UUID playerUuid;
        private int nextEntityId = 1;

        // play state
        private double x, y, z;
        private float yaw, pitch;
        private boolean onGround = true;
        private int teleportId = 0;
        private int hotbarSlot = 0;
        private final int[] invIds = new int[36];
        private final int[] invCounts = new int[36];
        private long lastKeepAliveSent;
        private long lastKeepAliveResponse;
        private ScheduledFuture<?> keepAliveTask;
        private boolean closing;

        public GatewaySession(PlaceholderServer server, WebSocket socket,
                        GatewayWorld world) {
                this.server = server;
                this.socket = socket;
                this.world = world;
        }

        public String getUsername() {
                return this.username;
        }

        public boolean isPlaying() {
                return this.state == STATE_PLAY;
        }

        // ==================== inbound ====================

        /** Handles one binary frame (one packet) in the current state. */
        public void handle(ByteBuffer frame) {
            try {
                byte[] bytes = new byte[frame.remaining()];
                frame.get(bytes);
                GatewayBuffer in = GatewayBuffer.wrap(bytes);
                int packetId = in.readVarInt();
                switch (this.state) {
                    case STATE_HANDSHAKE:
                        handleHandshake(packetId, in);
                        break;
                    case STATE_STATUS:
                        handleStatus(packetId, in);
                        break;
                    case STATE_LOGIN:
                        handleLogin(packetId, in);
                        break;
                    case STATE_CONFIGURATION:
                        handleConfiguration(packetId, in);
                        break;
                    case STATE_PLAY:
                        handlePlay(packetId, in);
                        break;
                    default:
                        kick("protocol violation: unknown state");
                        break;
                }
            } catch (RuntimeException e) {
                LOG.info("malformed packet from {}: {}", socket.getRemoteSocketAddress(),
                                e.toString(), e);
                kick("protocol violation: malformed packet (" + e.getMessage() + ")");
            }
        }

        private void handleHandshake(int packetId, GatewayBuffer in) {
                if (packetId != HS_HANDSHAKE) {
                        kick("protocol violation: first packet must be the handshake (0x00)");
                        return;
                }
                int protocol = in.readVarInt();
                String addr = in.readUtf();
                int port = in.readShort() & 0xFFFF;
                int nextState = in.readVarInt();
                LOG.info("handshake from {}: protocol={} addr={}:{} next={}",
                                socket.getRemoteSocketAddress(), protocol, addr, port, nextState);
                if (protocol != PROTOCOL_VERSION) {
                        // Vanilla answers with a login disconnect carrying
                        // the outdated-client reason.
                        sendLoginDisconnect("{\"translate\":\"multiplayer.disconnect.outdated_client\","
                                        + "\"with\":[\"" + PROTOCOL_VERSION + "\"]}");
                        close();
                        return;
                }
                if (nextState == 1) {
                        this.state = STATE_STATUS;
                } else if (nextState == 2) {
                        this.state = STATE_LOGIN;
                } else {
                        kick("protocol violation: handshake next state " + nextState);
                }
        }

        private void handleStatus(int packetId, GatewayBuffer in) {
                if (packetId == S_STATUS_REQUEST) {
                        GatewayBuffer out = GatewayBuffer.create();
                        out.writeVarInt(S_STATUS_RESPONSE);
                        out.writeUtf(statusJson());
                        send(out);
                } else if (packetId == S_PING) {
                        long token = in.readLong();
                        GatewayBuffer out = GatewayBuffer.create();
                        out.writeVarInt(S_PONG);
                        out.writeLong(token);
                        send(out);
                        close(); // vanilla closes after the pong
                } else {
                        kick("protocol violation: unexpected status packet 0x"
                                        + Integer.toHexString(packetId));
                }
        }

        private String statusJson() {
                int online = server.playingCount();
                return "{\"version\":{\"name\":\"26.1.2\",\"protocol\":"
                                + PROTOCOL_VERSION + "},\"players\":{\"online\":"
                                + online + ",\"max\":" + server.getMaxConnections()
                                + "},\"description\":{\"text\":\""
                                + jsonEscape(server.getMotd()) + "\"}}";
        }

        private void handleLogin(int packetId, GatewayBuffer in) {
                if (packetId == L_HELLO) {
                        in.readLong(); // uuid msb (the client's offline uuid)
                        in.readLong(); // uuid lsb
                        String name = in.readUtf();
                        int props = in.readVarInt();
                        for (int i = 0; i < props; i++) {
                                in.readUtf();
                                in.readUtf();
                                if (in.readBoolean()) {
                                        in.readUtf();
                                }
                        }
                        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                                sendLoginDisconnect("{\"translate\":\"multiplayer.disconnect.invalid_username\"}");
                                close();
                                return;
                        }
                        this.username = name;
                        // Offline-mode UUID: md5 of "OfflinePlayer:<name>"
                        this.playerUuid = UUID.nameUUIDFromBytes(
                                        ("OfflinePlayer:" + name).getBytes());
                        if (!server.registerPlaying(this)) {
                                sendLoginDisconnect("{\"text\":\"You are already connected to this server\"}");
                                close();
                                return;
                        }
                        // Vanilla order: acknowledge, then success; both
                        // sides enter CONFIGURATION after the success.
                        GatewayBuffer ack = GatewayBuffer.create();
                        ack.writeVarInt(S_L_ACKNOWLEDGE);
                        send(ack);
                        GatewayBuffer out = GatewayBuffer.create();
                        out.writeVarInt(S_L_SUCCESS);
                        out.writeLong(playerUuid.getMostSignificantBits());
                        out.writeLong(playerUuid.getLeastSignificantBits());
                        out.writeUtf(name);
                        out.writeVarInt(0); // properties
                        send(out);
                        this.state = STATE_CONFIGURATION;
                        sendConfiguration();
                } else if (packetId == L_PLUGIN_RESPONSE) {
                        // unanswered requests get an empty response - ignore
                } else {
                        kick("protocol violation: unexpected login packet 0x"
                                        + Integer.toHexString(packetId));
                }
        }

        private void sendLoginDisconnect(String json) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(S_L_DISCONNECT);
                out.writeUtf(json);
                send(out);
        }

        /** The configuration burst (vanilla sequence, pinned layouts). */
        private void sendConfiguration() {
                // brand plugin message
                GatewayBuffer brand = GatewayBuffer.create();
                brand.writeVarInt(SC_PLUGIN_MESSAGE);
                brand.writeUtf("minecraft:brand");
                brand.writeUtf(server.getServerBrand());
                send(brand);
                // registry data: the gateway resolves built-in registries
                // client-side; one declared (empty) registry for parity
                GatewayBuffer registry = GatewayBuffer.create();
                registry.writeVarInt(SC_REGISTRY_DATA);
                registry.writeVarInt(1);
                registry.writeUtf("minecraft:worldgen/biome");
                registry.writeVarInt(1);
                registry.writeUtf("minecraft:plains");
                send(registry);
                // feature flags: none
                GatewayBuffer flags = GatewayBuffer.create();
                flags.writeVarInt(SC_FEATURE_FLAGS);
                flags.writeVarInt(0);
                send(flags);
                // known packs: none (the client ships assets in assets.epk)
                GatewayBuffer packs = GatewayBuffer.create();
                packs.writeVarInt(SC_KNOWN_PACKS);
                packs.writeVarInt(0);
                send(packs);
                // finish
                GatewayBuffer finish = GatewayBuffer.create();
                finish.writeVarInt(SC_FINISH);
                send(finish);
        }

        private void handleConfiguration(int packetId, GatewayBuffer in) {
                switch (packetId) {
                        case CC_CLIENT_INFORMATION:
                                in.readUtf();  // locale
                                in.readByte(); // view distance
                                in.readVarInt(); // chat mode
                                in.readBoolean(); // chat colors
                                in.readUByte(); // skin parts
                                in.readVarInt(); // main hand
                                if (in.readBoolean()) { } // text filtering
                                in.readBoolean(); // server listings
                                break;
                        case CC_PLUGIN_MESSAGE:
                                in.readUtf();
                                in.readRemaining();
                                break;
                        case CC_KEEP_ALIVE:
                                in.readLong();
                                break;
                        case CC_PONG:
                                in.readLong();
                                break;
                        case CC_KNOWN_PACKS:
                                int packs = in.readVarInt();
                                for (int i = 0; i < packs; i++) {
                                        in.readUtf();
                                        in.readUtf();
                                        in.readUtf();
                                }
                                break;
                        case CC_ACK_FINISH:
                                enterPlay();
                                break;
                        default:
                                kick("protocol violation: unexpected configuration packet 0x"
                                                + Integer.toHexString(packetId));
                                break;
                }
        }

        // ==================== play ====================

        private void enterPlay() {
                this.state = STATE_PLAY;
                this.x = 0.5D;
                this.y = GatewayWorld.FLAT_TOP_Y + 1.0D;
                this.z = 0.5D;

                // Play Login (pinned layout)
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_LOGIN);
                out.writeInt(this.nextEntityId);
                out.writeByte(0); // gamemode: survival
                out.writeUtf("minecraft:overworld");
                out.writeLong(world.getSeed());
                out.writeByte(2); // difficulty: normal
                out.writeBoolean(false); // hardcore
                out.writeUtf("flat"); // level type
                out.writeVarInt(VIEW_RADIUS);
                out.writeLong(1000L); // day time: dawn
                out.writeLong(0L); // game time
                out.writeDouble(this.x);
                out.writeDouble(this.y);
                out.writeDouble(this.z);
                send(out);

                // Teleport to the spawn (vanilla sends a position packet on
                // join so the client confirms with Teleport Confirm)
                sendPlayerPosition();

                // Stream the spawn radius
                streamChunks(true);

                // Welcome chat
                server.broadcastSystemChat("{\"text\":\"" + jsonEscape(username)
                                + " joined the game\"}");

                // Protocol keep-alive loop
                this.lastKeepAliveSent = System.currentTimeMillis();
                this.lastKeepAliveResponse = this.lastKeepAliveSent;
                this.keepAliveTask = server.scheduleKeepAlive(this);
                LOG.info("player {} entered play (spawn {} {} {})",
                                username, x, y, z);
        }

        private void sendPlayerPosition() {
                this.teleportId++;
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_PLAYER_POSITION);
                out.writeDouble(this.x);
                out.writeDouble(this.y);
                out.writeDouble(this.z);
                out.writeFloat(this.yaw);
                out.writeFloat(this.pitch);
                out.writeByte(0); // all absolute
                out.writeVarInt(this.teleportId);
                send(out);
        }

        private void handlePlay(int packetId, GatewayBuffer in) {
                if (server.isVerbose()) {
                        LOG.info("play packet 0x{} from {}",
                                        Integer.toHexString(packetId), username);
                }
                switch (packetId) {
                        case P_TELEPORT_CONFIRM:
                                in.readVarInt();
                                break;
                        case P_CHAT_MESSAGE: {
                                String message = in.readUtf();
                                if (message.length() > 256) {
                                        kick("chat message too long");
                                        return;
                                }
                                server.broadcastSystemChat("{\"text\":\"<"
                                                + jsonEscape(username) + "> "
                                                + jsonEscape(message) + "\"}");
                                break;
                        }
                        case P_KEEP_ALIVE:
                                long token = in.readLong();
                                this.lastKeepAliveResponse = System.currentTimeMillis();
                                LOG.debug("keep alive from {}: token {}", username, token);
                                break;
                        case P_MOVE_POS: {
                                double nx = in.readDouble();
                                double ny = in.readDouble();
                                double nz = in.readDouble();
                                in.readBoolean();
                                updatePosition(nx, ny, nz);
                                break;
                        }
                        case P_MOVE_POS_ROT: {
                                double nx = in.readDouble();
                                double ny = in.readDouble();
                                double nz = in.readDouble();
                                float nyaw = in.readFloat();
                                float npitch = in.readFloat();
                                in.readBoolean();
                                this.yaw = nyaw;
                                this.pitch = npitch;
                                updatePosition(nx, ny, nz);
                                break;
                        }
                        case P_MOVE_ROT: {
                                float nyaw = in.readFloat();
                                float npitch = in.readFloat();
                                in.readBoolean();
                                this.yaw = nyaw;
                                this.pitch = npitch;
                                break;
                        }
                        case P_MOVE_STATUS_ONLY:
                                in.readBoolean();
                                break;
                        case P_PLAYER_ACTION: {
                                int status = in.readVarInt();
                                long pos = in.readLong();
                                int face = in.readByte();
                                handlePlayerAction(status, pos, face);
                                break;
                        }
                        case P_SET_CARRIED_ITEM:
                                int slot = in.readShort();
                                if (slot >= 0 && slot < 9) {
                                        this.hotbarSlot = slot;
                                }
                                break;
                        case P_SWING:
                                in.readVarInt();
                                break;
                        case P_USE_ITEM_ON: {
                                long pos = in.readLong();
                                int face = in.readByte();
                                handleUseItemOn(pos, face);
                                break;
                        }
                        default:
                                kick("protocol violation: unexpected play packet 0x"
                                                + Integer.toHexString(packetId));
                                break;
                }
        }

        private void updatePosition(double nx, double ny, double nz) {
                double dx = nx - this.x;
                double dy = ny - this.y;
                double dz = nz - this.z;
                // Server sanity (vanilla "moved too quickly" is 100 blocks/tick)
                if (dx * dx + dy * dy + dz * dz > 10000.0D) {
                        LOG.info("rejected huge move from {}: {} -> {}", username,
                                        posString(this.x, this.y, this.z),
                                        posString(nx, ny, nz));
                        sendPlayerPosition(); // resync
                        return;
                }
                this.x = nx;
                this.y = ny;
                this.z = nz;
                streamChunks(false);
        }

        private static String posString(double x, double y, double z) {
                return "(" + (int) x + "," + (int) y + "," + (int) z + ")";
        }

        /** Streams chunks around the player (radius VIEW_RADIUS), unloads
         * columns past UNLOAD_RADIUS. Full stream after join, deltas after. */
        private void streamChunks(boolean full) {
                int pcx = (int) Math.floor(this.x) >> 4;
                int pcz = (int) Math.floor(this.z) >> 4;
                server.streamAround(this, pcx, pcz, full);
        }

        private void handlePlayerAction(int status, long pos, int face) {
                int bx = GatewayBuffer.decodePosX(pos);
                int by = GatewayBuffer.decodePosY(pos);
                int bz = GatewayBuffer.decodePosZ(pos);
                if (!withinRange(bx, by, bz)) {
                        return; // vanilla silently ignores out-of-range digs
                }
                int block = world.blockAt(bx, by, bz);
                if (status == ACTION_START || status == ACTION_FINISH) {
                        if (block == GatewayWorld.AIR) {
                                return;
                        }
                        if (block == GatewayWorld.BEDROCK) {
                                // unbreakable - no update, no drop
                                return;
                        }
                        if (status == ACTION_FINISH) {
                                world.setBlock(bx, by, bz, GatewayWorld.AIR);
                                server.broadcastBlockUpdate(bx, by, bz,
                                                GatewayWorld.AIR);
                                int drop = GatewayWorld.dropFor(block);
                                if (drop != GatewayWorld.AIR) {
                                        giveItem(drop, 1);
                                }
                                LOG.info("{} dug {} at {} {} {}", username,
                                                block, bx, by, bz);
                        }
                }
                // ACTION_ABORT: nothing to validate
        }

        private void handleUseItemOn(long pos, int face) {
                int tx = GatewayBuffer.decodePosX(pos);
                int ty = GatewayBuffer.decodePosY(pos);
                int tz = GatewayBuffer.decodePosZ(pos);
                if (!withinRange(tx, ty, tz)) {
                        return;
                }
                if (world.blockAt(tx, ty, tz) == GatewayWorld.AIR) {
                        return; // must click a block face
                }
                int nx = tx + (face == 5 ? 1 : face == 4 ? -1 : 0);
                int ny = ty + (face == 1 ? 1 : face == 0 ? -1 : 0);
                int nz = tz + (face == 3 ? 1 : face == 2 ? -1 : 0);
                if (world.blockAt(nx, ny, nz) != GatewayWorld.AIR) {
                        return;
                }
                int slot = this.hotbarSlot;
                int held = this.invIds[slot];
                if (held == GatewayWorld.AIR || this.invCounts[slot] <= 0) {
                        return; // nothing to place
                }
                // don't place inside any player
                if (server.overlapsAnyPlayer(nx, ny, nz)) {
                        return;
                }
                world.setBlock(nx, ny, nz, held);
                server.broadcastBlockUpdate(nx, ny, nz, held);
                this.invCounts[slot]--;
                if (this.invCounts[slot] <= 0) {
                        this.invIds[slot] = GatewayWorld.AIR;
                        this.invCounts[slot] = 0;
                }
                sendSetContainerSlot(slot, this.invIds[slot], this.invCounts[slot]);
                LOG.info("{} placed {} at {} {} {}", username, held, nx, ny, nz);
        }

        /** The vanilla reach test against the player's eye position. */
        private boolean withinRange(int bx, int by, int bz) {
                double ex = this.x;
                double ey = this.y + 1.62D;
                double ez = this.z;
                double dx = (bx + 0.5D) - ex;
                double dy = (by + 0.5D) - ey;
                double dz = (bz + 0.5D) - ez;
                return dx * dx + dy * dy + dz * dz
                                <= MAX_INTERACT_RANGE * MAX_INTERACT_RANGE;
        }

        /** Gives an item: stack into the hotbar first, then empty slots. */
        private void giveItem(int itemId, int count) {
                // stack pass (hotbar, then main)
                for (int pass = 0; pass < 2; pass++) {
                        int from = pass == 0 ? 0 : 9;
                        int to = pass == 0 ? 9 : 36;
                        for (int i = from; i < to; i++) {
                                if (invIds[i] == itemId && invCounts[i] > 0
                                                && invCounts[i] < 64) {
                                        int take = Math.min(count, 64 - invCounts[i]);
                                        invCounts[i] += take;
                                        count -= take;
                                        sendSetContainerSlot(i, invIds[i], invCounts[i]);
                                        if (count == 0) {
                                                return;
                                        }
                                }
                        }
                }
                // empty pass
                for (int pass = 0; pass < 2; pass++) {
                        int from = pass == 0 ? 0 : 9;
                        int to = pass == 0 ? 9 : 36;
                        for (int i = from; i < to; i++) {
                                if (invIds[i] == GatewayWorld.AIR || invCounts[i] <= 0) {
                                        int take = Math.min(count, 64);
                                        invIds[i] = itemId;
                                        invCounts[i] = take;
                                        count -= take;
                                        sendSetContainerSlot(i, invIds[i], invCounts[i]);
                                        if (count == 0) {
                                                return;
                                        }
                                }
                        }
                }
                // full inventory: vanilla drops the excess - skip
        }

        /** Set Container Slot (window 0 = player inventory). */
        public void sendSetContainerSlot(int slot, int itemId, int count) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_SET_CONTAINER_SLOT);
                out.writeByte(0); // window: player inventory
                out.writeShort(slot);
                out.writeVarInt(itemId);
                out.writeVarInt(count);
                send(out);
        }

        // ==================== server-driven ====================

        /** Sends one streamed column (called by the server's streamer). */
        public void sendChunk(int chunkX, int chunkZ) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_CHUNK);
                world.encodeChunk(chunkX, chunkZ, new GatewayBufferSink(out));
                send(out);
        }

        /** Adapter: GatewayWorld's encoder writes into a GatewayBuffer. */
        private static final class GatewayBufferSink implements GatewayWorld.ByteSink {
                private final GatewayBuffer out;

                GatewayBufferSink(GatewayBuffer out) {
                        this.out = out;
                }

                @Override
                public void writeInt(int v) {
                        out.writeInt(v);
                }

                @Override
                public void writeVarInt(int v) {
                        out.writeVarInt(v);
                }

                @Override
                public void writeByte(int v) {
                        out.writeByte(v);
                }

                @Override
                public void writeBytes(byte[] b) {
                        out.writeBytes(b);
                }
        }

        public void sendUnloadChunk(int chunkX, int chunkZ) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_UNLOAD_CHUNK);
                out.writeInt(chunkX);
                out.writeInt(chunkZ);
                send(out);
        }

        public void sendBlockUpdate(int x, int y, int z, int blockId) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_BLOCK_UPDATE);
                out.writeLong(GatewayBuffer.encodePos(x, y, z));
                out.writeVarInt(blockId);
                send(out);
        }

        public void sendSystemChat(String json) {
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_SYSTEM_CHAT);
                out.writeUtf(json);
                send(out);
        }

        /** The 15s protocol keep-alive; closes silent connections after 30s. */
        public void tickKeepAlive() {
                if (this.state != STATE_PLAY || this.closing) {
                        return;
                }
                long now = System.currentTimeMillis();
                if (now - this.lastKeepAliveResponse > KEEP_ALIVE_TIMEOUT_MS) {
                        LOG.info("keep-alive timeout: {}", username);
                        GatewayBuffer out = GatewayBuffer.create();
                        out.writeVarInt(P_DISCONNECT);
                        out.writeUtf("{\"translate\":\"disconnect.timeout\"}");
                        send(out);
                        close();
                        return;
                }
                GatewayBuffer out = GatewayBuffer.create();
                out.writeVarInt(P_KEEP_ALIVE_S2C);
                out.writeLong(now);
                send(out);
                this.lastKeepAliveSent = now;
        }

        /** The player box overlap test for placement (x,z full cube, y). */
        public boolean overlapsBlock(int bx, int by, int bz) {
                return this.x > bx - 0.3D && this.x < bx + 1.3D
                                && this.z > bz - 0.3D && this.z < bz + 1.3D
                                && this.y > by - 1.8D && this.y < by + 1.0D;
        }

        public double getX() {
                return this.x;
        }

        public double getY() {
                return this.y;
        }

        public double getZ() {
                return this.z;
        }

        // ==================== plumbing ====================

        private void send(GatewayBuffer buf) {
                if (this.closing || !this.socket.isOpen()) {
                        return;
                }
                this.socket.send(buf.toArray());
        }

        /** Kicks with the legacy 0xFF reason frame (pre-protocol errors). */
        private void kick(String reason) {
                LOG.info("kick {}: {}", socket.getRemoteSocketAddress(), reason);
                // The documented disconnect shapes carry the reason; the
                // plain close covers the pre-handshake garbage cases.
                close();
        }

        public void close() {
                if (this.closing) {
                        return;
                }
                this.closing = true;
                if (this.keepAliveTask != null) {
                        this.keepAliveTask.cancel(false);
                }
                if (this.state == STATE_PLAY) {
                        server.broadcastSystemChat("{\"text\":\""
                                        + jsonEscape(String.valueOf(username))
                                        + " left the game\"}");
                        server.unregisterPlaying(this);
                }
                if (this.socket.isOpen()) {
                        this.socket.close(1000, "bye");
                }
        }

        public static String jsonEscape(String s) {
                StringBuilder sb = new StringBuilder(s.length() + 8);
                for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        switch (c) {
                                case '"': sb.append("\\\""); break;
                                case '\\': sb.append("\\\\"); break;
                                case '\n': sb.append("\\n"); break;
                                case '\r': sb.append("\\r"); break;
                                case '\t': sb.append("\\t"); break;
                                default:
                                        if (c < 0x20) {
                                                sb.append(String.format("\\u%04x", (int) c));
                                        } else {
                                                sb.append(c);
                                        }
                        }
                }
                return sb.toString();
        }
}
