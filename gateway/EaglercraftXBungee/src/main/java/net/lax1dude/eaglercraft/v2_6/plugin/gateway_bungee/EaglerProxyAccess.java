package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * EaglerCraft 26.1.2 - bridge between {@link EaglerPlayerSession} and the
 * live BungeeCord proxy runtime.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>fire proxy events ({@code ServerConnectEvent}, {@code PostLoginEvent},
 *       {@code ServerConnectedEvent}, {@code ServerSwitchEvent});</li>
 *   <li>register/unregister the session in the proxy runtime's player
 *       collection, so other plugins see the Eagler player in
 *       {@code getProxy().getPlayers()}. The API does not expose a mutation
 *       path for externally-authored connections, so this uses reflection on
 *       the well-known public {@code players} field of the proxy
 *       implementation and degrades to gateway-local-only registration if a
 *       future proxy build removes the field;</li>
 *   <li>expose the proxy's server registry for session connects.</li>
 * </ul>
 */
public final class EaglerProxyAccess implements EaglerPlayerSession.ProxyServerAccess {

    private final Plugin plugin;
    private final ProxyServer proxy;

    public EaglerProxyAccess(Plugin plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public void callEvent(Object event) {
        if (proxy != null && event instanceof Event) {
            try {
                // PluginManager (not ProxyServer) is the API event path in 1.20-R0.2
                proxy.getPluginManager().callEvent((Event) event);
            } catch (Exception e) {
                plugin.getLogger().warning("event dispatch failed: " + e);
            }
        }
    }

    @Override
    public void registerPlayer(ProxiedPlayer player) {
        Collection<ProxiedPlayer> players = playersField();
        if (players != null) {
            players.add(player);
        }
    }

    @Override
    public void unregisterPlayer(ProxiedPlayer player) {
        Collection<ProxiedPlayer> players = playersField();
        if (players != null) {
            players.remove(player);
        }
    }

    @Override
    public ListenerInfo firstListener() {
        Collection<ListenerInfo> listeners = proxy.getConfig().getListeners();
        return listeners.isEmpty() ? null : listeners.iterator().next();
    }

    @Override
    public Map<String, ServerInfo> servers() {
        return proxy.getServers();
    }

    /**
     * Reflects the proxy runtime's live player collection. Returns null when
     * the runtime does not expose it (e.g. standalone harness, or a future
     * BungeeCord that moved the field).
     */
    private Collection<ProxiedPlayer> playersField() {
        if (proxy == null) {
            return null;
        }
        try {
            Class<?> cls = proxy.getClass();
            while (cls != null) {
                try {
                    Field f = cls.getDeclaredField("players");
                    f.setAccessible(true);
                    Object o = f.get(proxy);
                    if (o instanceof Collection) {
                        @SuppressWarnings("unchecked")
                        Collection<ProxiedPlayer> players = (Collection<ProxiedPlayer>) o;
                        return players;
                    }
                    return null;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().info("proxy runtime does not expose its player collection ("
                    + e.getClass().getSimpleName() + "); Eagler players stay gateway-local");
            return null;
        }
    }
}
