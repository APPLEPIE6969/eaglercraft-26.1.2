package net.lax1dude.eaglercraft.v2_6.placeholder_server;

import java.util.HashMap;
import java.util.Map;

/**
 * EaglerCraft 26.1.2 gateway - the placeholder server's playable world.
 *
 * <p>The same superflat preset the client generates locally (vanilla
 * FlatLevelGeneratorSettings.getDefault: bedrock y=-64, two dirt layers,
 * grass y=-61) plus a deterministic ring of oak trees, stored as a
 * sparse block map. Block ids MUST mirror the client's runtime id space
 * (sources/teavm/java/net/minecraft/world/level/block/Blocks.java) -
 * the wire carries client ids verbatim.</p>
 *
 * <p>Chunk columns are encoded exactly as pinned in docs/PROTOCOL.md
 * ("Gateway Field Reference - Level Chunk with Light"): per section a
 * {@code [Byte sectionY][VarInt paletteLen][VarInt palette][4096
 * palette indices]} block, indices one byte per voxel in vanilla YZX
 * order {@code i = ((y&15)<<8) | ((z&15)<<4) | (x&15)}, sectionY the
 * absolute section index {@code (y+64)>>4}.</p>
 */
public final class GatewayWorld {

        // ==== client runtime block ids (mirror Blocks.java) ====
        public static final int AIR = 0;
        public static final int STONE = 1;
        public static final int COBBLESTONE = 4;
        public static final int GRASS_BLOCK = 9;
        public static final int DIRT = 10;
        public static final int OAK_PLANKS = 15;
        public static final int BEDROCK = 33;
        public static final int OAK_LOG = 351;
        public static final int OAK_LEAVES = 359;

        public static final int MIN_Y = -64;
        public static final int FLAT_TOP_Y = MIN_Y + 3; // the grass layer (-61)

        /** Superflat base: 16x16 columns, 4 layers each. */
        private static final int BASE_BLOCK = GRASS_BLOCK; // top layer; the
        // layers below are derived from the relative y in blockAt()

        /** Deterministic tree trunks (relative to spawn 0,0). Classic oak:
         * 4 logs on the grass, a 5x5x2 leaf body, a 3x3 layer and a plus
         * cap. Fixed constants so tests can pin on them. The first trunk
         * sits within the vanilla 4.5-block reach of the spawn point -
         * "hard-refresh and punch a tree". */
        private static final int[][] TREE_TRUNKS = {
                        {3, 1}, {6, 2}, {-8, 5}, {11, -6}, {-5, -9}, {14, 8}, {-12, -3}
        };

        /** Sparse world store: packed BlockPos.long -> block id. */
        private final Map<Long, Integer> blocks = new HashMap<Long, Integer>();

        private final long seed;

        public GatewayWorld(long seed) {
                this.seed = seed;
                plantTrees();
        }

        public long getSeed() {
                return this.seed;
        }

        // ==== world queries ====

        private static long pack(int x, int y, int z) {
                return ((long) (x & 0x3FFFFFF) << 38)
                                | ((long) (z & 0x3FFFFFF) << 12)
                                | (long) (y & 0xFFF);
        }

        /** The world block at a position (the generated base when unset). */
        public int blockAt(int x, int y, int z) {
                Long key = pack(x, y, z);
                Integer id = blocks.get(key);
                if (id != null) {
                        return id.intValue();
                }
                return baseBlock(x, y, z);
        }

        /** The unmodified superflat base (trees excluded). */
        private static int baseBlock(int x, int y, int z) {
                if (y == MIN_Y) {
                        return BEDROCK;
                }
                if (y == MIN_Y + 1 || y == MIN_Y + 2) {
                        return DIRT;
                }
                if (y == FLAT_TOP_Y) {
                        return GRASS_BLOCK;
                }
                return AIR;
        }

        /** Sets a world block (server-authoritative edits). */
        public void setBlock(int x, int y, int z, int id) {
                if (id == baseBlock(x, y, z)) {
                        blocks.remove(pack(x, y, z));
                } else {
                        blocks.put(pack(x, y, z), Integer.valueOf(id));
                }
        }

        // ==== trees ====

        private void plantTrees() {
                for (int[] trunk : TREE_TRUNKS) {
                        int tx = trunk[0];
                        int tz = trunk[1];
                        int baseY = FLAT_TOP_Y + 1; // first log sits on grass
                        int height = 4;
                        for (int i = 0; i < height; i++) {
                                setBlock(tx, baseY + i, tz, OAK_LOG);
                        }
                        int topY = baseY + height - 1;
                        // 5x5x2 body around the upper trunk
                        for (int ly = topY - 1; ly <= topY; ly++) {
                                for (int dx = -2; dx <= 2; dx++) {
                                        for (int dz = -2; dz <= 2; dz++) {
                                                if (Math.abs(dx) == 2
                                                                && Math.abs(dz) == 2) {
                                                        continue; // vanilla corners
                                                }
                                                if (dx == 0 && dz == 0 && ly <= topY) {
                                                        continue; // trunk wins
                                                }
                                                if (blockAt(tx + dx, ly, tz + dz) == AIR) {
                                                        setBlock(tx + dx, ly, tz + dz,
                                                                        OAK_LEAVES);
                                                }
                                        }
                                }
                        }
                        // 3x3 layer above the trunk
                        for (int dx = -1; dx <= 1; dx++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                        if (blockAt(tx + dx, topY + 1, tz + dz) == AIR) {
                                                setBlock(tx + dx, topY + 1, tz + dz,
                                                                OAK_LEAVES);
                                        }
                                }
                        }
                        // plus-shaped cap
                        setBlock(tx, topY + 2, tz, OAK_LEAVES);
                        setBlock(tx + 1, topY + 2, tz, OAK_LEAVES);
                        setBlock(tx - 1, topY + 2, tz, OAK_LEAVES);
                        setBlock(tx, topY + 2, tz + 1, OAK_LEAVES);
                        setBlock(tx, topY + 2, tz - 1, OAK_LEAVES);
                }
        }

        /** The planted trunks (x, z pairs) - tests pin on the first one. */
        public int[][] treeTrunks() {
                return TREE_TRUNKS;
        }

        // ==== vanilla drops ====

        /**
         * The item a broken block drops (vanilla loot for the blocks this
         * world can contain): grass drops dirt, stone drops cobblestone,
         * leaves drop nothing, everything else drops itself. 0 = nothing.
         */
        public static int dropFor(int blockId) {
                switch (blockId) {
                        case GRASS_BLOCK: return DIRT;
                        case STONE: return COBBLESTONE;
                        case OAK_LEAVES: return AIR;
                        default: return blockId;
                }
        }

        // ==== chunk encoding (docs/PROTOCOL.md pinned layout) ====

        /**
         * Encodes a chunk column's non-empty sections into the wire form.
         * Returns {chunkX, chunkZ, sectionY, palette..., 4096 indices}
         * packed as a buffer payload (without the packet id prefix).
         */
        public void encodeChunk(int chunkX, int chunkZ, ByteSink out) {
                out.writeInt(chunkX);
                out.writeInt(chunkZ);
                // This world only ever occupies section 0 (y -64..-49:
                // the 4 base layers + the tallest tree voxels). Still,
                // scan 0..2 so taller edits stream correctly.
                int sectionCount = 0;
                for (int sy = 0; sy < 3; sy++) {
                        if (sectionHasBlocks(chunkX, sy, chunkZ)) {
                                sectionCount++;
                        }
                }
                out.writeVarInt(sectionCount);
                for (int sy = 0; sy < 3; sy++) {
                        if (!sectionHasBlocks(chunkX, sy, chunkZ)) {
                                continue;
                        }
                        int baseY = MIN_Y + (sy << 4);
                        // palette: distinct ids in this section
                        Map<Integer, Integer> palette = new HashMap<Integer, Integer>();
                        java.util.List<Integer> paletteList =
                                        new java.util.ArrayList<Integer>();
                        paletteList.add(AIR); // index 0 is always air
                        palette.put(Integer.valueOf(AIR), Integer.valueOf(0));
                        byte[] voxels = new byte[4096];
                        for (int i = 0; i < 4096; i++) {
                                int x = (chunkX << 4) + (i & 15);
                                int y = baseY + ((i >> 8) & 15);
                                int z = (chunkZ << 4) + ((i >> 4) & 15);
                                int id = blockAt(x, y, z);
                                if (id == AIR) {
                                        voxels[i] = 0;
                                        continue;
                                }
                                Integer slot = palette.get(Integer.valueOf(id));
                                if (slot == null) {
                                        slot = Integer.valueOf(paletteList.size());
                                        palette.put(Integer.valueOf(id), slot);
                                        paletteList.add(Integer.valueOf(id));
                                }
                                if (paletteList.size() > 255) {
                                        throw new IllegalStateException(
                                                        "section palette overflow");
                                }
                                voxels[i] = (byte) slot.intValue();
                        }
                        out.writeByte(sy);
                        out.writeVarInt(paletteList.size());
                        for (Integer id : paletteList) {
                                out.writeVarInt(id.intValue());
                        }
                        out.writeBytes(voxels);
                }
        }

        /** Interface for the encoder's output (avoids a buffer dep here). */
        public interface ByteSink {
                void writeInt(int v);

                void writeVarInt(int v);

                void writeByte(int v);

                void writeBytes(byte[] b);
        }

        private boolean sectionHasBlocks(int chunkX, int sy, int chunkZ) {
                int baseY = MIN_Y + (sy << 4);
                for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < MIN_Y) {
                                continue;
                        }
                        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                                for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                                        if (blockAt(x, y, z) != AIR) {
                                                return true;
                                        }
                                }
                        }
                }
                return false;
        }
}
