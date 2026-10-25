package net.minecraft.world.item;

import java.util.ArrayList;
import java.util.List;

/**
 * EaglerCraft 26.1.2 item registry (the port's first non-block item ids).
 *
 * <p>Block ids occupy 0..511 (see {@code Blocks}); items get a port-assigned
 * space at {@code >= 4096} so the two never collide (the legacy pre-flattening
 * convention - blocks below, items above). Inventory stacks, the crafting
 * grid and the save format all store these ids verbatim, exactly like block
 * ids.</p>
 *
 * <p>This milestone registers the crafting-chain items the recipe data
 * needs: stick, the wooden/stone/iron tool ladders and iron_ingot (the
 * recipes ship with the data; iron_ingot has no in-world source until the
 * furnace milestone). Display names are the vanilla en_us.json strings;
 * tool stack size is vanilla's 1, ingredients stack to 64.</p>
 *
 * <p>Item icons live under {@code assets/gui/sprites/item/<texture>.png}
 * (the vanilla {@code textures/item/} PNGs, 16x16) and are served through
 * the same GuiSprites pipeline as block icons.</p>
 */
public final class Items {

        /** First port-assigned item id (block ids stay below 512). */
        public static final int ITEM_BASE = 4096;

        /** One registry entry (name, display name, texture, stack limit). */
        public static final class Item {
                public final int id;
                public final String name;
                public final String displayName;
                public final int maxStack;

                Item(int id, String name, String displayName, int maxStack) {
                        this.id = id;
                        this.name = name;
                        this.displayName = displayName;
                        this.maxStack = maxStack;
                }
        }

        private static final List<Item> REGISTRY = new ArrayList<Item>();
        private static final Item[] byId = new Item[4096 + 128];

        // NOTE: declared AFTER the registry collections above - static
        // initializers run in source order, and register() needs REGISTRY
        // and byId to already exist (a clinit-order slip here silently
        // kills every sprite download; caught by the crafting e2e).
        public static final int STICK = register(4096, "stick", "Stick", 64);

        public static final int WOODEN_PICKAXE = register(4097, "wooden_pickaxe", "Wooden Pickaxe", 1);
        public static final int WOODEN_AXE = register(4098, "wooden_axe", "Wooden Axe", 1);
        public static final int WOODEN_SHOVEL = register(4099, "wooden_shovel", "Wooden Shovel", 1);
        public static final int WOODEN_SWORD = register(4100, "wooden_sword", "Wooden Sword", 1);
        public static final int WOODEN_HOE = register(4101, "wooden_hoe", "Wooden Hoe", 1);

        public static final int STONE_PICKAXE = register(4102, "stone_pickaxe", "Stone Pickaxe", 1);
        public static final int STONE_AXE = register(4103, "stone_axe", "Stone Axe", 1);
        public static final int STONE_SHOVEL = register(4104, "stone_shovel", "Stone Shovel", 1);
        public static final int STONE_SWORD = register(4105, "stone_sword", "Stone Sword", 1);
        public static final int STONE_HOE = register(4106, "stone_hoe", "Stone Hoe", 1);

        public static final int IRON_INGOT = register(4107, "iron_ingot", "Iron Ingot", 64);
        public static final int IRON_PICKAXE = register(4108, "iron_pickaxe", "Iron Pickaxe", 1);
        public static final int IRON_AXE = register(4109, "iron_axe", "Iron Axe", 1);
        public static final int IRON_SHOVEL = register(4110, "iron_shovel", "Iron Shovel", 1);
        public static final int IRON_SWORD = register(4111, "iron_sword", "Iron Sword", 1);
        public static final int IRON_HOE = register(4112, "iron_hoe", "Iron Hoe", 1);

        private static int register(int id, String name, String displayName,
                        int maxStack) {
                REGISTRY.add(new Item(id, name, displayName, maxStack));
                if (id >= 0 && id < byId.length) {
                        byId[id] = byId[id] != null ? byId[id]
                                        : new Item(id, name, displayName,
                                                        maxStack);
                }
                return id;
        }

        private Items() {
        }

        /** Registry entry by item id (null when the id is not an item). */
        public static Item byId(int id) {
                return (id >= ITEM_BASE && id < byId.length) ? byId[id] : null;
        }

        /** Whether an inventory-stack id refers to an item (not a block). */
        public static boolean isItem(int id) {
                return id >= ITEM_BASE;
        }

        /** Every texture name the registry references (stable order). */
        public static List<String> allTextureNames() {
                List<String> names = new ArrayList<String>();
                for (Item item : REGISTRY) {
                        names.add(item.name);
                }
                return names;
        }
}
