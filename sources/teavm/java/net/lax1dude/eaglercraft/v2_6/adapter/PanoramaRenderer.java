package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Vanilla 26.1.2 title-screen panorama: a perspective-projected unit cube
 * textured with a {@code GL_TEXTURE_CUBE_MAP} built from the six panorama
 * PNG faces, rotating slowly around the Y axis behind the menu GUI.
 *
 * <p>Mirrors the 26.1.2 decompilation (see docs/UI-FIDELITY.md):</p>
 * <ul>
 *   <li>{@code CubeMap.render(10.0F, spin)}: perspective (fov 85,
 *       near 0.05, far 10), model view = rotX(PI) * rotX(10deg) * rotY(spin),
 *       24-vertex unit cube (positions are the sample directions), 36 quad
 *       indices, no depth test (the camera sits inside a convex cube, every
 *       ray exits through exactly one face), opaque output.</li>
 *   <li>{@code CubeMapTexture.SUFFIXES}: GL face order
 *       [+X, -X, +Y, -Y, +Z, -Z] takes panorama_[1, 3, 5, 4, 0, 2].png;
 *       each face is stacked with {@code copyRect(swapY=true)}, i.e. the
 *       rows are flipped vertically before upload.</li>
 *   <li>{@code panorama.vsh/fsh}: {@code texCoord0 = Position} (object
 *       space), fragment samples the cubemap by direction.</li>
 *   <li>{@code Panorama.extractRenderState}: {@code spin = wrapDegrees(spin
 *       + realtimeDeltaTicks * panoramaSpeed * 0.1)}, rendered as
 *       {@code -spin} (default panoramaSpeed = 1.0).</li>
 * </ul>
 *
 * <p>Face PNGs load asynchronously through {@link GuiSprites}; until all six
 * decoded RGBA buffers are available {@link #render} draws nothing and the
 * caller shows a flat quad in the vanilla stub color #626F71 (the default
 * 26.1.2 jar ships 1x1 panorama textures of exactly that color, so the
 * fallback is bit-identical to vanilla-without-a-resource-pack).</p>
 */
public final class PanoramaRenderer {

        // ===== Vanilla CubeMap constants =====

        private static final float PROJECTION_FOV_DEG = 85.0F;
        private static final float PROJECTION_Z_NEAR = 0.05F;
        private static final float PROJECTION_Z_FAR = 10.0F;

        /** First argument of vanilla {@code CubeMap.render(10.0F, spin)}. */
        private static final float CUBE_ROT_X_DEG = 10.0F;

        /** Vanilla Panorama: spin += delta * panoramaSpeed * 0.1. */
        private static final float SPIN_GAIN = 0.1F;

        /** Default of the accessibility "panorama speed" option (0..1). */
        private static final float PANORAMA_SPEED = 1.0F;

        /**
         * Vanilla CubeMapTexture.SUFFIXES: GL face order
         * [+X, -X, +Y, -Y, +Z, -Z] loads panorama_{suffix}.png.
         */
        private static final String[] FACE_SUFFIXES = { "1", "3", "5", "4", "0", "2" };

        /** Vanilla default-jar panorama stub color (1x1 RGB 98,111,113). */
        public static final float STUB_R = 98.0f / 255.0f;
        public static final float STUB_G = 111.0f / 255.0f;
        public static final float STUB_B = 113.0f / 255.0f;

        // ===== GL objects =====

        private static boolean initialized = false;
        private static JSObject panoramaProgram = null;
        private static JSObject uProjMat = null;
        private static JSObject uModelViewMat = null;
        private static JSObject cubeVAO = null;
        private static JSObject cubeVBO = null;
        private static JSObject cubeIBO = null;
        private static JSObject cubeTexture = null;
        private static boolean facesFailed = false;

        private static final Float32Array projMatBuf = Float32Array.create(16);
        private static final Float32Array modelViewBuf = Float32Array.create(16);
        private static final float[] scratchA = new float[16];
        private static final float[] scratchB = new float[16];
        private static final float[] scratchC = new float[16];

        private static float spin = 0.0F;

        private PanoramaRenderer() {
        }

        // ===== Public API =====

        /**
         * Advances the panorama spin. Call once per frame with the
         * real-time frame delta expressed in ticks (seconds * 20),
         * matching vanilla's {@code getRealtimeDeltaTicks()}.
         */
        public static void tick(float realtimeDeltaTicks) {
                spin = wrapDegrees(spin + realtimeDeltaTicks * PANORAMA_SPEED * SPIN_GAIN);
        }

        /** Whether the cubemap texture is built and drawable. */
        public static boolean isReady() {
                return cubeTexture != null;
        }

        /** Current accumulated spin in degrees (telemetry). */
        public static float spinDegrees() {
                return spin;
        }

        /**
         * Draws the panorama cube over the whole framebuffer. Call after
         * the frame clear and before any GUI work that should appear on
         * top of it. Nothing happens until all six face PNGs have decoded
         * (the caller covers the screen with the flat stub color in the
         * meantime, which is what the default 26.1.2 assets look like
         * anyway).
         */
        public static void render(WebGL2RenderingContext gl, int fbWidth, int fbHeight) {
                if (gl == null || fbWidth <= 0 || fbHeight <= 0) return;
                if (!initialized) {
                        init(gl);
                        if (!initialized) return;
                }
                if (cubeTexture == null) {
                        pollFaces(gl);
                        if (cubeTexture == null) return;
                }

                // Perspective: fov 85, near 0.05, far 10, aspect = framebuffer
                perspective(PROJECTION_FOV_DEG, (float) fbWidth / (float) fbHeight,
                                PROJECTION_Z_NEAR, PROJECTION_Z_FAR, scratchA);
                copyInto(projMatBuf, scratchA);

                // Model view: rotX(PI) * rotX(10) * rotY(-spin) - the negation
                // happens in vanilla Panorama (PanoramaRenderState(-spin))
                rotX(180.0F, scratchA);
                rotX(CUBE_ROT_X_DEG, scratchB);
                mul(scratchA, scratchB, scratchC);
                rotY(-spin, scratchA);
                mul(scratchC, scratchA, scratchB);
                copyInto(modelViewBuf, scratchB);

                PlatformOpenGL._wglUseProgram(panoramaProgram);
                PlatformOpenGL._wglUniformMatrix4fv(uProjMat, false, projMatBuf);
                PlatformOpenGL._wglUniformMatrix4fv(uModelViewMat, false, modelViewBuf);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_CUBE_MAP, cubeTexture);
                PlatformOpenGL._wglBindVertexArray(cubeVAO);
                PlatformOpenGL._wglDrawElements(WebGL2RenderingContext.TRIANGLES, 36,
                                WebGL2RenderingContext.UNSIGNED_INT, 0);
                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_CUBE_MAP, null);
                PlatformOpenGL._wglUseProgram(null);
        }

        // ===== Setup =====

        /**
         * Compiles the panorama program (a GLSL ES 300 translation of
         * vanilla's core/panorama.vsh + .fsh) and builds the unit-cube
         * VBO/IBO exactly as vanilla {@code CubeMap.initializeVertices()}.
         */
        private static void init(WebGL2RenderingContext gl) {
                String vsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec3 a_position;\n"
                                + "uniform mat4 u_projMat;\n"
                                + "uniform mat4 u_modelViewMat;\n"
                                + "out vec3 v_dir;\n"
                                + "void main() {\n"
                                + "  gl_Position = u_projMat * u_modelViewMat * vec4(a_position, 1.0);\n"
                                + "  v_dir = a_position;\n"
                                + "}\n";

                String fsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec3 v_dir;\n"
                                + "uniform samplerCube u_panorama;\n"
                                + "out vec4 fragColor;\n"
                                + "void main() {\n"
                                + "  fragColor = texture(u_panorama, v_dir);\n"
                                + "}\n";

                panoramaProgram = EaglerShaderImpl.createProgramFromSource(vsh, fsh, "panorama_cubemap");
                if (panoramaProgram == null) {
                        ClientMain.error("[PanoramaRenderer] failed to create the panorama program");
                        return;
                }
                uProjMat = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_projMat");
                uModelViewMat = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_modelViewMat");

                // Vanilla CubeMap.initializeVertices(): 6 quads in face order
                // +Z, +X, -Z, -X, -Y, +Y (camera sits inside; winding is
                // outward so the inside faces are geometric back faces)
                float[] cube = new float[] {
                                // +Z
                                -1.0f, -1.0f,  1.0f,  -1.0f, 1.0f,  1.0f,
                                 1.0f,  1.0f,  1.0f,   1.0f, -1.0f,  1.0f,
                                // +X
                                 1.0f, -1.0f,  1.0f,   1.0f, 1.0f,  1.0f,
                                 1.0f,  1.0f, -1.0f,   1.0f, -1.0f, -1.0f,
                                // -Z
                                 1.0f, -1.0f, -1.0f,   1.0f, 1.0f, -1.0f,
                                -1.0f,  1.0f, -1.0f,  -1.0f, -1.0f, -1.0f,
                                // -X
                                -1.0f, -1.0f, -1.0f,  -1.0f, 1.0f, -1.0f,
                                -1.0f,  1.0f,  1.0f,  -1.0f, -1.0f,  1.0f,
                                // -Y
                                -1.0f, -1.0f, -1.0f,  -1.0f, -1.0f,  1.0f,
                                 1.0f, -1.0f,  1.0f,   1.0f, -1.0f, -1.0f,
                                // +Y
                                -1.0f,  1.0f,  1.0f,  -1.0f, 1.0f, -1.0f,
                                 1.0f,  1.0f, -1.0f,   1.0f,  1.0f,  1.0f
                };

                int[] quadIndices = new int[36];
                for (int q = 0; q < 6; q++) {
                        int v = q * 4;
                        quadIndices[q * 6 + 0] = v + 0;
                        quadIndices[q * 6 + 1] = v + 1;
                        quadIndices[q * 6 + 2] = v + 2;
                        quadIndices[q * 6 + 3] = v + 2;
                        quadIndices[q * 6 + 4] = v + 1;
                        quadIndices[q * 6 + 5] = v + 3;
                }

                cubeVAO = PlatformOpenGL._wglCreateVertexArray();
                PlatformOpenGL._wglBindVertexArray(cubeVAO);

                cubeVBO = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, cubeVBO);
                Float32Array vboData = Float32Array.create(cube.length);
                for (int i = 0; i < cube.length; i++) {
                        vboData.set(i, cube[i]);
                }
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, vboData,
                                WebGL2RenderingContext.STATIC_DRAW);

                int posAttrib = EaglerShaderImpl.getAttribLocation(panoramaProgram, "a_position");
                PlatformOpenGL._wglEnableVertexAttribArray(posAttrib);
                PlatformOpenGL._wglVertexAttribPointer(posAttrib, 3,
                                WebGL2RenderingContext.FLOAT, false, 12, 0);

                cubeIBO = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER, cubeIBO);
                Int32Array iboData = Int32Array.create(quadIndices.length);
                for (int i = 0; i < quadIndices.length; i++) {
                        iboData.set(i, quadIndices[i]);
                }
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER, iboData,
                                WebGL2RenderingContext.STATIC_DRAW);

                // Unbind the VAO FIRST: the ELEMENT_ARRAY_BUFFER binding is
                // VAO state, so clearing it while cubeVAO is still bound
                // would leave the VAO without an index buffer at draw time.
                // ARRAY_BUFFER is global state, not captured by the VAO.
                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ELEMENT_ARRAY_BUFFER, null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);

                initialized = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[PanoramaRenderer] program + cube geometry ready");
                }
        }

        /**
         * Builds the cubemap once all six face PNGs have decoded through
         * {@link GuiSprites}: GL face order [+X,-X,+Y,-Y,+Z,-Z] takes
         * panorama_[1,3,5,4,0,2].png, each face vertically flipped before
         * upload (vanilla stacks with copyRect(swapY=true)).
         */
        private static void pollFaces(WebGL2RenderingContext gl) {
                if (facesFailed) return;
                if (cubeTexture != null) return;

                for (int f = 0; f < 6; f++) {
                        String name = GuiSprites.PANORAMA_PREFIX + FACE_SUFFIXES[f];
                        if (GuiSprites.isFailed(name)) {
                                facesFailed = true;
                                ClientMain.warn("[PanoramaRenderer] face '" + name
                                                + "' failed to load - keeping the flat stub background");
                                return;
                        }
                        if (GuiSprites.rawPanoramaData(name) == null) {
                                return; // still downloading
                        }
                }

                JSObject tex = PlatformOpenGL._wglCreateTexture();
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_CUBE_MAP, tex);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_FLIP_Y_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_ALIGNMENT, 1);

                int faceW = 0;
                int faceH = 0;
                boolean allPowerOfTwo = true;
                for (int f = 0; f < 6; f++) {
                        String name = GuiSprites.PANORAMA_PREFIX + FACE_SUFFIXES[f];
                        GuiSprites.SpriteData data = GuiSprites.rawPanoramaData(name);
                        int w = data.getW();
                        int h = data.getH();
                        if (f == 0) {
                                faceW = w;
                                faceH = h;
                        } else if (w != faceW || h != faceH) {
                                facesFailed = true;
                                ClientMain.warn("[PanoramaRenderer] cubemap face size mismatch on '" + name
                                                + "' (" + w + "x" + h + " vs " + faceW + "x" + faceH + ")");
                                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_CUBE_MAP, null);
                                return;
                        }
                        allPowerOfTwo &= isPowerOfTwo(w) && isPowerOfTwo(h);

                        // Vanilla copyRect(..., swapY=true): rows flip
                        Uint8Array src = data.getData();
                        Uint8Array flipped = Uint8Array.create(w * h * 4);
                        for (int y = 0; y < h; y++) {
                                int srcRow = y * w * 4;
                                int dstRow = (h - 1 - y) * w * 4;
                                for (int x = 0; x < w * 4; x++) {
                                        flipped.set(dstRow + x, src.get(srcRow + x));
                                }
                        }
                        // GL cube face targets are sequential from
                        // TEXTURE_CUBE_MAP (0x8513): +X, -X, +Y, -Y, +Z, -Z
                        PlatformOpenGL._wglTexImage2D(
                                        WebGL2RenderingContext.TEXTURE_CUBE_MAP + f, 0,
                                        WebGL2RenderingContext.RGBA, w, h, 0,
                                        WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE,
                                        flipped);
                }

                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_CUBE_MAP,
                                WebGL2RenderingContext.TEXTURE_MAG_FILTER, WebGL2RenderingContext.LINEAR);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_CUBE_MAP,
                                WebGL2RenderingContext.TEXTURE_MIN_FILTER,
                                allPowerOfTwo ? WebGL2RenderingContext.LINEAR_MIPMAP_LINEAR
                                                : WebGL2RenderingContext.LINEAR);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_CUBE_MAP,
                                WebGL2RenderingContext.TEXTURE_WRAP_S, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_CUBE_MAP,
                                WebGL2RenderingContext.TEXTURE_WRAP_T, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_CUBE_MAP,
                                WebGL2RenderingContext.TEXTURE_WRAP_R, WebGL2RenderingContext.CLAMP_TO_EDGE);
                if (allPowerOfTwo) {
                        PlatformOpenGL._wglGenerateMipmap(WebGL2RenderingContext.TEXTURE_CUBE_MAP);
                }
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_CUBE_MAP, null);

                cubeTexture = tex;
                ClientMain.log("[PanoramaRenderer] cubemap built from 6 faces (" + faceW + "x" + faceH + ")");
        }

        // ===== Math =====

        private static boolean isPowerOfTwo(int v) {
                return v > 0 && (v & (v - 1)) == 0;
        }

        /** Vanilla Mth.wrapDegrees: maps to [-180, 180). */
        private static float wrapDegrees(float v) {
                float d = v % 360.0F;
                if (d >= 180.0F) d -= 360.0F;
                if (d < -180.0F) d += 360.0F;
                return d;
        }

        /** Column-major perspective matrix (fov vertical, degrees). */
        private static void perspective(float fovDeg, float aspect, float near, float far, float[] out) {
                for (int i = 0; i < 16; i++) out[i] = 0.0F;
                float f = 1.0F / (float) Math.tan(fovDeg * Math.PI / 360.0);
                out[0] = f / aspect;
                out[5] = f;
                out[10] = (far + near) / (near - far);
                out[11] = -1.0F;
                out[14] = (2.0F * far * near) / (near - far);
        }

        private static void rotX(float deg, float[] out) {
                for (int i = 0; i < 16; i++) out[i] = 0.0F;
                float a = deg * (float) (Math.PI / 180.0);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                out[0] = 1.0F;
                out[5] = c;  out[6] = s;
                out[9] = -s; out[10] = c;
                out[15] = 1.0F;
        }

        private static void rotY(float deg, float[] out) {
                for (int i = 0; i < 16; i++) out[i] = 0.0F;
                float a = deg * (float) (Math.PI / 180.0);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                out[0] = c;  out[2] = -s;
                out[5] = 1.0F;
                out[8] = s;  out[10] = c;
                out[15] = 1.0F;
        }

        /** out = a * b (column-major 4x4). */
        private static void mul(float[] a, float[] b, float[] out) {
                for (int col = 0; col < 4; col++) {
                        for (int row = 0; row < 4; row++) {
                                float sum = 0.0F;
                                for (int k = 0; k < 4; k++) {
                                        sum += a[k * 4 + row] * b[col * 4 + k];
                                }
                                out[col * 4 + row] = sum;
                        }
                }
        }

        private static void copyInto(Float32Array target, float[] src) {
                for (int i = 0; i < 16; i++) {
                        target.set(i, src[i]);
                }
        }
}
