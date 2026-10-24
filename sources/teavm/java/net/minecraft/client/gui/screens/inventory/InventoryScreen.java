package net.minecraft.client.gui.screens.inventory;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;

/**
 * EaglerCraft 26.1.2 browser override for the MC survival
 * {@code InventoryScreen} - the E-key container.
 *
 * <p>Layout measured from the real 26.1.2
 * {@code textures/gui/container/inventory.png} (176x166 panel at 0,0)
 * with every slot cell located by the texture's own inset borders:</p>
 *
 * <ul>
 *   <li>armor column: x=7, y = 7 + 18*row (rows 0..3) - rendered
 *       inert (no armor items exist in the port yet)</li>
 *   <li>crafting 2x2: (97,17) (115,17) (97,35) (115,35) with the result
 *       slot at (153,27) - rendered inert (no recipes yet)</li>
 *   <li>main 27: x = 7 + 18*col, y = 83 + 18*row (3 rows)</li>
 *   <li>hotbar 9: x = 7 + 18*col, y = 141</li>
 * </ul>
 *
 * <p>Slot interaction is vanilla
 * {@code AbstractContainerMenu.doClick} via {@link Inventory#clickSlot}:
 * left pickup/place/merge/swap, right pickup-half/place-one, and
 * shift-click quick-move between the main inventory and the hotbar
 * ({@link Inventory#quickMove}). The carried stack follows the cursor
 * and hovering a populated slot shows its vanilla tooltip. E and ESC
 * both close (vanilla keyPressed checks key.inventory first, then
 * shouldCloseOnEsc).</p>
 */
public class InventoryScreen extends Screen {

        /** Vanilla panel size (imageWidth/imageHeight). */
        private static final int PANEL_W = 176;
        private static final int PANEL_H = 166;

        /** Measured slot origins, panel-relative. */
        private static final int MAIN_X = 7;
        private static final int MAIN_Y = 83;
        private static final int HOTBAR_Y = 141;
        private static final int ARMOR_X = 7;
        private static final int ARMOR_Y = 7;
        private static final int CRAFT_X = 97;
        private static final int CRAFT_Y = 17;
        private static final int RESULT_X = 153;
        private static final int RESULT_Y = 27;

        /** Vanilla labels (drawn by code, not in the texture). */
        private static final String LABEL_CRAFTING = "Crafting";
        private static final String LABEL_INVENTORY = "Inventory";

        /** Panel top-left (screen coords), set in init. */
        private int leftPos;
        private int topPos;

        /** Hovered interactive slot (-1 = none); main 0..26, hotbar 27..35. */
        private int hoveredSlot = -1;

        public InventoryScreen() {
        }

        @Override
        protected void init() {
                this.leftPos = (this.width - PANEL_W) / 2;
                this.topPos = (this.height - PANEL_H) / 2;
        }

        /** Maps a screen-space point to the hovered interactive slot. */
        private int slotAt(double mouseX, double mouseY) {
                int mx = (int) mouseX - leftPos;
                int my = (int) mouseY - topPos;
                // main 27 (interactive range 0..26)
                int col = (mx - MAIN_X) / 18;
                int row = (my - MAIN_Y) / 18;
                if (col >= 0 && col < 9 && row >= 0 && row < 3
                                && cellContains(mx, my, MAIN_X + col * 18, MAIN_Y + row * 18)) {
                        return row * 9 + col;
                }
                // hotbar 9 (interactive range 27..35)
                if (col >= 0 && col < 9
                                && cellContains(mx, my, MAIN_X + col * 18, HOTBAR_Y)) {
                        return 27 + col;
                }
                return -1;
        }

        /** 18x18 cell hit test (pixel-exact, not the integer-division box). */
        private static boolean cellContains(int mx, int my, int cellX, int cellY) {
                return mx >= cellX && mx < cellX + 18 && my >= cellY && my < cellY + 18;
        }

        /** Interactive slot (0..35) -> Inventory slot index. */
        private static int inventorySlot(int interactive) {
                // interactive 0..26 = main inventory (Inventory 9..35)
                // interactive 27..35 = hotbar (Inventory 0..8)
                return interactive < 27 ? interactive + 9 : interactive - 27;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // vanilla dims the world behind container screens
                // (GuiGraphics.renderDimmedBackground)
                EaglerGuiImpl.fillRect(0, 0, this.width, this.height, 0xB0101010);

                EaglerGuiImpl.drawSprite("container/inventory", leftPos, topPos,
                                PANEL_W, PANEL_H);

                // vanilla labels
                EaglerGuiImpl.drawText(LABEL_CRAFTING, leftPos + CRAFT_X,
                                topPos + CRAFT_Y - 4, 0xFF3F3F3F, false);
                EaglerGuiImpl.drawText(LABEL_INVENTORY, leftPos + MAIN_X,
                                topPos + MAIN_Y - 10, 0xFF3F3F3F, false);

                Inventory inventory = this.minecraft.getInventory();

                // hovered-slot highlight (vanilla renders a translucent white
                // overlay under the cursor item)
                if (hoveredSlot >= 0) {
                        int hx;
                        int hy;
                        if (hoveredSlot < 27) {
                                hx = leftPos + MAIN_X + (hoveredSlot % 9) * 18;
                                hy = topPos + MAIN_Y + (hoveredSlot / 9) * 18;
                        } else {
                                hx = leftPos + MAIN_X + (hoveredSlot - 27) * 18;
                                hy = topPos + HOTBAR_Y;
                        }
                        EaglerGuiImpl.fillRect(hx, hy, 16, 16, 0x80FFFFFF);
                }

                // main 27 + hotbar 9 (items over the highlight)
                for (int i = 0; i < 27; i++) {
                        Gui.drawItem(inventory.getBlockId(inventorySlot(i)),
                                        inventory.getCount(inventorySlot(i)),
                                        leftPos + MAIN_X + (i % 9) * 18 + 1,
                                        topPos + MAIN_Y + (i / 9) * 18 + 1);
                }
                for (int i = 0; i < 9; i++) {
                        Gui.drawItem(inventory.getBlockId(i), inventory.getCount(i),
                                        leftPos + MAIN_X + i * 18 + 1, topPos + HOTBAR_Y + 1);
                }

                // carried stack follows the cursor
                Gui.drawItem(inventory.getCarriedBlock(), inventory.getCarriedCount(),
                                mouseX - 1, mouseY - 1);

                // tooltip for the hovered slot
                if (hoveredSlot >= 0 && inventory.getCarriedCount() == 0) {
                        int inv = inventorySlot(hoveredSlot);
                        String name = Gui.blockName(inventory.getBlockId(inv));
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
                        if (PlatformInput.isKeyDown(KeyMapping.SCAN_LSHIFT)) {
                                inventory.quickMove(inventorySlot(slot));
                        } else {
                                inventory.clickSlot(inventorySlot(slot), button);
                        }
                        ClientMain.log("[InventoryScreen] slot " + slot + " button "
                                        + button);
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
                        ClientMain.log("[InventoryScreen] closed with key " + keyCode);
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
