package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * EaglerCraft 26.1.2 browser override for AbstractWidget, the base class
 * of all interactive GUI elements (buttons, sliders, text fields).
 *
 * <p>Widgets live in GUI-scaled pixel space with the origin at the top
 * left. Hit-testing is a plain rectangle intersection with the mouse
 * position the screen passes down, so clicks only register inside the
 * widget's bounds.</p>
 */
public abstract class AbstractWidget {

        protected int x;
        protected int y;
        protected int width;
        protected int height;
        protected boolean visible = true;
        protected boolean active = true;
        protected boolean hovered = false;

        protected AbstractWidget(int x, int y, int width, int height) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
        }

        /**
         * Renders the widget. {@code mouseX} / {@code mouseY} are in GUI
         * pixels and are already known to be inside the screen bounds.
         */
        public abstract void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick);

        /**
         * Returns whether the click at the given GUI position hits this
         * widget. Pressing left mouse button (button 0) inside the bounds
         * activates the widget.
         */
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!visible || !active || button != 0) {
                        return false;
                }
                if (isInside(mouseX, mouseY)) {
                        onClick();
                        return true;
                }
                return false;
        }

        /**
         * Called when a mouse button is released. Widgets that track
         * press state override this.
         */
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
                return visible && active && isInside(mouseX, mouseY);
        }

        /**
         * Called whenever the mouse moves while the widget's screen is up.
         * The base implementation just refreshes the hover state.
         */
        public void mouseMoved(double mouseX, double mouseY) {
                this.hovered = visible && isInside(mouseX, mouseY);
        }

        /**
         * Returns whether the given GUI position is inside the widget.
         */
        public boolean isInside(double mouseX, double mouseY) {
                return mouseX >= (double) x && mouseX < (double) (x + width)
                        && mouseY >= (double) y && mouseY < (double) (y + height);
        }

        /**
         * The widget's activation behavior. Subclasses override to react
         * to a left-click inside their bounds.
         */
        protected void onClick() {
        }

        /**
         * The stable id this widget reports to the e2e telemetry bridge
         * (docs/E2E.md), or null when it should stay out of the bridge's
         * buttons array. Buttons derive theirs from the message; custom
         * widgets like the world-list entry override this.
         */
        public String getTelemetryId() {
                return null;
        }

        // ========== Accessors ==========

        public int getX() {
                return x;
        }

        public int getY() {
                return y;
        }

        public int getWidth() {
                return width;
        }

        public int getHeight() {
                return height;
        }

        public void setPosition(int x, int y) {
                this.x = x;
                this.y = y;
        }

        public boolean isVisible() {
                return visible;
        }

        public void setVisible(boolean visible) {
                this.visible = visible;
        }

        public boolean isActive() {
                return active;
        }

        public void setActive(boolean active) {
                this.active = active;
        }

        public boolean isHovered() {
                return hovered;
        }
}
