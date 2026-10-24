package net.minecraft.client.gui.screens.inventory;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code CreativeModeInventoryScreen} (the item-picker tab set).
 *
 * <p>Layout measured from the real 26.1.2
 * {@code textures/gui/container/creative_inventory/tab_items.png}
 * (195x136 panel at 0,0): the 9x5 item grid at (8,17) with 18px pitch,
 * the player hotbar row at y=111, the destroy-item slot at (174,111)
 * and the 12x15 scroller thumb riding a 90px track at x=174. Tab
 * chrome uses the 26x32 {@code tab_top_selected/unselected_N} sprites
 * (nine-slice stretched to the label width, unselected tabs sit 2px
 * lower via their baked content offset).</p>
 *
 * <p>Behavior is vanilla's item-picker tab: left click grabs a full
 * stack, right click grabs one, clicking the grid with a carried stack
 * voids it, the hotbar row and the destroy slot use
 * {@code AbstractContainerMenu} semantics via {@link Inventory}, the
 * wheel pages the grid ({@code scrollRow}), and E/ESC close. Tab
 * simplifications vs vanilla (documented in docs/UI-FIDELITY.md): a
 * single tab row with the three populated categories (Building,
 * Colored, Natural) - the search tab and the player-inventory tab are
 * not ported yet.</p>
 */
public class CreativeModeInventoryScreen extends Screen {

        /** Vanilla panel size (imageWidth/imageHeight). */
        private static final int PANEL_W = 195;
        private static final int PANEL_H = 136;

        /** Measured slot origins, panel-relative. */
        private static final int GRID_X = 8;
        private static final int GRID_Y = 17;
        private static final int HOTBAR_Y = 111;
        private static final int DESTROY_X = 174;
        private static final int DESTROY_Y = 111;
        private static final int SCROLLER_X = 174;
        private static final int SCROLLER_TRACK_Y = 17;
        private static final int SCROLLER_TRACK_H = 90;

        /** Grid rows visible at once. */
        private static final int GRID_ROWS = 5;
        private static final int GRID_COLS = 9;
        private static final int CELLS_PER_PAGE = GRID_ROWS * GRID_COLS;

        private static final int[] TAB_KEYS = {
                Blocks.TAB_BUILDING, Blocks.TAB_COLORED, Blocks.TAB_NATURAL
        };
        private static final String[] TAB_LABELS = {
                "Building Blocks", "Colored Blocks", "Natural Blocks"
        };

        /** Panel top-left (screen coords), set in init. */
        private int leftPos;
        private int topPos;

        /** Active tab index into TAB_KEYS. */
        private int tabIndex;
        /** Current grid page (0..maxScrollRow). */
        private int scrollRow;

        /** Per-tab cached lists, refreshed in init. */
        private List<Blocks.Block>[] tabLists;

        /** Hovered interactive slot (-1 = none): grid 0..44, hotbar 45..53, destroy 54. */
        private int hoveredSlot = -1;

        public CreativeModeInventoryScreen() {
        }

        @Override
        protected void init() {
                this.leftPos = (this.width - PANEL_W) / 2;
                this.topPos = (this.height - PANEL_H) / 2;
                this.tabLists = new List[TAB_KEYS.length];
                for (int i = 0; i < TAB_KEYS.length; i++) {
                        this.tabLists[i] = blocksFor(TAB_KEYS[i]);
                }
                clampScroll();
        }

        /** The creative tab list for a tab key (vanilla CreativeModeTabs). */
        private static List<Blocks.Block> blocksFor(int tab) {
                if (tab == Blocks.TAB_BUILDING) {
                        return Blocks.creativeBuilding();
                }
                if (tab == Blocks.TAB_COLORED) {
                        return Blocks.creativeColored();
                }
                return Blocks.creativeNatural();
        }

        private int maxScrollRow() {
                int count = tabLists[tabIndex].size();
                int extraRows = (Math.max(0, count - CELLS_PER_PAGE) + GRID_COLS - 1)
                        / GRID_COLS;
                return extraRows;
        }

        private void clampScroll() {
                this.scrollRow = Math.max(0, Math.min(maxScrollRow(), this.scrollRow));
        }

        /** The block in the hovered grid cell (air when past the list end). */
        private Blocks.Block gridBlock(int cell) {
                int index = scrollRow * GRID_COLS + cell;
                List<Blocks.Block> list = tabLists[tabIndex];
                return index < list.size() ? list.get(index) : Blocks.AIR_BLOCK;
        }

        /** Maps a screen point to the hovered slot (-1 = none). */
        private int slotAt(double mouseX, double mouseY) {
                int mx = (int) mouseX - leftPos;
                int my = (int) mouseY - topPos;
                // grid 9x5
                int col = (mx - GRID_X) / 18;
                int row = (my - GRID_Y) / 18;
                if (col >= 0 && col < GRID_COLS && row >= 0 && row < GRID_ROWS
                                && inCell(mx, my, GRID_X + col * 18, GRID_Y + row * 18)) {
                        return row * GRID_COLS + col;
                }
                // hotbar row
                if (col >= 0 && col < GRID_COLS
                                && inCell(mx, my, GRID_X + col * 18, HOTBAR_Y)) {
                        return 45 + col;
                }
                // destroy slot
                if (inCell(mx, my, DESTROY_X, DESTROY_Y)) {
                        return 54;
                }
                return -1;
        }

        private static boolean inCell(int mx, int my, int cellX, int cellY) {
                return mx >= cellX && mx < cellX + 18 && my >= cellY && my < cellY + 18;
        }

        /** The tab under a screen point (-1 = none). */
        private int tabAt(double mouseX, double mouseY) {
                int[] widths = tabWidths();
                int x = leftPos;
                for (int i = 0; i < TAB_KEYS.length; i++) {
                        int w = widths[i];
                        if (mouseX >= x && mouseX < x + w
                                        && mouseY >= topPos - 30 && mouseY < topPos + 4) {
                                return i;
                        }
                        x += w - 2;
                }
                return -1;
        }

        /** Nine-slice tab widths: label + padding, min the 26px sprite. */
        private int[] tabWidths() {
                int[] widths = new int[TAB_LABELS.length];
                for (int i = 0; i < TAB_LABELS.length; i++) {
                        widths[i] = Math.max(26, EaglerGuiImpl.widthOf(TAB_LABELS[i]) + 12);
                }
                return widths;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // vanilla dims the world behind container screens
                // (GuiGraphics.renderDimmedBackground)
                EaglerGuiImpl.fillRect(0, 0, this.width, this.height, 0xB0101010);

                // panel
                EaglerGuiImpl.drawSprite("container/creative_inventory/tab_items",
                                leftPos, topPos, PANEL_W, PANEL_H);

                // tabs (drawn after the panel so their bottoms merge into it)
                int[] widths = tabWidths();
                int tx = leftPos;
                for (int i = 0; i < TAB_LABELS.length; i++) {
                        boolean selected = i == tabIndex;
                        String sprite = "container/creative_inventory/tab_top_"
                                + (selected ? "selected_" : "unselected_") + "2";
                        EaglerGuiImpl.drawSprite9Slices(sprite, tx, topPos - 28,
                                        widths[i], 32, 2, 2, 2, 0);
                        int labelW = EaglerGuiImpl.widthOf(TAB_LABELS[i]);
                        EaglerGuiImpl.drawText(TAB_LABELS[i],
                                        tx + (widths[i] - labelW) / 2.0f, topPos - 28 + 9,
                                        selected ? 0xFF7F7F7F : 0xFF3F3F3F, false);
                        tx += widths[i] - 2;
                }

                Inventory inventory = this.minecraft.getInventory();

                // hovered-slot highlight (under the items, vanilla order)
                if (hoveredSlot >= 0) {
                        int hx = -1;
                        int hy = -1;
                        if (hoveredSlot < 45) {
                                hx = leftPos + GRID_X + (hoveredSlot % GRID_COLS) * 18;
                                hy = topPos + GRID_Y + (hoveredSlot / GRID_COLS) * 18;
                        } else if (hoveredSlot < 54) {
                                hx = leftPos + GRID_X + (hoveredSlot - 45) * 18;
                                hy = topPos + HOTBAR_Y;
                        } else {
                                hx = leftPos + DESTROY_X;
                                hy = topPos + DESTROY_Y;
                        }
                        EaglerGuiImpl.fillRect(hx, hy, 16, 16, 0x80FFFFFF);
                }

                // item grid (45 visible cells)
                for (int cell = 0; cell < CELLS_PER_PAGE; cell++) {
                        Blocks.Block block = gridBlock(cell);
                        if (block == Blocks.AIR_BLOCK) {
                                continue;
                        }
                        Gui.drawItemIcon(block.id,
                                        leftPos + GRID_X + (cell % GRID_COLS) * 18 + 1,
                                        topPos + GRID_Y + (cell / GRID_COLS) * 18 + 1);
                }

                // player hotbar row
                for (int i = 0; i < 9; i++) {
                        Gui.drawItem(inventory.getBlockId(i), inventory.getCount(i),
                                        leftPos + GRID_X + i * 18 + 1, topPos + HOTBAR_Y + 1);
                }

                // scroller thumb
                int maxRow = maxScrollRow();
                if (maxRow > 0) {
                        float frac = (float) scrollRow / maxRow;
                        float thumbY = topPos + SCROLLER_TRACK_Y
                                + frac * (SCROLLER_TRACK_H - 15);
                        EaglerGuiImpl.drawSprite("container/creative_inventory/scroller",
                                        leftPos + SCROLLER_X, thumbY, 12, 15);
                }

                // carried stack follows the cursor
                Gui.drawItem(inventory.getCarriedBlock(), inventory.getCarriedCount(),
                                mouseX - 1, mouseY - 1);

                // tooltip
                if (hoveredSlot >= 0 && inventory.getCarriedCount() == 0) {
                        String name = null;
                        if (hoveredSlot < 45) {
                                Blocks.Block block = gridBlock(hoveredSlot);
                                if (block != Blocks.AIR_BLOCK) {
                                        name = block.displayName;
                                }
                        } else if (hoveredSlot < 54) {
                                name = Gui.blockName(
                                                inventory.getBlockId(hoveredSlot - 45));
                        }
                        if (name != null) {
                                Gui.drawTooltip(name, mouseX, mouseY,
                                                this.width, this.height);
                        }
                }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.minecraft == null) {
                        return super.mouseClicked(mouseX, mouseY, button);
                }
                Inventory inventory = this.minecraft.getInventory();

                int tab = tabAt(mouseX, mouseY);
                if (tab >= 0 && tab != tabIndex) {
                        ClientMain.log("[CreativeScreen] tab -> " + TAB_LABELS[tab]);
                        this.tabIndex = tab;
                        this.scrollRow = 0;
                        this.hoveredSlot = -1;
                        return true;
                }

                int slot = slotAt(mouseX, mouseY);
                if (slot >= 0) {
                        if (slot < 45) {
                                // item-picker cells: empty cursor grabs, carried voids
                                Blocks.Block block = gridBlock(slot);
                                if (inventory.getCarriedCount() > 0) {
                                        inventory.setCarried(Blocks.AIR, 0);
                                } else if (block != Blocks.AIR_BLOCK) {
                                        inventory.creativePickup(block.id, button);
                                }
                        } else if (slot < 54) {
                                // the player's hotbar row: container semantics
                                inventory.clickSlot(slot - 45, button);
                        } else {
                                // destroy slot voids the carried stack
                                inventory.setCarried(Blocks.AIR, 0);
                        }
                        return true;
                }
                // clicking anywhere else with a carried stack also voids it
                // (vanilla creative clicks outside the grid delete the stack)
                if (inventory.getCarriedCount() > 0) {
                        inventory.setCarried(Blocks.AIR, 0);
                        return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                // the port routes wheel events DOM-style (positive = down);
                // scrolling down advances the grid pages
                if (delta > 0 && scrollRow > 0) {
                        scrollRow--;
                        return true;
                }
                if (delta < 0 && scrollRow < maxScrollRow()) {
                        scrollRow++;
                        return true;
                }
                return super.mouseScrolled(mouseX, mouseY, delta);
        }

        @Override
        public void mouseMoved(double mouseX, double mouseY) {
                this.hoveredSlot = slotAt(mouseX, mouseY);
                super.mouseMoved(mouseX, mouseY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == KeyMapping.INVENTORY.getKey()
                                || (keyCode == 1 && this.shouldCloseOnEsc())) {
                        ClientMain.log("[CreativeScreen] closed with key " + keyCode);
                        this.onClose();
                        return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean isPauseScreen() {
                return false;
        }
}
