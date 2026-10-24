package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code OptionsScreen} - the settings grid reached from the pause
 * menu ("Options...") and the title screen.
 *
 * <p>Layout from the decompiled 26.1.2 screen: a header with the
 * "Options" title and a sub-row ({@code FOV} slider next to the
 * in-world difficulty button - vanilla
 * {@code createWorldOptionsButtonOrDifficultyButton}), then the
 * 2-column button grid of the option sub-screens, then the 200px
 * "Done" footer button:</p>
 *
 * <pre>
 *                  Options
 *        [FOV: Normal ][Difficulty: Normal]
 *      [Skin Customization][Sound Settings ]
 *      [ Video Settings   ][Controls       ]
 *      [ Language...      ][Chat Settings  ]
 *      [ Resource Packs   ][Accessibility..]
 *      [ Telemetry        ][Credits...     ]
 *                 [    Done    ]
 * </pre>
 *
 * <p>The browser port backs only a few of these yet: FOV and
 * Accessibility (menu background blurriness) are fully live; the other
 * sub-screens render vanilla's disabled style until their systems
 * exist. The header difficulty button is only added in-world, exactly
 * like vanilla's {@code inWorld} flag.</p>
 */
public class OptionsScreen extends Screen {

        /** Vanilla title at the top of the header (StringWidget). */
        private static final int TITLE_Y = 8;

        /** Sub-header row: FOV slider + difficulty, 150px each, 8px gap. */
        private static final int HEADER_WIDGET_WIDTH = 150;

        /** Vanilla grid: 2 columns x 150px + 8px gap = 308, centered. */
        private static final int GRID_WIDTH = 2 * HEADER_WIDGET_WIDTH + 8;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        /** Vanilla grid rows, label left / right (26.1.2 sub-screens). */
        private static final String[] GRID_LEFT = {
                        "Skin Customization", "Video Settings", "Language...",
                        "Resource Packs", "Telemetry"
        };
        private static final String[] GRID_RIGHT = {
                        "Sound Settings...", "Controls...", "Chat Settings...",
                        "Accessibility...", "Credits & Attribution"
        };

        /** The options bag the sliders write through. */
        protected final Options options;

        /** The screen that opened this one (vanilla lastScreen). */
        protected final Screen lastScreen;

        /** Vanilla inWorld: adds the difficulty button to the header. */
        private final boolean inWorld;

        public OptionsScreen(Screen lastScreen, Options options, boolean inWorld) {
                this.lastScreen = lastScreen;
                this.options = options;
                this.inWorld = inWorld;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;

                // ===== Header: FOV slider (+ in-world difficulty button) =====
                int headerY = TITLE_Y + 9 + 8;
                int headerX = centerX - (this.inWorld ? GRID_WIDTH : HEADER_WIDGET_WIDTH) / 2;

                this.addRenderableWidget(new FovSlider(headerX, headerY,
                                HEADER_WIDGET_WIDTH, Button.HEIGHT, this.options));
                if (this.inWorld && this.minecraft != null
                                && this.minecraft.getLevel() instanceof ClientLevel) {
                        this.addRenderableWidget(new DifficultyCycleButton(
                                        headerX + HEADER_WIDGET_WIDTH + 8, headerY,
                                        HEADER_WIDGET_WIDTH, Button.HEIGHT,
                                        (ClientLevel) this.minecraft.getLevel()));
                }

                // ===== 2-column grid of option sub-screens =====
                int gridX = centerX - GRID_WIDTH / 2;
                int rowY = headerY + Button.HEIGHT + 17;
                for (int i = 0; i < GRID_LEFT.length; i++) {
                        int y = rowY + i * (Button.HEIGHT + 4);
                        this.addGridButton(gridX, y, GRID_LEFT[i]);
                        this.addGridButton(gridX + HEADER_WIDGET_WIDTH + 8, y,
                                        GRID_RIGHT[i]);
                }

                // ===== Footer: Done (200px, centered) =====
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - FOOTER_HEIGHT + 6, 200, Button.HEIGHT,
                                "Done", button -> this.onClose()));
        }

        /**
         * One grid cell. The live sub-screens (Accessibility owns the
         * blur slider, Controls the key binds + toggles, Sound Settings
         * the volume sliders) open their screens; the remaining option
         * systems are not backed by the browser port yet and render
         * disabled, exactly like vanilla's inactive style.
         */
        private void addGridButton(int x, int y, String label) {
                boolean live = "Accessibility...".equals(label)
                                || "Controls...".equals(label)
                                || "Sound Settings...".equals(label);
                Button button = new Button(x, y, HEADER_WIDGET_WIDTH, Button.HEIGHT, label,
                                b -> {
                                        if (this.minecraft == null) {
                                                return;
                                        }
                                        if ("Accessibility...".equals(label)) {
                                                this.minecraft.setScreen(new AccessibilityOptionsScreen(
                                                                this, this.options));
                                        } else if ("Controls...".equals(label)) {
                                                this.minecraft.setScreen(new ControlsScreen(
                                                                this, this.options));
                                        } else if ("Sound Settings...".equals(label)) {
                                                this.minecraft.setScreen(new SoundOptionsScreen(
                                                                this, this.options));
                                        }
                                });
                button.setActive(live);
                this.addRenderableWidget(button);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // Vanilla extractMenuBackground: alpha-64 menu tiles over the
                // blurred background (panorama out of world / world in it),
                // repeating every 32 GUI px (declared textureWidth 32)
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Options");
                EaglerGuiImpl.drawText("Options", this.width / 2 - titleWidth / 2.0f,
                                TITLE_Y, COLOR_TEXT, true);

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

        /** Vanilla onClose: back to the screen that opened this one. */
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
         * The vanilla FOV option slider: 30..110, "Normal" at 70 and
         * "Quake Pro" at 110 (vanilla OptionInstance.FOV labels).
         */
        static class FovSlider extends AbstractSliderButton {

                private static final int MIN_FOV = 30;
                private static final int MAX_FOV = 110;
                private static final int NORMAL_FOV = 70;

                private final Options options;

                FovSlider(int x, int y, int width, int height, Options options) {
                        super(x, y, width, height, toNormalized(options.getFov()));
                        this.options = options;
                }

                private static double toNormalized(int fov) {
                        return (double) (fov - MIN_FOV) / (double) (MAX_FOV - MIN_FOV);
                }

                private static String fovLabel(int fov) {
                        if (fov == NORMAL_FOV) return "Normal";
                        if (fov == MAX_FOV) return "Quake Pro";
                        return String.valueOf(fov);
                }

                @Override
                protected String displayString() {
                        return "FOV: " + fovLabel(this.options.getFov());
                }

                @Override
                public String getTelemetryId() {
                        return "fovslider";
                }

                @Override
                protected void applyValue() {
                        int fov = MIN_FOV + (int) Math.round(
                                        this.value * (double) (MAX_FOV - MIN_FOV));
                        this.options.setFov(fov);
                        this.options.save();
                }
        }

        /**
         * The vanilla in-world difficulty CycleButton (peaceful / easy /
         * normal / hard), stored on the loaded level like vanilla.
         */
        static class DifficultyCycleButton extends AbstractWidget {

                private static final String[] NAMES = {
                                "Difficulty: Peaceful", "Difficulty: Easy",
                                "Difficulty: Normal", "Difficulty: Hard"
                };

                private final ClientLevel level;

                DifficultyCycleButton(int x, int y, int width, int height, ClientLevel level) {
                        super(x, y, width, height);
                        this.level = level;
                }

                @Override
                public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                        if (!visible) return;
                        this.hovered = active && isInside(mouseX, mouseY);
                        String sprite = !active ? "widget/button_disabled"
                                        : (hovered ? "widget/button_highlighted" : "widget/button");
                        if (!EaglerGuiImpl.drawSprite9(sprite, x, y, width, height,
                                        Button.SPRITE_BORDER)) {
                                EaglerGuiImpl.fillRect(x, y, width, height,
                                                hovered ? 0xFF8291B8 : 0xFF6F6F6F);
                        }
                        String label = NAMES[this.level.getDifficulty() & 3];
                        int textWidth = EaglerGuiImpl.widthOf(label);
                        EaglerGuiImpl.drawText(label,
                                        x + (width - textWidth) / 2.0f,
                                        y + (height - 8) / 2.0f,
                                        active ? 0xFFFFFFFF : 0xFFA0A0A0, true);
                }

                @Override
                protected void onClick() {
                        this.level.setDifficulty(
                                        (this.level.getDifficulty() + 1) % NAMES.length);
                }

                @Override
                public String getTelemetryId() {
                        return "difficultycycle";
                }
        }
}
