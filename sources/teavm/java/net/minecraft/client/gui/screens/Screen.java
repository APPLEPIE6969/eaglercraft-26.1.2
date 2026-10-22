package net.minecraft.client.gui.screens;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.screens.Screen.
 * Base class for all MC screens (menus, GUIs, etc.).
 * Provides minimal structure for TeaVM compilation.
 */
public class Screen {

        /** The Minecraft instance that owns this screen. */
        protected Minecraft minecraft;

        /** The font renderer for this screen. */
        protected Font font;

        /** Screen width in pixels. */
        protected int width;

        /** Screen height in pixels. */
        protected int height;

        /**
         * The widgets on this screen, in insertion order. Populated by
         * {@link #init()} (the per-screen initializer) which runs on
         * every {@link #init(Minecraft, int, int)} / resize.
         */
        protected final List<AbstractWidget> widgets = new ArrayList<AbstractWidget>();

        public Screen() {
        }

        /**
         * Called when this screen is initialized or resized. Rebuilds the
         * widget list from scratch (a resize re-runs the vanilla init
         * cycle), then delegates to {@link #init()} for the subclass to
         * add its widgets.
         */
        public void init(Minecraft minecraft, int width, int height) {
                this.minecraft = minecraft;
                this.font = minecraft != null ? minecraft.font : new Font();
                this.width = width;
                this.height = height;
                this.clearWidgets();
                this.init();
        }

        /**
         * The per-screen initializer: called after the widget list is
         * cleared, every time the screen is (re)initialized or resized.
         * Subclasses add their widgets here with
         * {@link #addRenderableWidget}.
         */
        protected void init() {
                // Subclasses override this
        }

        /**
         * Adds a widget to this screen and returns it, mirroring the
         * vanilla Screen helper of the same name.
         */
        protected <T extends AbstractWidget> T addRenderableWidget(T widget) {
                this.widgets.add(widget);
                return widget;
        }

        /**
         * Removes all widgets from this screen.
         */
        protected void clearWidgets() {
                this.widgets.clear();
        }

        /**
         * Returns this screen's widgets in insertion order.
         */
        public List<AbstractWidget> children() {
                return this.widgets;
        }

        /**
         * Called when this screen is being resized.
         */
        public void resize(Minecraft minecraft, int width, int height) {
                this.init(minecraft, width, height);
        }

        /**
         * Renders the screen. The base implementation draws the widgets;
         * subclasses that override render should call
         * {@code super.render(...)} so their widgets stay on top of the
         * screen background.
         *
         * @param poseStack The pose stack for transformations
         * @param mouseX    Mouse X position (GUI pixels)
         * @param mouseY    Mouse Y position (GUI pixels)
         * @param partialTick Interpolation factor between ticks
         */
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                for (int i = 0; i < widgets.size(); i++) {
                        AbstractWidget widget = widgets.get(i);
                        if (widget.isVisible()) {
                                widget.render(poseStack, mouseX, mouseY, partialTick);
                        }
                }
        }

        /**
         * Called when a key is pressed.
         */
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                return false;
        }

        /**
         * Called when a key is released.
         */
        public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
                return false;
        }

        /**
         * Called when a character is typed.
         */
        public boolean charTyped(char codePoint, int modifiers) {
                return false;
        }

        /**
         * Called when a mouse button is clicked. Routes the click to the
         * widgets in reverse insertion order (topmost first) and reports
         * whether any widget consumed it.
         */
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                for (int i = widgets.size() - 1; i >= 0; i--) {
                        AbstractWidget widget = widgets.get(i);
                        if (widget.isVisible() && widget.mouseClicked(mouseX, mouseY, button)) {
                                return true;
                        }
                }
                return false;
        }

        /**
         * Called when a mouse button is released.
         */
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
                for (int i = widgets.size() - 1; i >= 0; i--) {
                        AbstractWidget widget = widgets.get(i);
                        if (widget.isVisible() && widget.mouseReleased(mouseX, mouseY, button)) {
                                return true;
                        }
                }
                return false;
        }

        /**
         * Called whenever the mouse moves while this screen is up.
         * Keeps widget hover states fresh even without a click.
         */
        public void mouseMoved(double mouseX, double mouseY) {
                for (int i = widgets.size() - 1; i >= 0; i--) {
                        widgets.get(i).mouseMoved(mouseX, mouseY);
                }
        }

        /**
         * Called when the mouse is dragged.
         */
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
                return false;
        }

        /**
         * Called when the mouse is scrolled.
         */
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                return false;
        }

        /**
         * Called every tick while this screen is active.
         */
        public void tick() {
                // Subclasses override this
        }

        /**
         * Returns whether this screen pauses the game.
         */
        public boolean isPauseScreen() {
                return false;
        }

        /**
         * Returns whether pressing ESC should close this screen.
         */
        public boolean shouldCloseOnEsc() {
                return true;
        }

        /**
         * Called when this screen is removed (replaced by another screen).
         */
        public void removed() {
                // Subclasses override for cleanup
        }

        /**
         * Called when this screen is closed.
         */
        public void onClose() {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(null);
                }
        }

        /**
         * Returns whether this screen should suppress input to the game.
         */
        public boolean isSuppressingInput() {
                return false;
        }

        // ========== Accessors ==========

        public int getWidth() {
                return width;
        }

        public int getHeight() {
                return height;
        }

        public Minecraft getMinecraft() {
                return minecraft;
        }

        public Font getFont() {
                return font;
        }

        /**
         * Returns the title of this screen.
         */
        public Object getTitle() {
                return this.getClass().getSimpleName();
        }

        /**
         * Returns the narrative message for accessibility.
         */
        public Object getNarrationMessage() {
                return getTitle();
        }
}
