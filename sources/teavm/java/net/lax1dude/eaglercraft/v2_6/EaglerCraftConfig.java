package net.lax1dude.eaglercraft.v2_6;

/**
 * Configuration constants for the EaglerCraft 26.1.2 adapter layer.
 * Centralizes all version strings, protocol identifiers, feature flags,
 * and default values used throughout the EaglerCraft bridge between
 * Minecraft 26.1.2 and the TeaVM/browser platform.
 *
 * <p>These constants are referenced by:</p>
 * <ul>
 *   <li>{@link EaglerCraft} — main entry point uses version/brand for initialization</li>
 *   <li>{@link EaglerProfile} — default profile values</li>
 *   <li>{@link adapter.PlatformWebService} — version reporting to MC code</li>
 *   <li>{@link adapter.EaglerShaderImpl} — shader version compatibility checks</li>
 * </ul>
 *
 * <p>Architecture note: This class is in the <code>main</code> source set,
 * not the <code>teavm</code> source set. It bridges Minecraft 26.1.2 code
 * with the platform layer in <code>net.lax1dude.eaglercraft.v2_6.internal</code>.</p>
 */
public final class EaglerCraftConfig {

        // ========== Version Information ==========

        /** The EaglerCraft adapter version string. Matches the MC version we target. */
        public static final String VERSION = "26.1.2";

        /** The Minecraft protocol version number for 26.1.2. */
        // TODO: Update to actual MC 26.1.2 protocol version after decompilation
        // Expected: 775 or similar (depends on the actual MC 26.1.2 release)
        public static final int PROTOCOL_VERSION = 775;

        /** The EaglerCraft brand string sent to servers in the brand channel. */
        public static final String BRAND = "EaglerCraftX";

        /** Combined brand/version identifier for client listing. */
        public static final String CLIENT_IDENTIFIER = BRAND + "/" + VERSION;

        /** The minimum TeaVM runtime version required. */
        public static final String MINIMUM_TEAVM_VERSION = "0.15.0";

        /** The Minecraft version this adapter targets. */
        public static final String MINECRAFT_VERSION = "26.1.2";

        // ========== Rendering Defaults ==========

        /** Default render distance in chunks. */
        public static final int DEFAULT_RENDER_DISTANCE = 8;

        /** Minimum render distance in chunks. */
        public static final int MIN_RENDER_DISTANCE = 2;

        /** Maximum render distance in chunks (browser performance limit). */
        public static final int MAX_RENDER_DISTANCE = 16;

        /** Default field of view in degrees. */
        public static final int DEFAULT_FOV = 70;

        /** Whether VSync is enabled by default. */
        public static final boolean DEFAULT_VSYNC = true;

        /** Whether smooth lighting is enabled by default. */
        public static final boolean DEFAULT_SMOOTH_LIGHTING = true;

        /** Whether fancy graphics are enabled by default. */
        public static final boolean DEFAULT_FANCY_GRAPHICS = true;

        /** Default mipmap levels for textures. */
        public static final int DEFAULT_MIPMAP_LEVELS = 4;

        // ========== Audio Defaults ==========

        /** Default master volume (0.0 to 1.0). */
        public static final float DEFAULT_MASTER_VOLUME = 1.0f;

        /** Default music volume (0.0 to 1.0). */
        public static final float DEFAULT_MUSIC_VOLUME = 1.0f;

        /** Default SFX volume (0.0 to 1.0). */
        public static final float DEFAULT_SFX_VOLUME = 1.0f;

        /** Default ambient volume (0.0 to 1.0). */
        public static final float DEFAULT_AMBIENT_VOLUME = 0.75f;

        /** Audio sample rate used by the Web Audio API. */
        public static final int AUDIO_SAMPLE_RATE = 48000;

        /** Maximum simultaneous audio sources. */
        public static final int MAX_AUDIO_SOURCES = 32;

        // ========== Networking ==========

        /** Default server URI for auto-connect. */
        public static final String DEFAULT_SERVER_URI = "ws://localhost:25565";

        /** Maximum packet size in bytes (1MB). */
        public static final int MAX_PACKET_SIZE = 0x100000;

        /** Connection timeout in milliseconds. */
        public static final int CONNECTION_TIMEOUT_MS = 30000;

        /** Maximum reconnection attempts. */
        public static final int MAX_RECONNECT_ATTEMPTS = 5;

        /** Whether voice chat (WebRTC) is enabled by default. */
        public static final boolean DEFAULT_VOICE_CHAT = false;

        // ========== Feature Flags ==========

        /**
         * Whether the integrated singleplayer server is enabled.
         * Requires OffscreenCanvas and Web Worker support.
         */
        public static final boolean ENABLE_INTEGRATED_SERVER = true;

        /** Whether multiplayer (WebSocket) is enabled. */
        public static final boolean ENABLE_MULTIPLAYER = true;

        /** Whether the F3 debug screen is available. */
        public static final boolean ENABLE_F3_DEBUG = true;

        /** Whether command blocks are available in singleplayer. */
        public static final boolean ENABLE_COMMAND_BLOCKS = true;

        /** Whether the shader editor is available (for power users). */
        public static final boolean ENABLE_SHADER_EDITOR = false;

        /** Whether resource pack loading from URLs is allowed. */
        public static final boolean ENABLE_RESOURCE_PACK_LOADING = true;

        /** Whether world import/export via file download/upload is enabled. */
        public static final boolean ENABLE_WORLD_IMPORT_EXPORT = true;

        /** Whether the screenshot functionality is enabled (canvas toDataURL). */
        public static final boolean ENABLE_SCREENSHOTS = true;

        /** Whether demo mode restrictions are applied. */
        public static final boolean DEMO_MODE = false;

        // ========== Performance ==========

        /** Target frames per second for the game loop. */
        public static final int TARGET_FPS = 60;

        /** Target ticks per second for game logic. */
        public static final int TARGET_TPS = 20;

        /** Milliseconds per tick at TARGET_TPS. */
        public static final long MS_PER_TICK = 1000L / TARGET_TPS;

        /** Fixed timestep: the game simulates at exactly TARGET_TPS
         * regardless of the display's refresh rate - vanilla's Minecraft
         * timer semantics (a 20 TPS simulation with partialTick
         * interpolation for rendering). The old variable-timestep path
         * ran one full game tick per requestAnimationFrame frame, i.e.
         * 60 ticks/s on a 60Hz display and 144/s on gaming monitors -
         * 3x to 7x the vanilla simulation rate, which is why walking
         * (and everything else tick-driven) was "way too fast". */
        public static final boolean USE_FIXED_TIMESTEP = true;

        /** Maximum frame delta time in milliseconds (prevents spiral of death). */
        public static final long MAX_FRAME_DELTA_MS = 250;

        /** Whether to use HiDPI rendering (respects device pixel ratio). */
        public static final boolean USE_HIDPI_RENDERING = true;

        /** Whether chunk update batching is enabled. */
        public static final boolean ENABLE_CHUNK_BATCHING = true;

        // ========== Storage ==========

        /** localStorage namespace prefix (separates multiple instances). */
        public static final String DEFAULT_LOCALSTORAGE_NAMESPACE = "eaglercraft";

        /** Default IndexedDB database name for worlds. */
        public static final String DEFAULT_WORLDS_DB = "EAGLERWORLDS";

        /** Default IndexedDB database name for resource packs. */
        public static final String DEFAULT_RESOURCE_PACKS_DB = "EAGLERRESOURCEPACKS";

        /** Maximum world size in bytes (50MB browser storage limit). */
        public static final long MAX_WORLD_SIZE = 50 * 1024 * 1024;

        // ========== Skin ==========

        /** Default skin model ID (0 = Steve, 1 = Alex). */
        public static final int DEFAULT_SKIN_MODEL = 0;

        /** Supported skin widths. */
        public static final int SKIN_WIDTH = 64;

        /** Supported skin heights. */
        public static final int SKIN_HEIGHT = 64;

        /** Maximum skin file size in bytes (128KB). */
        public static final int MAX_SKIN_SIZE = 128 * 1024;

        // ========== Logging ==========

        /** Whether verbose logging is enabled during initialization. */
        public static final boolean VERBOSE_INIT_LOGGING = true;

        /** Whether GL error checking is enabled (performance impact). */
        public static final boolean ENABLE_GL_ERROR_CHECKING = false;

        // ========== WebGL Context ==========

        /** Whether the WebGL2 context should use alpha. */
        public static final boolean GL_CONTEXT_ALPHA = false;

        /** Whether the WebGL2 context should use depth buffer. */
        public static final boolean GL_CONTEXT_DEPTH = true;

        /** Whether the WebGL2 context should use stencil buffer. */
        public static final boolean GL_CONTEXT_STENCIL = true;

        /** Whether antialiasing is enabled on the WebGL2 context. */
        public static final boolean GL_CONTEXT_ANTIALIAS = false;

        /** WebGL2 power preference. */
        public static final String GL_POWER_PREFERENCE = "high-performance";

/**
 * Whether to use the desynchronized canvas hint (reduces presentation
 * latency where honored). Kept {@code false}: the hint routes frames
 * through Chrome's low-latency DirectComposition path, which is a
 * documented source of visible flicker on real compositors (parts of
 * the frame - typically the UI quads redrawn last - show stale/partial
 * content). Headless/software rendering never honors the hint, which is
 * why the flicker only reproduces on real GPU presentation.
 */
public static final boolean GL_CONTEXT_DESYNCHRONIZED = false;

        // Private constructor — all members are static constants
        private EaglerCraftConfig() {
        }
}
