#!/usr/bin/env python3
"""
Feature 4a — block palette expansion for EaglerCraft 26.1.2.

Single source of truth for the expanded block registry:
 1. copies the referenced vanilla textures from the surviving asset
    staging tree into public/assets/world/textures/ (3D array layers)
    and public/assets/gui/sprites/block/ (GUI icons)
 2. verifies every referenced texture exists
 3. emits the Blocks.java registry fragment to stdout (reviewed by hand)

ID policy (documented in docs/UI-FIDELITY.md):
 - ids 1/4/9/10/15/33 verified from the decompiled 26.1.2 client jar
 - ids 5-8, 11-14, 16-23, 34-49 follow the verified anchors' registry
   runs (stone-brick family, dirt family incl. mud, planks family,
   the ore ladder) - high confidence from modern-vanilla ordering
 - all other ids are >= 256, port-assigned for save-format stability
   (vanilla registry positions unverified without the decompile jar)
"""
import shutil
import sys
from pathlib import Path

STAGING = Path("/home/z/my-project/build-assets/mc2612/staging/assets/minecraft/textures/block")
REPO = Path("/home/z/my-project/eaglercraft-26.1.2")
WORLD_TEX = REPO / "public/assets/world/textures"
GUI_TEX = REPO / "public/assets/gui/sprites/block"

# 16 vanilla dye colors in registry order
COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime",
          "pink", "gray", "light_gray", "cyan", "purple", "blue",
          "brown", "green", "red", "black"]

# Foliage colormap tints (vanilla GrassColormap/FoliageColormap at plains)
FOLIAGE = 0x77AB2F
BIRCH_TINT = 0x80A755
SPRUCE_TINT = 0x619961

# tab keys
BLD, COL, NAT = "building", "colored", "natural"

# (id_or_None, name, [top, side, bottom] texture names, destroySpeed,
#  requiresCorrectTool, tab, topTint, allTint, displayName)
PALETTE = [
    # ===== ids anchored on the decompile (5-8, 11-14, 16-23, 34-49) =====
    (5,  "smooth_stone", ["smooth_stone"]*3, 2.0, True, BLD, 0, 0, "Smooth Stone"),
    (6,  "stone_bricks", ["stone_bricks"]*3, 1.5, True, BLD, 0, 0, "Stone Bricks"),
    (7,  "mossy_stone_bricks", ["mossy_stone_bricks"]*3, 1.5, True, BLD, 0, 0, "Mossy Stone Bricks"),
    (8,  "cracked_stone_bricks", ["cracked_stone_bricks"]*3, 1.5, True, BLD, 0, 0, "Cracked Stone Bricks"),
    (11, "coarse_dirt", ["coarse_dirt"]*3, 0.5, False, NAT, 0, 0, "Coarse Dirt"),
    (12, "podzol", ["podzol_top", "podzol_side", "dirt"], 0.5, False, NAT, 0, 0, "Podzol"),
    (13, "rooted_dirt", ["rooted_dirt"]*3, 0.5, False, NAT, 0, 0, "Rooted Dirt"),
    (14, "mud", ["mud"]*3, 0.5, False, NAT, 0, 0, "Mud"),
    (16, "spruce_planks", ["spruce_planks"]*3, 2.0, False, BLD, 0, 0, "Spruce Planks"),
    (17, "birch_planks", ["birch_planks"]*3, 2.0, False, BLD, 0, 0, "Birch Planks"),
    (18, "jungle_planks", ["jungle_planks"]*3, 2.0, False, BLD, 0, 0, "Jungle Planks"),
    (19, "acacia_planks", ["acacia_planks"]*3, 2.0, False, BLD, 0, 0, "Acacia Planks"),
    (20, "dark_oak_planks", ["dark_oak_planks"]*3, 2.0, False, BLD, 0, 0, "Dark Oak Planks"),
    (21, "mangrove_planks", ["mangrove_planks"]*3, 2.0, False, BLD, 0, 0, "Mangrove Planks"),
    (22, "cherry_planks", ["cherry_planks"]*3, 2.0, False, BLD, 0, 0, "Cherry Planks"),
    (23, "bamboo_planks", ["bamboo_planks"]*3, 2.0, False, BLD, 0, 0, "Bamboo Planks"),
    (34, "iron_ore", ["iron_ore"]*3, 3.0, True, NAT, 0, 0, "Iron Ore"),
    (35, "deepslate_iron_ore", ["deepslate_iron_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Iron Ore"),
    (36, "copper_ore", ["copper_ore"]*3, 3.0, True, NAT, 0, 0, "Copper Ore"),
    (37, "deepslate_copper_ore", ["deepslate_copper_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Copper Ore"),
    (38, "gold_ore", ["gold_ore"]*3, 3.0, True, NAT, 0, 0, "Gold Ore"),
    (39, "deepslate_gold_ore", ["deepslate_gold_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Gold Ore"),
    (40, "redstone_ore", ["redstone_ore"]*3, 3.0, True, NAT, 0, 0, "Redstone Ore"),
    (41, "deepslate_redstone_ore", ["deepslate_redstone_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Redstone Ore"),
    (42, "emerald_ore", ["emerald_ore"]*3, 3.0, True, NAT, 0, 0, "Emerald Ore"),
    (43, "deepslate_emerald_ore", ["deepslate_emerald_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Emerald Ore"),
    (44, "lapis_ore", ["lapis_ore"]*3, 3.0, True, NAT, 0, 0, "Lapis Ore"),
    (45, "deepslate_lapis_ore", ["deepslate_lapis_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Lapis Ore"),
    (46, "diamond_ore", ["diamond_ore"]*3, 3.0, True, NAT, 0, 0, "Diamond Ore"),
    (47, "deepslate_diamond_ore", ["deepslate_diamond_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Diamond Ore"),
    (48, "coal_ore", ["coal_ore"]*3, 3.0, True, NAT, 0, 0, "Coal Ore"),
    (49, "deepslate_coal_ore", ["deepslate_coal_ore"]*3, 4.5, True, NAT, 0, 0, "Deepslate Coal Ore"),

    # ===== Building Blocks (port-assigned ids from 256) =====
    (None, "granite", ["granite"]*3, 1.5, True, BLD, 0, 0, "Granite"),
    (None, "polished_granite", ["polished_granite"]*3, 2.0, True, BLD, 0, 0, "Polished Granite"),
    (None, "diorite", ["diorite"]*3, 1.5, True, BLD, 0, 0, "Diorite"),
    (None, "polished_diorite", ["polished_diorite"]*3, 2.0, True, BLD, 0, 0, "Polished Diorite"),
    (None, "andesite", ["andesite"]*3, 1.5, True, BLD, 0, 0, "Andesite"),
    (None, "polished_andesite", ["polished_andesite"]*3, 2.0, True, BLD, 0, 0, "Polished Andesite"),
    (None, "deepslate", ["deepslate_top", "deepslate", "deepslate_top"], 3.0, True, BLD, 0, 0, "Deepslate"),
    (None, "cobbled_deepslate", ["cobbled_deepslate"]*3, 3.5, True, BLD, 0, 0, "Cobbled Deepslate"),
    (None, "polished_deepslate", ["polished_deepslate"]*3, 3.5, True, BLD, 0, 0, "Polished Deepslate"),
    (None, "deepslate_bricks", ["deepslate_bricks"]*3, 3.5, True, BLD, 0, 0, "Deepslate Bricks"),
    (None, "deepslate_tiles", ["deepslate_tiles"]*3, 3.5, True, BLD, 0, 0, "Deepslate Tiles"),
    (None, "blackstone", ["blackstone_top", "blackstone", "blackstone_top"], 1.5, True, BLD, 0, 0, "Blackstone"),
    (None, "polished_blackstone", ["polished_blackstone"]*3, 2.0, True, BLD, 0, 0, "Polished Blackstone"),
    (None, "polished_blackstone_bricks", ["polished_blackstone_bricks"]*3, 2.0, True, BLD, 0, 0, "Polished Blackstone Bricks"),
    (None, "sandstone", ["sandstone_top", "sandstone", "sandstone_bottom"], 0.8, True, BLD, 0, 0, "Sandstone"),
    (None, "smooth_sandstone", ["sandstone_top"]*3, 2.0, True, BLD, 0, 0, "Smooth Sandstone"),
    (None, "red_sandstone", ["red_sandstone_top", "red_sandstone", "red_sandstone_bottom"], 0.8, True, BLD, 0, 0, "Red Sandstone"),
    (None, "smooth_red_sandstone", ["red_sandstone_top"]*3, 2.0, True, BLD, 0, 0, "Smooth Red Sandstone"),
    (None, "bricks", ["bricks"]*3, 2.0, True, BLD, 0, 0, "Bricks"),
    (None, "glass", ["glass"]*3, 0.3, False, BLD, 0, 0, "Glass"),
    (None, "tinted_glass", ["tinted_glass"]*3, 0.3, False, BLD, 0, 0, "Tinted Glass"),
    (None, "obsidian", ["obsidian"]*3, 50.0, True, BLD, 0, 0, "Obsidian"),
    (None, "crying_obsidian", ["crying_obsidian"]*3, 50.0, True, BLD, 0, 0, "Crying Obsidian"),
    (None, "purpur_block", ["purpur_block"]*3, 1.5, True, BLD, 0, 0, "Purpur Block"),
    (None, "end_stone", ["end_stone"]*3, 3.0, True, BLD, 0, 0, "End Stone"),
    (None, "end_stone_bricks", ["end_stone_bricks"]*3, 3.0, True, BLD, 0, 0, "End Stone Bricks"),
    (None, "netherrack", ["netherrack"]*3, 0.4, True, BLD, 0, 0, "Netherrack"),
    (None, "nether_bricks", ["nether_bricks"]*3, 2.0, True, BLD, 0, 0, "Nether Bricks"),
    (None, "red_nether_bricks", ["red_nether_bricks"]*3, 2.0, True, BLD, 0, 0, "Red Nether Bricks"),
    (None, "quartz_block", ["quartz_block_top", "quartz_block_side", "quartz_block_top"], 0.8, True, BLD, 0, 0, "Block of Quartz"),
    (None, "smooth_quartz", ["quartz_block_bottom"]*3, 2.0, True, BLD, 0, 0, "Smooth Quartz Block"),
    (None, "prismarine", ["prismarine"]*3, 1.5, True, BLD, 0, 0, "Prismarine"),
    (None, "dark_prismarine", ["dark_prismarine"]*3, 1.5, True, BLD, 0, 0, "Dark Prismarine"),
    (None, "bookshelf", ["oak_planks", "bookshelf", "oak_planks"], 1.5, False, BLD, 0, 0, "Bookshelf"),
    (None, "iron_block", ["iron_block"]*3, 5.0, True, BLD, 0, 0, "Block of Iron"),
    (None, "gold_block", ["gold_block"]*3, 3.0, True, BLD, 0, 0, "Block of Gold"),
    (None, "diamond_block", ["diamond_block"]*3, 5.0, True, BLD, 0, 0, "Block of Diamond"),
    (None, "emerald_block", ["emerald_block"]*3, 5.0, True, BLD, 0, 0, "Block of Emerald"),
    (None, "lapis_block", ["lapis_block"]*3, 3.0, True, BLD, 0, 0, "Block of Lapis"),
    (None, "coal_block", ["coal_block"]*3, 5.0, True, BLD, 0, 0, "Block of Coal"),
    (None, "copper_block", ["copper_block"]*3, 3.0, True, BLD, 0, 0, "Block of Copper"),

    # ===== Colored Blocks (16 wool + 16 concrete + 16 terracotta) =====
    *[(None, f"{c}_wool", [f"{c}_wool"]*3, 0.8, False, COL, 0, 0,
       f"{c.replace('_', ' ').title()} Wool") for c in COLORS],
    *[(None, f"{c}_concrete", [f"{c}_concrete"]*3, 1.8, True, COL, 0, 0,
       f"{c.replace('_', ' ').title()} Concrete") for c in COLORS],
    *[(None, f"{c}_terracotta", [f"{c}_terracotta"]*3, 1.25, True, COL, 0, 0,
       f"{c.replace('_', ' ').title()} Terracotta") for c in COLORS],

    # ===== Natural Blocks =====
    (None, "sand", ["sand"]*3, 0.5, False, NAT, 0, 0, "Sand"),
    (None, "red_sand", ["red_sand"]*3, 0.5, False, NAT, 0, 0, "Red Sand"),
    (None, "gravel", ["gravel"]*3, 0.6, False, NAT, 0, 0, "Gravel"),
    (None, "clay", ["clay"]*3, 0.6, False, NAT, 0, 0, "Clay"),
    (None, "snow_block", ["snow"]*3, 0.2, True, NAT, 0, 0, "Snow Block"),
    (None, "moss_block", ["moss_block"]*3, 0.1, False, NAT, 0, 0, "Moss Block"),
    (None, "oak_log", ["oak_log_top", "oak_log", "oak_log_top"], 2.0, False, NAT, 0, 0, "Oak Log"),
    (None, "spruce_log", ["spruce_log_top", "spruce_log", "spruce_log_top"], 2.0, False, NAT, 0, 0, "Spruce Log"),
    (None, "birch_log", ["birch_log_top", "birch_log", "birch_log_top"], 2.0, False, NAT, 0, 0, "Birch Log"),
    (None, "jungle_log", ["jungle_log_top", "jungle_log", "jungle_log_top"], 2.0, False, NAT, 0, 0, "Jungle Log"),
    (None, "acacia_log", ["acacia_log_top", "acacia_log", "acacia_log_top"], 2.0, False, NAT, 0, 0, "Acacia Log"),
    (None, "dark_oak_log", ["dark_oak_log_top", "dark_oak_log", "dark_oak_log_top"], 2.0, False, NAT, 0, 0, "Dark Oak Log"),
    (None, "mangrove_log", ["mangrove_log_top", "mangrove_log", "mangrove_log_top"], 2.0, False, NAT, 0, 0, "Mangrove Log"),
    (None, "cherry_log", ["cherry_log_top", "cherry_log", "cherry_log_top"], 2.0, False, NAT, 0, 0, "Cherry Log"),
    (None, "oak_leaves", ["oak_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Oak Leaves"),
    (None, "spruce_leaves", ["spruce_leaves"]*3, 0.2, False, NAT, 0, SPRUCE_TINT, "Spruce Leaves"),
    (None, "birch_leaves", ["birch_leaves"]*3, 0.2, False, NAT, 0, BIRCH_TINT, "Birch Leaves"),
    (None, "jungle_leaves", ["jungle_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Jungle Leaves"),
    (None, "acacia_leaves", ["acacia_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Acacia Leaves"),
    (None, "dark_oak_leaves", ["dark_oak_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Dark Oak Leaves"),
    (None, "mangrove_leaves", ["mangrove_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Mangrove Leaves"),
    (None, "cherry_leaves", ["cherry_leaves"]*3, 0.2, False, NAT, 0, 0, "Cherry Leaves"),
    (None, "azalea_leaves", ["azalea_leaves"]*3, 0.2, False, NAT, 0, FOLIAGE, "Azalea Leaves"),
    (None, "ancient_debris", ["ancient_debris_top", "ancient_debris_side", "ancient_debris_top"], 30.0, True, NAT, 0, 0, "Ancient Debris"),
    (None, "hay_block", ["hay_block_top", "hay_block_side", "hay_block_top"], 0.5, False, NAT, 0, 0, "Hay Bale"),
    (None, "dried_kelp_block", ["dried_kelp_top", "dried_kelp_side", "dried_kelp_top"], 0.5, False, NAT, 0, 0, "Dried Kelp Block"),
    (None, "pumpkin", ["pumpkin_top", "pumpkin_side", "pumpkin_top"], 1.0, False, NAT, 0, 0, "Pumpkin"),
    (None, "melon", ["melon_top", "melon_side", "melon_top"], 1.0, False, NAT, 0, 0, "Melon"),
    (None, "cactus", ["cactus_top", "cactus_side", "cactus_bottom"], 0.4, False, NAT, 0, 0, "Cactus"),
    (None, "mushroom_stem", ["mushroom_stem"]*3, 0.2, False, NAT, 0, 0, "Mushroom Stem"),
]

NEXT_DYNAMIC_ID = 256


def main():
    # assign ids
    entries = []
    next_id = NEXT_DYNAMIC_ID
    for row in PALETTE:
        bid, name, tex, speed, tool, tab, top_tint, all_tint, display = row
        if bid is None:
            bid = next_id
            next_id += 1
        entries.append(dict(id=bid, name=name, tex=tex, speed=speed, tool=tool,
                            tab=tab, topTint=top_tint, allTint=all_tint, display=display))

    # unique id check
    ids = [e["id"] for e in entries]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        sys.exit(f"DUP IDS: {dupes}")
    names = [e["name"] for e in entries]
    dupn = {n for n in names if names.count(n) > 1}
    if dupn:
        sys.exit(f"DUP NAMES: {dupn}")

    # collect + verify textures
    texs = set()
    for e in entries:
        texs.update(e["tex"])
    missing = sorted(t for t in texs if not (STAGING / f"{t}.png").exists())
    if missing:
        sys.exit(f"MISSING TEXTURES ({len(missing)}): {missing[:12]}")

    # copy (world + gui icons), skipping .mcmeta (static frame 0 is the
    # whole png; animated semantics not ported)
    copied = 0
    for t in sorted(texs):
        src = STAGING / f"{t}.png"
        shutil.copy2(src, WORLD_TEX / f"{t}.png")
        shutil.copy2(src, GUI_TEX / f"{t}.png")
        copied += 1

    # emit the Blocks.java fragment
    out = []
    out.append("        // ===== registry entries generated by scripts/gen_block_palette.py =====")
    tab_const = {"building": "TAB_BUILDING", "colored": "TAB_COLORED", "natural": "TAB_NATURAL"}
    for e in entries:
        top, side, bottom = e["tex"]
        uniform = top == side == bottom
        # always emit BOTH tint args explicitly (topTint, allTint)
        tint_str = f", 0x{e['topTint']:06X}, 0x{e['allTint']:06X}"
        # register(id, name, display, destroySpeed, requiresCorrectTool,
        #          top, side, bottom, tab, topTint, allTint)
        out.append(
            f"        register({e['id']}, \"{e['name']}\", \"{e['display']}\", "
            f"{e['speed']}F, {str(e['tool']).lower()}, "
            f"\"{top}\", \"{side}\", \"{bottom}\", "
            f"{tab_const[e['tab']]}{tint_str});")

    print(f"// {len(entries)} blocks, {copied} textures copied, ids "
          f"{min(ids)}..{max(ids)}")
    print("\n".join(out))


if __name__ == "__main__":
    main()
