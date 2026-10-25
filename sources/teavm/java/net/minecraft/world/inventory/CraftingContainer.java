package net.minecraft.world.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.crafting.Crafting;
import net.minecraft.world.item.crafting.CraftingRecipes;

/**
 * The crafting grid container behind the 2x2 inventory grid and the 3x3
 * crafting-table screen (vanilla's {@code TransientCraftingContainer} +
 * the craft/result slots of {@code InventoryMenu}/{@code CraftingMenu}).
 *
 * <p>The grid is transient: vanilla keeps it in the open menu and returns
 * its contents to the player inventory on close
 * ({@code AbstractContainerMenu.removed} -> placeItemBackInInventory),
 * which is exactly what {@link #returnContents} does here (items that no
 * longer fit are lost, the documented item-entity-free simplification of
 * the mining path).</p>
 *
 * <p>Slot clicks share vanilla's {@code AbstractContainerMenu.doClick}
 * through {@link Inventory#doClick}; the result slot follows
 * {@code ResultSlot}: left click takes one craft onto the cursor (empty
 * cursor, or the same stack with room), shift click crafts repeatedly
 * into the inventory until the grid runs out or the inventory is full.
 * Every grid mutation recomputes the result ({@code slotsChanged}).</p>
 */
public class CraftingContainer {

        private final int width;
        private final int height;
        private final int[] cellIds;
        private final int[] cellCounts;
        private int resultId;
        private int resultCount;
        private boolean resultPresent;

        public CraftingContainer(int width, int height) {
                this.width = width;
                this.height = height;
                this.cellIds = new int[width * height];
                this.cellCounts = new int[width * height];
        }

        public int getWidth() {
                return width;
        }

        public int getHeight() {
                return height;
        }

        public int getSize() {
                return cellIds.length;
        }

        public int getCellId(int index) {
                return (index >= 0 && index < cellIds.length) ? cellIds[index]
                                : 0;
        }

        public int getCellCount(int index) {
                return (index >= 0 && index < cellCounts.length)
                                ? cellCounts[index] : 0;
        }

        /** The computed result stack (air when nothing matches). */
        public int getResultId() {
                return resultPresent ? resultId : 0;
        }

        public int getResultCount() {
                return resultPresent ? resultCount : 0;
        }

        /** Vanilla slotsChanged: recompute the result from the grid. */
        public void recompute() {
                CraftingRecipes.Recipe recipe = Crafting.match(width, height,
                                cellIds);
                if (recipe != null) {
                        resultId = recipe.resultId;
                        resultCount = recipe.resultCount;
                        resultPresent = true;
                } else {
                        resultId = 0;
                        resultCount = 0;
                        resultPresent = false;
                }
        }

        /** doClick on a grid cell (the cursor stack lives in the inventory). */
        public void clickCell(int index, int button, Inventory inventory) {
                Inventory.doClick(cellIds, cellCounts, index, button,
                                inventory);
                recompute();
        }

        /**
         * Vanilla result-slot click. Left takes one craft (onto an empty
         * cursor or a mergeable same-id stack); shift crafts repeatedly
         * into the inventory. Returns true when something was crafted.
         */
        public boolean clickResult(boolean shift, Inventory inventory) {
                recompute();
                if (!resultPresent) {
                        return false;
                }
                int cap = Crafting.maxStack(resultId);
                if (!shift) {
                        int carriedId = inventory.getCarriedBlock();
                        int carriedCount = inventory.getCarriedCount();
                        if (carriedCount == 0) {
                                inventory.setCarried(resultId, resultCount);
                        } else if (carriedId == resultId
                                        && carriedCount + resultCount <= cap) {
                                inventory.setCarried(resultId,
                                                carriedCount + resultCount);
                        } else {
                                return false;
                        }
                        Crafting.consume(cellIds, cellCounts);
                        recompute();
                        return true;
                }
                // vanilla QUICK_MOVE on the result slot: craft all
                boolean any = false;
                // safety bound: total grid stock is finite
                int guard = 64 * 36;
                while (guard-- > 0) {
                        recompute();
                        if (!resultPresent) {
                                break;
                        }
                        int cap2 = Crafting.maxStack(resultId);
                        if (!inventory.addItem(resultId, resultCount, cap2)) {
                                break;
                        }
                        Crafting.consume(cellIds, cellCounts);
                        any = true;
                }
                if (any) {
                        recompute();
                }
                return any;
        }

        /**
         * Vanilla AbstractContainerMenu.removed: the grid's contents go
         * back to the player inventory (overflow is dropped - no item
         * entities in the port).
         */
        public void returnContents(Inventory inventory) {
                for (int i = 0; i < cellIds.length; i++) {
                        if (cellIds[i] != 0 && cellCounts[i] > 0) {
                                inventory.addItem(cellIds[i], cellCounts[i],
                                                Crafting.maxStack(cellIds[i]));
                        }
                        cellIds[i] = 0;
                        cellCounts[i] = 0;
                }
                recompute();
        }

        /** Whether every grid cell is empty. */
        public boolean isEmpty() {
                for (int i = 0; i < cellIds.length; i++) {
                        if (cellIds[i] != 0 && cellCounts[i] > 0) {
                                return false;
                        }
                }
                return true;
        }
}
