package com.mojang.blaze3d.platform;

import org.teavm.jso.JSBody;
import org.teavm.jso.dom.html.HTMLCanvasElement;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 browser override for Window.
 * Replaces GLFW window management with HTML5 canvas.
 * All window operations are redirected to the browser canvas element.
 */
public class Window {

        /** The event handler for window resize/close events. */
        private final WindowEventHandler eventHandler;

        /** The window/canvas width in pixels. */
        private int width;

        /** The window/canvas height in pixels. */
        private int height;

        /** The windowed-mode width (for fullscreen toggle, unused in browser). */
        private int windowedWidth;

        /** The windowed-mode height (for fullscreen toggle, unused in browser). */
        private int windowedHeight;

        /** The x position of the window (always 0 in browser). */
        private int xPos;

        /** The y position of the window (always 0 in browser). */
        private int yPos;

        /** The window title (unused in browser - document.title could be set). */
        private String title;
        private DisplayData displayData;

        /** Whether the window should close. */
        private boolean shouldClose;

        /** The framerate limit. */
        private int framerateLimit = 260;

        /** The GUI scale factor. */
        private int guiScale = 1;

        /** The framebuffer width (same as canvas drawing buffer width). */
        private int framebufferWidth;

        /** The framebuffer height (same as canvas drawing buffer height). */
        private int framebufferHeight;

        /** The screen width (for GUI scale calculations). */
        private int screenWidth;

        /** The screen height (for GUI scale calculations). */
        private int screenHeight;

        public Window(WindowEventHandler eventHandler, Monitor monitor, DisplayData displayData, String title) {
                this.eventHandler = eventHandler;
                this.title = title;
                this.shouldClose = false;

                // Initialize dimensions from canvas
                updateCanvasSize();

                // Use display data if provided
                if (displayData != null) {
                        this.width = displayData.getWidth() > 0 ? displayData.getWidth() : this.width;
                        this.height = displayData.getHeight() > 0 ? displayData.getHeight() : this.height;
                }

                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
                this.framebufferWidth = this.width;
                this.framebufferHeight = this.height;
                this.screenWidth = this.width;
                this.screenHeight = this.height;
        }

        /**
         * Updates the cached canvas dimensions from the browser.
         */
        private void updateCanvasSize() {
                HTMLCanvasElement canvas = ClientMain.getCanvas();
                if (canvas != null) {
                        this.width = PlatformRuntime.getCanvasDrawableWidth();
                        this.height = PlatformRuntime.getCanvasDrawableHeight();
                        this.framebufferWidth = this.width;
                        this.framebufferHeight = this.height;
                        this.screenWidth = PlatformRuntime.getCanvasWidth();
                        this.screenHeight = PlatformRuntime.getCanvasHeight();
                } else {
                        this.width = getCanvasWidth0();
                        this.height = getCanvasHeight0();
                        this.framebufferWidth = this.width;
                        this.framebufferHeight = this.height;
                        this.screenWidth = this.width;
                        this.screenHeight = this.height;
                }
        }

        /**
         * Re-reads the canvas dimensions from the browser into this
         * Window's cached fields. The cache can be empty (0x0) while the
         * page is still laying out during construction; Minecraft calls
         * this from resizeDisplay once the real size is known so the GUI
         * scale and the screens re-initialize against actual pixels.
         */
        public void refreshCachedSize() {
                updateCanvasSize();
        }

        @JSBody(script = "var c = document.querySelector('canvas'); return c ? (c.clientWidth || window.innerWidth) : 0;")
        private static native int getCanvasWidth0();

        @JSBody(script = "var c = document.querySelector('canvas'); return c ? (c.clientHeight || window.innerHeight) : 0;")
        private static native int getCanvasHeight0();

        // ========== Size / Position ==========

        public void setWidth(int width) {
                this.width = width;
                this.framebufferWidth = width;
        }

        public void setHeight(int height) {
                this.height = height;
                this.framebufferHeight = height;
        }

        public int getWidth() {
                return width;
        }

        public int getHeight() {
                return height;
        }

        public int getX() {
                return 0; // Browser windows are always at origin
        }

        public int getY() {
                return 0; // Browser windows are always at origin
        }

        // ========== Title ==========

        public String getTitle() {
                return title;
        }

        public void setTitle(String title) {
                this.title = title;
                // Optionally set document.title in the browser
                setDocumentTitle(title != null ? title : "EaglerCraft 26.1.2");
        }

        @JSBody(params = "t", script = "document.title = t;")
        private static native void setDocumentTitle(String t);

        // ========== Display ==========

        /**
         * Updates the display (swap buffers). In the browser, WebGL
         * automatically swaps at the next requestAnimationFrame, so this is a no-op.
         */
        public void updateDisplay() {
                // Refresh cached dimensions each frame
                updateCanvasSize();
        }

        /**
         * Returns whether the window should close.
         * In the browser, this is controlled by the shouldClose flag,
         * not by any OS window event.
         */
        public boolean shouldClose() {
                return shouldClose;
        }

        public void setShouldClose(boolean shouldClose) {
                this.shouldClose = shouldClose;
        }

        /**
         * Sets the window icon. No-op in the browser (we could set a favicon,
         * but that's handled by the HTML page).
         */
        public void setWindowIcon() {
                // no-op in browser
        }

        // ========== Framerate ==========

        public void setFramerateLimit(int limit) {
                this.framerateLimit = limit;
        }

        public int getFramerateLimit() {
                return framerateLimit;
        }

        // ========== Fullscreen ==========

        public boolean isFullscreen() {
                return false; // Browser fullscreen is handled differently
        }

        public void toggleFullScreen() {
                // no-op in browser - fullscreen API would need user gesture
        }

        public int getWindowedWidth() {
                return windowedWidth;
        }

        public int getWindowedHeight() {
                return windowedHeight;
        }

        // ========== Resize ==========

        /**
         * Called when the canvas/window is resized.
         * Updates dimensions and notifies the event handler.
         */
        public void onResize(int newWidth, int newHeight) {
                this.width = newWidth;
                this.height = newHeight;
                this.framebufferWidth = newWidth;
                this.framebufferHeight = newHeight;
                if (eventHandler != null) {
                        eventHandler.resizeDisplay();
                }
        }

        // ========== Input ==========

        /**
         * Sets raw mouse input mode. No-op in browser (Pointer Lock handles this).
         */
        public void setRawMouseInput(boolean raw) {
                // no-op in browser
        }

        /**
         * MC 26.1.2: Enables/disables raw mouse input on this window.
         * Browser: no-op (Pointer Lock API handles this differently).
         */
        public void updateRawMouseInput(boolean raw) {
                // no-op in browser
        }

        // ========== Backend ==========

        /**
         * MC 26.1.2: Returns the GPU backend associated with this window.
         * Browser: returns a stub WebGL2 backend.
         */
        public com.mojang.blaze3d.systems.GpuBackend backend() {
                return new com.mojang.blaze3d.systems.GpuBackend() {
                        @Override public String getName() { return "WebGL2"; }
                        @Override public String getVendor() { return "Browser"; }
                        @Override public String getVersion() { return "2.0"; }
                        @Override public boolean isOpenGL() { return true; }
                        @Override public boolean isWebGPU() { return false; }
                };
        }

        /**
         * MC 26.1.2: Returns the preferred fullscreen video mode, if any.
         * Browser: fullscreen uses the entire screen, no mode selection.
         */
        public java.util.Optional<VideoMode> getPreferredFullscreenVideoMode() {
                return java.util.Optional.empty();
        }

        // ========== Framebuffer ==========

        /**
         * Calculates the framebuffer size. In the browser, this is
         * the canvas drawing buffer size.
         */
        public int[] calculateFramebufferSize() {
                updateCanvasSize();
                return new int[] { framebufferWidth, framebufferHeight };
        }

        public int getScreenWidth() {
                return screenWidth;
        }

        public int getScreenHeight() {
                return screenHeight;
        }

        // ========== GUI Scale ==========

        public int getGuiScale() {
                return guiScale;
        }

        public void setGuiScale(int scale) {
                this.guiScale = scale;
        }

        /**
         * Calculates a reasonable GUI scale for the current screen size.
         * Tries to use the largest integer scale that fits.
         */
        public int calculateScale(int preferredScale, boolean forceUnicode) {
                int scale = 1;
                while (scale < preferredScale
                        && screenWidth / (scale + 1) >= 320
                        && screenHeight / (scale + 1) >= 240) {
                        scale++;
                }
                if (forceUnicode && scale % 2 != 0 && scale > 1) {
                        scale--;
                }
                return scale;
        }

        /**
         * Returns the GLFW window pointer (always 1 in browser).
         */
        public long getWindow() {
                return 1L;
        }


    // ===== MC 26.1.2 constructor (WindowEventHandler, DisplayData, String, String, GpuBackend) =====
    public Window(com.mojang.blaze3d.platform.WindowEventHandler eventHandler,
                  DisplayData displayData,
                  String title,
                  String vsyncTitle,
                  com.mojang.blaze3d.systems.GpuBackend backend)
            throws com.mojang.blaze3d.systems.BackendCreationException {
        this.eventHandler = eventHandler;
        this.displayData = displayData;
        this.title = title;
        // Pull the real canvas dimensions in at construction. The browser
        // canvas is laid out before main() runs, so this is the settled
        // size in virtually every boot; without it the cached size stays
        // 0x0, the first GUI-scale calculation degenerates to 1 and the
        // first screens initialize against a 0x0 viewport. Minecraft
        // syncCanvasSize() still re-syncs every tick as a safety net.
        updateCanvasSize();
        this.windowedWidth = this.width;
        this.windowedHeight = this.height;
    }

    public void close() {
        shouldClose = true;
    }

    public int getGuiScaledWidth() {
        return (int)(getWidth() / getGuiScale());
    }

    public int getGuiScaledHeight() {
        return (int)(getHeight() / getGuiScale());
    }

    public void setIcon(net.minecraft.server.packs.PackResources pack,
                        com.mojang.blaze3d.platform.IconSet iconSet)
            throws java.io.IOException {
        // Browser: icon is set via favicon, not programmatically
    }

    public void setWindowCloseCallback(Runnable callback) {
        // Browser: window close is handled by beforeunload event
    }

    public void setWindowed(int width, int height) {
        // Browser: window size is controlled by CSS / fullscreen API
    }

    public void updateVsync(boolean vsync) {
        // Browser: vsync is controlled by requestAnimationFrame
    }

    public int getRefreshRate() {
        return 60;
    }

    public static String getPlatform() {
        return "EaglerCraft";
    }

    public long getHandle() {
        return 0L;
    }

    public int getFramebufferWidth() {
        return getWidth();
    }

    public int getFramebufferHeight() {
        return getHeight();
    }

    // ========== MC 26.1.2 additional methods ==========

    /**
     * MC 26.1.2: Returns the raw window handle. Browser: returns 0.
     */
    public long handle() {
        return 0L;
    }

    /**
     * MC 26.1.2: Returns whether the window has focus.
     */
    public boolean isFocused() {
        return true;
    }

    /**
     * MC 26.1.2: Enables/disables cursor appearance changes. Browser: no-op.
     */
    public void setAllowCursorChanges(boolean allow) {
        // no-op in browser
    }

    /**
     * MC 26.1.2: Sets the error section name (used for error reporting).
     */
    public void setErrorSection(String section) {
        // no-op in browser
    }

    /**
     * MC 26.1.2: Updates fullscreen state if it has changed.
     * Browser: no-op (fullscreen handled by browser API).
     */
    public void updateFullscreenIfChanged() {
        // no-op in browser
    }
public void selectCursor(com.mojang.blaze3d.platform.cursor.CursorType cursorType) {
        // Browser: cursor handled by CSS
    }
    public static void setDefaultErrorCallback() {
        // Browser: no GLFW error callback
    }

    public float getAppropriateLineWidth() {
        return getWidth() / 8.0f;
    }

    public boolean isMinimized() { return false; }

    public boolean isResized() { return false; }
}
