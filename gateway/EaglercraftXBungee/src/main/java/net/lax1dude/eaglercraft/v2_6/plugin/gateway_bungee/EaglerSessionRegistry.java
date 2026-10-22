package net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EaglerCraft 26.1.2 - registry of logged-in Eagler player sessions.
 *
 * <p>Thread-safe; username lookups are case-insensitive (matching BungeeCord's
 * own player set semantics). The registry is authoritative for gateway-level
 * operations (duplicate login rejection, {@code /eagler} status, teardown);
 * proxy-runtime registration happens additionally via reflection and is
 * tracked per session with {@code registeredInProxyRuntime}.</p>
 */
public final class EaglerSessionRegistry {

    private final Map<UUID, EaglerPlayerSession> byUuid = new ConcurrentHashMap<>();
    private final Map<String, EaglerPlayerSession> byName =
            Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

    /**
     * Registers a session. Returns null on success or a rejection reason when
     * the username is already taken.
     */
    public String register(EaglerPlayerSession session) {
        String name = session.getName();
        synchronized (byName) {
            EaglerPlayerSession existing = byName.get(name);
            if (existing != null && existing.isConnected()) {
                return "username \"" + name + "\" is already online";
            }
            // a dead session with the same name may linger from a race; replace it
            if (existing != null) {
                existing.teardown("replaced by a new session with the same name");
                byUuid.remove(existing.getUniqueId());
            }
            byName.put(name, session);
            byUuid.put(session.getUniqueId(), session);
            return null;
        }
    }

    /** Removes a session; returns true when it was actually registered. */
    public boolean unregister(EaglerPlayerSession session) {
        boolean removed = byUuid.remove(session.getUniqueId(), session);
        synchronized (byName) {
            EaglerPlayerSession existing = byName.get(session.getName());
            if (existing == session) {
                byName.remove(session.getName());
            }
        }
        return removed;
    }

    /** Case-insensitive username lookup. */
    public EaglerPlayerSession byName(String name) {
        return byName.get(name);
    }

    public EaglerPlayerSession byUuid(UUID uuid) {
        return byUuid.get(uuid);
    }

    public int count() {
        return byUuid.size();
    }

    /** Snapshot of all sessions. */
    public Collection<EaglerPlayerSession> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(byUuid.values()));
    }
}
