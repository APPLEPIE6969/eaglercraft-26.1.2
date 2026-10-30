package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Vanilla 26.1.2 title-screen panorama: the six pre-rendered face PNGs
 * drawn over the whole framebuffer with the vanilla spin (Panorama.spin +=
 * delta * speed * 0.1) and the 10-degree downward camera tilt of vanilla
 * {@code CubeMap.render(10.0F, spin)}.
 *
 * <h3>Why a 2D face array instead of a GL cubemap</h3>
 *
 * <p>Two earlier attempts died on texture-convention grounds: a unit cube
 * around the camera (vanilla {@code CubeMap.initializeVertices()}) left
 * rotating clip wedges from its near-plane bowties, and a
 * {@code GL_TEXTURE_CUBE_MAP} routed every face through GL's per-face
 * (u,v) layouts - the uploaded faces kept coming out mirrored or flipped
 * no matter which of vanilla's copyRect(swapY) conventions was applied.
 * This version samples a {@code TEXTURE_2D_ARRAY} of the six faces with
 * explicit per-fragment yaw/pitch math in the exact conventions the face
 * generator renders them (face 0 = yaw 0 = +Z, image row 0 = the top of
 * the rendered view): every texel's provenance is controlled from here,
 * so the title screen shows the faces exactly as they were rendered.</p>
 *
 * <ul>
 *   <li>Layer mapping: 0..3 = side faces yaw {0,-90,180,90} (panorama_
 *       {0,1,2,3}.png), 4 = down (panorama_4), 5 = up (panorama_5).</li>
 *   <li>At fov 85 with the 10-degree tilt the view spans pitch
 *       [-52.5, +32.5] degrees - the up face is never reached and the
 *       below-horizon sliver (pitch &lt; -45) clamps to the side faces'
 *       bottom row, which is exactly the grass the down face holds.</li>
 *   <li>Angle units: the shader's trig consumes radians, so every
 *       angle crossing the Java/GLSL boundary is converted with
 *       {@code Math.toRadians} at the single uniform call site.
 *       Vanilla hands degrees to CubeMap.render, and the original
 *       port's straight pass-through is what spun the screen
 *       upside-down at ~57x speed.</li>
 *   <li>The spin clock clamps each frame delta to
 *       {@link #MAX_FRAME_DELTA_TICKS}: a background tab hands back a
 *       huge (or NaN) requestAnimationFrame delta on wake-up, and the
 *       panorama must not lurch on resume.</li>
 * </ul>
 */
public final class PanoramaRenderer {

        // ===== Vanilla CubeMap constants =====

        private static final float PROJECTION_FOV_DEG = 85.0F;

        /** First argument of vanilla {@code CubeMap.render(10.0F, spin)} -
         * the camera pitches 10 degrees down. */
        private static final float CUBE_ROT_X_DEG = 10.0F;

        /** Vanilla Panorama: spin += delta * panoramaSpeed * 0.1. */
        private static final float SPIN_GAIN = 0.1F;

        /** Default of the accessibility "panorama speed" option (0..1). */
        private static final float PANORAMA_SPEED = 1.0F;

        /** Largest frame delta the spin clock accepts, in ticks - guards
         * against the huge (or NaN) requestAnimationFrame deltas a
         * background tab produces when it wakes up. */
        private static final float MAX_FRAME_DELTA_TICKS = 10.0F;

        /**
         * Face file suffix per layer: layer 0..3 are the side faces
         * (panorama_0..3), 4 = down, 5 = up - the plain face numbers, in
         * the generator's own naming.
         */
        private static final String[] FACE_SUFFIXES = { "0", "1", "2", "3", "4", "5" };

        /** Layer i's yaw center in MC degrees (0=+Z, -90=+X, 180=-Z, 90=-X). */
        private static final float[] FACE_YAW = { 0.0F, -90.0F, 180.0F, 90.0F };

        /** Vanilla default-jar panorama stub color (1x1 RGB 98,111,113). */
        public static final float STUB_R = 98.0f / 255.0f;
        public static final float STUB_G = 111.0f / 255.0f;
        public static final float STUB_B = 113.0f / 255.0f;

        // ===== GL objects =====

        private static boolean initialized = false;
        private static JSObject panoramaProgram = null;
        private static JSObject uYaw = null;
        private static JSObject uPitch = null;
        private static JSObject uTanHalfFov = null;
        private static JSObject uAspect = null;
        private static JSObject triVAO = null;
        private static JSObject triVBO = null;
        private static JSObject faceTexture = null;
        private static boolean facesFailed = false;

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
                // Browser clock guard: a background tab hands back one huge
                // (or NaN) delta when it wakes up - clamp instead of letting
                // the panorama lurch. Vanilla never stalls its clock, so
                // this only ever clips catch-up, never steady-state spin.
                if (!(realtimeDeltaTicks > 0.0F)) return;
                realtimeDeltaTicks = Math.min(realtimeDeltaTicks, MAX_FRAME_DELTA_TICKS);
                spin = wrapDegrees(spin + realtimeDeltaTicks * PANORAMA_SPEED * SPIN_GAIN);
        }

        /** Whether the face texture is built and drawable. */
        public static boolean isReady() {
                return faceTexture != null;
        }

        /** Current accumulated spin in degrees (telemetry). */
        public static float spinDegrees() {
                return spin;
        }

        /**
         * Polls the pending face downloads and draws the panorama over the
         * whole framebuffer. Call after the frame clear and before any GUI
         * work that should appear on top of it.
         *
         * @return true when the faces were drawn; false while the six face
         *         PNGs are still decoding or failed (the caller covers the
         *         screen with the flat stub color, which is what the default
         *         26.1.2 assets look like anyway). The poll happens inside
         *         this call, so callers must invoke it unconditionally -
         *         gating on {@link #isReady()} first would deadlock the
         *         build-once texture.
         */
        public static boolean render(WebGL2RenderingContext gl, int fbWidth, int fbHeight) {
                if (gl == null || fbWidth <= 0 || fbHeight <= 0) return false;
                if (!initialized) {
                        init(gl);
                        if (!initialized) return false;
                }
                if (faceTexture == null) {
                        pollFaces(gl);
                        if (faceTexture == null) return false;
                }

                PlatformOpenGL._wglUseProgram(panoramaProgram);
                // Vanilla's CubeMap.render(10.0F, spin) camera tilt: +10 in
                // this shader's ray rotation puts the horizon just below
                // screen center (more ground than sky), grid-search verified.
                //
                // All angles cross the Java/GLSL boundary in radians,
                // converted at this single call site. Vanilla passes degrees
                // to CubeMap.render; feeding those straight into the shader's
                // trig consumed them as radians (10 degrees became 213
                // degrees of camera tumble) - the unit bug that turned the
                // screen upside down and spun it ~57x too fast.
                PlatformOpenGL._wglUniform1f(uYaw, (float) Math.toRadians(spin));
                PlatformOpenGL._wglUniform1f(uPitch, (float) Math.toRadians(CUBE_ROT_X_DEG));
                PlatformOpenGL._wglUniform1f(uTanHalfFov,
                                (float) Math.tan(PROJECTION_FOV_DEG * Math.PI / 360.0));
                PlatformOpenGL._wglUniform1f(uAspect, (float) fbWidth / (float) fbHeight);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, faceTexture);

                boolean cullWasEnabled = PlatformOpenGL._wglIsEnabled(WebGL2RenderingContext.CULL_FACE);
                if (cullWasEnabled) {
                        PlatformOpenGL._wglDisable(WebGL2RenderingContext.CULL_FACE);
                }
                PlatformOpenGL._wglBindVertexArray(triVAO);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, 3);
                PlatformOpenGL._wglBindVertexArray(null);
                if (cullWasEnabled) {
                        PlatformOpenGL._wglEnable(WebGL2RenderingContext.CULL_FACE);
                }
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, null);
                PlatformOpenGL._wglUseProgram(null);
                return true;
        }

        // ===== Setup =====

        /**
         * Compiles the panorama program (a fullscreen-triangle view-ray
         * pass over the face array) and builds the 3-vertex NDC triangle.
         */
        private static void init(WebGL2RenderingContext gl) {
                String vsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec2 a_pos;\n"
                                + "out vec2 v_ndc;\n"
                                + "void main() {\n"
                                + "  v_ndc = a_pos;\n"
                                + "  gl_Position = vec4(a_pos, 0.0, 1.0);\n"
                                + "}\n";

                // Ray -> (yaw, pitch) -> face layer + (u,v). The faces were
                // rendered with yaw 0 = +Z (face 0), yaw decreasing to the
                // right of each image, and image row 0 at the view's top
                // (pitch +45 for the side faces), which is exactly the
                // generator's camera - no GL cubemap conventions involved.
                String fsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec2 v_ndc;\n"
                                + "uniform highp sampler2DArray u_faces;\n"
                                + "uniform float u_yaw;\n"
                                + "uniform float u_pitch;\n"
                                + "uniform float u_tanHalfFov;\n"
                                + "uniform float u_aspect;\n"
                                + "out vec4 fragColor;\n"
                                + "float wrapDeg(float d) {\n"
                                + "  d = mod(d + 180.0, 360.0) - 180.0;\n"
                                + "  return d;\n"
                                + "}\n"
                                + "void main() {\n"
                                + "  vec3 dir = normalize(vec3(v_ndc.x * u_tanHalfFov * u_aspect,\n"
                                + "                             v_ndc.y * u_tanHalfFov, -1.0));\n"
                                + "  float cp = cos(u_pitch), sp = sin(u_pitch);\n"
                                + "  vec3 d1 = vec3(dir.x, dir.y * cp - dir.z * sp, dir.y * sp + dir.z * cp);\n"
                                + "  float cy = cos(u_yaw), sy = sin(u_yaw);\n"
                                + "  vec3 d2 = vec3(d1.x * cy + d1.z * sy, d1.y, -d1.x * sy + d1.z * cy);\n"
                                + "  float pitchDeg = degrees(asin(clamp(d2.y, -1.0, 1.0)));\n"
                                + "  float yawDeg = wrapDeg(degrees(atan(-d2.x, d2.z)));\n"
                                + "  float layer;\n"
                                + "  float centerYaw;\n"
                                + "  if (yawDeg > -45.0 && yawDeg <= 45.0) { layer = 0.0; centerYaw = 0.0; }\n"
                                + "  else if (yawDeg > -135.0 && yawDeg <= -45.0) { layer = 1.0; centerYaw = -90.0; }\n"
                                + "  else if (yawDeg > 45.0 && yawDeg <= 135.0) { layer = 3.0; centerYaw = 90.0; }\n"
                                + "  else { layer = 2.0; centerYaw = 180.0; }\n"
                                + "  float rel = wrapDeg(yawDeg - centerYaw);\n"
                                + "  float u = 0.5 - rel / 90.0;\n"
                                + "  // Row 0 of each uploaded face is the rendered view's\n"
                                + "  // top (pitch +45), so v ramps down as the ray pitches down\n"
                                + "  float v = clamp((45.0 - pitchDeg) / 90.0, 0.0, 1.0);\n"
                                + "  fragColor = texture(u_faces, vec3(u, v, layer));\n"
                                + "}\n";

                panoramaProgram = EaglerShaderImpl.createProgramFromSource(vsh, fsh, "panorama_faces");
                if (panoramaProgram == null) {
                        ClientMain.error("[PanoramaRenderer] failed to create the panorama program");
                        return;
                }
                uYaw = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_yaw");
                uPitch = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_pitch");
                uTanHalfFov = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_tanHalfFov");
                uAspect = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_aspect");

                // Fullscreen triangle: (-1,-1) (3,-1) (-1,3) covers NDC [-1..1]^2
                float[] tri = new float[] { -1.0f, -1.0f, 3.0f, -1.0f, -1.0f, -1.0f + 4.0f };

                triVAO = PlatformOpenGL._wglCreateVertexArray();
                PlatformOpenGL._wglBindVertexArray(triVAO);

                triVBO = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, triVBO);
                Float32Array vboData = Float32Array.create(tri.length);
                for (int i = 0; i < tri.length; i++) {
                        vboData.set(i, tri[i]);
                }
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, vboData,
                                WebGL2RenderingContext.STATIC_DRAW);

                int posAttrib = EaglerShaderImpl.getAttribLocation(panoramaProgram, "a_pos");
                PlatformOpenGL._wglEnableVertexAttribArray(posAttrib);
                PlatformOpenGL._wglVertexAttribPointer(posAttrib, 2,
                                WebGL2RenderingContext.FLOAT, false, 8, 0);

                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);

                initialized = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[PanoramaRenderer] program + face-array pass ready");
                }
        }

        /**
         * Builds the face texture once all six PNGs have decoded through
         * {@link GuiSprites}: one TEXTURE_2D_ARRAY, six 768-square layers,
         * each uploaded exactly as decoded (row 0 = the rendered view's
         * top - the shader's v math matches).
         */
        private static void pollFaces(WebGL2RenderingContext gl) {
                if (facesFailed) return;
                if (faceTexture != null) return;

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

                int faceW = 0;
                int faceH = 0;
                for (int f = 0; f < 6; f++) {
                        GuiSprites.SpriteData data = GuiSprites.rawPanoramaData(
                                        GuiSprites.PANORAMA_PREFIX + FACE_SUFFIXES[f]);
                        if (f == 0) {
                                faceW = data.getW();
                                faceH = data.getH();
                        } else if (data.getW() != faceW || data.getH() != faceH) {
                                facesFailed = true;
                                ClientMain.warn("[PanoramaRenderer] face size mismatch on layer " + f
                                                + " (" + data.getW() + "x" + data.getH()
                                                + " vs " + faceW + "x" + faceH + ")");
                                return;
                        }
                }

                JSObject tex = PlatformOpenGL._wglCreateTexture();
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, tex);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_FLIP_Y_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_ALIGNMENT, 1);

                PlatformOpenGL._wglTexImage3D(WebGL2RenderingContext.TEXTURE_2D_ARRAY, 0,
                                WebGL2RenderingContext.RGBA, faceW, faceH, 6, 0,
                                WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE, null);
                for (int f = 0; f < 6; f++) {
                        GuiSprites.SpriteData data = GuiSprites.rawPanoramaData(
                                        GuiSprites.PANORAMA_PREFIX + FACE_SUFFIXES[f]);
                        PlatformOpenGL._wglTexSubImage3D(WebGL2RenderingContext.TEXTURE_2D_ARRAY, 0,
                                        0, 0, f, faceW, faceH, 1,
                                        WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE,
                                        data.getData());
                }

                // NEAREST/NEAREST like the block atlas: no mip chain (the
                // 2D-array mip sampling is unreliable on SwiftShader), no
                // wrap bleed - every texel provenance stays explicit
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                                WebGL2RenderingContext.TEXTURE_MIN_FILTER, WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                                WebGL2RenderingContext.TEXTURE_MAG_FILTER, WebGL2RenderingContext.NEAREST);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                                WebGL2RenderingContext.TEXTURE_WRAP_S, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D_ARRAY,
                                WebGL2RenderingContext.TEXTURE_WRAP_T, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D_ARRAY, null);

                faceTexture = tex;
                ClientMain.log("[PanoramaRenderer] face array built (" + faceW + "x" + faceH + " x6)");
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
}
