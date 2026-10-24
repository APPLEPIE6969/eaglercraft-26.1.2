package net.minecraft.client.gui.screens.multiplayer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerList;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code ConnectScreen}
 * - the "Connecting to server" progress screen.
 *
 * <p>Vanilla layout: the "Connecting to server" title and a centered
 * status line at {@code height/2 - 50}. The join attempt is real: the
 * entry's address is normalized to a WebSocket URI and handed to
 * {@link PlatformNetworking#connect}; every tick the connection state
 * is polled. Failure opens the vanilla {@link DisconnectedScreen} with
 * the reason; a successful handshake disconnects immediately with the
 * honest "unsupported server protocol" reason (this client build has
 * no multiplayer world session), and the vanilla 10-second guard opens
 * "Connection timed out".</p>
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

        private String status = "Connecting to the server...";
        private long startTime;

        public ConnectScreen(Screen lastScreen, ServerList.ServerData serverData) {
                this.lastScreen = lastScreen;
                this.serverData = serverData;
        }

        @Override
        protected void init() {
                this.startTime = PlatformRuntime.getCurrentTimeMillis();
                String uri = normalizeUri(this.serverData.ip);
                this.status = "Connecting to " + this.serverData.ip + "...";
                if (!PlatformNetworking.connect(uri)) {
                        // Synchronous failure (bad URI / already connected)
                        int state = PlatformNetworking.getConnectionState();
                        this.status = "Connection failed: invalid server address \""
                                        + this.serverData.ip + "\"";
                        if (state != PlatformNetworking.CONN_FAILED) {
                                PlatformNetworking.disconnect();
                        }
                }
        }

        /**
         * Normalizes an entry address to a WebSocket URI: a bare
         * "host[:port]" becomes wss://host[:port] (browsers served over
         * HTTPS may only open secure sockets); a scheme already present
         * is kept verbatim.
         */
        static String normalizeUri(String address) {
                String trimmed = address.trim();
                if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
                        return trimmed;
                }
                return "wss://" + trimmed;
        }

        @Override
        public void tick() {
                int state = PlatformNetworking.getConnectionState();
                if (state == PlatformNetworking.CONN_FAILED) {
                        this.fail("Connection failed: the server did not respond");
                        return;
                }
                if (state == PlatformNetworking.CONN_CONNECTED) {
                        // The handshake completed, but this client build has
                        // no multiplayer world session - close honestly
                        PlatformNetworking.disconnect();
                        this.fail("Disconnected: this client build does not support"
                                        + " multiplayer gameplay yet");
                        return;
                }
                long elapsed = PlatformRuntime.getCurrentTimeMillis() - this.startTime;
                if (elapsed > TIMEOUT_MS) {
                        this.fail("Connection timed out");
                }
        }

        private void fail(String reason) {
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
                // Leaving the screen (any path) drops a pending socket
                int state = PlatformNetworking.getConnectionState();
                if (state == PlatformNetworking.CONN_CONNECTING
                                || state == PlatformNetworking.CONN_CONNECTED) {
                        PlatformNetworking.disconnect();
                }
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
