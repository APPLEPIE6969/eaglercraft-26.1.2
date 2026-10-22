package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;

/**
 * Embedded Eagler WebSocket listener for EaglercraftXVelocity — milestone #2
 * wire-protocol parity with the Bungee gateway.
 *
 * <p>Wire protocol (binary frames only, big-endian u16 string lengths):</p>
 * <pre>
 *   C→S  0x01 handshake   [0x01][u16 n][n bytes client brand][u16 n][n bytes username]
 *   S→C  0x01 accept      [0x01][u16 n][n bytes server brand][u16 n][n bytes motd]
 *   C→S  0x02 ping        [0x02][opaque payload]  (echoed back verbatim)
 *   S→C  0x02 pong        [0x02][same payload]
 *   C→S  0x03 login       [0x03][u16 n][username][u16 m][auth token]
 *   S→C  0x04 login result[0x04][u8 status][u16 n][message]
 *   C→S  0x05 chat        [0x05][u16 n][utf-8 text]
 *   S→C  0x05 chat        [0x05][u16 n][utf-8 text]
 *   S→C  0xFF kick        [0xFF][u16 n][n bytes utf-8 reason]  — followed by close(1000)
 * </pre>
 *
 * <p>A completed 0x03 login creates a {@link VelocityEaglerSession} backed by
 * a real MC-protocol bridge into Velocity's server registry (see the session
 * class for the Player-injection scope note). Guardrails carried over from
 * milestone 1: handshake timeout, duplicate handshake, username validation,
 * unknown packet ids, text frames, 1 MiB frame cap (close 1009),
 * sliding-window packet rate limit, max connections.</p>
 */
public class EaglerWSListener extends WebSocketServer {

    /** Hard cap on a single inbound frame; larger frames are closed with 1009. */
    public static final int MAX_FRAME_BYTES = 0x100000; // 1 MiB, matches client MAX_INCOMING_SIZE

    static final byte PKT_HANDSHAKE = 0x01;
    static final byte PKT_PING = 0x02;
    static final byte PKT_LOGIN = 0x03;
    static final byte PKT_LOGIN_RESULT = 0x04;
    static final byte PKT_CHAT = 0x05;
    static final byte PKT_KICK = (byte) 0xFF;

    /** 0x04 status codes (same values as the Bungee gateway). */
    static final int ST_OK = 0;
    static final int ST_INVALID_USERNAME = 1;
    static final int ST_NAME_TAKEN = 2;
    static final int ST_FORWARDING_DISABLED = 3;
    static final int ST_REJECTED = 4;

    /** Vanilla username charset, enforced before a session is created. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** Creates the session object for a logged-in connection. */
    public interface SessionFactory {
        VelocityEaglerSession create(String username, VelocityEaglerSession.FrameSink sink,
                InetSocketAddress remote);
    }

    /** Per-connection state machine data. */
    private static final class ConnState {
        Thread handshakeTimeout;
        volatile boolean handshaken;
        volatile long pingStart;
        volatile VelocityEaglerSession session;
    }

    private final String motd;
    private final String serverBrand;
    private final int maxConnections;
    private final long handshakeTimeoutMs;
    private final int packetsPerMinute;
    private final boolean verbose;
    private final Logger log;

    private final VelocitySessionRegistry registry;
    private final SessionFactory sessionFactory;
    private final Supplier<InetSocketAddress> defaultServerSupplier;
    private final McPacketIds mcIds;
    private final String virtualHost;
    private final int bridgeConnectTimeoutMs;

    private final Set<WebSocket> conns = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger totalAccepted = new AtomicInteger();

    private static final ConcurrentHashMap<WebSocket, long[]> rateWindows = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<WebSocket, int[]> rateCursors = new ConcurrentHashMap<>();

    public EaglerWSListener(int port, String motd, String serverBrand, int maxConnections,
            long handshakeTimeoutMs, int packetsPerMinute, boolean verbose, Logger log,
            VelocitySessionRegistry registry, SessionFactory sessionFactory,
            Supplier<InetSocketAddress> defaultServerSupplier, McPacketIds mcIds,
            String virtualHost, int bridgeConnectTimeoutMs) {
        super(new InetSocketAddress(port));
        this.motd = motd;
        this.serverBrand = serverBrand;
        this.maxConnections = maxConnections;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.packetsPerMinute = packetsPerMinute;
        this.verbose = verbose;
        this.log = log;
        this.registry = registry;
        this.sessionFactory = sessionFactory;
        this.defaultServerSupplier = defaultServerSupplier;
        this.mcIds = mcIds;
        this.virtualHost = virtualHost;
        this.bridgeConnectTimeoutMs = bridgeConnectTimeoutMs;
        setConnectionLostTimeout(30);
        // Let the listener re-bind while old connections from a previous run
        // are still draining through TIME_WAIT (proxy restarts, test reruns).
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        log.info("Eagler WS listener on ws://0.0.0.0:" + getPort()
                + " (maxConn=" + maxConnections + ", hsTimeout=" + handshakeTimeoutMs + "ms)");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (conns.size() >= maxConnections) {
            kick(conn, "server full (" + maxConnections + " connections)");
            return;
        }
        conns.add(conn);
        rateWindows.remove(conn);
        rateCursors.remove(conn);
        ConnState st = new ConnState();
        conn.setAttachment(st);
        scheduleHandshakeTimeout(conn, st);
        if (verbose) {
            log.info("open " + conn.getRemoteSocketAddress()
                    + " resource=" + handshake.getResourceDescriptor());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        conns.remove(conn);
        rateWindows.remove(conn);
        rateCursors.remove(conn);
        ConnState st = stateOf(conn);
        if (st != null) {
            cancelHandshakeTimeout(conn, st);
            VelocityEaglerSession session = st.session;
            if (session != null) {
                st.session = null;
                session.teardown("ws closed (code=" + code + ")");
            }
        }
        if (verbose) {
            log.info("close " + conn.getRemoteSocketAddress() + " code=" + code
                    + " remote=" + remote + " reason=" + reason);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // The client platform layer (PlatformNetworking) uses arraybuffer binary
        // frames exclusively; a text frame means we are not talking to our client.
        kick(conn, "protocol violation: text frames are not accepted");
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        int len = message.remaining();
        if (len < 1) {
            kick(conn, "protocol violation: empty frame");
            return;
        }
        if (len > MAX_FRAME_BYTES) {
            log.warn("oversize frame ({} B) from {} - closing 1009",
                    len, conn.getRemoteSocketAddress());
            conns.remove(conn);
            conn.close(1009, "frame exceeds 1 MiB limit");
            return;
        }
        if (!rateAllow(conn)) {
            kick(conn, "rate limit exceeded");
            return;
        }

        ConnState st = stateOf(conn);
        if (st == null) {
            kick(conn, "protocol violation: frame before listener state ready");
            return;
        }

        byte id = message.get();
        switch (id) {
            case PKT_HANDSHAKE:
                handleHandshake(conn, st, message);
                break;
            case PKT_PING:
                handlePing(conn, st, message);
                break;
            case PKT_LOGIN:
                handleLogin(conn, st, message);
                break;
            case PKT_CHAT:
                handleChat(conn, st, message);
                break;
            default:
                kick(conn, "protocol violation: unknown packet id 0x" + String.format("%02x", id));
                break;
        }
    }

    // ==================== packet handlers ====================

    private void handleHandshake(WebSocket conn, ConnState st, ByteBuffer message) {
        if (st.handshaken || st.session != null) {
            kick(conn, "protocol violation: duplicate handshake");
            return;
        }
        String clientBrand = readU16String(message);
        String username = readU16String(message);
        if (clientBrand == null || username == null) {
            kick(conn, "malformed handshake");
            return;
        }
        st.handshaken = true;
        cancelHandshakeTimeout(conn, st);
        totalAccepted.incrementAndGet();
        log.info("handshake from " + conn.getRemoteSocketAddress()
                + " brand=\"" + clientBrand + "\" username=\"" + username + "\"");
        // Size from UTF-8 byte lengths, not String.length(), which
        // counts chars and undercounts multibyte!
        byte[] brandBytes = serverBrand.getBytes(StandardCharsets.UTF_8);
        byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 2 + brandBytes.length + 2 + motdBytes.length);
        out.put(PKT_HANDSHAKE);
        putU16Bytes(out, brandBytes);
        putU16Bytes(out, motdBytes);
        out.flip();
        conn.send(out);
    }

    private void handlePing(WebSocket conn, ConnState st, ByteBuffer message) {
        if (!st.handshaken) {
            kick(conn, "ping before handshake");
            return;
        }
        ByteBuffer out = ByteBuffer.allocate(message.remaining() + 1);
        out.put(PKT_PING);
        out.put(message);
        out.flip();
        conn.send(out);
    }

    /** Milestone 2: session creation + MC bridge into Velocity's registry. */
    private void handleLogin(WebSocket conn, ConnState st, ByteBuffer message) {
        if (!st.handshaken) {
            kick(conn, "login before handshake");
            return;
        }
        if (st.session != null) {
            kick(conn, "protocol violation: duplicate login");
            return;
        }
        String username = readU16String(message);
        String token = readU16String(message); // reserved for auth plugins; may be empty
        if (username == null || token == null) {
            loginResult(conn, ST_INVALID_USERNAME, "malformed login");
            return;
        }
        username = username.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            loginResult(conn, ST_INVALID_USERNAME,
                    "invalid username (allowed: A-Z a-z 0-9 _, max 16 chars)");
            return;
        }
        InetSocketAddress target = defaultServerSupplier != null ? defaultServerSupplier.get() : null;
        if (target == null) {
            loginResult(conn, ST_FORWARDING_DISABLED,
                    "player forwarding is disabled or the default server is not registered");
            return;
        }
        if (registry.count() >= maxConnections) {
            loginResult(conn, ST_REJECTED, "too many Eagler players online");
            return;
        }

        VelocityEaglerSession session;
        try {
            session = sessionFactory.create(username, new WsSink(conn), conn.getRemoteSocketAddress());
        } catch (Exception e) {
            log.warn("session creation failed for {}: {}", username, e.toString());
            loginResult(conn, ST_REJECTED, "session creation failed");
            return;
        }

        String conflict = registry.register(session);
        if (conflict != null) {
            loginResult(conn, ST_NAME_TAKEN, conflict);
            return;
        }
        st.session = session;

        log.info("eagler login: {} from {} -> server {}:{}", username,
                conn.getRemoteSocketAddress(), target.getHostString(), target.getPort());
        // The MC bridge runs asynchronously: the 0x04 ST_OK is only sent once
        // the backend accepts the login, so "status 0" means "session + MC
        // bridge established" (see PROTOCOL.md).
        session.connectTo(target, target.getHostString(), (success) -> {
            if (success) {
                byte[] msg = ("joined server " + target.getHostString() + ":" + target.getPort())
                        .getBytes(StandardCharsets.UTF_8);
                ByteBuffer out = ByteBuffer.allocate(1 + 1 + 2 + msg.length);
                out.put(PKT_LOGIN_RESULT);
                out.put((byte) ST_OK);
                putU16Bytes(out, msg);
                out.flip();
                conn.send(out);
            } else {
                loginResult(conn, ST_FORWARDING_DISABLED,
                        "could not reach server " + target.getHostString() + ":" + target.getPort());
                session.teardown("backend connect failed");
            }
        });
    }

    private void handleChat(WebSocket conn, ConnState st, ByteBuffer message) {
        VelocityEaglerSession session = st.session;
        if (session == null) {
            kick(conn, "chat before login");
            return;
        }
        String text = readU16String(message);
        if (text == null || text.isEmpty()) {
            kick(conn, "malformed chat");
            return;
        }
        if (text.length() > 256) {
            text = text.substring(0, 256);
        }
        session.chat(text);
    }

    // ==================== login result ====================

    private void loginResult(WebSocket conn, int status, String message) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 1 + 2 + msg.length);
        out.put(PKT_LOGIN_RESULT);
        out.put((byte) status);
        putU16Bytes(out, msg);
        out.flip();
        conn.send(out);
        if (status != ST_OK) {
            conn.close(1000, "login rejected");
        }
    }

    // ==================== errors ====================

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            log.warn("error on {}: {}", conn.getRemoteSocketAddress(), ex.toString());
        } else {
            log.warn("listener error: {}", ex.toString());
        }
    }

    /** Number of completed handshakes since the listener started. */
    public int getTotalAccepted() {
        return totalAccepted.get();
    }

    /** Registry of logged-in sessions, for the status command. */
    public VelocitySessionRegistry getRegistry() {
        return registry;
    }

    // ==================== helpers ====================

    private static ConnState stateOf(WebSocket conn) {
        try {
            Object att = conn.getAttachment();
            return att instanceof ConnState ? (ConnState) att : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void kick(WebSocket conn, String reason) {
        if (verbose) {
            log.info("kick {}: {}", conn.getRemoteSocketAddress(), reason);
        }
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(3 + reasonBytes.length);
        out.put(PKT_KICK);
        out.putShort((short) reasonBytes.length);
        out.put(reasonBytes);
        out.flip();
        try {
            conn.send(out);
        } catch (Exception ignored) {
        }
        conn.close(1000, "kicked");
    }

    private void scheduleHandshakeTimeout(WebSocket conn, ConnState st) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(handshakeTimeoutMs);
            } catch (InterruptedException ignored) {
                return;
            }
            if (conn.isOpen() && !st.handshaken) {
                kick(conn, "handshake timeout (" + handshakeTimeoutMs + "ms)");
            }
        }, "eagler-hs-timeout");
        t.setDaemon(true);
        st.handshakeTimeout = t;
        t.start();
    }

    private void cancelHandshakeTimeout(WebSocket conn, ConnState st) {
        Thread t = st.handshakeTimeout;
        if (t != null) {
            t.interrupt();
            st.handshakeTimeout = null;
        }
    }

    private boolean rateAllow(WebSocket conn) {
        long now = System.currentTimeMillis();
        // Window buffer is one slot LARGER than the limit so the
        // (packetsPerMinute + 1)-th frame within the window trips the limit
        // (a buffer exactly the size of the limit would saturate and never fire).
        long[] stamps = rateWindows.computeIfAbsent(conn, k -> new long[packetsPerMinute + 1]);
        int[] cur = rateCursors.computeIfAbsent(conn, k -> new int[1]);
        int count = 0;
        synchronized (stamps) {
            stamps[cur[0] % stamps.length] = now;
            cur[0]++;
            for (long ts : stamps) {
                if (ts > now - 60_000L) {
                    count++;
                }
            }
        }
        return count <= packetsPerMinute;
    }

    private static void putU16Bytes(ByteBuffer out, byte[] b) {
        if (b.length > 0xFFFF) {
            throw new IllegalArgumentException("string too long");
        }
        out.putShort((short) b.length);
        out.put(b);
    }

    private static String readU16String(ByteBuffer in) {
        if (in.remaining() < 2) {
            return null;
        }
        int len = in.getShort() & 0xFFFF;
        if (in.remaining() < len) {
            return null;
        }
        byte[] b = new byte[len];
        in.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** FrameSink adapter over a live WebSocket. */
    private static final class WsSink implements VelocityEaglerSession.FrameSink {
        private final WebSocket conn;

        WsSink(WebSocket conn) {
            this.conn = conn;
        }

        @Override
        public void send(ByteBuffer frame) {
            conn.send(frame);
        }

        @Override
        public boolean isOpen() {
            return conn.isOpen();
        }

        @Override
        public void close(String reason) {
            conn.close(1000, "kicked");
        }
    }
}
