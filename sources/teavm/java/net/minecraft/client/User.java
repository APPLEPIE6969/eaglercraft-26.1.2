package net.minecraft.client;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.User.
 * Simple data class holding player session information.
 * In EaglerCraft, the user is always offline-mode with no real session.
 */
public class User {

	private final String name;
	private final UUID uuid;
	private final String accessToken;
	private final Optional<String> xuid;
	private final Optional<String> clientId;

	public User(String name, UUID uuid, String accessToken, Optional<String> xuid, Optional<String> clientId) {
		this.name = name;
		this.uuid = uuid;
		this.accessToken = accessToken;
		this.xuid = xuid;
		this.clientId = clientId;
	}

	public String getName() {
		return name;
	}

	public UUID getUuid() {
		return uuid;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public Optional<String> getXuid() {
		return xuid;
	}

	public Optional<String> getClientId() {
		return clientId;
	}

	/**
	 * Returns a GameProfile for this user.
	 * In EaglerCraft, this is a minimal offline-mode profile.
	 */
	public GameProfile getProfile() {
		return new GameProfile(uuid, name);
	}

	/**
	 * Returns the user type. In EaglerCraft, this is always "legacy" (offline).
	 */
	public Type getType() {
		return Type.LEGACY;
	}

	@Override
	public String toString() {
		return "User{name=" + name + ", uuid=" + uuid + "}";
	}

	/**
	 * User session type enum.
	 * EaglerCraft only uses LEGACY (offline) mode.
	 */
	public enum Type {
		LEGACY("legacy"),
		MOJANG("mojang"),
		MSA("msa");

		private final String name;

		Type(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public static Type byName(String name) {
			for (Type t : values()) {
				if (t.name.equalsIgnoreCase(name)) return t;
			}
			return LEGACY;
		}
	}
}
