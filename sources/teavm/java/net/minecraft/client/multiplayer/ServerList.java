package net.minecraft.client.multiplayer;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.multiplayer.
 * ServerData + ServerList (merged - the port needs the persisted list
 * and its entries, not the join protocol internals).
 *
 * <p>Vanilla semantics: {@link ServerData} is one entry (name, ip) and
 * {@link ServerList} owns the ordered list, loaded once at client start
 * from storage and saved after every mutation (vanilla stores
 * servers.dat; the port encodes the same fields into localStorage as
 * {@code serverlist} - entries joined by newlines, name/address split
 * by \\u0001, control characters stripped on entry).</p>
 */
public class ServerList {

        /** One entry (vanilla ServerData): display name + address. */
        public static final class ServerData {
                public String name;
                public String ip;

                public ServerData(String name, String ip) {
                        this.name = name;
                        this.ip = ip;
                }
        }

        private static final String STORAGE_KEY = "serverlist";

        /** The loaded list (vanilla ServerList.serverListEntries). */
        private final List<ServerData> entries = new ArrayList<ServerData>();

        /** Loads the list from localStorage (vanilla ServerList.load). */
        public void load() {
                this.entries.clear();
                String raw = PlatformRuntime.localStorageGet(STORAGE_KEY);
                if (raw == null || raw.isEmpty()) {
                        return;
                }
                for (String line : raw.split("\n")) {
                        int split = line.indexOf('\u0001');
                        if (split <= 0) {
                                continue;
                        }
                        this.entries.add(new ServerData(line.substring(0, split),
                                        line.substring(split + 1)));
                }
        }

        /** Persists the list to localStorage (vanilla ServerList.save). */
        public void save() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < this.entries.size(); i++) {
                        ServerData data = this.entries.get(i);
                        if (i > 0) {
                                sb.append('\n');
                        }
                        sb.append(sanitize(data.name)).append('\u0001')
                                        .append(sanitize(data.ip));
                }
                PlatformRuntime.localStorageSet(STORAGE_KEY, sb.toString());
        }

        /** Strips the storage separators (defensive - vanilla validates too). */
        private static String sanitize(String value) {
                return value.replace("\n", " ").replace("\u0001", " ").trim();
        }

        public int size() {
                return this.entries.size();
        }

        public ServerData get(int index) {
                return (index >= 0 && index < this.entries.size())
                                ? this.entries.get(index) : null;
        }

        /** Vanilla ServerList.add (appends at the end). */
        public void add(ServerData data) {
                this.entries.add(data);
                this.save();
        }

        /** Vanilla ServerList.remove. */
        public void remove(int index) {
                if (index >= 0 && index < this.entries.size()) {
                        this.entries.remove(index);
                        this.save();
                }
        }

        /** Vanilla ServerList.replace (edit flow: update + save). */
        public void replace(int index, ServerData data) {
                if (index >= 0 && index < this.entries.size()) {
                        this.entries.set(index, data);
                        this.save();
                }
        }

        /**
         * Vanilla ServerList.swap (move up/down with the arrows). Returns
         * false when the swap is out of bounds (list unmutated).
         */
        public boolean swap(int index, int other) {
                if (index < 0 || other < 0
                                || index >= this.entries.size()
                                || other >= this.entries.size() || index == other) {
                        return false;
                }
                ServerData tmp = this.entries.get(index);
                this.entries.set(index, this.entries.get(other));
                this.entries.set(other, tmp);
                this.save();
                return true;
        }

        /** The shared client-side list instance (vanilla keeps one per join screen). */
        private static ServerList singleton;

        public static synchronized ServerList getOrLoad() {
                if (singleton == null) {
                        singleton = new ServerList();
                        singleton.load();
                }
                return singleton;
        }

        /** Reloads the shared list (the Refresh button path). */
        public static synchronized void reload() {
                if (singleton == null) {
                        getOrLoad();
                        return;
                }
                singleton.load();
        }
}
