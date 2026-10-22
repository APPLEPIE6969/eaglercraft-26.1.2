package net.minecraft.client.main;

import java.io.File;
import java.util.OptionalInt;

import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.platform.DisplayData;

import net.minecraft.client.User;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.main.GameConfig.
 * Data class holding all configuration needed to start the Minecraft client.
 * In EaglerCraft, most of these are stubs since the browser environment
 * doesn't need real file paths or authentication.
 */
public class GameConfig {

	public final UserData user;
	public final DisplayData display;
	public final FolderData folder;
	public final GameData game;
	public final QuickPlayData quickPlay;

	public GameConfig(UserData user, DisplayData display, FolderData folder, GameData game, QuickPlayData quickPlay) {
		this.user = user;
		this.display = display;
		this.folder = folder;
		this.game = game;
		this.quickPlay = quickPlay;
	}

	/**
	 * Folder/directory configuration.
	 * In EaglerCraft, these are virtual paths backed by IndexedDB/EPK.
	 */
	public static class FolderData {
		public final File gameDirectory;
		public final File resourcePackDirectory;
		public final File assetsDirectory;
		public final String assetIndex;

		public FolderData(File gameDirectory, File resourcePackDirectory, File assetsDirectory, String assetIndex) {
			this.gameDirectory = gameDirectory;
			this.resourcePackDirectory = resourcePackDirectory;
			this.assetsDirectory = assetsDirectory;
			this.assetIndex = assetIndex;
		}
	}

	/**
	 * User/session data.
	 * In EaglerCraft, the user is always offline-mode.
	 */
	public static class UserData {
		public final User user;
		public final PropertyMap propertyMap;

		public UserData(User user, PropertyMap propertyMap) {
			this.user = user;
			this.propertyMap = propertyMap;
		}
	}

	/**
	 * Game feature/data configuration.
	 */
	public static class GameData {
		public final boolean demo;
		public final String launchVersion;
		public final String versionType;
		public final boolean playsSingleplayer;
		public final boolean allowsMultiplayer;
		public final boolean allowsChat;
		public final boolean disablesQuickPlay;
		public final boolean disableMultiplayer;

		public GameData(boolean demo, String launchVersion, String versionType,
				boolean playsSingleplayer, boolean allowsMultiplayer, boolean allowsChat,
				boolean disablesQuickPlay, boolean disableMultiplayer) {
			this.demo = demo;
			this.launchVersion = launchVersion;
			this.versionType = versionType;
			this.playsSingleplayer = playsSingleplayer;
			this.allowsMultiplayer = allowsMultiplayer;
			this.allowsChat = allowsChat;
			this.disablesQuickPlay = disablesQuickPlay;
			this.disableMultiplayer = disableMultiplayer;
		}
	}

	/**
	 * Quick-play configuration for auto-connecting to servers.
	 */
	public static class QuickPlayData {
		public final String quickPlayPath;
		public final QuickPlayVariant quickPlayVariant;

		public QuickPlayData(String quickPlayPath, QuickPlayVariant quickPlayVariant) {
			this.quickPlayPath = quickPlayPath;
			this.quickPlayVariant = quickPlayVariant;
		}
	}

	/**
	 * Quick-play variant enum.
	 */
	public enum QuickPlayVariant {
		DISABLED,
		SINGLEPLAYER,
		MULTIPLAYER,
		REALMS;

		public static QuickPlayVariant byName(String name) {
			for (QuickPlayVariant v : values()) {
				if (v.name().equalsIgnoreCase(name)) return v;
			}
			return DISABLED;
		}
	}
}
