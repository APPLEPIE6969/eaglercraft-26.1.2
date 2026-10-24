package net.minecraft.world.entity.player;

import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.world.entity.player.Inventory.
 *
 * <p>The full vanilla 36-slot model: 0-8 is the hotbar, 9-35 the main
 * inventory. {@link #addItem} follows vanilla's
 * getSlotWithRemainingSpace-then-getFreeSlot order (first matching stack
 * anywhere, then first empty slot, both hotbar-first); stack cap 64;
 * scroll swaps the selected slot with wraparound (MouseHandler's
 * Inventory.swapPaint).</p>
 *
 * <p>Slot-click semantics (vanilla AbstractContainerMenu.doClick) for
 * the inventory screens live here as {@link #clickSlot}: left pickup /
 * place / merge / swap, right pickup-half / place-one, shift
 * quick-move. The carried ("cursor") stack is held in {@link #carried}
 * exactly like AbstractContainerMenu.carried.</p>
 *
 * <p>Not ported (no surface yet): armor, offhand, item entities - mined
 * blocks go straight to the inventory, which is documented in
 * docs/UI-FIDELITY.md as the item-entity-free simplification.</p>
 */
public class Inventory {

        public static final int HOTBAR_SIZE = 9;
        public static final int MAIN_SIZE = 27;
        public static final int TOTAL_SIZE = HOTBAR_SIZE + MAIN_SIZE;
        public static final int STACK_SIZE = 64;

        /** Vanilla Inventory.items: [0..8] hotbar, [9..35] main. */
        private final int[] slots = new int[TOTAL_SIZE];
        private final int[] counts = new int[TOTAL_SIZE];
        /** Vanilla Inventory.selected. */
        private int selected;
        /** Vanilla AbstractContainerMenu.carried (the cursor stack). */
        private int carriedBlock = Blocks.AIR;
        private int carriedCount;

        /** Vanilla Inventory.add: first matching stack, then first empty (hotbar first). */
        public boolean addItem(int blockId) {
                if (blockId == Blocks.AIR) {
                        return false;
                }
                for (int i = 0; i < TOTAL_SIZE; i++) {
                        if (slots[i] == blockId && counts[i] > 0 && counts[i] < STACK_SIZE) {
                                counts[i]++;
                                return true;
                        }
                }
                for (int i = 0; i < TOTAL_SIZE; i++) {
                        if (counts[i] == 0) {
                                slots[i] = blockId;
                                counts[i] = 1;
                                return true;
                        }
                }
                return false; // inventory full
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
                consumeSlot(selected);
        }

        /** Consumes one item from a slot (creative placement consumes none). */
        public void consumeSlot(int slot) {
                if (slot >= 0 && slot < TOTAL_SIZE && counts[slot] > 0) {
                        counts[slot]--;
                        if (counts[slot] == 0) {
                                slots[slot] = Blocks.AIR;
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
                return (slot >= 0 && slot < TOTAL_SIZE) ? counts[slot] : 0;
        }

        public int getBlockId(int slot) {
                return (slot >= 0 && slot < TOTAL_SIZE) ? slots[slot] : Blocks.AIR;
        }

        /** Sets a slot directly (inventory-screen writes; id 0 / count 0 clears). */
        public void setSlot(int slot, int blockId, int count) {
                if (slot < 0 || slot >= TOTAL_SIZE) {
                        return;
                }
                slots[slot] = (blockId == Blocks.AIR || count <= 0) ? Blocks.AIR : blockId;
                counts[slot] = Math.max(0, Math.min(STACK_SIZE, count));
        }

        // ===== carried (cursor) stack =====

        public int getCarriedBlock() {
                return carriedBlock;
        }

        public int getCarriedCount() {
                return carriedCount;
        }

        public void setCarried(int blockId, int count) {
                carriedBlock = (blockId == Blocks.AIR || count <= 0) ? Blocks.AIR : blockId;
                carriedCount = Math.max(0, Math.min(STACK_SIZE, carriedCount));
        }

        // ===== vanilla AbstractContainerMenu.doClick (survival slots) =====

        /**
         * Vanilla left/right click on a slot: pickup, place, merge, swap.
         * Left = whole stack; right = pickup ceil-half, or place one.
         */
        public void clickSlot(int slot, int button) {
                if (slot < 0 || slot >= TOTAL_SIZE) {
                        return;
                }
                int slotBlock = slots[slot];
                int slotCount = counts[slot];
                if (carriedCount == 0) {
                        // pickup
                        if (slotCount > 0) {
                                if (button == 0) {
                                        // whole stack
                                        setCarried(slotBlock, slotCount);
                                        setSlot(slot, Blocks.AIR, 0);
                                } else {
                                        // ceil half
                                        int take = (slotCount + 1) / 2;
                                        setCarried(slotBlock, take);
                                        setSlot(slot, slotBlock, slotCount - take);
                                }
                        }
                        return;
                }
                // place / merge
                if (slotCount == 0) {
                        // place onto empty
                        if (button == 0) {
                                setSlot(slot, carriedBlock, carriedCount);
                                setCarried(Blocks.AIR, 0);
                        } else {
                                setSlot(slot, carriedBlock, 1);
                                setCarried(carriedBlock, carriedCount - 1);
                        }
                        return;
                }
                if (slotBlock == carriedBlock) {
                        // merge up to STACK_SIZE
                        int space = STACK_SIZE - slotCount;
                        int moved = Math.min(space, button == 0 ? carriedCount : 1);
                        if (moved > 0) {
                                setSlot(slot, slotBlock, slotCount + moved);
                                setCarried(carriedBlock, carriedCount - moved);
                        }
                        return;
                }
                // swap (left only; vanilla right-click-swap also swaps whole)
                if (button == 0) {
                        int nb = carriedBlock;
                        int nc = carriedCount;
                        setCarried(slotBlock, slotCount);
                        setSlot(slot, nb, nc);
                }
        }

        /**
         * Vanilla quick-move (shift-click): hotbar <-> main inventory
         * jump, merging into matching stacks first. Returns true when
         * something moved.
         */
        public boolean quickMove(int slot) {
                if (slot < 0 || slot >= TOTAL_SIZE) {
                        return false;
                }
                int blockId = slots[slot];
                int count = counts[slot];
                if (count == 0) {
                        return false;
                }
                int from = slot;
                int to = (slot < HOTBAR_SIZE) ? HOTBAR_SIZE : 0;
                int end = (slot < HOTBAR_SIZE) ? TOTAL_SIZE : HOTBAR_SIZE;
                // first matching stacks
                for (int i = to; i < end && counts[from] > 0; i++) {
                        if (slots[i] == blockId && counts[i] > 0 && counts[i] < STACK_SIZE) {
                                int moved = Math.min(STACK_SIZE - counts[i], counts[from]);
                                setSlot(i, blockId, counts[i] + moved);
                                setSlot(from, blockId, counts[from] - moved);
                        }
                }
                // then first empty slot in the destination range
                if (counts[from] > 0) {
                        for (int i = to; i < end; i++) {
                                if (counts[i] == 0) {
                                        setSlot(i, blockId, counts[from]);
                                        setSlot(from, Blocks.AIR, 0);
                                        break;
                                }
                        }
                }
                return true;
        }

        /**
         * Creative-source click (vanilla CreativeModeInventoryScreen
         * item list): left click grabs a full stack (replacing any
         * carried stack), right click grabs one.
         */
        public void creativePickup(int blockId, int button) {
                if (blockId == Blocks.AIR) {
                        return;
                }
                if (button == 0) {
                        setCarried(blockId, STACK_SIZE);
                } else {
                        setCarried(blockId, 1);
                }
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
                setSlot(slot, blockId, count);
        }
}
