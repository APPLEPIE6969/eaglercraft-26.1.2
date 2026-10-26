package net.minecraft.world.level.block;

import java.util.ArrayList;
import java.util.List;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.world.level.block.Blocks.
 *
 * <p>The block registry the world renderer, inventory screens and the
 * superflat generator need, with vanilla's exact material constants from
 * the decompiled client jar (Blocks.java + BlockBehaviour.Properties):
 * hardness values drive the survival destroy-progress formula, friction
 * drives ground movement, requiresCorrectToolForDrops selects the 100x
 * (wrong tool) vs 30x destroy-time divisor.</p>
 *
 * <p>Registry ids: 1/4/9/10/15/33 are verified from the decompiled
 * 26.1.2 jar; 5-8, 11-14, 16-23 and 34-49 follow the registry runs those
 * anchors imply (stone-brick family, dirt family with mud, planks
 * family, the ore ladder); every other entry uses a port-assigned id
 * &gt;= 256 because the vanilla numeric table can no longer be checked
 * against the decompile (see docs/UI-FIDELITY.md). Save format stores
 * these ids verbatim and they never change.</p>
 *
 * <p>Tints: grass_block carries the plains-biome grass color (0x91BD59)
 * on its top face and leaves carry the plains foliage color (0x77AB2F;
 * birch 0x80A755, spruce 0x619961 fixed per vanilla's constants) - the
 * renderer bakes both into the grayscale textures at upload, the same
 * bake vanilla's biome colormaps perform.</p>
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

        /** Creative tab keys (vanilla CreativeModeTabs groups). */
        public static final int TAB_NONE = 0;
        public static final int TAB_BUILDING = 1;
        public static final int TAB_COLORED = 2;
        public static final int TAB_NATURAL = 3;

        /** One registry entry. */
        public static final class Block {
                public final int id;
                public final String name;
                /** Vanilla display name (en_us.json block.minecraft.*). */
                public final String displayName;
                /** Destroy hardness from vanilla Properties.strength(); -1 = unbreakable. */
                public final float destroySpeed;
                /** Vanilla Block.getFriction() default (0.6); all full cubes use it. */
                public final float friction;
                /** Vanilla requiresCorrectToolForDrops - selects the 100x divisor. */
                public final boolean requiresCorrectTool;
                /** Texture name under textures/block/ (no .png). */
                public final String topTexture;
                public final String sideTexture;
                public final String bottomTexture;
                /** Creative tab (TAB_NONE for unobtainable blocks like bedrock). */
                public final int tab;
                /** Plains grass tint (ARGB) applied to the top face, or 0 for none. */
                public final int topTint;
                /** Foliage tint (ARGB) applied to every face, or 0 for none. */
                public final int allTint;

                Block(int id, String name, String displayName, float destroySpeed,
                                boolean requiresCorrectTool, String topTexture, String sideTexture,
                                String bottomTexture, int tab, int topTint, int allTint) {
                        this.id = id;
                        this.name = name;
                        this.displayName = displayName;
                        this.destroySpeed = destroySpeed;
                        this.friction = 0.6F;
                        this.requiresCorrectTool = requiresCorrectTool;
                        this.topTexture = topTexture;
                        this.sideTexture = sideTexture;
                        this.bottomTexture = bottomTexture;
                        this.tab = tab;
                        this.topTint = topTint;
                        this.allTint = allTint;
                }

                /** Tint for a specific face texture (allTint wins over topTint). */
                public int tintFor(String texture) {
                        if (allTint != 0) {
                                return allTint;
                        }
                        return (topTint != 0 && topTexture.equals(texture)) ? topTint : 0;
                }

                /**
                 * Tint for a texture name, scoped to THIS block's own textures:
                 * allTint only counts when the block actually uses the texture
                 * (a leaves block's foliage tint must not claim unrelated
                 * textures - the atlas bake iterates every block per texture
                 * name and the first nonzero answer used to win for the whole
                 * atlas, foliage-tinting stone/planks/dirt/enerything).
                 */
                public int tintForTexture(String texture) {
                        boolean uses = topTexture.equals(texture)
                                        || sideTexture.equals(texture)
                                        || bottomTexture.equals(texture);
                        if (!uses) {
                                return 0;
                        }
                        return tintFor(texture);
                }
        }

        private static final List<Block> REGISTRY = new ArrayList<Block>();
        private static final List<Block> CREATIVE_BUILDING = new ArrayList<Block>();
        private static final List<Block> CREATIVE_COLORED = new ArrayList<Block>();
        private static final List<Block> CREATIVE_NATURAL = new ArrayList<Block>();
        private static final List<String> TEXTURE_NAMES = new ArrayList<String>();
        private static Block[] byId = new Block[512];

        private static Block register(Block block) {
                REGISTRY.add(block);
                if (block.id >= 0 && block.id < byId.length) {
                        byId[block.id] = block;
                }
                if (block.tab == TAB_BUILDING) {
                        CREATIVE_BUILDING.add(block);
                } else if (block.tab == TAB_COLORED) {
                        CREATIVE_COLORED.add(block);
                } else if (block.tab == TAB_NATURAL) {
                        CREATIVE_NATURAL.add(block);
                }
                // texture-layer list for WorldTextures (dedup, registry order)
                addTexture(block.topTexture);
                addTexture(block.sideTexture);
                addTexture(block.bottomTexture);
                return block;
        }

        private static void addTexture(String name) {
                if (name != null && !TEXTURE_NAMES.contains(name)) {
                        TEXTURE_NAMES.add(name);
                }
        }

        /** Table-row form used by the generated registry block below. */
        private static Block register(int id, String name, String displayName,
                        float destroySpeed, boolean requiresCorrectTool, String top,
                        String side, String bottom, int tab, int topTint, int allTint) {
                return register(new Block(id, name, displayName, destroySpeed,
                        requiresCorrectTool, top, side, bottom, tab, topTint, allTint));
        }

        /** Plains grass colormap color (vanilla grass.png at default climate). */
        public static final int PLAINS_GRASS_TINT = 0xFF91BD59;
        /** Plains foliage colormap color (vanilla foliage.png at default climate). */
        public static final int PLAINS_FOLIAGE_TINT = 0xFF77AB2F;

        public static final Block AIR_BLOCK =
                register(new Block(AIR, "air", "Air", 0.0F, false, null, null, null,
                        TAB_NONE, 0, 0));
        public static final Block STONE_BLOCK = register(new Block(STONE, "stone",
                "Stone", 1.5F, true, "stone", "stone", "stone", TAB_BUILDING, 0, 0));
        public static final Block COBBLESTONE_BLOCK = register(new Block(COBBLESTONE,
                "cobblestone", "Cobblestone", 2.0F, true, "cobblestone", "cobblestone",
                "cobblestone", TAB_BUILDING, 0, 0));
        public static final Block GRASS_BLOCK_BLOCK = register(new Block(GRASS_BLOCK,
                "grass_block", "Grass Block", 0.6F, false, "grass_block_top",
                "grass_block_side", "dirt", TAB_NATURAL, PLAINS_GRASS_TINT, 0));
        public static final Block DIRT_BLOCK = register(new Block(DIRT, "dirt",
                "Dirt", 0.5F, false, "dirt", "dirt", "dirt", TAB_NATURAL, 0, 0));
        public static final Block OAK_PLANKS_BLOCK = register(new Block(OAK_PLANKS,
                "oak_planks", "Oak Planks", 2.0F, false, "oak_planks", "oak_planks",
                "oak_planks", TAB_BUILDING, 0, 0));
        public static final Block BEDROCK_BLOCK = register(new Block(BEDROCK, "bedrock",
                "Bedrock", -1.0F, true, "bedrock", "bedrock", "bedrock", TAB_NONE, 0, 0));

        /* ===== registry entries generated by scripts/gen_block_palette.py ===== */
        static {
        register(5, "smooth_stone", "Smooth Stone", 2.0F, true, "smooth_stone", "smooth_stone", "smooth_stone", TAB_BUILDING, 0x000000, 0x000000);
        register(6, "stone_bricks", "Stone Bricks", 1.5F, true, "stone_bricks", "stone_bricks", "stone_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(7, "mossy_stone_bricks", "Mossy Stone Bricks", 1.5F, true, "mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(8, "cracked_stone_bricks", "Cracked Stone Bricks", 1.5F, true, "cracked_stone_bricks", "cracked_stone_bricks", "cracked_stone_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(11, "coarse_dirt", "Coarse Dirt", 0.5F, false, "coarse_dirt", "coarse_dirt", "coarse_dirt", TAB_NATURAL, 0x000000, 0x000000);
        register(12, "podzol", "Podzol", 0.5F, false, "podzol_top", "podzol_side", "dirt", TAB_NATURAL, 0x000000, 0x000000);
        register(13, "rooted_dirt", "Rooted Dirt", 0.5F, false, "rooted_dirt", "rooted_dirt", "rooted_dirt", TAB_NATURAL, 0x000000, 0x000000);
        register(14, "mud", "Mud", 0.5F, false, "mud", "mud", "mud", TAB_NATURAL, 0x000000, 0x000000);
        register(16, "spruce_planks", "Spruce Planks", 2.0F, false, "spruce_planks", "spruce_planks", "spruce_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(17, "birch_planks", "Birch Planks", 2.0F, false, "birch_planks", "birch_planks", "birch_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(18, "jungle_planks", "Jungle Planks", 2.0F, false, "jungle_planks", "jungle_planks", "jungle_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(19, "acacia_planks", "Acacia Planks", 2.0F, false, "acacia_planks", "acacia_planks", "acacia_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(20, "dark_oak_planks", "Dark Oak Planks", 2.0F, false, "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(21, "mangrove_planks", "Mangrove Planks", 2.0F, false, "mangrove_planks", "mangrove_planks", "mangrove_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(22, "cherry_planks", "Cherry Planks", 2.0F, false, "cherry_planks", "cherry_planks", "cherry_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(23, "bamboo_planks", "Bamboo Planks", 2.0F, false, "bamboo_planks", "bamboo_planks", "bamboo_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(34, "iron_ore", "Iron Ore", 3.0F, true, "iron_ore", "iron_ore", "iron_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(35, "deepslate_iron_ore", "Deepslate Iron Ore", 4.5F, true, "deepslate_iron_ore", "deepslate_iron_ore", "deepslate_iron_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(36, "copper_ore", "Copper Ore", 3.0F, true, "copper_ore", "copper_ore", "copper_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(37, "deepslate_copper_ore", "Deepslate Copper Ore", 4.5F, true, "deepslate_copper_ore", "deepslate_copper_ore", "deepslate_copper_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(38, "gold_ore", "Gold Ore", 3.0F, true, "gold_ore", "gold_ore", "gold_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(39, "deepslate_gold_ore", "Deepslate Gold Ore", 4.5F, true, "deepslate_gold_ore", "deepslate_gold_ore", "deepslate_gold_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(40, "redstone_ore", "Redstone Ore", 3.0F, true, "redstone_ore", "redstone_ore", "redstone_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(41, "deepslate_redstone_ore", "Deepslate Redstone Ore", 4.5F, true, "deepslate_redstone_ore", "deepslate_redstone_ore", "deepslate_redstone_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(42, "emerald_ore", "Emerald Ore", 3.0F, true, "emerald_ore", "emerald_ore", "emerald_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(43, "deepslate_emerald_ore", "Deepslate Emerald Ore", 4.5F, true, "deepslate_emerald_ore", "deepslate_emerald_ore", "deepslate_emerald_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(44, "lapis_ore", "Lapis Ore", 3.0F, true, "lapis_ore", "lapis_ore", "lapis_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(45, "deepslate_lapis_ore", "Deepslate Lapis Ore", 4.5F, true, "deepslate_lapis_ore", "deepslate_lapis_ore", "deepslate_lapis_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(46, "diamond_ore", "Diamond Ore", 3.0F, true, "diamond_ore", "diamond_ore", "diamond_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(47, "deepslate_diamond_ore", "Deepslate Diamond Ore", 4.5F, true, "deepslate_diamond_ore", "deepslate_diamond_ore", "deepslate_diamond_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(48, "coal_ore", "Coal Ore", 3.0F, true, "coal_ore", "coal_ore", "coal_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(49, "deepslate_coal_ore", "Deepslate Coal Ore", 4.5F, true, "deepslate_coal_ore", "deepslate_coal_ore", "deepslate_coal_ore", TAB_NATURAL, 0x000000, 0x000000);
        register(256, "granite", "Granite", 1.5F, true, "granite", "granite", "granite", TAB_BUILDING, 0x000000, 0x000000);
        register(257, "polished_granite", "Polished Granite", 2.0F, true, "polished_granite", "polished_granite", "polished_granite", TAB_BUILDING, 0x000000, 0x000000);
        register(258, "diorite", "Diorite", 1.5F, true, "diorite", "diorite", "diorite", TAB_BUILDING, 0x000000, 0x000000);
        register(259, "polished_diorite", "Polished Diorite", 2.0F, true, "polished_diorite", "polished_diorite", "polished_diorite", TAB_BUILDING, 0x000000, 0x000000);
        register(260, "andesite", "Andesite", 1.5F, true, "andesite", "andesite", "andesite", TAB_BUILDING, 0x000000, 0x000000);
        register(261, "polished_andesite", "Polished Andesite", 2.0F, true, "polished_andesite", "polished_andesite", "polished_andesite", TAB_BUILDING, 0x000000, 0x000000);
        register(262, "deepslate", "Deepslate", 3.0F, true, "deepslate_top", "deepslate", "deepslate_top", TAB_BUILDING, 0x000000, 0x000000);
        register(263, "cobbled_deepslate", "Cobbled Deepslate", 3.5F, true, "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", TAB_BUILDING, 0x000000, 0x000000);
        register(264, "polished_deepslate", "Polished Deepslate", 3.5F, true, "polished_deepslate", "polished_deepslate", "polished_deepslate", TAB_BUILDING, 0x000000, 0x000000);
        register(265, "deepslate_bricks", "Deepslate Bricks", 3.5F, true, "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(266, "deepslate_tiles", "Deepslate Tiles", 3.5F, true, "deepslate_tiles", "deepslate_tiles", "deepslate_tiles", TAB_BUILDING, 0x000000, 0x000000);
        register(267, "blackstone", "Blackstone", 1.5F, true, "blackstone_top", "blackstone", "blackstone_top", TAB_BUILDING, 0x000000, 0x000000);
        register(268, "polished_blackstone", "Polished Blackstone", 2.0F, true, "polished_blackstone", "polished_blackstone", "polished_blackstone", TAB_BUILDING, 0x000000, 0x000000);
        register(269, "polished_blackstone_bricks", "Polished Blackstone Bricks", 2.0F, true, "polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(270, "sandstone", "Sandstone", 0.8F, true, "sandstone_top", "sandstone", "sandstone_bottom", TAB_BUILDING, 0x000000, 0x000000);
        register(271, "smooth_sandstone", "Smooth Sandstone", 2.0F, true, "sandstone_top", "sandstone_top", "sandstone_top", TAB_BUILDING, 0x000000, 0x000000);
        register(272, "red_sandstone", "Red Sandstone", 0.8F, true, "red_sandstone_top", "red_sandstone", "red_sandstone_bottom", TAB_BUILDING, 0x000000, 0x000000);
        register(273, "smooth_red_sandstone", "Smooth Red Sandstone", 2.0F, true, "red_sandstone_top", "red_sandstone_top", "red_sandstone_top", TAB_BUILDING, 0x000000, 0x000000);
        register(274, "bricks", "Bricks", 2.0F, true, "bricks", "bricks", "bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(275, "glass", "Glass", 0.3F, false, "glass", "glass", "glass", TAB_BUILDING, 0x000000, 0x000000);
        register(276, "tinted_glass", "Tinted Glass", 0.3F, false, "tinted_glass", "tinted_glass", "tinted_glass", TAB_BUILDING, 0x000000, 0x000000);
        register(277, "obsidian", "Obsidian", 50.0F, true, "obsidian", "obsidian", "obsidian", TAB_BUILDING, 0x000000, 0x000000);
        register(278, "crying_obsidian", "Crying Obsidian", 50.0F, true, "crying_obsidian", "crying_obsidian", "crying_obsidian", TAB_BUILDING, 0x000000, 0x000000);
        register(279, "purpur_block", "Purpur Block", 1.5F, true, "purpur_block", "purpur_block", "purpur_block", TAB_BUILDING, 0x000000, 0x000000);
        register(280, "end_stone", "End Stone", 3.0F, true, "end_stone", "end_stone", "end_stone", TAB_BUILDING, 0x000000, 0x000000);
        register(281, "end_stone_bricks", "End Stone Bricks", 3.0F, true, "end_stone_bricks", "end_stone_bricks", "end_stone_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(282, "netherrack", "Netherrack", 0.4F, true, "netherrack", "netherrack", "netherrack", TAB_BUILDING, 0x000000, 0x000000);
        register(283, "nether_bricks", "Nether Bricks", 2.0F, true, "nether_bricks", "nether_bricks", "nether_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(284, "red_nether_bricks", "Red Nether Bricks", 2.0F, true, "red_nether_bricks", "red_nether_bricks", "red_nether_bricks", TAB_BUILDING, 0x000000, 0x000000);
        register(285, "quartz_block", "Block of Quartz", 0.8F, true, "quartz_block_top", "quartz_block_side", "quartz_block_top", TAB_BUILDING, 0x000000, 0x000000);
        register(286, "smooth_quartz", "Smooth Quartz Block", 2.0F, true, "quartz_block_bottom", "quartz_block_bottom", "quartz_block_bottom", TAB_BUILDING, 0x000000, 0x000000);
        register(287, "prismarine", "Prismarine", 1.5F, true, "prismarine", "prismarine", "prismarine", TAB_BUILDING, 0x000000, 0x000000);
        register(288, "dark_prismarine", "Dark Prismarine", 1.5F, true, "dark_prismarine", "dark_prismarine", "dark_prismarine", TAB_BUILDING, 0x000000, 0x000000);
        register(289, "bookshelf", "Bookshelf", 1.5F, false, "oak_planks", "bookshelf", "oak_planks", TAB_BUILDING, 0x000000, 0x000000);
        register(290, "iron_block", "Block of Iron", 5.0F, true, "iron_block", "iron_block", "iron_block", TAB_BUILDING, 0x000000, 0x000000);
        register(291, "gold_block", "Block of Gold", 3.0F, true, "gold_block", "gold_block", "gold_block", TAB_BUILDING, 0x000000, 0x000000);
        register(292, "diamond_block", "Block of Diamond", 5.0F, true, "diamond_block", "diamond_block", "diamond_block", TAB_BUILDING, 0x000000, 0x000000);
        register(293, "emerald_block", "Block of Emerald", 5.0F, true, "emerald_block", "emerald_block", "emerald_block", TAB_BUILDING, 0x000000, 0x000000);
        register(294, "lapis_block", "Block of Lapis", 3.0F, true, "lapis_block", "lapis_block", "lapis_block", TAB_BUILDING, 0x000000, 0x000000);
        register(295, "coal_block", "Block of Coal", 5.0F, true, "coal_block", "coal_block", "coal_block", TAB_BUILDING, 0x000000, 0x000000);
        register(296, "copper_block", "Block of Copper", 3.0F, true, "copper_block", "copper_block", "copper_block", TAB_BUILDING, 0x000000, 0x000000);
        register(297, "white_wool", "White Wool", 0.8F, false, "white_wool", "white_wool", "white_wool", TAB_COLORED, 0x000000, 0x000000);
        register(298, "orange_wool", "Orange Wool", 0.8F, false, "orange_wool", "orange_wool", "orange_wool", TAB_COLORED, 0x000000, 0x000000);
        register(299, "magenta_wool", "Magenta Wool", 0.8F, false, "magenta_wool", "magenta_wool", "magenta_wool", TAB_COLORED, 0x000000, 0x000000);
        register(300, "light_blue_wool", "Light Blue Wool", 0.8F, false, "light_blue_wool", "light_blue_wool", "light_blue_wool", TAB_COLORED, 0x000000, 0x000000);
        register(301, "yellow_wool", "Yellow Wool", 0.8F, false, "yellow_wool", "yellow_wool", "yellow_wool", TAB_COLORED, 0x000000, 0x000000);
        register(302, "lime_wool", "Lime Wool", 0.8F, false, "lime_wool", "lime_wool", "lime_wool", TAB_COLORED, 0x000000, 0x000000);
        register(303, "pink_wool", "Pink Wool", 0.8F, false, "pink_wool", "pink_wool", "pink_wool", TAB_COLORED, 0x000000, 0x000000);
        register(304, "gray_wool", "Gray Wool", 0.8F, false, "gray_wool", "gray_wool", "gray_wool", TAB_COLORED, 0x000000, 0x000000);
        register(305, "light_gray_wool", "Light Gray Wool", 0.8F, false, "light_gray_wool", "light_gray_wool", "light_gray_wool", TAB_COLORED, 0x000000, 0x000000);
        register(306, "cyan_wool", "Cyan Wool", 0.8F, false, "cyan_wool", "cyan_wool", "cyan_wool", TAB_COLORED, 0x000000, 0x000000);
        register(307, "purple_wool", "Purple Wool", 0.8F, false, "purple_wool", "purple_wool", "purple_wool", TAB_COLORED, 0x000000, 0x000000);
        register(308, "blue_wool", "Blue Wool", 0.8F, false, "blue_wool", "blue_wool", "blue_wool", TAB_COLORED, 0x000000, 0x000000);
        register(309, "brown_wool", "Brown Wool", 0.8F, false, "brown_wool", "brown_wool", "brown_wool", TAB_COLORED, 0x000000, 0x000000);
        register(310, "green_wool", "Green Wool", 0.8F, false, "green_wool", "green_wool", "green_wool", TAB_COLORED, 0x000000, 0x000000);
        register(311, "red_wool", "Red Wool", 0.8F, false, "red_wool", "red_wool", "red_wool", TAB_COLORED, 0x000000, 0x000000);
        register(312, "black_wool", "Black Wool", 0.8F, false, "black_wool", "black_wool", "black_wool", TAB_COLORED, 0x000000, 0x000000);
        register(313, "white_concrete", "White Concrete", 1.8F, true, "white_concrete", "white_concrete", "white_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(314, "orange_concrete", "Orange Concrete", 1.8F, true, "orange_concrete", "orange_concrete", "orange_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(315, "magenta_concrete", "Magenta Concrete", 1.8F, true, "magenta_concrete", "magenta_concrete", "magenta_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(316, "light_blue_concrete", "Light Blue Concrete", 1.8F, true, "light_blue_concrete", "light_blue_concrete", "light_blue_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(317, "yellow_concrete", "Yellow Concrete", 1.8F, true, "yellow_concrete", "yellow_concrete", "yellow_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(318, "lime_concrete", "Lime Concrete", 1.8F, true, "lime_concrete", "lime_concrete", "lime_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(319, "pink_concrete", "Pink Concrete", 1.8F, true, "pink_concrete", "pink_concrete", "pink_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(320, "gray_concrete", "Gray Concrete", 1.8F, true, "gray_concrete", "gray_concrete", "gray_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(321, "light_gray_concrete", "Light Gray Concrete", 1.8F, true, "light_gray_concrete", "light_gray_concrete", "light_gray_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(322, "cyan_concrete", "Cyan Concrete", 1.8F, true, "cyan_concrete", "cyan_concrete", "cyan_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(323, "purple_concrete", "Purple Concrete", 1.8F, true, "purple_concrete", "purple_concrete", "purple_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(324, "blue_concrete", "Blue Concrete", 1.8F, true, "blue_concrete", "blue_concrete", "blue_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(325, "brown_concrete", "Brown Concrete", 1.8F, true, "brown_concrete", "brown_concrete", "brown_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(326, "green_concrete", "Green Concrete", 1.8F, true, "green_concrete", "green_concrete", "green_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(327, "red_concrete", "Red Concrete", 1.8F, true, "red_concrete", "red_concrete", "red_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(328, "black_concrete", "Black Concrete", 1.8F, true, "black_concrete", "black_concrete", "black_concrete", TAB_COLORED, 0x000000, 0x000000);
        register(329, "white_terracotta", "White Terracotta", 1.25F, true, "white_terracotta", "white_terracotta", "white_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(330, "orange_terracotta", "Orange Terracotta", 1.25F, true, "orange_terracotta", "orange_terracotta", "orange_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(331, "magenta_terracotta", "Magenta Terracotta", 1.25F, true, "magenta_terracotta", "magenta_terracotta", "magenta_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(332, "light_blue_terracotta", "Light Blue Terracotta", 1.25F, true, "light_blue_terracotta", "light_blue_terracotta", "light_blue_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(333, "yellow_terracotta", "Yellow Terracotta", 1.25F, true, "yellow_terracotta", "yellow_terracotta", "yellow_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(334, "lime_terracotta", "Lime Terracotta", 1.25F, true, "lime_terracotta", "lime_terracotta", "lime_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(335, "pink_terracotta", "Pink Terracotta", 1.25F, true, "pink_terracotta", "pink_terracotta", "pink_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(336, "gray_terracotta", "Gray Terracotta", 1.25F, true, "gray_terracotta", "gray_terracotta", "gray_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(337, "light_gray_terracotta", "Light Gray Terracotta", 1.25F, true, "light_gray_terracotta", "light_gray_terracotta", "light_gray_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(338, "cyan_terracotta", "Cyan Terracotta", 1.25F, true, "cyan_terracotta", "cyan_terracotta", "cyan_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(339, "purple_terracotta", "Purple Terracotta", 1.25F, true, "purple_terracotta", "purple_terracotta", "purple_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(340, "blue_terracotta", "Blue Terracotta", 1.25F, true, "blue_terracotta", "blue_terracotta", "blue_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(341, "brown_terracotta", "Brown Terracotta", 1.25F, true, "brown_terracotta", "brown_terracotta", "brown_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(342, "green_terracotta", "Green Terracotta", 1.25F, true, "green_terracotta", "green_terracotta", "green_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(343, "red_terracotta", "Red Terracotta", 1.25F, true, "red_terracotta", "red_terracotta", "red_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(344, "black_terracotta", "Black Terracotta", 1.25F, true, "black_terracotta", "black_terracotta", "black_terracotta", TAB_COLORED, 0x000000, 0x000000);
        register(345, "sand", "Sand", 0.5F, false, "sand", "sand", "sand", TAB_NATURAL, 0x000000, 0x000000);
        register(346, "red_sand", "Red Sand", 0.5F, false, "red_sand", "red_sand", "red_sand", TAB_NATURAL, 0x000000, 0x000000);
        register(347, "gravel", "Gravel", 0.6F, false, "gravel", "gravel", "gravel", TAB_NATURAL, 0x000000, 0x000000);
        register(348, "clay", "Clay", 0.6F, false, "clay", "clay", "clay", TAB_NATURAL, 0x000000, 0x000000);
        register(349, "snow_block", "Snow Block", 0.2F, true, "snow", "snow", "snow", TAB_NATURAL, 0x000000, 0x000000);
        register(350, "moss_block", "Moss Block", 0.1F, false, "moss_block", "moss_block", "moss_block", TAB_NATURAL, 0x000000, 0x000000);
        register(351, "oak_log", "Oak Log", 2.0F, false, "oak_log_top", "oak_log", "oak_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(352, "spruce_log", "Spruce Log", 2.0F, false, "spruce_log_top", "spruce_log", "spruce_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(353, "birch_log", "Birch Log", 2.0F, false, "birch_log_top", "birch_log", "birch_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(354, "jungle_log", "Jungle Log", 2.0F, false, "jungle_log_top", "jungle_log", "jungle_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(355, "acacia_log", "Acacia Log", 2.0F, false, "acacia_log_top", "acacia_log", "acacia_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(356, "dark_oak_log", "Dark Oak Log", 2.0F, false, "dark_oak_log_top", "dark_oak_log", "dark_oak_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(357, "mangrove_log", "Mangrove Log", 2.0F, false, "mangrove_log_top", "mangrove_log", "mangrove_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(358, "cherry_log", "Cherry Log", 2.0F, false, "cherry_log_top", "cherry_log", "cherry_log_top", TAB_NATURAL, 0x000000, 0x000000);
        register(359, "oak_leaves", "Oak Leaves", 0.2F, false, "oak_leaves", "oak_leaves", "oak_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(360, "spruce_leaves", "Spruce Leaves", 0.2F, false, "spruce_leaves", "spruce_leaves", "spruce_leaves", TAB_NATURAL, 0x000000, 0x619961);
        register(361, "birch_leaves", "Birch Leaves", 0.2F, false, "birch_leaves", "birch_leaves", "birch_leaves", TAB_NATURAL, 0x000000, 0x80A755);
        register(362, "jungle_leaves", "Jungle Leaves", 0.2F, false, "jungle_leaves", "jungle_leaves", "jungle_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(363, "acacia_leaves", "Acacia Leaves", 0.2F, false, "acacia_leaves", "acacia_leaves", "acacia_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(364, "dark_oak_leaves", "Dark Oak Leaves", 0.2F, false, "dark_oak_leaves", "dark_oak_leaves", "dark_oak_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(365, "mangrove_leaves", "Mangrove Leaves", 0.2F, false, "mangrove_leaves", "mangrove_leaves", "mangrove_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(366, "cherry_leaves", "Cherry Leaves", 0.2F, false, "cherry_leaves", "cherry_leaves", "cherry_leaves", TAB_NATURAL, 0x000000, 0x000000);
        register(367, "azalea_leaves", "Azalea Leaves", 0.2F, false, "azalea_leaves", "azalea_leaves", "azalea_leaves", TAB_NATURAL, 0x000000, 0x77AB2F);
        register(368, "ancient_debris", "Ancient Debris", 30.0F, true, "ancient_debris_top", "ancient_debris_side", "ancient_debris_top", TAB_NATURAL, 0x000000, 0x000000);
        register(369, "hay_block", "Hay Bale", 0.5F, false, "hay_block_top", "hay_block_side", "hay_block_top", TAB_NATURAL, 0x000000, 0x000000);
        register(370, "dried_kelp_block", "Dried Kelp Block", 0.5F, false, "dried_kelp_top", "dried_kelp_side", "dried_kelp_top", TAB_NATURAL, 0x000000, 0x000000);
        register(371, "pumpkin", "Pumpkin", 1.0F, false, "pumpkin_top", "pumpkin_side", "pumpkin_top", TAB_NATURAL, 0x000000, 0x000000);
        register(372, "melon", "Melon", 1.0F, false, "melon_top", "melon_side", "melon_top", TAB_NATURAL, 0x000000, 0x000000);
        register(373, "cactus", "Cactus", 0.4F, false, "cactus_top", "cactus_side", "cactus_bottom", TAB_NATURAL, 0x000000, 0x000000);
        register(374, "mushroom_stem", "Mushroom Stem", 0.2F, false, "mushroom_stem", "mushroom_stem", "mushroom_stem", TAB_NATURAL, 0x000000, 0x000000);
        // crafting_table: the vanilla numeric id is unverifiable from the
        // decompile, so it gets a port-assigned id like the 256+ run. It is
        // interactive (startUseItem opens the 3x3 CraftingTableScreen) and
        // craftable from four planks in the 2x2 grid, closing the survival
        // tool ladder. Not in a creative tab yet (the tab set is a
        // documented simplification).
        register(375, "crafting_table", "Crafting Table", 2.5F, false, "crafting_table_top", "crafting_table_front", "crafting_table_top", TAB_NONE, 0x000000, 0x000000);
        }

        private Blocks() {
        }

        /** Registry entry by vanilla numeric id (never null for known ids; air otherwise). */
        public static Block byId(int id) {
                Block block = (id >= 0 && id < byId.length) ? byId[id] : null;
                return block != null ? block : AIR_BLOCK;
        }

        /** The building-blocks creative tab in vanilla's display order. */
        public static List<Block> creativeBuilding() {
                return CREATIVE_BUILDING;
        }

        /** The colored-blocks creative tab in vanilla's display order. */
        public static List<Block> creativeColored() {
                return CREATIVE_COLORED;
        }

        /** The natural-blocks creative tab in vanilla's display order. */
        public static List<Block> creativeNatural() {
                return CREATIVE_NATURAL;
        }

        /** Every texture name the registry references (dedup, stable order). */
        public static List<String> allTextureNames() {
                return TEXTURE_NAMES;
        }
}
