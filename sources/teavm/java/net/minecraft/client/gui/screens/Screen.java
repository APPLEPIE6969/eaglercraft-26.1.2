package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

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

	public Screen() {
	}

	/**
	 * Called when this screen is initialized or resized.
	 */
	public void init(Minecraft minecraft, int width, int height) {
		this.minecraft = minecraft;
		this.font = minecraft != null ? minecraft.font : new Font();
		this.width = width;
		this.height = height;
	}

	/**
	 * Called when this screen is being resized.
	 */
	public void resize(Minecraft minecraft, int width, int height) {
		this.init(minecraft, width, height);
	}

	/**
	 * Renders the screen.
	 *
	 * @param poseStack The pose stack for transformations
	 * @param mouseX    Mouse X position
	 * @param mouseY    Mouse Y position
	 * @param partialTick Interpolation factor between ticks
	 */
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		// Subclasses override this
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
	 * Called when a mouse button is clicked.
	 */
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	/**
	 * Called when a mouse button is released.
	 */
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
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
