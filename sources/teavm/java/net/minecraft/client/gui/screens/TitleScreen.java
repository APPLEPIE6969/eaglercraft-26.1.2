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
 * In EaglerCraft, it renders a gradient background via EaglerShaderImpl and
 * the vanilla 26.1.2 menu grid (see docs/UI-FIDELITY.md for the decompiled
 * layout this mirrors).
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
                // Decompiled 26.1.2 TitleScreen.init() layout: 24px row pitch,
                // first row at height/4 + 48, options row 36px below row 3.
                int topPos = this.height / 4 + 48;
                int centerX = this.width / 2;

                // Row 1: Singleplayer (live - loads the milestone's minimal world)
                this.addRenderableWidget(Button.builder("Singleplayer", button -> {
                        if (this.minecraft != null) {
                                ClientMain.log("[TitleScreen] Singleplayer pressed -> loading world '" + DEFAULT_WORLD_NAME + "'");
                                this.minecraft.doWorldLoad(DEFAULT_WORLD_NAME);
                        }
                })).setPosition(centerX - Button.BIG_WIDTH / 2, topPos);

                // Row 2: Multiplayer - vanilla disables it when multiplayer is
                // unavailable; ours stays disabled until the gateway screens exist.
                Button multiplayer = new Button(centerX - Button.BIG_WIDTH / 2, topPos + 24,
                                Button.BIG_WIDTH, Button.HEIGHT, "Multiplayer", button -> { });
                multiplayer.setActive(false);
                this.addRenderableWidget(multiplayer);

                // Row 3: Realms (vanilla label menu.online) - disabled.
                Button realms = new Button(centerX - Button.BIG_WIDTH / 2, topPos + 48,
                                Button.BIG_WIDTH, Button.HEIGHT, "Realms", button -> { });
                realms.setActive(false);
                this.addRenderableWidget(realms);

                // Vanilla accumulates the row position: createNormalMenuOptions
                // returns the Realms row (topPos + 48), then init() moves 36px
                // further down to the options row (height/4 + 84 total).
                topPos += 48 + 36;

                // Language icon button (vanilla: 20x20 sprite icon, left of Options)
                Button language = new Button(centerX - 124, topPos, 20, 20, "", button -> { });
                language.setActive(false);
                this.addRenderableWidget(language);

                // Options - disabled until an options screen exists.
                Button options = new Button(centerX - 100, topPos, 98, 20, "Options...", button -> { });
                options.setActive(false);
                this.addRenderableWidget(options);

                // Quit Game - vanilla calls minecraft.stop(); the browser-honest
                // equivalent is reloading the page.
                this.addRenderableWidget(new Button(centerX + 2, topPos, 98, 20, "Quit Game",
                                button -> {
                                        ClientMain.log("[TitleScreen] Quit pressed -> reloading page");
                                        ClientMain.reloadPage();
                                }));

                // Accessibility icon button (vanilla: 20x20 sprite icon, right of Quit)
                Button accessibility = new Button(centerX + 104, topPos, 20, 20, "", button -> { });
                accessibility.setActive(false);
                this.addRenderableWidget(accessibility);
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
