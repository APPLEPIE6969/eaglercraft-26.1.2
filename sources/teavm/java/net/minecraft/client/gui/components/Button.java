package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code Button}.
 *
 * <p>Draws a vanilla-style beveled gray panel with a centered label from
 * the 5x7 bitmap font, and fires {@link OnPress} when left-clicked.
 * Rendering goes through {@link EaglerGuiImpl}'s batched quad pipeline.</p>
 *
 * <p>Geometry and render states mirror the decompiled 26.1.2 widget
 * (see docs/UI-FIDELITY.md): width constants {@code SMALL_WIDTH=120},
 * {@code DEFAULT_WIDTH=150}, {@code BIG_WIDTH=200}, height 20; the label
 * is white while active and {@code 0xFFA0A0A0} while disabled; hover is
 * expressed by the body highlight (the modern highlighted sprite), never
 * by a yellow label. Vanilla blits three nine-slice sprites
 * (widget/button, widget/button_highlighted, widget/button_disabled);
 * until the PNG pipeline exists this class approximates them with
 * solid quads.</p>
 */
public class Button extends AbstractWidget {

        /** Vanilla small button width (GUI pixels). */
        public static final int SMALL_WIDTH = 120;

        /** Vanilla default button width (GUI pixels). */
        public static final int DEFAULT_WIDTH = 150;

        /** Vanilla large button width (GUI pixels). */
        public static final int BIG_WIDTH = 200;

        /** Vanilla button height (GUI pixels). */
        public static final int HEIGHT = 20;

        /** Vanilla default spacing between stacked widgets. */
        public static final int DEFAULT_SPACING = 8;

        /** Press callback, mirroring MC's Button.OnPress. */
        public interface OnPress {
                void onPress(Button button);
        }

        private final String message;
        private final OnPress onPress;

        private static final int COLOR_BODY = 0xFF6F6F6F;
        private static final int COLOR_BODY_HOVER = 0xFF8291B8;
        private static final int COLOR_BODY_DISABLED = 0xFF4A4A4A;
        private static final int COLOR_BORDER = 0xFF000000;
        private static final int COLOR_BEVEL_LIGHT = 0xFFA8A8A8;
        private static final int COLOR_BEVEL_DARK = 0xFF565656;
        private static final int COLOR_BEVEL_DISABLED = 0xFF383838;
        /** Active label: vanilla renders the button text white (26.1.2). */
        private static final int COLOR_TEXT = 0xFFFFFFFF;
        /** Disabled label: vanilla AbstractWidget uses -6250336 (0xFFA0A0A0). */
        private static final int COLOR_TEXT_DISABLED = 0xFFA0A0A0;

        public Button(int x, int y, int width, int height, String message, OnPress onPress) {
                super(x, y, width, height);
                this.message = message != null ? message : "";
                this.onPress = onPress;
        }

        /**
         * Convenience factory for a vanilla-sized button.
         */
        public static Button builder(String message, OnPress onPress) {
                return new Button(0, 0, BIG_WIDTH, HEIGHT, message, onPress);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                if (!visible) return;
                this.hovered = active && isInside(mouseX, mouseY);

                if (active) {
                        // 1px black outline around the whole button
                        EaglerGuiImpl.fillRect(x, y, width, height, COLOR_BORDER);
                        // Body: highlighted tint on hover (vanilla widget/button_highlighted)
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                                hovered ? COLOR_BODY_HOVER : COLOR_BODY);
                        // Bevel: light top + left, dark bottom + right
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, 1,
                                hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, 1, height - 2,
                                hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                        EaglerGuiImpl.fillRect(x + 1, y + height - 2, width - 2, 1, COLOR_BEVEL_LIGHT);
                        EaglerGuiImpl.fillRect(x + width - 2, y + 1, 1, height - 2, COLOR_BEVEL_LIGHT);
                } else {
                        // Disabled: vanilla widget/button_disabled - darkened panel,
                        // grey label, no hover highlight.
                        EaglerGuiImpl.fillRect(x, y, width, height, COLOR_BORDER);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2, COLOR_BODY_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, 1, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, 1, height - 2, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + height - 2, width - 2, 1, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + width - 2, y + 1, 1, height - 2, COLOR_BEVEL_DISABLED);
                }

                // Centered label with shadow (vanilla centers vertically in the 20px cell)
                int textWidth = EaglerGuiImpl.widthOf(message);
                float tx = x + (width - textWidth) / 2.0f;
                float ty = y + (height - EaglerFont.GLYPH_HEIGHT) / 2.0f;
                EaglerGuiImpl.drawText(message, tx, ty,
                        active ? COLOR_TEXT : COLOR_TEXT_DISABLED, true);
        }

        @Override
        protected void onClick() {
                if (onPress != null) {
                        onPress.onPress(this);
                }
        }

        /**
         * Returns the button's display text.
         */
        public String getMessage() {
                return message;
        }
}
