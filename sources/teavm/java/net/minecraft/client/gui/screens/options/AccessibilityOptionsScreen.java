package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code AccessibilityOptionsScreen} - the screen that owns the
 * "Menu Background Blurriness" option ({@code menuBackgroundBlurriness},
 * vanilla accessibility option, 0..10 with default 5).
 *
 * <p>Vanilla lays ~11 option rows into a 2-column grid; the browser
 * port backs exactly one of them today, so this screen renders the
 * vanilla chrome (title "Accessibility Settings", centered grid, Done
 * footer) with the blur slider as its first row. The slider writes
 * straight into {@link Options#menuBackgroundBlurriness} and persists
 * it to localStorage; {@code GameRenderer} consumes the value every
 * frame it runs {@code processBlurEffect}.</p>
 */
public class AccessibilityOptionsScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla option widget width (OptionInstance buttons are 150). */
        private static final int WIDGET_WIDTH = 150;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        protected final Options options;
        protected final Screen lastScreen;

        public AccessibilityOptionsScreen(Screen lastScreen, Options options) {
                this.lastScreen = lastScreen;
                this.options = options;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;

                // The blur slider sits in the grid's first row, left cell -
                // the position vanilla's menuBackgroundBlurriness row uses
                int gridX = centerX - WIDGET_WIDTH - 4;
                int rowY = TITLE_Y + 9 + 17;
                this.addRenderableWidget(new MenuBackgroundBlurrinessSlider(
                                gridX, rowY, WIDGET_WIDTH, Button.HEIGHT, this.options));

                // Footer: Done (200px, centered)
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - FOOTER_HEIGHT + 6, 200, Button.HEIGHT,
                                "Done", button -> this.onClose()));
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Accessibility Settings");
                EaglerGuiImpl.drawText("Accessibility Settings",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == 1 && this.shouldCloseOnEsc()) {
                        this.onClose();
                        return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void onClose() {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(this.lastScreen);
                }
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }

        /**
         * The menuBackgroundBlurriness slider: 0..10 integer steps, value
         * shown as the plain number (vanilla renders the raw option
         * value). Zero disables the blur entirely (vanilla only runs the
         * effect when the option is >= 1).
         */
        static class MenuBackgroundBlurrinessSlider extends AbstractSliderButton {

                private static final int MAX_BLUR = 10;

                private final Options options;

                MenuBackgroundBlurrinessSlider(int x, int y, int width, int height,
                                Options options) {
                        super(x, y, width, height,
                                        (double) options.menuBackgroundBlurriness
                                                        / (double) MAX_BLUR);
                        this.options = options;
                }

                @Override
                protected String displayString() {
                        return "Menu Background Blurriness: "
                                        + this.options.menuBackgroundBlurriness;
                }

                @Override
                protected void applyValue() {
                        int blur = (int) Math.round(this.value * (double) MAX_BLUR);
                        if (blur != this.options.menuBackgroundBlurriness) {
                                this.options.menuBackgroundBlurriness = blur;
                                this.options.save();
                        }
                }

                /** Drivable from the e2e bridge (click-to-set like vanilla). */
                @Override
                public String getTelemetryId() {
                        return "menubackgroundblurriness";
                }
        }
}
