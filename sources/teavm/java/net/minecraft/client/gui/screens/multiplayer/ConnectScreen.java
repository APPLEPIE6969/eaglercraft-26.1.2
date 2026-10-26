package net.minecraft.client.gui.screens.multiplayer;

import com.mojang.blaze3d.vertex.PoseStack;



import net.lax1dude.eaglercraft.v2_6.EaglerProfile;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.Connection;
import org.teavm.jso.JSBody;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code ConnectScreen}
 * - the "Connecting to server" progress screen.
 *
 * <p>Vanilla layout: the "Connecting to server" title and a centered
 * status line at {@code height/2 - 50}. The join attempt is the real
 * gateway protocol (docs/PROTOCOL.md): the entry's address is
 * normalized to a WebSocket URI, {@link Connection} drives the
 * handshake - login - configuration state machine, and the play
 * Login packet enters the world. Failure at any stage opens the
 * vanilla {@link DisconnectedScreen} with the server's reason; the
 * 10-second join guard catches silent hosts.</p>
 */
public class ConnectScreen extends Screen {

        private static final int TITLE_Y = 20;

        /** Vanilla ConnectScreen status line Y (height/2 - 50). */
        private static final int STATUS_OFFSET = -50;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_STATUS = 0xFFD0D0D0;

        /** Vanilla join timeout (10s guard against silent hosts). */
        private static final long TIMEOUT_MS = 10000;

        protected final Screen lastScreen;
        protected final ServerList.ServerData serverData;

        private ClientPacketListener packetListener;
        private Connection connection;
        private String status = "Connecting to the server...";
        private long startTime;
        private boolean joined;
        /** Join attempts must be one-per-screen: the canvas-settle re-init
         * would otherwise open a second socket against the live one. */
        private boolean joinStarted;

        public ConnectScreen(Screen lastScreen, ServerList.ServerData serverData) {
                this.lastScreen = lastScreen;
                this.serverData = serverData;
        }

        @Override
        protected void init() {
                this.startTime = PlatformRuntime.getCurrentTimeMillis();
                String uri = normalizeUri(this.serverData.ip);
                this.status = "Connecting to " + this.serverData.ip + "...";
                if (!uri.isEmpty()) {
                        if (!this.joinStarted) {
                                this.joinStarted = true;
                                this.join(uri);
                        }
                } else {
                        this.status = "Connection failed: invalid server address \""
                                        + this.serverData.ip + "\"";
                }
        }

        private void join(String uri) {
                Minecraft mc = Minecraft.getInstance();
                String hostPort = hostOf(this.serverData.ip);
                this.packetListener = new ClientPacketListener(
                                mc, this.serverData.ip);
                // A random offline profile UUID (version 4, RFC 4122 variant),
                // built by hand: TeaVM's java.util.UUID loses its methods
                // across the Connection boundary, and the wire only ever
                // wanted the 16 bytes.
                java.util.Random random = new java.util.Random();
                long uuidMsb = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL)
                                | 0x0000000000004000L;
                long uuidLsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                                | 0x8000000000000000L;
                this.connection = Connection.connect(uri, hostPort,
                                portOf(this.serverData.ip),
                                EaglerProfile.getUsername(),
                                uuidMsb, uuidLsb,
                                this.packetListener);
                this.packetListener.attach(this.connection);
        }

        /**
         * Normalizes an entry address to a WebSocket URI: a bare
         * "host[:port]" takes the page's own scheme (an http page opens
         * ws://, an https page wss:// - browsers refuse the mixed
         * alternative); a scheme already present is kept verbatim.
         * Returns "" for an unusable address.
         */
        static String normalizeUri(String address) {
                String trimmed = address == null ? "" : address.trim();
                if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
                        return trimmed;
                }
                if (trimmed.isEmpty() || trimmed.contains("://")
                                || trimmed.contains("/")) {
                        return "";
                }
                return ("https:".equals(pageProtocol()) ? "wss://" : "ws://")
                                + trimmed;
        }

        /** The host part of the entry address (for the handshake packet). */
        static String hostOf(String address) {
                String s = address.trim();
                int scheme = s.indexOf("://");
                if (scheme >= 0) {
                        s = s.substring(scheme + 3);
                }
                int slash = s.indexOf('/');
                if (slash >= 0) {
                        s = s.substring(0, slash);
                }
                int colon = s.lastIndexOf(':');
                if (colon > 0) {
                        s = s.substring(0, colon);
                }
                return s;
        }

        /** The port: explicit in the address, else 80 (ws) / 443 (wss). */
        static int portOf(String address) {
                String s = address.trim();
                int scheme = s.indexOf("://");
                if (scheme >= 0) {
                        s = s.substring(scheme + 3);
                }
                int slash = s.indexOf('/');
                if (slash >= 0) {
                        s = s.substring(0, slash);
                }
                int colon = s.lastIndexOf(':');
                if (colon > 0) {
                        try {
                                return Integer.parseInt(s.substring(colon + 1));
                        } catch (NumberFormatException nfe) {
                                return defaultPort();
                        }
                }
                return defaultPort();
        }

        private static int defaultPort() {
                return "https:".equals(pageProtocol()) ? 443 : 80;
        }

        @JSBody(script = "return typeof window !== 'undefined'"
                        + " ? window.location.protocol : 'https:';")
        private static native String pageProtocol();

        @Override
        public void tick() {
                if (this.joined) {
                        return;
                }
                Minecraft mc = Minecraft.getInstance();
                if (this.packetListener != null) {
                        // Progress the protocol queue while the screen is up
                        this.connection.tick();
                        this.status = this.packetListener.getStageText();
                        if (mc.isLevelLoaded()) {
                                // The play Login packet entered the world;
                                // the game HUD replaces this screen
                                this.joined = true;
                                return;
                        }
                        String dead = this.connection.pollDisconnect();
                        if (dead != null) {
                                this.fail(dead);
                                return;
                        }
                } else if (PlatformNetworking.getConnectionState()
                                == PlatformNetworking.CONN_FAILED) {
                        this.fail("Connection failed: the server did not respond");
                        return;
                }
                long elapsed = PlatformRuntime.getCurrentTimeMillis() - this.startTime;
                if (elapsed > TIMEOUT_MS) {
                        this.fail("Connection timed out");
                }
        }

        private void fail(String reason) {
                this.joined = true;
                if (this.connection != null) {
                        this.connection.disconnect(reason);
                        this.connection = null;
                }
                if (this.minecraft != null) {
                        this.minecraft.setScreen(new DisconnectedScreen(
                                        "Connection Lost", reason, this.lastScreen));
                }
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Connecting to server");
                EaglerGuiImpl.drawText("Connecting to server",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                int statusWidth = EaglerGuiImpl.widthOf(this.status);
                EaglerGuiImpl.drawText(this.status,
                                this.width / 2 - statusWidth / 2.0f,
                                this.height / 2 + STATUS_OFFSET, COLOR_STATUS, true);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                // Vanilla ConnectScreen is not closable mid-attempt
                return true;
        }

        @Override
        public boolean shouldCloseOnEsc() {
                return false;
        }

        @Override
        public void removed() {
                // Leaving the screen without joining (timeout/failure paths
                // already cleaned up) drops a pending socket. The play-Login
                // path lands here too - setScreen(null) from the world join
                // runs this while this.joined is still false - so the world
                // being loaded means the connection is the game's now.
                if (this.joined
                                || (this.minecraft != null && this.minecraft.isLevelLoaded())) {
                        return;
                }
                if (this.connection != null) {
                        this.connection.disconnect("disconnect.genericReason");
                        this.connection = null;
                } else {
                        int state = PlatformNetworking.getConnectionState();
                        if (state == PlatformNetworking.CONN_CONNECTING
                                        || state == PlatformNetworking.CONN_CONNECTED) {
                                PlatformNetworking.disconnect();
                        }
                }
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
