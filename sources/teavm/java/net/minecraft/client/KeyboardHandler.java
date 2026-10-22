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

                // Then handle game key bindings
                // TODO: Check key bindings and dispatch to MC's input system
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
