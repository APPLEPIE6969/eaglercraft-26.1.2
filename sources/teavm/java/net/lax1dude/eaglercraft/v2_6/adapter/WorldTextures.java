package net.lax1dude.eaglercraft.v2_6.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;
import net.minecraft.world.level.block.Blocks;

/**
 * Loads the real Minecraft 26.1.2 block textures for the 3D world
 * renderer into ONE WebGL2 texture array - the browser form of
 * vanilla's stitched block atlas.
 *
 * <p>The PNGs ship in {@code public/assets/world/textures/} extracted
 * verbatim from the official client jar's
 * {@code assets/minecraft/textures/block/} (see docs/UI-FIDELITY.md).
 * Every texture is a 16x16 layer of a single {@code TEXTURE_2D_ARRAY};
 * the mesh builder indexes layers per-vertex, so a mixed section (grass
 * top + dirt sides + bedrock) draws in one call with one bind - the
 * same shape as vanilla's atlas binding. The layer list is derived from
 * the {@link Blocks} registry (destroy stages first, then every
 * texture the registry references in registry order), so adding blocks
 * never touches this class.</p>
 *
 * <p>Sampling state mirrors vanilla block atlases:</p>
 * <ul>
 *   <li>{@code REPEAT} wrapping (per-face 0..1 UVs; the inflated
 *       destroy-stage cubes sample slightly past the edge)</li>
 *   <li>{@code NEAREST} magnification + {@code NEAREST_MIPMAP_LINEAR}
 *       mip chain (vanilla's block mipmap, kills distant shimmer)</li>
 * </ul>
 *
 * <p>Biome tint bake: the grayscale grass_block_top and leaf textures
 * are multiplied at upload time with their registry tint (plains grass
 * 0x91BD59, foliage 0x77AB2F, birch 0x80A755, spruce 0x619961) - the
 * same bake vanilla's biome colormaps perform.</p>
 *
 * <p>Load protocol mirrors {@link GuiSprites}: async {@code <img>}
 * downloads parked in {@code window.__eaglerWorldTextures}, drained
 * once per frame; the array builds when every layer has landed.</p>
 */
public final class WorldTextures {

        /** Base path the block texture PNGs are served from (same origin). */
        public static final String BASE_PATH = "assets/world/textures/";

        /** Destroy-stage overlays occupy the first 10 array layers. */
        public static final int DESTROY_STAGE_COUNT = 10;

        /** Layer of destroy_stage_0 (destroy_stage_N = this + N). */
        public static final int LAYER_DESTROY_0 = 0;

        /** Layer order = the array's depth index (destroy stages, then block textures). */
        public static final String[] NAMES = buildNames();

        /** Per-layer tint (0 = none), aligned with NAMES. */
        private static final int[] TINTS = buildTints();

        private static String[] buildNames() {
                List<String> names = new ArrayList<String>();
                for (int i = 0; i < DESTROY_STAGE_COUNT; i++) {
                        names.add("destroy_stage_" + i);
                }
                names.addAll(Blocks.allTextureNames());
                return names.toArray(new String[0]);
        }

        private static int[] buildTints() {
                int[] tints = new int[NAMES.length];
                for (int i = DESTROY_STAGE_COUNT; i < NAMES.length; i++) {
                        String tex = NAMES[i];
                        // tintForTexture scopes an allTint claim to the block's
                        // OWN textures - the plain tintFor lets a leaves block's
                        // foliage tint answer for every texture name in the
                        // registry, which foliage-tinted the entire atlas (the
                        // "dark green world" render bug).
                        for (Blocks.Block b : Blocks.creativeBuilding()) {
                                tints[i] = b.tintForTexture(tex);
                                if (tints[i] != 0) break;
                        }
                        if (tints[i] == 0) {
                                for (Blocks.Block b : Blocks.creativeColored()) {
                                        tints[i] = b.tintForTexture(tex);
                                        if (tints[i] != 0) break;
                                }
                        }
                        if (tints[i] == 0) {
                                for (Blocks.Block b : Blocks.creativeNatural()) {
                                        tints[i] = b.tintForTexture(tex);
                                        if (tints[i] != 0) break;
                                }
                        }
                }
                return tints;
        }

        private static final Map<String, Uint8Array> decoded = new HashMap<String, Uint8Array>();
        private static final List<String> pending = new ArrayList<String>();
        private static JSObject arrayTexture = null;
        private static boolean requested = false;

        public abstract static class TexData implements JSObject {
                @JSProperty
                public abstract int getW();

                @JSProperty
                public abstract int getH();

                @JSProperty
                public abstract Uint8Array getData();

                @JSProperty
                public abstract boolean getError();
        }

        private WorldTextures() {
        }

        /** Starts the async downloads. Safe to call again. */
        public static void requestAll() {
                if (requested) return;
                requested = true;
                for (int i = 0; i < NAMES.length; i++) {
                        pending.add(NAMES[i]);
                        beginLoad(NAMES[i], BASE_PATH + NAMES[i] + ".png");
                }
                ClientMain.log("[WorldTextures] requested " + NAMES.length + " block texture PNGs");
        }

        /** Drains finished downloads; builds the array once all are up. */
        public static void pollPending() {
                if (pending.isEmpty()) {
                        return;
                }
                for (int i = pending.size() - 1; i >= 0; i--) {
                        String name = pending.get(i);
                        TexData data = poll(name);
                        if (data == null) {
                                continue;
                        }
                        pending.remove(i);
                        if (data.getError()) {
                                ClientMain.warn("[WorldTextures] failed to load '" + name
                                        + "' - layer stays blank");
                                continue;
                        }
                        Uint8Array pixels = data.getData();
                        int tint = layerTint(name);
                        if (tint != 0) {
                                pixels = bakeTint(pixels, tint);
                        }
                        decoded.put(name, pixels);
                }
                if (arrayTexture == null && decoded.size() == NAMES.length) {
                        WebGL2RenderingContext gl = ClientMain.getWebGL2();
                        if (gl != null) {
                                arrayTexture = buildArray(gl);
                                ClientMain.log("[WorldTextures] block texture array ready ("
                                        + NAMES.length + " layers)");
                        }
                }
        }

        /** Whether the full atlas array is up (rendering gates on this). */
        public static boolean isReady() {
                return arrayTexture != null;
        }

        /** The atlas array texture (null until all layers decoded). */
        public static JSObject texture() {
                return arrayTexture;
        }

        /** Array layer index by texture name (-1 for unknown). */
        public static int layerOf(String name) {
                for (int i = 0; i < NAMES.length; i++) {
                        if (NAMES[i].equals(name)) {
                                return i;
                        }
                }
                return -1;
        }

        /** Registry tint for a texture layer (0 = render raw). */
        public static int layerTint(String name) {
                int idx = layerOf(name);
                return idx >= 0 ? TINTS[idx] : 0;
        }

        /** Array layer for a block's top face (real Blocks registry type). */
        public static int layerTop(net.minecraft.world.level.block.Blocks.Block block) {
                return layerOf(block.topTexture);
        }

        /** Array layer for a block's side faces. */
        public static int layerSide(net.minecraft.world.level.block.Blocks.Block block) {
                return layerOf(block.sideTexture);
        }

        /** Array layer for a block's bottom face. */
        public static int layerBottom(net.minecraft.world.level.block.Blocks.Block block) {
                return layerOf(block.bottomTexture);
        }

        /**
         * Multiplies an RGBA byte buffer's RGB by the tint color (alpha
         * kept). Returns the new buffer (input untouched).
         */
        private static Uint8Array bakeTint(Uint8Array src, int tint) {
                int r0 = (tint >> 16) & 0xFF;
                int g0 = (tint >> 8) & 0xFF;
                int b0 = tint & 0xFF;
                int len = src.getLength();
                Uint8Array out = Uint8Array.create(len);
                for (int i = 0; i < len; i += 4) {
                        out.set(i, (short) ((src.get(i) * r0) >> 8));
                        out.set(i + 1, (short) ((src.get(i + 1) * g0) >> 8));
                        out.set(i + 2, (short) ((src.get(i + 2) * b0) >> 8));
                        out.set(i + 3, src.get(i + 3));
                }
                return out;
        }

        /** Uploads every decoded layer as one TEXTURE_2D_ARRAY. */
        private static JSObject buildArray(WebGL2RenderingContext gl) {
                JSObject tex = PlatformOpenGL._wglCreateTexture();
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, tex);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_FLIP_Y_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_ALIGNMENT, 1);
                PlatformOpenGL._wglTexImage3D(WebGL2RenderingContext.TEXTURE_2D_ARRAY, 0,
                        WebGL2RenderingContext.RGBA, 16, 16, NAMES.length, 0,
                        WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE, null);
                for (int i = 0; i < NAMES.length; i++) {
                        Uint8Array px = decoded.get(NAMES[i]);
                        if (px == null) {
                                px = blankLayer();
                        }
                        PlatformOpenGL._wglTexSubImage3D(WebGL2RenderingContext.TEXTURE_2D_ARRAY, 0,
                                0, 0, i, 16, 16, 1,
                                WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE, px);
                }
                // NEAREST/NEAREST, no mipmaps: vanilla's mipmap-off default
                // look, and immune to SwiftShader's 2D-array mipmap sampling
                // (the generated levels sampled wrong layers, which read as a
                // dark, wrong-tinted world at every distance).
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                        WebGL2RenderingContext.TEXTURE_MIN_FILTER,
                        WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                        WebGL2RenderingContext.TEXTURE_MAG_FILTER, WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                        WebGL2RenderingContext.TEXTURE_WRAP_S, WebGL2RenderingContext.REPEAT);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                        WebGL2RenderingContext.TEXTURE_WRAP_T, WebGL2RenderingContext.REPEAT);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, null);
                return tex;
        }

        private static Uint8Array blankLayer() {
                Uint8Array px = Uint8Array.create(16 * 16 * 4);
                for (int i = 0; i < px.getLength(); i += 4) {
                        px.set(i, (short) 255);
                        px.set(i + 1, (short) 255);
                        px.set(i + 2, (short) 255);
                        px.set(i + 3, (short) 255);
                }
                return px;
        }

        @JSBody(params = { "name", "path" }, script =
                "window.__eaglerWorldTextures = window.__eaglerWorldTextures || {};"
                + "var store = window.__eaglerWorldTextures;"
                + "if (store[name]) return;"
                + "var img = new Image();"
                + "img.onload = function() {"
                + " try {"
                + "  var c = document.createElement('canvas');"
                + "  c.width = img.width; c.height = img.height;"
                + "  var ctx = c.getContext('2d', {willReadFrequently: true});"
                + "  ctx.drawImage(img, 0, 0);"
                + "  var d = ctx.getImageData(0, 0, img.width, img.height);"
                + "  store[name] = { w: img.width, h: img.height,"
                + "    data: new Uint8Array(d.data.buffer), error: false };"
                + " } catch (e) { store[name] = { error: true }; }"
                + "};"
                + "img.onerror = function() { store[name] = { error: true }; };"
                + "img.src = path;")
        private static native void beginLoad(String name, String path);

        @JSBody(params = { "name" }, script =
                "return (window.__eaglerWorldTextures && window.__eaglerWorldTextures[name]) || null;")
        private static native TexData poll(String name);
}
