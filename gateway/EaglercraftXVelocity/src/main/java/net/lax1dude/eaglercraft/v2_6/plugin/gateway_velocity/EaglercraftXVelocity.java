package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;

/**
 * EaglercraftXVelocity 26.1.2 - Velocity plugin for EaglerCraft WebSocket clients.
 *
 * <p>On proxy initialization it reads {@code config.yml} from the plugin data
 * directory and starts an embedded WebSocket listener ({@link EaglerWSListener})
 * that speaks the Eagler binary protocol: 0x01 handshake/accept, 0x02
 * ping/pong, 0x03 login, 0x04 login result, 0x05 chat, 0xFF kick.
 * Java-WebSocket is relocated at build time, so the plugin jar is
 * self-contained.</p>
 *
 * <p>Gateway milestone #2 (wire-protocol parity): a completed 0x03 login
 * creates a {@link VelocityEaglerSession} backed by a real MC-protocol bridge
 * into the backend resolved from Velocity's server registry
 * ({@code forwarding_default_server}). See the session class for the
 * Player-injection scope note.</p>
 */
@Plugin(
        id = "eaglercraftx",
        name = "EaglercraftXVelocity",
        version = "1.3.2",
        description = "EaglerCraft WebSocket gateway for Velocity (26.1.2)",
        authors = { "lax1dude" })
public class EaglercraftXVelocity {

    public static final String SERVER_BRAND = "EaglercraftXVelocity/1.3.2";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final AtomicReference<EaglerWSListener> listenerRef = new AtomicReference<>();

    @Inject
    public EaglercraftXVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Map<String, Object> cfg = loadConfig();

        int port = intOf(cfg.get("port"), 8081);
        String motd = strOf(cfg.get("motd"), "EaglerCraft 26.1.2 via EaglercraftXVelocity");
        int maxConnections = intOf(cfg.get("max_connections"), 64);
        long handshakeTimeoutMs = longOf(cfg.get("handshake_timeout_ms"), 10_000L);
        int packetsPerMinute = intOf(cfg.get("packet_rate_per_minute"), 500);
        boolean verbose = boolOf(cfg.get("verbose"), false);

        // milestone 2: forwarding configuration
        String defaultServerName = strOf(cfg.get("forwarding_default_server"), "");
        String virtualHost = strOf(cfg.get("forwarding_virtual_host"), "eagler.26.1.2");
        int bridgeConnectTimeoutMs = intOf(cfg.get("forwarding_connect_timeout_ms"), 8_000);
        McPacketIds mcIds = mcIdsOf(cfg);

        VelocitySessionRegistry registry = new VelocitySessionRegistry();

        Optional<com.velocitypowered.api.proxy.server.RegisteredServer> defaultServer;
        if (defaultServerName.isEmpty()) {
            defaultServer = Optional.empty();
        } else {
            defaultServer = server.getServer(defaultServerName);
        }
        if (!defaultServerName.isEmpty() && !defaultServer.isPresent()) {
            logger.warn("forwarding_default_server '{}' is not registered in the proxy -"
                    + " Eagler logins will be rejected", defaultServerName);
        }
        final InetSocketAddress target = defaultServer
                .map(rs -> rs.getServerInfo().getAddress())
                .orElse(null);

        EaglerWSListener.SessionFactory factory = (username, sink, remote) ->
                new VelocityEaglerSession(username, sink, remote, registry, mcIds,
                        virtualHost, bridgeConnectTimeoutMs,
                        m -> logger.info(m), m -> logger.warn(m));

        EaglerWSListener listener = new EaglerWSListener(port, motd, SERVER_BRAND,
                maxConnections, handshakeTimeoutMs, packetsPerMinute, verbose, logger,
                registry, factory, () -> target, mcIds, virtualHost, bridgeConnectTimeoutMs);
        if (listenerRef.compareAndSet(null, listener)) {
            listener.start();
        }

        CommandManager commands = server.getCommandManager();
        commands.register(commands.metaBuilder("eagler").plugin(this).build(),
                new EaglerCommand(listenerRef));

        logger.info("EaglercraftXVelocity enabled: ws listener on ws://0.0.0.0:{}"
                + " (motd=\"{}\", maxConn={}, forwarding={})", port, motd, maxConnections,
                target != null ? target.getHostString() + ":" + target.getPort() : "disabled");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        EaglerWSListener listener = listenerRef.getAndSet(null);
        if (listener != null) {
            final int accepted = listener.getTotalAccepted();
            // Java-WebSocket's stop() can block on the selector thread; run it
            // on a daemon thread so proxy shutdown is never held up.
            Thread t = new Thread(() -> {
                try {
                    listener.stop(0);
                } catch (Exception ignored) {
                }
            }, "eagler-listener-stop");
            t.setDaemon(true);
            t.start();
            logger.info("EaglercraftXVelocity disabled: ws listener stopping (accepted "
                    + accepted + " handshakes this session)");
        }
    }

    // ==================== config ====================

    private Map<String, Object> loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
        } catch (Exception e) {
            logger.warn("could not create plugin data directory {}: {}", dataDirectory, e.toString());
        }
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.exists(file)) {
            saveDefaultConfig(file);
        }
        try (var in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            if (root instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) root;
                return map;
            }
            logger.warn("config.yml is empty or not a map - using defaults");
        } catch (Exception e) {
            logger.warn("failed to read config.yml: {} - using defaults", e.toString());
        }
        return new HashMap<>();
    }

    private void saveDefaultConfig(Path file) {
        String yaml = ""
                + "# EaglercraftXVelocity configuration\n"
                + "# Port for the embedded Eagler WebSocket listener.\n"
                + "port: 8081\n"
                + "# Message of the day shown to EaglerCraft clients on handshake.\n"
                + "motd: \"EaglerCraft 26.1.2 via EaglercraftXVelocity\"\n"
                + "# Maximum simultaneous WebSocket connections.\n"
                + "max_connections: 64\n"
                + "# How long a connection may stay open before completing the handshake.\n"
                + "handshake_timeout_ms: 10000\n"
                + "# Sliding-window per-connection packet limit.\n"
                + "packet_rate_per_minute: 500\n"
                + "# Verbose connection logging.\n"
                + "verbose: false\n"
                + "\n"
                + "# ---- Gateway milestone 2: player forwarding ----\n"
                + "# Name of the backend server (in Velocity's registry) that Eagler\n"
                + "# players join after login. Empty = forwarding disabled.\n"
                + "forwarding_default_server: \"lobby\"\n"
                + "# Virtual hostname advertised in the MC handshake to the backend.\n"
                + "forwarding_virtual_host: \"eagler.26.1.2\"\n"
                + "# TCP connect + login timeout for the MC bridge.\n"
                + "forwarding_connect_timeout_ms: 8000\n"
                + "#\n"
                + "# MC-protocol packet ids used by the bridge (defaults match protocol\n"
                + "# 775 of the 26.1.2 fork; override when the backend differs).\n"
                + "mc_keepalive_id: 30\n"
                + "mc_system_chat_id: 114\n"
                + "mc_disconnect_id: 77\n"
                + "mc_keepalive_response_id: 18\n"
                + "mc_chat_id: 6\n";
        try {
            Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("could not write default config.yml: {}", e.toString());
        }
    }

    /** Builds the bridge packet-id config from config.yml overrides. */
    private static McPacketIds mcIdsOf(Map<String, Object> cfg) {
        McPacketIds ids = new McPacketIds();
        ids.playKeepAlive = intOf(cfg.get("mc_keepalive_id"), ids.playKeepAlive);
        ids.playSystemChat = intOf(cfg.get("mc_system_chat_id"), ids.playSystemChat);
        ids.playDisconnect = intOf(cfg.get("mc_disconnect_id"), ids.playDisconnect);
        ids.playKeepAliveResponse = intOf(cfg.get("mc_keepalive_response_id"), ids.playKeepAliveResponse);
        ids.playChat = intOf(cfg.get("mc_chat_id"), ids.playChat);
        return ids;
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static long longOf(Object o, long def) {
        return o instanceof Number ? ((Number) o).longValue() : def;
    }

    private static String strOf(Object o, String def) {
        return o instanceof String && !((String) o).isEmpty() ? (String) o : def;
    }

    private static boolean boolOf(Object o, boolean def) {
        return o instanceof Boolean ? (Boolean) o : def;
    }
}
