package net.minecraft.client.multiplayer;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.DisconnectedScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 - the client's play-phase protocol handler (the
 * vanilla {@code ClientPacketListener} role) plus the configuration
 * phase handshake that leads into it.
 *
 * <p>Packet layouts are pinned in docs/PROTOCOL.md ("Gateway Field
 * Reference"); the transport state machine lives in {@link Connection}
 * (keep-alives and pings are answered there). This class translates
 * packets into game state:</p>
 *
 * <ul>
 *   <li>configuration: brand, registry/feature-flag/known-pack
 *       bookkeeping (logged), Finish → client info + ack.</li>
 *   <li>play login: constructs the remote {@link ClientLevel} and the
 *       {@link LocalPlayer} and enters the world
 *       ({@link Minecraft#doMultiplayerJoin}).</li>
 *   <li>level chunk: applies server sections into the level.</li>
 *   <li>block update / set container slot: server-authoritative world
 *       and hotbar state.</li>
 *   <li>player position: server teleports snap the player (confirmed).</li>
 *   <li>movement pump: the vanilla move-packet choice rule per tick.</li>
 * </ul>
 */
public class ClientPacketListener implements Connection.Listener {

        private final Minecraft minecraft;
        private final String serverAddress;

        /** The connection this listener is bound to (set by ConnectScreen). */
        private Connection connection;

        /** Human-readable phase for the ConnectScreen status line. */
        private String stageText = "Connecting to the server...";

        // Movement pump: the last transform sent to the server
        private double lastSentX, lastSentY, lastSentZ;
        private float lastSentYaw, lastSentPitch;
        private boolean lastSentOnGround;
        private boolean hasSentMove;

        public ClientPacketListener(Minecraft minecraft, String serverAddress) {
                this.minecraft = minecraft;
                this.serverAddress = serverAddress;
        }

        public void attach(Connection connection) {
                this.connection = connection;
        }

        public String getStageText() {
                return this.stageText;
        }

        public Connection getConnection() {
                return this.connection;
        }

        // ========== Configuration phase ==========

        private void handleConfigurationPacket(int packetId, PacketBuffer buf) {
                switch (packetId) {
                        case Connection.S2C_CONFIG_PLUGIN_MESSAGE: {
                                String channel = buf.readUtf();
                                byte[] data = buf.readRemaining();
                                if ("minecraft:brand".equals(channel)) {
                                        PacketBuffer brand = new PacketBuffer(data);
                                        ClientMain.log("[ClientPacketListener] server brand: "
                                                        + brand.readUtf());
                                }
                                break;
                        }
                        case Connection.S2C_CONFIG_REGISTRY_DATA: {
                                int registries = buf.readVarInt();
                                for (int r = 0; r < registries; r++) {
                                        String registryId = buf.readUtf();
                                        int entries = buf.readVarInt();
                                        for (int e = 0; e < entries; e++) {
                                                buf.readUtf();
                                        }
                                        ClientMain.log("[ClientPacketListener] registry "
                                                        + registryId + " (" + entries + " entries)");
                                }
                                break;
                        }
                        case Connection.S2C_CONFIG_FEATURE_FLAGS: {
                                int flags = buf.readVarInt();
                                for (int i = 0; i < flags; i++) {
                                        buf.readUtf();
                                }
                                break;
                        }
                        case Connection.S2C_CONFIG_KNOWN_PACKS: {
                                int packs = buf.readVarInt();
                                for (int i = 0; i < packs; i++) {
                                        buf.readUtf(); // namespace
                                        buf.readUtf(); // id
                                        buf.readUtf(); // version
                                }
                                // This client ships all resources in assets.epk:
                                // it knows no downloadable packs.
                                this.connection.sendKnownPacksEmpty();
                                break;
                        }
                        case Connection.S2C_CONFIG_FINISH: {
                                this.stageText = "Joining world...";
                                // Vanilla order: client information goes out
                                // during configuration; the ack transitions
                                // both sides to play.
                                this.connection.sendClientInformation();
                                this.connection.sendBrand("eaglercraft-26.1.2-web");
                                this.connection.sendAcknowledgeFinishConfiguration();
                                break;
                        }
                        default:
                                break;
                }
        }

        // ========== Play phase ==========

        private void handlePlayPacket(int packetId, PacketBuffer buf) {
                switch (packetId) {
                        case Connection.S2C_PLAY_LOGIN:
                                this.handlePlayLogin(buf);
                                break;
                        case Connection.S2C_PLAY_CHUNK:
                                this.handleLevelChunk(buf);
                                break;
                        case Connection.S2C_PLAY_BLOCK_UPDATE:
                                this.handleBlockUpdate(buf);
                                break;
                        case Connection.S2C_PLAY_SET_CONTAINER_SLOT:
                                this.handleSetContainerSlot(buf);
                                break;
                        case Connection.S2C_PLAY_UNLOAD_CHUNK:
                                this.handleUnloadChunk(buf);
                                break;
                        case Connection.S2C_PLAY_PLAYER_POSITION:
                                this.handlePlayerPosition(buf);
                                break;
                        case Connection.S2C_PLAY_SYSTEM_CHAT: {
                                String json = buf.readUtf();
                                ClientMain.log("[chat] "
                                                + Connection.extractText(json));
                                break;
                        }
                        default:
                                ClientMain.warn("[ClientPacketListener] unhandled play packet 0x"
                                                + Integer.toHexString(packetId));
                                break;
                }
        }

        /**
         * Play Login (S→C 0x2A) - the gateway's pinned layout: entity id,
         * game mode, dimension, hashed seed, difficulty, hardcore flag,
         * level type, view distance, day time, game time, spawn position.
         */
        private void handlePlayLogin(PacketBuffer buf) {
                int entityId = buf.readInt();
                int gameMode = buf.readUByte();
                String dimension = buf.readUtf();
                buf.readLong(); // hashed seed (worldgen is server-side)
                int difficulty = buf.readUByte();
                boolean hardcore = buf.readBoolean();
                String levelType = buf.readUtf();
                int viewDistance = buf.readVarInt();
                long dayTime = buf.readLong();
                long gameTime = buf.readLong();
                double spawnX = buf.readDouble();
                double spawnY = buf.readDouble();
                double spawnZ = buf.readDouble();

                ClientMain.log("[ClientPacketListener] play login: entity=" + entityId
                                + " gamemode=" + gameMode + " dimension=" + dimension
                                + " levelType=" + levelType + " view=" + viewDistance);

                this.minecraft.doMultiplayerJoin(this, this.serverAddress, entityId,
                                gameMode, dimension, difficulty, hardcore,
                                dayTime, gameTime, spawnX, spawnY, spawnZ);
        }

        /**
         * Level Chunk with Light (S→C 0x2E) - the gateway's pinned
         * section-palette form: chunkX, chunkZ, section count, per
         * section {sectionY, palette, 4096 palette indices}. Voxels are
         * applied into the level's edit overlay (full block-id range;
         * the mesher reads through getBlockState).
         */
        private void handleLevelChunk(PacketBuffer buf) {
                ClientLevel level = this.clientLevel();
                if (level == null) {
                        return;
                }
                int chunkX = buf.readInt();
                int chunkZ = buf.readInt();
                int sections = buf.readVarInt();
                for (int s = 0; s < sections; s++) {
                        int sectionY = buf.readByte();
                        int paletteLen = buf.readVarInt();
                        int[] palette = new int[paletteLen];
                        for (int i = 0; i < paletteLen; i++) {
                                palette[i] = buf.readVarInt();
                        }
                        byte[] voxels = buf.readBytes(4096);
                        level.applyServerSection(chunkX, sectionY, chunkZ,
                                        palette, voxels);
                }
        }

        /** Block Update (S→C 0x05): server-authoritative single block. */
        private void handleBlockUpdate(PacketBuffer buf) {
                ClientLevel level = this.clientLevel();
                if (level == null) {
                        return;
                }
                long pos = buf.readLong();
                int blockId = buf.readVarInt();
                level.applyServerBlockUpdate(Connection.decodePosX(pos),
                                Connection.decodePosY(pos),
                                Connection.decodePosZ(pos), blockId);
        }

        /**
         * Set Container Slot (S→C 0x0E) - window 0 is the player
         * inventory; slots 0-8 are the hotbar (vanilla numbering).
         */
        private void handleSetContainerSlot(PacketBuffer buf) {
                int window = buf.readUByte();
                int slot = buf.readShort();
                int itemId = buf.readVarInt();
                int count = buf.readVarInt();
                if (window == 0 && slot >= 0
                                && slot < net.minecraft.world.entity.player.Inventory.TOTAL_SIZE) {
                        this.minecraft.getInventory().setSlot(slot, itemId, count);
                }
        }

        /** Unload Chunk (S→C 0x1F): forget a streamed column. */
        private void handleUnloadChunk(PacketBuffer buf) {
                ClientLevel level = this.clientLevel();
                if (level == null) {
                        return;
                }
                int chunkX = buf.readInt();
                int chunkZ = buf.readInt();
                level.unloadServerColumn(chunkX, chunkZ);
        }

        /**
         * Player Position (S→C 0x38) - a server teleport. Vanilla shape:
         * x/y/z, yaw, pitch, relative-flags byte, teleport id. Flags 0
         * (all absolute) is what the gateway sends; relative bits are
         * applied when set. Confirmed with Teleport Confirm (C→S 0x00).
         */
        private void handlePlayerPosition(PacketBuffer buf) {
                LocalPlayer player = this.minecraft.getPlayer();
                if (player == null) {
                        return;
                }
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                float yaw = buf.readFloat();
                float pitch = buf.readFloat();
                int flags = buf.readByte();
                int teleportId = buf.readVarInt();

                double nx = (flags & 0x01) != 0 ? player.x + x : x;
                double ny = (flags & 0x02) != 0 ? player.y + y : y;
                double nz = (flags & 0x04) != 0 ? player.z + z : z;
                float nyaw = (flags & 0x08) != 0 ? player.yRot + yaw : yaw;
                float npitch = (flags & 0x10) != 0 ? player.xRot + pitch : pitch;
                player.snapTo(nx, ny, nz, nyaw, npitch);
                player.xd = 0.0D;
                player.yd = 0.0D;
                player.zd = 0.0D;
                this.connection.sendTeleportConfirm(teleportId);
                this.hasSentMove = false; // force a full pos-rot resend
        }

        private ClientLevel clientLevel() {
                Object level = this.minecraft.getLevel();
                return level instanceof ClientLevel
                                ? (ClientLevel) level : null;
        }

        // ========== Connection.Listener ==========

        @Override
        public void packetReceived(int state, int packetId, PacketBuffer payload) {
                if (state == Connection.STATE_LOGIN
                                && packetId == Connection.S2C_LOGIN_SUCCESS) {
                        // 0x02 is both Login Success and Configuration Finish -
                        // the state is the only discriminator. Login success
                        // just advances the status line; the configuration
                        // sequence is driven by the real finish packet.
                        this.stageText = "Joining world...";
                        return;
                }
                if (state == Connection.STATE_CONFIGURATION) {
                        this.handleConfigurationPacket(packetId, payload);
                } else if (state == Connection.STATE_PLAY) {
                        this.handlePlayPacket(packetId, payload);
                }
        }

        // ========== Outgoing gameplay helpers ==========

        /** Player Action - the dig lifecycle (vanilla statuses 0/1/2). */
        public void sendPlayerAction(int status, int x, int y, int z, int face) {
                if (this.connection != null) {
                        this.connection.sendPlayerAction(status, x, y, z, face);
                }
        }

        /** Use Item On - right-click placement against a block face. */
        public void sendUseItemOn(int x, int y, int z, int face) {
                if (this.connection != null) {
                        this.connection.sendUseItemOn(x, y, z, face);
                }
        }

        public void sendSwing() {
                if (this.connection != null) {
                        this.connection.sendSwing();
                }
        }

        /** Set Carried Item - hotbar selection changes. */
        public void sendSetCarriedItem(int slot) {
                if (this.connection != null) {
                        this.connection.sendSetCarriedItem(slot);
                }
        }

        /** Chat Message. */
        public void sendChat(String message) {
                if (this.connection != null) {
                        this.connection.sendChat(message);
                }
        }

        // ========== Movement pump (vanilla LocalPlayer.sendPosition) ==========

        /**
         * The vanilla move-packet choice rule, evaluated once per game
         * tick while in a multiplayer world: Pos Rot when both moved and
         * turned, Rot when only turned, Status Only when only the
         * onGround flag changed, nothing when nothing changed.
         */
        public void tickMovement() {
                LocalPlayer player = this.minecraft.getPlayer();
                if (this.connection == null || player == null) {
                        return;
                }
                double dx = player.x - this.lastSentX;
                double dy = player.y - this.lastSentY;
                double dz = player.z - this.lastSentZ;
                float dyaw = player.yRot - this.lastSentYaw;
                float dpitch = player.xRot - this.lastSentPitch;
                boolean moved = dx * dx + dy * dy + dz * dz > 2.25E-7D;
                boolean turned = dyaw != 0.0F || dpitch != 0.0F;
                boolean groundChanged = player.onGround != this.lastSentOnGround;

                if (!this.hasSentMove) {
                        this.connection.sendMovePosRot(player.x, player.y,
                                        player.z, player.yRot, player.xRot,
                                        player.onGround);
                } else if (moved && turned) {
                        this.connection.sendMovePosRot(player.x, player.y,
                                        player.z, player.yRot, player.xRot,
                                        player.onGround);
                } else if (moved) {
                        this.connection.sendMovePosRot(player.x, player.y,
                                        player.z, player.yRot, player.xRot,
                                        player.onGround);
                } else if (turned) {
                        this.connection.sendMoveRot(player.yRot, player.xRot,
                                        player.onGround);
                } else if (groundChanged) {
                        this.connection.sendMoveStatusOnly(player.onGround);
                } else {
                        return;
                }

                this.lastSentX = player.x;
                this.lastSentY = player.y;
                this.lastSentZ = player.z;
                this.lastSentYaw = player.yRot;
                this.lastSentPitch = player.xRot;
                this.lastSentOnGround = player.onGround;
                this.hasSentMove = true;
        }

        /** Leaves the world: closes the connection. */
        public void disconnect(String reason) {
                if (this.connection != null) {
                        this.connection.disconnect(reason != null
                                        ? reason : "disconnect.quitting");
                }
        }

        /** Opens the vanilla disconnect screen (server-side kicks). */
        public void showDisconnected(String reason) {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(new DisconnectedScreen(
                                        "Connection Lost", reason, null));
                }
        }
}
