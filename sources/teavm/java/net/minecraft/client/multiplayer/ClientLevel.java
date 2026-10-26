package net.minecraft.client.multiplayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * EaglerCraft 26.1.2 browser override for ClientLevel, the client-side
 * representation of a loaded world.
 *
 * <h3>Block storage</h3>
 *
 * <p>Voxel storage mirrors vanilla's section layout: 16x16x16 sections
 * addressed by packed (sectionX, sectionY, sectionZ) longs, with the
 * vanilla overworld vertical range y=-64..319 (24 section rows). A
 * section's byte array is allocated only when it becomes non-default,
 * so the superflat base costs a handful of sections. Reads outside any
 * allocated section fall through to the generated base: the vanilla
 * flat preset (FlatLevelGeneratorSettings.getDefault) - bedrock at
 * y=-64, two dirt layers, grass at y=-61, plains biome, everything
 * above air.</p>
 *
 * <h3>Persistence</h3>
 *
 * <p>Player edits are kept as a per-position overlay (packed BlockPos
 * to block id) and serialized under one localStorage key per world -
 * the browser stand-in for vanilla's region files. Only diffs against
 * the generated base are stored, so an unedited world costs nothing
 * and a mined-out trench costs one record per block.</p>
 */
public class ClientLevel {

        /** Vanilla overworld bounds (LevelHeightAccessor): min_y=-64, height=384. */
        public static final int MIN_Y = -64;
        public static final int MAX_Y = 319; // inclusive
        public static final int SECTION_SHIFT = 4;
        public static final int SECTION_SIZE = 16;

        private final String levelName;
        private final String dimensionId;

        private long gameTime;
        private long dayTime;

        /**
         * The vanilla world difficulty: 0=peaceful, 1=easy, 2=normal
         * (the fresh-world default), 3=hard. Edited from the in-world
         * Options header difficulty CycleButton.
         */
        private int difficulty = 2;

        /** Vanilla GameType: 0=survival, 1=creative, 3=spectator. */
        private int gameMode = 0;

        private double spawnX;
        private double spawnY;
        private double spawnZ;

        /** Allocated sections: packed section coord -> 4096 block ids. */
        private final Map<Long, byte[]> sections = new HashMap<Long, byte[]>();

        /** Player edits over the generated base: packed BlockPos -> block id.
         * In remote (multiplayer) mode this overlay carries the entire
         * server-fed world instead: the mesher and collision read through
         * getBlockState, so edits give full block-id range where the byte
         * sections would truncate ids > 255. */
        private final Map<Long, Integer> edits = new HashMap<Long, Integer>();

        /** Remote mode: packed column key (chunkX, 0, chunkZ) -> the packed
         * voxel keys this column's streamed sections contributed. Unload
         * Chunk (S→C 0x1F) drains the list to forget the column. */
        private final Map<Long, List<Long>> columnEdits = new HashMap<Long, List<Long>>();

        /** Sections whose mesh needs a rebuild (consumed by LevelRenderer). */
        private final Set<Long> dirtySections = new HashSet<Long>();

        private final String storageKey;

        /** True for server-fed (multiplayer) worlds: no generated base, no
         * localStorage persistence, missing chunks read as air. */
        private final boolean remote;

        public ClientLevel(String levelName, String dimensionId) {
                this(levelName, dimensionId, false);
        }

        private ClientLevel(String levelName, String dimensionId, boolean remote) {
                this.levelName = levelName != null ? levelName : "New World";
                this.dimensionId = dimensionId != null ? dimensionId : "minecraft:overworld";
                this.remote = remote;
                this.gameTime = 0L;
                this.dayTime = 1000L; // dawn, like a fresh vanilla world
                // Spawn on the flat preset's grass surface (y=-61 top face),
                // centered in the world like vanilla's shared spawn point
                this.spawnX = 0.5D;
                this.spawnY = flatTopY() + 1.0D;
                this.spawnZ = 0.5D;
                this.storageKey = remote ? null
                        : "eaglercraft.world.blocks."
                                + sanitize(this.levelName);
                if (!remote) {
                        loadEdits();
                        loadDayTime();
                }
                this.dirtySections.add(sectionKey(0, sectionIndex(this.MIN_Y), 0));
        }

        /**
         * A server-fed multiplayer world (the real gateway protocol's
         * play phase): no generated base and no persistence - every
         * voxel comes from Level Chunk packets and dies with the
         * session. The spawn point arrives with the play Login packet
         * ({@link #setSpawn}).
         */
        public static ClientLevel createRemote(String host) {
                return new ClientLevel("Server: " + host,
                                "minecraft:overworld", true);
        }

        /** True when the world's blocks are server-authoritative. */
        public boolean isRemote() {
                return this.remote;
        }

        /**
         * Remote worlds: whether any server geometry has landed yet. The
         * client holds the player's physics (vanilla's "Loading terrain"
         * hold) until the first streamed chunk applies, so a slow frame
         * between the play Login and the chunk burst can't drop the
         * player into the void.
         */
        public boolean hasServerChunks() {
                return !this.sections.isEmpty() || !this.edits.isEmpty();
        }

        /** Remote worlds: the gateway's spawn point (play Login packet). */
        public void setSpawn(double x, double y, double z) {
                this.spawnX = x;
                this.spawnY = y;
                this.spawnZ = z;
        }

        /** Highest solid y of the flat preset (-61, the grass layer). */
        public static int flatTopY() {
                return MIN_Y + 3;
        }

        private static String sanitize(String name) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < name.length(); i++) {
                        char c = name.charAt(i);
                        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                                        || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                                sb.append(c);
                        } else {
                                sb.append('_');
                        }
                }
                return sb.toString();
        }

        // ========== Section addressing ==========

        public static long sectionKey(int sectionX, int sectionY, int sectionZ) {
                return BlockPos.asLong(sectionX, sectionY, sectionZ);
        }

        public static int sectionIndex(int y) {
                return y >> SECTION_SHIFT;
        }

        private static int indexInSection(int x, int y, int z) {
                return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }

        private byte[] sectionFor(long key) {
                byte[] section = sections.get(key);
                if (section == null) {
                        section = new byte[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
                        // Materialize the generated preset - vanilla fills a
                        // fresh section from the ChunkSource generator. A
                        // zero array would read as air and drop the whole
                        // 16^3 region out of collision/raycast/mesh reads
                        // the moment the first edit materializes it.
                        int baseX = BlockPos.getX(key) << SECTION_SHIFT;
                        int baseY = BlockPos.getY(key) << SECTION_SHIFT;
                        int baseZ = BlockPos.getZ(key) << SECTION_SHIFT;
                        for (int y = 0; y < SECTION_SIZE; y++) {
                                int worldY = baseY + y;
                                if (worldY < MIN_Y || worldY > MAX_Y) {
                                        continue;
                                }
                                // The flat preset is x/z-uniform: one layer value
                                byte preset = (byte) generatedBlock(baseX, worldY, baseZ);
                                if (preset == Blocks.AIR) {
                                        continue;
                                }
                                int layerBase = y << 8;
                                for (int z = 0; z < SECTION_SIZE; z++) {
                                        int rowBase = layerBase | (z << 4);
                                        for (int x = 0; x < SECTION_SIZE; x++) {
                                                section[rowBase | x] = preset;
                                        }
                                }
                        }
                        sections.put(key, section);
                }
                return section;
        }

        // ========== Block access (vanilla BlockGetter semantics) ==========

        /** Vanilla getBlockState: the block id at the position. */
        public int getBlockState(int x, int y, int z) {
                if (y < MIN_Y || y > MAX_Y) {
                        return Blocks.AIR;
                }
                Long editKey = BlockPos.asLong(x, y, z);
                Integer edit = edits.get(editKey);
                if (edit != null) {
                        return edit.intValue();
                }
                long secKey = sectionKey(x >> SECTION_SHIFT, sectionIndex(y), z >> SECTION_SHIFT);
                byte[] section = sections.get(secKey);
                if (section != null) {
                        return section[indexInSection(x, y, z)] & 0xFF;
                }
                // Remote worlds have no generated base: unloaded space is air
                return this.remote ? Blocks.AIR : generatedBlock(x, y, z);
        }

        /** The vanilla flat preset base (FlatLevelGeneratorSettings.getDefault). */
        public int generatedBlock(int x, int y, int z) {
                if (y == MIN_Y) {
                        return Blocks.BEDROCK;
                }
                if (y == MIN_Y + 1 || y == MIN_Y + 2) {
                        return Blocks.DIRT;
                }
                if (y == MIN_Y + 3) {
                        return Blocks.GRASS_BLOCK;
                }
                return Blocks.AIR;
        }

        /**
         * Vanilla setBlock with the flags the client path uses (block
         * update + send to clients). Marks the owning section dirty, plus
         * neighbor sections when the position touches a section border
         * (their face culling depends on this block).
         */
        public void setBlockState(int x, int y, int z, int blockId) {
                if (y < MIN_Y || y > MAX_Y) {
                        return;
                }
                int secX = x >> SECTION_SHIFT;
                int secY = sectionIndex(y);
                int secZ = z >> SECTION_SHIFT;
                Long editKey = BlockPos.asLong(x, y, z);
                if (blockId == generatedBlock(x, y, z)) {
                        edits.remove(editKey); // revert to base: drop the edit
                } else {
                        edits.put(editKey, Integer.valueOf(blockId));
                }
                byte[] section = sectionFor(sectionKey(secX, secY, secZ));
                section[indexInSection(x, y, z)] = (byte) blockId;

                markDirty(secX, secY, secZ);
                int lx = x & 15;
                int ly = y & 15;
                int lz = z & 15;
                if (lx == 0) markDirty(secX - 1, secY, secZ);
                if (lx == 15) markDirty(secX + 1, secY, secZ);
                if (ly == 0) markDirty(secX, secY - 1, secZ);
                if (ly == 15) markDirty(secX, secY + 1, secZ);
                if (lz == 0) markDirty(secX, secY, secZ - 1);
                if (lz == 15) markDirty(secX, secY, secZ + 1);
        }

        private void markDirty(int secX, int secY, int secZ) {
                this.dirtySections.add(sectionKey(secX, secY, secZ));
        }

        /** Pops the next dirty section key for meshing (null when clean). */
        public Long pollDirtySection() {
                if (dirtySections.isEmpty()) {
                        return null;
                }
                Long next = dirtySections.iterator().next();
                dirtySections.remove(next);
                return next;
        }

        /** Marks everything around a position dirty (level load / blob restore). */
        public void markRangeDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
                for (int sx = minX >> SECTION_SHIFT; sx <= maxX >> SECTION_SHIFT; sx++) {
                        for (int sy = sectionIndex(minY); sy <= sectionIndex(maxY); sy++) {
                                for (int sz = minZ >> SECTION_SHIFT; sz <= maxZ >> SECTION_SHIFT; sz++) {
                                        markDirty(sx, sy, sz);
                                }
                        }
                }
        }

        // ========== Edit persistence (localStorage stand-in for region files) ==========

        private void loadEdits() {
                String raw = PlatformRuntime.localStorageGet(storageKey);
                if (raw == null || raw.isEmpty()) {
                        return;
                }
                try {
                        int i = 0;
                        while (i < raw.length()) {
                                int semi = raw.indexOf(';', i);
                                if (semi < 0) {
                                        semi = raw.length();
                                }
                                if (semi > i) {
                                        int[] parts = parseQuad(raw, i, semi);
                                        if (parts != null) {
                                                // The edits map is the read-side source
                                                // of truth (checked before sections in
                                                // getBlockState); no section allocation
                                                // needed at load time
                                                edits.put(BlockPos.asLong(parts[0], parts[1], parts[2]),
                                                        Integer.valueOf(parts[3]));
                                        }
                                }
                                i = semi + 1;
                        }
                        if (!edits.isEmpty()) {
                                ClientMain.log("[ClientLevel] restored " + edits.size()
                                        + " block edits for " + levelName);
                                markRangeDirty(-16, MIN_Y, -16, 16, flatTopY() + 1, 16);
                        }
                } catch (Throwable t) {
                        ClientMain.warn("[ClientLevel] edit restore failed: " + t.getMessage());
                }
        }

        /** Parses "x,y,z,id" between the offsets; null when malformed. */
        private static int[] parseQuad(String raw, int start, int end) {
                int[] values = new int[4];
                int vi = 0;
                int tokenStart = start;
                for (int i = start; i <= end; i++) {
                        if (i == end || raw.charAt(i) == ',') {
                                if (i == tokenStart) {
                                        return null;
                                }
                                try {
                                        values[vi++] = Integer.parseInt(raw.substring(tokenStart, i));
                                } catch (NumberFormatException nfe) {
                                        return null;
                                }
                                if (vi > 4) {
                                        return null;
                                }
                                tokenStart = i + 1;
                        }
                }
                return vi == 4 ? values : null;
        }

        /** Writes the edit overlay back to localStorage (called on save-and-quit). */
        public void saveEdits() {
                if (this.remote || storageKey == null) {
                        return; // server-authoritative worlds are never persisted
                }
                if (edits.isEmpty()) {
                        PlatformRuntime.localStorageSet(storageKey, "");
                        return;
                }
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<Long, Integer> e : edits.entrySet()) {
                        long packed = e.getKey().longValue();
                        if (sb.length() > 0) {
                                sb.append(';');
                        }
                        sb.append(BlockPos.getX(packed));
                        sb.append(',');
                        sb.append(BlockPos.getY(packed));
                        sb.append(',');
                        sb.append(BlockPos.getZ(packed));
                        sb.append(',');
                        sb.append(e.getValue().intValue());
                }
                String data = sb.toString();
                // The localStorage layer swallows quota errors silently;
                // the edits stay in memory either way (they just won't
                // survive a reload if the write failed)
                PlatformRuntime.localStorageSet(storageKey, data);
                ClientMain.log("[ClientLevel] saved " + edits.size() + " block edits");
        }

        public int editCount() {
                return edits.size();
        }

        // ========== Day-time persistence (vanilla level.dat Data.day) ==========

        private String timeKey() {
                return "eaglercraft.world.time." + sanitize(levelName);
        }

        /** Restores the persisted day time; 1000 (dawn, day 0) for fresh worlds. */
        private void loadDayTime() {
                String raw = PlatformRuntime.localStorageGet(timeKey());
                if (raw == null || raw.isEmpty()) {
                        return;
                }
                try {
                        long t = Long.parseLong(raw.trim());
                        this.dayTime = Math.max(0L, t);
                        ClientMain.log("[ClientLevel] restored day time " + this.dayTime);
                } catch (NumberFormatException nfe) {
                        ClientMain.warn("[ClientLevel] bad day time '" + raw + "'");
                }
        }

        /** Persists the day time (called with the edit save on save-and-quit). */
        public void saveDayTime() {
                if (this.remote || storageKey == null) {
                        return;
                }
                PlatformRuntime.localStorageSet(timeKey(), Long.toString(dayTime));
        }

        // ========== Server-fed world state (the real gateway protocol) ==========

        private List<Long> columnList(int chunkX, int chunkZ) {
                Long columnKey = BlockPos.asLong(chunkX, 0, chunkZ);
                List<Long> list = columnEdits.get(columnKey);
                if (list == null) {
                        list = new java.util.ArrayList<Long>();
                        columnEdits.put(columnKey, list);
                }
                return list;
        }

        /**
         * Applies one streamed server section (the gateway's Level Chunk
         * pinned layout): {@code voxels[i]} indexes {@code palette}, one
         * byte per voxel in vanilla YZX order
         * {@code i = ((y&15)<<8) | ((z&15)<<4) | (x&15)}. Non-air voxels
         * enter the edit overlay (full block-id range) and their sections
         * (plus the horizontal neighbors, for face culling) are marked
         * dirty for meshing.
         */
        public void applyServerSection(int chunkX, int sectionY, int chunkZ,
                        int[] palette, byte[] voxels) {
                // Palettes entirely within the byte range (the superflat
                // base: bedrock/dirt/grass) store natively in the section
                // array - 4 KiB flat bytes instead of thousands of boxed
                // edit entries. Palettes carrying high ids (oak logs 351,
                // leaves 359) keep the edit-overlay path.
                boolean overId = false;
                for (int id : palette) {
                        if (id > 255) {
                                overId = true;
                                break;
                        }
                }
                if (!overId) {
                        byte[] section = sectionFor(sectionKey(chunkX, sectionY, chunkZ));
                        boolean any = false;
                        for (int i = 0; i < 4096; i++) {
                                int idx = voxels[i] & 0xFF;
                                int id = idx < palette.length ? palette[idx] : Blocks.AIR;
                                if (id != Blocks.AIR) {
                                        any = true;
                                }
                                section[i] = (byte) id;
                        }
                        if (any) {
                                markServerSectionRows(chunkX, sectionY, chunkZ, voxels, palette);
                        }
                        return;
                }
                int baseY = this.MIN_Y + (sectionY << SECTION_SHIFT);
                boolean any = false;
                for (int i = 0; i < 4096; i++) {
                        int idx = voxels[i] & 0xFF;
                        if (idx >= palette.length) {
                                continue;
                        }
                        int id = palette[idx];
                        if (id == Blocks.AIR) {
                                continue;
                        }
                        int x = (chunkX << SECTION_SHIFT) + (i & 15);
                        int y = baseY + ((i >> 8) & 15);
                        int z = (chunkZ << SECTION_SHIFT) + ((i >> 4) & 15);
                        Long key = BlockPos.asLong(x, y, z);
                        edits.put(key, Integer.valueOf(id));
                        columnList(chunkX, chunkZ).add(key);
                        any = true;
                }
                if (!any) {
                        return;
                }
                markServerSectionRows(chunkX, sectionIndex(baseY), chunkZ, voxels, palette);
        }

        /** Marks the section rows that carry blocks, plus their horizontal
         * neighbors (face culling across chunk borders). */
        private void markServerSectionRows(int chunkX, int sy, int chunkZ,
                        byte[] voxels, int[] palette) {
                for (int row = 0; row < 4096; row += 256) {
                        boolean rowHas = false;
                        for (int i = row; i < row + 256; i++) {
                                int p = voxels[i] & 0xFF;
                                if (p < palette.length && palette[p] != Blocks.AIR) {
                                        rowHas = true;
                                        break;
                                }
                        }
                        if (rowHas) {
                                int ry = sy + (row >> 8);
                                markDirty(chunkX, ry, chunkZ);
                                markDirty(chunkX - 1, ry, chunkZ);
                                markDirty(chunkX + 1, ry, chunkZ);
                                markDirty(chunkX, ry, chunkZ - 1);
                                markDirty(chunkX, ry, chunkZ + 1);
                        }
                }
        }

        /**
         * A server-authoritative single block change (Block Update,
         * S→C 0x05): edits the overlay and marks the owning section and
         * its border neighbors dirty - the same neighbor rule the local
         * edit path uses.
         */
        public void applyServerBlockUpdate(int x, int y, int z, int blockId) {
                if (y < this.MIN_Y || y > this.MAX_Y) {
                        return;
                }
                Long key = BlockPos.asLong(x, y, z);
                int secX = x >> SECTION_SHIFT;
                int secY = sectionIndex(y);
                int secZ = z >> SECTION_SHIFT;
                long secKey = sectionKey(secX, secY, secZ);
                int idx = indexInSection(x, y, z);
                if (blockId == Blocks.AIR) {
                        edits.remove(key); // the server's air overrides the overlay
                        byte[] section = sections.get(secKey);
                        if (section != null) {
                                section[idx] = 0;
                        }
                } else if (blockId <= 255) {
                        edits.remove(key); // section bytes win where no edit exists
                        sectionFor(secKey)[idx] = (byte) blockId;
                } else {
                        edits.put(key, Integer.valueOf(blockId));
                        columnList(x >> SECTION_SHIFT, z >> SECTION_SHIFT).add(key);
                }

                markDirty(secX, secY, secZ);
                int lx = x & 15;
                int ly = y & 15;
                int lz = z & 15;
                if (lx == 0) markDirty(secX - 1, secY, secZ);
                if (lx == 15) markDirty(secX + 1, secY, secZ);
                if (ly == 0) markDirty(secX, secY - 1, secZ);
                if (ly == 15) markDirty(secX, secY + 1, secZ);
                if (lz == 0) markDirty(secX, secY, secZ - 1);
                if (lz == 15) markDirty(secX, secY, secZ + 1);
        }

        /**
         * Forgets a streamed column (Unload Chunk, S→C 0x1F): drops the
         * column's server-fed voxels from the overlay and re-meshes.
         */
        public void unloadServerColumn(int chunkX, int chunkZ) {
                List<Long> column = columnEdits.remove(
                                BlockPos.asLong(chunkX, 0, chunkZ));
                boolean any = column != null && !column.isEmpty();
                if (column != null) {
                        for (Long key : column) {
                                if (edits.remove(key) != null) {
                                        any = true;
                                }
                        }
                }
                // drop the column's native sections as well
                for (int sy = 0; sy < (this.MAX_Y - this.MIN_Y + 1) >> SECTION_SHIFT; sy++) {
                        if (sections.remove(sectionKey(chunkX, sy, chunkZ)) != null) {
                                any = true;
                        }
                }
                if (any) {
                        markRangeDirty(chunkX << SECTION_SHIFT, this.MIN_Y,
                                        chunkZ << SECTION_SHIFT,
                                        (chunkX << SECTION_SHIFT) + 15,
                                        this.MAX_Y,
                                        (chunkZ << SECTION_SHIFT) + 15);
                }
        }

        // ========== Collision (vanilla Shapes.collide for full cubes) ==========

        /**
         * Whether any solid block overlaps the given box - the vanilla
         * {@code Level.noCollision} / {@code isUnobstructed} primitive the
         * movement and placement paths both build on.
         */
        public boolean noCollision(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
                int x0 = floor(minX);
                int x1 = floor(maxX); // exclusive upper bound handling below
                int y0 = floor(minY);
                int y1 = floor(maxY);
                int z0 = floor(minZ);
                int z1 = floor(maxZ);
                for (int x = x0; x <= x1; x++) {
                        for (int y = y0; y <= y1; y++) {
                                for (int z = z0; z <= z1; z++) {
                                        if (!isFreeForCollision(x, y, z)) {
                                                // Strict overlap test: the block cube
                                                // [x,x+1) vs the query box
                                                if (minX < x + 1.0D && maxX > (double) x
                                                        && minY < y + 1.0D && maxY > (double) y
                                                        && minZ < z + 1.0D && maxZ > (double) z) {
                                                        return false;
                                                }
                                        }
                                }
                        }
                }
                return true;
        }

        private static int floor(double v) {
                int i = (int) v;
                return v < (double) i ? i - 1 : i;
        }

        private boolean isFreeForCollision(int x, int y, int z) {
                return getBlockState(x, y, z) == Blocks.AIR;
        }

        // ========== Raycast (vanilla BlockGetter.clip traverseBlocks) ==========

        /**
         * Vanilla {@code BlockGetter.clip} for full-cube collision shapes:
         * the decompiled traverseBlocks voxel DDA, verbatim, including the
         * -1.0E-7 endpoint nudges that keep block borders deterministic.
         */
        public BlockHitResult clip(double fromX, double fromY, double fromZ,
                        double toX, double toY, double toZ) {
                if (fromX == toX && fromY == toY && fromZ == toZ) {
                        return missAt(toX, toY, toZ);
                }
                double startToX = toX - 1.0E-7D * (toX - fromX);
                double startToY = toY - 1.0E-7D * (toY - fromY);
                double startToZ = toZ - 1.0E-7D * (toZ - fromZ);
                double toStartX = fromX - 1.0E-7D * (fromX - toX);
                double toStartY = fromY - 1.0E-7D * (fromY - toY);
                double toStartZ = fromZ - 1.0E-7D * (fromZ - toZ);

                int blockX = floor(toStartX);
                int blockY = floor(toStartY);
                int blockZ = floor(toStartZ);

                BlockHitResult first = checkBlock(fromX, fromY, fromZ, toX, toY, toZ,
                        blockX, blockY, blockZ, (Direction) null);
                if (first != null) {
                        return first;
                }

                double dx = startToX - toStartX;
                double dy = startToY - toStartY;
                double dz = startToZ - toStartZ;
                int signX = sign(dx);
                int signY = sign(dy);
                int signZ = sign(dz);
                double tDeltaX = signX == 0 ? Double.MAX_VALUE : (double) signX / dx;
                double tDeltaY = signY == 0 ? Double.MAX_VALUE : (double) signY / dy;
                double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : (double) signZ / dz;
                double tX = tDeltaX * (signX > 0 ? 1.0D - frac(toStartX) : frac(toStartX));
                double tY = tDeltaY * (signY > 0 ? 1.0D - frac(toStartY) : frac(toStartY));
                double tZ = tDeltaZ * (signZ > 0 ? 1.0D - frac(toStartZ) : frac(toStartZ));

                while (tX <= 1.0D || tY <= 1.0D || tZ <= 1.0D) {
                        Direction face;
                        if (tX < tY) {
                                if (tX < tZ) {
                                        blockX += signX;
                                        tX += tDeltaX;
                                        face = signX > 0 ? Direction.WEST : Direction.EAST;
                                } else {
                                        blockZ += signZ;
                                        tZ += tDeltaZ;
                                        face = signZ > 0 ? Direction.NORTH : Direction.SOUTH;
                                }
                        } else if (tY < tZ) {
                                blockY += signY;
                                tY += tDeltaY;
                                face = signY > 0 ? Direction.DOWN : Direction.UP;
                        } else {
                                blockZ += signZ;
                                tZ += tDeltaZ;
                                face = signZ > 0 ? Direction.NORTH : Direction.SOUTH;
                        }

                        BlockHitResult hit = checkBlock(fromX, fromY, fromZ, toX, toY, toZ,
                                blockX, blockY, blockZ, face);
                        if (hit != null) {
                                return hit;
                        }
                }
                return missAt(toX, toY, toZ);
        }

        private BlockHitResult checkBlock(double fromX, double fromY, double fromZ,
                        double toX, double toY, double toZ, int x, int y, int z, Direction face) {
                if (getBlockState(x, y, z) != Blocks.AIR) {
                        if (face == null) {
                                // Vanilla: ray started inside a block - the hit
                                // reports the entry face of the *first* crossing,
                                // approximated by the ray's dominant direction
                                double vx = toX - fromX;
                                double vy = toY - fromY;
                                double vz = toZ - fromZ;
                                double ax = Math.abs(vx);
                                double ay = Math.abs(vy);
                                double az = Math.abs(vz);
                                if (ay >= ax && ay >= az) {
                                        face = vy > 0.0D ? Direction.DOWN : Direction.UP;
                                } else if (ax >= az) {
                                        face = vx > 0.0D ? Direction.WEST : Direction.EAST;
                                } else {
                                        face = vz > 0.0D ? Direction.NORTH : Direction.SOUTH;
                                }
                        }
                        return BlockHitResult.block(fromX, fromY, fromZ, x, y, z, face, false);
                }
                return null;
        }

        private static BlockHitResult missAt(double x, double y, double z) {
                return BlockHitResult.miss(x, y, z, floor(x), floor(y), floor(z));
        }

        private static int sign(double v) {
                if (v == 0.0D) {
                        return 0;
                }
                return v > 0.0D ? 1 : -1;
        }

        private static double frac(double v) {
                double f = v - Math.floor(v);
                return f < 0.0D ? 0.0D : f;
        }

        // ========== Friction (vanilla Entity.getBlockPosBelowThatAffectsMyMovement) ==========

        /** The block whose friction drives ground movement (0.5 block below feet). */
        public int getBlockStateBelowForMovement(double x, double y, double z) {
                return getBlockState(floor(x), floor(y - 0.5000001D), floor(z));
        }

        // ========== Game clock ==========

        /**
         * Advances the world clock one tick (1/20 s). Called from
         * {@code Minecraft.tick()}. The counter runs FREE like vanilla's
         * level.dat Data.day (never wraps): the timeline samples
         * {@code dayTime % 24000} for the time of day and
         * {@code dayTime / 24000} for the day count (moon phase).
         */
        public void tick() {
                this.gameTime++;
                this.dayTime++;
        }

        /**
         * Vanilla /time set equivalent (the telemetry bridge's setDayTime).
         * Accepts the full counter value: {@code setDayTime(30000)} is
         * day 1, time-of-day 6000 (noon, waning-gibbous day count).
         */
        public void setDayTime(long t) {
                this.dayTime = Math.max(0L, t);
        }

        // ========== Accessors ==========

        public String getLevelName() {
                return levelName;
        }

        public String getDimensionId() {
                return dimensionId;
        }

        public long getGameTime() {
                return gameTime;
        }

        public long getDayTime() {
                return dayTime;
        }

        /** Vanilla world difficulty: 0=peaceful, 1=easy, 2=normal, 3=hard. */
        public int getDifficulty() {
                return difficulty;
        }

        public void setDifficulty(int difficulty) {
                this.difficulty = difficulty & 3;
        }

        /** Vanilla GameType: 0=survival, 1=creative, 3=spectator. */
        public int getGameMode() {
                return gameMode;
        }

        public void setGameMode(int gameMode) {
                this.gameMode = gameMode;
        }

        public boolean isCreative() {
                return gameMode == 1;
        }

        public double getSpawnX() {
                return spawnX;
        }

        public double getSpawnY() {
                return spawnY;
        }

        public double getSpawnZ() {
                return spawnZ;
        }

        @Override
        public String toString() {
                return "ClientLevel[" + levelName + " in " + dimensionId
                        + ", gameTime=" + gameTime + ", edits=" + edits.size() + "]";
        }
}
