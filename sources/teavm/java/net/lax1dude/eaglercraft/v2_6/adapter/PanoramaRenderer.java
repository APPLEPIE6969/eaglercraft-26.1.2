package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Vanilla 1.8 title-screen panorama, replicated literally from the
 * decompiled {@code GuiMainMenu.drawPanorama/renderSkybox} (MCP 1.8.x) and
 * validated texel-for-texel against the real 1.8 jar assets with a numpy
 * twin of this math (scripts/pano_sim2.py) before being ported here.
 *
 * <h3>The vanilla method, end to end</h3>
 *
 * <ol>
 *   <li>Vanilla renders the six face quads into a square 256x256 target
 *       with {@code gluPerspective(120, 1, 0.05, 10)} and the modelview
 *       {@code rotX(180).rotZ(90) . rotX(pitch).rotY(yaw) . R_face}, where
 *       {@code pitch = 20 + 25*sin(t/400)} degrees,
 *       {@code yaw = -0.1*t} degrees and {@code t} is the client tick
 *       clock (20/s). The quads sit at local z=+1 spanning +-1 with
 *       {@code uv = ((x+1)/2, (y+1)/2)}, v=0 = image top row.</li>
 *   <li>The fixed {@code rotX(180).rotZ(90)} pre-transform TRANSPOSES the
 *       square image; the final blit transposes it back with swapped tex
 *       coords ({@code texX = 0.5 + f1*ndcY_up}, {@code texY = 0.5 - f2*ndcX},
 *       {@code f = 120/max(W,H)}, {@code f1 = H*f/256}, {@code f2 = W*f/256})
 *       plus a centered crop. Two transpositions cancel end to end.</li>
 * </ol>
 *
 * <p>This renderer folds both stages into one fullscreen pass: per pixel,
 * {@code sqx = 2*f1*ndcY_up}, {@code sqy = -2*f2*ndcX}, the square eye ray
 * through the folded pre-transform is
 * {@code base = (-sqy*tan60, -sqx*tan60, 1)}, the cube motion is undone
 * with {@code rotY(-yaw).rotX(-pitch)}, and the ray is intersected with the
 * six face planes exactly like vanilla's quad loop (slot k = rotY
 * {0,+90,180,-90} / rotX {+90,-90} of the z=+1 quad; vanilla slot 4 = up,
 * slot 5 = down). Our EPK stores the up face as panorama_5 and the down
 * face as panorama_4, so slot 4 samples layer 5 and slot 5 samples layer 4;
 * the side slots map 1:1. The face images are natural camera renders
 * (row 0 = view top) and are uploaded unflipped, matching the quad uv
 * convention - verified seamless against the real 1.8 assets.</p>
 *
 * <h3>Unit discipline (the bug this rewrite fixes)</h3>
 *
 * <p>The previous shader consumed {@code u_yaw}/{@code u_pitch} as RADIANS
 * while the Java side fed DEGREES: pitch 10 was interpreted as 10 rad
 * (= 573 deg = 213 deg after wrapping), tumbling the camera upside down,
 * and the 2 deg/s spin became 2 rad/s = 114.6 deg/s - the "upside down,
 * spinning very fast" title screen. Every angle crossing the Java/GLSL
 * boundary is now converted with {@code Math.toRadians} at the single
 * call site, and the delta fed into the tick clock is clamped so a
 * background-tab rAF pause cannot whip the panorama.</p>
 */
public final class PanoramaRenderer {

        // ===== Vanilla 1.8 constants =====

        /** gluPerspective fov of the square panorama render (degrees). */
        private static final float SQUARE_FOV_DEG = 120.0F;

        /** Vanilla square render size (the crop constants divide by it). */
        private static final int SQUARE_SIZE = 256;

        /** tan(60 deg): half-fov tangent of the 120-degree square render. */
        private static final float TAN_HALF_SQUARE_FOV = 1.7320508F;

        /** Vanilla Panorama pitch: 20 + 25*sin(t/400) degrees. */
        private static final float PITCH_BASE_DEG = 20.0F;
        private static final float PITCH_BOB_DEG = 25.0F;
        private static final float PITCH_PERIOD_TICKS = 400.0F;

        /** Vanilla Panorama yaw: -0.1 degrees per tick (2 deg/s). */
        private static final float YAW_DEG_PER_TICK = -0.1F;

        /** Max ticks consumed per frame - absorbs background-tab pauses. */
        private static final float MAX_FRAME_DELTA_TICKS = 10.0F;

        /**
         * Face file suffix per texture-array layer: the generator's own
         * naming - layers 0..3 are the side faces (panorama_0..3, natural
         * camera renders at yaw {0,-90,180,90}), 4 = down, 5 = up.
         */
        private static final String[] FACE_SUFFIXES = { "0", "1", "2", "3", "4", "5" };

        /** Vanilla default-jar panorama stub color (1x1 RGB 98,111,113). */
        public static final float STUB_R = 98.0f / 255.0f;
        public static final float STUB_G = 111.0f / 255.0f;
        public static final float STUB_B = 113.0f / 255.0f;

        // ===== GL objects =====

        private static boolean initialized = false;
        private static JSObject panoramaProgram = null;
        private static JSObject uYaw = null;
        private static JSObject uPitch = null;
        private static JSObject uF1 = null;
        private static JSObject uF2 = null;
        private static JSObject triVAO = null;
        private static JSObject triVBO = null;
        private static JSObject faceTexture = null;
        private static boolean facesFailed = false;

        /** Continuous vanilla tick clock (float ticks, 20 per second). */
        private static float panoramaTicks = 0.0F;

        private PanoramaRenderer() {
        }

        // ===== Public API =====

        /**
         * Advances the vanilla tick clock. Call once per frame with the
         * real-time frame delta expressed in ticks (seconds * 20), like
         * vanilla's realtime delta; the delta is clamped so a paused tab
         * cannot whip the rotation on return.
         */
        public static void tick(float realtimeDeltaTicks) {
                float d = realtimeDeltaTicks;
                if (!(d >= 0.0F)) d = 0.0F;            // NaN / negative guard
                if (d > MAX_FRAME_DELTA_TICKS) d = MAX_FRAME_DELTA_TICKS;
                panoramaTicks += d;
                // Keep the clock bounded without disturbing either the yaw
                // (wraps at 3600 ticks = exactly one visual rotation) or the
                // pitch bob phase (400-tick sine): 3600 * 400 = 1_440_000 is
                // a common multiple of both periods.
                if (panoramaTicks >= 1440000.0F) panoramaTicks -= 1440000.0F;
        }

        /** Whether the face texture is built and drawable. */
        public static boolean isReady() {
                return faceTexture != null;
        }

        /** Current accumulated yaw in degrees (telemetry). */
        public static float spinDegrees() {
                return wrapDegrees(panoramaTicks * YAW_DEG_PER_TICK);
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

                // Vanilla 1.8 motion: pitch = 20 + 25*sin(t/400) deg,
                // yaw = -0.1*t deg. Angles cross to GLSL as RADIANS.
                float pitchDeg = PITCH_BASE_DEG
                                + PITCH_BOB_DEG * (float) Math.sin(panoramaTicks / PITCH_PERIOD_TICKS);
                float yawDeg = panoramaTicks * YAW_DEG_PER_TICK;

                // The transposing crop of vanilla's final blit.
                float f = SQUARE_FOV_DEG / (float) Math.max(fbWidth, fbHeight);
                float f1 = (float) fbHeight * f / (float) SQUARE_SIZE;
                float f2 = (float) fbWidth * f / (float) SQUARE_SIZE;

                PlatformOpenGL._wglUseProgram(panoramaProgram);
                PlatformOpenGL._wglUniform1f(uYaw, (float) Math.toRadians(yawDeg));
                PlatformOpenGL._wglUniform1f(uPitch, (float) Math.toRadians(pitchDeg));
                PlatformOpenGL._wglUniform1f(uF1, f1);
                PlatformOpenGL._wglUniform1f(uF2, f2);
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
         * Compiles the panorama program (a fullscreen-triangle replication
         * of vanilla's square render + transposing blit over the face
         * array) and builds the 3-vertex NDC triangle.
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

                // Literal port of scripts/pano_sim2.py (validated against the
                // real 1.8 jar assets): square-ray fold + cube-motion undo +
                // the six face-plane hits with vanilla's quad uv convention.
                // All angles arrive in RADIANS.
                String fsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec2 v_ndc;\n"
                                + "uniform highp sampler2DArray u_faces;\n"
                                + "uniform float u_yaw;\n"      // radians
                                + "uniform float u_pitch;\n"    // radians
                                + "uniform float u_f1;\n"
                                + "uniform float u_f2;\n"
                                + "out vec4 fragColor;\n"
                                + "void main() {\n"
                                // stage 2: transposing crop -> square NDC
                                + "  float sqx = 2.0 * u_f1 * v_ndc.y;\n"
                                + "  float sqy = -2.0 * u_f2 * v_ndc.x;\n"
                                // square eye ray through A^-1 = rotZ(-90).rotX(-180)
                                + "  vec3 base = vec3(-sqy * 1.7320508, -sqx * 1.7320508, 1.0);\n"
                                // undo the cube motion: rotY(-yaw).rotX(-pitch)
                                + "  float cp = cos(u_pitch), sp = sin(u_pitch);\n"
                                + "  vec3 d1 = vec3(base.x, base.y * cp + base.z * sp,\n"
                                + "                    base.z * cp - base.y * sp);\n"
                                + "  float cy = cos(u_yaw), sy = sin(u_yaw);\n"
                                + "  vec3 d2 = vec3(d1.x * cy - d1.z * sy, d1.y,\n"
                                + "                  d1.x * sy + d1.z * cy);\n"
                                // vanilla slot loop: R_k^-1 frame, hit z=+1 plane
                                // with |x|,|y| <= 1; uv = ((x+1)/2, (y+1)/2),
                                // v=0 = image top row (faces uploaded unflipped).
                                // Slot 4 = up (our layer 5), slot 5 = down (layer 4).
                                + "  vec2 uv = vec2(0.5);\n"
                                + "  float layer = 0.0;\n"
                                + "  bool found = false;\n"
                                + "  for (int k = 0; k < 6; k++) {\n"
                                + "    vec3 l;\n"
                                + "    float ly;\n"
                                + "    if (k == 0) { l = vec3(d2.x, d2.y, d2.z); ly = 0.0; }\n"
                                + "    else if (k == 1) { l = vec3(-d2.z, d2.y, d2.x); ly = 1.0; }\n"
                                + "    else if (k == 2) { l = vec3(-d2.x, d2.y, -d2.z); ly = 2.0; }\n"
                                + "    else if (k == 3) { l = vec3(d2.z, d2.y, -d2.x); ly = 3.0; }\n"
                                + "    else if (k == 4) { l = vec3(d2.x, d2.z, -d2.y); ly = 5.0; }\n"
                                + "    else { l = vec3(d2.x, -d2.z, d2.y); ly = 4.0; }\n"
                                + "    if (!found && l.z > 0.0 && abs(l.x) <= l.z && abs(l.y) <= l.z) {\n"
                                + "      uv = vec2((l.x / l.z + 1.0) * 0.5, (l.y / l.z + 1.0) * 0.5);\n"
                                + "      layer = ly;\n"
                                + "      found = true;\n"
                                + "    }\n"
                                + "  }\n"
                                // textureLod: no mip chain, and no implicit-derivative
                                // questions inside non-uniform control flow
                                + "  fragColor = textureLod(u_faces, vec3(uv, layer), 0.0);\n"
                                + "}\n";

                panoramaProgram = EaglerShaderImpl.createProgramFromSource(vsh, fsh, "panorama_faces");
                if (panoramaProgram == null) {
                        ClientMain.error("[PanoramaRenderer] failed to create the panorama program");
                        return;
                }
                uYaw = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_yaw");
                uPitch = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_pitch");
                uF1 = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_f1");
                uF2 = EaglerShaderImpl.getUniformLocation(panoramaProgram, "u_f2");

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
                        ClientMain.log("[PanoramaRenderer] vanilla 1.8 panorama pass ready");
                }
        }

        /**
         * Builds the face texture once all six PNGs have decoded through
         * {@link GuiSprites}: one TEXTURE_2D_ARRAY, six 768-square layers,
         * each uploaded exactly as decoded (row 0 = the rendered view's
         * top), which is the orientation vanilla's quad uv expects.
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

        /** Vanilla Mth.wrapDegrees: maps to [-180, 180). */
        private static float wrapDegrees(float v) {
                float d = v % 360.0F;
                if (d >= 180.0F) d -= 360.0F;
                if (d < -180.0F) d += 360.0F;
                return d;
        }
}
