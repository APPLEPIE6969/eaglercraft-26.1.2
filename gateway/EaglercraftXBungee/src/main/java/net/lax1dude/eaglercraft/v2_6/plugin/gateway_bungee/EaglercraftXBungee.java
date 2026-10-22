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

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * EaglercraftXBungee 26.1.2 - BungeeCord plugin for EaglerCraft WebSocket clients.
 *
 * <p>On enable it reads {@code plugins/EaglercraftXBungee/config.yml} and starts
 * an embedded WebSocket listener ({@link EaglerWSListener}) that speaks the
 * Eagler binary protocol: 0x01 handshake/accept, 0x02 ping/pong, 0x03 login,
 * 0x04 login result, 0x05 chat, 0xFF kick. Java-WebSocket is embedded
 * (relocated at build time), so the plugin is self-contained.</p>
 *
 * <p>Gateway milestone #2: a completed 0x03 login creates an
 * {@link EaglerPlayerSession} (a full {@code ProxiedPlayer}), registers it in
 * the proxy runtime's player collection (reflection, best effort), fires
 * {@code PostLoginEvent}, and connects the player to the backend server named
 * by {@code forwarding_default_server} via a real MC-protocol TCP bridge
 * ({@code McBridge}); {@code ServerConnectedEvent}/{@code ServerSwitchEvent}
 * fire once the backend accepts.</p>
 */
public class EaglercraftXBungee extends Plugin {

    private static final Logger LOG = Logger.getLogger("EaglercraftXBungee");

    public static final String SERVER_BRAND = "EaglercraftXBungee/1.3.2";

    private EaglerWSListener listener;
    private EaglerSessionRegistry registry;
    private EaglerProxyAccess proxyAccess;

    @Override
    public void onEnable() {
        Map<String, Object> cfg = loadConfig();

        int port = intOf(cfg.get("port"), 8081);
        String motd = strOf(cfg.get("motd"), "EaglerCraft 26.1.2 via EaglercraftXBungee");
        int maxConnections = intOf(cfg.get("max_connections"), 64);
        long handshakeTimeoutMs = longOf(cfg.get("handshake_timeout_ms"), 10_000L);
        int packetsPerMinute = intOf(cfg.get("packet_rate_per_minute"), 500);
        boolean verbose = boolOf(cfg.get("verbose"), false);

        // milestone 2: forwarding configuration
        String defaultServerName = strOf(cfg.get("forwarding_default_server"), "");
        String virtualHost = strOf(cfg.get("forwarding_virtual_host"), "eagler.26.1.2");
        int bridgeConnectTimeoutMs = intOf(cfg.get("forwarding_connect_timeout_ms"), 8_000);
        McPacketIds mcIds = mcIdsOf(cfg);

        registry = new EaglerSessionRegistry();
        proxyAccess = new EaglerProxyAccess(this, getProxy());

        ServerInfo defaultServer = defaultServerName.isEmpty()
                ? null
                : getProxy().getServers().get(defaultServerName);
        if (!defaultServerName.isEmpty() && defaultServer == null) {
            LOG.warning("forwarding_default_server '" + defaultServerName
                    + "' is not registered in the proxy (config.yml servers list) - Eagler logins will be rejected");
        }
        final ServerInfo target = defaultServer;

        EaglerWSListener.SessionFactory factory = (username, sink, remote) -> {
            EaglerPlayerSession session = new EaglerPlayerSession(username, sink, remote,
                    registry, proxyAccess, mcIds, virtualHost, bridgeConnectTimeoutMs,
                    LOG::info, LOG::warning);
            proxyAccess.registerPlayer(session);
            proxyAccess.callEvent(new net.md_5.bungee.api.event.PostLoginEvent(session));
            return session;
        };

        getProxy().getPluginManager().registerCommand(this,
                new EaglerCommand(this, () -> listener));

        listener = new EaglerWSListener(port, motd, SERVER_BRAND, maxConnections,
                handshakeTimeoutMs, packetsPerMinute, verbose, LOG,
                registry, factory, () -> target, mcIds, virtualHost, bridgeConnectTimeoutMs);
        listener.start();

        LOG.info("EaglercraftXBungee enabled: ws listener on ws://0.0.0.0:" + port
                + " (motd=\"" + motd + "\", maxConn=" + maxConnections
                + ", forwarding=" + (target != null ? target.getName() : "disabled") + ")");
    }

    @Override
    public void onDisable() {
        final EaglerWSListener l = listener;
        listener = null;
        if (l != null) {
            final int accepted = l.getTotalAccepted();
            final int players = l.getRegistry() != null ? l.getRegistry().count() : 0;
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
                    + accepted + " handshakes, " + players + " sessions this session)");
        }
    }

    /** Returns the active listener, for the status command. */
    public EaglerWSListener getListener() {
        return listener;
    }

    /** Returns the session registry, for the status command. */
    public EaglerSessionRegistry getRegistry() {
        return registry;
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
                + "verbose: false\n"
                + "\n"
                + "# ---- Gateway milestone 2: player forwarding ----\n"
                + "# Name of the backend server (in the proxy's servers map) that Eagler\n"
                + "# players join after login. Empty = forwarding disabled (logins rejected).\n"
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
            Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warning("could not write default config.yml: " + e);
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
