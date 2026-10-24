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

        /** Vanilla Gui's held-item name popup timer (40 ticks). */
        private int missTime = 0;

        /** Hotbar geometry, vanilla units in GUI pixels. */
        private static final int HOTBAR_WIDTH = 182;
        private static final int HOTBAR_HEIGHT = 22;
        private static final int SLOT_SIZE = 20;

        /** Vanilla crosshair sprite size (GUI pixels). */
        private static final int CROSSHAIR_SIZE = 15;

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
         * The center crosshair: vanilla blits the hud/crosshair sprite
         * (Mojang's own pixels) at ((guiWidth - 15) / 2, (guiHeight - 15) / 2),
         * so the box origin - not the screen midpoint - defines the center.
         * Falls back to the procedural 1px-arm plus while the sprite PNGs
         * are still downloading.
         */
        private void renderCrosshair(int guiWidth, int guiHeight) {
                int x0 = (guiWidth - CROSSHAIR_SIZE) / 2;
                int y0 = (guiHeight - CROSSHAIR_SIZE) / 2;
                if (EaglerGuiImpl.drawSprite("hud/crosshair", x0, y0, CROSSHAIR_SIZE, CROSSHAIR_SIZE)) {
                        return;
                }
                int cx = x0 + CROSSHAIR_SIZE / 2;
                int cy = y0 + CROSSHAIR_SIZE / 2;

                EaglerGuiImpl.fillRect(x0 + 1, cy + 1, CROSSHAIR_SIZE, 1, COLOR_CROSSHAIR_SHADOW);
                EaglerGuiImpl.fillRect(cx + 1, y0 + 1, 1, CROSSHAIR_SIZE, COLOR_CROSSHAIR_SHADOW);
                EaglerGuiImpl.fillRect(x0, cy, CROSSHAIR_SIZE, 1, COLOR_CROSSHAIR);
                EaglerGuiImpl.fillRect(cx, y0, 1, CROSSHAIR_SIZE, COLOR_CROSSHAIR);
        }

        /**
         * The bottom-center hotbar: vanilla blits the hud/hotbar sprite
         * (182x22 of Mojang's own pixels) at (w/2-91, h-22) with the 24x23
         * hud/hotbar_selection sprite one pixel up/left of the selected
         * slot, stepping 20px per slot. Falls back to the procedural
         * panel while the sprite PNGs are still downloading.
         *
         * <p>Slot contents: the inventory's block stacks render as 16x16
         * icons (the vanilla block texture, drawn flat) centered in each
         * slot + the stack count bottom-right (vanilla's count text at
         * y+9 of the slot top-left corner... slot-relative 16x16 icon at
         * +3,+3 and count at +17). The held item's name pops above the
         * hotbar on change (vanilla Gui's item name display).</p>
         */
        private void renderHotbar(int guiWidth, int guiHeight) {
                int x0 = (guiWidth - HOTBAR_WIDTH) / 2;
                int y0 = guiHeight - HOTBAR_HEIGHT;

                boolean spriteDrawn = EaglerGuiImpl.drawSprite("hud/hotbar", x0, y0, HOTBAR_WIDTH, HOTBAR_HEIGHT);
                if (!spriteDrawn) {
                        // Panel background + border
                        EaglerGuiImpl.fillRect(x0 - 1, y0 - 1, HOTBAR_WIDTH + 2, HOTBAR_HEIGHT + 2, COLOR_HOTBAR_BORDER);
                        EaglerGuiImpl.fillRect(x0, y0, HOTBAR_WIDTH, HOTBAR_HEIGHT, COLOR_HOTBAR_BG);

                        // Slot separators
                        for (int slot = 1; slot < 9; slot++) {
                                EaglerGuiImpl.fillRect(x0 + slot * SLOT_SIZE + 1, y0, 1, HOTBAR_HEIGHT, COLOR_SLOT_LINE);
                        }
                        EaglerGuiImpl.fillRect(x0, y0, HOTBAR_WIDTH, 1, COLOR_SLOT_LINE);
                        EaglerGuiImpl.fillRect(x0, y0 + HOTBAR_HEIGHT - 1, HOTBAR_WIDTH, 1, COLOR_SLOT_LINE);
                }

                // Selection frame: vanilla blits the 24x23 hud/hotbar_selection
                // sprite one pixel up/left of the hotbar origin, stepping 20px
                // per slot.
                int selX = x0 + selectedHotbarSlot * SLOT_SIZE - 1;
                int selY = y0 - 1;
                if (!EaglerGuiImpl.drawSprite("hud/hotbar_selection", selX, selY, 24, 23)) {
                        for (int i = 0; i < 24; i++) {
                                EaglerGuiImpl.fillRect(selX + i, selY, 1, 1, COLOR_SELECTOR);
                                EaglerGuiImpl.fillRect(selX + i, selY + 22, 1, 1, COLOR_SELECTOR);
                        }
                        for (int i = 1; i < 22; i++) {
                                EaglerGuiImpl.fillRect(selX, selY + i, 1, 1, COLOR_SELECTOR);
                                EaglerGuiImpl.fillRect(selX + 23, selY + i, 1, 1, COLOR_SELECTOR);
                        }
                }

                // Slot contents: icons + counts (vanilla inventory rendering)
                net.minecraft.world.entity.player.Inventory inventory = minecraft.getInventory();
                if (inventory != null) {
                        for (int slot = 0; slot < 9; slot++) {
                                int blockId = inventory.getBlockId(slot);
                                int count = inventory.getCount(slot);
                                if (blockId == net.minecraft.world.level.block.Blocks.AIR || count <= 0) {
                                        continue;
                                }
                                String tex = iconTexture(blockId);
                                int sx = x0 + slot * SLOT_SIZE + 3;
                                int sy = y0 + 3;
                                if (tex != null) {
                                        EaglerGuiImpl.drawSprite(tex, sx, sy, 16, 16);
                                }
                                if (count > 1) {
                                        String label = String.valueOf(count);
                                        float width = EaglerGuiImpl.widthOf(label);
                                        EaglerGuiImpl.drawText(label, sx + 16 - width, sy + 8,
                                                        0xFFFFFFFF, true);
                                }
                        }

                        // Vanilla Gui's held-item name popup: shows the name
                        // above the hotbar for 40 ticks after a slot change
                        if (missTime > 0) {
                                int heldId = inventory.getSelectedBlock();
                                if (heldId != 0) {
                                        String name = blockName(heldId);
                                        if (name != null) {
                                                float width = EaglerGuiImpl.widthOf(name);
                                                EaglerGuiImpl.drawText(name, (guiWidth - width) / 2.0f,
                                                                y0 - 14, 0xFFFFFFFF, true);
                                        }
                                }
                        }
                }

                // Debug line: world identity + clock, bottom-left above the hotbar
                if (minecraft.getLevel() instanceof ClientLevel level) {
                        String label = level.getLevelName() + " | " + level.getDimensionId()
                                + " | tick " + level.getGameTime();
                        EaglerGuiImpl.drawText(label, 2.0f, y0 - 10, 0xFFE0E0E0, true);
                }
        }

        /** Gui sprite path for a block's flat inventory icon. */
        private static String iconTexture(int blockId) {
                net.minecraft.world.level.block.Blocks.Block block =
                        net.minecraft.world.level.block.Blocks.byId(blockId);
                if (block.topTexture == null) {
                        return null;
                }
                // the side texture is the icon for grass (vanilla uses the
                // item model; the side face is the recognizable one)
                return "block/" + block.sideTexture;
        }

        /** The display name of a block (vanilla Component.translatable
         * "block.minecraft.<name>", rendered from the registry's
         * vanilla en_us.json display name). */
        public static String blockName(int blockId) {
                net.minecraft.world.level.block.Blocks.Block block =
                        net.minecraft.world.level.block.Blocks.byId(blockId);
                return block.displayName == null ? null : block.displayName;
        }

        /**
         * Vanilla slot-item rendering shared by the hotbar and the
         * inventory screens: a 16x16 flat block icon at the slot's
         * +3,+3 inner corner (hotbar) or +1,+1 (container slots).
         */
        public static void drawItemIcon(int blockId, float x, float y) {
                String tex = iconTexture(blockId);
                if (tex != null) {
                        EaglerGuiImpl.drawSprite(tex, x, y, 16, 16);
                }
        }

        /** The stack-count text, bottom-right of a 16x16 icon slot. */
        public static void drawItemCount(int count, float x, float y) {
                if (count > 1) {
                        String label = String.valueOf(count);
                        float width = EaglerGuiImpl.widthOf(label);
                        EaglerGuiImpl.drawText(label, x + 17 - width, y + 9,
                                        0xFFFFFFFF, true);
                }
        }

        /** A full item rendering pass (icon + count). */
        public static void drawItem(int blockId, int count, float x, float y) {
                if (blockId == net.minecraft.world.level.block.Blocks.AIR || count <= 0) {
                        return;
                }
                drawItemIcon(blockId, x, y);
                drawItemCount(count, x, y);
        }

        /** Vanilla tooltip background (GuiGraphics.renderTooltip). */
        public static void drawTooltip(String text, float mouseX, float mouseY,
                        float screenW, float screenH) {
                float width = EaglerGuiImpl.widthOf(text);
                float x = mouseX + 12;
                float y = mouseY - 12;
                if (x + width + 6 > screenW) {
                        x = mouseX - width - 18;
                }
                if (y < 4) {
                        y = 4;
                }
                EaglerGuiImpl.fillRect(x - 3, y - 3, width + 6, 12, 0xF0100010);
                EaglerGuiImpl.fillRect(x - 4, y - 4, width + 8, 1, 0xFF50509E);
                EaglerGuiImpl.fillRect(x - 4, y + 9, width + 8, 1, 0xFF50509E);
                EaglerGuiImpl.fillRect(x - 4, y - 3, 1, 12, 0xFF50509E);
                EaglerGuiImpl.fillRect(x + width + 3, y - 3, 1, 12, 0xFF50509E);
                EaglerGuiImpl.drawText(text, x, y, 0xFFFFFFFF, false);
        }

        /**
         * Called every tick.
         */
        public void tick() {
                if (missTime > 0) {
                        missTime--;
                }
        }

        /** The held-item name popup (vanilla Gui.missTime trigger). */
        public void setMissTime(int ticks) {
                this.missTime = ticks;
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
