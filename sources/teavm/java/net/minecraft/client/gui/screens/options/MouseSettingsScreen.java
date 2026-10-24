package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code MouseSettingsScreen} - opened from the Controls page's
 * "[Mouse Settings...]" button.
 *
 * <p>Rows from the decompiled 26.1.2 screen: the sensitivity slider
 * (0..1, rendered 0%..200% with vanilla's min/max labels
 * "Yaw Speed: *yawn*" / "HYPERSPEED!!!" and "Sensitivity: 100%" at the
 * default 0.5), Invert Mouse Y (live: flips the vertical look delta in
 * MouseHandler.turnPlayer), and the browser-inapplicable rows
 * (Discrete Scrolling, Scrolling Smoothness, Raw Input) rendered
 * disabled.</p>
 */
public class MouseSettingsScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla option widget width (OptionInstance buttons are 150). */
        private static final int WIDGET_WIDTH = 150;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        protected final Options options;
        protected final Screen lastScreen;

        public MouseSettingsScreen(Screen lastScreen, Options options) {
                this.lastScreen = lastScreen;
                this.options = options;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int gridX = centerX - WIDGET_WIDTH - 4;
                int rightX = centerX + 4;
                int rowStep = Button.HEIGHT + 4;

                int rowY = TITLE_Y + 9 + 17;
                this.addRenderableWidget(new SensitivitySlider(gridX, rowY,
                                WIDGET_WIDTH, Button.HEIGHT, this.options));
                this.addRenderableWidget(new CycleButton(rightX, rowY, WIDGET_WIDTH,
                                Button.HEIGHT, "Invert Mouse Y",
                                new String[] { "OFF", "ON" },
                                this.options.invertMouseY ? 1 : 0,
                                (b, i) -> {
                                        this.options.invertMouseY = i == 1;
                                        this.options.save();
                                }));

                // Browser-inapplicable rows: disabled, vanilla style
                int row2Y = rowY + rowStep;
                this.addRenderableWidget(this.disabledButton(gridX, row2Y,
                                "Discrete Scrolling: OFF"));
                this.addRenderableWidget(this.disabledButton(rightX, row2Y,
                                "Scrolling Smoothness: OFF"));
                this.addRenderableWidget(this.disabledButton(gridX, row2Y + rowStep,
                                "Raw Input: ON"));

                // ===== Footer: Done =====
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - FOOTER_HEIGHT + 6, 200, Button.HEIGHT,
                                "Done", button -> this.onClose()));
        }

        private Button disabledButton(int x, int y, String label) {
                Button button = new Button(x, y, WIDGET_WIDTH, Button.HEIGHT,
                                label, b -> { });
                button.setActive(false);
                return button;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Mouse Settings");
                EaglerGuiImpl.drawText("Mouse Settings",
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
         * The vanilla sensitivity slider: 0..1 mapped to 0%..200%.
         * Labels exactly like vanilla OptionInstance MOUSE_SENSITIVITY:
         * "Yaw Speed: *yawn*" at 0, "HYPERSPEED!!!" at max, and the
         * percentage otherwise (100% at the 0.5 default).
         */
        static class SensitivitySlider extends AbstractSliderButton {

                private final Options options;

                SensitivitySlider(int x, int y, int width, int height, Options options) {
                        super(x, y, width, height, options.sensitivity);
                        this.options = options;
                }

                private static String label(float sensitivity) {
                        if (sensitivity <= 0.0f) {
                                return "Yaw Speed: *yawn*";
                        }
                        if (sensitivity >= 1.0f) {
                                return "HYPERSPEED!!!";
                        }
                        return "Sensitivity: " + (int) (sensitivity * 200.0f) + "%";
                }

                @Override
                protected String displayString() {
                        return label(this.options.sensitivity);
                }

                @Override
                protected void applyValue() {
                        float sens = (float) this.value;
                        if (sens != this.options.sensitivity) {
                                this.options.sensitivity = sens;
                                this.options.save();
                        }
                }

                @Override
                public String getTelemetryId() {
                        return "sensitivity";
                }
        }
}
