package net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.Gui.
 * HUD overlay stub. Renders in-game HUD elements (health bar, hotbar, crosshair, etc.).
 * All methods are no-ops for now - actual HUD rendering will use EaglerShaderImpl.
 */
public class Gui {

	private final Minecraft minecraft;
	private Object chatComponent; // ChatComponent stub

	public Gui(Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	/**
	 * Renders the HUD overlay.
	 */
	public void render(PoseStack poseStack, float partialTick) {
		// no-op for now - HUD rendering not yet implemented
	}

	/**
	 * Called every tick.
	 */
	public void tick() {
		// no-op
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
}
