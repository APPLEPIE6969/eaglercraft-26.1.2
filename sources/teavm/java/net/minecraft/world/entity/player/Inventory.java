package net.minecraft.world.entity.player;

import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.world.entity.player.Inventory
 * (hotbar scope).
 *
 * <p>Nine stacks of one block type each, vanilla's slot semantics:
 * pickup goes to the first matching stack, then the first empty slot
 * (Inventory.add order); stack cap 64; scroll swaps the selected slot
 * with wraparound (MouseHandler's Inventory.swapPaint); placing
 * consumes one in survival and leaves the count intact in creative.</p>
 *
 * <p>Not ported (no surface yet): armor, offhand, the full 36-slot
 * grid, item entities - mined blocks go straight to the hotbar, which
 * is documented in docs/UI-FIDELITY.md as the item-entity-free
 * simplification.</p>
 */
public class Inventory {

        public static final int HOTBAR_SIZE = 9;
        public static final int STACK_SIZE = 64;

        /** Slot block ids (-1 = empty slot). */
        private final int[] slots = new int[HOTBAR_SIZE];
        private final int[] counts = new int[HOTBAR_SIZE];
        /** Vanilla Inventory.selected. */
        private int selected;

        /** Vanilla Inventory.add for block drops: first matching stack, then first empty. */
        public boolean addItem(int blockId) {
                if (blockId == Blocks.AIR) {
                        return false;
                }
                for (int i = 0; i < HOTBAR_SIZE; i++) {
                        if (slots[i] == blockId && counts[i] < STACK_SIZE) {
                                counts[i]++;
                                return true;
                        }
                }
                for (int i = 0; i < HOTBAR_SIZE; i++) {
                        if (counts[i] == 0) {
                                slots[i] = blockId;
                                counts[i] = 1;
                                return true;
                        }
                }
                return false; // hotbar full
        }

        /** Whether the selected slot holds a placeable block. */
        public boolean hasSelectedBlock() {
                return counts[selected] > 0 && slots[selected] != Blocks.AIR;
        }

        /** The selected slot's block id (air when empty). */
        public int getSelectedBlock() {
                return counts[selected] > 0 ? slots[selected] : Blocks.AIR;
        }

        /** Consumes one item from the selected slot (survival place). */
        public void consumeSelected() {
                if (counts[selected] > 0) {
                        counts[selected]--;
                        if (counts[selected] == 0) {
                                slots[selected] = Blocks.AIR;
                        }
                }
        }

        /** Vanilla Inventory.swapPaint: selection moves with wraparound. */
        public void swapPaint(int direction) {
                this.selected = ((this.selected + direction) % HOTBAR_SIZE + HOTBAR_SIZE)
                        % HOTBAR_SIZE;
        }

        public int getSelected() {
                return selected;
        }

        public void setSelected(int slot) {
                this.selected = Math.max(0, Math.min(HOTBAR_SIZE - 1, slot));
        }

        public int getCount(int slot) {
                return (slot >= 0 && slot < HOTBAR_SIZE) ? counts[slot] : 0;
        }

        public int getBlockId(int slot) {
                return (slot >= 0 && slot < HOTBAR_SIZE) ? slots[slot] : Blocks.AIR;
        }

        /**
         * Telemetry-bridge hook: directly fills a hotbar slot (the
         * programmatic equivalent of a creative-inventory pickup). Id 0 /
         * count 0 clears the slot.
         */
        public void telemetrySetSlot(int slot, int blockId, int count) {
                if (slot < 0 || slot >= HOTBAR_SIZE) {
                        return;
                }
                slots[slot] = (blockId == Blocks.AIR || count <= 0) ? Blocks.AIR : blockId;
                counts[slot] = Math.max(0, Math.min(STACK_SIZE, count));
        }
}
