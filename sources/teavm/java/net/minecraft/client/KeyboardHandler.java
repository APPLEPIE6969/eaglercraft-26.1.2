package net.minecraft.client;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.KeyboardHandler.
 * Handles keyboard input by delegating to PlatformInput's event system.
 * In the browser, key events come from DOM KeyboardEvents, which
 * PlatformInput captures. This class provides MC's key handler interface
 * and routes events to the current screen.
 */
public class KeyboardHandler {

        private final Minecraft minecraft;

        public KeyboardHandler(Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        /**
         * Called when a key is pressed.
         * Routes the event to the current screen or the game's key binding system.
         */
        public void keyPress(long window, int key, int scanCode, int action, int modifiers) {
                if (action == 1) {
                        // Key pressed
                        keyDown(key, scanCode, modifiers);
                } else if (action == 0) {
                        // Key released
                        keyUp(key, scanCode, modifiers);
                }
        }

        /**
         * Called when a character is typed (text input).
         */
        public void charTyped(long window, int codePoint, int modifiers) {
                char c = (char) codePoint;
                if (minecraft.screen != null) {
                        minecraft.screen.charTyped(c, modifiers);
                }
        }

        /**
         * Handles a key press event.
         */
        private void keyDown(int key, int scanCode, int modifiers) {
                // First, try to route to the current screen
                if (minecraft.screen != null) {
                        if (minecraft.screen.keyPressed(key, scanCode, modifiers)) {
                                return;
                        }
                }

                // Vanilla Minecraft.pauseGame: ESC while in a world with no
                // screen open opens the pause menu (the in-world game menu).
                if (key == 1 && minecraft.screen == null && minecraft.isLevelLoaded()) {
                        minecraft.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                        return;
                }

                // Vanilla Minecraft.handleKeybinds (in-world, no screen):
                // digits 1-9 select the hotbar slot (vanilla key.hotbar.N),
                // inventory reserved, drop consumes a click. The binds are
                // resolved through the KeyMapping table (defaults = the
                // scan codes 2..10 / 18 / 16).
                if (minecraft.screen == null && minecraft.isLevelLoaded()) {
                        int[] hotbarKeys = {
                                        KeyMapping.HOTBAR_1.getKey(), KeyMapping.HOTBAR_2.getKey(),
                                        KeyMapping.HOTBAR_3.getKey(), KeyMapping.HOTBAR_4.getKey(),
                                        KeyMapping.HOTBAR_5.getKey(), KeyMapping.HOTBAR_6.getKey(),
                                        KeyMapping.HOTBAR_7.getKey(), KeyMapping.HOTBAR_8.getKey(),
                                        KeyMapping.HOTBAR_9.getKey() };
                        for (int slot = 0; slot < hotbarKeys.length; slot++) {
                                if (hotbarKeys[slot] != 0 && key == hotbarKeys[slot]) {
                                        minecraft.getInventory().setSelected(slot);
                                        minecraft.playerSelectedSlotChanged();
                                        return;
                                }
                        }
                        if (key == KeyMapping.INVENTORY.getKey()) {
                                // vanilla key.inventory opens the inventory screen
                                // (no inventory UI yet - reserved)
                                return;
                        }
                }
        }

        /**
         * Handles a key release event.
         */
        private void keyUp(int key, int scanCode, int modifiers) {
                if (minecraft.screen != null) {
                        minecraft.screen.keyReleased(key, scanCode, modifiers);
                }
        }

        /**
         * Called every tick.
         */
        public void tick() {
                // Process queued key events, if any
                // PlatformInput handles polling; this is for MC-level key binding processing
        }

        /**
         * Checks if a key is currently down.
         */
        public boolean isKeyDown(int key) {
                return PlatformInput.isKeyDown(key);
        }
}
