package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Embedded Eagler WebSocket listener for EaglercraftXBungee.
 *
 * <p>Wire protocol (binary frames only, big-endian u16 string lengths):</p>
 * <pre>
 *   C→S  0x01 handshake   [0x01][u16 n][n bytes client brand][u16 n][n bytes username]
 *   S→C  0x01 accept      [0x01][u16 n][n bytes server brand][u16 n][n bytes motd]
 *   C→S  0x02 ping        [0x02][opaque payload]  (echoed back verbatim)
 *   S→C  0x02 pong        [0x02][same payload]
 *   S→C  0xFF kick        [0xFF][u16 n][n bytes utf-8 reason]  — followed by close(1000)
 * </pre>
 *
 * <p>This is the same protocol the standalone PlaceholderServer exercises and
 * the browser client's PlatformNetworking layer speaks; keeping the two in
 * sync is intentional. Guardrails: handshake timeout, duplicate handshake,
 * username validation, unknown packet ids, text frames, 1 MiB frame cap
 * (close 1009), sliding-window packet rate limit, max connections.</p>
 */
public class EaglerWSListener extends WebSocketServer {

    /** Hard cap on a single inbound frame; larger frames are closed with 1009. */
    public static final int MAX_FRAME_BYTES = 0x100000; // 1 MiB, matches client MAX_INCOMING_SIZE

    static final byte PKT_HANDSHAKE = 0x01;
    static final byte PKT_PING = 0x02;
    static final byte PKT_KICK = (byte) 0xFF;

    private final String motd;
    private final String serverBrand;
    private final int maxConnections;
    private final long handshakeTimeoutMs;
    private final int packetsPerMinute;
    private final boolean verbose;
    private final Logger log;

    private final Set<WebSocket> conns = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<WebSocket> handshaken = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger totalAccepted = new AtomicInteger();

    private static final ConcurrentHashMap<WebSocket, long[]> rateWindows = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<WebSocket, int[]> rateCursors = new ConcurrentHashMap<>();

    public EaglerWSListener(int port, String motd, String serverBrand, int maxConnections,
            long handshakeTimeoutMs, int packetsPerMinute, boolean verbose, Logger log) {
        super(new InetSocketAddress(port));
        this.motd = motd;
        this.serverBrand = serverBrand;
        this.maxConnections = maxConnections;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
        this.packetsPerMinute = packetsPerMinute;
        this.verbose = verbose;
        this.log = log;
        setConnectionLostTimeout(30);
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
        scheduleHandshakeTimeout(conn);
        if (verbose) {
            log.info("open " + conn.getRemoteSocketAddress()
                    + " resource=" + handshake.getResourceDescriptor());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        conns.remove(conn);
        handshaken.remove(conn);
        rateWindows.remove(conn);
        rateCursors.remove(conn);
        cancelHandshakeTimeout(conn);
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
            log.warning("oversize frame (" + len + " B) from " + conn.getRemoteSocketAddress()
                    + " - closing 1009");
            conns.remove(conn);
            conn.close(1009, "frame exceeds 1 MiB limit");
            return;
        }
        if (!rateAllow(conn)) {
            kick(conn, "rate limit exceeded");
            return;
        }

        byte id = message.get();
        switch (id) {
            case PKT_HANDSHAKE: {
                if (!handshaken.add(conn)) {
                    kick(conn, "protocol violation: duplicate handshake");
                    return;
                }
                String clientBrand = readU16String(message);
                String username = readU16String(message);
                if (clientBrand == null || username == null) {
                    kick(conn, "malformed handshake");
                    return;
                }
                if (username.trim().isEmpty() || username.length() > 16) {
                    kick(conn, "invalid username");
                    return;
                }
                totalAccepted.incrementAndGet();
                cancelHandshakeTimeout(conn);
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
                break;
            }
            case PKT_PING: {
                if (!handshaken.contains(conn)) {
                    kick(conn, "ping before handshake");
                    return;
                }
                ByteBuffer out = ByteBuffer.allocate(len);
                out.put(PKT_PING);
                out.put(message);
                out.flip();
                conn.send(out);
                if (verbose) {
                    log.info("ping " + (len - 1) + " B from " + conn.getRemoteSocketAddress());
                }
                break;
            }
            default:
                kick(conn, "protocol violation: unknown packet id 0x" + String.format("%02x", id));
                break;
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            log.warning("error on " + conn.getRemoteSocketAddress() + ": " + ex);
        } else {
            log.warning("listener error: " + ex);
        }
    }

    /** Number of completed handshakes since the listener started. */
    public int getTotalAccepted() {
        return totalAccepted.get();
    }

    // ==================== helpers ====================

    private void kick(WebSocket conn, String reason) {
        if (verbose) {
            log.info("kick " + conn.getRemoteSocketAddress() + ": " + reason);
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

    private void scheduleHandshakeTimeout(WebSocket conn) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(handshakeTimeoutMs);
            } catch (InterruptedException ignored) {
                return;
            }
            if (conn.isOpen() && !handshaken.contains(conn)) {
                kick(conn, "handshake timeout (" + handshakeTimeoutMs + "ms)");
            }
        }, "eagler-hs-timeout");
        t.setDaemon(true);
        conn.setAttachment(t);
        t.start();
    }

    private void cancelHandshakeTimeout(WebSocket conn) {
        Object att = conn.getAttachment();
        if (att instanceof Thread) {
            ((Thread) att).interrupt();
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
}
