package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McPacketIds;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * EaglerCraft 26.1.2 - standalone gateway harness for smoke tests.
 *
 * <p>Boots the exact same {@link EaglerWSListener} + {@link EaglerPlayerSession}
 * + {@link McBridge} stack the BungeeCord plugin uses, but without a proxy
 * runtime: the target server is provided on the command line and the
 * proxy-facing hooks are wired to no-ops. This is what the e2e multiplayer
 * smoke test drives, so the browser exercises the production listener code
 * (not a test double) end to end:</p>
 *
 * <pre>
 *   browser (Playwright) - WS - EaglerWSListener - EaglerPlayerSession
 *     - TCP - McBridge - McProtocolStub
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp EaglercraftXBungee-1.3.2.jar \
 *        net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee.GatewaySmokeMain \
 *        --port 8081 --server lobby=127.0.0.1:25577 [--motd ...] [--verbose]
 * </pre>
 */
public final class GatewaySmokeMain {

    private GatewaySmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8081;
        String serverName = "lobby";
        String serverAddr = "127.0.0.1:25577";
        String motd = "EaglerCraft 26.1.2 smoke gateway";
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--server": {
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    serverName = kv.substring(0, eq);
                    serverAddr = kv.substring(eq + 1);
                    break;
                }
                case "--motd": motd = args[++i]; break;
                case "--verbose": verbose = true; break;
                case "--help":
                case "-h":
                    System.out.println("Usage: GatewaySmokeMain [--port N] [--server name=host:port]"
                            + " [--motd S] [--verbose]");
                    return;
                default:
                    System.err.println("Unknown argument: " + args[i] + " (try --help)");
                    System.exit(2);
            }
        }

        int colon = serverAddr.lastIndexOf(':');
        if (colon <= 0) {
            System.err.println("--server expects name=host:port, got: " + serverAddr);
            System.exit(2);
        }
        SmokeServerInfo target = new SmokeServerInfo(serverName,
                new InetSocketAddress(serverAddr.substring(0, colon),
                        Integer.parseInt(serverAddr.substring(colon + 1))));

        EaglerSessionRegistry registry = new EaglerSessionRegistry();
        McPacketIds mcIds = new McPacketIds();

        EaglerWSListener.SessionFactory factory = (username, sink, remote) ->
                new EaglerPlayerSession(username, sink, remote, registry, null,
                        mcIds, "eagler.26.1.2", 8_000,
                        m -> System.out.println("[gateway] " + m),
                        m -> System.out.println("[gateway] WARN: " + m));

        EaglerWSListener listener = new EaglerWSListener(port, motd,
                "EaglercraftXBungee-Smoke/1.3.2", 64, 10_000, 500, verbose,
                java.util.logging.Logger.getLogger("GatewaySmoke"),
                registry, factory, () -> target, mcIds, "eagler.26.1.2", 8_000);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                listener.stop(0);
            } catch (Exception ignored) {
            }
        }, "smoke-shutdown"));

        listener.start();
        System.out.println("[gateway] smoke listener on ws://0.0.0.0:" + port
                + " forwarding to server " + serverName + " (" + serverAddr + ")");
        Thread.currentThread().join();
    }

    /**
     * Minimal {@link ServerInfo}: the API type is an interface and the real
     * implementation is proxy-internal, so the harness provides its own.
     */
    private static final class SmokeServerInfo implements ServerInfo {
        private final String name;
        private final InetSocketAddress address;

        SmokeServerInfo(String name, InetSocketAddress address) {
            this.name = name;
            this.address = address;
        }

        @Override public String getName() { return name; }
        @Override public InetSocketAddress getAddress() { return address; }
        @Override public java.net.SocketAddress getSocketAddress() { return address; }
        @Override public Collection<ProxiedPlayer> getPlayers() { return Collections.emptyList(); }
        @Override public String getMotd() { return "smoke"; }
        @Override public boolean isRestricted() { return false; }
        @Override public String getPermission() { return null; }
        @Override public boolean canAccess(net.md_5.bungee.api.CommandSender sender) { return true; }
        @Override public void sendData(String channel, byte[] data) { /* no-op */ }
        @Override public boolean sendData(String channel, byte[] data, boolean queue) { return false; }
        @Override public void ping(Callback<ServerPing> callback) {
            if (callback != null) {
                callback.done(null, null);
            }
        }
    }
}
