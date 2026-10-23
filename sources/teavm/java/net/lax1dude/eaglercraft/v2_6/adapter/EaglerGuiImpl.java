package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Batched 2D renderer for the browser GUI (menus, HUD overlay).
 *
 * <p>Minecraft 26.1.2 draws every 2D element as either a colored quad or
 * a sprite blit (widget/button*, hud/hotbar, hud/crosshair ...). Both
 * paths are batched here into a single dynamic VBO and drawn with one
 * {@code drawArrays} call per flush: solid quads sample a 1x1 white
 * texture tinted by the vertex color, sprite blits sample the real
 * Mojang PNG (loaded by {@link GuiSprites}). Positions are in
 * GUI-scaled pixels with the origin at the top-left corner of the
 * canvas, which matches how {@code Screen} and its widgets address the
 * screen.</p>
 *
 * <h3>Frame protocol</h3>
 * <ol>
 *   <li>{@link #begin} once per frame with the GUI-scaled dimensions
 *       (also polls {@link GuiSprites} uploads).</li>
 *   <li>Any number of {@link #fillRect} / {@link #drawSprite} /
 *       {@link #drawText} calls. Switching textures transparently
 *       flushes the queued quads for the previous texture first, and the
 *       batch re-binds its program/VAO lazily, so other GL code (e.g.
 *       the title-screen gradient shader) may run in between.</li>
 *   <li>{@link #flush} to draw everything queued so far. Call again
 *       after any interleaved GL work that must stay underneath the
 *       next batch of quads.</li>
 * </ol>
 *
 * <p>Depth testing is disabled while a batch draws (GUI pixels are all
 * at z=0 and would otherwise z-fight); the previous depth-test enable
 * state is restored on flush. Blending is expected to be enabled by the
 * caller ({@code RenderSystem.enableBlend}) - sprite alpha needs it.</p>
 */
public final class EaglerGuiImpl {

        /** Vertex count per quad (two triangles). */
        private static final int VERTICES_PER_QUAD = 6;

        /** Floats per vertex: x, y, r, g, b, a, u, v. */
        private static final int FLOATS_PER_VERTEX = 8;

        /** Maximum quads queued before an automatic mid-batch flush. */
        private static final int MAX_QUADS = 16384;

        private static JSObject guiProgram = null;
        private static JSObject guiVAO = null;
        private static JSObject guiVBO = null;
        private static JSObject uResolution = null;
        private static JSObject uSampler = null;
        private static int aPos = -1;
        private static int aColor = -1;
        private static int aUv = -1;
        private static Float32Array vertexBuffer = null;

        /** Texture the currently queued quads sample from. */
        private static JSObject currentTexture = null;

        private static boolean initialized = false;
        private static boolean batchOpen = false;
        private static boolean depthWasEnabled = false;

        private static int guiWidth = 1;
        private static int guiHeight = 1;
        private static int queuedVertices = 0;

        private EaglerGuiImpl() {
        }

        // ========== Lifecycle ==========

        /**
         * Compiles the GUI shader program and allocates the dynamic
         * vertex buffer. Runs once per page load. Also kicks off the
         * vanilla sprite PNG downloads.
         */
        private static void init(WebGL2RenderingContext gl) {
                if (initialized) return;

                String vsh = "#version 300 es\n"
                        + "precision highp float;\n"
                        + "in vec2 a_pos;\n"
                        + "in vec4 a_color;\n"
                        + "in vec2 a_uv;\n"
                        + "uniform vec2 u_resolution;\n"
                        + "out vec4 v_color;\n"
                        + "out vec2 v_uv;\n"
                        + "void main() {\n"
                        + "  vec2 ndc = vec2(a_pos.x / u_resolution.x * 2.0 - 1.0,"
                        + " 1.0 - a_pos.y / u_resolution.y * 2.0);\n"
                        + "  gl_Position = vec4(ndc, 0.0, 1.0);\n"
                        + "  v_color = a_color;\n"
                        + "  v_uv = a_uv;\n"
                        + "}\n";

                String fsh = "#version 300 es\n"
                        + "precision mediump float;\n"
                        + "in vec4 v_color;\n"
                        + "in vec2 v_uv;\n"
                        + "uniform sampler2D u_tex;\n"
                        + "out vec4 fragColor;\n"
                        + "void main() {\n"
                        + "  fragColor = texture(u_tex, v_uv) * v_color;\n"
                        + "}\n";

                guiProgram = EaglerShaderImpl.createProgramFromSource(vsh, fsh, "gui_2d");
                if (guiProgram == null) {
                        ClientMain.error("[EaglerGuiImpl] Failed to create the 2D GUI shader program");
                        return;
                }

                uResolution = EaglerShaderImpl.getUniformLocation(guiProgram, "u_resolution");
                uSampler = EaglerShaderImpl.getUniformLocation(guiProgram, "u_tex");
                aPos = EaglerShaderImpl.getAttribLocation(guiProgram, "a_pos");
                aColor = EaglerShaderImpl.getAttribLocation(guiProgram, "a_color");
                aUv = EaglerShaderImpl.getAttribLocation(guiProgram, "a_uv");

                PlatformOpenGL._wglUseProgram(guiProgram);
                PlatformOpenGL._wglActiveTexture(WebGL2RenderingContext.TEXTURE0);
                PlatformOpenGL._wglUniform1i(uSampler, 0);

                guiVAO = PlatformOpenGL._wglCreateVertexArray();
                guiVBO = PlatformOpenGL._wglCreateBuffer();

                PlatformOpenGL._wglBindVertexArray(guiVAO);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, guiVBO);
                vertexBuffer = Float32Array.create(MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, vertexBuffer,
                        WebGL2RenderingContext.DYNAMIC_DRAW);

                int stride = FLOATS_PER_VERTEX * 4;
                PlatformOpenGL._wglEnableVertexAttribArray(aPos);
                PlatformOpenGL._wglVertexAttribPointer(aPos, 2,
                        WebGL2RenderingContext.FLOAT, false, stride, 0);
                PlatformOpenGL._wglEnableVertexAttribArray(aColor);
                PlatformOpenGL._wglVertexAttribPointer(aColor, 4,
                        WebGL2RenderingContext.FLOAT, false, stride, 8);
                PlatformOpenGL._wglEnableVertexAttribArray(aUv);
                PlatformOpenGL._wglVertexAttribPointer(aUv, 2,
                        WebGL2RenderingContext.FLOAT, false, stride, 24);

                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);

                // Start downloading the vanilla sprite PNGs; they upload on
                // later begin() polls. Solid fills work from frame one via
                // the 1x1 white texture.
                GuiSprites.requestAll();

                initialized = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerGuiImpl] 2D GUI renderer initialized (textured pipeline)");
                }
        }

        // ========== Batch protocol ==========

        /**
         * Opens a batch for the given GUI-scaled resolution. Safe to call
         * every frame; re-issuing it just updates the resolution uniform.
         */
        public static void begin(WebGL2RenderingContext gl, int width, int height) {
                if (gl == null) return;
                init(gl);
                if (!initialized) return;
                guiWidth = Math.max(1, width);
                guiHeight = Math.max(1, height);
                GuiSprites.pollPending();
                if (!batchOpen) {
                        openBatch(gl);
                } else {
                        PlatformOpenGL._wglUniform2f(uResolution, (float) guiWidth, (float) guiHeight);
                }
        }

        /**
         * Draws and empties the queue. Safe to call even when nothing is
         * queued or no batch is open.
         */
        public static void flush(WebGL2RenderingContext gl) {
                if (gl == null || !initialized || !batchOpen) return;
                if (queuedVertices > 0) {
                        // bufferSubData writes into whatever is bound to
                        // ARRAY_BUFFER - that binding is global state, NOT
                        // part of the VAO, so it must be (re)bound here even
                        // though the VAO already references this buffer.
                        PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, guiVBO);
                        PlatformOpenGL._wglBindVertexArray(guiVAO);
                        PlatformOpenGL._wglBufferSubData(WebGL2RenderingContext.ARRAY_BUFFER, 0, vertexBuffer);
                        if (currentTexture == null) {
                                currentTexture = GuiSprites.getWhiteTexture();
                        }
                        if (currentTexture != null) {
                                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, currentTexture);
                        }
                        PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, queuedVertices);
                        PlatformOpenGL._wglBindVertexArray(null);
                        PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                }
                queuedVertices = 0;
                batchOpen = false;

                PlatformOpenGL._wglUseProgram(null);
                if (depthWasEnabled) {
                        PlatformOpenGL._wglEnable(WebGL2RenderingContext.DEPTH_TEST);
                }
        }

        // ========== Drawing ==========

        /**
         * Queues a filled rectangle at {@code (x, y)} in GUI pixels
         * (origin top-left). Colors are 0.0-1.0, alpha is blended.
         */
        public static void fillRect(float x, float y, float w, float h,
                        float r, float g, float b, float a) {
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null || !initialized) return;
                if (!batchOpen) {
                        openBatch(gl);
                }
                if (queuedVertices + VERTICES_PER_QUAD > MAX_QUADS * VERTICES_PER_QUAD) {
                        flush(gl);
                        openBatch(gl);
                }
                ensureTexture(GuiSprites.getWhiteTexture());

                float x2 = x + w;
                float y2 = y + h;
                // Center of the 1x1 white texture: the color tint does the work
                putVertex(x, y, r, g, b, a, 0.5f, 0.5f);
                putVertex(x, y2, r, g, b, a, 0.5f, 0.5f);
                putVertex(x2, y2, r, g, b, a, 0.5f, 0.5f);
                putVertex(x, y, r, g, b, a, 0.5f, 0.5f);
                putVertex(x2, y2, r, g, b, a, 0.5f, 0.5f);
                putVertex(x2, y, r, g, b, a, 0.5f, 0.5f);
        }

        /**
         * Same as the float overload with a packed 0xAARRGGBB color.
         */
        public static void fillRect(float x, float y, float w, float h, int argb) {
                fillRect(x, y, w, h,
                        ((argb >> 16) & 0xFF) / 255.0f,
                        ((argb >> 8) & 0xFF) / 255.0f,
                        (argb & 0xFF) / 255.0f,
                        ((argb >> 24) & 0xFF) / 255.0f);
        }

        /**
         * Blits a vanilla sprite PNG (loaded by {@link GuiSprites}) at
         * {@code (x, y)} sized {@code w x h} in GUI pixels. The full
         * texture is mapped onto the quad - use {@link #drawSprite9} for
         * the nine-slice borders vanilla applies to button panels.
         *
         * @return false when the sprite is not loaded yet; callers fall
         *         back to their procedural rendering in that case.
         */
        public static boolean drawSprite(String name, float x, float y, float w, float h) {
                if (!GuiSprites.isReady(name)) return false;
                return drawTexture(GuiSprites.texture(name),
                        0.0f, 0.0f, 1.0f, 1.0f, x, y, x + w, y + h);
        }

        /**
         * Nine-slice blit of a vanilla sprite, matching the
         * {@code gui.scaling: nine_slice} metadata the real 26.1.2
         * resources declare (widget/button* declare {@code border: 3}):
         * the {@code border}-pixel edges map 1:1 from the sprite edges,
         * the source middle stretches to fill the destination middle.
         *
         * @return false when the sprite is not loaded yet.
         */
        public static boolean drawSprite9(String name, float x, float y, float w, float h, int border) {
                return drawSprite9Slices(name, x, y, w, h, border, border, border, border);
        }

        /**
         * Nine-slice blit with independent per-side borders, for sprites
         * whose .png.mcmeta declares an uneven border object - the vanilla
         * {@code widget/tab*} sprites declare {@code left:2, top:2, right:2,
         * bottom:0} (the tab sits flush on the header separator, so its
         * bottom rows belong to the stretchable middle).
         *
         * @return false when the sprite is not loaded yet.
         */
        public static boolean drawSprite9Slices(String name, float x, float y, float w, float h,
                        int borderLeft, int borderTop, int borderRight, int borderBottom) {
                if (!GuiSprites.isReady(name)) return false;
                JSObject tex = GuiSprites.texture(name);
                if (w <= 0 || h <= 0) return true;

                // Never let the borders overlap the dest middle
                float bl = Math.min(borderLeft, w / 2.0f);
                float bt = Math.min(borderTop, h / 2.0f);
                float br = Math.min(borderRight, w / 2.0f);
                float bb = Math.min(borderBottom, h / 2.0f);

                // Destination spans: [edge, middle, edge]
                float[] dx = { x, x + bl, x + w - br, x + w };
                float[] dy = { y, y + bt, y + h - bb, y + h };
                // Source UV spans: edges are 1:1 (border px of the sprite),
                // the middle spans whatever the sprite has between them.
                float blU = bl / (float) GuiSprites.widthOf(name);
                float btV = bt / (float) GuiSprites.heightOf(name);
                float brU = br / (float) GuiSprites.widthOf(name);
                float bbV = bb / (float) GuiSprites.heightOf(name);
                float[] su = { 0.0f, blU, 1.0f - brU, 1.0f };
                float[] sv = { 0.0f, btV, 1.0f - bbV, 1.0f };

                for (int row = 0; row < 3; row++) {
                        for (int col = 0; col < 3; col++) {
                                float px0 = dx[col], px1 = dx[col + 1];
                                float py0 = dy[row], py1 = dy[row + 1];
                                if (px1 - px0 <= 0.0f || py1 - py0 <= 0.0f) continue;
                                drawTexture(tex, su[col], sv[row], su[col + 1], sv[row + 1],
                                        px0, py0, px1, py1);
                        }
                }
                return true;
        }

        /**
         * Tiled blit of a repeating background texture (vanilla
         * menu_background / menu_list_background are 16x16 tiles). The
         * pattern can be anchored at an arbitrary pixel offset so tiles
         * stay fixed relative to the screen while the list scrolls, the
         * way vanilla passes scroll-dependent u/v offsets.
         *
         * @return false when the sprite is not loaded yet.
         */
        public static boolean drawSpriteTiled(String name, float x, float y, float w, float h,
                        float uOffPx, float vOffPx) {
                return drawSpriteTiled(name, x, y, w, h, uOffPx, vOffPx, 0);
        }

        /**
         * Tiled blit with an explicit destination tile span. Vanilla draws
         * the menu backgrounds with {@code blit(..., 32, 32)} texture
         * dimensions, so the 16x16 {@code menu_background} PNG repeats
         * every 32 GUI pixels (2x zoom) - pass {@code destTileSize = 32}
         * for exactly that look. {@code destTileSize <= 0} restores the
         * native 1:1 tile size.
         *
         * @return false when the sprite is not loaded yet.
         */
        public static boolean drawSpriteTiled(String name, float x, float y, float w, float h,
                        float uOffPx, float vOffPx, int destTileSize) {
                if (!GuiSprites.isReady(name)) return false;
                JSObject tex = GuiSprites.texture(name);
                int tw = GuiSprites.widthOf(name);
                int th = GuiSprites.heightOf(name);
                if (tw <= 0 || th <= 0 || w <= 0 || h <= 0) return true;

                // Destination span of one texture tile (vanilla 32 for the
                // menu backgrounds), source always one full texture repeat
                float tileW = destTileSize > 0 ? (float) destTileSize : (float) tw;
                float tileH = destTileSize > 0 ? (float) destTileSize : (float) th;

                int offX = ((int) uOffPx) % (int) tileW;
                if (offX < 0) offX += tileW;
                int offY = ((int) vOffPx) % (int) tileH;
                if (offY < 0) offY += tileH;

                for (float ty = y; ty < y + h; ty += tileH) {
                        for (float tx = x; tx < x + w; tx += tileW) {
                                // Dest: exactly this tile's clipped rect (vanilla
                                // GuiSpriteScaling.Tiled blits one sprite-sized
                                // chunk per step and clips the last row/column).
                                float dw = Math.min(tileW, x + w - tx);
                                float dh = Math.min(tileH, y + h - ty);
                                // Source phase: scroll offset + tile index within
                                // the repeat, wrapped back into [0, tileW). The raw
                                // destination offset must NEVER feed the UVs - it
                                // grows across the loop and would stretch every
                                // later tile across the rest of the screen.
                                float su0 = ((offX + (tx - x)) % tileW) / tileW;
                                float sv0 = ((offY + (ty - y)) % tileH) / tileH;
                                float su1 = su0 + dw / tileW;
                                float sv1 = sv0 + dh / tileH;
                                // Split the source window at the texture seam (u
                                // and v independently, so up to 2x2 sub-quads).
                                // Part A keeps [phase..1], part B repeats [0..rest];
                                // the dest split point is the same seam in pixels.
                                float seamX = tx + (1.0f - su0) * tileW;
                                float seamY = ty + (1.0f - sv0) * tileH;
                                boolean uWrap = su1 > 1.0f;
                                boolean vWrap = sv1 > 1.0f;
                                // u pieces: {su0, su1End, dx0, dx1} per part
                                float[] us = uWrap
                                                ? new float[] { su0, 1.0f, tx, seamX,
                                                        0.0f, su1 - 1.0f, seamX, tx + dw }
                                                : new float[] { su0, su1, tx, tx + dw };
                                float[] vs = vWrap
                                                ? new float[] { sv0, 1.0f, ty, seamY,
                                                        0.0f, sv1 - 1.0f, seamY, ty + dh }
                                                : new float[] { sv0, sv1, ty, ty + dh };
                                for (int vi = 0; vi < vs.length; vi += 4) {
                                        for (int ui = 0; ui < us.length; ui += 4) {
                                                drawTexture(tex, us[ui], vs[vi], us[ui + 1], vs[vi + 1],
                                                                us[ui + 2], vs[vi + 2], us[ui + 3], vs[vi + 3]);
                                        }
                                }
                        }
                }
                return true;
        }

        /**
         * Blits an explicit sub-rectangle of a texture. Source coords are
         * normalized UVs (v=0 is the image top row).
         */
        private static boolean drawTexture(JSObject tex, float u0, float v0, float u1, float v1,
                        float x0, float y0, float x1, float y1) {
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null || !initialized || tex == null) return false;
                if (!batchOpen) {
                        openBatch(gl);
                }
                if (queuedVertices + VERTICES_PER_QUAD > MAX_QUADS * VERTICES_PER_QUAD) {
                        flush(gl);
                        openBatch(gl);
                }
                ensureTexture(tex);
                putVertex(x0, y0, 1, 1, 1, 1, u0, v0);
                putVertex(x0, y1, 1, 1, 1, 1, u0, v1);
                putVertex(x1, y1, 1, 1, 1, 1, u1, v1);
                putVertex(x0, y0, 1, 1, 1, 1, u0, v0);
                putVertex(x1, y1, 1, 1, 1, 1, u1, v1);
                putVertex(x1, y0, 1, 1, 1, 1, u1, v0);
                return true;
        }

        /**
         * Draws one line of text with the 5x7 bitmap font. When
         * {@code shadow} is on, a 1px dark copy is drawn at {@code +1,+1}
         * first, exactly like MC's {@code drawShadow}. Returns the width
         * of the drawn text in GUI pixels.
         */
        public static int drawText(String text, float x, float y, int argb, boolean shadow) {
                if (text == null || text.isEmpty()) return 0;
                if (shadow) {
                        int shadowColor = (argb & 0xFC000000) | ((argb >> 2) & 0x003F3F3F) | 0xFF000000;
                        drawTextRaw(text, x + 1.0f, y + 1.0f, shadowColor);
                }
                drawTextRaw(text, x, y, argb);
                return widthOf(text);
        }

        private static void drawTextRaw(String text, float x, float y, int argb) {
                float cursorX = x;
                for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (EaglerFont.hasGlyph(c)) {
                                long glyph = EaglerFont.glyph(c);
                                for (int row = 0; row < EaglerFont.GLYPH_HEIGHT; row++) {
                                        int runStart = -1;
                                        for (int col = 0; col <= EaglerFont.GLYPH_WIDTH; col++) {
                                                boolean set = col < EaglerFont.GLYPH_WIDTH
                                                        && EaglerFont.isPixelSet(glyph, col, row);
                                                if (set && runStart < 0) {
                                                        runStart = col;
                                                } else if (!set && runStart >= 0) {
                                                        fillRect(cursorX + runStart, y + row,
                                                                col - runStart, 1.0f, argb);
                                                        runStart = -1;
                                                }
                                        }
                                }
                        }
                        cursorX += EaglerFont.CHAR_ADVANCE;
                }
        }

        /**
         * Returns the width of the text as drawn (5px glyphs, 6px
         * advance, no trailing advance).
         */
        public static int widthOf(String text) {
                if (text == null || text.isEmpty()) return 0;
                return text.length() * EaglerFont.CHAR_ADVANCE - 1;
        }

        // ========== Internals ==========

        private static void openBatch(WebGL2RenderingContext gl) {
                PlatformOpenGL._wglUseProgram(guiProgram);
                PlatformOpenGL._wglUniform2f(uResolution, (float) guiWidth, (float) guiHeight);
                PlatformOpenGL._wglBindVertexArray(guiVAO);
                depthWasEnabled = PlatformOpenGL._wglIsEnabled(WebGL2RenderingContext.DEPTH_TEST);
                if (depthWasEnabled) {
                        PlatformOpenGL._wglDisable(WebGL2RenderingContext.DEPTH_TEST);
                }
                queuedVertices = 0;
                batchOpen = true;
        }

        /**
         * Switches the texture the queued quads sample from. All queued
         * quads share one texture, so switching flushes the outgoing
         * texture's quads first and re-opens the batch under the new
         * texture (the batch is always open again when this returns).
         */
        private static void ensureTexture(JSObject tex) {
                if (tex == null || tex == currentTexture) return;
                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl != null) {
                        if (batchOpen && queuedVertices > 0) {
                                flush(gl); // draws with the outgoing texture
                        }
                        openBatch(gl);
                }
                currentTexture = tex;
        }

        private static void putVertex(float x, float y, float r, float g, float b, float a,
                        float u, float v) {
                int base = queuedVertices * FLOATS_PER_VERTEX;
                vertexBuffer.set(base, x);
                vertexBuffer.set(base + 1, y);
                vertexBuffer.set(base + 2, r);
                vertexBuffer.set(base + 3, g);
                vertexBuffer.set(base + 4, b);
                vertexBuffer.set(base + 5, a);
                vertexBuffer.set(base + 6, u);
                vertexBuffer.set(base + 7, v);
                queuedVertices++;
        }
}
