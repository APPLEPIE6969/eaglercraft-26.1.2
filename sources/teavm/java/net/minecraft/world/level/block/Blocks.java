package net.minecraft.world.level.block;

import java.util.ArrayList;
import java.util.List;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.world.level.block.Blocks.
 *
 * <p>The subset of the vanilla block registry that the superflat world
 * and the hotbar need, with vanilla's exact material constants from the
 * decompiled client jar (Blocks.java + BlockBehaviour.Properties):
 * hardness values drive the survival destroy-progress formula, friction
 * drives ground movement, requiresCorrectToolForDrops selects the 100x
 * (wrong tool) vs 30x destroy-time divisor.</p>
 *
 * <p>Grass_block carries the plains-biome grass tint (0x91BD59, the
 * vanilla GrassColormap entry at the default temperature/humidity) which
 * the renderer bakes into the grayscale grass_block_top texture.</p>
 */
public final class Blocks {

        /** Vanilla block ids (minecraft: namespace numeric ids). */
        public static final int AIR = 0;
        public static final int STONE = 1;
        public static final int GRASS_BLOCK = 9;
        public static final int DIRT = 10;
        public static final int COBBLESTONE = 4;
        public static final int OAK_PLANKS = 15;
        public static final int BEDROCK = 33;

        /** One registry entry. */
        public static final class Block {
                public final int id;
                public final String name;
                /** Destroy hardness from vanilla Properties.strength(); -1 = unbreakable. */
                public final float destroySpeed;
                /** Vanilla Block.getFriction() default (0.6); grass/dirt/stone all use it. */
                public final float friction;
                /** Vanilla requiresCorrectToolForDrops - selects the 100x divisor. */
                public final boolean requiresCorrectTool;
                /** Texture name under textures/block/ (no .png). */
                public final String topTexture;
                public final String sideTexture;
                public final String bottomTexture;
                /** Plains grass tint (ARGB) applied to the top face, or 0 for none. */
                public final int topTint;

                Block(int id, String name, float destroySpeed, boolean requiresCorrectTool,
                                String topTexture, String sideTexture, String bottomTexture, int topTint) {
                        this.id = id;
                        this.name = name;
                        this.destroySpeed = destroySpeed;
                        this.friction = 0.6F;
                        this.requiresCorrectTool = requiresCorrectTool;
                        this.topTexture = topTexture;
                        this.sideTexture = sideTexture;
                        this.bottomTexture = bottomTexture;
                        this.topTint = topTint;
                }
        }

        private static final List<Block> REGISTRY = new ArrayList<Block>();
        private static final Block[] BY_ID = new Block[64];

        private static Block register(Block block) {
                REGISTRY.add(block);
                BY_ID[block.id] = block;
                return block;
        }

        /** Plains grass colormap color (vanilla grass.png at default climate). */
        public static final int PLAINS_GRASS_TINT = 0xFF91BD59;

        public static final Block AIR_BLOCK =
                register(new Block(AIR, "air", 0.0F, false, null, null, null, 0));
        public static final Block STONE_BLOCK = register(new Block(STONE, "stone",
                1.5F, true, "stone", "stone", "stone", 0));
        public static final Block COBBLESTONE_BLOCK = register(new Block(COBBLESTONE,
                "cobblestone", 2.0F, true, "cobblestone", "cobblestone", "cobblestone", 0));
        public static final Block GRASS_BLOCK_BLOCK = register(new Block(GRASS_BLOCK,
                "grass_block", 0.6F, false, "grass_block_top", "grass_block_side", "dirt",
                PLAINS_GRASS_TINT));
        public static final Block DIRT_BLOCK = register(new Block(DIRT, "dirt",
                0.5F, false, "dirt", "dirt", "dirt", 0));
        public static final Block OAK_PLANKS_BLOCK = register(new Block(OAK_PLANKS,
                "oak_planks", 2.0F, false, "oak_planks", "oak_planks", "oak_planks", 0));
        public static final Block BEDROCK_BLOCK = register(new Block(BEDROCK, "bedrock",
                -1.0F, true, "bedrock", "bedrock", "bedrock", 0));

        private Blocks() {
        }

        /** Registry entry by vanilla numeric id (never null for known ids; air otherwise). */
        public static Block byId(int id) {
                Block block = (id >= 0 && id < BY_ID.length) ? BY_ID[id] : null;
                return block != null ? block : AIR_BLOCK;
        }
}
