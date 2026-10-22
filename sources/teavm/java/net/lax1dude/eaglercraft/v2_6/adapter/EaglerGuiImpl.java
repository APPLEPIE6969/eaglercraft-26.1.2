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
 * <p>Minecraft 26.1.2 draws every 2D element (button panels, hotbar,
 * crosshair, text) as colored quads. On the desktop those go through the
 * core-renderer pipelines; in EaglerCraft they are batched here into a
 * single dynamic VBO and drawn with one {@code drawArrays} call per
 * flush. Positions are in GUI-scaled pixels with the origin at the
 * top-left corner of the canvas, which matches how {@code Screen} and
 * its widgets address the screen.</p>
 *
 * <h3>Frame protocol</h3>
 * <ol>
 *   <li>{@link #begin} once per frame with the GUI-scaled dimensions
 *       (cheap; subsequent calls just update the resolution uniform).</li>
 *   <li>Any number of {@link #fillRect} / {@link #drawText} calls.
 *       The batch re-binds its program/VAO lazily, so other GL code
 *       (e.g. the title-screen gradient shader) may run in between.</li>
 *   <li>{@link #flush} to draw everything queued so far. Call again
 *       after any interleaved GL work that must stay underneath the
 *       next batch of quads.</li>
 * </ol>
 *
 * <p>Depth testing is disabled while a batch draws (GUI pixels are all
 * at z=0 and would otherwise z-fight); the previous depth-test enable
 * state is restored on flush.</p>
 */
public final class EaglerGuiImpl {

        /** Vertex count per quad (two triangles). */
        private static final int VERTICES_PER_QUAD = 6;

        /** Floats per vertex: x, y, r, g, b, a. */
        private static final int FLOATS_PER_VERTEX = 6;

        /** Maximum quads queued before an automatic mid-batch flush. */
        private static final int MAX_QUADS = 16384;

        private static JSObject guiProgram = null;
        private static JSObject guiVAO = null;
        private static JSObject guiVBO = null;
        private static JSObject uResolution = null;
        private static int aPos = -1;
        private static int aColor = -1;
        private static Float32Array vertexBuffer = null;

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
         * vertex buffer. Runs once per page load.
         */
        private static void init(WebGL2RenderingContext gl) {
                if (initialized) return;

                String vsh = "#version 300 es\n"
                        + "precision highp float;\n"
                        + "in vec2 a_pos;\n"
                        + "in vec4 a_color;\n"
                        + "uniform vec2 u_resolution;\n"
                        + "out vec4 v_color;\n"
                        + "void main() {\n"
                        + "  vec2 ndc = vec2(a_pos.x / u_resolution.x * 2.0 - 1.0,"
                        + " 1.0 - a_pos.y / u_resolution.y * 2.0);\n"
                        + "  gl_Position = vec4(ndc, 0.0, 1.0);\n"
                        + "  v_color = a_color;\n"
                        + "}\n";

                String fsh = "#version 300 es\n"
                        + "precision mediump float;\n"
                        + "in vec4 v_color;\n"
                        + "out vec4 fragColor;\n"
                        + "void main() {\n"
                        + "  fragColor = v_color;\n"
                        + "}\n";

                guiProgram = EaglerShaderImpl.createProgramFromSource(vsh, fsh, "gui_2d");
                if (guiProgram == null) {
                        ClientMain.error("[EaglerGuiImpl] Failed to create the 2D GUI shader program");
                        return;
                }

                uResolution = EaglerShaderImpl.getUniformLocation(guiProgram, "u_resolution");
                aPos = EaglerShaderImpl.getAttribLocation(guiProgram, "a_pos");
                aColor = EaglerShaderImpl.getAttribLocation(guiProgram, "a_color");

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

                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);

                initialized = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[EaglerGuiImpl] 2D GUI renderer initialized");
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
                        PlatformOpenGL._wglBindVertexArray(guiVAO);
                        PlatformOpenGL._wglBufferSubData(WebGL2RenderingContext.ARRAY_BUFFER, 0, vertexBuffer);
                        PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, queuedVertices);
                        PlatformOpenGL._wglBindVertexArray(null);
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

                float x2 = x + w;
                float y2 = y + h;
                putVertex(x, y, r, g, b, a);
                putVertex(x, y2, r, g, b, a);
                putVertex(x2, y2, r, g, b, a);
                putVertex(x, y, r, g, b, a);
                putVertex(x2, y2, r, g, b, a);
                putVertex(x2, y, r, g, b, a);
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

        private static void putVertex(float x, float y, float r, float g, float b, float a) {
                int base = queuedVertices * FLOATS_PER_VERTEX;
                vertexBuffer.set(base, x);
                vertexBuffer.set(base + 1, y);
                vertexBuffer.set(base + 2, r);
                vertexBuffer.set(base + 3, g);
                vertexBuffer.set(base + 4, b);
                vertexBuffer.set(base + 5, a);
                queuedVertices++;
        }
}
