package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * GL layer for the 3D world renderer (the block-pipeline counterpart of
 * {@link EaglerGuiImpl} and {@link MenuBlurRenderer}).
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>{@link #ensureProgram}: a #version 300 es shader pair - MVP
 *       transform, per-vertex color (face shade x tint bake), one bound
 *       block texture per draw.</li>
 *   <li>{@link #uploadMesh}: one interleaved VBO per 16x16x16 section
 *       (pos 3f, uv 2f, color 4ub), rebuilt wholesale when a section is
 *       dirty - the vanilla SectionRenderDispatcher job reduced to its
 *       synchronous browser form.</li>
 *   <li>{@link #drawSections}: depth-tested, backface-culled draw of
 *       every built section's opaque quads. Vanilla face shading comes
 *       pre-baked into the color attribute by the mesh builder.</li>
 *   <li>{@link #drawDestroyStage}: the break-progress overlay - the
 *       inflated (1.002) hit cube textured with destroy_stage_N,
 *       polygon-offset so the crack sits on the block surface (vanilla
 *       LevelRenderer.renderDestroyProgress semantics).</li>
 *   <li>{@link #drawSelectionBox}: the 12-edge hit outline. Vanilla
 *       renders GL lines with lineWidth max(2.5, width/1920*2.5) - WebGL
 *       caps lineWidth at 1, so each edge is drawn as a 2px-wide line
 *       (two triangles) to keep vanilla's visual weight.</li>
 * </ol>
 *
 * <p>Coordinate system: world blocks are unit cubes on the integer
 * grid; the matrix stack is a plain float[16] helper (column-major,
 * matching GL) - perspective projection x view rotation/translation,
 * exactly vanilla's Camera + GameRenderer pose reduction.</p>
 */
public final class WorldGlRenderer {

        private static boolean programReady = false;
        private static JSObject program = null;
        private static JSObject uMvp = null;
        private static JSObject uTex = null;
        private static JSObject vao = null;

        /** Interleaved: 3 floats pos, 2 floats uv, 1 float layer, 4 bytes color = 28 bytes. */
        public static final int VERTEX_BYTES = 28;
        public static final int STRIDE_FLOATS = 7;

        private WorldGlRenderer() {
        }

        // ========== Program ==========

        private static final String VSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "layout(location=0) in vec3 a_pos;"
                + "layout(location=1) in vec2 a_uv;"
                + "layout(location=2) in float a_layer;"
                + "layout(location=3) in vec4 a_color;"
                + "uniform mat4 u_mvp;"
                + "out vec2 v_uv;"
                + "out float v_layer;"
                + "out vec4 v_color;"
                + "void main() {"
                + "  gl_Position = u_mvp * vec4(a_pos, 1.0);"
                + "  v_uv = a_uv;"
                + "  v_layer = a_layer;"
                + "  v_color = a_color;"
                + "}";

        private static final String FSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "precision highp sampler2DArray;"
                + "uniform sampler2DArray u_tex;"
                + "in vec2 v_uv;"
                + "in float v_layer;"
                + "in vec4 v_color;"
                + "out vec4 frag;"
                + "void main() {"
                + "  vec4 c = texture(u_tex, vec3(v_uv, v_layer));"
                + "  if (c.a < 0.5) discard;"
                + "  frag = c * v_color;"
                + "}";

        /** Compiles the world program once. Returns false while unavailable. */
        public static boolean ensureProgram(WebGL2RenderingContext gl) {
                if (programReady) {
                        return true;
                }
                try {
                        JSObject vs = PlatformOpenGL._wglCreateShader(WebGL2RenderingContext.VERTEX_SHADER);
                        PlatformOpenGL._wglShaderSource(vs, VSH);
                        PlatformOpenGL._wglCompileShader(vs);
                        if (!PlatformOpenGL._wglGetShaderCompiled(vs)) {
                                ClientMain.warn("[WorldGl] vsh: " + PlatformOpenGL._wglGetShaderInfoLog(vs));
                                return false;
                        }
                        JSObject fs = PlatformOpenGL._wglCreateShader(WebGL2RenderingContext.FRAGMENT_SHADER);
                        PlatformOpenGL._wglShaderSource(fs, FSH);
                        PlatformOpenGL._wglCompileShader(fs);
                        if (!PlatformOpenGL._wglGetShaderCompiled(fs)) {
                                ClientMain.warn("[WorldGl] fsh: " + PlatformOpenGL._wglGetShaderInfoLog(fs));
                                return false;
                        }
                        program = PlatformOpenGL._wglCreateProgram();
                        PlatformOpenGL._wglAttachShader(program, vs);
                        PlatformOpenGL._wglAttachShader(program, fs);
                        PlatformOpenGL._wglLinkProgram(program);
                        if (!PlatformOpenGL._wglGetProgramLinked(program)) {
                                ClientMain.warn("[WorldGl] link: " + PlatformOpenGL._wglGetProgramInfoLog(program));
                                return false;
                        }
                        uMvp = PlatformOpenGL._wglGetUniformLocation(program, "u_mvp");
                        uTex = PlatformOpenGL._wglGetUniformLocation(program, "u_tex");
                        vao = PlatformOpenGL._wglCreateVertexArray();
                        PlatformOpenGL._wglBindVertexArray(vao);
                        PlatformOpenGL._wglEnableVertexAttribArray(0);
                        PlatformOpenGL._wglEnableVertexAttribArray(1);
                        PlatformOpenGL._wglEnableVertexAttribArray(2);
                        PlatformOpenGL._wglEnableVertexAttribArray(3);
                        PlatformOpenGL._wglBindVertexArray(null);
                        programReady = true;
                        ClientMain.log("[WorldGl] world program ready");
                        return true;
                } catch (Throwable t) {
                        ClientMain.warn("[WorldGl] program init failed: " + t.getMessage());
                        return false;
                }
        }

        // ========== Mesh upload ==========

        /**
         * Uploads (or replaces) a section mesh from packed vertex data.
         * Returns an opaque handle (the VBO), null on failure.
         */
        public static JSObject uploadMesh(WebGL2RenderingContext gl, Float32Array vertices) {
                if (!ensureProgram(gl)) {
                        return null;
                }
                JSObject vbo = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, vertices,
                        WebGL2RenderingContext.STATIC_DRAW);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                return vbo;
        }

        public static void deleteMesh(JSObject vbo) {
                if (vbo != null) {
                        PlatformOpenGL._wglDeleteBuffer(vbo);
                }
        }

        public static int vertexCount(Float32Array vertices) {
                return vertices.getLength() / STRIDE_FLOATS;
        }

        /** Float32Array view over a Java float[] (repo-proven creation path). */
        public static Float32Array pack(float[] data) {
                Float32Array out = Float32Array.create(data.length);
                for (int i = 0; i < data.length; i++) {
                        out.set(i, data[i]);
                }
                return out;
        }

        // ========== Matrix helpers (column-major float[16], GL layout) ==========

        /** Identity into out. */
        public static void identity(float[] m) {
                for (int i = 0; i < 16; i++) {
                        m[i] = 0.0f;
                }
                m[0] = m[5] = m[10] = m[15] = 1.0f;
        }

        /** Vanilla GameRenderer.getProjectionMatrix: symmetric perspective. */
        public static void perspective(float[] m, float fovDegrees, float aspect, float near, float far) {
                identity(m);
                float f = 1.0f / (float) Math.tan(fovDegrees * (Math.PI / 180.0f) * 0.5f);
                m[0] = f / aspect;
                m[5] = f;
                m[10] = (far + near) / (near - far);
                m[11] = -1.0f;
                m[14] = 2.0f * far * near / (near - far);
                m[15] = 0.0f;
        }

        /** View rotation+translation: pitch (X), then yaw (Y), then -eye pos. */
        public static void makeView(float[] m, double x, double y, double z, float yawDeg, float pitchDeg) {
                float yaw = yawDeg * (float) (Math.PI / 180.0f);
                float pitch = pitchDeg * (float) (Math.PI / 180.0f);
                float cy = (float) Math.cos(yaw);
                float sy = (float) Math.sin(yaw);
                float cp = (float) Math.cos(pitch);
                float sp = (float) Math.sin(pitch);
                identity(m);
                // Column-major rotation matrix R = Rx(pitch) * Ry(yaw), then
                // translate by -eye (the standard FPS view).
                m[0] = cy;
                m[2] = sy;
                m[4] = sp * sy;
                m[5] = cp;
                m[6] = -sp * cy;
                m[8] = -cp * sy;
                m[9] = sp;
                m[10] = cp * cy;
                m[12] = (float) (-(m[0] * x + m[4] * y + m[8] * z));
                m[13] = (float) (-(m[1] * x + m[5] * y + m[9] * z));
                m[14] = (float) (-(m[2] * x + m[6] * y + m[10] * z));
        }

        /** out = a * b (column-major). */
        public static void multiply(float[] out, float[] a, float[] b) {
                for (int c = 0; c < 4; c++) {
                        for (int r = 0; r < 4; r++) {
                                float sum = 0.0f;
                                for (int k = 0; k < 4; k++) {
                                        sum += a[k * 4 + r] * b[c * 4 + k];
                                }
                                tmp[c * 4 + r] = sum;
                        }
                }
                System.arraycopy(tmp, 0, out, 0, 16);
        }

        private static final float[] tmp = new float[16];

        // ========== Drawing ==========

        private static final float[] MVP = new float[16];
        private static final float[] PROJ = new float[16];
        private static final float[] VIEW = new float[16];

        /**
         * Sets up the frame state: program, MVP (projection x view), depth
         * test + cull on. Vanilla render level state for the opaque layer.
         */
        public static void beginFrame(WebGL2RenderingContext gl, double eyeX, double eyeY, double eyeZ,
                        float yaw, float pitch, float fovDegrees, int width, int height) {
                if (!ensureProgram(gl)) return;
                float aspect = height > 0 ? (float) width / (float) height : 1.0f;
                perspective(PROJ, fovDegrees, aspect, 0.05f, 1000.0f);
                makeView(VIEW, eyeX, eyeY, eyeZ, yaw, pitch);
                multiply(MVP, PROJ, VIEW);
        }

        /** Binds the world program + VAO and uploads the MVP uniform. */
        public static void bindState(WebGL2RenderingContext gl) {
                if (!programReady) return;
                PlatformOpenGL._wglUseProgram(program);
                PlatformOpenGL._wglBindVertexArray(vao);
                Float32Array mvpBuf = pack(MVP);
                PlatformOpenGL._wglUniformMatrix4fv(uMvp, false, mvpBuf);
                PlatformOpenGL._wglUniform1i(uTex, 0);
                PlatformOpenGL._wglActiveTexture(WebGL2RenderingContext.TEXTURE0);
        }
        /**
         * Draws one section's opaque mesh against the block texture ARRAY.
         * Depth test + backface cull on; no blending (vanilla solid layer).
         */
        public static boolean drawSections(WebGL2RenderingContext gl, JSObject vbo, int vertexCount,
                        JSObject arrayTexture) {
                if (!programReady || vbo == null || arrayTexture == null || vertexCount <= 0) {
                        return false;
                }
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.DEPTH_TEST);
                PlatformOpenGL._wglDepthMask(true);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.CULL_FACE);
                PlatformOpenGL._wglCullFace(WebGL2RenderingContext.BACK);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, arrayTexture);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                bindAttributes();
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                return true;
        }

        /**
         * The break-progress overlay: vanilla renderDestroyProgress - the
         * block cube inflated to 1.002 and drawn with the destroy_stage_N
         * texture, blended, polygon-offset toward the camera so the crack
         * sits exactly on the surface.
         */
        public static boolean drawDestroyStage(WebGL2RenderingContext gl, Float32Array cubeVerts, int vertexCount,
                        JSObject destroyTexture) {
                if (!programReady || destroyTexture == null || vertexCount <= 0) {
                        return false;
                }
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglBlendFunc(WebGL2RenderingContext.SRC_ALPHA,
                        WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA);
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.POLYGON_OFFSET_FILL);
                PlatformOpenGL._wglPolygonOffset(-1.0f, -4.0f);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, destroyTexture);
                JSObject vbo = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, cubeVerts,
                        WebGL2RenderingContext.STATIC_DRAW);
                bindAttributes();
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglDeleteBuffer(vbo);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.POLYGON_OFFSET_FILL);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
                return true;
        }

        /**
         * The hit outline: vanilla renderHitOutline draws the 12 cube edges
         * as black lines (alpha 0.4 in vanilla's debug color path; the
         * gameplay outline uses full-black 0xFF000000 with width ~2.5px).
         * WebGL caps lineWidth at 1.0, so each edge is a screen-facing
         * 2-triangle strip built by the caller (two quads per edge pair
         * sharing corner-adjacent faces).
         */
        public static boolean drawOutline(WebGL2RenderingContext gl, Float32Array lineVerts, int vertexCount) {
                if (!programReady || vertexCount <= 0) {
                        return false;
                }
                JSObject white = GuiSprites.getWhiteTexture();
                if (white == null) {
                        return false;
                }
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglBlendFunc(WebGL2RenderingContext.SRC_ALPHA,
                        WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, white);
                JSObject vbo = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, lineVerts,
                        WebGL2RenderingContext.STATIC_DRAW);
                bindAttributes();
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglDeleteBuffer(vbo);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
                return true;
        }

        /** Shared attribute layout: pos 3f, uv 2f, layer 1f, color 4ub. */
        private static void bindAttributes() {
                PlatformOpenGL._wglVertexAttribPointer(0, 3, WebGL2RenderingContext.FLOAT, false,
                        VERTEX_BYTES, 0);
                PlatformOpenGL._wglVertexAttribPointer(1, 2, WebGL2RenderingContext.FLOAT, false,
                        VERTEX_BYTES, 12);
                PlatformOpenGL._wglVertexAttribPointer(2, 1, WebGL2RenderingContext.FLOAT, false,
                        VERTEX_BYTES, 20);
                PlatformOpenGL._wglVertexAttribPointer(3, 4, WebGL2RenderingContext.UNSIGNED_BYTE, true,
                        VERTEX_BYTES, 24);
        }

        /** Frame teardown: unbind the pipeline so the GUI batch starts clean. */
        public static void endFrame(WebGL2RenderingContext gl) {
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, null);
                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglUseProgram(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
        }
}
