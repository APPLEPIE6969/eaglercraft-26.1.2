package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.EaglerCraftConfig;
import net.lax1dude.eaglercraft.v2_6.EaglerProfile;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerShaderImpl;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.screens.TitleScreen.
 * The title screen is the first screen shown when MC starts.
 * In EaglerCraft, it renders a gradient background via EaglerShaderImpl.
 */
public class TitleScreen extends Screen {

        private static final String TITLE = "EaglerCraft " + EaglerCraftConfig.VERSION;
        private static final String SUBTITLE = EaglerCraftConfig.BRAND + " | Protocol " + EaglerCraftConfig.PROTOCOL_VERSION;

        /** The name of the world the Singleplayer button creates/loads. */
        public static final String DEFAULT_WORLD_NAME = "New World";

        private boolean fadingIn = true;
        private long fadeInStart;
        private float fadeInAlpha = 0.0f;

        public TitleScreen() {
                super();
        }

        @Override
        protected void init() {
                // Vanilla lays the menu out as width/2 - 100 wide buttons,
                // 24px apart, starting at height/4 + 48.
                int buttonX = (this.width - Button.BIG_WIDTH) / 2;
                int buttonY = this.height / 4 + 48;
                this.addRenderableWidget(Button.builder("Singleplayer", button -> {
                        if (this.minecraft != null) {
                                ClientMain.log("[TitleScreen] Singleplayer pressed -> loading world '" + DEFAULT_WORLD_NAME + "'");
                                this.minecraft.doWorldLoad(DEFAULT_WORLD_NAME);
                        }
                })).setPosition(buttonX, buttonY);
        }

        @Override
        public void init(Minecraft minecraft, int width, int height) {
                super.init(minecraft, width, height);
                this.fadeInStart = System.currentTimeMillis();
                this.fadingIn = true;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // Render gradient background
                if (this.minecraft != null) {
                        int width = minecraft.getWindow().getWidth();
                        int height = minecraft.getWindow().getHeight();

                        // Use EaglerShaderImpl for the title screen rendering
                        EaglerShaderImpl.renderTitleScreen(
                                ClientMain.getWebGL2(),
                                width, height,
                                TITLE,
                                SUBTITLE,
                                EaglerProfile.getUsername(),
                                0,
                                0
                        );
                }

                // Buttons render on top of the background via the base class
                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }

        @Override
        public void tick() {
                // Update fade-in animation
                if (this.fadingIn) {
                        long elapsed = System.currentTimeMillis() - this.fadeInStart;
                        this.fadeInAlpha = Math.min(1.0f, elapsed / 2000.0f);
                        if (this.fadeInAlpha >= 1.0f) {
                                this.fadingIn = false;
                        }
                }
        }

        @Override
        public boolean shouldCloseOnEsc() {
                return false;
        }

        @Override
        public void removed() {
                super.removed();
        }
}
