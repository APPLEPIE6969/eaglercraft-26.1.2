package net.minecraft.client.gui.screens.inventory;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingContainer;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code CraftingScreen} - the crafting-table container (the 3x3 grid
 * that opens on right-clicking a placed crafting_table block).
 *
 * <p>Layout measured from the real 26.1.2
 * {@code textures/gui/container/crafting_table.png} (176x166 panel at 0,0)
 * with every slot cell located by the texture's own inset borders, exactly
 * like the survival inventory panel:</p>
 *
 * <ul>
 *   <li>crafting 3x3: x = 30 + 18*col, y = 17 + 18*row (vanilla
 *       CraftingMenu addCraftingGridSlots(30, 17))</li>
 *   <li>result: (124,35) (vanilla CraftingMenu addResultSlot(124, 35) -
 *       the texture draws the modern larger 26x26 result cell centered on
 *       that item origin)</li>
 *   <li>player main 27: x = 8 + 18*col, y = 84 + 18*row; hotbar 9:
 *       x = 8 + 18*col, y = 142 (addStandardInventorySlots(8, 84))</li>
 * </ul>
 *
 * <p>Behavior is the survival inventory's: shared doClick grid cells,
 * result take (left) / craft-all (shift), carried stack on the cursor,
 * tooltips, E/ESC close returning the grid contents to the inventory.
 * Vanilla's recipe book button is a documented deviation (not ported -
 * the button sprite is not in the shipped texture set).</p>
 */
public class CraftingTableScreen extends Screen {

        /** Vanilla panel size (imageWidth/imageHeight). */
        private static final int PANEL_W = 176;
        private static final int PANEL_H = 166;

        /** Measured slot origins, panel-relative (vanilla menu constants). */
        private static final int GRID_X = 30;
        private static final int GRID_Y = 17;
        private static final int RESULT_X = 124;
        private static final int RESULT_Y = 35;
        private static final int MAIN_X = 8;
        private static final int MAIN_Y = 84;
        private static final int HOTBAR_Y = 142;

        /** Vanilla label. */
        private static final String LABEL_CRAFTING = "Crafting";

        /** Panel top-left (screen coords), set in init. */
        private int leftPos;
        private int topPos;

        /** Hovered interactive slot (-1 = none); grid 0..8, main 20..46,
         * hotbar 47..55, result 60. */
        private int hoveredSlot = -1;

        /** The transient 3x3 crafting grid (vanilla CraftingMenu.craftSlots). */
        private final CraftingContainer craft = new CraftingContainer(3, 3);

        public CraftingTableScreen() {
        }

        @Override
        protected void init() {
                this.leftPos = (this.width - PANEL_W) / 2;
                this.topPos = (this.height - PANEL_H) / 2;
                this.craft.recompute();
        }

        /** The open crafting grid (telemetry bridge + tests). */
        public CraftingContainer getCraftingContainer() {
                return craft;
        }

        @Override
        public void onClose() {
                // vanilla AbstractContainerMenu.removed: the crafting grid
                // and the carried stack go back to the player inventory
                // before the screen closes
                Inventory inventory = this.minecraft.getInventory();
                this.craft.returnContents(inventory);
                if (inventory.getCarriedCount() > 0) {
                        inventory.addItem(inventory.getCarriedBlock(),
                                        inventory.getCarriedCount(),
                                        net.minecraft.world.item.crafting.Crafting
                                                .maxStack(inventory.getCarriedBlock()));
                        inventory.setCarried(0, 0);
                }
                super.onClose();
        }

        /** Maps a screen-space point to the hovered interactive slot. */
        private int slotAt(double mouseX, double mouseY) {
                int mx = (int) mouseX - leftPos;
                int my = (int) mouseY - topPos;
                // crafting 3x3 (codes 0..8, row-major)
                int gcol = (mx - GRID_X) / 18;
                int grow = (my - GRID_Y) / 18;
                if (gcol >= 0 && gcol < 3 && grow >= 0 && grow < 3
                                && cellContains(mx, my,
                                                GRID_X + gcol * 18,
                                                GRID_Y + grow * 18)) {
                        return grow * 3 + gcol;
                }
                // result (code 60)
                if (cellContains(mx, my, RESULT_X, RESULT_Y)) {
                        return 60;
                }
                // main 27 (codes 20..46)
                int col = (mx - MAIN_X) / 18;
                int row = (my - MAIN_Y) / 18;
                if (col >= 0 && col < 9 && row >= 0 && row < 3
                                && cellContains(mx, my, MAIN_X + col * 18,
                                                MAIN_Y + row * 18)) {
                        return 20 + row * 9 + col;
                }
                // hotbar 9 (codes 47..55)
                if (col >= 0 && col < 9
                                && cellContains(mx, my, MAIN_X + col * 18,
                                                HOTBAR_Y)) {
                        return 47 + col;
                }
                return -1;
        }

        private static boolean cellContains(int mx, int my, int cellX,
                        int cellY) {
                return mx >= cellX && mx < cellX + 18 && my >= cellY
                                && my < cellY + 18;
        }

        /** Interactive main-inventory code (20..46) -> Inventory slot. */
        private static int inventorySlot(int interactive) {
                // codes 20..46 = main inventory (Inventory 9..35)
                // codes 47..55 = hotbar (Inventory 0..8)
                return interactive < 47 ? interactive - 11
                                : interactive - 47;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY,
                        float partialTick) {
                // vanilla dims the world behind container screens
                EaglerGuiImpl.fillRect(0, 0, this.width, this.height,
                                0xB0101010);

                EaglerGuiImpl.drawSprite("container/crafting_table", leftPos,
                                topPos, PANEL_W, PANEL_H);

                // vanilla label (CraftingScreen draws the title at (28, 6))
                EaglerGuiImpl.drawText(LABEL_CRAFTING, leftPos + 28,
                                topPos + 6, 0xFF3F3F3F, false);

                Inventory inventory = this.minecraft.getInventory();

                // hovered-slot highlight (under the items, vanilla order)
                if (hoveredSlot >= 0) {
                        int hx;
                        int hy;
                        if (hoveredSlot < 9) {
                                hx = leftPos + GRID_X
                                                + (hoveredSlot % 3) * 18;
                                hy = topPos + GRID_Y
                                                + (hoveredSlot / 3) * 18;
                        } else if (hoveredSlot == 60) {
                                hx = leftPos + RESULT_X;
                                hy = topPos + RESULT_Y;
                        } else if (hoveredSlot < 47) {
                                hx = leftPos + MAIN_X
                                                + ((hoveredSlot - 20) % 9) * 18;
                                hy = topPos + MAIN_Y
                                                + ((hoveredSlot - 20) / 9) * 18;
                        } else {
                                hx = leftPos + MAIN_X
                                                + (hoveredSlot - 47) * 18;
                                hy = topPos + HOTBAR_Y;
                        }
                        EaglerGuiImpl.fillRect(hx, hy, 16, 16, 0x80FFFFFF);
                }

                // crafting 3x3 + computed result
                for (int i = 0; i < 9; i++) {
                        Gui.drawItem(craft.getCellId(i), craft.getCellCount(i),
                                        leftPos + GRID_X + (i % 3) * 18 + 1,
                                        topPos + GRID_Y + (i / 3) * 18 + 1);
                }
                Gui.drawItem(craft.getResultId(), craft.getResultCount(),
                                leftPos + RESULT_X + 1, topPos + RESULT_Y + 1);

                // main 27 + hotbar 9
                for (int i = 0; i < 27; i++) {
                        Gui.drawItem(inventory.getBlockId(inventorySlot(20 + i)),
                                        inventory.getCount(inventorySlot(20 + i)),
                                        leftPos + MAIN_X + (i % 9) * 18 + 1,
                                        topPos + MAIN_Y + (i / 9) * 18 + 1);
                }
                for (int i = 0; i < 9; i++) {
                        Gui.drawItem(inventory.getBlockId(i),
                                        inventory.getCount(i),
                                        leftPos + MAIN_X + i * 18 + 1,
                                        topPos + HOTBAR_Y + 1);
                }

                // carried stack follows the cursor
                Gui.drawItem(inventory.getCarriedBlock(),
                                inventory.getCarriedCount(),
                                mouseX - 1, mouseY - 1);

                // tooltip for the hovered slot
                if (hoveredSlot >= 0 && inventory.getCarriedCount() == 0) {
                        String name = null;
                        if (hoveredSlot == 60) {
                                name = Gui.blockName(craft.getResultId());
                        } else if (hoveredSlot < 9) {
                                name = Gui.blockName(craft.getCellId(hoveredSlot));
                        } else {
                                int inv = inventorySlot(hoveredSlot);
                                name = Gui.blockName(inventory.getBlockId(inv));
                        }
                        if (name != null) {
                                Gui.drawTooltip(name, mouseX, mouseY,
                                                this.width, this.height);
                        }
                }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                int slot = slotAt(mouseX, mouseY);
                if (slot >= 0 && this.minecraft != null) {
                        Inventory inventory = this.minecraft.getInventory();
                        if (slot == 60) {
                                boolean shift = PlatformInput
                                                .isKeyDown(KeyMapping.SCAN_LSHIFT);
                                this.craft.clickResult(shift, inventory);
                                ClientMain.log("[CraftingTableScreen] result "
                                                + (shift ? "craft-all" : "take"));
                        } else if (slot < 9) {
                                this.craft.clickCell(slot, button, inventory);
                                ClientMain.log("[CraftingTableScreen] craft cell "
                                                + slot + " button " + button);
                        } else if (PlatformInput
                                        .isKeyDown(KeyMapping.SCAN_LSHIFT)) {
                                inventory.quickMove(inventorySlot(slot));
                        } else {
                                inventory.clickSlot(inventorySlot(slot), button);
                        }
                        return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void mouseMoved(double mouseX, double mouseY) {
                this.hoveredSlot = slotAt(mouseX, mouseY);
                super.mouseMoved(mouseX, mouseY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                // vanilla: the inventory key itself closes the container
                if (keyCode == KeyMapping.INVENTORY.getKey()
                                || (keyCode == 1 && this.shouldCloseOnEsc())) {
                        ClientMain.log("[CraftingTableScreen] closed with key "
                                        + keyCode);
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
