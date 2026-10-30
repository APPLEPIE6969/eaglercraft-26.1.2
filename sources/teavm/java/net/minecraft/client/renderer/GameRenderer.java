package net.minecraft.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.adapter.MenuBlurRenderer;
import net.lax1dude.eaglercraft.v2_6.adapter.PanoramaRenderer;
import net.lax1dude.eaglercraft.v2_6.adapter.SkyRenderer;
import net.lax1dude.eaglercraft.v2_6.adapter.WorldGlRenderer;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.BlockHitResult;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.renderer.GameRenderer.
 * Main renderer stub that handles the rendering pipeline dispatch.
 * In the full implementation, this would manage shaders, camera, and world rendering.
 * For now, it provides enough structure for TeaVM compilation and renders
 * the current screen if MC is in a screen state.
 */
public class GameRenderer {

        private final Minecraft minecraft;
        private float zoom = 1.0f;
        private float zoomX;
        private float zoomY;
        private Object mainCamera; // Camera

        public GameRenderer(Minecraft minecraft) {
                this.minecraft = minecraft;
                this.mainCamera = new Camera();
        }

        /**
         * Renders one frame.
         *
         * <p>Frame layout (vanilla order): clear -> HUD (when in a world) ->
         * screen (menus + widgets) on top. Everything 2D goes through
         * {@link EaglerGuiImpl}'s batched quad pipeline; the batch re-binds
         * lazily, so interleaved GL work (the title gradient) stays safe.</p>
         *
         * @param partialTick         Vanilla DeltaTracker.getGameTimeDeltaPartialTick:
         *                             interpolation factor [0,1) between simulation ticks
         * @param realtimeDeltaTicks  Vanilla getRealtimeDeltaTicks: this frame's
         *                             real-time delta expressed in ticks (clocks that
         *                             must advance with wall time, e.g. the panorama)
         * @param currentTimeMs       Current time in milliseconds
         * @param renderLevel         Whether to render the 3D world
         */
        public void render(float partialTick, float realtimeDeltaTicks, long currentTimeMs, boolean renderLevel) {
                int pxWidth = PlatformRuntime.getCanvasDrawableWidth();
                int pxHeight = PlatformRuntime.getCanvasDrawableHeight();
                if (pxWidth <= 0 || pxHeight <= 0) return;

                WebGL2RenderingContext gl = ClientMain.getWebGL2();
                if (gl == null) return;

                int guiWidth = minecraft.getWindow().getGuiScaledWidth();
                int guiHeight = minecraft.getWindow().getGuiScaledHeight();
                if (guiWidth <= 0 || guiHeight <= 0) return;

                // Keep the GL viewport glued to the actual drawing buffer on
                // every frame. The viewport used to be sized once at boot; a
                // buffer that settles later (or at a different device pixel
                // ratio) then rendered into a stale, overscanned viewport,
                // clipping the right/bottom of the GUI until the user manually
                // resized the window.
                PlatformOpenGL._wglViewport(0, 0, pxWidth, pxHeight);

                try {
                        // Vanilla clears to the fog color; with the day/night
                        // timeline live the fog color IS the sky-fog horizon
                        // color the disc blends into. Menus keep the classic
                        // constant (the panorama covers the screen anyway).
                        net.minecraft.client.multiplayer.ClientLevel skyLevel =
                                        minecraft.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel l
                                                        && minecraft.isLevelLoaded() ? l : null;
                        if (skyLevel != null) {
                                net.minecraft.client.player.LocalPlayer skyPlayer = minecraft.getPlayer();
                                SkyRenderer.updateState(skyLevel.getDayTime(), partialTick,
                                                skyPlayer != null ? skyPlayer.getEyeY() : 0.0D);
                                PlatformOpenGL._wglClearColor(SkyRenderer.fogR(), SkyRenderer.fogG(),
                                                SkyRenderer.fogB(), 1.0f);
                        } else {
                                PlatformOpenGL._wglClearColor(0.627f, 0.808f, 1.0f, 1.0f);
                        }
                        PlatformOpenGL._wglClear(
                                WebGL2RenderingContext.COLOR_BUFFER_BIT
                                | WebGL2RenderingContext.DEPTH_BUFFER_BIT);

                        // GUI state: blended translucent quads, no depth writes
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                        RenderSystem.disableDepthTest();

                        EaglerGuiImpl.begin(gl, guiWidth, guiHeight);

                        // Vanilla Screen.extractBackground: when no level is
                        // loaded, every menu screen draws the rotating panorama
                        // cubemap behind the GUI. The cube renders into an
                        // offscreen target that the vanilla menu-background
                        // blur (post_effect/blur.json, radius =
                        // options.menuBackgroundBlurriness, default 5) then
                        // processes and blits to the screen; menu tiles are
                        // drawn on top of the blurred image by the screens.
                        if (minecraft.screen != null && !minecraft.isLevelLoaded()) {
                                // Vanilla Panorama.render uses the realtime clock
                                // (DeltaTracker.getRealtimeDeltaTicks), not the
                                // game-tick fraction: the menu spins even when
                                // the game is paused
                                PanoramaRenderer.tick(realtimeDeltaTicks);
                                // Close the (still empty) GUI batch - the cube
                                // switches programs, and the next GUI draw
                                // re-opens the batch. render() also polls the
                                // pending face downloads, so it must run
                                // unconditionally (gating on isReady() first
                                // would deadlock the build-once cubemap).
                                EaglerGuiImpl.flush(gl);
                                boolean haveTargets =
                                        MenuBlurRenderer.bindPanoramaTarget(gl, pxWidth, pxHeight);
                                boolean panoramaDrawn =
                                        PanoramaRenderer.render(gl, pxWidth, pxHeight);
                                if (haveTargets) {
                                        // Back to the default framebuffer before
                                        // anything else draws to the screen
                                        PlatformOpenGL._wglBindFramebuffer(
                                                WebGL2RenderingContext.FRAMEBUFFER, null);
                                        if (panoramaDrawn) {
                                                // Vanilla: TitleScreen has an empty
                                                // extractBackground -> no blur marker,
                                                // the panorama stays sharp there
                                                int blurRadius =
                                                        minecraft.screen.shouldBlurBackground()
                                                                        ? minecraft.getOptions()
                                                                                .menuBackgroundBlurriness
                                                                        : 0;
                                                MenuBlurRenderer.present(gl, pxWidth, pxHeight,
                                                                blurRadius);
                                        }
                                }
                                if (!panoramaDrawn) {
                                        // Faces still decoding (or failed): flat quad in
                                        // the vanilla default-jar panorama stub color.
                                        // A flat color is its own blur, so skipping
                                        // the post passes here is bit-identical.
                                        EaglerGuiImpl.fillRect(0, 0, guiWidth, guiHeight,
                                                        PanoramaRenderer.STUB_R, PanoramaRenderer.STUB_G,
                                                        PanoramaRenderer.STUB_B, 1.0f);
                                }
                        }

                        // The 3D world (vanilla renderLevel): sky pass first
                        // (vanilla addSkyPass - disc, sunrise fan, sun/moon/
                        // stars, dark disc; rotation-only view, no depth
                        // writes), then the block meshes with the camera view,
                        // then the crosshair pick, then the hit outline +
                        // destroy overlay
                        if (minecraft.isLevelLoaded()) {
                                net.minecraft.client.player.LocalPlayer player = minecraft.getPlayer();
                                net.minecraft.client.multiplayer.ClientLevel level = minecraft.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel l ? l : null;
                                if (player != null && level != null) {
                                        float aspect = pxHeight > 0 ? (float) pxWidth / (float) pxHeight : 1.0f;
                                        // Close the (still empty) GUI batch - the sky
                                        // pass switches programs and attribute
                                        // pointers, and the next GUI draw must
                                        // re-open the batch and re-set its state
                                        // (the same contract the panorama honors)
                                        EaglerGuiImpl.flush(gl);
                                        SkyRenderer.renderFrame(gl, pxWidth, pxHeight,
                                                        minecraft.getOptions().fov, aspect,
                                                        player.yRot, player.xRot);
                                        WorldGlRenderer.setSkyLight(
                                                        (SkyRenderer.skyLightRgb() >> 16 & 0xFF) / 255.0f,
                                                        (SkyRenderer.skyLightRgb() >> 8 & 0xFF) / 255.0f,
                                                        (SkyRenderer.skyLightRgb() & 0xFF) / 255.0f);
                                        // Vanilla pick: from the eye along the view
                                        // vector, BLOCK_INTERACTION_RANGE long.
                                        // Vanilla Camera.setup reads getEyePosition
                                        // (partialTick): the eye position lerped
                                        // between the last two simulation ticks
                                        double eyeX = player.getEyeX(partialTick);
                                        double eyeY = player.getEyeY(partialTick);
                                        double eyeZ = player.getEyeZ(partialTick);
                                        double yawRad = player.yRot * (Math.PI / 180.0D);
                                        double pitchRad = player.xRot * (Math.PI / 180.0D);
                                        double cosPitch = Math.cos(pitchRad);
                                        double dirX = -Math.sin(yawRad) * cosPitch;
                                        double dirY = -Math.sin(pitchRad);
                                        double dirZ = Math.cos(yawRad) * cosPitch;
                                        minecraft.pickFromEye(eyeX, eyeY, eyeZ,
                                                dirX, dirY, dirZ,
                                                net.minecraft.client.player.LocalPlayer.BLOCK_INTERACTION_RANGE);

                                        minecraft.getLevelRenderer().renderLevel(gl,
                                                pxWidth, pxHeight,
                                                eyeX, eyeY, eyeZ,
                                                player.yRot, player.xRot,
                                                minecraft.getOptions().fov);

                                        // Vanilla renderHitOutline + destroy stage
                                        // (hidden with the HUD by the dev
                                        // setScene override - the straight-down
                                        // panorama face must stay clean)
                                        BlockHitResult hit = minecraft.getHitResult();
                                        if (hit != null && hit.type == BlockHitResult.Type.BLOCK
                                                        && !net.lax1dude.eaglercraft.v2_6.EaglerCraft.isHudHidden()) {
                                                minecraft.getLevelRenderer().renderHitOutline(gl,
                                                        hit.blockX, hit.blockY, hit.blockZ);
                                                int stage = minecraft.getGameMode().getDestroyStage();
                                                if (stage >= 0) {
                                                        minecraft.getLevelRenderer().renderDestroyProgress(gl,
                                                                hit.blockX, hit.blockY, hit.blockZ, stage);
                                                }
                                        }
                                        WorldGlRenderer.endFrame(gl);
                                }
                        }

                        // HUD overlay (crosshair + hotbar) when inside a world;
                        // flushed now so any screen below draws over it. The dev
                        // setScene({hideHud:true}) override (panorama face
                        // capture) hides this stratum.
                        if (minecraft.isLevelLoaded()
                                        && !net.lax1dude.eaglercraft.v2_6.EaglerCraft.isHudHidden()) {
                                minecraft.getGui().render(new PoseStack(), partialTick);
                        }
                        EaglerGuiImpl.flush(gl);

                        // Vanilla in-world menu blur (GameRenderer.processBlurEffect):
                        // when a screen is open over a loaded world and it declares a
                        // blurred background (Screen.shouldBlurBackground), the world +
                        // HUD content below the menu stratum runs through
                        // post_effect/blur.json (radius = menuBackgroundBlurriness) and
                        // is blitted back before the menu draws sharp on top.
                        if (minecraft.isLevelLoaded() && minecraft.screen != null
                                        && minecraft.screen.shouldBlurBackground()) {
                                MenuBlurRenderer.blurScreenContent(gl, pxWidth, pxHeight,
                                                minecraft.getOptions().menuBackgroundBlurriness);
                        }

                        // Current screen (menus + widgets), with the real mouse pos
                        Screen screen = minecraft.screen;
                        if (screen != null) {
                                screen.render(new PoseStack(),
                                        (int) minecraft.getMouseHandler().xpos(),
                                        (int) minecraft.getMouseHandler().ypos(),
                                        partialTick);
                        }
                        EaglerGuiImpl.flush(gl);

                } catch (Throwable t) {
                        // Don't let render errors crash the game loop
                        if (EaglerCraftConfig.VERBOSE_INIT_LOGGING) {
                                ClientMain.warn("[GameRenderer] render error: " + t.getMessage());
                        }
                }
        }

        /**
         * Returns the main camera.
         */
        public Camera getMainCamera() {
                return (Camera) mainCamera;
        }

        /**
         * Performs ray picking (object selection).
         * Stub - no ray picking in the browser override yet.
         */
        public void pick(float partialTick) {
                // no-op
        }

        /**
         * Gets the zoom level.
         */
        public float getZoom() {
                return zoom;
        }

        /**
         * Sets the zoom level.
         */
        public void setZoom(float zoom, float zoomX, float zoomY) {
                this.zoom = zoom;
                this.zoomX = zoomX;
                this.zoomY = zoomY;
        }

        /**
         * Closes/shuts down the renderer.
         */
        public void close() {
                // no-op
        }

        /**
         * Called when the window resizes.
         */
        public void resize(int width, int height) {
                // Update projection matrix - handled by render() on next frame
        }

        /**
         * Camera stub - holds position and orientation data.
         */
        public static class Camera {
                private double x, y, z;
                private float yaw, pitch;
                private boolean thirdPerson;

                public Camera() {
                        this.x = 0;
                        this.y = 64; // Default world height
                        this.z = 0;
                        this.yaw = 0;
                        this.pitch = 0;
                        this.thirdPerson = false;
                }

                public double getX() { return x; }
                public double getY() { return y; }
                public double getZ() { return z; }
                public float getYaw() { return yaw; }
                public float getPitch() { return pitch; }
                public boolean isThirdPerson() { return thirdPerson; }

                public void setPosition(double x, double y, double z) {
                        this.x = x; this.y = y; this.z = z;
                }

                public void setRotation(float yaw, float pitch) {
                        this.yaw = yaw; this.pitch = pitch;
                }

                public void setThirdPerson(boolean thirdPerson) {
                        this.thirdPerson = thirdPerson;
                }
        }
}
