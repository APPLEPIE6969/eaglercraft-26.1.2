package net.lax1dude.eaglercraft.v2_6.plugin.gateway_velocity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EaglerCraft 26.1.2 - registry of logged-in Eagler sessions on the Velocity
 * gateway (case-insensitive usernames, thread-safe). Authoritative for
 * duplicate-login rejection and {@code /eagler} status until full Player
 * injection lands.
 */
public final class VelocitySessionRegistry {

    private final Map<UUID, VelocityEaglerSession> byUuid = new ConcurrentHashMap<>();
    private final Map<String, VelocityEaglerSession> byName =
            Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

    /** Returns null on success or a rejection reason. */
    public String register(VelocityEaglerSession session) {
        String name = session.getName();
        synchronized (byName) {
            VelocityEaglerSession existing = byName.get(name);
            if (existing != null && existing.isConnected()) {
                return "username \"" + name + "\" is already online";
            }
            if (existing != null) {
                existing.teardown("replaced by a new session with the same name");
                byUuid.remove(existing.getUniqueId());
            }
            byName.put(name, session);
            byUuid.put(session.getUniqueId(), session);
            return null;
        }
    }

    public boolean unregister(VelocityEaglerSession session) {
        boolean removed = byUuid.remove(session.getUniqueId(), session);
        synchronized (byName) {
            VelocityEaglerSession existing = byName.get(session.getName());
            if (existing == session) {
                byName.remove(session.getName());
            }
        }
        return removed;
    }

    public VelocityEaglerSession byName(String name) {
        return byName.get(name);
    }

    public int count() {
        return byUuid.size();
    }

    public Collection<VelocityEaglerSession> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(byUuid.values()));
    }
}
