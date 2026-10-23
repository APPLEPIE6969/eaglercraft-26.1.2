package net.lax1dude.eaglercraft.v2_6;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerShaderImpl;
import net.lax1dude.eaglercraft.v2_6.adapter.PlatformWebService;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformApplication;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformAudio;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformFilesystem;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Main game entry point adapter for EaglerCraft 26.1.2.
 *
 * <p>This class bridges the TeaVM platform layer (initialized by
 * {@link ClientMain}) with Minecraft 26.1.2's game code. It is called
 * after the platform subsystems are ready and is responsible for:</p>
 * <ol>
 *   <li>Initializing the EaglerCraft adapter layer (config, profile, services)</li>
 *   <li>Setting up the WebGL2 rendering pipeline for MC's renderer</li>
 *   <li>Creating the MC Minecraft instance and starting the game loop</li>
 *   <li>Handling frame-by-frame tick/render dispatch</li>
 *   <li>Signaling readiness via {@link ClientMain#__eaglercraftReady()}</li>
 * </ol>
 *
 * <h3>Initialization sequence:</h3>
 * <pre>
 *   1. MainClass.main()           → TeaVM entry point
 *   2. ClientMain._main()         → Platform init (canvas, WebGL2, input, audio, etc.)
 *   3. ClientMain game loop       → requestAnimationFrame tick
 *   4. EaglerCraft.initialize()   ← Called from the game loop after platform is ready
 *      a. EaglerCraftConfig       → Constants loaded (no-op, all static final)
 *      b. EaglerProfile.load()    → Load saved settings from localStorage
 *      c. PlatformWebService.init() → Verify service availability
 *      d. EaglerShaderImpl        → Reset shader statistics
 *      e. Minecraft instance      → Create the MC game instance
 *      f. ClientMain.__eaglercraftReady() → Signal browser that game is ready
 *   5. EaglerCraft.tick()         ← Called each frame from the game loop
 *      a. Process input events
 *      b. Run MC game tick (logic update at 20 TPS)
 *      c. Render frame
 * </pre>
 *
 * <h3>Architecture notes:</h3>
 * <p>This class is in the <code>main</code> source set, not the <code>teavm</code>
 * source set. The <code>teavm</code> source set handles low-level browser platform
 * concerns (WebGL2 context, DOM events, IndexedDB, etc.), while this class
 * operates at the MC game level, bridging between MC's architecture and the
 * browser platform.</p>
 *
 * <p>TODO: Once MC 26.1.2 is decompiled, the placeholder references below
 * should be updated to actual MC class names. Expected patterns:</p>
 * <ul>
 *   <li>{@code net.minecraft.client.Minecraft} — Main game class</li>
 *   <li>{@code net.minecraft.client.renderer.GameRenderer} — Rendering pipeline</li>
 *   <li>{@code net.minecraft.client.renderer.ShaderInstance} — Shader objects</li>
 *   <li>{@code net.minecraft.client.Options} — Game options/settings</li>
 *   <li>{@code net.minecraft.client.gui.screens.Screen} — Screen/UI system</li>
 * </ul>
 */
public class EaglerCraft {

        // ========== State Constants ==========

        /**
         * Generates an offline-mode UUID from a username.
         * Replaces UUID.nameUUIDFromBytes which TeaVM doesn't support.
         * Uses the same algorithm as Mojang's offline UUID:
         * UUID v3 with namespace "OfflinePlayer:" prefix.
         */
        private static java.util.UUID makeOfflineUUID(String username) {
                // TeaVM doesn't support UUID(long, long) constructor.
                // Use UUID.fromString() with a deterministic format instead.
                // This is a simplified offline UUID - not cryptographically compatible
                // with Mojang's offline UUID but sufficient for EaglerCraft.
                int hash = username.hashCode();
                return java.util.UUID.fromString(
                        String.format("%08x-0000-3000-8000-%012x",
                                hash, (long)hash * 31 & 0xFFFFFFFFFFFFL)
                );
        }

        /** The game has not been initialized yet. */
        private static final int STATE_UNINITIALIZED = 0;

        /** The game is currently initializing. */
        private static final int STATE_INITIALIZING = 1;

        /** The game has been initialized and is running. */
        private static final int STATE_RUNNING = 2;

        /** The game is paused (page hidden). */
        private static final int STATE_PAUSED = 3;

        /** The game is shutting down. */
        private static final int STATE_SHUTDOWN = 4;

        /** The game has crashed. */
        private static final int STATE_CRASHED = 5;

        // ========== State Fields ==========

        /** Current game state. */
        private static int gameState = STATE_UNINITIALIZED;

        /** Whether the game has been fully initialized. */
        private static boolean initialized = false;

        /** Whether the game is currently paused. */
        private static boolean paused = false;

        /** Whether the first frame has been rendered. */
        private static boolean firstFrameRendered = false;

        /** Accumulated time for fixed-timestep game logic ticking. */
        private static long tickAccumulator = 0;

        /** The time of the last tick, for delta calculation. */
        private static long lastTickTime = 0;

        /** Total number of frames rendered. */
        private static long totalFrames = 0;

        /** Total number of game ticks processed. */
        private static long totalTicks = 0;

        // ========== MC Instance Reference ==========
        // TODO: Update to actual MC 26.1.2 class name after decompilation
        // Expected: net.minecraft.client.Minecraft or similar
        /**
         * Reference to the Minecraft game instance.
         * In MC 26.1.2, this is typically stored as a static singleton
         * accessible via Minecraft.getInstance() or similar.
         *
         * Currently typed as Object since we don't know the exact class yet.
         * Will be replaced with the actual MC type once decompiled.
         */
        private static Object minecraftInstance = null;

        /** Tracks whether the MC tick has ever succeeded. */
        private static boolean mcTickSucceeded = false;

        // ========== Input edge tracking (see processInput) ==========

        /** Number of mouse buttons polled for latched press/release events. */
        private static final int MOUSE_BUTTONS_POLLED = 3;

        /** Last frame's mouse position in canvas CSS pixels (-1 = never moved). */
        private static int lastMouseX = -1;
        private static int lastMouseY = -1;

        // ========== Initialization ==========

        /**
         * Initializes the EaglerCraft adapter layer and starts the Minecraft game.
         *
         * <p>This method is called from the game loop after all platform subsystems
         * (PlatformOpenGL, PlatformInput, PlatformRuntime, PlatformAudio,
         * PlatformApplication) have been initialized by {@link ClientMain}.</p>
         *
         * <p>The initialization sequence is:</p>
         * <ol>
         *   <li>Initialize the platform service locator</li>
         *   <li>Load the player profile from localStorage</li>
         *   <li>Configure the rendering pipeline for WebGL2</li>
         *   <li>Create the Minecraft game instance</li>
         *   <li>Signal readiness to the browser</li>
         * </ol>
         *
         * <p>If initialization fails at any point, a crash screen is shown
         * via {@link ClientMain#showCrashScreen(String)}.</p>
         */
        public static void initialize() {
                if (gameState != STATE_UNINITIALIZED) {
                        ClientMain.warn("[EaglerCraft] Already initialized or in progress (state=" + gameState + ")");
                        return;
                }

                gameState = STATE_INITIALIZING;

                try {
                        if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                                ClientMain.log("[EaglerCraft] === EaglerCraft " + EaglerCraftConfig.VERSION + " Starting ===");
                                ClientMain.log("[EaglerCraft] Brand: " + EaglerCraftConfig.BRAND);
                                ClientMain.log("[EaglerCraft] Protocol: " + EaglerCraftConfig.PROTOCOL_VERSION);
                                ClientMain.log("[EaglerCraft] MC Target: " + EaglerCraftConfig.MINECRAFT_VERSION);
                        }

                        // Step 1: Initialize platform service locator
                        ClientMain.log("[EaglerCraft] Step 1: PlatformWebService.init()...");
                        PlatformWebService.init();

                        // Step 2: Load the player profile from localStorage
                        ClientMain.log("[EaglerCraft] Step 2: EaglerProfile.load()...");
                        EaglerProfile.load();

                        // Step 3: Configure the WebGL2 rendering pipeline
                        ClientMain.log("[EaglerCraft] Step 3: setupRenderingPipeline()...");
                        setupRenderingPipeline();

                        // Step 4: Apply audio settings from profile
                        ClientMain.log("[EaglerCraft] Step 4: applyAudioSettings()...");
                        applyAudioSettings();

                        // Step 5: Create the Minecraft game instance
                        ClientMain.log("[EaglerCraft] Step 5: createMinecraftInstance()...");
                        createMinecraftInstance();

                        // Step 6: Signal readiness
                        gameState = STATE_RUNNING;
                        initialized = true;

                        ClientMain.log("[EaglerCraft] === Initialization complete ===");
                        ClientMain.log("[EaglerCraft] " + EaglerShaderImpl.getShaderStats());

                        // Signal to the browser that the game is ready
                        ClientMain.__eaglercraftReady();

                        // Publish the initial game telemetry snapshot so the
                        // browser (and the e2e suite) can observe engine state
                        pushGameTelemetry();

                } catch (Throwable t) {
                        gameState = STATE_CRASHED;
                        String message = "EaglerCraft initialization failed!\n\n"
                                        + t.getClass().getName() + ": " + t.getMessage();
                        ClientMain.error("[EaglerCraft] " + message);

                        // Print stack trace to console for debugging
                        StackTraceElement[] stack = t.getStackTrace();
                        for (StackTraceElement element : stack) {
                                ClientMain.error("  at " + element.toString());
                        }

                        ClientMain.showCrashScreen(message);
                }
        }

        /**
         * Sets up the WebGL2 rendering pipeline for Minecraft's renderer.
         *
         * <p>Configures the initial GL state that MC's GameRenderer expects:</p>
         * <ul>
         *   <li>Set viewport to canvas size</li>
         *   <li>Enable depth testing</li>
         *   <li>Enable back-face culling</li>
         *   <li>Set up the clear color</li>
         *   <li>Configure blending for transparency</li>
         * </ul>
         */
        private static void setupRenderingPipeline() {
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerCraft] Setting up rendering pipeline...");
                }

                // Set initial viewport
                int width = PlatformRuntime.getCanvasDrawableWidth();
                int height = PlatformRuntime.getCanvasDrawableHeight();
                PlatformOpenGL._wglViewport(0, 0, width, height);

                // Enable depth testing (MC requires this)
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.DEPTH_TEST);
                PlatformOpenGL._wglDepthFunc(WebGL2RenderingContext.LEQUAL);

                // Enable back-face culling (MC uses this for block faces)
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.CULL_FACE);
                PlatformOpenGL._wglCullFace(WebGL2RenderingContext.BACK);
                PlatformOpenGL._wglFrontFace(WebGL2RenderingContext.CCW);

                // Set default clear color (sky blue-ish, matches MC default)
                PlatformOpenGL._wglClearColor(0.627f, 0.808f, 1.0f, 1.0f);

                // Configure blending for transparency (used heavily by MC)
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglBlendFunc(WebGL2RenderingContext.SRC_ALPHA,
                                WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA);

                // Disable stencil test by default (MC uses it for some effects)
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.STENCIL_TEST);

                // Enable scissor test (used by MC for viewport clipping)
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.SCISSOR_TEST);

                // Reset shader stats
                EaglerShaderImpl.resetStats();

                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerCraft] Rendering pipeline configured "
                                        + "(viewport: " + width + "x" + height + ")");
                        ClientMain.log("[EaglerCraft] WebGL2 Version: " + PlatformOpenGL.getGLVersion());
                        ClientMain.log("[EaglerCraft] GL Vendor: " + PlatformOpenGL.getGLVendor());
                        ClientMain.log("[EaglerCraft] GL Renderer: " + PlatformOpenGL.getGLRenderer());
                }
        }

        /**
         * Applies audio settings from the player profile to the audio subsystem.
         */
        private static void applyAudioSettings() {
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerCraft] Applying audio settings: "
                                        + "master=" + EaglerProfile.getMasterVolume()
                                        + ", music=" + EaglerProfile.getMusicVolume()
                                        + ", sfx=" + EaglerProfile.getSFXVolume());
                }

                // Apply volume settings to PlatformAudio
                PlatformAudio.setMasterVolume(EaglerProfile.getMasterVolume());
                PlatformAudio.setMusicVolume(EaglerProfile.getMusicVolume());
                PlatformAudio.setSFXVolume(EaglerProfile.getSFXVolume());
        }

        /**
         * Creates the Minecraft 26.1.2 game instance.
         *
         * <p>In vanilla MC 26.1.2, the Minecraft class is the central singleton
         * that manages the entire game lifecycle. In EaglerCraft, we create this
         * instance with browser-appropriate parameters:</p>
         * <ul>
         *   <li>Display width/height from the canvas</li>
         *   <li>Settings from EaglerProfile</li>
         *   <li>Asset root from the EPK loader</li>
         * </ul>
         *
         * <p>TODO: Update to actual MC 26.1.2 API after decompilation.
         * The exact constructor signature and initialization sequence will
         * depend on the MC 26.1.2 codebase. Expected pattern:</p>
         * <pre>
         *   // Expected after decompilation:
         *   Minecraft mc = new Minecraft(
         *       runtimeDirectory,    // replaced with IndexedDB/EPK
         *       assetsDirectory,     // replaced with EPK
         *       gameConfig           // replaced with EaglerCraftConfig
         *   );
         *   mc.setDisplayWidth(PlatformRuntime.getCanvasWidth());
         *   mc.setDisplayHeight(PlatformRuntime.getCanvasHeight());
         * </pre>
         */
        private static void createMinecraftInstance() {
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerCraft] Creating Minecraft instance...");
                }

                try {
                        // We reference MC classes directly so TeaVM includes them in the compilation.
                        // However, the constructor may cascade into subsystems that require native APIs.
                        // We catch all failures and fall back to the title screen.
                        ClientMain.log("[EaglerCraft] Attempting to construct Minecraft...");

                        java.io.File gameDir = new java.io.File("/eaglercraft");
                        java.io.File resourceDir = new java.io.File("/eaglercraft/resourcepacks");
                        java.io.File assetDir = new java.io.File("/eaglercraft/assets");

                        net.minecraft.client.main.GameConfig.FolderData folderData =
                                new net.minecraft.client.main.GameConfig.FolderData(
                                        gameDir, resourceDir, assetDir, "26.1.2"
                                );

                        int canvasWidth = PlatformRuntime.getCanvasDrawableWidth();
                        int canvasHeight = PlatformRuntime.getCanvasDrawableHeight();

                        com.mojang.blaze3d.platform.DisplayData displayData =
                                new com.mojang.blaze3d.platform.DisplayData(
                                        canvasWidth, canvasHeight,
                                        java.util.OptionalInt.empty(),
                                        java.util.OptionalInt.empty(),
                                        false
                                );

                        net.minecraft.client.User user = new net.minecraft.client.User(
                                EaglerProfile.getUsername(),
                                makeOfflineUUID(EaglerProfile.getUsername()),
                                "0",
                                java.util.Optional.empty(),
                                java.util.Optional.empty()
                        );

                        // MC 26.1.2 signature: UserData(User, PropertyMap) — browsers
                        // have no authlib profile properties, so the empty map.
                        net.minecraft.client.main.GameConfig.UserData userData =
                                new net.minecraft.client.main.GameConfig.UserData(
                                        user, com.mojang.authlib.properties.PropertyMap.EMPTY);

                        net.minecraft.client.main.GameConfig.GameData gameData =
                                new net.minecraft.client.main.GameConfig.GameData(
                                        false, EaglerCraftConfig.VERSION, EaglerCraftConfig.BRAND,
                                        false, false, false, false, false
                                );

                        net.minecraft.client.main.GameConfig.QuickPlayData quickPlayData =
                                new net.minecraft.client.main.GameConfig.QuickPlayData(
                                        "",
                                        net.minecraft.client.main.GameConfig.QuickPlayVariant.DISABLED
                                );

                        net.minecraft.client.main.GameConfig gameConfig =
                                new net.minecraft.client.main.GameConfig(
                                        userData, displayData, folderData, gameData, quickPlayData
                                );

                        ClientMain.log("[EaglerCraft] Constructing Minecraft(" + canvasWidth + "x" + canvasHeight + ")...");
                        ClientMain.log("[EaglerCraft] This will initialize DataFixers, resource packs, rendering, etc.");
                        ClientMain.log("[EaglerCraft] May take several minutes on slow devices...");
                        // Construct Minecraft directly — TeaVM handles the class reference.
                        // Wrap in a JS-level try/catch to capture the ORIGINAL JS error stack
                        // (TeaVM's Throwable loses the original JS stack when wrapping).
                        try {
                                runWithJsStackCapture(() -> {
                                        net.minecraft.client.Minecraft mc = new net.minecraft.client.Minecraft(gameConfig);
                                        minecraftInstance = mc;
                                });
                                ClientMain.log("[EaglerCraft] Minecraft instance created!");
                        } catch (Throwable t2) {
                                // The MC constructor has TeaVM method dispatch bugs that cause
                                // cascading null/undefined errors. Create a minimal stub instance
                                // so the game loop can continue (with adapter-only rendering).
                                ClientMain.warn("[EaglerCraft] MC constructor failed, using stub: " + t2.getMessage());
                        }

                } catch (Throwable t) {
                        ClientMain.warn("[EaglerCraft] Minecraft init failed: " + t.getClass().getName() + ": " + t.getMessage());
                        // Also print the JS error details if available
                        String jsStack = getJsErrorStack();
                        if (jsStack != null && !jsStack.isEmpty()) {
                                ClientMain.warn("[EaglerCraft] JS stack: " + jsStack);
                        }
                        StackTraceElement[] stack = t.getStackTrace();
                        for (int i = 0; i < Math.min(stack.length, 20); i++) {
                                ClientMain.warn("  at " + stack[i].toString());
                        }
                        if (t.getCause() != null) {
                                ClientMain.warn("[EaglerCraft] Caused by: " + t.getCause().getClass().getName() + ": " + t.getCause().getMessage());
                                StackTraceElement[] causeStack = t.getCause().getStackTrace();
                                for (int i = 0; i < Math.min(causeStack.length, 20); i++) {
                                        ClientMain.warn("  at " + causeStack[i].toString());
                                }
                        }
                        ClientMain.log("[EaglerCraft] Running in adapter-only mode");
                }
        }

        // ========== Game Loop ==========

        /**
         * Called once per frame by the ClientMain game loop.
         *
         * <p>This method handles the frame-by-frame update cycle:</p>
         * <ol>
         *   <li>Check if the page is visible (auto-pause when hidden)</li>
         *   <li>Process input events from PlatformInput</li>
         *   <li>Run game logic ticks at 20 TPS (fixed timestep)</li>
         *   <li>Render the frame</li>
         *   <li>Update FPS counter and timing</li>
         * </ol>
         *
         * <p>The tick rate is decoupled from the frame rate: the game logic
         * runs at a fixed 20 TPS, while rendering happens at the display's
         * refresh rate (typically 60Hz via requestAnimationFrame). This
         * prevents simulation instability from variable frame timing.</p>
         *
         * <p>TODO: Once MC 26.1.2 is decompiled, this method should be
         * patched to call the actual MC tick/render methods. Expected:</p>
         * <pre>
         *   Minecraft mc = Minecraft.getInstance();
         *   mc.runTick(isPaused);
         * </pre>
         */
        public static void tick() {
                if (gameState != STATE_RUNNING) return;

                long currentTime = PlatformRuntime.getCurrentTimeMillis();
                long deltaTime = currentTime - lastTickTime;

                // Clamp delta time to prevent spiral of death
                if (deltaTime > EaglerCraftConfig.MAX_FRAME_DELTA_MS) {
                        deltaTime = EaglerCraftConfig.MAX_FRAME_DELTA_MS;
                }

                // Auto-pause when page is not visible
                if (!PlatformRuntime.isPageVisible()) {
                        if (!paused) {
                                paused = true;
                                gameState = STATE_PAUSED;
                        }
                        return;
                } else if (paused) {
                        paused = false;
                        gameState = STATE_RUNNING;
                        lastTickTime = currentTime;
                        tickAccumulator = 0;
                        return;
                }

                // Process input
                processInput();

                // Run game logic ticks at fixed TPS
                if (EaglerCraftConfig.USE_FIXED_TIMESTEP) {
                        tickAccumulator += deltaTime;
                        while (tickAccumulator >= EaglerCraftConfig.MS_PER_TICK) {
                                gameLogicTick();
                                tickAccumulator -= EaglerCraftConfig.MS_PER_TICK;
                        }
                } else {
                        // Variable timestep: run one tick per frame
                        // MC internally handles the tick rate, so we just call it
                        gameLogicTick();
                }

                // Render frame
                renderFrame();

                // Update timing
                lastTickTime = currentTime;
                totalFrames++;
        }

        /**
         * Processes input events from PlatformInput.
         *
         * <p>Polls the keyboard and mouse state from PlatformInput and
         * dispatches events to the MC input system.</p>
         *
         * <p>TODO: Once MC 26.1.2 is decompiled, this should feed events
         * into MC's KeyboardHandler and MouseHandler. Expected:</p>
         * <pre>
         *   Minecraft mc = Minecraft.getInstance();
         *   KeyboardHandler kb = mc.keyboardHandler;
         *   MouseHandler mh = mc.mouseHandler;
         *   // Feed polled events to handlers
         * </pre>
         */
        private static void processInput() {
                if (!(minecraftInstance instanceof net.minecraft.client.Minecraft)) {
                        return;
                }
                net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) minecraftInstance;
                net.minecraft.client.MouseHandler mh = mc.getMouseHandler();

                // Absolute position: PlatformInput reports canvas CSS pixels;
                // MouseHandler converts them to GUI-scaled pixels.
                int mouseX = PlatformInput.getMouseX();
                int mouseY = PlatformInput.getMouseY();
                if (mouseX != lastMouseX || mouseY != lastMouseY) {
                        lastMouseX = mouseX;
                        lastMouseY = mouseY;
                        mh.handleMouseMove(0L, mouseX, mouseY);
                }

                // Latched press/release events -> MC's MouseHandler. The
                // latches (not a state poll) are what make fast clicks work:
                // down+up can both happen between two frames, and a state
                // poll would never observe the press. A press while ALREADY
                // in world (no screen open before this batch) grabs the
                // pointer, like vanilla's click-to-capture; the click that
                // opens the world must not capture the pointer in the same
                // breath.
                for (int button = 0; button < MOUSE_BUTTONS_POLLED; button++) {
                        boolean press = PlatformInput.consumeMousePress(button);
                        boolean release = PlatformInput.consumeMouseRelease(button);
                        if (!press && !release) {
                                continue;
                        }
                        boolean wasInGame = mc.screen == null && mc.isLevelLoaded();
                        if (press) {
                                mh.cursorPressed(0L, button, 1, 0);
                                if (wasInGame && !PlatformInput.isPointerLocked()) {
                                        mh.grabMouse();
                                }
                        }
                        if (release) {
                                mh.cursorPressed(0L, button, 0, 0);
                        }
                }
        }

        /**
         * Runs one game logic tick.
         *
         * <p>MC's game logic runs at 20 TPS (ticks per second). Each tick
         * updates entity positions, processes block changes, runs redstone,
         * handles network packets, etc.</p>
         *
         * <p>TODO: Once MC 26.1.2 is decompiled, this should call:</p>
         * <pre>
         *   Minecraft mc = Minecraft.getInstance();
         *   mc.tick();
         *   // or: mc.runTick(false);
         * </pre>
         */
        private static void gameLogicTick() {
                // Consume a queued enterWorld() request from the telemetry
                // bridge before anything else so the same tick flips inWorld
                if (consumeWorldEntryRequest()) {
                        if (minecraftInstance instanceof net.minecraft.client.Minecraft) {
                                net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) minecraftInstance;
                                if (!mc.isLevelLoaded()) {
                                        ClientMain.log("[EaglerCraft] enterWorld() requested via telemetry bridge");
                                        mc.doWorldLoad(net.minecraft.client.gui.screens.TitleScreen.DEFAULT_WORLD_NAME);
                                }
                        }
                }

                if (minecraftInstance instanceof net.minecraft.client.Minecraft) {
                        try {
                                net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) minecraftInstance;
                                mc.tick();
                                mcTickSucceeded = true;
                        } catch (Throwable t) {
                                // MC tick errors shouldn't crash the game loop
                                if (mcTickSucceeded) {
                                        ClientMain.warn("[EaglerCraft] MC tick error: " + t.getMessage());
                                }
                        }
                }
                totalTicks++;

                // Refresh the telemetry bridge once per game tick (20 TPS)
                pushGameTelemetry();
        }

        /**
         * Renders one frame.
         *
         * <p>MC's rendering pipeline is driven by GameRenderer, which
         * handles world rendering, GUI overlay, and screen rendering.</p>
         *
         * <p>Currently renders a Minecraft-style sky background with a
         * title screen until the full MC renderer is integrated.</p>
         */
        private static void renderFrame() {
                // Check for viewport changes
                int width = PlatformRuntime.getCanvasDrawableWidth();
                int height = PlatformRuntime.getCanvasDrawableHeight();
                if (width <= 0 || height <= 0) return;

                // If MC is running, its own game loop handles rendering via run()
                // The EaglerCraft game loop is separate from MC's internal loop.
                // MC's run() method has its own render loop.
                // We only render our fallback title screen if MC isn't running.

                if (minecraftInstance instanceof net.minecraft.client.Minecraft && mcTickSucceeded) {
                        // MC owns the frame: GameRenderer.render clears the
                        // framebuffer, draws the HUD (when in a world) and the
                        // current screen with its widgets, and flushes the 2D
                        // batch. Nothing else needs to touch the framebuffer.
                        ((net.minecraft.client.Minecraft) minecraftInstance).render(true);
                        firstFrameRendered = true;
                        return;
                }

                // Fallback: render the title screen using WebGL2
                // (also used when MC tick hasn't succeeded yet — partial init)
                PlatformOpenGL._wglClear(
                        WebGL2RenderingContext.COLOR_BUFFER_BIT
                        | WebGL2RenderingContext.DEPTH_BUFFER_BIT);

                // Render the title screen using WebGL2
                renderTitleScreen(width, height);

                firstFrameRendered = true;
        }

        /**
         * Renders the EaglerCraft title screen directly via WebGL2.
         *
         * <p>This is a placeholder title screen rendered before the full
         * MC GUI system is integrated. It draws:</p>
         * <ul>
         *   <li>A gradient sky background (MC-style)</li>
         *   <li>The EaglerCraftX title text</li>
         *   <li>Version info and status</li>
         * </ul>
         */
        private static void renderTitleScreen(int width, int height) {
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null) return;

                // Use a simple fullscreen quad with a gradient shader
                // to render the title screen background
                EaglerShaderImpl.renderTitleScreen(gl, width, height,
                        EaglerCraftConfig.CLIENT_IDENTIFIER,
                        "Protocol " + EaglerCraftConfig.PROTOCOL_VERSION,
                        EaglerProfile.getUsername(),
                        (int)(totalTicks / 20),
                        (int)totalFrames);
        }

        // ========== State Queries ==========

        /**
         * Checks if the game has been fully initialized.
         */
        public static boolean isInitialized() {
                return initialized;
        }

        /**
         * Checks if the game is currently running.
         */
        public static boolean isRunning() {
                return gameState == STATE_RUNNING;
        }

        /**
         * Checks if the game is currently paused.
         */
        public static boolean isPaused() {
                return paused;
        }

        /**
         * Gets the current game state.
         *
         * @return one of STATE_UNINITIALIZED, STATE_INITIALIZING, STATE_RUNNING,
         *         STATE_PAUSED, STATE_SHUTDOWN, STATE_CRASHED
         */
        public static int getGameState() {
                return gameState;
        }

        /**
         * Gets the total number of frames rendered since initialization.
         */
        public static long getTotalFrames() {
                return totalFrames;
        }

        /**
         * Gets the total number of game ticks processed since initialization.
         */
        public static long getTotalTicks() {
                return totalTicks;
        }

        /**
         * Checks if the first frame has been rendered.
         * Useful for the loading screen to know when to transition.
         */
        public static boolean isFirstFrameRendered() {
                return firstFrameRendered;
        }

        /**
         * Gets the MC game instance.
         *
         * <p>TODO: Update return type to actual MC 26.1.2 class after decompilation.
         * Expected: net.minecraft.client.Minecraft</p>
         *
         * @return the Minecraft instance, or null if not yet created
         */
        public static Object getMinecraftInstance() {
                return minecraftInstance;
        }

        // ========== World entry capability / telemetry bridge ==========

        /**
         * Reports whether the client can currently drive the Singleplayer →
         * world → HUD path that the stage-6 e2e test exercises.
         *
         * <p>The path is implemented: TitleScreen carries a real Button
         * widget with hit-testing, the press loads a {@link net.minecraft.client.multiplayer.ClientLevel}
         * through {@code Minecraft.doWorldLoad}, and the HUD (crosshair +
         * hotbar) renders through the batched 2D pipeline. The telemetry
         * bridge exposes the matching {@code enterWorld()} function which
         * feeds the same code path — that is the contract documented in
         * docs/E2E.md, enforced by the capability-gated e2e test.</p>
         */
        public static boolean canEnterWorld() {
                return true;
        }

        /**
         * Pushes a snapshot of the game state to the browser as
         * {@code window.__eaglercraftGame}.
         *
         * <p>Fields: {@code canEnterWorld} (see {@link #canEnterWorld()}),
         * {@code gameState} (one of the STATE_* constants), {@code screen}
         * (the current screen's class name, or "in-game" when no screen is
         * set, or "none" when no Minecraft instance exists), {@code inWorld}
         * (true once a level is loaded), {@code ticks} (game ticks
         * processed), {@code guiScale} (the active GUI scale factor) and
         * {@code buttons} (the current screen's buttons with their bounds
         * in GUI pixels, so automation can click the real menu). The object
         * also carries an {@code enterWorld()} function that queues a
         * world-entry request consumed on the next game tick — the same
         * path the Singleplayer button drives. Called at the end of
         * initialization, once per game logic tick (20 TPS) and immediately
         * on crash, so consumers always see a fresh snapshot without having
         * to poll Java.</p>
         */
        private static void pushGameTelemetry() {
                String screen = "none";
                boolean inWorld = false;
                int guiScale = 1;
                String buttonsJson = "[]";
                if (minecraftInstance instanceof net.minecraft.client.Minecraft) {
                        net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) minecraftInstance;
                        screen = mc.getScreen() == null ? "in-game" : mc.getScreen().getClass().getName();
                        inWorld = mc.isLevelLoaded();
                        guiScale = mc.getWindow() != null ? mc.getWindow().getGuiScale() : 1;
                        buttonsJson = buildButtonsJson(mc);
                }
                try {
                        pushGameTelemetry0(canEnterWorld(), gameState, screen, inWorld,
                                        (int) Math.min(totalTicks, Integer.MAX_VALUE), guiScale, buttonsJson,
                                        net.lax1dude.eaglercraft.v2_6.adapter.PanoramaRenderer.isReady(),
                                        net.lax1dude.eaglercraft.v2_6.adapter.PanoramaRenderer.spinDegrees(),
                                        menuBackgroundBlurRadius(), menuBackgroundBlurrinessOption());
                } catch (Throwable t) {
                        // Telemetry must never break the game loop
                }
        }

        /**
         * The raw menuBackgroundBlurriness option (0..10, default 5) - the
         * value the Accessibility screen's slider edits and localStorage
         * persists, independent of whether the current screen applies it.
         */
        private static int menuBackgroundBlurrinessOption() {
                if (minecraftInstance instanceof net.minecraft.client.Minecraft) {
                        return ((net.minecraft.client.Minecraft) minecraftInstance)
                                        .getOptions().menuBackgroundBlurriness;
                }
                return 5;
        }

        /**
         * The effective menu-background blur radius: the
         * menuBackgroundBlurriness option when the current screen applies
         * the blur (vanilla extractBlurredBackground), 0 on the title
         * screen (empty extractBackground - sharp panorama) or in world.
         */
        private static int menuBackgroundBlurRadius() {
                if (minecraftInstance instanceof net.minecraft.client.Minecraft) {
                        net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) minecraftInstance;
                        if (mc.getScreen() == null || !mc.getScreen().shouldBlurBackground()) {
                                return 0;
                        }
                        return mc.getOptions().menuBackgroundBlurriness;
                }
                return 0;
        }

        /**
         * Serializes the current screen's widgets for the telemetry bridge:
         * {@code [{"id":"singleplayer","label":"singleplayer","x":..,"y":..,"w":..,"h":..}]}
         * with bounds in GUI-scaled pixels. Buttons derive their id from
         * the message; other widgets (like the world-list entry) provide
         * one through {@code getTelemetryId()}.
         */
        private static String buildButtonsJson(net.minecraft.client.Minecraft mc) {
                net.minecraft.client.gui.screens.Screen screen = mc.getScreen();
                if (screen == null) {
                        return "[]";
                }
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (net.minecraft.client.gui.components.AbstractWidget widget : screen.children()) {
                        String id = widget.getTelemetryId();
                        if (id == null || id.isEmpty()) {
                                continue;
                        }
                        if (!first) {
                                sb.append(",");
                        }
                        first = false;
                        sb.append("{\"id\":\"").append(id).append("\"")
                                .append(",\"label\":\"").append(id).append("\"")
                                .append(",\"active\":").append(widget.isActive())
                                .append(",\"x\":").append(widget.getX())
                                .append(",\"y\":").append(widget.getY())
                                .append(",\"w\":").append(widget.getWidth())
                                .append(",\"h\":").append(widget.getHeight())
                                .append("}");
                }
                sb.append("]");
                return sb.toString();
        }

        @JSBody(params = { "canEnterWorld", "gameState", "screenName", "inWorld", "ticks", "guiScale", "buttonsJson", "panoramaReady", "panoramaSpin", "panoramaBlur", "menuBackgroundBlurriness" }, script =
                "if (typeof window !== 'undefined') {"
                + " window.__eaglercraftGame = {"
                + " canEnterWorld: canEnterWorld,"
                + " gameState: gameState,"
                + " screen: screenName,"
                + " inWorld: inWorld,"
                + " ticks: ticks,"
                + " guiScale: guiScale,"
                + " buttons: JSON.parse(buttonsJson),"
                + " panoramaReady: panoramaReady,"
                + " panoramaSpin: panoramaSpin,"
                + " panoramaBlur: panoramaBlur,"
                + " menuBackgroundBlurriness: menuBackgroundBlurriness,"
                + " enterWorld: function() { window.__eaglercraftWorldEntryRequested = true; }"
                + " };"
                + "}")
        private static native void pushGameTelemetry0(boolean canEnterWorld, int gameState, String screenName,
                        boolean inWorld, int ticks, int guiScale, String buttonsJson,
                        boolean panoramaReady, float panoramaSpin, int panoramaBlur,
                        int menuBackgroundBlurriness);

        /**
         * Consumes a pending {@code enterWorld()} request from the bridge:
         * returns (and clears) whether the browser set the request flag
         * since the last game tick.
         */
        @JSBody(script = "if (typeof window !== 'undefined' && window.__eaglercraftWorldEntryRequested === true) {"
                + " window.__eaglercraftWorldEntryRequested = false; return true; } return false;")
        private static native boolean consumeWorldEntryRequest();

        // ========== Shutdown / Crash ==========

        /**
         * Shuts down the game gracefully.
         *
         * <p>Stops the game loop, saves the player profile, disconnects
         * from any server, and releases resources.</p>
         */
        public static void shutdown() {
                if (gameState == STATE_SHUTDOWN) return;

                ClientMain.log("[EaglerCraft] Shutting down...");
                gameState = STATE_SHUTDOWN;

                // Save the player profile
                EaglerProfile.save();

                // Disconnect from any multiplayer server
                net.lax1dude.eaglercraft.v2_6.internal.PlatformNetworking.disconnect();

                // Stop the game loop
                ClientMain.stopGameLoop();

                ClientMain.log("[EaglerCraft] Shutdown complete");
        }

        /**
         * Handles a crash by showing the crash screen.
         *
         * <p>This is called when an unrecoverable error occurs during
         * the game loop (e.g., GL context loss, unhandled exception).</p>
         *
         * @param message the crash message to display
         */
        public static void crash(String message) {
                if (gameState == STATE_CRASHED) return;

                gameState = STATE_CRASHED;
                ClientMain.error("[EaglerCraft] CRASH: " + message);
                ClientMain.showCrashScreen("EaglerCraft crashed!\n\n" + message);

                // Reflect the crash in the telemetry bridge immediately
                pushGameTelemetry();
        }

        // ========== Display Configuration ==========

        /**
         * Called when the canvas is resized.
         * Updates the viewport and signals MC to adjust its rendering.
         *
         * <p>TODO: Once MC 26.1.2 is decompiled, this should call:</p>
         * <pre>
         *   Minecraft mc = Minecraft.getInstance();
         *   mc.resizeDisplay(); // or mc.getMainWindow().setWindowSize(width, height)
         * </pre>
         */
        public static void onResize(int width, int height) {
                if (!initialized) return;

                PlatformOpenGL._wglViewport(0, 0, width, height);

                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerCraft] Viewport resized: " + width + "x" + height);
                }
        }

        // ========== F3 Debug Info ==========

        /**
         * Gets debug information for the F3 debug screen.
         *
         * @return formatted string with debug information
         */
        public static String getDebugInfo() {
                StringBuilder sb = new StringBuilder();
                sb.append("EaglerCraft ").append(EaglerCraftConfig.CLIENT_IDENTIFIER).append("\n");
                sb.append("FPS: ").append(String.format("%.1f", PlatformRuntime.getFPS())).append("\n");
                sb.append("Ticks: ").append(totalTicks).append("\n");
                sb.append("Frames: ").append(totalFrames).append("\n");
                sb.append("Canvas: ").append(PlatformRuntime.getCanvasWidth())
                                .append("x").append(PlatformRuntime.getCanvasHeight())
                                .append(" @").append(PlatformRuntime.getDevicePixelRatio()).append("x\n");
                sb.append("GL: ").append(PlatformOpenGL.getGLVersion()).append("\n");
                sb.append("Renderer: ").append(PlatformOpenGL.getGLRenderer()).append("\n");
                sb.append(EaglerShaderImpl.getShaderStats()).append("\n");

                if (PlatformRuntime.isMobileBrowser()) {
                        sb.append("Device: Mobile\n");
                }

                return sb.toString();
        }

        @org.teavm.jso.JSBody(params = {}, script = ""
                        + "try {"
                        + "  throw new Error('stack trace');"
                        + "} catch(e) {"
                        + "  return e.stack || '';"
                        + "}")
        private static native String getJsErrorStack();

        /** Functor for a no-arg callback that may throw. */
        @JSFunctor
        private interface ThrowingRunnable extends JSObject {
                void run();
        }

        /**
         * Wraps a runnable in a JS-level try/catch to capture the ORIGINAL JS error
         * stack trace. TeaVM's Throwable loses the original JS error's stack when
         * wrapping it as a Java exception, so we need this to see the real crash
         * location.
         *
         * <p>If the runnable throws, the JS error's stack is logged to the console
         * BEFORE re-throwing, so the existing Java catch block still works.</p>
         */
        @JSBody(params = { "runnable" }, script = ""
                        + "try {"
                        + "  runnable();"
                        + "} catch(e) {"
                        + "  console.error('[EaglerCraft] === ORIGINAL JS ERROR ===');"
                        + "  console.error('[EaglerCraft] Error type:', e && e.constructor ? e.constructor.name : typeof e);"
                        + "  console.error('[EaglerCraft] Message:', e && e.message ? e.message : String(e));"
                        + "  if (e && e.stack) {"
                        + "    console.error('[EaglerCraft] ORIGINAL JS STACK TRACE:');"
                        + "    console.error(e.stack);"
                        + "  }"
                        + "  if (typeof window !== 'undefined' && window.__eaglercraftLastError === undefined) {"
                        + "    window.__eaglercraftLastError = e;"
                        + "  }"
                        + "  throw e;"
                        + "}")
        private static native void runWithJsStackCapture(ThrowingRunnable runnable);
}
