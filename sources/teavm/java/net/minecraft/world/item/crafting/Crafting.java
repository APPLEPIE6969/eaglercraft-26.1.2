package net.minecraft.world.item.crafting;

import net.minecraft.world.item.Items;

/**
 * The crafting matcher + consumption logic over the port's
 * {@link CraftingRecipes} data (vanilla ShapedRecipe/ShapelessRecipe
 * semantics, reduced to what the recipe set needs).
 *
 * <p>Shaped matching follows vanilla's ShapedRecipe.matches: the grid's
 * non-empty cells must form a bounding box exactly the pattern's size, and
 * every pattern cell's accepted-id set must contain the grid cell's id.
 * Vanilla tries the pattern at every grid offset; with the exact-size
 * bounding-box requirement that is equivalent to comparing the box, and it
 * makes 3-wide tool patterns correctly uncraftable in the 2x2 inventory
 * grid. The horizontally mirrored pattern is tried too (vanilla
 * mirrors shaped recipes, so either axe orientation crafts).</p>
 *
 * <p>Shapeless matching is vanilla's ingredient multiset: every non-empty
 * grid cell is assigned to a distinct ingredient (backtracking), so the
 * single tagged log of a planks recipe matches anywhere in the grid.</p>
 *
 * <p>Taking a result consumes one item from every non-empty grid cell
 * (vanilla ResultSlot on recipes without container leftovers - the whole
 * milestone set qualifies).</p>
 */
public final class Crafting {

        private Crafting() {
        }

        /** The recipe matching the grid (null when nothing matches). */
        public static CraftingRecipes.Recipe match(int gridW, int gridH,
                        int[] cellIds) {
                // grid bounding box of the non-empty cells
                int minX = gridW;
                int minY = gridH;
                int maxX = -1;
                int maxY = -1;
                int filled = 0;
                for (int y = 0; y < gridH; y++) {
                        for (int x = 0; x < gridW; x++) {
                                if (cellIds[y * gridW + x] != 0) {
                                        filled++;
                                        if (x < minX) minX = x;
                                        if (y < minY) minY = y;
                                        if (x > maxX) maxX = x;
                                        if (y > maxY) maxY = y;
                                }
                        }
                }
                if (filled == 0) {
                        return null;
                }
                int boxW = maxX - minX + 1;
                int boxH = maxY - minY + 1;
                CraftingRecipes.Recipe[] all = CraftingRecipes.ALL;
                for (int r = 0; r < all.length; r++) {
                        CraftingRecipes.Recipe recipe = all[r];
                        if (recipe.shaped) {
                                if (recipe.width != boxW
                                                || recipe.height != boxH) {
                                        continue;
                                }
                                if (matchesShaped(recipe, gridW, cellIds,
                                                minX, minY, false)
                                                || matchesShaped(recipe, gridW,
                                                                cellIds, minX,
                                                                minY, true)) {
                                        return recipe;
                                }
                        } else {
                                if (recipe.ingredients.length != filled) {
                                        continue;
                                }
                                if (matchesShapeless(recipe, cellIds, filled)) {
                                        return recipe;
                                }
                        }
                }
                return null;
        }

        /** Vanilla ShapedRecipe.matches at the box origin (+ mirror). */
        private static boolean matchesShaped(CraftingRecipes.Recipe recipe,
                        int gridW, int[] cellIds, int minX, int minY,
                        boolean mirrored) {
                for (int ry = 0; ry < recipe.height; ry++) {
                        for (int rx = 0; rx < recipe.width; rx++) {
                                int gx = minX + (mirrored
                                                ? recipe.width - 1 - rx : rx);
                                int gy = minY + ry;
                                int cellId = cellIds[gy * gridW + gx];
                                int[] accepted = recipe.cells[ry * recipe.width
                                                + rx];
                                if (cellId == 0) {
                                        if (accepted != null) {
                                                return false;
                                        }
                                } else {
                                        if (accepted == null
                                                        || !contains(accepted,
                                                                        cellId)) {
                                                return false;
                                        }
                                }
                        }
                }
                return true;
        }

        /** Vanilla ShapelessRecipe.matches: distinct ingredient per cell. */
        private static boolean matchesShapeless(
                        CraftingRecipes.Recipe recipe, int[] cellIds,
                        int filled) {
                int n = recipe.ingredients.length;
                boolean[] usedIngredient = new boolean[n];
                boolean[] usedCell = new boolean[cellIds.length];
                // backtracking over the filled cells (n <= 4 here, so the
                // search space is tiny)
                return assign(recipe, cellIds, usedIngredient, usedCell, 0,
                                filled);
        }

        private static boolean assign(CraftingRecipes.Recipe recipe,
                        int[] cellIds, boolean[] usedIngredient,
                        boolean[] usedCell, int cellIndex, int remaining) {
                if (remaining == 0) {
                        return true;
                }
                while (cellIndex < cellIds.length && cellIds[cellIndex] == 0) {
                        cellIndex++;
                }
                if (cellIndex >= cellIds.length) {
                        return false;
                }
                int cellId = cellIds[cellIndex];
                for (int i = 0; i < recipe.ingredients.length; i++) {
                        if (usedIngredient[i]
                                        || !contains(recipe.ingredients[i],
                                                        cellId)) {
                                continue;
                        }
                        usedIngredient[i] = true;
                        usedCell[cellIndex] = true;
                        if (assign(recipe, cellIds, usedIngredient, usedCell,
                                        cellIndex + 1, remaining - 1)) {
                                return true;
                        }
                        usedIngredient[i] = false;
                        usedCell[cellIndex] = false;
                }
                return false;
        }

        private static boolean contains(int[] ids, int id) {
                for (int i = 0; i < ids.length; i++) {
                        if (ids[i] == id) {
                                return true;
                        }
                }
                return false;
        }

        /**
         * Vanilla ResultSlot consumption after taking the result: one item
         * leaves every non-empty grid cell (ids are 0-based like the
         * inventory; 0 = empty).
         */
        public static void consume(int[] cellIds, int[] cellCounts) {
                for (int i = 0; i < cellIds.length; i++) {
                        if (cellIds[i] != 0 && cellCounts[i] > 0) {
                                cellCounts[i]--;
                                if (cellCounts[i] == 0) {
                                        cellIds[i] = 0;
                                }
                        }
                }
        }

        /** Vanilla AbstractContainerMenu.getMaxStackSize for a stack id. */
        public static int maxStack(int id) {
                if (Items.isItem(id)) {
                        Items.Item item = Items.byId(id);
                        return item != null ? item.maxStack : 1;
                }
                return 64;
        }
}
