package net.lax1dude.eaglercraft.v2_6.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Loads the real Minecraft 26.1.2 GUI sprite PNGs and hands them to
 * {@link EaglerGuiImpl} as WebGL2 textures.
 *
 * <p>The PNGs ship in {@code public/assets/gui/sprites/} - they were
 * extracted verbatim from the official (unobfuscated) 26.1.2 client jar
 * (see docs/UI-FIDELITY.md), so every menu button, the hotbar, the
 * selection frame and the crosshair use Mojang's own pixels.</p>
 *
 * <h3>Load protocol</h3>
 * <ol>
 *   <li>{@link #requestAll()} kicks off an {@code <img>} download per
 *       sprite right after GL init. The browser decodes each image into
 *       a {@code Uint8Array} of RGBA bytes parked in
 *       {@code window.__eaglerSprites}.</li>
 *   <li>{@link #pollPending()} runs once per frame (from
 *       {@code EaglerGuiImpl.begin}): any completed download is uploaded
 *       with {@code texImage2D} as an RGBA texture with NEAREST
 *       filtering (the crisp vanilla look) and CLAMP_TO_EDGE wrapping.</li>
 * </ol>
 *
 * <p>Nothing blocks on the loads: until a sprite is ready, callers get
 * {@code false} from {@link #isReady} and fall back to the procedural
 * solid-quad rendering, exactly like before the pipeline existed. The
 * sprites are a few hundred bytes each, so the fallback is only visible
 * for the first frame or two after boot.</p>
 */
public final class GuiSprites {

        /** Base path the sprite PNGs are served from (same origin). */
        private static final String BASE_PATH = "assets/gui/sprites/";

        private static final Map<String, JSObject> textures = new HashMap<String, JSObject>();
        private static final Map<String, int[]> sizes = new HashMap<String, int[]>();
        private static final List<String> pending = new ArrayList<String>();
        private static final Set<String> failed = new HashSet<String>();

        /**
         * Raw decoded RGBA bytes for the six title-screen panorama faces.
         * Kept alongside the uploaded 2D textures because the panorama
         * renderer needs the pixel rows to build its CUBE_MAP texture
         * (vanilla CubeMapTexture stacks the faces into a column and
         * uploads one band per GL cube face).
         */
        private static final Map<String, SpriteData> panoramaRaw = new HashMap<String, SpriteData>();

        /** Name prefix of the six panorama face sprites. */
        public static final String PANORAMA_PREFIX = "title/background/panorama_";

        /** 1x1 opaque white texture used for every solid (non-sprite) quad. */
        private static JSObject whiteTexture = null;

        private static boolean requested = false;

        private GuiSprites() {
        }

        // ========== Public API ==========

        /**
         * Starts the async download of every GUI sprite. Safe to call
         * again; only the first call has an effect. Must be called after
         * a WebGL2 context exists (textures upload on later polls, not
         * here).
         */
        public static void requestAll() {
                if (requested) return;
                requested = true;
                String[] names = {
                        "widget/button",
                        "widget/button_highlighted",
                        "widget/button_disabled",
                        "widget/text_field",
                        "widget/text_field_highlighted",
                        "widget/tab",
                        "widget/tab_highlighted",
                        "widget/tab_selected",
                        "widget/tab_selected_highlighted",
                        "widget/scroller",
                        "widget/scroller_background",
                        "hud/crosshair",
                        "hud/hotbar",
                        "hud/hotbar_selection",
                        "misc/unknown_server",
                        "icon/language",
                        "icon/accessibility",
                        "gui/menu_background",
                        "gui/menu_list_background",
                        "gui/header_separator",
                        "gui/footer_separator",
                        "title/background/panorama_0",
                        "title/background/panorama_1",
                        "title/background/panorama_2",
                        "title/background/panorama_3",
                        "title/background/panorama_4",
                        "title/background/panorama_5"
                };
                for (int i = 0; i < names.length; i++) {
                        pending.add(names[i]);
                        beginLoad(names[i], BASE_PATH + names[i] + ".png");
                }
                ClientMain.log("[GuiSprites] requested " + names.length + " vanilla sprite PNGs");
        }

        /**
         * Uploads any sprite downloads that finished since the last call.
         * Runs once per frame; never blocks.
         */
        public static void pollPending() {
                if (pending.isEmpty()) return;
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null) return;
                for (int i = pending.size() - 1; i >= 0; i--) {
                        String name = pending.get(i);
                        SpriteData data = poll(name);
                        if (data == null) {
                                continue;
                        }
                        pending.remove(i);
                        if (data.getError()) {
                                failed.add(name);
                                ClientMain.warn("[GuiSprites] failed to load sprite '" + name + "'");
                                continue;
                        }
                        try {
                                textures.put(name, upload(gl, data));
                                sizes.put(name, new int[] { data.getW(), data.getH() });
                                if (name.startsWith(PANORAMA_PREFIX)) {
                                        panoramaRaw.put(name, data);
                                }
                        } catch (Throwable t) {
                                failed.add(name);
                                ClientMain.warn("[GuiSprites] texture upload failed for '" + name + "': " + t.getMessage());
                        }
                }
        }

        /**
         * Returns whether the named sprite is available as a texture.
         */
        public static boolean isReady(String name) {
                return textures.containsKey(name);
        }

        /**
         * Returns the GL texture object for a ready sprite, or null.
         */
        public static JSObject texture(String name) {
                return textures.get(name);
        }

        /**
         * Returns the pixel width of a ready sprite, or 0 when unknown.
         */
        public static int widthOf(String name) {
                int[] s = sizes.get(name);
                return s != null ? s[0] : 0;
        }

        /**
         * Returns the pixel height of a ready sprite, or 0 when unknown.
         */
        public static int heightOf(String name) {
                int[] s = sizes.get(name);
                return s != null ? s[1] : 0;
        }

        /**
         * Returns whether the named sprite permanently failed to load
         * (404, decode error) - callers should stop polling for it.
         */
        public static boolean isFailed(String name) {
                return failed.contains(name);
        }

        /**
         * Returns the raw decoded RGBA download of a panorama face
         * (only populated for names under {@link #PANORAMA_PREFIX}),
         * or null while the download is still in flight or failed.
         */
        public static SpriteData rawPanoramaData(String name) {
                return panoramaRaw.get(name);
        }

        /**
         * Returns the 1x1 white texture that backs all solid fills,
         * creating it on first use.
         */
        public static JSObject getWhiteTexture() {
                if (whiteTexture != null) return whiteTexture;
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null) return null;
                ArrayBuffer buffer = ArrayBuffer.create(4);
                Uint8Array px = Uint8Array.create(buffer);
                px.set(0, (short) 255);
                px.set(1, (short) 255);
                px.set(2, (short) 255);
                px.set(3, (short) 255);
                whiteTexture = uploadBytes(gl, 1, 1, px);
                return whiteTexture;
        }

        // ========== Internals ==========

        /**
         * Uploads a decoded sprite as an RGBA texture: NEAREST min/mag
         * filter, CLAMP_TO_EDGE, no Y flip (the vertex UVs address the
         * image top-down, matching the top-left GUI coordinate system).
         */
        private static JSObject upload(WebGL2RenderingContext gl, SpriteData data) {
                return uploadBytes(gl, data.getW(), data.getH(), data.getData());
        }

        private static JSObject uploadBytes(WebGL2RenderingContext gl, int w, int h, Uint8Array pixels) {
                JSObject tex = PlatformOpenGL._wglCreateTexture();
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, tex);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_FLIP_Y_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_ALIGNMENT, 1);
                PlatformOpenGL._wglTexImage2D(WebGL2RenderingContext.TEXTURE_2D, 0,
                        WebGL2RenderingContext.RGBA, w, h, 0,
                        WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE, pixels);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                        WebGL2RenderingContext.TEXTURE_MIN_FILTER, WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                        WebGL2RenderingContext.TEXTURE_MAG_FILTER, WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                        WebGL2RenderingContext.TEXTURE_WRAP_S, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                        WebGL2RenderingContext.TEXTURE_WRAP_T, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, null);
                return tex;
        }

        /**
         * TeaVM view of a parked sprite download:
         * {@code {w, h, data: Uint8Array} } or {@code {error: true}}.
         */
        public abstract static class SpriteData implements JSObject {
                @JSProperty
                public abstract int getW();

                @JSProperty
                public abstract int getH();

                @JSProperty
                public abstract Uint8Array getData();

                @JSProperty
                public abstract boolean getError();
        }

        /**
         * Starts the browser-side download+decode of one PNG. On success
         * parks {@code {w,h,data}} under {@code window.__eaglerSprites};
         * on failure parks {@code {error:true}}.
         */
        @JSBody(params = { "name", "path" }, script =
                "var store = window.__eaglerSprites || (window.__eaglerSprites = {});"
                + "var img = new Image();"
                + "img.onload = function() {"
                + " try {"
                + "  var c = document.createElement('canvas');"
                + "  c.width = img.width; c.height = img.height;"
                + "  var ctx = c.getContext('2d', {willReadFrequently: true});"
                + "  ctx.drawImage(img, 0, 0);"
                + "  var d = ctx.getImageData(0, 0, img.width, img.height);"
                + "  store[name] = { w: img.width, h: img.height,"
                + "    data: new Uint8Array(d.data.buffer) };"
                + " } catch (e) { store[name] = { error: true }; }"
                + "};"
                + "img.onerror = function() { store[name] = { error: true }; };"
                + "img.src = path;")
        private static native void beginLoad(String name, String path);

        /**
         * Returns the parked decode result for a sprite, or null while
         * the download is still in flight.
         */
        @JSBody(params = { "name" }, script =
                "return (window.__eaglerSprites && window.__eaglerSprites[name]) || null;")
        private static native SpriteData poll(String name);
}
