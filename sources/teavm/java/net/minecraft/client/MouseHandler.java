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

        /** Pointer-lock deltas accumulated since the last frame (GUI px). */
        private double accumulatedDX;
        private double accumulatedDY;

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
         *
         * <p>With the pointer grabbed (in-world gameplay) the press routes
         * to the vanilla gameplay actions instead of the GUI: button 0 =
         * attack (start destroying), button 1 = use/place, button 2 =
         * pick block - exactly vanilla MouseHandler.onPress's grabbed
         * branch.</p>
         */
        public void cursorPressed(long window, int button, int action, int modifiers) {
                double mx = this.xpos;
                double my = this.ypos;

                // DOM MouseEvent.button -> vanilla GLFW numbering: DOM
                // 0=left, 1=middle, 2=right; GLFW 0=left, 1=right, 2=middle.
                // The gameplay branch below uses vanilla semantics (1 =
                // use/place, 2 = pick block); the GUI path only checks
                // button 0, which is identical in both conventions.
                if (button == 2) {
                        button = 1;
                } else if (button == 1) {
                        button = 2;
                }

                // Vanilla MouseHandler.onPress: clicking the canvas while
                // in-world with the cursor free re-grabs the pointer (the
                // first click is the grab, gameplay clicks come after)
                if (action == 1 && !this.mouseGrabbed
                                && minecraft.screen == null && minecraft.isLevelLoaded()) {
                        this.grabMouse();
                        return;
                }

                if (this.mouseGrabbed && minecraft.screen == null && minecraft.isLevelLoaded()) {
                        if (action == 1) {
                                if (button == 0) {
                                        minecraft.startAttack();
                                } else if (button == 1) {
                                        minecraft.startUseItem();
                                } else if (button == 2) {
                                        minecraft.pickBlock();
                                }
                        } else if (action == 0) {
                                if (button == 0) {
                                        minecraft.stopAttack();
                                }
                        }
                        return;
                }

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
         *
         * <p>Grabbed (in-world): vanilla's Inventory.swapPaint - the hotbar
         * selection wraps through the nine slots. Unlocked: the current
         * screen gets the event.</p>
         */
        public void onScroll(long window, double xOffset, double yOffset) {
                if (this.mouseGrabbed && minecraft.screen == null && minecraft.isLevelLoaded()) {
                        int direction = yOffset < 0.0D ? -1 : 1;
                        int current = minecraft.getGui().getSelectedHotbarSlot();
                        int next = ((current + direction) % 9 + 9) % 9;
                        minecraft.getGui().setSelectedHotbarSlot(next);
                        minecraft.playerSelectedSlotChanged();
                        return;
                }
                if (minecraft.screen != null) {
                        minecraft.screen.mouseScrolled(this.xpos, this.ypos, yOffset);
                }
        }

        /**
         * Per-frame mouse update - vanilla {@code xhandleMouseMove}. When
         * the pointer is grabbed, the accumulated pointer-lock deltas are
         * converted to look rotation with vanilla's exact sensitivity
         * curve: {@code sens = (sensitivity * 0.6 + 0.2)^3 * 8.0} applied

         * to GUI-scaled pixel deltas, then {@code player.turn(xo, yo)}.
         */
        public void xhandleMouseMove() {
                if (this.isMouseGrabbed() && minecraft.isLevelLoaded()) {
                        double rawDX = PlatformInput.getMouseDX();
                        double rawDY = PlatformInput.getMouseDY();
                        if (rawDX != 0.0D || rawDY != 0.0D) {
                                this.accumulatedDX += toGuiX(rawDX);
                                this.accumulatedDY += toGuiY(rawDY);
                        }
                        this.turnPlayer();
                } else {
                        // Deltas are meaningless unlocked; keep the buffers clear
                        PlatformInput.getMouseDX();
                        PlatformInput.getMouseDY();
                        this.accumulatedDX = 0.0D;
                        this.accumulatedDY = 0.0D;
                }
        }

        /**
         * Vanilla turnPlayer: applies the accumulated deltas as camera
         * rotation. The default sensitivity option (0.5) gives the
         * familiar default turn rate; FOV-scale is NOT applied (vanilla
         * only scales by the GUI pixel ratio, done above).
         */
        private void turnPlayer() {
                double ss = minecraft.getOptions().sensitivity * 0.6F + 0.2F;
                double sensitivityMod = ss * ss * ss;
                double sens = sensitivityMod * 8.0;
                double xo = this.accumulatedDX * sens;
                double yo = this.accumulatedDY * sens;
                this.accumulatedDX = 0.0D;
                this.accumulatedDY = 0.0D;
                // Vanilla invertY option (MouseSettingsScreen): flips the
                // vertical look axis
                if (minecraft.getOptions().invertMouseY) {
                        yo = -yo;
                }
                if (minecraft.getPlayer() != null) {
                        minecraft.getPlayer().turn(xo, yo);
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

        /** Vanilla isLeftPressed: the attack button's held state (drives
         * the destroy continuation while grabbed). */
        public boolean isLeftPressed() {
                return this.mouseGrabbed && PlatformInput.isMouseDown(0);
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
         * Backing-store pixels per CSS pixel (device pixel ratio).
         *
         * <p>Window.screenWidth/Height are PHYSICAL pixels since the GUI-scale
         * convention unification (the vanilla auto-scale rule runs on device
         * pixels, like desktop MC on a HiDPI monitor), so the CSS width comes
         * straight from the platform canvas cache instead.</p>
         */
        private double backingPerCss() {
                int cssWidth = net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime.getCanvasWidth();
                if (cssWidth <= 0) {
                        return 1.0D;
                }
                return (double) minecraft.getWindow().getWidth() / (double) cssWidth;
        }
}
