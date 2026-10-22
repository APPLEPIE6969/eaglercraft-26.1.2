package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;

/**
 * EaglerCraft 26.1.2 browser override for the classic MC {@code Button}.
 *
 * <p>Draws a vanilla-style beveled gray panel with a centered label from
 * the 5x7 bitmap font, and fires {@link OnPress} when left-clicked.
 * Rendering goes through {@link EaglerGuiImpl}'s batched quad pipeline.</p>
 */
public class Button extends AbstractWidget {

        /** Vanilla button width (GUI pixels). */
        public static final int SMALL_WIDTH = 150;

        /** Vanilla large button width (GUI pixels). */
        public static final int BIG_WIDTH = 200;

        /** Vanilla button height (GUI pixels). */
        public static final int HEIGHT = 20;

        /** Press callback, mirroring MC's Button.OnPress. */
        public interface OnPress {
                void onPress(Button button);
        }

        private final String message;
        private final OnPress onPress;

        private static final int COLOR_BODY = 0xFF6F6F6F;
        private static final int COLOR_BODY_HOVER = 0xFF8291B8;
        private static final int COLOR_BORDER = 0xFF000000;
        private static final int COLOR_BEVEL_LIGHT = 0xFFA8A8A8;
        private static final int COLOR_BEVEL_DARK = 0xFF565656;
        private static final int COLOR_TEXT = 0xFFE0E0E0;
        private static final int COLOR_TEXT_HOVER = 0xFFFFFFA0;

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

                // 1px black outline around the whole button
                EaglerGuiImpl.fillRect(x, y, width, height, COLOR_BORDER);
                // Body
                EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                        hovered ? COLOR_BODY_HOVER : COLOR_BODY);
                // Bevel: light top + left, dark bottom + right
                EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, 1,
                        hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                EaglerGuiImpl.fillRect(x + 1, y + 1, 1, height - 2,
                        hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                EaglerGuiImpl.fillRect(x + 1, y + height - 2, width - 2, 1, COLOR_BEVEL_LIGHT);
                EaglerGuiImpl.fillRect(x + width - 2, y + 1, 1, height - 2, COLOR_BEVEL_LIGHT);

                // Centered label with shadow (vanilla centers vertically in the 20px cell)
                int textWidth = EaglerGuiImpl.widthOf(message);
                float tx = x + (width - textWidth) / 2.0f;
                float ty = y + (height - EaglerFont.GLYPH_HEIGHT) / 2.0f;
                EaglerGuiImpl.drawText(message, tx, ty,
                        hovered ? COLOR_TEXT_HOVER : COLOR_TEXT, true);
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
