package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.Consumer;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McBridge;
import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;

/**
 * EaglerCraft 26.1.2 - one logged-in Eagler WebSocket session on the Velocity
 * gateway.
 *
 * <p>Wire-protocol parity milestone #2: the session owns the per-player MC
 * bridge ({@link McBridge}) into the backend server resolved from Velocity's
 * registry, relays system chat to the browser (0x05) and client chat to the
 * backend, and tears everything down on kick/close.</p>
 *
 * <p><b>Scope note:</b> unlike the Bungee plugin (whose
 * {@code EaglerPlayerSession} is a full {@code ProxiedPlayer}), this class is
 * not a Velocity {@code Player}. Velocity's API offers no sanctioned path to
 * register an externally-authored connection as a {@code Player}, and the
 * interface is far too large to fake safely; full player injection into
 * Velocity's connection map is deferred to a later milestone and tracked in
 * the gateway docs. Until then the session registry below is authoritative
 * for {@code /eagler} status and duplicate-login handling.</p>
 */
public final class VelocityEaglerSession {

    static final byte PKT_KICK = (byte) 0xFF;
    static final byte PKT_CHAT = 0x05;

    /** Abstraction over the WS connection (mirrors the Bungee gateway). */
    public interface FrameSink {
        void send(ByteBuffer frame);

        boolean isOpen();

        void close(String reason);
    }

    private final String name;
    private final UUID uuid;
    private final FrameSink sink;
    private final VelocitySessionRegistry registry;
    private final McPacketIds mcIds;
    private final String virtualHost;
    private final int connectTimeoutMs;
    private final Consumer<String> logInfo;
    private final Consumer<String> logWarn;

    private volatile McBridge bridge;

    /** Chat sent while the backend bridge is still logging in; flushed on connect. */
    private final ArrayDeque<String> pendingChat = new ArrayDeque<>();
    private static final int MAX_PENDING_CHAT = 64;

    /** Called by the listener once the backend login finished (or failed). */
    public interface ConnectCallback {
        void done(boolean success);
    }

    public VelocityEaglerSession(String name, FrameSink sink, InetSocketAddress remote,
            VelocitySessionRegistry registry, McPacketIds mcIds, String virtualHost,
            int connectTimeoutMs, Consumer<String> logInfo, Consumer<String> logWarn) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        this.sink = sink;
        this.registry = registry;
        this.mcIds = mcIds;
        this.virtualHost = virtualHost;
        this.connectTimeoutMs = connectTimeoutMs;
        this.logInfo = logInfo;
        this.logWarn = logWarn;
    }

    public String getName() {
        return name;
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public boolean isConnected() {
        return sink.isOpen() && bridge != null && bridge.isConnected();
    }

    // ==================== milestone 2: server forwarding ====================

    /**
     * Connects this session to a backend server from Velocity's registry via
     * a real MC login. Backend system chat flows to the browser as 0x05
     * frames; backend kicks end the session. The callback fires once the
     * backend login finished (success or failure), so the listener can time
     * its 0x04 ST_OK to the moment the bridge is actually live.
     */
    public void connectTo(InetSocketAddress target, String targetName, ConnectCallback callback) {
        McBridge old = bridge;
        if (old != null) {
            old.close("switching server");
        }
        McBridge.Listener listener = new McBridge.Listener() {
            private boolean cbFired;

            @Override
            public void onStateChange(McBridge b, McBridge.State s, String detail) {
                if (s == McBridge.State.CONNECTED) {
                    logInfo.accept("eagler player " + name + " connected to server " + targetName);
                    sendChatFrame("Connected to server " + targetName);
                    flushPendingChat();
                    if (!cbFired) {
                        cbFired = true;
                        if (callback != null) {
                            callback.done(true);
                        }
                    }
                } else if (s == McBridge.State.CLOSED && !cbFired) {
                    cbFired = true;
                    if (callback != null) {
                        callback.done(false);
                    }
                }
            }

            @Override
            public void onChat(McBridge b, String text) {
                sendChatFrame(text);
            }

            @Override
            public void onKicked(McBridge b, String reason) {
                disconnect("Kicked from " + targetName + ": " + reason);
            }

            @Override
            public void onError(McBridge b, String message) {
                logWarn.accept("mc bridge error for " + name + ": " + message);
            }
        };
        McBridge b = new McBridge(target, mcIds, virtualHost, name, uuid,
                connectTimeoutMs, listener);
        bridge = b;
        logInfo.accept("eagler player " + name + " connecting to server " + targetName
                + " (" + target + ")");
        b.start();
    }

    // ==================== chat + teardown ====================

    public void chat(String message) {
        McBridge b = bridge;
        if (b == null || !b.isConnected()) {
            // the backend login is still in flight; queue instead of dropping so
            // a client that chats right after its 0x04 result is not racing
            synchronized (pendingChat) {
                if (pendingChat.size() >= MAX_PENDING_CHAT) {
                    pendingChat.poll();
                }
                pendingChat.add(message);
            }
            logInfo.accept("chat from " + name + " buffered (backend still connecting)");
            return;
        }
        if (!b.sendChat(message)) {
            logWarn.accept("chat from " + name + " dropped (send failed)");
        }
    }

    /** Sends everything buffered while the backend was connecting. */
    private void flushPendingChat() {
        synchronized (pendingChat) {
            McBridge b = bridge;
            String m;
            while (b != null && b.isConnected() && (m = pendingChat.poll()) != null) {
                if (!b.sendChat(m)) {
                    break;
                }
            }
        }
    }

    public void disconnect(String reason) {
        teardown("disconnect");
        sendKickFrame(reason == null ? "Disconnected" : reason);
        sink.close("kicked");
    }

    public void teardown(String detail) {
        McBridge b = bridge;
        if (b != null) {
            b.close(detail);
        }
        registry.unregister(this);
    }

    // ==================== frames ====================

    private void sendChatFrame(String text) {
        if (!sink.isOpen()) {
            return;
        }
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 2 + b.length);
        out.put(PKT_CHAT);
        out.putShort((short) b.length);
        out.put(b);
        out.flip();
        sink.send(out);
    }

    private void sendKickFrame(String reason) {
        byte[] b = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 2 + b.length);
        out.put(PKT_KICK);
        out.putShort((short) b.length);
        out.put(b);
        out.flip();
        sink.send(out);
    }
}
