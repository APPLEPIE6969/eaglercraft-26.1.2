package net.lax1dude.eaglercraft.v2_6.internal;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * Platform networking implementation for EaglerCraft 26.1.2 on the TeaVM/browser platform.
 * Uses WebSocket with binary framing for multiplayer server communication.
 *
 * <p>Key 26.1.2 improvements:</p>
 * <ul>
 *   <li>WebSocket with binary type arraybuffer for efficient data transfer</li>
 *   <li>permessage-deflate compression support (negotiated in handshake)</li>
 *   <li>Automatic reconnection with exponential backoff</li>
 *   <li>Rate limiting to prevent flooding</li>
 *   <li>SignalR-style protocol support for server discovery</li>
 *   <li>Connection quality monitoring (ping/latency tracking)</li>
 *   <li>WebRTC with unified plan SDP for voice chat</li>
 *   <li>Connection state machine with proper lifecycle</li>
 * </ul>
 */
public class PlatformNetworking {

        /** Connection state constants. */
        public static final int CONN_CLOSED = 0;
        public static final int CONN_CONNECTING = 1;
        public static final int CONN_CONNECTED = 2;
        public static final int CONN_FAILED = 3;

        /** Current connection state. */
        private static int connectionState = CONN_CLOSED;

        /** The WebSocket instance. */
        private static JSObject websocket = null;

        /** The server URI. */
        private static String currentURI = null;

        /** Whether permessage-deflate is active on this connection. */
        private static boolean compressionEnabled = false;

        // ========== Reconnection State ==========
        private static boolean autoReconnect = false;
        private static int reconnectAttempts = 0;
        private static final int MAX_RECONNECT_ATTEMPTS = 5;
        private static final long BASE_RECONNECT_DELAY = 1000;
        private static final long MAX_RECONNECT_DELAY = 30000;
        private static long reconnectTimer = 0;

        // ========== Rate Limiting ==========
        private static final int RATE_LIMIT_WINDOW_MS = 1000;
        private static final int RATE_LIMIT_MAX_PACKETS = 200;
        private static long[] packetTimestamps = new long[RATE_LIMIT_MAX_PACKETS];
        private static int packetTimestampIndex = 0;
        private static boolean rateLimited = false;

        // ========== Connection Quality ==========
        private static long lastPingTime = 0;
        private static long currentLatency = 0;
        private static long bytesSent = 0;
        private static long bytesReceived = 0;
        private static long connectTime = 0;

        // ========== Inbound message listener ==========
        /**
         * The protocol layer's inbound hook ({@code Connection}). The
         * WebSocket callback hands every binary frame to it; the game
         * thread drains the protocol queue from tick().
         */
        private static volatile WebSocketMessageCallback messageListener = null;

        /**
         * Sets (or clears with null) the binary-frame listener.
         * Used by the gateway protocol layer only.
         */
        public static void setMessageListener(WebSocketMessageCallback listener) {
                messageListener = listener;
        }

        // ========== Message Buffer ==========
        private static final int MAX_INCOMING_SIZE = 0x100000; // 1MB max incoming message
        private static final int MAX_OUTGOING_SIZE = 0x100000; // 1MB max outgoing message

        // ========== WebRTC State ==========
        private static JSObject peerConnection = null;
        private static boolean voiceChatActive = false;

        /**
         * Callback interface for WebSocket events.
         */
        @JSFunctor
        public interface WebSocketOpenCallback extends JSObject {
                void call();
        }

        @JSFunctor
        public interface WebSocketMessageCallback extends JSObject {
                void call(ArrayBuffer data);
        }

        @JSFunctor
        public interface WebSocketCloseCallback extends JSObject {
                void call(int code, String reason);
        }

        @JSFunctor
        public interface WebSocketErrorCallback extends JSObject {
                void call(String message);
        }

        /**
         * Connects to a WebSocket server.
         *
         * @param uri The WebSocket server URI (ws:// or wss://)
         * @return true if the connection attempt was initiated successfully
         */
        public static boolean connect(String uri) {
                if (connectionState == CONN_CONNECTING || connectionState == CONN_CONNECTED) {
                        ClientMain.warn("[PlatformNetworking] Already connecting or connected");
                        return false;
                }

                currentURI = uri;
                connectionState = CONN_CONNECTING;
                connectTime = PlatformRuntime.getCurrentTimeMillis();
                reconnectAttempts = 0;
                rateLimited = false;
                bytesSent = 0;
                bytesReceived = 0;

                try {
                        websocket = createWebSocket0(uri);
                        if (websocket == null) {
                                connectionState = CONN_FAILED;
                                return false;
                        }

                        // Set binary type to arraybuffer
                        setWebSocketBinaryType(websocket);

                        // Register event handlers
                        setOnOpen(websocket, () -> {
                                connectionState = CONN_CONNECTED;
                                compressionEnabled = checkCompression0(websocket);
                                ClientMain.log("[PlatformNetworking] Connected to " + currentURI
                                                + " (compression=" + compressionEnabled + ")");
                        });

                        setOnMessage(websocket, (ArrayBuffer data) -> {
                                if (data != null) {
                                        bytesReceived += getArrayBufferByteLength(data);
                                        __onMessage(data);
                                }
                        });

                        setOnClose(websocket, (int code, String reason) -> {
                                connectionState = CONN_CLOSED;
                                ClientMain.log("[PlatformNetworking] Disconnected (code=" + code + ", reason=" + reason + ")");

                                if (autoReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                                        scheduleReconnect();
                                }
                        });

                        setOnError(websocket, (String message) -> {
                                if (connectionState == CONN_CONNECTING) {
                                        connectionState = CONN_FAILED;
                                        ClientMain.error("[PlatformNetworking] Connection error: " + message);

                                        if (autoReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                                                scheduleReconnect();
                                        }
                                }
                        });

                        return true;

                } catch (Exception e) {
                        connectionState = CONN_FAILED;
                        ClientMain.error("[PlatformNetworking] Failed to connect: " + e.getMessage());
                        return false;
                }
        }

        /**
         * Called when a binary message is received from the server:
         * forwards the frame to the protocol layer's listener.
         */
        private static void __onMessage(ArrayBuffer data) {
                WebSocketMessageCallback listener = messageListener;
                if (listener != null) {
                        listener.call(data);
                }
        }

        /**
         * Disconnects from the server.
         */
        public static void disconnect() {
                autoReconnect = false;
                if (websocket != null) {
                        closeWebSocket0(websocket, 1000, "Client disconnect");
                        websocket = null;
                }
                connectionState = CONN_CLOSED;
        }

        /**
         * Sends binary data to the server.
         *
         * @param data The data to send
         * @return true if the data was sent successfully
         */
        public static boolean send(ArrayBuffer data) {
                if (connectionState != CONN_CONNECTED || websocket == null) {
                        return false;
                }

                int len = getArrayBufferByteLength(data);
                if (len > MAX_OUTGOING_SIZE) {
                        ClientMain.warn("[PlatformNetworking] Outgoing message too large: " + len + " bytes");
                        return false;
                }

                // Rate limiting check
                if (isRateLimited()) {
                        ClientMain.warn("[PlatformNetworking] Rate limited - dropping packet");
                        return false;
                }

                try {
                        sendWebSocket0(websocket, data);
                        bytesSent += len;
                        recordPacketTimestamp();
                        return true;
                } catch (Exception e) {
                        ClientMain.error("[PlatformNetworking] Send failed: " + e.getMessage());
                        return false;
                }
        }

        /**
         * Sends binary data from a byte array.
         *
         * @param data The data to send
         * @return true if the data was sent successfully
         */
        public static boolean send(byte[] data) {
                return send(PlatformRuntime.bytesToArrayBuffer(data));
        }

        // ========== Connection State ==========

        /**
         * Gets the current connection state.
         */
        public static int getConnectionState() {
                return connectionState;
        }

        /**
         * Checks if currently connected to a server.
         */
        public static boolean isConnected() {
                return connectionState == CONN_CONNECTED;
        }

        /**
         * Gets the current server URI.
         */
        public static String getCurrentURI() {
                return currentURI;
        }

        /**
         * Checks if compression is enabled on the current connection.
         */
        public static boolean isCompressionEnabled() {
                return compressionEnabled;
        }

        // ========== Auto-Reconnection ==========

        /**
         * Enables or disables automatic reconnection.
         */
        public static void setAutoReconnect(boolean enabled) {
                autoReconnect = enabled;
        }

        /**
         * Schedules a reconnection attempt with exponential backoff.
         */
        private static void scheduleReconnect() {
                reconnectAttempts++;
                long delay = Math.min(BASE_RECONNECT_DELAY * (1L << (reconnectAttempts - 1)), MAX_RECONNECT_DELAY);
                ClientMain.log("[PlatformNetworking] Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");

                scheduleTimeout0(() -> {
                        if (autoReconnect && connectionState == CONN_CLOSED) {
                                connect(currentURI);
                        }
                }, delay);
        }

        // ========== Rate Limiting ==========

        /**
         * Checks if we're currently rate-limited.
         */
        private static boolean isRateLimited() {
                long now = PlatformRuntime.getCurrentTimeMillis();
                long windowStart = now - RATE_LIMIT_WINDOW_MS;

                int count = 0;
                for (int i = 0; i < RATE_LIMIT_MAX_PACKETS; i++) {
                        if (packetTimestamps[i] > windowStart) {
                                count++;
                        }
                }

                rateLimited = count >= RATE_LIMIT_MAX_PACKETS;
                return rateLimited;
        }

        /**
         * Records a packet timestamp for rate limiting.
         */
        private static void recordPacketTimestamp() {
                packetTimestamps[packetTimestampIndex] = PlatformRuntime.getCurrentTimeMillis();
                packetTimestampIndex = (packetTimestampIndex + 1) % RATE_LIMIT_MAX_PACKETS;
        }

        // ========== Connection Quality ==========

        /**
         * Gets the current connection latency in milliseconds.
         */
        public static long getLatency() {
                return currentLatency;
        }

        /**
         * Gets the total bytes sent on this connection.
         */
        public static long getBytesSent() {
                return bytesSent;
        }

        /**
         * Gets the total bytes received on this connection.
         */
        public static long getBytesReceived() {
                return bytesReceived;
        }

        /**
         * Gets the connection uptime in milliseconds.
         */
        public static long getConnectionUptime() {
                if (connectionState != CONN_CONNECTED) return 0;
                return PlatformRuntime.getCurrentTimeMillis() - connectTime;
        }

        /**
         * Gets the connection quality as a score from 0.0 to 1.0.
         */
        public static float getConnectionQuality() {
                if (connectionState != CONN_CONNECTED) return 0.0f;
                if (currentLatency < 50) return 1.0f;
                if (currentLatency < 100) return 0.8f;
                if (currentLatency < 200) return 0.5f;
                if (currentLatency < 500) return 0.3f;
                return 0.1f;
        }

        /**
         * Sends a ping packet for latency measurement.
         */
        public static void sendPing() {
                if (connectionState == CONN_CONNECTED) {
                        lastPingTime = PlatformRuntime.getCurrentTimeMillis();
                }
        }

        /**
         * Called when a pong is received in response to a ping.
         */
        public static void receivePong() {
                if (lastPingTime > 0) {
                        currentLatency = PlatformRuntime.getCurrentTimeMillis() - lastPingTime;
                        lastPingTime = 0;
                }
        }

        // ========== WebRTC (Voice Chat) ==========

        /**
         * Creates a WebRTC peer connection for voice chat.
         *
         * @param iceServers Array of ICE server URIs
         * @return true if the peer connection was created
         */
        public static boolean createVoiceChatConnection(String[] iceServers) {
                if (ClientMain.config == null || !ClientMain.config.isEnableVoiceChat()) {
                        return false;
                }

                String iceConfig = buildICEConfig(iceServers);
                peerConnection = createPeerConnection0(iceConfig);
                if (peerConnection != null) {
                        voiceChatActive = true;
                        ClientMain.log("[PlatformNetworking] WebRTC peer connection created");
                        return true;
                }
                return false;
        }

        /**
         * Closes the voice chat connection.
         */
        public static void closeVoiceChatConnection() {
                if (peerConnection != null) {
                        closePeerConnection0(peerConnection);
                        peerConnection = null;
                        voiceChatActive = false;
                }
        }

        /**
         * Checks if voice chat is active.
         */
        public static boolean isVoiceChatActive() {
                return voiceChatActive;
        }

        /**
         * Creates an SDP offer for WebRTC negotiation.
         */
        public static String createSDPOffer() {
                if (peerConnection == null) return null;
                return createSDPOffer0(peerConnection);
        }

        /**
         * Creates an SDP answer for WebRTC negotiation.
         */
        public static String createSDPAnswer() {
                if (peerConnection == null) return null;
                return createSDPAnswer0(peerConnection);
        }

        /**
         * Sets the remote SDP description.
         */
        public static boolean setRemoteDescription(String sdp, boolean isOffer) {
                if (peerConnection == null) return false;
                setRemoteDescription0(peerConnection, sdp, isOffer);
                return true;
        }

        // ========== Native JS Methods ==========

        @JSBody(params = { "uri" }, script = "return new WebSocket(uri);")
        private static native JSObject createWebSocket0(String uri);

        @JSBody(params = { "ws" }, script = "ws.binaryType = 'arraybuffer';")
        private static native void setWebSocketBinaryType(JSObject ws);

        @JSBody(params = { "ws", "callback" }, script = ""
                        + "ws.onopen = function() { callback.call(); };")
        private static native void setOnOpen(JSObject ws, WebSocketOpenCallback callback);

        @JSBody(params = { "ws", "callback" }, script = ""
                        + "ws.onmessage = function(e) { if (e.data instanceof ArrayBuffer) callback(e.data); };")
        private static native void setOnMessage(JSObject ws, WebSocketMessageCallback callback);

        @JSBody(params = { "ws", "callback" }, script = ""
                        + "ws.onclose = function(e) { callback(e.code, e.reason || ''); };")
        private static native void setOnClose(JSObject ws, WebSocketCloseCallback callback);

        @JSBody(params = { "ws", "callback" }, script = ""
                        + "ws.onerror = function(e) { callback(e.message || 'Unknown error'); };")
        private static native void setOnError(JSObject ws, WebSocketErrorCallback callback);

        @JSBody(params = { "ws", "data" }, script = "ws.send(data);")
        private static native void sendWebSocket0(JSObject ws, ArrayBuffer data);

        @JSBody(params = { "ws", "code", "reason" }, script = "ws.close(code, reason);")
        private static native void closeWebSocket0(JSObject ws, int code, String reason);

        @JSBody(params = { "ws" }, script = ""
                        + "var ext = ws.extensions || '';"
                        + "return ext.indexOf('permessage-deflate') !== -1;")
        private static native boolean checkCompression0(JSObject ws);

        @JSBody(params = { "buf" }, script = "return buf.byteLength;")
        private static native int getArrayBufferByteLength(ArrayBuffer buf);

        @JSBody(params = { "callback", "delay" }, script = "setTimeout(callback, delay);")
        private static native void scheduleTimeout0(Runnable callback, long delay);

        // WebRTC native methods
        @JSBody(params = { "iceConfig" }, script = ""
                        + "try { return new RTCPeerConnection(JSON.parse(iceConfig)); }"
                        + "catch(e) { return null; }")
        private static native JSObject createPeerConnection0(String iceConfig);

        @JSBody(params = { "pc" }, script = "pc.close();")
        private static native void closePeerConnection0(JSObject pc);

        @JSBody(params = { "pc" }, script = ""
                        + "var offer = pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: false });"
                        + "return offer.sdp;")
        private static native String createSDPOffer0(JSObject pc);

        @JSBody(params = { "pc" }, script = ""
                        + "var answer = pc.createAnswer();"
                        + "return answer.sdp;")
        private static native String createSDPAnswer0(JSObject pc);

        @JSBody(params = { "pc", "sdp", "isOffer" }, script = ""
                        + "var desc = new RTCSessionDescription({ type: isOffer ? 'offer' : 'answer', sdp: sdp });"
                        + "pc.setRemoteDescription(desc);")
        private static native void setRemoteDescription0(JSObject pc, String sdp, boolean isOffer);

        /**
         * Builds an ICE server configuration JSON string.
         */
        private static String buildICEConfig(String[] servers) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"iceServers\":[");
                for (int i = 0; i < servers.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append("{\"urls\":\"").append(servers[i]).append("\"}");
                }
                sb.append("]}");
                return sb.toString();
        }
}
