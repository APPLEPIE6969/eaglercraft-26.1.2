package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.gui.components.Button;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code PauseScreen} -
 * the in-world game menu opened with ESC (vanilla {@code Minecraft.pauseGame}).
 *
 * <p>Layout copied from the decompiled 26.1.2 {@code PauseScreen}:
 * the "Game Menu" title at y=40 and a 2-column button grid
 * ({@code COLUMNS=2}, {@code BUTTON_WIDTH_FULL=204},
 * {@code BUTTON_WIDTH_HALF=98}, 4px cell padding) aligned at the 25%
 * height mark ({@code FrameLayout.alignInRectangle(..., 0.5F, 0.25F)}):</p>
 *
 * <pre>
 *            [       Back to Game        ]
 *            [ Advancements ][ Statistics ]
 *            [ Send Feedback][ Report Bugs]
 *            [ Options...   ][ Open to LAN]
 *            [   Save and Quit to Title   ]
 * </pre>
 *
 * <p>Vanilla behavior notes carried over: the background is the world
 * with the HUD, run through the menu-background blur
 * ({@code GameRenderer.processBlurEffect} - implemented in
 * {@code GameRenderer} via {@code MenuBlurRenderer.blurScreenContent});
 * "Send Feedback"/"Report Bugs" open Mojang's real URLs
 * (feedback.minecraft.net / bugs.mojang.com); Advancements, Statistics
 * and Open to LAN have no backing systems in the browser port yet and
 * render in vanilla's disabled style.</p>
 */
public class PauseScreen extends Screen {

        /** Vanilla PauseScreen constants. */
        private static final int MENU_PADDING_TOP = 50;
        private static final int BUTTON_WIDTH_FULL = 204;
        private static final int BUTTON_WIDTH_HALF = 98;
        /** Cell padding of the vanilla GridLayout (4,4,4,0) -> 8px gaps. */
        private static final int CELL_GAP = 4;
        private static final int GRID_WIDTH = BUTTON_WIDTH_FULL + CELL_GAP * 2;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        /** Vanilla title: showPauseMenu ? "menu.game" : "menu.paused". */
        private final String title;

        public PauseScreen(boolean showPauseMenu) {
                this.title = showPauseMenu ? "Game Menu" : "Paused";
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int gx = centerX - GRID_WIDTH / 2;

                // Grid height: 5 rows x 20 + 4px row gaps, first row pushed
                // down by MENU_PADDING_TOP; the whole block is aligned at
                // the 25% height mark like vanilla's alignInRectangle.
                int gridH = MENU_PADDING_TOP + 5 * Button.HEIGHT + 4 * CELL_GAP;
                int gy = (int) ((float) (this.height - gridH) * 0.25f);

                int colL = gx + CELL_GAP;
                int colR = gx + CELL_GAP + BUTTON_WIDTH_HALF + CELL_GAP;

                // Row 0: Back to Game, colspan 2, paddingTop 50
                this.addRenderableWidget(new Button(colL, gy + MENU_PADDING_TOP,
                                BUTTON_WIDTH_FULL, Button.HEIGHT, "Back to Game",
                                button -> {
                                        ClientMain.log("[PauseScreen] Back to Game");
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(null);
                                                this.minecraft.getMouseHandler().grabMouse();
                                        }
                                }));

                // Row 1: Advancements | Statistics (no systems yet: disabled)
                Button advancements = new Button(colL, gy + MENU_PADDING_TOP
                                + (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Advancements", button -> { });
                advancements.setActive(false);
                this.addRenderableWidget(advancements);

                Button stats = new Button(colR, gy + MENU_PADDING_TOP
                                + (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Statistics", button -> { });
                stats.setActive(false);
                this.addRenderableWidget(stats);

                // Row 2: Send Feedback | Report Bugs - vanilla opens Mojang's
                // real feedback/report URLs in the browser
                this.addRenderableWidget(new Button(colL, gy + MENU_PADDING_TOP
                                + 2 * (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Send Feedback", button -> PlatformInput.openURL(
                                                "https://feedback.minecraft.net", "_blank")));
                this.addRenderableWidget(new Button(colR, gy + MENU_PADDING_TOP
                                + 2 * (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Report Bugs", button -> PlatformInput.openURL(
                                                "https://bugs.mojang.com", "_blank")));

                // Row 3: Options... | Open to LAN (no networking: disabled)
                this.addRenderableWidget(new Button(colL, gy + MENU_PADDING_TOP
                                + 3 * (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Options...", button -> {
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new net.minecraft.client.gui.screens.options.OptionsScreen(
                                                                this, this.minecraft.getOptions(), true));
                                        }
                                }));

                Button openToLan = new Button(colR, gy + MENU_PADDING_TOP
                                + 3 * (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_HALF, Button.HEIGHT,
                                "Open to LAN", button -> { });
                openToLan.setActive(false);
                this.addRenderableWidget(openToLan);

                // Row 4: Save and Quit to Title (local worlds) / Disconnect
                // (multiplayer) - vanilla disconnectButtonLabel, colspan 2.
                // Both paths run Minecraft.disconnect, which closes the
                // gateway connection first and never persists remote worlds.
                boolean remote = this.minecraft != null && this.minecraft.isMultiplayer();
                this.addRenderableWidget(new Button(colL, gy + MENU_PADDING_TOP
                                + 4 * (Button.HEIGHT + CELL_GAP), BUTTON_WIDTH_FULL, Button.HEIGHT,
                                remote ? "Disconnect" : "Save and Quit to Title", button -> {
                                        ClientMain.log(remote
                                                        ? "[PauseScreen] Disconnect"
                                                        : "[PauseScreen] Save and Quit to Title");
                                        if (this.minecraft != null) {
                                                this.minecraft.disconnect();
                                        }
                                }));
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // No extractBackground call in vanilla PauseScreen: the world
                // below was already blurred by GameRenderer.processBlurEffect
                // (this screen's shouldBlurBackground is the base true).

                // Title: StringWidget centered at y=40 (vanilla init)
                int titleWidth = EaglerGuiImpl.widthOf(this.title);
                EaglerGuiImpl.drawText(this.title,
                                this.width / 2 - titleWidth / 2.0f, 40, COLOR_TEXT, true);

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                // Vanilla: ESC closes the pause menu (returns to the game)
                if (keyCode == 1 && this.shouldCloseOnEsc()) {
                        ClientMain.log("[PauseScreen] ESC -> Back to Game");
                        this.onClose();
                        return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
