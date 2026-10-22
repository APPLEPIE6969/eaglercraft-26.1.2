package net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.Gui.
 * HUD overlay: renders the in-game overlay elements on top of the world.
 *
 * <p>Currently draws the two elements every vanilla HUD has in survival
 * view: the center crosshair and the bottom hotbar (nine slots with a
 * selection frame). Text uses the 5x7 bitmap font and everything goes
 * through {@link EaglerGuiImpl}'s batched quad pipeline, so the whole
 * HUD is a handful of draw calls per frame.</p>
 */
public class Gui {

        private final Minecraft minecraft;
        private Object chatComponent; // ChatComponent stub

        /** Currently selected hotbar slot (0-8). */
        private int selectedHotbarSlot = 0;

        /** Hotbar geometry, vanilla units in GUI pixels. */
        private static final int HOTBAR_WIDTH = 182;
        private static final int HOTBAR_HEIGHT = 22;
        private static final int SLOT_SIZE = 20;

        private static final int COLOR_HOTBAR_BG = 0xB4141414;
        private static final int COLOR_HOTBAR_BORDER = 0xFF545454;
        private static final int COLOR_SLOT_LINE = 0xFF8B8B8B;
        private static final int COLOR_SELECTOR = 0xFFF0F0F0;
        private static final int COLOR_CROSSHAIR = 0xE6FFFFFF;
        private static final int COLOR_CROSSHAIR_SHADOW = 0x59000000;

        public Gui(Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        /**
         * Renders the HUD overlay. No-op unless the client is inside a
         * loaded world ({@code Minecraft.isLevelLoaded()}) - the HUD has
         * nothing to show on menus.
         */
        public void render(PoseStack poseStack, float partialTick) {
                if (minecraft == null || !minecraft.isLevelLoaded()) {
                        return;
                }

                com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
                if (window == null) {
                        return;
                }
                int guiWidth = window.getGuiScaledWidth();
                int guiHeight = window.getGuiScaledHeight();
                if (guiWidth <= 0 || guiHeight <= 0) {
                        return;
                }

                renderCrosshair(guiWidth, guiHeight);
                renderHotbar(guiWidth, guiHeight);
        }

        /**
         * The center crosshair: two 15px bars (1px thick) with a subtle
         * drop shadow so they stay visible against the sky.
         */
        private void renderCrosshair(int guiWidth, int guiHeight) {
                int cx = guiWidth / 2;
                int cy = guiHeight / 2;
                int half = 7; // 15px bar centered on the middle pixel

                EaglerGuiImpl.fillRect(cx - half + 1, cy + 1, half * 2 + 1, 1, COLOR_CROSSHAIR_SHADOW);
                EaglerGuiImpl.fillRect(cx + 1, cy - half + 1, 1, half * 2 + 1, COLOR_CROSSHAIR_SHADOW);
                EaglerGuiImpl.fillRect(cx - half, cy, half * 2 + 1, 1, COLOR_CROSSHAIR);
                EaglerGuiImpl.fillRect(cx, cy - half, 1, half * 2 + 1, COLOR_CROSSHAIR);
        }

        /**
         * The bottom-center hotbar: 9 slots in a bordered panel with the
         * vanilla 1px overlap selection frame around the selected slot.
         */
        private void renderHotbar(int guiWidth, int guiHeight) {
                int x0 = (guiWidth - HOTBAR_WIDTH) / 2;
                int y0 = guiHeight - HOTBAR_HEIGHT;

                // Panel background + border
                EaglerGuiImpl.fillRect(x0 - 1, y0 - 1, HOTBAR_WIDTH + 2, HOTBAR_HEIGHT + 2, COLOR_HOTBAR_BORDER);
                EaglerGuiImpl.fillRect(x0, y0, HOTBAR_WIDTH, HOTBAR_HEIGHT, COLOR_HOTBAR_BG);

                // Slot separators
                for (int slot = 1; slot < 9; slot++) {
                        EaglerGuiImpl.fillRect(x0 + slot * SLOT_SIZE + 1, y0, 1, HOTBAR_HEIGHT, COLOR_SLOT_LINE);
                }
                EaglerGuiImpl.fillRect(x0, y0, HOTBAR_WIDTH, 1, COLOR_SLOT_LINE);
                EaglerGuiImpl.fillRect(x0, y0 + HOTBAR_HEIGHT - 1, HOTBAR_WIDTH, 1, COLOR_SLOT_LINE);

                // Selection frame: 24x24 vanilla selector, 2px inset overlap
                int selX = x0 + selectedHotbarSlot * SLOT_SIZE - 2;
                int selY = y0 - 2;
                for (int i = 0; i < 24; i++) {
                        EaglerGuiImpl.fillRect(selX + i, selY, 1, 1, COLOR_SELECTOR);
                        EaglerGuiImpl.fillRect(selX + i, selY + 23, 1, 1, COLOR_SELECTOR);
                }
                for (int i = 1; i < 23; i++) {
                        EaglerGuiImpl.fillRect(selX, selY + i, 1, 1, COLOR_SELECTOR);
                        EaglerGuiImpl.fillRect(selX + 23, selY + i, 1, 1, COLOR_SELECTOR);
                }

                // Debug line: world identity + clock, bottom-left above the hotbar
                if (minecraft.getLevel() instanceof ClientLevel level) {
                        String label = level.getLevelName() + " | " + level.getDimensionId()
                                + " | tick " + level.getGameTime();
                        EaglerGuiImpl.drawText(label, 2.0f, y0 - 10, 0xFFE0E0E0, true);
                }
        }

        /**
         * Called every tick.
         */
        public void tick() {
                // no-op (animation state lands here later)
        }

        /**
         * Returns the chat component.
         */
        public Object getChat() {
                return chatComponent;
        }

        /**
         * Clears the chat.
         */
        public void clearChat() {
                // no-op
        }

        /**
         * Returns whether the GUI is hidden (F1).
         */
        public boolean isHidden() {
                return false;
        }

        /**
         * Returns the selected hotbar slot index.
         */
        public int getSelectedHotbarSlot() {
                return selectedHotbarSlot;
        }

        /**
         * Sets the selected hotbar slot index (clamped to 0-8).
         */
        public void setSelectedHotbarSlot(int slot) {
                this.selectedHotbarSlot = Math.max(0, Math.min(8, slot));
        }
}
