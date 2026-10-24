package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code ControlsScreen}
 * - the "Controls" settings page.
 *
 * <p>Layout from the decompiled 26.1.2 screen: title, the two
 * sub-screen buttons {@code [Mouse Settings...][Key Binds...]}, then
 * the control-behaviour option rows - the four toggle switches
 * (toggleCrouch/toggleSprint/toggleAttack/toggleUse, rendered
 * Hold/Toggle like vanilla), Auto-Jump, the sprintWindow slider
 * (0..10 ticks, default 7) and operatorItemsTab:</p>
 *
 * <pre>
 *                Controls
 *      [Mouse Settings...][Key Binds...]
 *      [Toggle Crouch: Hold][Toggle Sprint: Hold]
 *      [Toggle Attack: Hold][Toggle Use: Hold]
 *      [Auto-Jump: ON     ][Sprint Window: 7 Ticks]
 *      [Operator Items Tab: OFF]
 *                [    Done    ]
 * </pre>
 *
 * <p>Every row writes straight into {@link Options} and persists on
 * change (vanilla saves options when the screen closes; the port saves
 * eagerly like the other option rows). The gameplay layer consumes
 * toggleCrouch/toggleSprint through the input tick and sprintWindow
 * through the double-tap logic.</p>
 */
public class ControlsScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla option widget width (OptionInstance buttons are 150). */
        private static final int WIDGET_WIDTH = 150;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        protected final Options options;
        protected final Screen lastScreen;

        public ControlsScreen(Screen lastScreen, Options options) {
                this.lastScreen = lastScreen;
                this.options = options;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;

                // ===== Sub-screen row: Mouse Settings + Key Binds =====
                int topY = TITLE_Y + 9 + 17;
                int leftX = centerX - WIDGET_WIDTH - 4;
                int rightX = centerX + 4;
                this.addRenderableWidget(new Button(leftX, topY, WIDGET_WIDTH,
                                Button.HEIGHT, "Mouse Settings...", b -> {
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new MouseSettingsScreen(
                                                                this, this.options));
                                        }
                                }));
                this.addRenderableWidget(new Button(rightX, topY, WIDGET_WIDTH,
                                Button.HEIGHT, "Key Binds...", b -> {
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new KeyBindsScreen(
                                                                this, this.options));
                                        }
                                }));

                // ===== Option rows (2-column grid like vanilla) =====
                int gridX = centerX - WIDGET_WIDTH - 4;
                int rowY = topY + Button.HEIGHT + 8;
                int rowStep = Button.HEIGHT + 4;

                this.addRenderableWidget(new CycleButton(gridX, rowY, WIDGET_WIDTH,
                                Button.HEIGHT, "Toggle Crouch",
                                new String[] { "Hold", "Toggle" },
                                this.options.toggleCrouch ? 1 : 0,
                                (b, i) -> {
                                        this.options.toggleCrouch = i == 1;
                                        this.options.save();
                                }));
                this.addRenderableWidget(new CycleButton(rightX, rowY, WIDGET_WIDTH,
                                Button.HEIGHT, "Toggle Sprint",
                                new String[] { "Hold", "Toggle" },
                                this.options.toggleSprint ? 1 : 0,
                                (b, i) -> {
                                        this.options.toggleSprint = i == 1;
                                        this.options.save();
                                }));

                int row2Y = rowY + rowStep;
                this.addRenderableWidget(new CycleButton(gridX, row2Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Toggle Attack",
                                new String[] { "Hold", "Toggle" },
                                this.options.toggleAttack ? 1 : 0,
                                (b, i) -> {
                                        this.options.toggleAttack = i == 1;
                                        this.options.save();
                                }));
                this.addRenderableWidget(new CycleButton(rightX, row2Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Toggle Use",
                                new String[] { "Hold", "Toggle" },
                                this.options.toggleUse ? 1 : 0,
                                (b, i) -> {
                                        this.options.toggleUse = i == 1;
                                        this.options.save();
                                }));

                int row3Y = row2Y + rowStep;
                this.addRenderableWidget(new CycleButton(gridX, row3Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Auto-Jump",
                                new String[] { "OFF", "ON" },
                                this.options.autoJump ? 1 : 0,
                                (b, i) -> {
                                        this.options.autoJump = i == 1;
                                        this.options.save();
                                }));
                this.addRenderableWidget(new SprintWindowSlider(rightX, row3Y,
                                WIDGET_WIDTH, Button.HEIGHT, this.options));

                int row4Y = row3Y + rowStep;
                this.addRenderableWidget(new CycleButton(gridX, row4Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Operator Items Tab",
                                new String[] { "OFF", "ON" },
                                this.options.operatorItemsTab ? 1 : 0,
                                (b, i) -> {
                                        this.options.operatorItemsTab = i == 1;
                                        this.options.save();
                                }));

                // ===== Footer: Done =====
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - FOOTER_HEIGHT + 6, 200, Button.HEIGHT,
                                "Done", button -> this.onClose()));
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Controls");
                EaglerGuiImpl.drawText("Controls",
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
         * The vanilla sprintWindow slider: 0..10 integer ticks, default 7.
         * The value is the double-tap window for the sprint trigger
         * (LocalPlayer's sprintTriggerTime).
         */
        static class SprintWindowSlider extends AbstractSliderButton {

                private static final int MAX_WINDOW = 10;

                private final Options options;

                SprintWindowSlider(int x, int y, int width, int height, Options options) {
                        super(x, y, width, height,
                                        (double) options.sprintWindow / (double) MAX_WINDOW);
                        this.options = options;
                }

                @Override
                protected String displayString() {
                        return "Sprint Window: " + this.options.sprintWindow + " Ticks";
                }

                @Override
                protected void applyValue() {
                        int window = (int) Math.round(this.value * (double) MAX_WINDOW);
                        if (window != this.options.sprintWindow) {
                                this.options.sprintWindow = window;
                                this.options.save();
                        }
                }

                @Override
                public String getTelemetryId() {
                        return "sprintwindow";
                }
        }
}
