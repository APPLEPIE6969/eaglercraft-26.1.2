package net.lax1dude.eaglercraft.v2_6.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.opts.IClientConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformAudio;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformApplication;
import net.lax1dude.eaglercraft.v2_6.EaglerCraft;

/**
 * Client initialization and startup logic for EaglerCraft 26.1.2 TeaVM platform.
 * Handles browser feature detection, WebGL2 context creation, and client bootstrap.
 *
 * <p>Key 26.1.2 improvements:</p>
 * <ul>
 *   <li>WebGL2 native (no WebGL1 fallback)</li>
 *   <li>Modern browser feature detection</li>
 *   <li>OffscreenCanvas support for integrated server</li>
 *   <li>Performance API for frame timing</li>
 *   <li>Page Visibility API for auto-pause</li>
 * </ul>
 */
public class ClientMain {

        private static final Window window = Window.current();
        private static final HTMLDocument document = HTMLDocument.current();

        /** The main game canvas element. */
        static HTMLCanvasElement canvas;

        /** The WebGL2 rendering context. */
        static WebGL2RenderingContext webgl;

        /** The client configuration loaded from the bootstrap page. */
        public static IClientConfig config;

        /** Whether the client has been initialized. */
        private static boolean initialized = false;

        /** Whether the client is running in a dedicated worker. */
        static boolean isDedicatedWorker = false;

        /** Whether OffscreenCanvas is available for the integrated server. */
        public static boolean offscreenCanvasSupported = false;

        /** Whether the Pointer Lock API 2.0 is available. */
        public static boolean pointerLockSupported = false;

        /** Whether the Gamepad API is available. */
        public static boolean gamepadSupported = false;

        /** Whether the Web Audio API is available. */
        public static boolean audioSupported = false;

        /** Whether the Performance API is available. */
        static boolean performanceSupported = false;

        /** Whether the Clipboard API (async) is available. */
        public static boolean clipboardSupported = false;

        /** Whether the Fullscreen API is available. */
        public static boolean fullscreenSupported = false;

        /** Whether WebCodecs API is available. */
        static boolean webCodecsSupported = false;

        /** Whether WebGPU is available (for future use). */
        static boolean webGPUSupported = false;

        /** Whether the Screen Orientation API is available. */
        public static boolean screenOrientationSupported = false;

        /** Whether the Page Visibility API is available. */
        public static boolean pageVisibilitySupported = false;

        /** Frame timing via Performance API. */
        static long lastFrameTime = 0L;

        /** The animation frame callback ID for cancellation. */
        private static int animationFrameId = 0;

        /** Whether the EaglerCraft adapter layer has been initialized. */
        private static boolean eaglercraftInitialized = false;

        /** Callback interface for the game loop tick. */
        @JSFunctor
        public interface TickFunctor extends JSObject {
                void tick();
        }

        private static TickFunctor tickFunctor = null;

        /**
         * Main entry point called from MainClass.main().
         * Performs browser feature detection, creates the canvas and WebGL2 context,
         * then starts the game loop.
         */
        public static void _main() {
                if (initialized) {
                        return;
                }

                try {
                        log("[ClientMain] _main() started — loading config...");
                        config = loadConfig();
                        log("[ClientMain] Config loaded. Detecting browser features...");

                        detectBrowserFeatures();
                        log("[ClientMain] Features detected. Creating canvas...");

                        if (webgl == null) {
                                createCanvasAndContext();
                        }
                        log("[ClientMain] Canvas created. WebGL2: " + (webgl != null));

                        if (webgl == null) {
                                showCrashScreen("WebGL2 is not supported by your browser!\n\n"
                                                + "EaglerCraft 26.1.2 requires WebGL2.\n"
                                                + "Please use a modern browser (Chrome 56+, Firefox 51+, Edge 79+, Safari 15+).");
                                return;
                        }

                        log("[ClientMain] Initializing platform subsystems...");
                        PlatformOpenGL._init();
                        log("[ClientMain] PlatformOpenGL done");
                        PlatformInput._init();
                        log("[ClientMain] PlatformInput done");
                        PlatformRuntime._init();
                        log("[ClientMain] PlatformRuntime done");
                        PlatformAudio._init();
                        log("[ClientMain] PlatformAudio done");
                        PlatformApplication._init();
                        log("[ClientMain] PlatformApplication done");

                        initialized = true;
                        log("[ClientMain] Starting game loop...");

                        startGameLoop();

                        // Boot the asset bundle download in the background. The client
                        // runs fine without it (fonts/lang fall back to defaults), so a
                        // missing or corrupt assets.epk must never block or crash boot.
                        beginAssetBundleLoad();

                } catch (Throwable t) {
                        log("[ClientMain] CRASH: " + t.getClass().getName() + ": " + t.getMessage());
                        showCrashScreen("Failed to initialize EaglerCraft 26.1.2!\n\n" + t.getMessage());
                }
        }

        /**
         * Detects available browser features and APIs.
         * Results are stored in static boolean fields for use by Platform* classes.
         */
        private static void detectBrowserFeatures() {
                offscreenCanvasSupported = checkOffscreenCanvas();
                pointerLockSupported = checkPointerLock();
                gamepadSupported = checkGamepad();
                audioSupported = checkWebAudio();
                performanceSupported = checkPerformance();
                clipboardSupported = checkClipboard();
                fullscreenSupported = checkFullscreen();
                webCodecsSupported = checkWebCodecs();
                webGPUSupported = checkWebGPU();
                screenOrientationSupported = checkScreenOrientation();
                pageVisibilitySupported = checkPageVisibility();
        }

        /**
         * Creates the game canvas element and initializes the WebGL2 context.
         * Uses context attributes from the client configuration if available.
         */
        private static void createCanvasAndContext() {
                String containerId = "game_frame";
                if (config != null && config.getContainer() != null) {
                        containerId = config.getContainer();
                }

                HTMLElement container = document.getElementById(containerId);
                HTMLElement appendTarget = null;

                // A <canvas> container swallows the game canvas as fallback
                // content: browsers never render a canvas's child elements,
                // so the game canvas would keep a 0x0 layout box forever -
                // no size, no mouse events, and the platform's canvas cache
                // would stay empty. When the container is a canvas (the
                // shell ships #game_frame as one), hide it and overlay the
                // real canvas on the viewport instead; proper element
                // containers (a <div>) keep the classic contain behavior.
                boolean containerIsCanvas = false;
                if (container != null) {
                        containerIsCanvas = isCanvasElement(container);
                }
                if (container == null || containerIsCanvas) {
                        if (containerIsCanvas) {
                                container.getStyle().setProperty("display", "none");
                        }
                        appendTarget = document.getBody();
                } else {
                        appendTarget = container;
                }

                // Create the canvas element
                canvas = (HTMLCanvasElement) document.createElement("canvas");
                canvas.setId("EaglerCraftX_26_1_2_Canvas");
                if (appendTarget == container) {
                        canvas.getStyle().setProperty("width", "100%");
                        canvas.getStyle().setProperty("height", "100%");
                } else {
                        // Overlaying the viewport directly
                        canvas.getStyle().setProperty("position", "fixed");
                        canvas.getStyle().setProperty("top", "0");
                        canvas.getStyle().setProperty("left", "0");
                        canvas.getStyle().setProperty("width", "100vw");
                        canvas.getStyle().setProperty("height", "100vh");
                        // Below the shell overlays (loading 1000, title 100,
                        // error 2000), above the static page background
                        canvas.getStyle().setProperty("z-index", "1");
                }
                canvas.getStyle().setProperty("display", "block");
                canvas.getStyle().setProperty("outline", "none");
                canvas.getStyle().setProperty("image-rendering", "pixelated");
                canvas.setAttribute("tabindex", "0");
                canvas.setAttribute("autocomplete", "off");

                appendTarget.appendChild(canvas);

                // Create WebGL2 context with attributes
                webgl = createWebGL2Context(canvas);
        }

        /**
         * Returns whether the element is a {@code <canvas>}. Plain JS on
         * purpose: the DOM API surface used here must not depend on any
         * specific TeaVM DOM interface beyond HTMLElement.
         */
        @JSBody(params = { "el" }, script =
                "return !!(el && el.tagName && String(el.tagName).toLowerCase() === 'canvas');")
        private static native boolean isCanvasElement(HTMLElement el);

        /**
         * Creates a WebGL2 rendering context on the given canvas.
         * Uses configuration attributes if provided, otherwise uses defaults
         * optimized for EaglerCraft 26.1.2.
         */
        private static WebGL2RenderingContext createWebGL2Context(HTMLCanvasElement canvasElement) {
                // Always use default attributes — the config's getGLContextAttributes()
                // returns a TeaVM JSO wrapper that doesn't have .alpha property
                JSObject attrs = createDefaultContextAttributes();
                return tryCreateWebGL2(canvasElement, attrs);
        }

        /**
         * Starts the game loop using requestAnimationFrame for vsync.
         */
        private static void startGameLoop() {
                lastFrameTime = getCurrentTimeMillis();

                tickFunctor = () -> {
                        if (!initialized) return;

                        long currentTime = getCurrentTimeMillis();
                        long deltaTime = currentTime - lastFrameTime;
                        lastFrameTime = currentTime;

                        // Update frame timing in PlatformRuntime
                        PlatformRuntime.update(deltaTime);

                        // Initialize the EaglerCraft adapter layer on the first frame
                        if (!eaglercraftInitialized) {
                                log("[ClientMain] First frame — calling EaglerCraft.initialize()...");
                                eaglercraftInitialized = true;
                                EaglerCraft.initialize();
                                log("[ClientMain] EaglerCraft.initialize() returned!");
                        }

                        // Tick the game
                        EaglerCraft.tick();

                        // Schedule the next frame
                        animationFrameId = requestAnimationFrame(tickFunctor);
                };

                animationFrameId = requestAnimationFrame(tickFunctor);
        }

        /**
         * Kicks off the background download of the asset bundle (assets.epk).
         * Non-fatal on any failure: the client boots and renders with default
         * assets when the bundle is unavailable, and EPKLoader reports the
         * outcome through the browser console.
         */
        private static void beginAssetBundleLoad() {
                try {
                        log("[ClientMain] Requesting asset bundle (assets.epk)...");
                        EPKLoader.loadEPK("assets.epk",
                                        data -> log("[ClientMain] Asset bundle ready: "
                                                        + data.getByteLength() + " bytes"),
                                        (loaded, total) -> {
                                                if (total > 0 && (loaded & 0x3FFFFF) < 65536) {
                                                        log("[ClientMain] Asset bundle: " + (loaded >> 20)
                                                                        + " / " + (total >> 20) + " MiB");
                                                }
                                        });
                } catch (Throwable t) {
                        warn("[ClientMain] Asset bundle load could not start: " + t);
                }
        }

        /**
         * Stops the game loop.
         */
        public static void stopGameLoop() {
                if (animationFrameId != 0) {
                        cancelAnimationFrame(animationFrameId);
                        animationFrameId = 0;
                }
                initialized = false;
        }

        /**
         * Shows a crash screen with the given error message.
         * Replaces the entire page content with a styled error display.
         */
        public static void showCrashScreen(String message) {
                showCrashScreen0(message);
        }

        // ========== Native JS Methods ==========

        /**
         * Loads the client configuration from window.__eaglercraftXClientConfig.
         */
        @JSBody(params = {}, script = "return window.eaglercraftXOpts || window.__eaglercraftXClientConfig || null;")
        private static native IClientConfig loadConfig();

        /**
         * Checks if OffscreenCanvas is available.
         */
        @JSBody(params = {}, script = "return typeof OffscreenCanvas !== 'undefined';")
        private static native boolean checkOffscreenCanvas();

        /**
         * Checks if the Pointer Lock API is available.
         */
        @JSBody(params = {}, script = "return !!(document.exitPointerLock || document.mozExitPointerLock);")
        private static native boolean checkPointerLock();

        /**
         * Checks if the Gamepad API is available.
         */
        @JSBody(params = {}, script = "return !!navigator.getGamepads;")
        private static native boolean checkGamepad();

        /**
         * Checks if the Web Audio API is available.
         */
        @JSBody(params = {}, script = "return !!(window.AudioContext || window.webkitAudioContext);")
        private static native boolean checkWebAudio();

        /**
         * Checks if the Performance API is available.
         */
        @JSBody(params = {}, script = "return !!(window.performance && window.performance.now);")
        private static native boolean checkPerformance();

        /**
         * Checks if the async Clipboard API is available.
         */
        @JSBody(params = {}, script = "return !!(navigator.clipboard && navigator.clipboard.readText);")
        private static native boolean checkClipboard();

        /**
         * Checks if the Fullscreen API is available.
         */
        @JSBody(params = {}, script = "return !!(document.documentElement.requestFullscreen || document.documentElement.webkitRequestFullscreen);")
        private static native boolean checkFullscreen();

        /**
         * Checks if the WebCodecs API is available.
         */
        @JSBody(params = {}, script = "return typeof VideoDecoder !== 'undefined';")
        private static native boolean checkWebCodecs();

        /**
         * Checks if WebGPU is available.
         */
        @JSBody(params = {}, script = "return !!navigator.gpu;")
        private static native boolean checkWebGPU();

        /**
         * Checks if the Screen Orientation API is available.
         */
        @JSBody(params = {}, script = "return !!screen.orientation;")
        private static native boolean checkScreenOrientation();

        /**
         * Checks if the Page Visibility API is available.
         */
        @JSBody(params = {}, script = "return typeof document.hidden !== 'undefined';")
        private static native boolean checkPageVisibility();

        /**
         * Tries to create a WebGL2 context with the given attributes.
         * Returns null if WebGL2 is not available.
         */
        @JSBody(params = { "canvas", "attrs" }, script = ""
                        + "try {"
                        + "  var ctx = canvas.getContext('webgl2', attrs);"
                        + "  if (!ctx) ctx = canvas.getContext('experimental-webgl2', attrs);"
                        + "  return ctx;"
                        + "} catch(e) {"
                        + "  return null;"
                        + "}")
        private static native WebGL2RenderingContext tryCreateWebGL2(HTMLCanvasElement canvas, JSObject attrs);

        /**
         * Creates default WebGL2 context attributes optimized for EaglerCraft 26.1.2.
         */
        @JSBody(params = {}, script = ""
                        + "return {"
                        + "  alpha: false,"
                        + "  depth: true,"
                        + "  stencil: true,"
                        + "  antialias: false,"
                        + "  premultipliedAlpha: false,"
                        + "  preserveDrawingBuffer: false,"
                        + "  powerPreference: 'high-performance',"
                        + "  failIfMajorPerformanceCaveat: false,"
                        + "  desynchronized: true"
                        + "};")
        private static native JSObject createDefaultContextAttributes();

        /**
         * Creates WebGL2 context attributes from the client configuration.
         */
        @JSBody(params = { "glConfig" }, script = ""
                        + "return {"
                        + "  alpha: glConfig.alpha !== undefined ? glConfig.alpha : false,"
                        + "  depth: glConfig.depth !== undefined ? glConfig.depth : true,"
                        + "  stencil: glConfig.stencil !== undefined ? glConfig.stencil : true,"
                        + "  antialias: glConfig.antialias !== undefined ? glConfig.antialias : false,"
                        + "  premultipliedAlpha: glConfig.premultipliedAlpha !== undefined ? glConfig.premultipliedAlpha : false,"
                        + "  preserveDrawingBuffer: glConfig.preserveDrawingBuffer !== undefined ? glConfig.preserveDrawingBuffer : false,"
                        + "  powerPreference: glConfig.powerPreference || 'high-performance',"
                        + "  failIfMajorPerformanceCaveat: glConfig.failIfMajorPerformanceCaveat || false,"
                        + "  desynchronized: glConfig.desynchronized !== undefined ? glConfig.desynchronized : true"
                        + "};")
        private static native JSObject createContextAttributes(Object glConfig);

        /**
         * Requests the next animation frame.
         */
        @JSBody(params = { "callback" }, script = "return window.requestAnimationFrame(callback);")
        private static native int requestAnimationFrame(TickFunctor callback);

        /**
         * Cancels an animation frame request.
         */
        @JSBody(params = { "id" }, script = "window.cancelAnimationFrame(id);")
        private static native void cancelAnimationFrame(int id);

        /**
         * Gets the current time in milliseconds using the Performance API if available.
         */
        @JSBody(params = {}, script = ""
                        + "if (window.performance && window.performance.now) {"
                        + "  return BigInt(Math.floor(window.performance.now()));"
                        + "} else {"
                        + "  return BigInt(Date.now());"
                        + "}")
        private static native long getCurrentTimeMillis();

        /**
         * Shows a crash screen with the given message.
         * Creates a full-page overlay with the error details.
         */
        @JSBody(params = { "message" }, script = ""
                        + "var root = document.createElement('div');"
                        + "root.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:#000;color:#FFF;"
                        + "font-family:monospace;display:flex;align-items:center;justify-content:center;z-index:999999;';"
                        + "var box = document.createElement('div');"
                        + "box.style.cssText = 'max-width:80vw;max-height:80vh;overflow:auto;padding:32px;"
                        + "background:#111;border:2px solid #333;border-radius:8px;white-space:pre-wrap;word-break:break-word;';"
                        + "var title = document.createElement('h2');"
                        + "title.style.cssText = 'color:#F55;margin:0 0 16px 0;';"
                        + "title.textContent = 'EaglerCraft 26.1.2 Crashed!';"
                        + "var msg = document.createElement('p');"
                        + "msg.style.cssText = 'font-size:14px;line-height:1.6;';"
                        + "msg.textContent = message;"
                        + "box.appendChild(title);"
                        + "box.appendChild(msg);"
                        + "root.appendChild(box);"
                        + "document.body.innerHTML = '';"
                        + "document.body.appendChild(root);")
        private static native void showCrashScreen0(String message);

        /**
         * Called by the EaglerCraft adapter when the game is fully initialized.
         * Dispatches the eaglercraftReady event to the browser so the bootstrap
         * page knows the client is ready for interaction.
         */
        public static void __eaglercraftReady() {
                __eaglercraftReady0();
                log("[ClientMain] EaglerCraft ready event dispatched");
        }

        /**
         * Gets the WebGL2 context for use by PlatformOpenGL.
         */
        public static WebGL2RenderingContext getWebGL2() {
                return webgl;
        }

        /**
         * Gets the game canvas element.
         */
        public static HTMLCanvasElement getCanvas() {
                return canvas;
        }

        /**
         * Gets the client configuration.
         */
        public static IClientConfig getConfig() {
                return config;
        }

        /**
         * Checks if the client has been initialized.
         */
        public static boolean isInitialized() {
                return initialized;
        }

        /**
         * Logs a message to the browser console.
         */
        @JSBody(params = { "msg" }, script = "console.log(msg);")
        public static native void log(String msg);

        /**
         * Logs a warning to the browser console.
         */
        @JSBody(params = { "msg" }, script = "console.warn(msg);")
        public static native void warn(String msg);

        /**
         * Reloads the browser page. The browser-honest equivalent of vanilla's
         * {@code Minecraft.stop()} used by the title screen Quit button.
         */
        @JSBody(params = {}, script = "window.location.reload();")
        public static native void reloadPage();

        /**
         * Logs an error to the browser console.
         */
        @JSBody(params = { "msg" }, script = "console.error(msg);")
        public static native void error(String msg);

        /**
         * Dispatches the eaglercraftReady event to the browser.
         * Fires a custom event on the window object so the bootstrap page
         * can detect when the client is fully loaded and ready.
         */
        @JSBody(params = {}, script = ""
                        + "if (typeof CustomEvent !== 'undefined') {"
                        + "  window.dispatchEvent(new CustomEvent('eaglercraftReady'));"
                        + "}"
                        + "if (typeof window.__eaglercraftOnReady === 'function') {"
                        + "  window.__eaglercraftOnReady();"
                        + "}")
        private static native void __eaglercraftReady0();
}
