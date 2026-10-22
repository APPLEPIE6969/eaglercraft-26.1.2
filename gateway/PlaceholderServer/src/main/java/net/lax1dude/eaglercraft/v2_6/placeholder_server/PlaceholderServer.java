package net.lax1dude.eaglercraft.v2_6.placeholder_server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EaglerCraft 26.1.2 - Placeholder WebSocket Server.
 *
 * <p>A tiny standalone WebSocket server used to exercise the browser client's
 * multiplayer path without a real Minecraft backend. It accepts the Eagler-style
 * binary handshake, answers pings, serves a MOTD, and kicks anything that
 * violates the protocol — exactly what a gateway smoke-test needs.</p>
 *
 * <h2>Wire protocol (binary frames only, big-endian u16 string lengths)</h2>
 * <pre>
 *   C→S  0x01 handshake   [0x01][u16 n][n bytes client brand][u16 n][n bytes username]
 *   S→C  0x01 accept      [0x01][u16 n][n bytes server brand][u16 n][n bytes motd]
 *   C→S  0x02 ping        [0x02][opaque payload]  (echoed back verbatim)
 *   S→C  0x02 pong        [0x02][same payload]
 *   S→C  0xFF kick        [0xFF][u16 n][n bytes utf-8 reason]  — followed by close(1000)
 * </pre>
 *
 * <p>Violations: first frame not a handshake (within the handshake timeout),
 * text frames, unknown packet ids, duplicate handshakes, frames over
 * {@link #MAX_FRAME_BYTES}, or exceeding the per-connection packet rate all
 * result in a kick (or close 1009 for oversize).</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar PlaceholderServer.jar [--port 8081] [--motd "..."] [--max-conn N]
 *                                   [--hs-timeout-ms N] [--rate N] [--verbose]
 * </pre>
 *
 * <p>This class is the {@code mainClass} of the PlaceholderServer Gradle module
 * (shadowJar: {@code gateway/PlaceholderServer/build/libs/PlaceholderServer-1.0.0.jar}).</p>
 */
public class PlaceholderServer extends WebSocketServer {

        private static final Logger LOG = LoggerFactory.getLogger(PlaceholderServer.class);

        /** Server brand string returned in the handshake accept frame. */
        public static final String SERVER_BRAND = "EaglerCraftX-26.1.2-Placeholder/1.0.0";

        /** Default listen port. */
        public static final int DEFAULT_PORT = 8081;

        /** Hard cap on a single inbound frame; larger frames are closed with 1009. */
        public static final int MAX_FRAME_BYTES = 0x100000; // 1 MiB, matches client MAX_INCOMING_SIZE

        /** Packet ids. */
        static final byte PKT_HANDSHAKE = 0x01;
        static final byte PKT_PING = 0x02;
        static final byte PKT_KICK = (byte) 0xFF;

        // ---- configuration ----
        private final String motd;
        private final int maxConnections;
        private final long handshakeTimeoutMs;
        private final int packetsPerMinute;
        private final boolean verbose;

        // ---- state ----
        private final Set<WebSocket> conns = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Set<WebSocket> handshaken = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final AtomicInteger totalAccepted = new AtomicInteger();

        /** Sliding-window per-connection packet rate limiter state. */
        private static final ConcurrentHashMap<WebSocket, long[]> rateWindows = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<WebSocket, int[]> rateCursors = new ConcurrentHashMap<>();

        private PlaceholderServer(int port, String motd, int maxConnections, long handshakeTimeoutMs,
                        int packetsPerMinute, boolean verbose) {
                super(new InetSocketAddress(port));
                this.motd = motd;
                this.maxConnections = maxConnections;
                this.handshakeTimeoutMs = handshakeTimeoutMs;
                this.packetsPerMinute = packetsPerMinute;
                this.verbose = verbose;
                setConnectionLostTimeout(30);
        }

        // ==================== lifecycle ====================

        @Override
        public void onStart() {
                LOG.info("PlaceholderServer listening on ws://0.0.0.0:{} (motd=\"{}\", maxConn={}, hsTimeout={}ms, rate={} pkt/min)",
                                getPort(), motd, maxConnections, handshakeTimeoutMs, packetsPerMinute);
        }

        public static void main(String[] args) throws Exception {
                int port = DEFAULT_PORT;
                String motd = "EaglerCraft 26.1.2 placeholder — no game server behind this socket";
                int maxConnections = 64;
                long handshakeTimeoutMs = 10_000;
                int packetsPerMinute = 500;
                boolean verbose = false;

                for (int i = 0; i < args.length; i++) {
                        switch (args[i]) {
                                case "--port": port = Integer.parseInt(args[++i]); break;
                                case "--motd": motd = args[++i]; break;
                                case "--max-conn": maxConnections = Integer.parseInt(args[++i]); break;
                                case "--hs-timeout-ms": handshakeTimeoutMs = Long.parseLong(args[++i]); break;
                                case "--rate": packetsPerMinute = Integer.parseInt(args[++i]); break;
                                case "--verbose": verbose = true; break;
                                case "--help":
                                case "-h":
                                        System.out.println("Usage: PlaceholderServer [--port N] [--motd S] [--max-conn N]"
                                                        + " [--hs-timeout-ms N] [--rate N] [--verbose]");
                                        return;
                                default:
                                        System.err.println("Unknown argument: " + args[i] + " (try --help)");
                                        System.exit(2);
                        }
                }

                PlaceholderServer server = new PlaceholderServer(port, motd, maxConnections,
                                handshakeTimeoutMs, packetsPerMinute, verbose);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                                server.stop(0);
                        } catch (InterruptedException ignored) {
                        }
                }, "shutdown-hook"));
                server.start();
        }

        // ==================== websocket events ====================

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
                        LOG.info("open {}: resource={}", conn.getRemoteSocketAddress(), handshake.getResourceDescriptor());
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
                        LOG.info("close {}: code={} remote={} reason={}", conn.getRemoteSocketAddress(), code, remote, reason);
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
                        LOG.warn("oversize frame ({} B) from {} — closing 1009", len, conn.getRemoteSocketAddress());
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
                                LOG.info("handshake from {} brand=\"{}\" username=\"{}\"",
                                                conn.getRemoteSocketAddress(), clientBrand, username);
                                // S1 accept: brand + motd (size from UTF-8 byte lengths, not
                                // String.length(), which counts chars and undercounts multibyte!)
                                byte[] brandBytes = SERVER_BRAND.getBytes(StandardCharsets.UTF_8);
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
                                // Echo the opaque payload verbatim: [0x02][payload...]
                                ByteBuffer out = ByteBuffer.allocate(len);
                                out.put(PKT_PING);
                                out.put(message);
                                out.flip();
                                conn.send(out);
                                if (verbose) {
                                        LOG.info("ping {} B from {} -> pong", len - 1, conn.getRemoteSocketAddress());
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
                        LOG.warn("error on {}: {}", conn.getRemoteSocketAddress(), ex.toString());
                } else {
                        LOG.warn("server error: {}", ex.toString());
                }
        }

        // ==================== helpers ====================

        private void kick(WebSocket conn, String reason) {
                LOG.info("kick {}: {}", conn.getRemoteSocketAddress(), reason);
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
                }, "hs-timeout");
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
                // The window buffer is one slot LARGER than the limit so that the
                // (packetsPerMinute + 1)-th frame within the window pushes the count
                // above the limit and triggers the kick (a buffer exactly the size of
                // the limit would saturate at the limit and never fire).
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

        private static void putU16String(ByteBuffer out, String s) {
                putU16Bytes(out, s.getBytes(StandardCharsets.UTF_8));
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
