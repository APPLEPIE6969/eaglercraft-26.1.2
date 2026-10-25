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
                return addItem(blockId, 1, STACK_SIZE);
        }

        /**
         * Vanilla Inventory.add with an explicit per-item stack limit
         * (tools cap at 1, ingredients at 64): first merge into matching
         * stacks with room, then first empty slot, hotbar first. Returns
         * false when nothing fit (inventory full).
         */
        public boolean addItem(int blockId, int count, int maxStack) {
                if (blockId == Blocks.AIR || count <= 0) {
                        return false;
                }
                int cap = Math.max(1, Math.min(STACK_SIZE, maxStack));
                int left = count;
                for (int i = 0; i < TOTAL_SIZE && left > 0; i++) {
                        if (slots[i] == blockId && counts[i] > 0
                                        && counts[i] < cap) {
                                int moved = Math.min(cap - counts[i], left);
                                counts[i] += moved;
                                left -= moved;
                        }
                }
                for (int i = 0; i < TOTAL_SIZE && left > 0; i++) {
                        if (counts[i] == 0) {
                                int moved = Math.min(cap, left);
                                slots[i] = blockId;
                                counts[i] = moved;
                                left -= moved;
                        }
                }
                return left < count;
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
                // clamp the REQUESTED count (the pre-crafting code clamped
                // the stale field here, so every pickup produced an empty
                // cursor stack - caught by the crafting e2e)
                carriedCount = Math.max(0, Math.min(STACK_SIZE, count));
        }

        // ===== vanilla AbstractContainerMenu.doClick (survival slots) =====

        /**
         * Vanilla left/right click on a slot: pickup, place, merge, swap.
         * Left = whole stack; right = pickup ceil-half, or place one.
         *
         * <p>Shared by the player inventory ({@link #clickSlot}) and the
         * crafting grids ({code CraftingContainer.clickCell}) - vanilla's
         * AbstractContainerMenu.doClick is one code path over any slot
         * list. {@code ids} uses 0 = empty; {@code inv} carries the cursor
         * stack. Merge space respects the id's vanilla stack limit
         * (tools stack to 1, blocks to 64).</p>
         */
        public static void doClick(int[] ids, int[] counts, int index,
                        int button, Inventory inv) {
                if (index < 0 || index >= ids.length) {
                        return;
                }
                int slotBlock = ids[index];
                int slotCount = counts[index];
                int cap = net.minecraft.world.item.crafting.Crafting
                        .maxStack(slotBlock == 0 && inv.carriedCount > 0
                                ? inv.carriedBlock : slotBlock);
                if (inv.carriedCount == 0) {
                        // pickup
                        if (slotCount > 0) {
                                if (button == 0) {
                                        // whole stack
                                        inv.setCarried(slotBlock, slotCount);
                                        writeSlot(ids, counts, index, 0, 0);
                                } else {
                                        // ceil half
                                        int take = (slotCount + 1) / 2;
                                        inv.setCarried(slotBlock, take);
                                        writeSlot(ids, counts, index,
                                                        slotBlock,
                                                        slotCount - take);
                                }
                        }
                        return;
                }
                // place / merge
                if (slotCount == 0) {
                        // place onto empty
                        if (button == 0) {
                                writeSlot(ids, counts, index,
                                                inv.carriedBlock,
                                                inv.carriedCount);
                                inv.setCarried(0, 0);
                        } else {
                                writeSlot(ids, counts, index,
                                                inv.carriedBlock, 1);
                                inv.setCarried(inv.carriedBlock,
                                                inv.carriedCount - 1);
                        }
                        return;
                }
                if (slotBlock == inv.carriedBlock) {
                        // merge up to the stack cap
                        int space = cap - slotCount;
                        int moved = Math.min(space,
                                        button == 0 ? inv.carriedCount : 1);
                        if (moved > 0) {
                                writeSlot(ids, counts, index, slotBlock,
                                                slotCount + moved);
                                inv.setCarried(inv.carriedBlock,
                                                inv.carriedCount - moved);
                        }
                        return;
                }
                // swap (left only; vanilla right-click-swap also swaps whole)
                if (button == 0) {
                        int nb = inv.carriedBlock;
                        int nc = inv.carriedCount;
                        inv.setCarried(slotBlock, slotCount);
                        writeSlot(ids, counts, index, nb, nc);
                }
        }

        /** The doClick slot write: 0 count or 0 id clears to air. */
        private static void writeSlot(int[] ids, int[] counts, int index,
                        int blockId, int count) {
                ids[index] = (blockId == 0 || count <= 0) ? 0 : blockId;
                counts[index] = Math.max(0, Math.min(STACK_SIZE, count));
        }

        /** The inventory's own arrays through the shared doClick. */
        public void clickSlot(int slot, int button) {
                doClick(slots, counts, slot, button, this);
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
