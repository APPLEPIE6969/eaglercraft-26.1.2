package net.lax1dude.eaglercraft.v2_6;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * Browser world persistence for EaglerCraft 26.1.2 - the localStorage
 * stand-in for vanilla's {@code LevelStorageSource} save directory.
 *
 * <p>Created worlds are listed under one localStorage key; each record
 * carries the metadata the Select World screen shows (name, last-played
 * date, game mode, version) plus the creation settings (seed, cheats,
 * structures, bonus chest). Records are separated by {@code \n} and
 * fields by {@code \u0001} - both unreachable through the keyboard's
 * printable-character path, so typed world names can't corrupt the
 * file. The list is seeded with the classic "New World" milestone
 * entry on first boot, exactly the world this build shipped before
 * persistence existed.</p>
 */
public final class WorldStorage {

        private static final String KEY = "eaglercraft.worlds";
        private static final String SEED_FLAG = "eaglercraft.worlds.seeded";
        /** Record/field separators. String form for concatenation, char
         * form for the manual scans (TeaVM mis-emits String + char concat,
         * so the two forms are kept apart deliberately). */
        private static final String FIELD_SEP = "\u0001";
        private static final String RECORD_SEP = "\n";
        private static final char FIELD_SEP_CHAR = '\u0001';
        private static final char RECORD_SEP_CHAR = '\n';

        /** One entry of the Select World list (vanilla LevelSummary). */
        public static class WorldSummary {
                public String name;
                public String seed;
                public String gameMode;
                public boolean allowCommands;
                public boolean generateStructures;
                public boolean bonusChest;
                public long lastPlayed;
                public String version = "26.1.2";
        }

        private WorldStorage() {
        }

        /** Reads every stored world, oldest creation first (vanilla sorts newest first; we append). */
        public static WorldSummary[] listWorlds() {
                String raw = PlatformRuntime.localStorageGet(KEY);
                if (raw == null || raw.isEmpty()) {
                        return new WorldSummary[0];
                }
                String[] records = splitRecords(raw);
                int count = 0;
                for (int i = 0; i < records.length; i++) {
                        if (!records[i].isEmpty()) count++;
                }
                WorldSummary[] out = new WorldSummary[count];
                int outi = 0;
                for (int i = 0; i < records.length; i++) {
                        WorldSummary s = parse(records[i]);
                        if (s != null) {
                                out[outi++] = s;
                        }
                }
                return out;
        }

        /**
         * Adds (or renames over) a world record. Matching happens on the
         * exact name, so creating a world with an existing name replaces
         * that entry instead of duplicating it.
         */
        public static void saveWorld(WorldSummary summary) {
                if (summary == null || summary.name == null || summary.name.isEmpty()) {
                        return;
                }
                StringBuilder sb = new StringBuilder();
                WorldSummary[] existing = listWorlds();
                for (int i = 0; i < existing.length; i++) {
                        if (summary.name.equals(existing[i].name)) {
                                continue; // replaced below
                        }
                        sb.append(serialize(existing[i]));
                        sb.append(RECORD_SEP);
                }
                sb.append(serialize(summary));
                PlatformRuntime.localStorageSet(KEY, sb.toString());
        }

        /**
         * Refreshes a world's last-played timestamp (vanilla updates
         * lastPlayed on every world load, which drives the Select World
         * date line). No-op when the world is unknown.
         */
        public static void touchWorld(String name) {
                WorldSummary s = findWorld(name);
                if (s != null) {
                        s.lastPlayed = System.currentTimeMillis();
                        saveWorld(s);
                }
        }

        /** Removes the record with the given name; returns whether it existed. */
        public static boolean deleteWorld(String name) {
                WorldSummary[] existing = listWorlds();
                StringBuilder sb = new StringBuilder();
                boolean found = false;
                for (int i = 0; i < existing.length; i++) {
                        if (name.equals(existing[i].name)) {
                                found = true;
                                continue;
                        }
                        if (sb.length() > 0) {
                                sb.append(RECORD_SEP);
                        }
                        sb.append(serialize(existing[i]));
                }
                if (found) {
                        PlatformRuntime.localStorageSet(KEY, sb.toString());
                }
                return found;
        }

        /** Look up one world by exact name (null when absent). */
        public static WorldSummary findWorld(String name) {
                WorldSummary[] all = listWorlds();
                for (int i = 0; i < all.length; i++) {
                        if (all[i].name.equals(name)) {
                                return all[i];
                        }
                }
                return null;
        }

        /**
         * First-boot seed: the milestone "New World" this build always
         * shipped, so a fresh profile still has a playable entry (and the
         * Delete button can remove it, like any vanilla world).
         */
        public static void ensureSeeded() {
                if (PlatformRuntime.localStorageGet(SEED_FLAG) != null) {
                        return;
                }
                if (listWorlds().length == 0) {
                        WorldSummary milestone = new WorldSummary();
                        milestone.name = "New World";
                        milestone.seed = "";
                        milestone.gameMode = "Survival";
                        milestone.allowCommands = false;
                        milestone.generateStructures = true;
                        milestone.bonusChest = false;
                        // Real epoch (System.currentTimeMillis):
                        // PlatformRuntime.getCurrentTimeMillis is a monotonic
                        // page-uptime clock, not a wall clock
                        milestone.lastPlayed = System.currentTimeMillis();
                        saveWorld(milestone);
                        ClientMain.log("[WorldStorage] seeded milestone world");
                }
                PlatformRuntime.localStorageSet(SEED_FLAG, "1");
        }

        private static String serialize(WorldSummary s) {
                // Explicit appends, all String args: TeaVM 0.15 mis-emits
                // incremental string-concat chains that mix operand types or
                // carry ternaries (EmitException: append(C)/append(J) fed a
                // String), so both the separators and the flags are plain
                // String values pushed one append at a time.
                StringBuilder sb = new StringBuilder();
                sb.append(s.name);
                sb.append(FIELD_SEP);
                sb.append(s.seed == null ? "" : s.seed);
                sb.append(FIELD_SEP);
                sb.append(s.gameMode);
                sb.append(FIELD_SEP);
                sb.append(s.allowCommands ? "1" : "0");
                sb.append(FIELD_SEP);
                sb.append(s.generateStructures ? "1" : "0");
                sb.append(FIELD_SEP);
                sb.append(s.bonusChest ? "1" : "0");
                sb.append(FIELD_SEP);
                sb.append(String.valueOf(s.lastPlayed));
                sb.append(FIELD_SEP);
                sb.append(s.version);
                return sb.toString();
        }

        private static WorldSummary parse(String record) {
                String[] f = splitFields(record);
                if (f.length < 8 || f[0].isEmpty()) {
                        return null;
                }
                WorldSummary s = new WorldSummary();
                s.name = f[0];
                s.seed = f[1];
                s.gameMode = f[2];
                s.allowCommands = "1".equals(f[3]);
                s.generateStructures = "1".equals(f[4]);
                s.bonusChest = "1".equals(f[5]);
                try {
                        s.lastPlayed = Long.parseLong(f[6]);
                } catch (NumberFormatException nfe) {
                        s.lastPlayed = 0L;
                }
                s.version = f[7];
                return s;
        }

        /** record-splitting without regex (TeVM-friendly, no String.split compile quirks) */
        private static String[] splitRecords(String raw) {
                int count = 1;
                for (int i = 0; i < raw.length(); i++) {
                        if (raw.charAt(i) == RECORD_SEP_CHAR) count++;
                }
                String[] out = new String[count];
                int idx = 0, start = 0;
                for (int i = 0; i < raw.length(); i++) {
                        if (raw.charAt(i) == RECORD_SEP_CHAR) {
                                out[idx++] = raw.substring(start, i);
                                start = i + 1;
                        }
                }
                out[idx] = raw.substring(start);
                return out;
        }

        private static String[] splitFields(String record) {
                int count = 1;
                for (int i = 0; i < record.length(); i++) {
                        if (record.charAt(i) == FIELD_SEP_CHAR) count++;
                }
                String[] out = new String[count];
                int idx = 0, start = 0;
                for (int i = 0; i < record.length(); i++) {
                        if (record.charAt(i) == FIELD_SEP_CHAR) {
                                out[idx++] = record.substring(start, i);
                                start = i + 1;
                        }
                }
                out[idx] = record.substring(start);
                return out;
        }
}
