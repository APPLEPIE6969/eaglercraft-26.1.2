package net.lax1dude.eaglercraft.v2_6.placeholder_server.mc;

/**
 * EaglerCraft 26.1.2 - Minecraft protocol packet identifiers used by the
 * Eagler gateway MC bridge.
 *
 * <p>The bridge speaks a minimal subset of the vanilla server protocol
 * (protocol 775, the 26.1.2 fork version) towards the backend server:
 * handshake, login, keepalive, system chat and disconnect. Packet ids in
 * modern Minecraft shift between releases, so every id used by the bridge
 * lives here and can be overridden per deployment (config {@code mc_*} keys
 * in the Bungee/Velocity plugins). Only the ids actually consumed or
 * produced by the bridge are listed; every other inbound packet is skipped
 * via length framing, which is stable across versions.</p>
 */
public final class McPacketIds {

    // ===== login state, server -> client =====

    /** Disconnect (login state), NBT text component. */
    public int loginDisconnect = 0x00;
    /** Encryption Request. The bridge is offline-mode only and refuses. */
    public int loginEncryptionRequest = 0x01;
    /** Login Success. */
    public int loginSuccess = 0x02;
    /** Set Compression. */
    public int loginSetCompression = 0x03;
    /** Login Plugin Request (answered with a "no"). */
    public int loginPluginRequest = 0x04;

    // ===== login state, client -> server =====

    /** Login Plugin Response. */
    public int loginPluginResponse = 0x02;

    // ===== play state, server -> client =====

    /** Keep Alive. */
    public int playKeepAlive = 0x1E;
    /** System Chat Message (NBT component + overlay boolean). */
    public int playSystemChat = 0x72;
    /** Disconnect (play state), NBT text component. */
    public int playDisconnect = 0x4D;

    // ===== play state, client -> server =====

    /** Keep Alive response. */
    public int playKeepAliveResponse = 0x12;
    /** Chat Message (plain string + signature fields, sent unsigned). */
    public int playChat = 0x06;

    /** Protocol version advertised in the handshake (26.1.2 fork = 775). */
    public int protocolVersion = 775;

    /** Copies this configuration so callers can tweak ids per session. */
    public McPacketIds copy() {
        McPacketIds c = new McPacketIds();
        c.loginDisconnect = loginDisconnect;
        c.loginEncryptionRequest = loginEncryptionRequest;
        c.loginSuccess = loginSuccess;
        c.loginSetCompression = loginSetCompression;
        c.loginPluginRequest = loginPluginRequest;
        c.loginPluginResponse = loginPluginResponse;
        c.playKeepAlive = playKeepAlive;
        c.playSystemChat = playSystemChat;
        c.playDisconnect = playDisconnect;
        c.playKeepAliveResponse = playKeepAliveResponse;
        c.playChat = playChat;
        c.protocolVersion = protocolVersion;
        return c;
    }
}
