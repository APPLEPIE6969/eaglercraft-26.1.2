package net.minecraft.network;

import java.util.ArrayDeque;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 - the client half of the real gateway protocol
 * (docs/PROTOCOL.md, "Minecraft 26.1.2 Protocol 775").
 *
 * <p>One WebSocket binary frame is one packet:
 * {@code [VarInt packetId][payload]}. The connection walks the vanilla
 * state machine</p>
 *
 * <pre>HANDSHAKE -&gt; LOGIN -&gt; CONFIGURATION -&gt; PLAY   (or HANDSHAKE -&gt; STATUS)</pre>
 *
 * <p>driven by the packets pinned in the PROTOCOL.md field reference.
 * Transport-level packets (config/play keep-alive, config ping) are
 * answered here without bothering the listener; everything else is
 * forwarded to {@link Listener#packetReceived} with the current state
 * so the login/config/play handlers live next to their game logic.</p>
 *
 * <p>Incoming frames are queued by the WebSocket callback and drained
 * by {@link #tick()} from the game loop, so packet handling always
 * runs on the game thread and can safely touch the world.</p>
 */
public class Connection {

        // ========== Protocol states ==========
        public static final int STATE_HANDSHAKE = 0;
        public static final int STATE_STATUS = 1;
        public static final int STATE_LOGIN = 2;
        public static final int STATE_CONFIGURATION = 3;
        public static final int STATE_PLAY = 4;

        // ========== Handshake (C→S 0x00) ==========
        /** The 26.1.2 protocol version sent in the handshake. */
        public static final int PROTOCOL_VERSION = 775;

        // ========== Status state ==========
        public static final int S2C_STATUS_RESPONSE = 0x00;
        public static final int S2C_STATUS_PONG = 0x01;
        public static final int C2S_STATUS_REQUEST = 0x00;
        public static final int C2S_STATUS_PING = 0x01;

        // ========== Login state ==========
        public static final int C2S_LOGIN_HELLO = 0x00;
        public static final int C2S_LOGIN_PLUGIN_RESPONSE = 0x05;
        public static final int S2C_LOGIN_ACKNOWLEDGE = 0x01;
        public static final int S2C_LOGIN_SUCCESS = 0x02;
        public static final int S2C_LOGIN_DISCONNECT = 0x03;
        public static final int S2C_LOGIN_PLUGIN_REQUEST = 0x04;

        // ========== Configuration state ==========
        public static final int S2C_CONFIG_PLUGIN_MESSAGE = 0x00;
        public static final int S2C_CONFIG_DISCONNECT = 0x01;
        public static final int S2C_CONFIG_FINISH = 0x02;
        public static final int S2C_CONFIG_KEEP_ALIVE = 0x03;
        public static final int S2C_CONFIG_PING = 0x04;
        public static final int S2C_CONFIG_REGISTRY_DATA = 0x06;
        public static final int S2C_CONFIG_FEATURE_FLAGS = 0x07;
        public static final int S2C_CONFIG_KNOWN_PACKS = 0x08;
        public static final int C2S_CONFIG_CLIENT_INFORMATION = 0x00;
        public static final int C2S_CONFIG_PLUGIN_MESSAGE = 0x01;
        public static final int C2S_CONFIG_ACK_FINISH = 0x02;
        public static final int C2S_CONFIG_KEEP_ALIVE = 0x03;
        public static final int C2S_CONFIG_PONG = 0x04;
        public static final int C2S_CONFIG_KNOWN_PACKS = 0x05;

        // ========== Play state ==========
        public static final int S2C_PLAY_BLOCK_UPDATE = 0x05;
        public static final int S2C_PLAY_SET_CONTAINER_SLOT = 0x0E;
        public static final int S2C_PLAY_UNLOAD_CHUNK = 0x1F;
        public static final int S2C_PLAY_DISCONNECT = 0x1A;
        public static final int S2C_PLAY_KEEP_ALIVE = 0x2C;
        public static final int S2C_PLAY_LOGIN = 0x2A;
        public static final int S2C_PLAY_CHUNK = 0x2E;
        public static final int S2C_PLAY_PLAYER_POSITION = 0x38;
        public static final int S2C_PLAY_SYSTEM_CHAT = 0x4F;
        public static final int C2S_PLAY_TELEPORT_CONFIRM = 0x00;
        public static final int C2S_PLAY_CHAT_MESSAGE = 0x07;
        public static final int C2S_PLAY_KEEP_ALIVE = 0x11;
        public static final int C2S_PLAY_MOVE_POS = 0x13;
        public static final int C2S_PLAY_MOVE_POS_ROT = 0x14;
        public static final int C2S_PLAY_MOVE_ROT = 0x15;
        public static final int C2S_PLAY_MOVE_STATUS_ONLY = 0x16;
        public static final int C2S_PLAY_PLAYER_ACTION = 0x1C;
        public static final int C2S_PLAY_SET_CARRIED_ITEM = 0x22;
        public static final int C2S_PLAY_SWING = 0x26;
        public static final int C2S_PLAY_USE_ITEM_ON = 0x2A;

        /** Player Action statuses (C→S 0x1C, PROTOCOL.md field reference). */
        public static final int ACTION_START_DIGGING = 0;
        public static final int ACTION_ABORT_DIGGING = 1;
        public static final int ACTION_FINISH_DIGGING = 2;

        /** Block faces (vanilla Direction 3D ids: 0=-Y .. 5=+X). */
        public static final int FACE_DOWN = 0;
        public static final int FACE_UP = 1;
        public static final int FACE_NORTH = 2;
        public static final int FACE_SOUTH = 3;
        public static final int FACE_WEST = 4;
        public static final int FACE_EAST = 5;

        /** The protocol listener: gameplay-level packet handling. */
        public interface Listener {
                void packetReceived(int state, int packetId, PacketBuffer payload);
        }

        private int state = STATE_HANDSHAKE;
        private Listener listener;
        private boolean socketOwned;
        private boolean disconnected;
        private String disconnectReason;

        /** Frames received from the WS callback, drained on the game thread. */
        private final ArrayDeque<byte[]> inbound = new ArrayDeque<byte[]>();

        /** Outbound frames queued while the socket is still opening; the
         * handshake/login burst is written the moment onopen lands. */
        private final ArrayDeque<byte[]> outboundQueue = new ArrayDeque<byte[]>();
        private boolean socketOpen;

        private Connection() {
        }

        // ========== Join flow ==========

        /**
         * Opens the real protocol join: WebSocket connect, handshake with
         * next state LOGIN, then Login Hello. Progress reaches the
         * listener as packets (login success → configuration → play);
         * transport errors surface through {@link #pollDisconnect()}.
         *
         * @param uri    the WebSocket URI (ws:// or wss://)
         * @param host   the server address written into the handshake
         * @param port   the server port written into the handshake
         * @param name   the player username (Login Hello)
         * @param uuidMsb the player UUID's most-significant 64 bits
         * @param uuidLsb the player UUID's least-significant 64 bits
         * @param listener the gameplay packet listener
         * @return true when the attempt was initiated
         */
        public static Connection connect(String uri, String host, int port,
                        String name, long uuidMsb, long uuidLsb, Listener listener) {
                Connection conn = new Connection();
                conn.listener = listener;
                if (!PlatformNetworking.connect(uri)) {
                        return conn; // state FAILED; pollDisconnect reports it
                }
                conn.socketOwned = true;
                PlatformNetworking.setMessageListener(data -> {
                        byte[] bytes = PlatformRuntime.arrayBufferToBytes(data);
                        if (bytes != null && bytes.length > 0) {
                                conn.inbound.add(bytes);
                        }
                });
                // Vanilla order: the handshake must arrive before anything
                // else; the login hello rides in the same burst (the server
                // reads sequentially, and both frames are already ours).
                // The socket is still CONNECTING here - the burst queues up
                // and flushes the moment onopen lands (a direct send would
                // be dropped by the not-yet-open guard).
                conn.sendHandshake(host, port, 2); // next state: LOGIN
                conn.sendLoginHello(name, uuidMsb, uuidLsb);
                // The client's own state machine follows its handshake: the
                // server's replies are LOGIN-state packets from here on.
                conn.state = STATE_LOGIN;
                return conn;
        }

        private void sendHandshake(String host, int port, int nextState) {
                PacketBuffer buf = new PacketBuffer(64);
                buf.writeVarInt(0x00);
                buf.writeVarInt(PROTOCOL_VERSION);
                buf.writeUtf(host);
                buf.writeShort(port);
                buf.writeVarInt(nextState);
                this.sendRaw(buf);
        }

        private void sendLoginHello(String name, long uuidMsb, long uuidLsb) {
                PacketBuffer buf = new PacketBuffer(64);
                buf.writeVarInt(C2S_LOGIN_HELLO);
                buf.writeLong(uuidMsb);
                buf.writeLong(uuidLsb);
                buf.writeUtf(name);
                buf.writeVarInt(0); // properties: none (offline profile)
                this.sendRaw(buf);
        }

        // ========== Configuration state (C→S) ==========

        /** Configuration Client Information - the pinned vanilla shape. */
        public void sendClientInformation() {
                PacketBuffer buf = new PacketBuffer(32);
                buf.writeVarInt(C2S_CONFIG_CLIENT_INFORMATION);
                buf.writeUtf("en_us");        // locale
                buf.writeByte(8);             // view distance
                buf.writeVarInt(0);           // chat mode: enabled
                buf.writeBoolean(true);       // chat colors
                buf.writeByte(0x7F);          // displayed skin parts: all
                buf.writeVarInt(1);           // main hand: right
                buf.writeBoolean(false);      // text filtering
                buf.writeBoolean(true);       // allow server listings
                this.sendRaw(buf);
        }

        /** Known Packs response - this client knows no downloadable packs. */
        public void sendKnownPacksEmpty() {
                PacketBuffer buf = new PacketBuffer(8);
                buf.writeVarInt(C2S_CONFIG_KNOWN_PACKS);
                buf.writeVarInt(0);
                this.sendRaw(buf);
        }

        /** Acknowledge Finish Configuration (→ PLAY). */
        public void sendAcknowledgeFinishConfiguration() {
                PacketBuffer buf = new PacketBuffer(4);
                buf.writeVarInt(C2S_CONFIG_ACK_FINISH);
                this.sendRaw(buf);
        }

        /** Client brand plugin message (minecraft:brand, after config). */
        public void sendBrand(String brand) {
                PacketBuffer data = new PacketBuffer(32);
                data.writeUtf(brand);
                this.sendPluginMessage("minecraft:brand", data.toArray());
        }

        /** Configuration Plugin Message (C→S 0x01). */
        public void sendPluginMessage(String channel, byte[] data) {
                PacketBuffer buf = new PacketBuffer(16 + data.length);
                buf.writeVarInt(C2S_CONFIG_PLUGIN_MESSAGE);
                buf.writeUtf(channel);
                buf.writeBytes(data);
                this.sendRaw(buf);
        }

        // ========== Play state (C→S) ==========

        public void sendKeepAliveResponse(long token) {
                PacketBuffer buf = new PacketBuffer(12);
                buf.writeVarInt(C2S_PLAY_KEEP_ALIVE);
                buf.writeLong(token);
                this.sendRaw(buf);
        }

        public void sendTeleportConfirm(int teleportId) {
                PacketBuffer buf = new PacketBuffer(8);
                buf.writeVarInt(C2S_PLAY_TELEPORT_CONFIRM);
                buf.writeVarInt(teleportId);
                this.sendRaw(buf);
        }

        /** Move Player Pos Rot (C→S 0x14) - position AND rotation changed. */
        public void sendMovePosRot(double x, double y, double z,
                        float yaw, float pitch, boolean onGround) {
                PacketBuffer buf = new PacketBuffer(40);
                buf.writeVarInt(C2S_PLAY_MOVE_POS_ROT);
                buf.writeDouble(x);
                buf.writeDouble(y);
                buf.writeDouble(z);
                buf.writeFloat(yaw);
                buf.writeFloat(pitch);
                buf.writeBoolean(onGround);
                this.sendRaw(buf);
        }

        /** Move Player Rot (C→S 0x15) - only the rotation changed. */
        public void sendMoveRot(float yaw, float pitch, boolean onGround) {
                PacketBuffer buf = new PacketBuffer(16);
                buf.writeVarInt(C2S_PLAY_MOVE_ROT);
                buf.writeFloat(yaw);
                buf.writeFloat(pitch);
                buf.writeBoolean(onGround);
                this.sendRaw(buf);
        }

        /** Move Player Status Only (C→S 0x16) - only the onGround flag. */
        public void sendMoveStatusOnly(boolean onGround) {
                PacketBuffer buf = new PacketBuffer(4);
                buf.writeVarInt(C2S_PLAY_MOVE_STATUS_ONLY);
                buf.writeBoolean(onGround);
                this.sendRaw(buf);
        }

        /** Player Action (C→S 0x1C): dig start/abort/finish. */
        public void sendPlayerAction(int status, int x, int y, int z, int face) {
                PacketBuffer buf = new PacketBuffer(16);
                buf.writeVarInt(C2S_PLAY_PLAYER_ACTION);
                buf.writeVarInt(status);
                buf.writeLong(encodePos(x, y, z));
                buf.writeByte(face);
                this.sendRaw(buf);
        }

        /** Use Item On (C→S 0x2A): right-click a block face. */
        public void sendUseItemOn(int x, int y, int z, int face) {
                PacketBuffer buf = new PacketBuffer(12);
                buf.writeVarInt(C2S_PLAY_USE_ITEM_ON);
                buf.writeLong(encodePos(x, y, z));
                buf.writeByte(face);
                this.sendRaw(buf);
        }

        /** Set Carried Item (C→S 0x22): hotbar slot selection. */
        public void sendSetCarriedItem(int slot) {
                PacketBuffer buf = new PacketBuffer(4);
                buf.writeVarInt(C2S_PLAY_SET_CARRIED_ITEM);
                buf.writeShort(slot);
                this.sendRaw(buf);
        }

        /** Swing (C→S 0x26): arm swing animation. */
        public void sendSwing() {
                PacketBuffer buf = new PacketBuffer(4);
                buf.writeVarInt(C2S_PLAY_SWING);
                buf.writeVarInt(0); // main hand
                this.sendRaw(buf);
        }

        /** Chat Message (C→S 0x07). */
        public void sendChat(String message) {
                PacketBuffer buf = new PacketBuffer(8 + message.length());
                buf.writeVarInt(C2S_PLAY_CHAT_MESSAGE);
                buf.writeUtf(message);
                this.sendRaw(buf);
        }

        /**
         * Vanilla Position type: 8-byte long, x 26 bits, z 26 bits,
         * y 12 bits (PROTOCOL.md Data Types).
         */
        public static long encodePos(int x, int y, int z) {
                return ((long) (x & 0x3FFFFFF) << 38)
                                | ((long) (z & 0x3FFFFFF) << 12)
                                | (long) (y & 0xFFF);
        }

        public static int decodePosX(long pos) {
                return (int) (pos >> 38);
        }

        public static int decodePosY(long pos) {
                return (int) (pos << 52 >> 52);
        }

        public static int decodePosZ(long pos) {
                return (int) (pos << 26 >> 38);
        }

        // ========== Pump / dispatch ==========

        /**
         * Drains queued inbound frames on the game thread and advances
         * the state machine. Called once per game tick.
         */
        public void tick() {
                if (this.disconnected) {
                        return;
                }
                // Flush the pre-open queue the moment the socket opens
                if (!this.socketOpen && PlatformNetworking.isConnected()) {
                        this.socketOpen = true;
                        while (!this.outboundQueue.isEmpty()) {
                                this.sendRaw(new PacketBuffer(this.outboundQueue.poll()));
                        }
                }
                while (true) {
                        byte[] frame;
                        synchronized (this.inbound) {
                                frame = this.inbound.poll();
                        }
                        if (frame == null) {
                                break;
                        }
                        try {
                                this.handleFrame(frame);
                        } catch (PacketBuffer.PacketDecoderException e) {
                                ClientMain.warn("[Connection] malformed packet: "
                                                + e.getMessage());
                                this.disconnect("Internal error: bad packet data");
                                return;
                        }
                }
        }

        private void handleFrame(byte[] frame) {
                PacketBuffer buf = new PacketBuffer(frame);
                int packetId = buf.readVarInt();
                switch (this.state) {
                        case STATE_HANDSHAKE:
                                // Nothing is legal here: the server moves us
                                // to LOGIN/STATUS on its side after our burst.
                                ClientMain.warn("[Connection] unexpected packet 0x"
                                                + Integer.toHexString(packetId)
                                                + " in HANDSHAKE");
                                this.disconnect("Protocol error: unexpected server packet");
                                break;

                        case STATE_STATUS:
                                if (packetId == S2C_STATUS_RESPONSE) {
                                        this.forward(packetId, buf);
                                } else if (packetId == S2C_STATUS_PONG) {
                                        this.forward(packetId, buf);
                                } else {
                                        this.unknownPacket(packetId);
                                }
                                break;

                        case STATE_LOGIN:
                                if (packetId == S2C_LOGIN_ACKNOWLEDGE) {
                                        // Informational: Login Success moves
                                        // the connection into CONFIGURATION.
                                } else if (packetId == S2C_LOGIN_SUCCESS) {
                                        buf.readLong(); // uuid msb (echoed)
                                        buf.readLong(); // uuid lsb
                                        String name = buf.readUtf();
                                        int props = buf.readVarInt();
                                        for (int i = 0; i < props; i++) {
                                                buf.readUtf(); // key
                                                buf.readUtf(); // value
                                                if (buf.readBoolean()) {
                                                        buf.readUtf(); // signature
                                                }
                                        }
                                        this.state = STATE_CONFIGURATION;
                                        ClientMain.log("[Connection] logged in as "
                                                        + name + " - configuration phase");
                                        // the login success reaches the listener
                                        // under its OWN state - this.state has
                                        // already advanced to CONFIGURATION and
                                        // 0x02 doubles as the config finish id
                                        this.forward(STATE_LOGIN, packetId,
                                                        new PacketBuffer());
                                } else if (packetId == S2C_LOGIN_DISCONNECT) {
                                        String reason = buf.readUtf();
                                        this.disconnect(extractText(reason));
                                } else if (packetId == S2C_LOGIN_PLUGIN_REQUEST) {
                                        // Vanilla answers with the same message id.
                                        int msgId = buf.readVarInt();
                                        String channel = buf.readUtf();
                                        PacketBuffer reply = new PacketBuffer(8);
                                        reply.writeVarInt(C2S_LOGIN_PLUGIN_RESPONSE);
                                        reply.writeVarInt(msgId);
                                        reply.writeBoolean(false); // no data
                                        this.sendRaw(reply);
                                        ClientMain.warn("[Connection] login plugin request '"
                                                        + channel + "' answered empty");
                                } else {
                                        this.unknownPacket(packetId);
                                }
                                break;

                        case STATE_CONFIGURATION:
                                if (packetId == S2C_CONFIG_KEEP_ALIVE) {
                                        long token = buf.readLong();
                                        PacketBuffer reply = new PacketBuffer(12);
                                        reply.writeVarInt(C2S_CONFIG_KEEP_ALIVE);
                                        reply.writeLong(token);
                                        this.sendRaw(reply);
                                } else if (packetId == S2C_CONFIG_PING) {
                                        long token = buf.readLong();
                                        PacketBuffer reply = new PacketBuffer(12);
                                        reply.writeVarInt(C2S_CONFIG_PONG);
                                        reply.writeLong(token);
                                        this.sendRaw(reply);
                                } else if (packetId == S2C_CONFIG_FINISH) {
                                        ClientMain.log("[Connection] configuration finished - play phase");
                                        this.state = STATE_PLAY;
                                        // the finish reaches the listener under
                                        // CONFIGURATION - this.state has already
                                        // advanced to PLAY and the listener's
                                        // config handler sends the ack burst
                                        this.forward(STATE_CONFIGURATION, packetId,
                                                        new PacketBuffer());
                                } else if (packetId == S2C_CONFIG_DISCONNECT) {
                                        this.disconnect(extractText(buf.readUtf()));
                                } else {
                                        // plugin message / registry data /
                                        // feature flags / known packs
                                        this.forward(packetId, buf);
                                }
                                break;

                        case STATE_PLAY:
                                if (packetId == S2C_PLAY_KEEP_ALIVE) {
                                        long token = buf.readLong();
                                        this.sendKeepAliveResponse(token);
                                } else if (packetId == S2C_PLAY_DISCONNECT) {
                                        this.disconnect(extractText(buf.readUtf()));
                                } else {
                                        this.forward(packetId, buf);
                                }
                                break;

                        default:
                                this.unknownPacket(packetId);
                                break;
                }
        }

        private void forward(int packetId, PacketBuffer payload) {
                this.forward(this.state, packetId, payload);
        }

        private void forward(int state, int packetId, PacketBuffer payload) {
                if (this.listener != null) {
                        this.listener.packetReceived(state, packetId, payload);
                }
        }

        private void unknownPacket(int packetId) {
                ClientMain.warn("[Connection] unknown packet 0x"
                                + Integer.toHexString(packetId) + " in state "
                                + this.state);
                this.disconnect("Protocol error: unknown packet id");
        }

        // ========== Outbound / teardown ==========

        private void sendRaw(PacketBuffer buf) {
                if (this.disconnected) {
                        return;
                }
                if (!this.socketOpen) {
                        // The socket is still opening: queue (flushed in tick)
                        this.outboundQueue.add(buf.toArray());
                        return;
                }
                if (!PlatformNetworking.isConnected()) {
                        return;
                }
                PlatformNetworking.send(buf.toArray());
        }

        /**
         * Closes the socket (client-initiated) and stops the state machine.
         * The reason is surfaced through {@link #pollDisconnect()}.
         */
        public void disconnect(String reason) {
                if (this.disconnected) {
                        return;
                }
                this.disconnected = true;
                this.disconnectReason = reason != null ? reason : "disconnect.endOfStream";
                if (this.socketOwned) {
                        PlatformNetworking.setMessageListener(null);
                        PlatformNetworking.disconnect();
                }
                ClientMain.log("[Connection] disconnected: " + this.disconnectReason);
        }

        /**
         * Returns (and clears) the disconnect reason once the connection
         * is dead - socket failure, server disconnect, or a client-side
         * protocol violation. Null while the connection is alive.
         */
        public String pollDisconnect() {
                if (!this.disconnected) {
                        int transport = PlatformNetworking.getConnectionState();
                        if (transport == PlatformNetworking.CONN_FAILED) {
                                this.disconnected = true;
                                this.disconnectReason =
                                                "Connection failed: the server did not respond";
                        } else if (this.socketOwned
                                        && transport == PlatformNetworking.CONN_CLOSED) {
                                this.disconnected = true;
                                if (this.disconnectReason == null) {
                                        this.disconnectReason =
                                                        "Connection lost: the server closed the connection";
                                }
                        }
                }
                String reason = this.disconnectReason;
                this.disconnectReason = null;
                return this.disconnected ? reason : null;
        }

        public boolean isConnected() {
                return !this.disconnected && PlatformNetworking.isConnected();
        }

        public int getState() {
                return this.state;
        }

        // ========== JSON reason helper ==========

        /**
         * Loosely extracts human-readable text from a JSON chat
         * component (the vanilla disconnect reason shape): prefers
         * "text", falls back to "translate", falls back to the raw
         * string. No JSON library - the gateway only ever sends
         * {"text": ...} or {"translate": ...}.
         */
        public static String extractText(String json) {
                if (json == null) {
                        return "disconnect.endOfStream";
                }
                String text = extractJsonString(json, "text");
                if (text != null) {
                        return text;
                }
                String translate = extractJsonString(json, "translate");
                if (translate != null) {
                        return translate;
                }
                return json;
        }

        private static String extractJsonString(String json, String key) {
                String needle = "\"" + key + "\"";
                int k = json.indexOf(needle);
                if (k < 0) {
                        return null;
                }
                int colon = json.indexOf(':', k + needle.length());
                if (colon < 0) {
                        return null;
                }
                int i = colon + 1;
                while (i < json.length() && json.charAt(i) == ' ') {
                        i++;
                }
                if (i >= json.length() || json.charAt(i) != '"') {
                        return null;
                }
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < json.length()) {
                        char c = json.charAt(i);
                        if (c == '\\' && i + 1 < json.length()) {
                                char n = json.charAt(i + 1);
                                if (n == 'n') {
                                        sb.append('\n');
                                } else if (n == 't') {
                                        sb.append('\t');
                                } else {
                                        sb.append(n);
                                }
                                i += 2;
                                continue;
                        }
                        if (c == '"') {
                                return sb.toString();
                        }
                        sb.append(c);
                        i++;
                }
                return sb.toString();
        }
}
