package net.minecraft.client;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.MouseHandler.
 * Handles mouse input by delegating to PlatformInput's event system.
 * In the browser, mouse events come from DOM MouseEvents, which
 * PlatformInput captures. This class provides MC's mouse handler interface
 * and routes events to the current screen.
 */
public class MouseHandler {

        private final Minecraft minecraft;
        private double xpos;
        private double ypos;
        private boolean mouseGrabbed;

        public MouseHandler(Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        /**
         * Called when the mouse moves.
         */
        public void handleMouseMove(long window, double xpos, double ypos) {
                this.xpos = xpos;
                this.ypos = ypos;

                // Route to current screen
                if (minecraft.screen != null) {
                        minecraft.screen.mouseDragged(xpos, ypos, 0, xpos, ypos);
                }
        }

        /**
         * Called when a mouse button is pressed or released.
         */
        public void cursorPressed(long window, int button, int action, int modifiers) {
                double mx = this.xpos;
                double my = this.ypos;

                if (action == 1) {
                        // Button pressed
                        if (minecraft.screen != null) {
                                minecraft.screen.mouseClicked(mx, my, button);
                        }
                } else if (action == 0) {
                        // Button released
                        if (minecraft.screen != null) {
                                minecraft.screen.mouseReleased(mx, my, button);
                        }
                }
        }

        /**
         * Called when the mouse is scrolled.
         */
        public void onScroll(long window, double xOffset, double yOffset) {
                if (minecraft.screen != null) {
                        minecraft.screen.mouseScrolled(this.xpos, this.ypos, yOffset);
                }
        }

        /**
         * Grabs the mouse cursor (enables pointer lock for gameplay).
         */
        public void grabMouse() {
                PlatformInput.requestPointerLock();
                this.mouseGrabbed = true;
        }

        /**
         * Releases the mouse cursor (disables pointer lock).
         */
        public void releaseMouse() {
                PlatformInput.exitPointerLock();
                this.mouseGrabbed = false;
        }

        /**
         * Returns whether the mouse is currently grabbed (pointer lock active).
         */
        public boolean isMouseGrabbed() {
                return this.mouseGrabbed;
        }

        /**
         * Called every tick.
         */
        public void tick() {
                // Update mouse position from PlatformInput
                this.xpos = PlatformInput.getMouseX();
                this.ypos = PlatformInput.getMouseY();
        }

        /**
         * Returns the current mouse X position.
         */
        public double xpos() {
                return xpos;
        }

        /**
         * Returns the current mouse Y position.
         */
        public double ypos() {
                return ypos;
        }

        /**
         * Sets the mouse position.
         */
        public void setMousePosition(double x, double y) {
                this.xpos = x;
                this.ypos = y;
        }
}
