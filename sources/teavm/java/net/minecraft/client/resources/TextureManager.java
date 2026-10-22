package net.minecraft.client.resources;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.TextureUtil;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.resources.TextureManager.
 * Texture manager that maps resource locations to WebGL texture IDs.
 * Uses TextureUtil for WebGL texture creation/deletion.
 * For now, this is a minimal stub that supports basic texture registration.
 */
public class TextureManager {

	/** Map of resource location (string) to texture ID. */
	private final Map<String, Integer> textureMap = new HashMap<>();

	/** Map of texture ID to tick count (for animated textures). */
	private final Map<Integer, Integer> tickCountMap = new HashMap<>();

	public TextureManager() {
		ClientMain.log("[TextureManager] Initialized (WebGL2 texture stub)");
	}

	/**
	 * Registers a texture for the given resource location.
	 * Creates a WebGL texture via TextureUtil and stores the mapping.
	 *
	 * @param resourceLocation The resource location string
	 * @param textureObject    The texture object (not used directly; ID is generated)
	 * @return The texture ID, or 0 if registration failed
	 */
	public int register(String resourceLocation, Object textureObject) {
		if (resourceLocation == null) return 0;

		// Check if already registered
		Integer existingId = textureMap.get(resourceLocation);
		if (existingId != null) return existingId;

		// Create a new texture
		int textureId = TextureUtil.generateTextureId();
		if (textureId != 0) {
			textureMap.put(resourceLocation, textureId);
		}

		return textureId;
	}

	/**
	 * Binds a texture for rendering.
	 *
	 * @param resourceLocation The resource location string to bind
	 */
	public void bind(String resourceLocation) {
		if (resourceLocation == null) return;

		Integer textureId = textureMap.get(resourceLocation);
		if (textureId == null) {
			// Auto-register: create a texture on first bind
			textureId = TextureUtil.generateTextureId();
			if (textureId != 0) {
				textureMap.put(resourceLocation, textureId);
			}
		}

		if (textureId != null && textureId != 0) {
			// Bind the WebGL texture
			bindTexture0(textureId);
		}
	}

	/**
	 * Binds a texture by its integer ID.
	 */
	public void bindForStreaming(int textureId) {
		if (textureId != 0) {
			bindTexture0(textureId);
		}
	}

	/**
	 * Releases a texture for the given resource location.
	 *
	 * @param resourceLocation The resource location string to release
	 */
	public void release(String resourceLocation) {
		if (resourceLocation == null) return;

		Integer textureId = textureMap.remove(resourceLocation);
		if (textureId != null) {
			TextureUtil.releaseTextureId(textureId);
		}
	}

	/**
	 * Gets the texture ID for a resource location.
	 *
	 * @param resourceLocation The resource location string
	 * @return The texture ID, or 0 if not registered
	 */
	public int getTexture(String resourceLocation) {
		if (resourceLocation == null) return 0;
		Integer id = textureMap.get(resourceLocation);
		return id != null ? id : 0;
	}

	/**
	 * Ticks all animated textures.
	 */
	public void tick() {
		// TODO: Update animated textures
	}

	/**
	 * Binds a WebGL texture by its integer ID.
	 * Delegates to TextureUtil's JSObject mapping.
	 */
	private void bindTexture0(int textureId) {
		org.teavm.jso.JSObject glTexture = TextureUtil.getTextureObject(textureId);
		if (glTexture != null) {
			// Use PlatformOpenGL to bind the texture
			net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL._wglBindTexture(
				net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext.TEXTURE_2D,
				glTexture
			);
		}
	}

	/**
	 * Returns the number of loaded textures.
	 */
	public int getLoadedTextureCount() {
		return textureMap.size();
	}

	/**
	 * Closes/releases all textures.
	 */
	public void close() {
		for (Map.Entry<String, Integer> entry : textureMap.entrySet()) {
			TextureUtil.releaseTextureId(entry.getValue());
		}
		textureMap.clear();
		tickCountMap.clear();
	}
}
