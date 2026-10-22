package net.minecraft.client;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.MouseHandler.
 *
 * <p>DOM mouse events are captured by {@link PlatformInput}, which reports
 * positions in canvas CSS pixels. The GUI works in GUI-scaled pixels
 * (canvas backing pixels divided by the GUI scale), so this handler owns
 * that conversion for every route into screens and widgets:
 * {@code gui = css * (backing / css) / guiScale}.</p>
 *
 * <p>Event flow (wired by EaglerCraft.processInput each frame):</p>
 * <pre>
 *   DOM mousemove/mousedown -> PlatformInput state -> MouseHandler
 *     -> Screen.mouseMoved / Screen.mouseClicked (GUI pixels)
 * </pre>
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
         * Called when the mouse moves. Coordinates are canvas CSS pixels
         * (as reported by PlatformInput); stored as GUI pixels.
         */
        public void handleMouseMove(long window, double xpos, double ypos) {
                this.xpos = toGuiX(xpos);
                this.ypos = toGuiY(ypos);

                // Hover tracking for the current screen's widgets
                if (minecraft.screen != null) {
                        minecraft.screen.mouseMoved(this.xpos, this.ypos);
                }
        }

        /**
         * Called when a mouse button is pressed or released.
         * GLFW conventions: action 1 = press, 0 = release.
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
         * The browser only grants pointer lock in response to a user
         * gesture, so EaglerCraft calls this on a real in-world canvas
         * click.
         */
        public void grabMouse() {
                PlatformInput.requestPointerLock();
                this.mouseGrabbed = PlatformInput.isPointerLocked();
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
         * Called every tick. Refreshes the position from PlatformInput
         * (CSS pixels) and syncs the grab flag with the browser state,
         * since the user can exit pointer lock with Escape at any time.
         */
        public void tick() {
                this.xpos = toGuiX(PlatformInput.getMouseX());
                this.ypos = toGuiY(PlatformInput.getMouseY());
                this.mouseGrabbed = PlatformInput.isPointerLocked();
        }

        /**
         * Returns the current mouse X position (GUI pixels).
         */
        public double xpos() {
                return xpos;
        }

        /**
         * Returns the current mouse Y position (GUI pixels).
         */
        public double ypos() {
                return ypos;
        }

        /**
         * Sets the mouse position (GUI pixels).
         */
        public void setMousePosition(double x, double y) {
                this.xpos = x;
                this.ypos = y;
        }

        // ========== Unit conversion ==========

        /**
         * Converts a canvas CSS-pixel X to GUI-scaled pixels. The ratio
         * comes from the Window itself so it always matches the units the
         * renderer uses for {@code getGuiScaledWidth()}.
         */
        private double toGuiX(double cssX) {
                if (minecraft == null || minecraft.getWindow() == null) {
                        return cssX;
                }
                return cssX * backingPerCss() / minecraft.getWindow().getGuiScale();
        }

        /**
         * Converts a canvas CSS-pixel Y to GUI-scaled pixels.
         */
        private double toGuiY(double cssY) {
                if (minecraft == null || minecraft.getWindow() == null) {
                        return cssY;
                }
                return cssY * backingPerCss() / minecraft.getWindow().getGuiScale();
        }

        /**
         * Backing-store pixels per CSS pixel (device pixel ratio), as
         * tracked by the Window.
         */
        private double backingPerCss() {
                int cssWidth = minecraft.getWindow().getScreenWidth();
                if (cssWidth <= 0) {
                        return 1.0D;
                }
                return (double) minecraft.getWindow().getWidth() / (double) cssWidth;
        }
}
