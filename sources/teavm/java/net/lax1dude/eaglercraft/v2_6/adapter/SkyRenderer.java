package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;
import net.minecraft.client.renderer.SkyTimeline;

/**
 * Vanilla 26.1.2 overworld sky, ported from the decompiled
 * {@code net.minecraft.client.renderer.SkyRenderer} + the data-driven
 * day timeline (see {@link SkyTimeline} for provenance).
 *
 * <p>Rendered every frame before the world pass, in vanilla
 * {@code LevelRenderer.addSkyPass} order:</p>
 * <ol>
 *   <li>{@link #renderTopDisc sky disc} - the top fan (radius 512 at
 *       y=+16) filled with the timeline sky color, fogged to the fog
 *       color toward the rim (core/sky shader semantics: fog start 0,
 *       FogSkyEnd 512 = SKY_FOG_END_DISTANCE default, max of spherical
 *       linear and cylindrical step).</li>
 *   <li>{@link #renderSunriseSunset} - the 16-segment white-to-
 *       transparent fan tilted at the sun's horizon position, tinted by
 *       the gated sunrise_sunset_color track (z-scaled by its alpha,
 *       vanilla's fade trick), TRANSLUCENT blend.</li>
 *   <li>{@link #renderSunMoonAndStars} - sun quad (30x30 at distance
 *       100), moon quad (20x20, one of 8 phase textures), star quads
 *       (1500 iterations seeded with 10842L, java.util.Random sequence
 *       = vanilla LegacyRandom), all OVERLAY blend, rotated by the
 *       cubic-bezier-eased sun/moon/star angle tracks around the world
 *       X axis (rotY(-90) then rotX(angle), exactly vanilla).</li>
 *   <li>{@link #renderDarkDisc} - the bottom fan (y=-16 translated
 *       +12, vanilla's renderDarkDisc) filled black when the eye is
 *       below the horizon height; drawn last so it covers celestial
 *       bodies below the horizon.</li>
 * </ol>
 *
 * <p>All sky draws run with depth test off and depth writes off; the
 * world pass then draws over the sky with its own depth state. The
 * model-view matrix is the camera ROTATION only (no translation) - the
 * sky geometry is centered on the eye, like vanilla's rotation-only sky
 * model-view. Projection matches the world pass (fov option, near
 * 0.05, far 1000; the disc rim at ~512 stays inside the frustum).</p>
 *
 * <p>The 8 moon phases are the classic day-count formula
 * {@code floor(dayTime / 24000) % 8} over the vanilla MoonPhase enum
 * order - timeline/moon.json's visual/moon_phase float track (period
 * 192000) maps 1:1 onto that sequence.</p>
 *
 * <p>Celestial textures load asynchronously (same parked-decode pattern
 * as {@link GuiSprites}); while missing, the sun/moon are skipped and
 * the disc + stars render as usual.</p>
 */
public final class SkyRenderer {

        // ===== Vanilla constants =====

        /** SKY_FOG_END_DISTANCE attribute default = the disc radius. */
        private static final float SKY_FOG_END = 512.0F;
        /** Vanilla overworld horizon (getHorizonHeight). */
        private static final float HORIZON_HEIGHT = 63.0F;

        /** MoonPhase enum order (net.minecraft.world.level.MoonPhase). */
        private static final String[] MOON_PHASE_NAMES = {
                        "full_moon", "waning_gibbous", "third_quarter",
                        "waning_crescent", "new_moon", "waxing_crescent",
                        "first_quarter", "waxing_gibbous" };

        // ===== Programs =====

        private static boolean initialized = false;
        private static JSObject discProgram = null;
        private static JSObject discUMvp;
        private static JSObject discUColor = null;
        private static JSObject discUFogColor = null;
        private static JSObject tintProgram = null;
        private static JSObject tintUMvp;
        private static JSObject tintUColor = null;
        private static JSObject celestialProgram = null;
        private static JSObject celestialUMvp;
        private static JSObject celestialUColor = null;
        private static JSObject starsProgram = null;
        private static JSObject starsUMvp;
        private static JSObject starsUColor = null;

        // ===== Geometry =====

        private static JSObject topDiscVbo = null;
        private static JSObject bottomDiscVbo = null;
        private static JSObject sunriseVbo = null;
        private static JSObject sunVbo = null;
        private static JSObject moonVbo = null;
        private static JSObject starVbo = null;
        private static int starVertexCount = 0;

        // ===== Textures =====

        private static boolean texturesRequested = false;
        private static JSObject sunTexture = null;
        private static final JSObject[] moonTextures = new JSObject[8];
        private static boolean texturesFailed = false;

        // ===== Per-frame state (computed by updateState) =====

        private static float skyR, skyG, skyB;
        private static float fogR, fogG, fogB;
        private static float lightR, lightG, lightB;
        private static float sunAngleRad, starAngleRad;
        private static float starBrightness;
        private static float sunriseA, sunriseR, sunriseG, sunriseB;
        private static int moonPhase;
        private static boolean belowHorizon = true;

        // Scratch
        private static final float[] PROJ = new float[16];
        private static final float[] CAM = new float[16];
        private static final float[] MODEL = new float[16];
        private static final float[] MVP = new float[16];
        private static final float[] TMP_A = new float[16];
        private static final float[] TMP_B = new float[16];

        private SkyRenderer() {
        }

        // ===== Public API =====

        /**
         * Samples the day timeline for {@code dayTime + partialTick}.
         * Call before the framebuffer clear: the fog color result is the
         * vanilla clear color. Pure state computation - no GL.
         */
        public static void updateState(long dayTime, float partialTick, double eyeY) {
                float t = (float) (dayTime % SkyTimeline.PERIOD) + partialTick;
                if (t < 0.0F) {
                        t += SkyTimeline.PERIOD;
                }
                if (t >= SkyTimeline.PERIOD) {
                        t -= SkyTimeline.PERIOD;
                }

                int skyColor = multiplyColor(SkyTimeline.SKY_COLOR_BASE,
                                sampleWrappedArgb(SkyTimeline.SKY_COLOR_MULT, t));
                int fogColor = multiplyColor(SkyTimeline.FOG_COLOR_BASE,
                                sampleWrappedArgb(SkyTimeline.FOG_COLOR_MULT, t));
                skyR = ((skyColor >> 16) & 0xFF) / 255.0F;
                skyG = ((skyColor >> 8) & 0xFF) / 255.0F;
                skyB = (skyColor & 0xFF) / 255.0F;
                fogR = ((fogColor >> 16) & 0xFF) / 255.0F;
                fogG = ((fogColor >> 8) & 0xFF) / 255.0F;
                fogB = (fogColor & 0xFF) / 255.0F;

                // sun/moon/star angles: 360 x bezier(frac((t - 6000) / 24000));
                // the moon rides 180 deg ahead of the sun (timeline keyframes
                // 360->0 at t=6000 for the sun/stars, 540->180 for the moon)
                float phase = (t - SkyTimeline.ANGLE_ORIGIN) / SkyTimeline.PERIOD;
                phase -= (float) Math.floor(phase);
                float angleDeg = 360.0F * cubicBezier(phase);
                sunAngleRad = angleDeg * (float) (Math.PI / 180.0);
                starAngleRad = sunAngleRad;

                starBrightness = sampleGatedFloat(SkyTimeline.STAR_BRIGHTNESS, t);
                int sunrise = sampleGatedArgb(SkyTimeline.SUNRISE_SUNSET, t);
                sunriseA = ((sunrise >>> 24) & 0xFF) / 255.0F;
                sunriseR = ((sunrise >> 16) & 0xFF) / 255.0F;
                sunriseG = ((sunrise >> 8) & 0xFF) / 255.0F;
                sunriseB = (sunrise & 0xFF) / 255.0F;

                float[] light = sampleWrappedVec3(SkyTimeline.SKY_LIGHT, t);
                lightR = light[0];
                lightG = light[1];
                lightB = light[2];

                long dayCount = Math.floorDiv(dayTime, SkyTimeline.PERIOD);
                moonPhase = (int) Math.floorMod(dayCount, 8L);
                belowHorizon = eyeY < HORIZON_HEIGHT;
        }

        /** The vanilla clear color for the current frame (fog color). */
        public static float fogR() {
                return fogR;
        }

        public static float fogG() {
                return fogG;
        }

        public static float fogB() {
                return fogB;
        }

        /** Telemetry: the timeline sky color, 0..255 per channel. */
        public static int skyColorRgb() {
                return packRgb(skyR, skyG, skyB);
        }

        /** Telemetry: the timeline fog color, 0..255 per channel. */
        public static int fogColorRgb() {
                return packRgb(fogR, fogG, fogB);
        }

        /** Telemetry: the sun angle in degrees for the current frame. */
        public static float sunAngleDeg() {
                return sunAngleRad * (float) (180.0 / Math.PI);
        }

        /** Telemetry: the star brightness for the current frame. */
        public static float starBrightness() {
                return starBrightness;
        }

        /** Telemetry: the moon phase enum index (0 = full moon). */
        public static int moonPhase() {
                return moonPhase;
        }

        /** Telemetry: the world-dimming factor (sky light color x factor). */
        public static int skyLightRgb() {
                return packRgb(lightR, lightG, lightB);
        }

        /**
         * Draws the full sky pass. Called after the clear and before the
         * world pass; leaves depth writes re-enabled and blend disabled.
         */
        public static void renderFrame(WebGL2RenderingContext gl, int pxWidth, int pxHeight,
                        float fovDegrees, float aspect, float yawDeg, float pitchDeg) {
                if (gl == null || pxWidth <= 0 || pxHeight <= 0) {
                        return;
                }
                if (!initialized && !init(gl)) {
                        return;
                }
                pollTextures(gl);

                WorldGlRenderer.perspective(PROJ, fovDegrees, aspect, 0.05F, 1000.0F);
                camRotation(CAM, yawDeg, pitchDeg);

                // Detach any VAO the caller left bound (an empty
                // EaglerGuiImpl.flush leaves guiVAO bound): the draws below
                // issue vertexAttribPointer per draw, and with a VAO still
                // bound those writes would corrupt the GUI batch's captured
                // pointer state (the panorama avoids this by owning cubeVAO
                // and never re-issuing pointers at draw time).
                PlatformOpenGL._wglBindVertexArray(null);

                PlatformOpenGL._wglDisable(WebGL2RenderingContext.DEPTH_TEST);
                PlatformOpenGL._wglDepthMask(false);

                // 1. Top sky disc (opaque, fogged to the fog color at the rim)
                multiply(MVP, PROJ, CAM);
                drawDisc(gl, discProgram, topDiscVbo, 48, skyR, skyG, skyB,
                                fogR, fogG, fogB);

                // 2. Sunrise/sunset fan
                if (sunriseA > 0.001F) {
                        float flip = (float) Math.sin(sunAngleRad) < 0.0F ? 180.0F : 0.0F;
                        copy(TMP_A, CAM);
                        rotX(90.0F, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        rotZ(flip + 90.0F, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        scale(1.0F, 1.0F, sunriseA, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        multiply(MVP, PROJ, TMP_A);
                        drawTintFan(gl, sunriseVbo, 48, sunriseR, sunriseG, sunriseB, sunriseA);
                }

                // 3. Sun + moon + stars (rotY(-90) then the angle around X)
                boolean haveSun = sunTexture != null;
                boolean haveMoon = moonTextures[moonPhase] != null;
                if (haveSun || haveMoon) {
                        copy(TMP_A, CAM);
                        rotY(-90.0F, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        // sun branch
                        if (haveSun) {
                                copy(TMP_B, TMP_A);
                                rotX(sunAngleRad, MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                translate(0.0F, 100.0F, 0.0F, MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                scale(30.0F, 1.0F, 30.0F, MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                multiply(MVP, PROJ, TMP_B);
                                drawCelestial(gl, sunVbo, 6, sunTexture, 1.0F, 1.0F, 1.0F, 1.0F);
                        }
                        // moon branch
                        if (haveMoon) {
                                copy(TMP_B, TMP_A);
                                rotX(sunAngleRad + 180.0F * (float) (Math.PI / 180.0), MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                translate(0.0F, 100.0F, 0.0F, MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                scale(20.0F, 1.0F, 20.0F, MODEL);
                                multiply(TMP_B, TMP_B, MODEL);
                                multiply(MVP, PROJ, TMP_B);
                                drawCelestial(gl, moonVbo, 6, moonTextures[moonPhase],
                                                1.0F, 1.0F, 1.0F, 1.0F);
                        }
                }
                if (starBrightness > 0.0F && starVbo != null) {
                        copy(TMP_A, CAM);
                        rotY(-90.0F, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        rotX(starAngleRad, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        multiply(MVP, PROJ, TMP_A);
                        drawStars(gl, starVbo, starVertexCount, starBrightness);
                }

                // 4. Dark disc when the eye is below the horizon (covers the
                // celestial bodies that are below the horizon too)
                if (belowHorizon) {
                        copy(TMP_A, CAM);
                        translate(0.0F, 12.0F, 0.0F, TMP_B);
                        multiply(TMP_A, TMP_A, TMP_B);
                        multiply(MVP, PROJ, TMP_A);
                        drawDisc(gl, discProgram, bottomDiscVbo, 48, 0.0F, 0.0F, 0.0F,
                                        0.0F, 0.0F, 0.0F);
                }

                PlatformOpenGL._wglDepthMask(true);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
        }

        // ===== State sampling =====

        /** Multiplies an opaque base ARGB by an opaque multiplier ARGB. */
        private static int multiplyColor(int base, int mult) {
                int r = ((base >> 16) & 0xFF) * ((mult >> 16) & 0xFF) / 255;
                int g = ((base >> 8) & 0xFF) * ((mult >> 8) & 0xFF) / 255;
                int b = (base & 0xFF) * (mult & 0xFF) / 255;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        /**
         * WRAPPED {tick, argb} track: the generated key list spans one
         * extra period on each side, so every t interpolates. Colors lerp
         * per channel including alpha.
         */
        private static int sampleWrappedArgb(int[] keys, float t) {
                for (int i = 0; i + 3 < keys.length; i += 2) {
                        float t0 = keys[i];
                        float t1 = keys[i + 2];
                        if (t >= t0 && t <= t1) {
                                return lerpArgb(keys[i + 1], keys[i + 3],
                                                (t - t0) / (t1 - t0));
                        }
                    }
                // Outside the generated span: clamp to the nearest end
                return t < keys[0] ? keys[1] : keys[keys.length - 1];
        }

        /** GATED {tick, argb} track: 0 (fully transparent) outside coverage. */
        private static int sampleGatedArgb(int[] keys, float t) {
                if (t < keys[0] || t > keys[keys.length - 2]) {
                        return 0;
                }
                for (int i = 0; i + 3 < keys.length; i += 2) {
                        float t0 = keys[i];
                        float t1 = keys[i + 2];
                        if (t >= t0 && t <= t1) {
                                return lerpArgb(keys[i + 1], keys[i + 3],
                                                (t - t0) / (t1 - t0));
                        }
                }
                return keys[keys.length - 1];
        }

        private static int lerpArgb(int a, int b, float u) {
                int alpha = lerpChannel((a >>> 24) & 0xFF, (b >>> 24) & 0xFF, u);
                int r = lerpChannel((a >> 16) & 0xFF, (b >> 16) & 0xFF, u);
                int g = lerpChannel((a >> 8) & 0xFF, (b >> 8) & 0xFF, u);
                int bl = lerpChannel(a & 0xFF, b & 0xFF, u);
                return (alpha << 24) | (r << 16) | (g << 8) | bl;
        }

        private static int lerpChannel(int a, int b, float u) {
                return Math.round(a + (b - a) * u);
        }

        /** GATED {tick, value} track: 0 outside coverage. */
        private static float sampleGatedFloat(float[] keys, float t) {
                if (t < keys[0] || t > keys[keys.length - 2]) {
                        return 0.0F;
                }
                for (int i = 0; i + 3 < keys.length; i += 2) {
                        float t0 = keys[i];
                        float t1 = keys[i + 2];
                        if (t >= t0 && t <= t1) {
                                float u = (t - t0) / (t1 - t0);
                                return keys[i + 1] + (keys[i + 3] - keys[i + 1]) * u;
                        }
                }
                return keys[keys.length - 1];
        }

        /** WRAPPED {tick, r, g, b} track sampled into a 3-float out. */
        private static float[] sampleWrappedVec3(float[] keys, float t) {
                float[] out = new float[3];
                for (int i = 0; i + 6 < keys.length; i += 4) {
                        float t0 = keys[i];
                        float t1 = keys[i + 4];
                        if (t >= t0 && t <= t1) {
                                float u = (t - t0) / (t1 - t0);
                                out[0] = keys[i + 1] + (keys[i + 5] - keys[i + 1]) * u;
                                out[1] = keys[i + 2] + (keys[i + 6] - keys[i + 2]) * u;
                                out[2] = keys[i + 3] + (keys[i + 7] - keys[i + 3]) * u;
                                return out;
                        }
                }
                int last = keys.length - 4;
                out[0] = keys[last + 1];
                out[1] = keys[last + 2];
                out[2] = keys[last + 3];
                return out;
        }

        /**
         * Evaluates the timeline angle ease cubic_bezier(x0,y0,x1,y1) at
         * parameter u: solves the x(s) cubic for s (Newton, bisection
         * fallback), returns y(s). Endpoints are (0,0) and (1,1).
         */
        private static float cubicBezier(float u) {
                float x0 = SkyTimeline.ANGLE_EASE[0];
                float y0 = SkyTimeline.ANGLE_EASE[1];
                float x1 = SkyTimeline.ANGLE_EASE[2];
                float y1 = SkyTimeline.ANGLE_EASE[3];
                if (u <= 0.0F) return 0.0F;
                if (u >= 1.0F) return 1.0F;
                float s = u;
                for (int i = 0; i < 8; i++) {
                        float x = bez(s, x0, x1) - u;
                        if (Math.abs(x) < 1.0e-5F) {
                                break;
                        }
                        float d = bezDerivative(s, x0, x1);
                        if (Math.abs(d) < 1.0e-6F) {
                                break;
                        }
                        s -= x / d;
                        if (s < 0.0F) s = 0.0F;
                        if (s > 1.0F) s = 1.0F;
                }
                if (s < 0.0F) s = 0.0F;
                if (s > 1.0F) s = 1.0F;
                return bez(s, y0, y1);
        }

        /** B(s) = 3(1-s)^2 s c0 + 3(1-s) s^2 c1 + s^3. */
        private static float bez(float s, float c0, float c1) {
                float inv = 1.0F - s;
                return 3.0F * inv * inv * s * c0 + 3.0F * inv * s * s * c1 + s * s * s;
        }

        private static float bezDerivative(float s, float c0, float c1) {
                float inv = 1.0F - s;
                return 3.0F * inv * inv * c0 + 6.0F * inv * s * (c1 - c0) + 3.0F * s * s * (1.0F - c1);
        }

        private static int packRgb(float r, float g, float b) {
                int ri = Math.round(clamp01(r) * 255.0F);
                int gi = Math.round(clamp01(g) * 255.0F);
                int bi = Math.round(clamp01(b) * 255.0F);
                return (ri << 16) | (gi << 8) | bi;
        }

        private static float clamp01(float v) {
                return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
        }

        // ===== GL setup =====

        private static boolean init(WebGL2RenderingContext gl) {
                discProgram = buildProgram(gl, DISC_VSH, DISC_FSH, "sky_disc");
                tintProgram = buildProgram(gl, TINT_VSH, TINT_FSH, "sky_tint");
                celestialProgram = buildProgram(gl, CELESTIAL_VSH, CELESTIAL_FSH, "sky_celestial");
                starsProgram = buildProgram(gl, STARS_VSH, STARS_FSH, "sky_stars");
                if (discProgram == null || tintProgram == null
                                || celestialProgram == null || starsProgram == null) {
                        return false;
                }
                discUMvp = PlatformOpenGL._wglGetUniformLocation(discProgram, "u_mvp");
                discUColor = PlatformOpenGL._wglGetUniformLocation(discProgram, "u_color");
                discUFogColor = PlatformOpenGL._wglGetUniformLocation(discProgram, "u_fogColor");
                tintUMvp = PlatformOpenGL._wglGetUniformLocation(tintProgram, "u_mvp");
                tintUColor = PlatformOpenGL._wglGetUniformLocation(tintProgram, "u_color");
                celestialUMvp = PlatformOpenGL._wglGetUniformLocation(celestialProgram, "u_mvp");
                celestialUColor = PlatformOpenGL._wglGetUniformLocation(celestialProgram, "u_color");
                starsUMvp = PlatformOpenGL._wglGetUniformLocation(starsProgram, "u_mvp");
                starsUColor = PlatformOpenGL._wglGetUniformLocation(starsProgram, "u_color");

                topDiscVbo = buildDisc(gl, 16.0F, false);
                bottomDiscVbo = buildDisc(gl, -16.0F, true);
                sunriseVbo = buildSunrise(gl);
                sunVbo = buildCelestialQuad(gl, false);
                moonVbo = buildCelestialQuad(gl, true);
                starVbo = buildStars(gl);
                if (topDiscVbo == null || bottomDiscVbo == null || sunriseVbo == null
                                || sunVbo == null || moonVbo == null || starVbo == null) {
                        return false;
                }
                initialized = true;
                ClientMain.log("[SkyRenderer] programs + geometry ready");
                return true;
        }

        private static final String DISC_VSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "layout(location=0) in vec3 a_pos;"
                + "uniform mat4 u_mvp;"
                + "out float v_sph;"
                + "out float v_cyl;"
                + "void main() {"
                + "  gl_Position = u_mvp * vec4(a_pos, 1.0);"
                + "  v_sph = length(a_pos);"
                + "  v_cyl = max(length(a_pos.xz), abs(a_pos.y));"
                + "}";

        /** core/sky.fsh: max(spherical linear fog, cylindrical step) at FogSkyEnd. */
        private static final String DISC_FSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "in float v_sph;"
                + "in float v_cyl;"
                + "uniform vec3 u_color;"
                + "uniform vec3 u_fogColor;"
                + "out vec4 fragColor;"
                + "float lin(float d, float s, float e) {"
                + "  return d <= s ? 0.0 : (d >= e ? 1.0 : (d - s) / (e - s));"
                + "}"
                + "void main() {"
                + "  float fog = max(lin(v_sph, 0.0, 512.0), v_cyl >= 512.0 ? 1.0 : 0.0);"
                + "  fragColor = vec4(mix(u_color, u_fogColor, fog), 1.0);"
                + "}";

        private static final String TINT_VSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "layout(location=0) in vec3 a_pos;"
                + "layout(location=1) in vec4 a_color;"
                + "uniform mat4 u_mvp;"
                + "out vec4 v_color;"
                + "void main() {"
                + "  gl_Position = u_mvp * vec4(a_pos, 1.0);"
                + "  v_color = a_color;"
                + "}";

        /** core/position_color.fsh: vertexColor * ColorModulator. */
        private static final String TINT_FSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "in vec4 v_color;"
                + "uniform vec4 u_color;"
                + "out vec4 fragColor;"
                + "void main() {"
                + "  fragColor = v_color * u_color;"
                + "}";

        private static final String CELESTIAL_VSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "layout(location=0) in vec3 a_pos;"
                + "layout(location=1) in vec2 a_uv;"
                + "uniform mat4 u_mvp;"
                + "out vec2 v_uv;"
                + "void main() {"
                + "  gl_Position = u_mvp * vec4(a_pos, 1.0);"
                + "  v_uv = a_uv;"
                + "}";

        /** core/position_tex.fsh: texture * ColorModulator. */
        private static final String CELESTIAL_FSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "in vec2 v_uv;"
                + "uniform sampler2D u_tex;"
                + "uniform vec4 u_color;"
                + "out vec4 fragColor;"
                + "void main() {"
                + "  fragColor = texture(u_tex, v_uv) * u_color;"
                + "}";

        private static final String STARS_VSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "layout(location=0) in vec3 a_pos;"
                + "uniform mat4 u_mvp;"
                + "void main() {"
                + "  gl_Position = u_mvp * vec4(a_pos, 1.0);"
                + "}";

        /** core/stars.fsh: ColorModulator only. */
        private static final String STARS_FSH =
                "#version 300 es\n"
                + "precision highp float;"
                + "uniform vec4 u_color;"
                + "out vec4 fragColor;"
                + "void main() {"
                + "  fragColor = u_color;"
                + "}";

        private static JSObject buildProgram(WebGL2RenderingContext gl, String vshSrc,
                        String fshSrc, String name) {
                try {
                        JSObject vs = PlatformOpenGL._wglCreateShader(WebGL2RenderingContext.VERTEX_SHADER);
                        PlatformOpenGL._wglShaderSource(vs, vshSrc);
                        PlatformOpenGL._wglCompileShader(vs);
                        if (!PlatformOpenGL._wglGetShaderCompiled(vs)) {
                                ClientMain.warn("[SkyRenderer] " + name + " vsh: "
                                                + PlatformOpenGL._wglGetShaderInfoLog(vs));
                                return null;
                        }
                        JSObject fs = PlatformOpenGL._wglCreateShader(WebGL2RenderingContext.FRAGMENT_SHADER);
                        PlatformOpenGL._wglShaderSource(fs, fshSrc);
                        PlatformOpenGL._wglCompileShader(fs);
                        if (!PlatformOpenGL._wglGetShaderCompiled(fs)) {
                                ClientMain.warn("[SkyRenderer] " + name + " fsh: "
                                                + PlatformOpenGL._wglGetShaderInfoLog(fs));
                                return null;
                        }
                        JSObject program = PlatformOpenGL._wglCreateProgram();
                        PlatformOpenGL._wglAttachShader(program, vs);
                        PlatformOpenGL._wglAttachShader(program, fs);
                        PlatformOpenGL._wglLinkProgram(program);
                        if (!PlatformOpenGL._wglGetProgramLinked(program)) {
                                ClientMain.warn("[SkyRenderer] " + name + " link: "
                                                + PlatformOpenGL._wglGetProgramInfoLog(program));
                                return null;
                        }
                        return program;
                } catch (Throwable t) {
                        ClientMain.warn("[SkyRenderer] " + name + " init failed: " + t.getMessage());
                        return null;
                }
        }

        /**
         * Vanilla buildSkyDisc(yy) expanded from TRIANGLE_FAN to
         * triangles: center (0, yy, 0), rim i=-180..180 step 45 at
         * (signum(yy) * 512 * cos, yy, 512 * sin).
         */
        private static JSObject buildDisc(WebGL2RenderingContext gl, float yy, boolean bottom) {
                float rimX = bottom ? -512.0F : 512.0F;
                float[] rim = new float[17 * 3];
                for (int i = 0; i <= 16; i++) {
                        float a = (-180 + i * 45) * (float) (Math.PI / 180.0);
                        rim[i * 3] = rimX * (float) Math.cos(a);
                        rim[i * 3 + 1] = yy;
                        rim[i * 3 + 2] = 512.0F * (float) Math.sin(a);
                }
                float[] tris = new float[16 * 3 * 3];
                int o = 0;
                for (int i = 0; i < 16; i++) {
                        tris[o++] = 0.0F; tris[o++] = yy;  tris[o++] = 0.0F;
                        tris[o++] = rim[i * 3];     tris[o++] = rim[i * 3 + 1];     tris[o++] = rim[i * 3 + 2];
                        tris[o++] = rim[i * 3 + 3]; tris[o++] = rim[i * 3 + 4];     tris[o++] = rim[i * 3 + 5];
                }
                return upload(gl, tris);
        }

        /**
         * Vanilla buildSunriseFan expanded: center (0,100,0) white a=1,
         * ring i=0..16 at (120 sin, 120 cos, -40 cos) white a=0.
         */
        private static JSObject buildSunrise(WebGL2RenderingContext gl) {
                float[] ring = new float[17 * 3];
                for (int i = 0; i <= 16; i++) {
                        float a = i * (float) (Math.PI * 2) / 16.0F;
                        ring[i * 3] = (float) Math.sin(a) * 120.0F;
                        ring[i * 3 + 1] = (float) Math.cos(a) * 120.0F;
                        ring[i * 3 + 2] = -(float) Math.cos(a) * 40.0F;
                }
                // pos(3) + color(4) interleaved
                float[] tris = new float[16 * 3 * 7];
                int o = 0;
                for (int i = 0; i < 16; i++) {
                        o = writeFanVertex(tris, o, 0.0F, 100.0F, 0.0F, 1.0F);
                        o = writeFanVertex(tris, o, ring[i * 3], ring[i * 3 + 1], ring[i * 3 + 2], 0.0F);
                        o = writeFanVertex(tris, o, ring[i * 3 + 3], ring[i * 3 + 4], ring[i * 3 + 5], 0.0F);
                }
                return upload(gl, tris);
        }

        private static int writeFanVertex(float[] out, int o, float x, float y, float z, float a) {
                out[o++] = x;  out[o++] = y;  out[o++] = z;
                out[o++] = 1.0F; out[o++] = 1.0F; out[o++] = 1.0F; out[o++] = a;
                return o;
        }

        /**
         * Vanilla buildCelestialQuad expanded to two triangles. The moon
         * quad carries the phase-atlas horizontal flip in its UVs
         * (vanilla: (U1,V1),(U0,V1),(U0,V0),(U1,V0)).
         */
        private static JSObject buildCelestialQuad(WebGL2RenderingContext gl, boolean flipped) {
                float u0 = flipped ? 1.0F : 0.0F;
                float u1 = flipped ? 0.0F : 1.0F;
                float[] quad = {
                                -1.0F, 0.0F, -1.0F, u0, 0.0F,
                                 1.0F, 0.0F, -1.0F, u1, 0.0F,
                                 1.0F, 0.0F,  1.0F, u1, 1.0F,
                                 1.0F, 0.0F,  1.0F, u1, 1.0F,
                                -1.0F, 0.0F,  1.0F, u0, 1.0F,
                                -1.0F, 0.0F, -1.0F, u0, 0.0F };
                return upload(gl, quad);
        }

        /**
         * Vanilla buildStars: 1500 iterations of RandomSource(10842L),
         * java.util.Random sequence (vanilla LegacyRandom delegates to
         * it). Each accepted iteration: unit-shell point normalized to
         * distance 100, quad of size 0.15..0.25 billboarded via
         * rotateTowards(-center, +Y) then rotZ(-zRot), JOML semantics
         * replicated by hand.
         */
        private static JSObject buildStars(WebGL2RenderingContext gl) {
                java.util.Random random = new java.util.Random(10842L);
                Float32Array acc = Float32Array.create(1500 * 6 * 3);
                int count = 0;
                for (int i = 0; i < 1500; i++) {
                        float x = random.nextFloat() * 2.0F - 1.0F;
                        float y = random.nextFloat() * 2.0F - 1.0F;
                        float z = random.nextFloat() * 2.0F - 1.0F;
                        float starSize = 0.15F + random.nextFloat() * 0.1F;
                        float lengthSq = x * x + y * y + z * z;
                        if (lengthSq <= 0.010000001F || lengthSq >= 1.0F) {
                                continue;
                        }
                        float zRot = (float) (random.nextDouble() * Math.PI * 2.0);

                        float len = (float) Math.sqrt(lengthSq);
                        float cx = x / len * 100.0F;
                        float cy = y / len * 100.0F;
                        float cz = z / len * 100.0F;

                        // R = rotateTowards(-center, +Y) * rotZ(-zRot)
                        float dx = -cx, dy = -cy, dz = -cz;
                        float inv = 1.0F / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        dx *= inv; dy *= inv; dz *= inv;
                        // left = up x dir (up = (0,1,0))
                        float lx = dz, ly = 0.0F, lz = -dx;
                        float invL = 1.0F / (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
                        lx *= invL; ly *= invL; lz *= invL;
                        // up = dir x left
                        float ux = dy * lz - dz * ly;
                        float uy = dz * lx - dx * lz;
                        float uz = dx * ly - dy * lx;
                        float invU = 1.0F / (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                        ux *= invU; uy *= invU; uz *= invU;
                        // rotateZ(-zRot) post-multiplied
                        float c = (float) Math.cos(-zRot);
                        float s = (float) Math.sin(-zRot);
                        // R columns: [left, up, dir] each rotated by Rz
                        float r00 = lx * c - ux * s;
                        float r01 = ux * c + lx * s;
                        float r02 = dx;
                        float r10 = ly * c - uy * s;
                        float r11 = uy * c + ly * s;
                        float r12 = dy;
                        float r20 = lz * c - uz * s;
                        float r21 = uz * c + lz * s;
                        float r22 = dz;

                        // 4 corners, quad order like vanilla
                        float[][] corners = {
                                        { starSize, -starSize, 0.0F },
                                        { starSize, starSize, 0.0F },
                                        { -starSize, starSize, 0.0F },
                                        { -starSize, -starSize, 0.0F } };
                        for (int q = 0; q < 6; q++) {
                                int ci = QUAD_TRIS[q];
                                float vx = corners[ci][0];
                                float vy = corners[ci][1];
                                float vz = corners[ci][2];
                                acc.set(count++, r00 * vx + r01 * vy + r02 * vz + cx);
                                acc.set(count++, r10 * vx + r11 * vy + r12 * vz + cy);
                                acc.set(count++, r20 * vx + r21 * vy + r22 * vz + cz);
                        }
                }
                starVertexCount = count;
                // Trim to the accepted count
                float[] tris = new float[count];
                for (int i = 0; i < count; i++) {
                        tris[i] = acc.get(i);
                }
                ClientMain.log("[SkyRenderer] " + (count / 3) + " star vertices from 1500 seeds");
                return upload(gl, tris);
        }

        private static final int[] QUAD_TRIS = { 0, 1, 2, 2, 3, 0 };

        private static JSObject upload(WebGL2RenderingContext gl, float[] data) {
                Float32Array buf = Float32Array.create(data.length);
                for (int i = 0; i < data.length; i++) {
                        buf.set(i, data[i]);
                }
                JSObject vbo = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, buf,
                                WebGL2RenderingContext.STATIC_DRAW);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                return vbo;
        }

        // ===== Draws =====

        private static void drawDisc(WebGL2RenderingContext gl, JSObject program, JSObject vbo,
                        int vertexCount, float r, float g, float b,
                        float fogRf, float fogGf, float fogBf) {
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglUseProgram(program);
                Float32Array mvp = Float32Array.create(16);
                for (int i = 0; i < 16; i++) {
                        mvp.set(i, MVP[i]);
                }
                PlatformOpenGL._wglUniformMatrix4fv(discUMvp, false, mvp);
                PlatformOpenGL._wglUniform3f(discUColor, r, g, b);
                PlatformOpenGL._wglUniform3f(discUFogColor, fogRf, fogGf, fogBf);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglEnableVertexAttribArray(0);
                PlatformOpenGL._wglVertexAttribPointer(0, 3, WebGL2RenderingContext.FLOAT,
                                false, 12, 0);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglUseProgram(null);
        }

        private static void drawTintFan(WebGL2RenderingContext gl, JSObject vbo,
                        int vertexCount, float r, float g, float b, float a) {
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                // BlendFunction.TRANSLUCENT: (SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
                // for color, (ONE, ONE_MINUS_SRC_ALPHA) for alpha
                PlatformOpenGL._wglBlendFuncSeparate(WebGL2RenderingContext.SRC_ALPHA,
                                WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA,
                                WebGL2RenderingContext.ONE,
                                WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA);
                PlatformOpenGL._wglUseProgram(tintProgram);
                Float32Array mvp = Float32Array.create(16);
                for (int i = 0; i < 16; i++) {
                        mvp.set(i, MVP[i]);
                }
                PlatformOpenGL._wglUniformMatrix4fv(tintUMvp, false, mvp);
                PlatformOpenGL._wglUniform4f(tintUColor, r, g, b, a);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglEnableVertexAttribArray(0);
                PlatformOpenGL._wglEnableVertexAttribArray(1);
                PlatformOpenGL._wglVertexAttribPointer(0, 3, WebGL2RenderingContext.FLOAT,
                                false, 28, 0);
                PlatformOpenGL._wglVertexAttribPointer(1, 4, WebGL2RenderingContext.FLOAT,
                                false, 28, 12);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglUseProgram(null);
        }

        private static void drawCelestial(WebGL2RenderingContext gl, JSObject vbo,
                        int vertexCount, JSObject texture,
                        float r, float g, float b, float a) {
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                // BlendFunction.OVERLAY: (SRC_ALPHA, ONE) color, (ONE, ZERO) alpha
                PlatformOpenGL._wglBlendFuncSeparate(WebGL2RenderingContext.SRC_ALPHA,
                                WebGL2RenderingContext.ONE,
                                WebGL2RenderingContext.ONE,
                                WebGL2RenderingContext.ZERO);
                PlatformOpenGL._wglUseProgram(celestialProgram);
                Float32Array mvp = Float32Array.create(16);
                for (int i = 0; i < 16; i++) {
                        mvp.set(i, MVP[i]);
                }
                PlatformOpenGL._wglUniformMatrix4fv(celestialUMvp, false, mvp);
                PlatformOpenGL._wglUniform4f(celestialUColor, r, g, b, a);
                PlatformOpenGL._wglActiveTexture(WebGL2RenderingContext.TEXTURE0);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, texture);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglEnableVertexAttribArray(0);
                PlatformOpenGL._wglEnableVertexAttribArray(1);
                PlatformOpenGL._wglVertexAttribPointer(0, 3, WebGL2RenderingContext.FLOAT,
                                false, 20, 0);
                PlatformOpenGL._wglVertexAttribPointer(1, 2, WebGL2RenderingContext.FLOAT,
                                false, 20, 12);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, null);
                PlatformOpenGL._wglUseProgram(null);
        }

        private static void drawStars(WebGL2RenderingContext gl, JSObject vbo,
                        int vertexCount, float brightness) {
                PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                PlatformOpenGL._wglBlendFuncSeparate(WebGL2RenderingContext.SRC_ALPHA,
                                WebGL2RenderingContext.ONE,
                                WebGL2RenderingContext.ONE,
                                WebGL2RenderingContext.ZERO);
                PlatformOpenGL._wglUseProgram(starsProgram);
                Float32Array mvp = Float32Array.create(16);
                for (int i = 0; i < 16; i++) {
                        mvp.set(i, MVP[i]);
                }
                PlatformOpenGL._wglUniformMatrix4fv(starsUMvp, false, mvp);
                PlatformOpenGL._wglUniform4f(starsUColor, brightness, brightness,
                                brightness, brightness);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, vbo);
                PlatformOpenGL._wglEnableVertexAttribArray(0);
                PlatformOpenGL._wglVertexAttribPointer(0, 3, WebGL2RenderingContext.FLOAT,
                                false, 12, 0);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, vertexCount);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                PlatformOpenGL._wglUseProgram(null);
        }

        // ===== Textures =====

        private static void pollTextures(WebGL2RenderingContext gl) {
                if (texturesFailed) {
                        return;
                }
                if (!texturesRequested) {
                        texturesRequested = true;
                        beginLoad("sun", "assets/world/environment/sun.png");
                        for (int i = 0; i < MOON_PHASE_NAMES.length; i++) {
                                beginLoad("moon_" + i,
                                                "assets/world/environment/moon/"
                                                                + MOON_PHASE_NAMES[i] + ".png");
                        }
                        ClientMain.log("[SkyRenderer] requested sun + 8 moon phase textures");
                }
                if (sunTexture == null) {
                        SkyTextureData data = poll("sun");
                        if (data != null) {
                                if (data.getError()) {
                                        texturesFailed = true;
                                        ClientMain.warn("[SkyRenderer] sun texture failed");
                                } else {
                                        sunTexture = uploadTexture(gl, data);
                                }
                        }
                }
                for (int i = 0; i < 8; i++) {
                        if (moonTextures[i] != null) {
                                continue;
                        }
                        SkyTextureData data = poll("moon_" + i);
                        if (data == null) {
                                continue;
                        }
                        if (data.getError()) {
                                texturesFailed = true;
                                ClientMain.warn("[SkyRenderer] moon texture '" + i + "' failed");
                                return;
                        }
                        moonTextures[i] = uploadTexture(gl, data);
                }
        }

        /** Celestial textures are soft-edged: LINEAR, CLAMP_TO_EDGE. */
        private static JSObject uploadTexture(WebGL2RenderingContext gl, SkyTextureData data) {
                JSObject tex = PlatformOpenGL._wglCreateTexture();
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, tex);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_FLIP_Y_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0);
                PlatformOpenGL._wglPixelStorei(WebGL2RenderingContext.UNPACK_ALIGNMENT, 1);
                PlatformOpenGL._wglTexImage2D(WebGL2RenderingContext.TEXTURE_2D, 0,
                                WebGL2RenderingContext.RGBA, data.getW(), data.getH(), 0,
                                WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE,
                                data.getData());
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                                WebGL2RenderingContext.TEXTURE_MIN_FILTER, WebGL2RenderingContext.LINEAR);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                                WebGL2RenderingContext.TEXTURE_MAG_FILTER, WebGL2RenderingContext.LINEAR);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                                WebGL2RenderingContext.TEXTURE_WRAP_S, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglTexParameteri(WebGL2RenderingContext.TEXTURE_2D,
                                WebGL2RenderingContext.TEXTURE_WRAP_T, WebGL2RenderingContext.CLAMP_TO_EDGE);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, null);
                return tex;
        }

        @JSBody(params = { "name", "path" }, script =
                "var store = window.__eaglerSkyTextures || (window.__eaglerSkyTextures = {});"
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

        @JSBody(params = { "name" }, script =
                "return (window.__eaglerSkyTextures && window.__eaglerSkyTextures[name]) || null;")
        private static native SkyTextureData poll(String name);

        /** TeaVM view of a parked celestial texture download. */
        public abstract static class SkyTextureData implements JSObject {
                @JSProperty
                public abstract int getW();

                @JSProperty
                public abstract int getH();

                @JSProperty
                public abstract Uint8Array getData();

                @JSProperty
                public abstract boolean getError();
        }

        // ===== Matrix helpers (column-major, GL layout) =====

        /** Camera rotation-only view: Rx(pitch) * Ry(yaw + 180), no
         * translation - the same +180 convention as WorldGlRenderer.makeView
         * (vanilla GameRenderer.renderLevel pose order), so the sun rises
         * where the physics yaw says it must. */
        private static void camRotation(float[] m, float yawDeg, float pitchDeg) {
                float yaw = yawDeg * (float) (Math.PI / 180.0F);
                float pitch = pitchDeg * (float) (Math.PI / 180.0F);
                float cy = (float) Math.cos(yaw);
                float sy = (float) Math.sin(yaw);
                float cp = (float) Math.cos(pitch);
                float sp = (float) Math.sin(pitch);
                identity(m);
                m[0] = -cy;
                m[1] = -sp * sy;
                m[2] = cp * sy;
                m[4] = 0.0F;
                m[5] = cp;
                m[6] = sp;
                m[8] = -sy;
                m[9] = sp * cy;
                m[10] = -cp * cy;
        }

        private static void identity(float[] m) {
                for (int i = 0; i < 16; i++) {
                        m[i] = 0.0F;
                }
                m[0] = m[5] = m[10] = m[15] = 1.0F;
        }

        private static void copy(float[] dst, float[] src) {
                System.arraycopy(src, 0, dst, 0, 16);
        }

        /** out = a * b (column-major). */
        private static void multiply(float[] out, float[] a, float[] b) {
                float[] tmp = new float[16];
                for (int col = 0; col < 4; col++) {
                        for (int row = 0; row < 4; row++) {
                                float sum = 0.0F;
                                for (int k = 0; k < 4; k++) {
                                        sum += a[k * 4 + row] * b[col * 4 + k];
                                }
                                tmp[col * 4 + row] = sum;
                        }
                }
                System.arraycopy(tmp, 0, out, 0, 16);
        }

        private static void rotX(float deg, float[] out) {
                identity(out);
                float a = deg * (float) (Math.PI / 180.0F);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                out[5] = c;  out[6] = s;
                out[9] = -s; out[10] = c;
        }

        private static void rotY(float deg, float[] out) {
                identity(out);
                float a = deg * (float) (Math.PI / 180.0F);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                out[0] = c;  out[2] = -s;
                out[8] = s;  out[10] = c;
        }

        private static void rotZ(float deg, float[] out) {
                identity(out);
                float a = deg * (float) (Math.PI / 180.0F);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                out[0] = c;  out[1] = s;
                out[4] = -s; out[5] = c;
        }

        private static void translate(float x, float y, float z, float[] out) {
                identity(out);
                out[12] = x;
                out[13] = y;
                out[14] = z;
        }

        private static void scale(float x, float y, float z, float[] out) {
                identity(out);
                out[0] = x;
                out[5] = y;
                out[10] = z;
        }
}
