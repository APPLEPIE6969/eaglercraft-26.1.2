package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * EaglercraftXVelocity 26.1.2 - Velocity plugin for EaglerCraft WebSocket clients.
 *
 * <p>On proxy initialization it reads {@code config.yml} from the plugin data
 * directory and starts an embedded WebSocket listener ({@link EaglerWSListener})
 * that speaks the Eagler binary protocol: 0x01 handshake/accept, 0x02
 * ping/pong, 0xFF kick. Java-WebSocket is relocated at build time, so the
 * plugin jar is self-contained.</p>
 *
 * <p>This is the first functional milestone of the gateway: it makes the
 * proxy reachable by EaglerCraft browsers for MOTD/ping/handshake traffic.
 * Full MC-protocol player forwarding into Velocity's server registry is the
 * next milestone and intentionally out of scope here.</p>
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

        EaglerWSListener listener = new EaglerWSListener(port, motd, SERVER_BRAND,
                maxConnections, handshakeTimeoutMs, packetsPerMinute, verbose, logger);
        if (listenerRef.compareAndSet(null, listener)) {
            listener.start();
        }

        CommandManager commands = server.getCommandManager();
        commands.register(commands.metaBuilder("eagler").plugin(this).build(),
                new EaglerCommand(listenerRef));

        logger.info("EaglercraftXVelocity enabled: ws listener on ws://0.0.0.0:" + port
                + " (motd=\"" + motd + "\", maxConn=" + maxConnections + ")");
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
                + "verbose: false\n";
        try {
            Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("could not write default config.yml: {}", e.toString());
        }
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
