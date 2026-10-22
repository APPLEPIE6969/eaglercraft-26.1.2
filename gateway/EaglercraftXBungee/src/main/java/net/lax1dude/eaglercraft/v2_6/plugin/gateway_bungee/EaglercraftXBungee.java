package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

import net.md_5.bungee.api.plugin.Plugin;

/**
 * EaglercraftXBungee 26.1.2 - BungeeCord plugin for EaglerCraft WebSocket clients.
 *
 * <p>On enable it reads {@code plugins/EaglercraftXBungee/config.yml} and starts
 * an embedded WebSocket listener ({@link EaglerWSListener}) that speaks the
 * Eagler binary protocol: 0x01 handshake/accept, 0x02 ping/pong, 0xFF kick.
 * The listener embeds Java-WebSocket (relocated at build time), so the plugin
 * is self-contained.</p>
 *
 * <p>This is the first functional milestone of the gateway: it makes the
 * proxy reachable by EaglerCraft browsers for MOTD/ping/handshake traffic.
 * Full MC-protocol player forwarding into BungeeCord's server registry is the
 * next milestone and intentionally out of scope here.</p>
 */
public class EaglercraftXBungee extends Plugin {

    private static final Logger LOG = Logger.getLogger("EaglercraftXBungee");

    public static final String SERVER_BRAND = "EaglercraftXBungee/1.3.2";

    private EaglerWSListener listener;

    @Override
    public void onEnable() {
        Map<String, Object> cfg = loadConfig();

        int port = intOf(cfg.get("port"), 8081);
        String motd = strOf(cfg.get("motd"), "EaglerCraft 26.1.2 via EaglercraftXBungee");
        int maxConnections = intOf(cfg.get("max_connections"), 64);
        long handshakeTimeoutMs = longOf(cfg.get("handshake_timeout_ms"), 10_000L);
        int packetsPerMinute = intOf(cfg.get("packet_rate_per_minute"), 500);
        boolean verbose = boolOf(cfg.get("verbose"), false);

        getProxy().getPluginManager().registerCommand(this,
                new EaglerCommand(this, () -> listener));

        listener = new EaglerWSListener(port, motd, SERVER_BRAND, maxConnections,
                handshakeTimeoutMs, packetsPerMinute, verbose, LOG);
        listener.start();

        LOG.info("EaglercraftXBungee enabled: ws listener on ws://0.0.0.0:" + port
                + " (motd=\"" + motd + "\", maxConn=" + maxConnections + ")");
    }

    @Override
    public void onDisable() {
        final EaglerWSListener l = listener;
        listener = null;
        if (l != null) {
            final int accepted = l.getTotalAccepted();
            // Java-WebSocket's stop() can block on the selector thread; run it
            // on a daemon thread so proxy shutdown is never held up.
            Thread t = new Thread(() -> {
                try {
                    l.stop(0);
                } catch (Exception ignored) {
                }
            }, "eagler-listener-stop");
            t.setDaemon(true);
            t.start();
            LOG.info("EaglercraftXBungee disabled: ws listener stopping (accepted "
                    + accepted + " handshakes this session)");
        }
    }

    /** Returns the active listener, for the status command. */
    public EaglerWSListener getListener() {
        return listener;
    }

    // ==================== config ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            LOG.warning("could not create plugin data folder: " + folder);
        }
        File file = new File(folder, "config.yml");
        if (!file.exists()) {
            saveDefaultConfig(file);
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            Object root = new Yaml().load(in);
            if (root instanceof Map) {
                return (Map<String, Object>) root;
            }
            LOG.warning("config.yml is empty or not a map - using defaults");
        } catch (IOException e) {
            LOG.warning("failed to read config.yml: " + e + " - using defaults");
        }
        return new HashMap<>();
    }

    private void saveDefaultConfig(File file) {
        String yaml = ""
                + "# EaglercraftXBungee configuration\n"
                + "# Port for the embedded Eagler WebSocket listener.\n"
                + "port: 8081\n"
                + "# Message of the day shown to EaglerCraft clients on handshake.\n"
                + "motd: \"EaglerCraft 26.1.2 via EaglercraftXBungee\"\n"
                + "# Maximum simultaneous WebSocket connections.\n"
                + "max_connections: 64\n"
                + "# How long a connection may stay open before completing the handshake.\n"
                + "handshake_timeout_ms: 10000\n"
                + "# Sliding-window per-connection packet limit.\n"
                + "packet_rate_per_minute: 500\n"
                + "# Verbose connection logging.\n"
                + "verbose: false\n";
        try {
            Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("could not write default config.yml: " + e);
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
