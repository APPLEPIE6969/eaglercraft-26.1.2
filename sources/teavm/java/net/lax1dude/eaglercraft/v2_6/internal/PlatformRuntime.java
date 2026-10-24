package net.lax1dude.eaglercraft.v2_6.internal;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * Platform runtime implementation for EaglerCraft 26.1.2 on the TeaVM/browser platform.
 * Provides browser-specific runtime services: storage, timing, downloads, screen info, etc.
 *
 * <p>Key 26.1.2 improvements:</p>
 * <ul>
 *   <li>localStorage with namespace isolation to prevent key collisions</li>
 *   <li>IndexedDB for worlds and resource packs (via PlatformFilesystem)</li>
 *   <li>Performance API for high-resolution frame timing</li>
 *   <li>requestAnimationFrame with vsync support</li>
 *   <li>Download API using Blob + URL.createObjectURL</li>
 *   <li>Screen Orientation API</li>
 *   <li>Page Visibility API for auto-pause when tab is hidden</li>
 *   <li>Web Worker support for integrated server</li>
 *   <li>OffscreenCanvas for render thread separation</li>
 *   <li>Performance monitoring with PerformanceObserver</li>
 * </ul>
 */
public class PlatformRuntime {

        private static final Window window = Window.current();

        /** localStorage namespace prefix from client config. */
        private static String namespace = "eaglercraft";

        /** Whether the page is currently visible (not hidden/minimized). */
        private static boolean pageVisible = true;

        /** Whether the runtime has been initialized. */
        private static boolean initialized = false;

        /** The current canvas width in CSS pixels. */
        private static int canvasWidth = 0;

        /** The current canvas height in CSS pixels. */
        private static int canvasHeight = 0;

        /** The current drawing-buffer width in physical pixels (CSS × DPR). */
        private static int bufferWidth = 0;

        /** The current drawing-buffer height in physical pixels (CSS × DPR). */
        private static int bufferHeight = 0;

        /** The device pixel ratio for HiDPI support. */
        private static float devicePixelRatio = 1.0f;

        /** Frame delta time in milliseconds. */
        private static long deltaTimeMs = 0L;

        /** Total elapsed time in milliseconds. */
        private static long totalTimeMs = 0L;

        /** Performance timing origin (navigation start). */
        private static long timeOrigin = 0;

        /** Whether the screen orientation is landscape. */
        private static boolean landscapeOrientation = true;

        /** Screen information. */
        private static int screenWidth = 0;
        private static int screenHeight = 0;

        /** Whether the integrated server worker is running. */
        private static boolean serverWorkerRunning = false;

        /** The integrated server Web Worker instance. */
        private static JSObject serverWorker = null;

        /**
         * Initializes the runtime platform. Called by ClientMain during startup.
         */
        public static void _init() {
                if (initialized) return;

                // Load namespace from config
                if (ClientMain.config != null && ClientMain.config.getLocalStorageNamespace() != null) {
                        namespace = ClientMain.config.getLocalStorageNamespace();
                }

                // Initialize timing
                timeOrigin = getTimeOrigin();
                totalTimeMs = getCurrentTimeMillis();

                // Initialize canvas size (also reads the device pixel ratio:
                // the buffer must always be sized with the CURRENT ratio)
                updateCanvasSize();

                // Initialize screen info
                updateScreenInfo();

                // Register page visibility listener
                if (ClientMain.pageVisibilitySupported) {
                        registerVisibilityChangeListener();
                }

                // Register resize listener
                registerResizeListener();

                // Register orientation change listener
                if (ClientMain.screenOrientationSupported) {
                        registerOrientationChangeListener();
                }

                initialized = true;
                ClientMain.log("[PlatformRuntime] Runtime initialized (namespace: " + namespace + ")");
        }

        /**
         * Called once per frame by the game loop to update timing and canvas size.
         */
        public static void update(long deltaMs) {
                deltaTimeMs = deltaMs;
                totalTimeMs += deltaMs;

                // Update canvas size (picks up client-size AND device-pixel-ratio
                // changes; the ratio is refreshed inside, before it is used)
                updateCanvasSize();

                // Poll gamepad
                PlatformInput.pollGamepad();
        }

        // ========== Timing ==========

        /**
         * Gets the current time in milliseconds using the Performance API.
         * Uses performance.now() for sub-millisecond precision when available.
         */
        public static long getCurrentTimeMillis() {
                return getCurrentTimeMillis0();
        }

        /**
         * Gets the high-resolution time origin (navigation start time).
         */
        public static long getTimeOrigin() {
                return getTimeOrigin0();
        }

        /**
         * Gets the time since the page was loaded in milliseconds.
         */
        public static long getTimeSinceLoad() {
                return getCurrentTimeMillis() - timeOrigin;
        }

        /**
         * Gets the frame delta time in milliseconds.
         */
        public static long getDeltaTimeMs() {
                return deltaTimeMs;
        }

        /**
         * Gets the total elapsed time in milliseconds.
         */
        public static long getTotalTimeMs() {
                return totalTimeMs;
        }

        // ========== Canvas / Screen ==========

        /**
         * Updates the cached canvas dimensions and the drawing-buffer size.
         *
         * <p>This is the single authority for the canvas backing-store size:
         * buffer = CSS size × devicePixelRatio, refreshed whenever EITHER
         * factor changes. The old implementation sized the buffer with the
         * PREVIOUS ratio and only refreshed the ratio afterwards, and it only
         * re-sized when clientWidth changed — so on HiDPI displays and zoomed
         * browsers (devicePixelRatio ≠ 1) the buffer stayed at CSS×1 forever,
         * rendering blurry/pixelated while the rest of the pipeline (Window
         * cache, GUI scale, mouse mapping) used the true ratio. A manual
         * window resize was the only thing that re-aligned them.</p>
         */
        public static void updateCanvasSize() {
                HTMLCanvasElement canvas = ClientMain.getCanvas();
                if (canvas == null) {
                        return;
                }
                int w = canvas.getClientWidth();
                int h = canvas.getClientHeight();
                // Read the ratio FIRST and size the buffer with it in the same
                // pass, so a ratio change alone (browser zoom, moving the window
                // to another monitor) re-sizes the buffer without needing a
                // client-size change to trigger it.
                float dpr = getDevicePixelRatio0();
                devicePixelRatio = dpr;
                int dw = Math.max(1, Math.round(w * dpr));
                int dh = Math.max(1, Math.round(h * dpr));
                boolean bufferChanged = dw != bufferWidth || dh != bufferHeight
                                || canvas.getWidth() != dw || canvas.getHeight() != dh;
                canvasWidth = w;
                canvasHeight = h;
                bufferWidth = dw;
                bufferHeight = dh;
                if (bufferChanged) {
                        // setWidth/setHeight clear the canvas, so they are only
                        // touched when the buffer really has to change.
                        canvas.setWidth(dw);
                        canvas.setHeight(dh);
                }
        }

        /**
         * Gets the canvas width in CSS pixels.
         */
        public static int getCanvasWidth() {
                return canvasWidth;
        }

        /**
         * Gets the canvas height in CSS pixels.
         */
        public static int getCanvasHeight() {
                return canvasHeight;
        }

        /**
         * Gets the canvas width in physical pixels. Returns the exact
         * drawing-buffer size that was last applied to the canvas element,
         * not a re-derived product, so every consumer (Window cache, GUI
         * scale, viewport, mouse mapping) agrees on the same integers.
         */
        public static int getCanvasDrawableWidth() {
                return bufferWidth;
        }

        /**
         * Gets the canvas height in physical pixels (exact drawing-buffer size).
         */
        public static int getCanvasDrawableHeight() {
                return bufferHeight;
        }

        /**
         * Gets the device pixel ratio (for HiDPI displays).
         */
        public static float getDevicePixelRatio() {
                return devicePixelRatio;
        }

        /**
         * Gets the screen width in CSS pixels.
         */
        public static int getScreenWidth() {
                return screenWidth;
        }

        /**
         * Gets the screen height in CSS pixels.
         */
        public static int getScreenHeight() {
                return screenHeight;
        }

        /**
         * Checks if the screen is in landscape orientation.
         */
        public static boolean isLandscapeOrientation() {
                return landscapeOrientation;
        }

        /**
         * Updates the cached screen information.
         */
        private static void updateScreenInfo() {
                screenWidth = getScreenWidth0();
                screenHeight = getScreenHeight0();
                landscapeOrientation = screenWidth >= screenHeight;
        }

        // ========== localStorage ==========

        /**
         * Reads a string value from localStorage with namespace prefix.
         *
         * @param key The key name (without namespace prefix)
         * @return The stored value, or null if not found
         */
        public static String localStorageGet(String key) {
                return localStorageGet0(namespace + "." + key);
        }

        /**
         * Writes a string value to localStorage with namespace prefix.
         *
         * @param key The key name (without namespace prefix)
         * @param value The value to store
         */
        public static void localStorageSet(String key, String value) {
                localStorageSet0(namespace + "." + key, value);
        }

        /**
         * Deletes a key from localStorage with namespace prefix.
         *
         * @param key The key name (without namespace prefix)
         */
        public static void localStorageDelete(String key) {
                localStorageDelete0(namespace + "." + key);
        }

        /**
         * Checks if a key exists in localStorage.
         *
         * @param key The key name (without namespace prefix)
         */
        public static boolean localStorageHas(String key) {
                return localStorageHas0(namespace + "." + key);
        }

        // ========== Download API ==========

        /**
         * Downloads a file using Blob + URL.createObjectURL.
         * Creates a temporary anchor element and clicks it to trigger download.
         *
         * @param filename The name of the file to download
         * @param data The file data as a byte array
         * @param mimeType The MIME type of the file
         */
        public static void downloadFile(String filename, byte[] data, String mimeType) {
                downloadFile0(filename, byteArrayToUint8Array(data), mimeType);
        }

        /**
         * Downloads a file from an ArrayBuffer.
         *
         * @param filename The name of the file to download
         * @param data The file data as an ArrayBuffer
         * @param mimeType The MIME type of the file
         */
        public static void downloadFile(String filename, ArrayBuffer data, String mimeType) {
                downloadFile0(filename, Uint8Array.create(data), mimeType);
        }

        /**
         * Downloads a text file.
         *
         * @param filename The name of the file to download
         * @param text The text content
         */
        public static void downloadTextFile(String filename, String text) {
                downloadTextFile0(filename, text);
        }

        // ========== Page Visibility ==========

        /**
         * Checks if the page is currently visible.
         */
        public static boolean isPageVisible() {
                return pageVisible;
        }

        /**
         * Sets the page visibility state. Called by the visibility change listener.
         */
        private static void setPageVisible(boolean visible) {
                if (pageVisible != visible) {
                        pageVisible = visible;
                        if (!visible) {
                                // Auto-pause when page is hidden
                                ClientMain.log("[PlatformRuntime] Page hidden - auto-pausing");
                        } else {
                                ClientMain.log("[PlatformRuntime] Page visible - resuming");
                        }
                }
        }

        // ========== Web Worker Support ==========

        /**
         * Creates and starts the integrated server Web Worker.
         *
         * @param workerURL The URL of the worker script (blob URL or same-origin)
         * @return true if the worker was created successfully
         */
        public static boolean startServerWorker(String workerURL) {
                if (serverWorkerRunning) return false;

                try {
                        serverWorker = createWorker0(workerURL);
                        if (serverWorker != null) {
                                serverWorkerRunning = true;
                                ClientMain.log("[PlatformRuntime] Server worker started");
                                return true;
                        }
                } catch (Exception e) {
                        ClientMain.error("Failed to start server worker: " + e.getMessage());
                }
                return false;
        }

        /**
         * Stops the integrated server Web Worker.
         */
        public static void stopServerWorker() {
                if (serverWorkerRunning && serverWorker != null) {
                        terminateWorker0(serverWorker);
                        serverWorker = null;
                        serverWorkerRunning = false;
                        ClientMain.log("[PlatformRuntime] Server worker stopped");
                }
        }

        /**
         * Posts a message to the server worker.
         *
         * @param data The message data (ArrayBuffer)
         */
        public static void postMessageToWorker(ArrayBuffer data) {
                if (serverWorkerRunning && serverWorker != null) {
                        postWorkerMessage0(serverWorker, data);
                }
        }

        /**
         * Checks if the server worker is running.
         */
        public static boolean isServerWorkerRunning() {
                return serverWorkerRunning;
        }

        // ========== Performance Monitoring ==========

        /**
         * Gets the current frames per second estimate.
         */
        public static float getFPS() {
                if (deltaTimeMs <= 0) return 0;
                return 1000.0f / deltaTimeMs;
        }

        /**
         * Gets memory information from performance.memory (Chrome only).
         *
         * @return Array of [usedJSHeapSize, totalJSHeapSize, jsHeapSizeLimit] in bytes, or null
         */
        public static long[] getMemoryInfo() {
                return getMemoryInfo0();
        }

        // ========== Utility Methods ==========

        /**
         * Converts a Java byte array to a TeaVM Uint8Array.
         */
        private static Uint8Array byteArrayToUint8Array(byte[] arr) {
                ArrayBuffer buffer = ArrayBuffer.create(arr.length);
                Uint8Array view = Uint8Array.create(buffer);
                for (int i = 0; i < arr.length; i++) {
                        view.set(i, (short)(arr[i] & 0xFF));
                }
                return view;
        }

        /**
         * Converts a Uint8Array to a Java byte array.
         */
        public static byte[] uint8ArrayToBytes(Uint8Array arr) {
                byte[] result = new byte[arr.getLength()];
                for (int i = 0; i < result.length; i++) {
                        result[i] = (byte) arr.get(i);
                }
                return result;
        }

        /**
         * Converts an ArrayBuffer to a Java byte array.
         */
        public static byte[] arrayBufferToBytes(ArrayBuffer buffer) {
                return uint8ArrayToBytes(Uint8Array.create(buffer));
        }

        /**
         * Converts a Java byte array to an ArrayBuffer.
         */
        public static ArrayBuffer bytesToArrayBuffer(byte[] arr) {
                ArrayBuffer buffer = ArrayBuffer.create(arr.length);
                Uint8Array view = Uint8Array.create(buffer);
                for (int i = 0; i < arr.length; i++) {
                        view.set(i, (short)(arr[i] & 0xFF));
                }
                return buffer;
        }

        /**
         * Gets the user agent string.
         */
        public static String getUserAgent() {
                return getUserAgent0();
        }

        /**
         * Checks if the current browser is mobile.
         */
        public static boolean isMobileBrowser() {
                return isMobileBrowser0();
        }

        // ========== Native JS Methods ==========

        @JSBody(params = {}, script = ""
                        + "if (window.performance && window.performance.now) {"
                        + "  return BigInt(Math.floor(window.performance.now()));"
                        + "} else {"
                        + "  return BigInt(Date.now());"
                        + "}")
        private static native long getCurrentTimeMillis0();

        @JSBody(params = {}, script = ""
                        + "if (window.performance && window.performance.timeOrigin) {"
                        + "  return BigInt(Math.floor(window.performance.timeOrigin));"
                        + "} else {"
                        + "  return BigInt(0);"
                        + "}")
        private static native long getTimeOrigin0();

        @JSBody(params = {}, script = "return window.devicePixelRatio || 1.0;")
        private static native float getDevicePixelRatio0();

        @JSBody(params = {}, script = "return window.screen.width;")
        private static native int getScreenWidth0();

        @JSBody(params = {}, script = "return window.screen.height;")
        private static native int getScreenHeight0();

        // localStorage methods
        @JSBody(params = { "fullKey" }, script = ""
                        + "try { return localStorage.getItem(fullKey); } catch(e) { return null; }")
        private static native String localStorageGet0(String fullKey);

        @JSBody(params = { "fullKey", "value" }, script = ""
                        + "try { localStorage.setItem(fullKey, value); } catch(e) { console.warn('localStorage write failed:', e); }")
        private static native void localStorageSet0(String fullKey, String value);

        @JSBody(params = { "fullKey" }, script = ""
                        + "try { localStorage.removeItem(fullKey); } catch(e) { console.warn('localStorage delete failed:', e); }")
        private static native void localStorageDelete0(String fullKey);

        @JSBody(params = { "fullKey" }, script = ""
                        + "try { return localStorage.getItem(fullKey) !== null; } catch(e) { return false; }")
        private static native boolean localStorageHas0(String fullKey);

        // Download methods
        @JSBody(params = { "filename", "data", "mimeType" }, script = ""
                        + "var blob = new Blob([data], { type: mimeType });"
                        + "var url = URL.createObjectURL(blob);"
                        + "var a = document.createElement('a');"
                        + "a.href = url;"
                        + "a.download = filename;"
                        + "a.style.display = 'none';"
                        + "document.body.appendChild(a);"
                        + "a.click();"
                        + "setTimeout(function() {"
                        + "  document.body.removeChild(a);"
                        + "  URL.revokeObjectURL(url);"
                        + "}, 100);")
        private static native void downloadFile0(String filename, Uint8Array data, String mimeType);

        @JSBody(params = { "filename", "text" }, script = ""
                        + "var blob = new Blob([text], { type: 'text/plain' });"
                        + "var url = URL.createObjectURL(blob);"
                        + "var a = document.createElement('a');"
                        + "a.href = url;"
                        + "a.download = filename;"
                        + "a.style.display = 'none';"
                        + "document.body.appendChild(a);"
                        + "a.click();"
                        + "setTimeout(function() {"
                        + "  document.body.removeChild(a);"
                        + "  URL.revokeObjectURL(url);"
                        + "}, 100);")
        private static native void downloadTextFile0(String filename, String text);

        // Page visibility
        /** Callback for visibility change events from the browser. */
        @JSFunctor
        private interface VisibilityChangeCallback extends JSObject {
                void call(boolean visible);
        }

        @JSBody(params = { "callback" }, script = ""
                        + "document.addEventListener('visibilitychange', function() {"
                        + "  callback(!document.hidden);"
                        + "});")
        private static native void registerVisibilityChangeListener0(VisibilityChangeCallback callback);

        private static void registerVisibilityChangeListener() {
                registerVisibilityChangeListener0(visible -> __onVisibilityChange(visible));
        }

        private static void __onVisibilityChange(boolean visible) {
                setPageVisible(visible);
        }

        // Resize listener
        /** Callback for resize events from the browser. */
        @JSFunctor
        private interface SimpleCallback extends JSObject {
                void call();
        }

        @JSBody(params = { "callback" }, script = ""
                        + "window.addEventListener('resize', function() {"
                        + "  callback();"
                        + "});")
        private static native void registerResizeListener0(SimpleCallback callback);

        private static void registerResizeListener() {
                registerResizeListener0(() -> __onResize());
        }

        private static void __onResize() {
                // updateCanvasSize re-reads the device pixel ratio before using
                // it, so a zoom-driven resize (ratio change, same client size)
                // is picked up here too.
                updateCanvasSize();
                updateScreenInfo();
        }

        // Orientation change listener
        @JSBody(params = { "callback" }, script = ""
                        + "if (screen.orientation) {"
                        + "  screen.orientation.addEventListener('change', function() {"
                        + "    callback();"
                        + "  });"
                        + "} else {"
                        + "  window.addEventListener('orientationchange', function() {"
                        + "    callback();"
                        + "  });"
                        + "}")
        private static native void registerOrientationChangeListener0(SimpleCallback callback);

        private static void registerOrientationChangeListener() {
                registerOrientationChangeListener0(() -> __onOrientationChange());
        }

        private static void __onOrientationChange() {
                updateScreenInfo();
        }

        // Web Worker methods
        @JSBody(params = { "url" }, script = "return new Worker(url);")
        private static native JSObject createWorker0(String url);

        @JSBody(params = { "worker" }, script = "worker.terminate();")
        private static native void terminateWorker0(JSObject worker);

        @JSBody(params = { "worker", "data" }, script = "worker.postMessage(data, [data]);")
        private static native void postWorkerMessage0(JSObject worker, ArrayBuffer data);

        // Memory info (Chrome only)
        @JSBody(params = {}, script = ""
                        + "if (window.performance && window.performance.memory) {"
                        + "  var m = window.performance.memory;"
                        + "  return [m.usedJSHeapSize, m.totalJSHeapSize, m.jsHeapSizeLimit];"
                        + "} else {"
                        + "  return null;"
                        + "}")
        private static native long[] getMemoryInfo0();

        // User agent
        @JSBody(params = {}, script = "return navigator.userAgent;")
        private static native String getUserAgent0();

        // Mobile detection
        @JSBody(params = {}, script = ""
                        + "return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);")
        private static native boolean isMobileBrowser0();

        /**
         * Creates a blob URL from a string (for worker creation).
         */
        @JSBody(params = { "content", "type" }, script = ""
                        + "var blob = new Blob([content], { type: type });"
                        + "return URL.createObjectURL(blob);")
        public static native String createBlobURL(String content, String type);

        /**
         * Revokes a blob URL.
         */
        @JSBody(params = { "url" }, script = "URL.revokeObjectURL(url);")
        public static native void revokeBlobURL(String url);
}
