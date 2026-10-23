package net.minecraft.client;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.EaglerProfile;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.TextureManager;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.Minecraft.
 *
 * <p>This is THE most critical class in the entire EaglerCraft adapter.
 * In vanilla MC, Minecraft is the central singleton that manages the
 * entire game lifecycle: initialization, tick, render, input, resources,
 * networking, etc.</p>
 *
 * <p>In EaglerCraft, we override this class to:</p>
 * <ul>
 *   <li>Initialize minimal state from GameConfig (browser-appropriate)</li>
 *   <li>Provide tick() and render() methods called by EaglerCraft's rAF loop</li>
 *   <li>Stub out features that don't apply in the browser (e.g., thread queue,
 *       native window management, real filesystem)</li>
 *   <li>Return browser-appropriate values (e.g., isSameThread() always true,
 *       execute() runs immediately, no thread queue needed)</li>
 * </ul>
 *
 * <p>The game loop is requestAnimationFrame-based, not the traditional
 * MC while-loop. EaglerCraft.tick() calls our tick(), and EaglerCraft's
 * renderFrame() calls our render().</p>
 */
public class Minecraft implements com.mojang.blaze3d.platform.WindowEventHandler {

        // ========== Static Singleton ==========
        private static Minecraft instance;

        // ========== Core Fields ==========
        private final GameConfig gameConfig;
        private final User user;
        private final Window window;
        private final Options options;
        private final GameRenderer gameRenderer;
        private final LevelRenderer levelRenderer;
        private final KeyboardHandler keyboardHandler;
        private final MouseHandler mouseHandler;
        public final Font font;
        private final TextureManager textureManager;
        private final SoundManager soundManager;
        private final Gui gui;

        // ========== Game State ==========
        public Screen screen;
        private Screen overlay;
        private int fps;
        private long currentTime;
        private boolean running = true;
        private boolean pause = false;
        private int rightClickDelay;
        private int missTime;
        private Object hitResult;

        // ========== Level/World State ==========
        private Object level; // ClientLevel
        private Object cameraEntity; // Entity
        private Object connection; // ClientPacketListener
        private Object singleplayerServer; // IntegratedServer stub

        // ========== Render Target ==========
        private RenderTarget mainRenderTarget;

        /** Number of frames rendered (for FPS counter). */
        private int frameCount;
        private long lastFpsTime;
        private int currentFps;

        public Minecraft(GameConfig gameConfig) {
                Minecraft.setInstance(this);
                this.gameConfig = gameConfig;

                ClientMain.log("[Minecraft] Constructing Minecraft instance...");

                try {
                        // Step 1: Initialize User from config
                        this.user = gameConfig.user != null ? gameConfig.user.user : createDefaultUser();
                        ClientMain.log("[Minecraft] User: " + this.user.getName());

                        // Step 2: Create the Window (backed by browser canvas)
                        this.window = new Window(this, null,
                                gameConfig.display, "EaglerCraft " + EaglerCraftConfig.VERSION);
                        ClientMain.log("[Minecraft] Window: " + this.window.getWidth() + "x" + this.window.getHeight());

                        // Step 2b: pick the GUI scale (vanilla auto rule: largest
                        // integer scale that still shows a 320x240 GUI)
                        this.window.setGuiScale(this.window.calculateScale(Integer.MAX_VALUE, false));
                        ClientMain.log("[Minecraft] GUI scale: " + this.window.getGuiScale()
                                + " -> " + this.window.getGuiScaledWidth() + "x" + this.window.getGuiScaledHeight());

                        // Step 3: Initialize Options
                        this.options = new Options(this, new java.io.File("/eaglercraft"));
                        ClientMain.log("[Minecraft] Options loaded");

                        // Step 4: Create subsystem stubs
                        this.textureManager = new TextureManager();
                        this.soundManager = new SoundManager();
                        this.font = new Font();
                        this.keyboardHandler = new KeyboardHandler(this);
                        this.mouseHandler = new MouseHandler(this);
                        this.gameRenderer = new GameRenderer(this);
                        this.levelRenderer = new LevelRenderer(this);
                        this.gui = new Gui(this);

                        // Route raw DOM keyboard events into the key handler:
                        // printable keys become charTyped on the current screen
                        // (EditBox text input), everything else goes through
                        // keyPress -> screen.keyPressed (ESC, arrows, ...).
                        net.lax1dude.eaglercraft.v2_6.internal.PlatformInput.setKeyEventObserver(
                                (keyCode, keyStr, down) -> {
                                        if (!down) {
                                                this.keyboardHandler.keyPress(0L, keyCode, 0, 0, 0);
                                                return;
                                        }
                                        if (keyStr != null && keyStr.length() == 1 && keyCode >= 30) {
                                                // Printable character (codes < 30 are
                                                // control keys even when keyStr is 1 char)
                                                this.keyboardHandler.charTyped(0L,
                                                                keyStr.charAt(0), 0);
                                        }
                                        this.keyboardHandler.keyPress(0L, keyCode, 0, 1, 0);
                                });

                        // Step 5: Create main render target
                        this.mainRenderTarget = new RenderTarget(
                                this.window.getWidth(), this.window.getHeight(), true, false);
                        this.mainRenderTarget.create(
                                this.window.getWidth(), this.window.getHeight(), true, false);

                        // Step 6: Set initial screen. Must go through
                        // setScreen (not a bare field assignment) so the
                        // screen's init() runs and its widgets (the
                        // Singleplayer button) actually get created.
                        this.setScreen(new TitleScreen());

                        ClientMain.log("[Minecraft] Construction complete");

                } catch (Throwable t) {
                        ClientMain.error("[Minecraft] Construction failed: " + t.getMessage());
                        StackTraceElement[] stack = t.getStackTrace();
                        for (int i = 0; i < Math.min(stack.length, 8); i++) {
                                ClientMain.error("  at " + stack[i].toString());
                        }
                        // Set minimal stubs so the game doesn't NPE
                        throw new RuntimeException("Minecraft construction failed", t);
                }
        }

        /**
         * Creates a default offline-mode user when no config is provided.
         */
        private static User createDefaultUser() {
                return new User(
                        EaglerProfile.getUsername(),
                        UUID.randomUUID(),
                        "0",
                        Optional.empty(),
                        Optional.empty()
                );
        }

        // ========== Static Accessors ==========

        public static Minecraft getInstance() {
                return instance;
        }

        public static void setInstance(Minecraft mc) {
                instance = mc;
        }

        // ========== Game Loop Methods ==========

        /**
         * The main game loop method. In vanilla MC, this is a blocking while-loop.
         * In EaglerCraft, we DON'T use this method because our game loop is
         * requestAnimationFrame-based. Instead, tick() and render() are called
         * separately by the EaglerCraft adapter.
         *
         * <p>This method exists so TeaVM can resolve the reference from MC code,
         * but it should not be called in the browser.</p>
         */
        public void run() {
                ClientMain.log("[Minecraft] run() called - using browser rAF loop instead");
                // In the browser, the game loop is managed by ClientMain/EaglerCraft
                // This method is intentionally left empty
        }

        /**
         * Updates game state for one tick (20 TPS).
         * Called by EaglerCraft.gameLogicTick().
         */
        public void tick() {
                try {
                        // Update timing
                        this.currentTime = PlatformRuntime.getCurrentTimeMillis();

                        // Re-sync with the browser canvas. The cache is empty
                        // (0x0) while the page is still laying out during
                        // construction, so the first real frame sizes the
                        // canvas and everything downstream (GUI scale, menu
                        // widgets, render target) re-initializes here. This
                        // also picks up real browser resizes within a tick.
                        this.syncCanvasSize();

                        // Tick input handlers
                        if (this.keyboardHandler != null) {
                                this.keyboardHandler.tick();
                        }
                        if (this.mouseHandler != null) {
                                this.mouseHandler.tick();
                        }

                        // Tick current screen if present
                        if (this.screen != null) {
                                this.screen.tick();
                        }

                        // Tick the loaded world (advances the game clock)
                        if (this.level instanceof ClientLevel clientLevel) {
                                clientLevel.tick();
                        }

                        // Tick the HUD
                        if (this.gui != null) {
                                this.gui.tick();
                        }

                        // Update FPS counter
                        this.frameCount++;
                        long now = PlatformRuntime.getCurrentTimeMillis();
                        if (now - this.lastFpsTime >= 1000L) {
                                this.currentFps = this.frameCount;
                                this.frameCount = 0;
                                this.lastFpsTime = now;
                        }

                } catch (Throwable t) {
                        ClientMain.warn("[Minecraft] tick() error: " + t.getMessage());
                }
        }

        /**
         * Renders one frame.
         * Called by EaglerCraft.renderFrame().
         *
         * @param renderLevel Whether to render the 3D world (false when paused/loading)
         */
        public void render(boolean renderLevel) {
                try {
                        // Delegate to GameRenderer
                        if (this.gameRenderer != null) {
                                this.gameRenderer.render(PlatformRuntime.getDeltaTimeMs() / 1000.0f,
                                        PlatformRuntime.getCurrentTimeMillis(), renderLevel);
                        }
                } catch (Throwable t) {
                        ClientMain.warn("[Minecraft] render() error: " + t.getMessage());
                }
        }

        // ========== Display / Window ==========

        @Override
        public void resizeDisplay() {
                int width = PlatformRuntime.getCanvasDrawableWidth();
                int height = PlatformRuntime.getCanvasDrawableHeight();
                if (width <= 0 || height <= 0 || this.window == null) return;

                // Pull the fresh canvas dimensions into the Window. (Vanilla
                // has the GLFW framebuffer callback do this; here the browser
                // cache is the source of truth, refreshed per frame by
                // PlatformRuntime.update.) Do NOT call window.onResize from
                // here: it invokes eventHandler.resizeDisplay() and would
                // recurse forever.
                this.window.refreshCachedSize();

                // Recompute the GUI scale against the real dimensions
                this.window.setGuiScale(this.window.calculateScale(Integer.MAX_VALUE, false));

                // Resize the render target
                if (this.mainRenderTarget != null) {
                        this.mainRenderTarget.resize(width, height);
                }

                // Re-initialize the current screen (vanilla re-runs init on
                // resize — this is what repositions the menu widgets)
                if (this.screen != null) {
                        this.screen.resize(this,
                                this.window.getGuiScaledWidth(), this.window.getGuiScaledHeight());
                }
        }

        /**
         * Applies a resize whenever the browser canvas no longer matches
         * the Window's cached dimensions (the layout-settling transition
         * and real browser resizes alike). Cheap no-op once settled.
         */
        private void syncCanvasSize() {
                int drawableW = PlatformRuntime.getCanvasDrawableWidth();
                int drawableH = PlatformRuntime.getCanvasDrawableHeight();
                if (drawableW <= 0 || drawableH <= 0 || this.window == null) return;

                int newScale = this.window.calculateScale(Integer.MAX_VALUE, false);
                if (drawableW == this.window.getWidth() && drawableH == this.window.getHeight()
                        && newScale == this.window.getGuiScale()) {
                        return; // settled
                }

                ClientMain.log("[Minecraft] Canvas settled at " + drawableW + "x" + drawableH
                        + ", GUI scale " + newScale + " -> re-initializing screens");
                resizeDisplay();
        }

        /**
         * Creates the display. In the browser, this is a no-op because
         * ClientMain already created the canvas and WebGL2 context.
         */
        public void createDisplay() {
                // no-op in browser
        }

        /**
         * Updates the display (swap buffers). In the browser, WebGL
         * automatically presents at the next rAF, so this is a no-op.
         */
        public void updateDisplay() {
                if (this.window != null) {
                        this.window.updateDisplay();
                }
        }

        // ========== Thread / Queue ==========

        /**
         * Returns true because the browser is always single-threaded.
         * MC uses this to check if code is running on the render thread.
         */
        public boolean isSameThread() {
                return true;
        }

        /**
         * Executes a Runnable immediately. In vanilla MC, this queues work
         * to the render thread, but in the browser there's only one thread.
         */
        public void execute(Runnable runnable) {
                if (runnable != null) {
                        runnable.run();
                }
        }

        /**
         * Same as execute() - runs immediately in the single browser thread.
         */
        public void tell(Runnable runnable) {
                execute(runnable);
        }

        /**
         * Same as execute() - runs immediately.
         */
        public void enqueueOperation(Runnable runnable) {
                execute(runnable);
        }

        // ========== Unicode / Fonts ==========

        public boolean isEnforceUnicode() {
                return false;
        }

        public Font getFontManager() {
                return this.font;
        }

        // ========== Resource Managers ==========

        public TextureManager getTextureManager() {
                return this.textureManager;
        }

        public SoundManager getSoundManager() {
                return this.soundManager;
        }

        // ========== Window ==========

        public Window getWindow() {
                return this.window;
        }

        // ========== Options ==========

        public Options getOptions() {
                return this.options;
        }

        // ========== User / Profile ==========

        public User getUser() {
                return this.user;
        }

        public GameProfile getGameProfile() {
                return this.user != null ? this.user.getProfile() : null;
        }

        public CompletableFuture<GameProfile> getGameProfileFuture() {
                return CompletableFuture.completedFuture(getGameProfile());
        }

        // ========== Connection / Server ==========

        public Object getConnection() {
                return this.connection;
        }

        public void setConnection(Object connection) {
                this.connection = connection;
        }

        public Object getSingleplayerServer() {
                return this.singleplayerServer;
        }

        public boolean hasSingleplayerServer() {
                return this.singleplayerServer != null;
        }

        public boolean isSingleplayer() {
                return this.singleplayerServer != null;
        }

        // ========== Version ==========

        public String getLaunchedVersion() {
                return EaglerCraftConfig.MINECRAFT_VERSION;
        }

        public String getVersionType() {
                return EaglerCraftConfig.BRAND;
        }

        // ========== FPS / Timing ==========

        public int getFps() {
                return this.currentFps;
        }

        public long getCurrentTime() {
                return PlatformRuntime.getCurrentTimeMillis();
        }

        // ========== Screen Management ==========

        public void setScreen(Screen screen) {
                if (this.screen != null) {
                        this.screen.removed();
                }
                this.screen = screen;
                if (screen != null) {
                        screen.init(this, this.window.getGuiScaledWidth(), this.window.getGuiScaledHeight());
                }
        }

        public Screen getScreen() {
                return this.screen;
        }

        /**
         * Displays a client message in the chat area.
         * In EaglerCraft, logs to console as we don't have chat UI yet.
         */
        public void displayClientMessage(Object component) {
                if (component != null) {
                        ClientMain.log("[MC Chat] " + component.toString());
                }
        }

        // ========== Rendering ==========

        public GameRenderer getGameRenderer() {
                return this.gameRenderer;
        }

        public LevelRenderer getLevelRenderer() {
                return this.levelRenderer;
        }

        public RenderTarget getMainRenderTarget() {
                return this.mainRenderTarget;
        }

        public boolean getRenderingStatus() {
                return true;
        }

        // ========== Renderer Stubs ==========

        public Object getEntityRenderDispatcher() {
                return null; // TODO: stub when needed by TeaVM call graph
        }

        public Object getItemRenderer() {
                return null;
        }

        public Object getBlockRenderer() {
                return null;
        }

        public Object getModelManager() {
                return null;
        }

        public Object getItemModelMesher() {
                return null;
        }

        public Object getBlockColors() {
                return null;
        }

        public Object getItemColors() {
                return null;
        }

        // ========== Overlay ==========

        public Screen getOverlay() {
                return this.overlay;
        }

        public void setOverlay(Screen overlay) {
                this.overlay = overlay;
        }

        // ========== Services (all stubbed) ==========

        public Object getProfileKeyPairManager() {
                return null;
        }

        public MinecraftSessionService getMinecraftSessionService() {
                return null; // No session service in browser
        }

        public Optional<?> getSocialInteractionsService() {
                return Optional.empty();
        }

        public Object getSkinManager() {
                return null;
        }

        // ========== Creative Mode ==========

        public List<?> getCreativeModeTabs() {
                return Collections.emptyList();
        }

        // ========== Search Trees ==========

        public void populateSearchTree(Object searchTree, List<?> items) {
                // no-op
        }

        public void createSearchTrees() {
                // no-op
        }

        public Object getSearchTree(Object key) {
                return null;
        }

        // ========== Multiplayer / Chat ==========

        public Optional<?> getMultiplayerBanService() {
                return Optional.empty();
        }

        public boolean allowsMultiplayer() {
                return true;
        }

        public boolean allowsChat() {
                return true;
        }

        public boolean isBlocked(String uuid) {
                return false;
        }

        public boolean shouldBlockMessage(String uuid) {
                return false;
        }

        // ========== Screens ==========

        public void addInitialScreens(Set<?> screens) {
                // no-op
        }

        public Object getLevelSource() {
                return null;
        }

        public Object createWorldOpenFlows() {
                return null;
        }

        /**
         * Enters a world the Singleplayer way: constructs the client-side
         * {@link ClientLevel}, swaps it in, closes the menu, and leaves
         * the mouse free (the browser grants pointer lock only on a real
         * canvas click, which EaglerCraft requests on the next click while
         * in world).
         *
         * <p>The e2e telemetry bridge exposes this state as
         * {@code window.__eaglercraftGame.inWorld}.</p>
         */
        public void doWorldLoad(String levelName) {
                if (this.isLevelLoaded()) {
                        ClientMain.log("[Minecraft] doWorldLoad ignored: already in a world");
                        return;
                }

                ClientMain.log("[Minecraft] Loading world: " + levelName);
                try {
                        ClientLevel clientLevel = new ClientLevel(levelName, "minecraft:overworld");
                        this.setLevel(clientLevel);
                        this.setScreen(null); // no menu = in-game; the HUD renders
                        this.missTime = 0;
                        ClientMain.log("[Minecraft] World loaded: " + clientLevel
                                + " (HUD active)");
                } catch (Throwable t) {
                        ClientMain.error("[Minecraft] World load failed: " + t.getMessage());
                        StackTraceElement[] stack = t.getStackTrace();
                        for (int i = 0; i < Math.min(stack.length, 8); i++) {
                                ClientMain.error("  at " + stack[i].toString());
                        }
                        throw new RuntimeException("World load failed", t);
                }
        }

        // ========== Level / World ==========

        public void setLevel(Object clientLevel) {
                this.level = clientLevel;
        }

        public Object getLevel() {
                return this.level;
        }

        /**
         * True once the client is in a loaded world (the in-game HUD stage).
         * The e2e telemetry bridge exposes this as
         * {@code window.__eaglercraftGame.inWorld}.
         */
        public boolean isLevelLoaded() {
                return this.level != null;
        }

        // ========== Camera ==========

        public Object getCameraEntity() {
                return this.cameraEntity;
        }

        public void setCameraEntity(Object entity) {
                this.cameraEntity = entity;
        }

        public Object getCrosshairVec() {
                return null;
        }

        // ========== Input Stubs ==========

        public void startUseItem() {
                // stub
        }

        public void startAttack() {
                // stub
        }

        public void handleKeybindings() {
                // stub - handled by KeyboardHandler/MouseHandler
        }

        // ========== Narrator / Toast / Tutorial ==========

        public Object getNarrator() {
                return null;
        }

        public Object getToastComponent() {
                return null;
        }

        public Object getTutorial() {
                return null;
        }

        public Object getProgressListener() {
                return null;
        }

        // ========== Debug ==========

        public boolean isDemo() {
                return false;
        }

        public Object getDebugOverlay() {
                return null;
        }

        public Object getClientTelemetryManager() {
                return null;
        }

        // ========== Multiplayer Screen ==========

        public Screen getMultiPlayerScreen() {
                return null;
        }

        public TitleScreen createTitleScreen() {
                return new TitleScreen();
        }

        // ========== Misc ==========

        public void queueSectionVisibility(Object section, boolean visible) {
                // no-op
        }

        // ========== Shutdown ==========

        public void destroy() {
                ClientMain.log("[Minecraft] destroy() called");
                this.running = false;
        }

        public void close() {
                ClientMain.log("[Minecraft] close() called - shutting down");
                this.running = false;

                // Save options
                if (this.options != null) {
                        this.options.save();
                }

                // Clean up resources
                if (this.mainRenderTarget != null) {
                        this.mainRenderTarget.destroyBuffers();
                }
        }

        /**
         * Returns the KeyboardHandler for this Minecraft instance.
         */
        public KeyboardHandler getKeyboardHandler() {
                return this.keyboardHandler;
        }

        /**
         * Returns the MouseHandler for this Minecraft instance.
         */
        public MouseHandler getMouseHandler() {
                return this.mouseHandler;
        }

        /**
         * Returns the Gui (HUD overlay) for this Minecraft instance.
         */
        public Gui getGui() {
                return this.gui;
        }

        /**
         * Returns whether the game is currently running.
         */
        public boolean isRunning() {
                return this.running;
        }

        /**
         * Sets the game running state.
         */
        public void setRunning(boolean running) {
                this.running = running;
        }

        /**
         * Returns the game configuration.
         */
        public GameConfig getGameConfig() {
                return this.gameConfig;
        }
}
