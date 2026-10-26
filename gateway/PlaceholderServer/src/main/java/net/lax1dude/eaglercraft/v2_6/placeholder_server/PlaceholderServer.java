package net.lax1dude.eaglercraft.v2_6.placeholder_server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EaglerCraft 26.1.2 - the placeholder gateway server.
 *
 * <p>A small standalone play server speaking the REAL gateway protocol
 * (docs/PROTOCOL.md, "Minecraft 26.1.2 Protocol 775") over WebSocket
 * binary frames: status ping/MOTD, offline-mode login, the vanilla
 * configuration sequence, and a playable superflat world with oak
 * trees - chunk streaming, server-authoritative digging and placing,
 * inventory drops, chat and keep-alives. This is the multiplayer
 * endpoint the browser client's Multiplayer menu joins.</p>
 *
 * <p>One WebSocket binary frame = one packet: {@code [VarInt
 * packetId][payload]}. Per-connection state lives in
 * {@link GatewaySession}; the world lives in {@link GatewayWorld}.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar PlaceholderServer.jar [--port 8081] [--motd "..."] [--max-conn N]
 *                                   [--rate N] [--seed N] [--verbose]
 * </pre>
 *
 * <p>Transport hygiene: text frames, empty frames, oversize frames
 * (1 MiB cap) and per-connection packet-rate violations close the
 * socket - exactly what a hostile client deserves before the
 * protocol layer even sees the bytes.</p>
 */
public class PlaceholderServer extends WebSocketServer {

        private static final Logger LOG = LoggerFactory.getLogger(PlaceholderServer.class);

        /** Server brand string (minecraft:brand plugin message). */
        public static final String SERVER_BRAND = "EaglerCraftX-26.1.2-Gateway/1.0.0";

        /** Default listen port. */
        public static final int DEFAULT_PORT = 8081;

        /** Hard cap on a single inbound frame; larger frames are closed with 1009. */
        public static final int MAX_FRAME_BYTES = 0x100000; // 1 MiB

        // ---- configuration ----
        private final String motd;
        private final int maxConnections;
        private final int packetsPerSecond;
        private final boolean verbose;
        private final GatewayWorld world;

        // ---- state ----
        private final Set<WebSocket> conns = ConcurrentHashMap.newKeySet();
        private final Map<WebSocket, GatewaySession> sessions = new ConcurrentHashMap<>();
        /** Playing sessions by username (duplicate-login guard). */
        private final Map<String, GatewaySession> playing = new ConcurrentHashMap<>();
        /** The chunk columns each playing session currently holds. */
        private final Map<GatewaySession, Set<Long>> sentChunks = new ConcurrentHashMap<>();

        /** Sliding-window per-connection packet rate limiter state. */
        private static final ConcurrentHashMap<WebSocket, long[]> rateWindows = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<WebSocket, int[]> rateCursors = new ConcurrentHashMap<>();

        private final ScheduledExecutorService scheduler =
                        Executors.newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "gateway-scheduler");
                                t.setDaemon(true);
                                return t;
                        });

        private PlaceholderServer(int port, String motd, int maxConnections,
                        int packetsPerSecond, long seed, boolean verbose) {
                super(new InetSocketAddress(port));
                this.motd = motd;
                this.maxConnections = maxConnections;
                this.packetsPerSecond = packetsPerSecond;
                this.verbose = verbose;
                this.world = new GatewayWorld(seed);
                setConnectionLostTimeout(30);
                setReuseAddr(true); // restarts re-bind through TIME_WAIT
        }

        // ==================== lifecycle ====================

        @Override
        public void onStart() {
                LOG.info("GatewayServer (real protocol, docs/PROTOCOL.md) listening on ws://0.0.0.0:{}",
                                getPort());
                LOG.info("motd=\"{}\" maxConn={} rate={} pkt/s seed={} trees={}",
                                motd, maxConnections, packetsPerSecond, world.getSeed(),
                                world.treeTrunks().length);
                // The single keep-alive driver: per-session tasks would
                // double-send (onStart runs once, sessions register into the
                // same scheduler).
        }

        public static void main(String[] args) throws Exception {
                int port = DEFAULT_PORT;
                String motd = "EaglerCraft 26.1.2 gateway - punch a tree!";
                int maxConnections = 64;
                int packetsPerSecond = 500;
                long seed = 2612L;
                boolean verbose = false;

                for (int i = 0; i < args.length; i++) {
                        switch (args[i]) {
                                case "--port": port = Integer.parseInt(args[++i]); break;
                                case "--motd": motd = args[++i]; break;
                                case "--max-conn": maxConnections = Integer.parseInt(args[++i]); break;
                                case "--rate": packetsPerSecond = Integer.parseInt(args[++i]); break;
                                case "--seed": seed = Long.parseLong(args[++i]); break;
                                case "--verbose": verbose = true; break;
                                case "--help":
                                case "-h":
                                        System.out.println("Usage: PlaceholderServer [--port N] [--motd S]"
                                                        + " [--max-conn N] [--rate N] [--seed N] [--verbose]");
                                        return;
                                default:
                                        System.err.println("Unknown argument: " + args[i] + " (try --help)");
                                        System.exit(2);
                        }
                }

                PlaceholderServer server = new PlaceholderServer(port, motd,
                                maxConnections, packetsPerSecond, seed, verbose);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        // stop() waits for open connections; the halt
                        // guarantees SIGTERM always takes the JVM down
                        Thread stopper = new Thread(() -> {
                                try {
                                        server.stop(500);
                                } catch (Exception ignored) {
                                }
                        }, "stopper");
                        stopper.start();
                        try {
                                stopper.join(2000);
                        } catch (InterruptedException ignored) {
                        }
                        Runtime.getRuntime().halt(0);
                }, "shutdown-hook"));
                server.start();
        }

        // ==================== websocket events ====================

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
                if (conns.size() >= maxConnections) {
                        LOG.info("reject {}: server full ({})", conn.getRemoteSocketAddress(),
                                        maxConnections);
                        conn.close(1000, "server full");
                        return;
                }
                conns.add(conn);
                rateWindows.remove(conn);
                rateCursors.remove(conn);
                if (verbose) {
                        LOG.info("open {}: resource={}", conn.getRemoteSocketAddress(),
                                        handshake.getResourceDescriptor());
                }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                if (conn == null) {
                        // Java-WebSocket delivers failed handshakes with a
                        // null socket; there is no session state to clean
                        return;
                }
                conns.remove(conn);
                rateWindows.remove(conn);
                rateCursors.remove(conn);
                GatewaySession session = sessions.remove(conn);
                if (session != null) {
                        session.close();
                }
                sentChunks.remove(session);
                if (verbose) {
                        LOG.info("close {}: code={} remote={} reason={}",
                                        conn.getRemoteSocketAddress(), code, remote, reason);
                }
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
                // The client platform layer (PlatformNetworking) uses arraybuffer
                // binary frames exclusively; a text frame means we are not
                // talking to our client.
                LOG.info("text frame from {} - closing", conn.getRemoteSocketAddress());
                conn.close(1003, "protocol violation: text frames are not accepted");
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
                int len = message.remaining();
                if (len < 1) {
                        conn.close(1003, "protocol violation: empty frame");
                        return;
                }
                if (len > MAX_FRAME_BYTES) {
                        LOG.warn("oversize frame ({} B) from {} - closing 1009", len,
                                        conn.getRemoteSocketAddress());
                        conns.remove(conn);
                        conn.close(1009, "frame exceeds 1 MiB limit");
                        return;
                }
                if (!rateAllow(conn)) {
                        conn.close(1008, "rate limit exceeded");
                        return;
                }
                GatewaySession session = sessions.get(conn);
                if (session == null) {
                        session = new GatewaySession(this, conn, world);
                        sessions.put(conn, session);
                }
                session.handle(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
                if (conn != null) {
                        LOG.warn("error on {}: {}", conn.getRemoteSocketAddress(), ex.toString(), ex);
                } else {
                        LOG.warn("server error: {}", ex.toString(), ex);
                }
        }

        // ==================== session services ====================

        public String getMotd() {
                return this.motd;
        }

        public int getMaxConnections() {
                return this.maxConnections;
        }

        public String getServerBrand() {
                return SERVER_BRAND;
        }

        public boolean isVerbose() {
                return this.verbose;
        }

        public int playingCount() {
                return this.playing.size();
        }

        /** Registers a playing session; false on a duplicate username. */
        public boolean registerPlaying(GatewaySession session) {
                if (playing.putIfAbsent(session.getUsername(), session) != null) {
                        return false;
                }
                sentChunks.put(session, ConcurrentHashMap.newKeySet());
                return true;
        }

        public void unregisterPlaying(GatewaySession session) {
                playing.remove(session.getUsername(), session);
                sentChunks.remove(session);
        }

        public ScheduledFuture<?> scheduleKeepAlive(GatewaySession session) {
                // Session keep-alives ride the shared driver (tickKeepAlives);
                // this returns a no-op future for symmetry with close().
                return scheduler.scheduleAtFixedRate(() -> { },
                                GatewaySession.KEEP_ALIVE_INTERVAL_MS,
                                GatewaySession.KEEP_ALIVE_INTERVAL_MS,
                                TimeUnit.MILLISECONDS);
        }

        /**
         * Streams the world around a player: every column within
         * {@link GatewaySession#VIEW_RADIUS} of (pcx, pcz) the session
         * doesn't hold yet is sent; held columns beyond
         * {@link GatewaySession#UNLOAD_RADIUS} are unloaded.
         */
        public void streamAround(GatewaySession session, int pcx, int pcz, boolean full) {
                Set<Long> sent = sentChunks.get(session);
                if (sent == null) {
                        return;
                }
                // stream new columns
                List<long[]> toSend = new ArrayList<>();
                for (int dx = -GatewaySession.VIEW_RADIUS; dx <= GatewaySession.VIEW_RADIUS; dx++) {
                        for (int dz = -GatewaySession.VIEW_RADIUS; dz <= GatewaySession.VIEW_RADIUS; dz++) {
                                int cx = pcx + dx;
                                int cz = pcz + dz;
                                long key = ((long) cx & 0xFFFFFFFFL) << 32
                                                | ((long) cz & 0xFFFFFFFFL);
                                if (sent.add(key)) {
                                        toSend.add(new long[]{cx, cz});
                                }
                        }
                }
                for (long[] c : toSend) {
                        session.sendChunk((int) c[0], (int) c[1]);
                }
                // unload far columns
                List<long[]> toUnload = new ArrayList<>();
                for (Long key : sent) {
                        int cx = (int) (key >> 32);
                        int cz = (int) (key & 0xFFFFFFFFL);
                        int dx = cx - pcx;
                        int dz = cz - pcz;
                        if (dx < -GatewaySession.UNLOAD_RADIUS
                                        || dx > GatewaySession.UNLOAD_RADIUS
                                        || dz < -GatewaySession.UNLOAD_RADIUS
                                        || dz > GatewaySession.UNLOAD_RADIUS) {
                                toUnload.add(new long[]{cx, cz});
                        }
                }
                for (long[] c : toUnload) {
                        session.sendUnloadChunk((int) c[0], (int) c[1]);
                        sent.remove((((long) c[0] & 0xFFFFFFFFL) << 32)
                                        | ((long) c[1] & 0xFFFFFFFFL));
                }
                if (verbose && (!toSend.isEmpty() || !toUnload.isEmpty())) {
                        LOG.info("stream {}: +{} -{} columns at chunk {} {}",
                                        session.getUsername(), toSend.size(), toUnload.size(), pcx, pcz);
                }
        }

        /** Broadcasts a Block Update to every playing session. */
        public void broadcastBlockUpdate(int x, int y, int z, int blockId) {
                for (GatewaySession session : playing.values()) {
                        if (session.isPlaying()) {
                                session.sendBlockUpdate(x, y, z, blockId);
                        }
                }
        }

        /** Broadcasts a chat JSON component to every playing session. */
        public void broadcastSystemChat(String json) {
                for (GatewaySession session : playing.values()) {
                        if (session.isPlaying()) {
                                session.sendSystemChat(json);
                        }
                }
        }

        /** Whether any playing session's box overlaps the block cube. */
        public boolean overlapsAnyPlayer(int bx, int by, int bz) {
                for (GatewaySession session : playing.values()) {
                        if (session.isPlaying() && session.overlapsBlock(bx, by, bz)) {
                                return true;
                        }
                }
                return false;
        }

        private void tickKeepAlives() {
                for (GatewaySession session : playing.values()) {
                        try {
                                session.tickKeepAlive();
                        } catch (Exception e) {
                                LOG.warn("keep-alive tick failed for {}: {}",
                                                session.getUsername(), e.toString());
                        }
                }
        }

        // ==================== helpers ====================

        private boolean rateAllow(WebSocket conn) {
                long now = System.currentTimeMillis();
                // The window buffer is one slot LARGER than the limit so that
                // the (limit + 1)-th frame within the window fires the kick
                // (a buffer exactly the size of the limit would saturate and
                // never fire).
                long[] stamps = rateWindows.computeIfAbsent(conn,
                                k -> new long[packetsPerSecond + 1]);
                int[] cur = rateCursors.computeIfAbsent(conn, k -> new int[1]);
                int count = 0;
                synchronized (stamps) {
                        stamps[cur[0] % stamps.length] = now;
                        cur[0]++;
                        for (long ts : stamps) {
                                if (ts > now - 1000L) {
                                        count++;
                                }
                        }
                }
                return count <= packetsPerSecond;
        }
}
