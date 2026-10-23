package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code Button}.
 *
 * <p>Blits the real Mojang sprite (the same PNG the official 26.1.2
 * client ships) with the vanilla nine-slice scaling the sprite metadata
 * declares ({@code border: 3}), and fires {@link OnPress} when
 * left-clicked. Rendering goes through {@link EaglerGuiImpl}'s batched
 * textured-quad pipeline.</p>
 *
 * <p>Geometry and render states mirror the decompiled 26.1.2 widget
 * (see docs/UI-FIDELITY.md): width constants {@code SMALL_WIDTH=120},
 * {@code DEFAULT_WIDTH=150}, {@code BIG_WIDTH=200}, height 20; three
 * sprites - widget/button (active), widget/button_highlighted (hover),
 * widget/button_disabled - selected exactly like vanilla
 * {@code WidgetSprites.get(active, hovered)}; the label is white while
 * active and {@code 0xFFA0A0A0} while disabled; hover changes the body
 * sprite only, never the label color. Until the sprite PNGs arrive
 * (they download asynchronously) the procedural solid-quad look is the
 * fallback.</p>
 */
public class Button extends AbstractWidget {

        /** Vanilla small button width (GUI pixels). */
        public static final int SMALL_WIDTH = 120;

        /** Vanilla default button width (GUI pixels). */
        public static final int DEFAULT_WIDTH = 150;

        /** Vanilla big button width (GUI pixels). */
        public static final int BIG_WIDTH = 200;

        /** Vanilla button height (GUI pixels). */
        public static final int HEIGHT = 20;

        /** Vanilla default spacing between stacked widgets. */
        public static final int DEFAULT_SPACING = 8;

        /**
         * Nine-slice border of the widget/button sprites, straight from
         * their .png.mcmeta: {@code {"type":"nine_slice", "width":200,
         * "height":20, "border":3}}.
         */
        public static final int SPRITE_BORDER = 3;

        /** Press callback, mirroring MC's Button.OnPress. */
        public interface OnPress {
                void onPress(Button button);
        }

        /** Mutable so CycleButton can rewrite the label when cycling. */
        protected String message;
        private final OnPress onPress;

        /**
         * Optional icon sprite (e.g. "icon/language") drawn centered over
         * the panel, the way vanilla's SpriteIconButton does.
         */
        private String iconSprite = null;

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

        /**
         * Sets a centered icon sprite drawn over the panel (vanilla
         * SpriteIconButton / CommonButtons.language behavior).
         */
        public Button setIcon(String spriteName) {
                this.iconSprite = spriteName;
                return this;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                if (!visible) return;
                this.hovered = active && isInside(mouseX, mouseY);

                // Vanilla sprite selection: WidgetSprites.get(active, hovered)
                String sprite = !active ? "widget/button_disabled"
                        : (hovered ? "widget/button_highlighted" : "widget/button");
                boolean spriteDrawn = EaglerGuiImpl.drawSprite9(sprite, x, y, width, height, SPRITE_BORDER);

                if (!spriteDrawn) {
                        // Fallback: procedural bevel approximation of the sprite
                        renderProceduralPanel();
                }

                if (iconSprite != null) {
                        // Centered 1:1 blit (vanilla CenteredIcon: (w - iw) / 2)
                        int iw = Math.min(15, width);
                        int ih = Math.min(15, height);
                        EaglerGuiImpl.drawSprite(iconSprite,
                                x + (width - iw) / 2.0f, y + (height - ih) / 2.0f, iw, ih);
                }

                if (message.isEmpty()) {
                        return;
                }
                // Centered label with shadow (vanilla centers vertically in the 20px cell)
                int textWidth = EaglerGuiImpl.widthOf(message);
                float tx = x + (width - textWidth) / 2.0f;
                float ty = y + (height - EaglerFont.GLYPH_HEIGHT) / 2.0f;
                EaglerGuiImpl.drawText(message, tx, ty,
                        active ? COLOR_TEXT : COLOR_TEXT_DISABLED, true);
        }

        /**
         * The pre-sprite-pipeline bevel approximation, kept as the
         * fallback for the frames before the PNGs finish loading.
         */
        private void renderProceduralPanel() {
                if (active) {
                        EaglerGuiImpl.fillRect(x, y, width, height, COLOR_BORDER);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                                hovered ? COLOR_BODY_HOVER : COLOR_BODY);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, 1,
                                hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, 1, height - 2,
                                hovered ? COLOR_BEVEL_LIGHT : COLOR_BEVEL_DARK);
                        EaglerGuiImpl.fillRect(x + 1, y + height - 2, width - 2, 1, COLOR_BEVEL_LIGHT);
                        EaglerGuiImpl.fillRect(x + width - 2, y + 1, 1, height - 2, COLOR_BEVEL_LIGHT);
                } else {
                        EaglerGuiImpl.fillRect(x, y, width, height, COLOR_BORDER);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2, COLOR_BODY_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, 1, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, 1, height - 2, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + 1, y + height - 2, width - 2, 1, COLOR_BEVEL_DISABLED);
                        EaglerGuiImpl.fillRect(x + width - 2, y + 1, 1, height - 2, COLOR_BEVEL_DISABLED);
                }
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

        /**
         * The bridge id, derived from the message exactly like the
         * telemetry serializer derives it ("Play Selected World" ->
         * "playselectedworld").
         */
        @Override
        public String getTelemetryId() {
                return message == null ? null
                        : message.toLowerCase().replaceAll("[^a-z0-9]+", "");
        }
}
