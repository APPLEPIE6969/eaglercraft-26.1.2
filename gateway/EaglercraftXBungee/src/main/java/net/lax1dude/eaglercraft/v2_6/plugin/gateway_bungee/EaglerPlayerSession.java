package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McBridge;
import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ServerConnectRequest;
import net.md_5.bungee.api.SkinConfiguration;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.score.Scoreboard;

/**
 * EaglerCraft 26.1.2 - a fully-fledged {@link ProxiedPlayer} backed by an
 * Eagler WebSocket session and a {@link McBridge} into the proxy's server
 * registry. This is gateway milestone #2.
 *
 * <p>Lifecycle: the WS listener completes the Eagler handshake (0x01) and the
 * login (0x03). The listener creates one session per logged-in player and
 * registers it in {@link EaglerSessionRegistry}; the plugin additionally
 * tries to register the instance in the proxy runtime's player collection
 * (reflection, best effort) and fires {@code PostLoginEvent}. The session
 * then connects to the default backend server: a real TCP login (handshake,
 * Login Start, Set Compression, Login Success) is performed by the
 * {@link McBridge} and, once the backend accepts, the player reports
 * {@link #getServer()} as a live {@link Server}, {@code ServerConnectedEvent}
 * and {@code ServerSwitchEvent} are fired, and system chat from the backend
 * flows to the browser as 0x05 chat frames (client chat goes the other
 * way).</p>
 *
 * <p>Unsupported surface is documented inline and degrades gracefully:
 * titles/tab-header/scoreboard traffic is accepted but not relayed (the
 * Eagler client has no UI for them yet), plugin messaging is a no-op, and
 * {@link #unsafe()} returns a sink that drops packets instead of throwing.</p>
 */
public final class EaglerPlayerSession implements ProxiedPlayer {

    /** Packet ids on the Eagler WS side (see PROTOCOL.md). */
    static final byte PKT_KICK = (byte) 0xFF;
    static final byte PKT_CHAT = 0x05;

    /**
     * Abstraction over the WS connection so sessions can also run in
     * standalone harnesses without a proxy or WebSocket internals.
     */
    public interface FrameSink {
        void send(ByteBuffer frame);

        boolean isOpen();

        void close(String reason);
    }

    private final String name;
    private final UUID uuid;
    private final FrameSink sink;
    private final InetSocketAddress remote;
    private final EaglerSessionRegistry registry;
    private final ProxyServerAccess proxy;
    private final McPacketIds mcIds;
    private final String virtualHost;
    private final int connectTimeoutMs;
    private final Consumer<String> logInfo;
    private final Consumer<String> logWarn;
    private final EaglerPendingConnection pendingConnection;
    private final Scoreboard scoreboard = new Scoreboard();

    private volatile McBridge bridge;
    private volatile Server serverConnection;
    private volatile ServerInfo reconnectServer;
    private volatile int lastPing;

    /** Chat sent while the backend bridge is still logging in; flushed on connect. */
    private final ArrayDeque<String> pendingChat = new ArrayDeque<>();
    private static final int MAX_PENDING_CHAT = 64;

    /** Tracks whether the proxy runtime knows this player (best effort). */
    private volatile boolean registeredInProxyRuntime;

    public EaglerPlayerSession(String name, FrameSink sink, InetSocketAddress remote,
            EaglerSessionRegistry registry, ProxyServerAccess proxy, McPacketIds mcIds,
            String virtualHost, int connectTimeoutMs,
            Consumer<String> logInfo, Consumer<String> logWarn) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        this.sink = sink;
        this.remote = remote;
        this.registry = registry;
        this.proxy = proxy;
        this.mcIds = mcIds;
        this.virtualHost = virtualHost;
        this.connectTimeoutMs = connectTimeoutMs;
        this.logInfo = logInfo;
        this.logWarn = logWarn;
        this.pendingConnection = new EaglerPendingConnection();
    }

    // ==================== milestone 2: server forwarding ====================

    /**
     * Connects the Eagler player to a backend server from the proxy registry.
     * Fires {@code ServerConnectEvent} (cancellable), then performs the real
     * MC login via {@link McBridge}; on success {@code ServerConnectedEvent}
     * and {@code ServerSwitchEvent} follow.
     */
    public void connectTo(ServerInfo target, ServerConnectEvent.Reason reason, Callback<Boolean> callback) {
        if (target == null) {
            completeCallback(callback, false);
            return;
        }
        McBridge old = bridge;
        if (old != null) {
            old.close("switching server");
        }
        if (proxy != null) {
            ServerConnectEvent event = new ServerConnectEvent(this, target, reason);
            proxy.callEvent(event);
            if (event.isCancelled()) {
                logInfo.accept("connect of " + name + " to " + target.getName() + " cancelled by plugin");
                completeCallback(callback, false);
                return;
            }
        }
        final AtomicBoolean cbDone = new AtomicBoolean(callback == null);
        McBridge.Listener listener = new McBridge.Listener() {
            @Override
            public void onStateChange(McBridge b, McBridge.State s, String detail) {
                if (s == McBridge.State.CONNECTED) {
                    serverConnection = new EaglerServerConnection(b, target);
                    if (proxy != null) {
                        proxy.callEvent(new ServerConnectedEvent(EaglerPlayerSession.this, serverConnection));
                        proxy.callEvent(new ServerSwitchEvent(EaglerPlayerSession.this, null));
                    }
                    logInfo.accept("eagler player " + name + " is now connected to server " + target.getName());
                    flushPendingChat();
                    if (cbDone.compareAndSet(false, true)) {
                        completeCallback(callback, true);
                    }
                } else if (s == McBridge.State.CLOSED && !cbDone.get()) {
                    if (cbDone.compareAndSet(false, true)) {
                        completeCallback(callback, false);
                    }
                }
            }

            @Override
            public void onChat(McBridge b, String text) {
                sendChatFrame(text);
            }

            @Override
            public void onKicked(McBridge b, String reason) {
                disconnect("Kicked from " + target.getName() + ": " + reason);
            }

            @Override
            public void onError(McBridge b, String message) {
                logWarn.accept("mc bridge error for " + name + ": " + message);
            }
        };
        McBridge b = new McBridge(target.getAddress(),
                mcIds, virtualHost, name, uuid, connectTimeoutMs, listener);
        bridge = b;
        logInfo.accept("eagler player " + name + " connecting to server " + target.getName()
                + " (" + target.getAddress() + ")");
        b.start();
    }

    /** Convenience: connect with no callback. */
    public void connectTo(ServerInfo target, ServerConnectEvent.Reason reason) {
        connectTo(target, reason, null);
    }

    private static void completeCallback(Callback<Boolean> cb, boolean result) {
        if (cb != null) {
            try {
                cb.done(result, null);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== ProxiedPlayer: identity ====================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public void setDisplayName(String displayName) {
        // no client UI for display names yet
    }

    @Override
    public String getUUID() {
        return uuid.toString();
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public PendingConnection getPendingConnection() {
        return pendingConnection;
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    // ==================== ProxiedPlayer: server ====================

    @Override
    public Server getServer() {
        return serverConnection;
    }

    @Override
    public ServerInfo getReconnectServer() {
        return reconnectServer;
    }

    @Override
    public void setReconnectServer(ServerInfo server) {
        this.reconnectServer = server;
    }

    @Override
    public void connect(ServerInfo target) {
        connectTo(target, ServerConnectEvent.Reason.PLUGIN, null);
    }

    @Override
    public void connect(ServerInfo target, ServerConnectEvent.Reason reason) {
        connectTo(target, reason, null);
    }

    @Override
    public void connect(ServerInfo target, Callback<Boolean> callback) {
        connectTo(target, ServerConnectEvent.Reason.PLUGIN, callback);
    }

    @Override
    public void connect(ServerInfo target, Callback<Boolean> callback, ServerConnectEvent.Reason reason) {
        connectTo(target, reason, callback);
    }

    @Override
    public void connect(ServerConnectRequest request) {
        connectTo(request.getTarget(), request.getReason(), new Callback<Boolean>() {
            @Override
            public void done(Boolean result, Throwable error) {
                Callback<net.md_5.bungee.api.ServerConnectRequest.Result> cb = request.getCallback();
                if (cb != null) {
                    cb.done(result != null && result
                            ? net.md_5.bungee.api.ServerConnectRequest.Result.SUCCESS
                            : net.md_5.bungee.api.ServerConnectRequest.Result.FAIL, error);
                }
            }
        });
    }

    @Override
    public int getPing() {
        return lastPing;
    }

    /** Updated by the WS listener when a 0x02 ping/pong round-trip completes. */
    public void setLastPing(int ping) {
        this.lastPing = ping;
    }

    // ==================== ProxiedPlayer: messaging ====================

    @Override
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

    @Override
    public void sendMessage(String message) {
        sendChatFrame(message);
    }

    @Override
    public void sendMessages(String... messages) {
        for (String m : messages) {
            sendMessage(m);
        }
    }

    @Override
    public void sendMessage(BaseComponent component) {
        sendMessage(component == null ? "" : component.toPlainText());
    }

    @Override
    public void sendMessage(BaseComponent... components) {
        if (components == null || components.length == 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (BaseComponent c : components) {
            if (c != null) {
                sb.append(c.toPlainText());
            }
        }
        sendChatFrame(sb.toString());
    }

    @Override
    public void sendMessage(ChatMessageType position, BaseComponent component) {
        sendMessage(component);
    }

    @Override
    public void sendMessage(ChatMessageType position, BaseComponent... components) {
        sendMessage(components);
    }

    @Override
    public void sendMessage(UUID sender, BaseComponent component) {
        sendMessage(component);
    }

    @Override
    public void sendMessage(UUID sender, BaseComponent... components) {
        sendMessage(components);
    }

    @Override
    public void sendData(String channel, byte[] data) {
        // plugin messaging channels are not relayed to Eagler sessions yet
    }

    @Override
    public void setTabHeader(BaseComponent header, BaseComponent footer) {
        // no tab UI on the Eagler client yet
    }

    @Override
    public void setTabHeader(BaseComponent[] header, BaseComponent[] footer) {
        // no tab UI on the Eagler client yet
    }

    @Override
    public void resetTabHeader() {
        // no tab UI on the Eagler client yet
    }

    @Override
    public void sendTitle(Title title) {
        if (title != null) {
            // Titles are rendered client-side by real MC; the Eagler client has
            // no title UI yet, so the object is accepted and dropped.
        }
    }

    // ==================== ProxiedPlayer: client info (static defaults) ====

    @Override
    public Locale getLocale() {
        return Locale.US;
    }

    @Override
    public byte getViewDistance() {
        return 8;
    }

    @Override
    public ChatMode getChatMode() {
        return ChatMode.SHOWN;
    }

    @Override
    public boolean hasChatColors() {
        return true;
    }

    @Override
    public SkinConfiguration getSkinParts() {
        return FULL_SKIN;
    }

    @Override
    public MainHand getMainHand() {
        return MainHand.RIGHT;
    }

    @Override
    public boolean isForgeUser() {
        return false;
    }

    @Override
    public Map<String, String> getModList() {
        return Collections.emptyMap();
    }

    private static final SkinConfiguration FULL_SKIN = new SkinConfiguration() {
        @Override public boolean hasCape() { return true; }
        @Override public boolean hasJacket() { return true; }
        @Override public boolean hasLeftSleeve() { return true; }
        @Override public boolean hasRightSleeve() { return true; }
        @Override public boolean hasLeftPants() { return true; }
        @Override public boolean hasRightPants() { return true; }
        @Override public boolean hasHat() { return true; }
    };

    // ==================== Connection ====================

    @Override
    public InetSocketAddress getAddress() {
        return remote;
    }

    @Override
    public SocketAddress getSocketAddress() {
        return remote;
    }

    @Override
    public boolean isConnected() {
        return sink.isOpen() && (bridge == null || bridge.isConnected());
    }

    @Override
    public void disconnect(String reason) {
        teardown("disconnect: " + reason);
        sendKickFrame(reason == null ? "Disconnected" : reason);
        sink.close("kicked");
    }

    @Override
    public void disconnect(BaseComponent component) {
        disconnect(component == null ? "Disconnected" : component.toPlainText());
    }

    @Override
    public void disconnect(BaseComponent... components) {
        if (components == null || components.length == 0) {
            disconnect("Disconnected");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (BaseComponent c : components) {
            if (c != null) {
                sb.append(c.toPlainText());
            }
        }
        disconnect(sb.toString());
    }

    @Override
    public Unsafe unsafe() {
        return UNSAFE;
    }

    private static final Unsafe UNSAFE = new Unsafe() {
        @Override
        public void sendPacket(net.md_5.bungee.protocol.DefinedPacket packet) {
            // raw protocol packets cannot be routed into the Eagler WS stream;
            // callers using unsafe() get a documented black hole instead of a crash
        }
    };

    // ==================== CommandSender ====================

    @Override
    public Collection<String> getGroups() {
        return Collections.emptyList();
    }

    @Override
    public void addGroups(String... groups) {
        // static permission model: groups are not persisted for Eagler sessions
    }

    @Override
    public void removeGroups(String... groups) {
        // static permission model
    }

    @Override
    public boolean hasPermission(String permission) {
        return true; // Eagler sessions are trusted like proxy-local players
    }

    @Override
    public void setPermission(String permission, boolean value) {
        // static permission model
    }

    @Override
    public Collection<String> getPermissions() {
        return Collections.emptyList();
    }

    // ==================== registry + teardown ====================

    /** Marks the session as registered in the proxy runtime player set. */
    public void setRegisteredInProxyRuntime(boolean registered) {
        this.registeredInProxyRuntime = registered;
    }

    public boolean isRegisteredInProxyRuntime() {
        return registeredInProxyRuntime;
    }

    /** Unregisters from registry + proxy runtime and closes the MC bridge. */
    public void teardown(String detail) {
        McBridge b = bridge;
        if (b != null) {
            b.close(detail);
        }
        registry.unregister(this);
        if (proxy != null && registeredInProxyRuntime) {
            proxy.unregisterPlayer(this);
            registeredInProxyRuntime = false;
        }
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

    // ==================== nested API adapters ====================

    private final class EaglerPendingConnection implements PendingConnection {
        @Override public String getName() { return name; }
        @Override public int getVersion() { return mcIds.protocolVersion; }
        @Override public InetSocketAddress getVirtualHost() {
            return InetSocketAddress.createUnresolved(virtualHost, 25565);
        }
        @Override public net.md_5.bungee.api.config.ListenerInfo getListener() {
            return proxy != null ? proxy.firstListener() : null;
        }
        @Override public String getUUID() { return uuid.toString(); }
        @Override public UUID getUniqueId() { return uuid; }
        @Override public void setUniqueId(UUID id) { /* offline uuid is fixed */ }
        @Override public boolean isOnlineMode() { return false; }
        @Override public void setOnlineMode(boolean onlineMode) { /* offline gateway */ }
        @Override public boolean isLegacy() { return false; }
        @Override public InetSocketAddress getAddress() { return remote; }
        @Override public SocketAddress getSocketAddress() { return remote; }
        @Override public void disconnect(String reason) { EaglerPlayerSession.this.disconnect(reason); }
        @Override public void disconnect(BaseComponent component) { EaglerPlayerSession.this.disconnect(component); }
        @Override public void disconnect(BaseComponent... components) { EaglerPlayerSession.this.disconnect(components); }
        @Override public boolean isConnected() { return sink.isOpen(); }
        @Override public Unsafe unsafe() { return UNSAFE; }
    }

    private final class EaglerServerConnection implements Server {
        private final McBridge backingBridge;
        private final ServerInfo info;

        EaglerServerConnection(McBridge backingBridge, ServerInfo info) {
            this.backingBridge = backingBridge;
            this.info = info;
        }

        @Override public ServerInfo getInfo() { return info; }
        @Override public void sendData(String channel, byte[] data) { /* not relayed yet */ }
        @Override public InetSocketAddress getAddress() { return info.getAddress(); }
        @Override public SocketAddress getSocketAddress() {
            return new InetSocketAddress(info.getAddress().getAddress(), info.getAddress().getPort());
        }
        @Override public boolean isConnected() { return backingBridge.isConnected(); }
        @Override public void disconnect(String reason) { backingBridge.close(reason); }
        @Override public void disconnect(BaseComponent component) { backingBridge.close("disconnect"); }
        @Override public void disconnect(BaseComponent... components) { backingBridge.close("disconnect"); }
        @Override public Unsafe unsafe() { return UNSAFE; }
    }

    /** Small internal view of ProxyServer so standalone harnesses can pass null. */
    public interface ProxyServerAccess {
        void callEvent(Object event);

        void registerPlayer(ProxiedPlayer player);

        void unregisterPlayer(ProxiedPlayer player);

        net.md_5.bungee.api.config.ListenerInfo firstListener();

        Map<String, ServerInfo> servers();
    }
}
