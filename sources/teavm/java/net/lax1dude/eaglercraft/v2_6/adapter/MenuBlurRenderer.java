package net.lax1dude.eaglercraft.v2_6.adapter;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;

/**
 * Vanilla 26.1.2 menu-background blur: the {@code post_effect/blur.json}
 * pipeline (loaded by {@code GameRenderer.processBlurEffect}) applied to
 * whatever the menu drew behind the GUI - out of a world that is the
 * rotating panorama cubemap.
 *
 * <p>The vanilla definition is six passes of {@code post/box_blur}
 * (horizontal, vertical, repeated 3x) over the {@code minecraft:main}
 * target, each rendered with {@code core/screenquad} and a bilinear
 * input sampler. The shader math (vanilla {@code box_blur.fsh}, kept
 * verbatim) exploits the bilinear filter to halve the sample count:</p>
 *
 * <pre>{@code
 * float actualRadius = Radius >= 0.5 ? round(Radius) : float(MenuBlurRadius);
 * for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0)
 *     blurred += texture(InSampler, texCoord + sampleStep * a);
 * blurred += texture(InSampler, texCoord + sampleStep * actualRadius) / 2.0;
 * fragColor = blurred / (actualRadius + 0.5);
 * }</pre>
 *
 * <p>{@code Radius} is the accessibility option
 * {@code menuBackgroundBlurriness} (0..10, default 5); vanilla only runs
 * the effect when it is {@code >= 1} ({@code Screen.extractBlurredBackground}).
 * This renderer mirrors the pipeline 1:1 with two RGBA8 FBOs of the
 * framebuffer size: the panorama cube draws into target A, the six blur
 * passes ping-pong A→B→A→B→A→B (final content back in A), and a plain
 * blit copies the result to the default framebuffer before the GUI draws
 * its menu tiles on top.</p>
 */
public final class MenuBlurRenderer {

        /** Vanilla blur.json: six box_blur passes (3x horizontal + vertical). */
        private static final int PASSES = 6;

        private static boolean initialized = false;
        private static boolean targetsOk = false;
        private static int targetWidth = 0;
        private static int targetHeight = 0;

        private static JSObject blurProgram = null;
        private static JSObject uInSampler = null;
        private static JSObject uBlurDir = null;
        private static JSObject uRadius = null;
        private static JSObject uInSize = null;

        private static JSObject blitProgram = null;
        private static JSObject blitUInSampler = null;

        /** Fullscreen quad: 6 vertices (two triangles), uv 0..1. */
        private static JSObject quadVAO = null;
        private static JSObject quadVBO = null;

        private static JSObject textureA = null;
        private static JSObject textureB = null;
        private static JSObject framebufferA = null;
        private static JSObject framebufferB = null;

        private MenuBlurRenderer() {
        }

        // ===== Public API =====

        /**
         * Binds the offscreen target the panorama cube should render into
         * this frame. Same dimensions as the default framebuffer, so the
         * viewport and the cube's projection stay untouched.
         *
         * @return false when the offscreen targets are unavailable (FBO
         *         creation failed); the caller should then let the cube
         *         draw straight to the screen, unblurred.
         */
        public static boolean bindPanoramaTarget(WebGL2RenderingContext gl, int fbWidth, int fbHeight) {
                if (gl == null || fbWidth <= 0 || fbHeight <= 0) return false;
                if (!ensureTargets(gl, fbWidth, fbHeight)) return false;
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, framebufferA);
                return true;
        }

        /**
         * Runs the vanilla blur passes over the panorama target and copies
         * the final image to the default framebuffer. Call after the cube
         * has drawn into {@link #bindPanoramaTarget}, while the GUI batch
         * is still closed.
         *
         * @param blurRadius the menuBackgroundBlurriness option (0..10)
         */
        public static void present(WebGL2RenderingContext gl, int fbWidth, int fbHeight, int blurRadius) {
                if (gl == null || !targetsOk || framebufferA == null) return;
                if (fbWidth <= 0 || fbHeight <= 0) return;

                // The caller's blend state (GUI alpha blending) must not
                // leak into the post passes: vanilla runs them through
                // pipelines with blending disabled.
                boolean blendWasEnabled =
                        PlatformOpenGL._wglIsEnabled(WebGL2RenderingContext.BLEND);
                boolean cullWasEnabled =
                        PlatformOpenGL._wglIsEnabled(WebGL2RenderingContext.CULL_FACE);
                if (blendWasEnabled) PlatformOpenGL._wglDisable(WebGL2RenderingContext.BLEND);
                if (cullWasEnabled) PlatformOpenGL._wglDisable(WebGL2RenderingContext.CULL_FACE);
                PlatformOpenGL._wglDisable(WebGL2RenderingContext.DEPTH_TEST);
                PlatformOpenGL._wglActiveTexture(WebGL2RenderingContext.TEXTURE0);

                try {
                        if (blurRadius >= 1) {
                                PlatformOpenGL._wglUseProgram(blurProgram);
                                PlatformOpenGL._wglUniform1i(uInSampler, 0);
                                PlatformOpenGL._wglUniform1f(uRadius, (float) blurRadius);
                                PlatformOpenGL._wglUniform2f(uInSize,
                                        (float) targetWidth, (float) targetHeight);

                                PlatformOpenGL._wglBindVertexArray(quadVAO);
                                JSObject srcTex = textureA;
                                JSObject dstFb = framebufferB;
                                // 6 passes: H, V, H, V, H, V - ping-pong A and B.
                                // Each pass samples srcTex and writes dstFb; then
                                // the texture just written becomes the next source.
                                for (int i = 0; i < PASSES; i++) {
                                        PlatformOpenGL._wglBindFramebuffer(
                                                WebGL2RenderingContext.FRAMEBUFFER, dstFb);
                                        PlatformOpenGL._wglBindTexture(
                                                WebGL2RenderingContext.TEXTURE_2D, srcTex);
                                        // (1,0) = horizontal, (0,1) = vertical
                                        if ((i & 1) == 0) {
                                                PlatformOpenGL._wglUniform2f(uBlurDir, 1.0f, 0.0f);
                                        } else {
                                                PlatformOpenGL._wglUniform2f(uBlurDir, 0.0f, 1.0f);
                                        }
                                        PlatformOpenGL._wglDrawArrays(
                                                WebGL2RenderingContext.TRIANGLES, 0, 6);
                                        srcTex = (srcTex == textureA) ? textureB : textureA;
                                        dstFb = (dstFb == framebufferA) ? framebufferB : framebufferA;
                                }
                                // After the 6th pass the final image lives in srcTex
                                PlatformOpenGL._wglBindVertexArray(null);
                                blitToScreen(gl, srcTex);
                        } else {
                                // Vanilla: blurRadius < 1 -> no effect at all,
                                // the panorama stays sharp.
                                blitToScreen(gl, textureA);
                        }
                } finally {
                        PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, null);
                        PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, null);
                        PlatformOpenGL._wglUseProgram(null);
                        if (blendWasEnabled) PlatformOpenGL._wglEnable(WebGL2RenderingContext.BLEND);
                        if (cullWasEnabled) PlatformOpenGL._wglEnable(WebGL2RenderingContext.CULL_FACE);
                }
        }

        /** Copies a fullscreen texture to the default framebuffer. */
        private static void blitToScreen(WebGL2RenderingContext gl, JSObject texture) {
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, null);
                PlatformOpenGL._wglUseProgram(blitProgram);
                PlatformOpenGL._wglUniform1i(blitUInSampler, 0);
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, texture);
                PlatformOpenGL._wglBindVertexArray(quadVAO);
                PlatformOpenGL._wglDrawArrays(WebGL2RenderingContext.TRIANGLES, 0, 6);
                PlatformOpenGL._wglBindVertexArray(null);
        }

        // ===== Setup =====

        private static boolean ensureTargets(WebGL2RenderingContext gl, int width, int height) {
                if (targetsOk && targetWidth == width && targetHeight == height) return true;
                if (!initialized) {
                        initPrograms(gl);
                        if (!initialized) return false;
                }
                releaseTargets(gl);
                targetWidth = width;
                targetHeight = height;

                textureA = createTargetTexture(gl, width, height);
                textureB = createTargetTexture(gl, width, height);
                framebufferA = PlatformOpenGL._wglCreateFramebuffer();
                framebufferB = PlatformOpenGL._wglCreateFramebuffer();
                if (textureA == null || textureB == null
                                || framebufferA == null || framebufferB == null) {
                        targetsOk = false;
                        return false;
                }
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, framebufferA);
                PlatformOpenGL._wglFramebufferTexture2D(WebGL2RenderingContext.FRAMEBUFFER,
                        WebGL2RenderingContext.COLOR_ATTACHMENT0,
                        WebGL2RenderingContext.TEXTURE_2D, textureA, 0);
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, framebufferB);
                PlatformOpenGL._wglFramebufferTexture2D(WebGL2RenderingContext.FRAMEBUFFER,
                        WebGL2RenderingContext.COLOR_ATTACHMENT0,
                        WebGL2RenderingContext.TEXTURE_2D, textureB, 0);
                int statusA = statusOf(gl, framebufferA);
                int statusB = statusOf(gl, framebufferB);
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, null);
                if (statusA != WebGL2RenderingContext.FRAMEBUFFER_COMPLETE
                                || statusB != WebGL2RenderingContext.FRAMEBUFFER_COMPLETE) {
                        ClientMain.warn("[MenuBlurRenderer] framebuffer incomplete: A=" + statusA
                                        + " B=" + statusB + " - blur disabled");
                        targetsOk = false;
                        return false;
                }
                targetsOk = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[MenuBlurRenderer] targets ready (" + width + "x" + height + ")");
                }
                return true;
        }

        private static int statusOf(WebGL2RenderingContext gl, JSObject framebuffer) {
                PlatformOpenGL._wglBindFramebuffer(WebGL2RenderingContext.FRAMEBUFFER, framebuffer);
                return PlatformOpenGL._wglCheckFramebufferStatus(WebGL2RenderingContext.FRAMEBUFFER);
        }

        private static void releaseTargets(WebGL2RenderingContext gl) {
                if (textureA != null) PlatformOpenGL._wglDeleteTexture(textureA);
                if (textureB != null) PlatformOpenGL._wglDeleteTexture(textureB);
                if (framebufferA != null) PlatformOpenGL._wglDeleteFramebuffer(framebufferA);
                if (framebufferB != null) PlatformOpenGL._wglDeleteFramebuffer(framebufferB);
                textureA = null;
                textureB = null;
                framebufferA = null;
                framebufferB = null;
        }

        /** RGBA8, LINEAR (the box blur relies on bilinear sampling), CLAMP_TO_EDGE. */
        private static JSObject createTargetTexture(WebGL2RenderingContext gl, int width, int height) {
                JSObject tex = PlatformOpenGL._wglCreateTexture();
                if (tex == null) return null;
                PlatformOpenGL._wglBindTexture(WebGL2RenderingContext.TEXTURE_2D, tex);
                PlatformOpenGL._wglTexImage2D(WebGL2RenderingContext.TEXTURE_2D, 0,
                        WebGL2RenderingContext.RGBA8, width, height, 0,
                        WebGL2RenderingContext.RGBA, WebGL2RenderingContext.UNSIGNED_BYTE, null);
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

        /**
         * The screenquad vertex shader (vanilla {@code core/screenquad.vsh})
         * expressed as an explicit VBO: two triangles covering the screen,
         * uv 0..1 matching the NDC extents (no orientation flips anywhere,
         * so the offscreen round-trip is pixel-identical at 1:1).
         */
        private static void initPrograms(WebGL2RenderingContext gl) {
                String quadVsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "in vec2 a_pos;\n"
                                + "in vec2 a_uv;\n"
                                + "out vec2 texCoord;\n"
                                + "void main() {\n"
                                + "  gl_Position = vec4(a_pos, 0.0, 1.0);\n"
                                + "  texCoord = a_uv;\n"
                                + "}\n";

                // Vanilla post/box_blur.fsh - math kept verbatim (see class doc)
                String blurFsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "uniform sampler2D InSampler;\n"
                                + "uniform vec2 u_blurDir;\n"
                                + "uniform float u_radius;\n"
                                + "uniform vec2 u_inSize;\n"
                                + "in vec2 texCoord;\n"
                                + "out vec4 fragColor;\n"
                                + "void main() {\n"
                                + "  vec2 oneTexel = 1.0 / u_inSize;\n"
                                + "  vec2 sampleStep = oneTexel * u_blurDir;\n"
                                + "  vec4 blurred = vec4(0.0);\n"
                                + "  float actualRadius = u_radius >= 0.5 ? floor(u_radius + 0.5) : 5.0;\n"
                                + "  for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {\n"
                                + "    blurred += texture(InSampler, texCoord + sampleStep * a);\n"
                                + "  }\n"
                                + "  blurred += texture(InSampler, texCoord + sampleStep * actualRadius) / 2.0;\n"
                                + "  fragColor = blurred / (actualRadius + 0.5);\n"
                                + "}\n";

                // Plain copy pass for the final blit to the screen
                String blitFsh = "#version 300 es\n"
                                + "precision highp float;\n"
                                + "uniform sampler2D InSampler;\n"
                                + "in vec2 texCoord;\n"
                                + "out vec4 fragColor;\n"
                                + "void main() {\n"
                                + "  fragColor = texture(InSampler, texCoord);\n"
                                + "}\n";

                blurProgram = EaglerShaderImpl.createProgramFromSource(quadVsh, blurFsh, "menu_box_blur");
                blitProgram = EaglerShaderImpl.createProgramFromSource(quadVsh, blitFsh, "menu_blit");
                if (blurProgram == null || blitProgram == null) {
                        ClientMain.warn("[MenuBlurRenderer] failed to create the blur programs");
                        return;
                }
                uInSampler = EaglerShaderImpl.getUniformLocation(blurProgram, "InSampler");
                uBlurDir = EaglerShaderImpl.getUniformLocation(blurProgram, "u_blurDir");
                uRadius = EaglerShaderImpl.getUniformLocation(blurProgram, "u_radius");
                uInSize = EaglerShaderImpl.getUniformLocation(blurProgram, "u_inSize");
                blitUInSampler = EaglerShaderImpl.getUniformLocation(blitProgram, "InSampler");

                float[] quad = new float[] {
                                // pos (NDC)   uv
                                -1.0f, -1.0f, 0.0f, 0.0f,
                                 1.0f, -1.0f, 1.0f, 0.0f,
                                 1.0f,  1.0f, 1.0f, 1.0f,
                                -1.0f, -1.0f, 0.0f, 0.0f,
                                 1.0f,  1.0f, 1.0f, 1.0f,
                                -1.0f,  1.0f, 0.0f, 1.0f
                };
                quadVAO = PlatformOpenGL._wglCreateVertexArray();
                PlatformOpenGL._wglBindVertexArray(quadVAO);
                quadVBO = PlatformOpenGL._wglCreateBuffer();
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, quadVBO);
                Float32Array vboData = Float32Array.create(quad.length);
                for (int i = 0; i < quad.length; i++) {
                        vboData.set(i, quad[i]);
                }
                PlatformOpenGL._wglBufferData(WebGL2RenderingContext.ARRAY_BUFFER, vboData,
                                WebGL2RenderingContext.STATIC_DRAW);
                int posAttrib = EaglerShaderImpl.getAttribLocation(blurProgram, "a_pos");
                int uvAttrib = EaglerShaderImpl.getAttribLocation(blurProgram, "a_uv");
                PlatformOpenGL._wglEnableVertexAttribArray(posAttrib);
                PlatformOpenGL._wglVertexAttribPointer(posAttrib, 2,
                                WebGL2RenderingContext.FLOAT, false, 16, 0);
                PlatformOpenGL._wglEnableVertexAttribArray(uvAttrib);
                PlatformOpenGL._wglVertexAttribPointer(uvAttrib, 2,
                                WebGL2RenderingContext.FLOAT, false, 16, 8);
                PlatformOpenGL._wglBindVertexArray(null);
                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                initialized = true;
                if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                        ClientMain.log("[MenuBlurRenderer] programs ready");
                }
        }
}
