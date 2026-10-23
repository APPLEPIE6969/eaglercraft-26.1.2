package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code AbstractSliderButton}: the vanilla slider widget with the real
 * Mojang sprites - {@code widget/slider} track (nine-slice 200x20,
 * border 1) with {@code widget/slider_handle} (8x20, border 2/2/2/3)
 * sliding across it, and the centered label the subclass formats.
 *
 * <p>Interaction mirrors vanilla: clicking anywhere on the track jumps
 * the handle there, and the handle keeps following the mouse while the
 * button is held (dragging continues outside the widget bounds, exactly
 * like vanilla {@code onDrag}). The normalized value 0..1 maps to the
 * handle x {@code x + round(value * (width - 8))}.</p>
 */
public abstract class AbstractSliderButton extends AbstractWidget {

        /** Nine-slice border of widget/slider (.png.mcmeta: border 1). */
        public static final int TRACK_BORDER = 1;

        /** Vanilla handle metrics: 8 wide, slides over (width - 8). */
        public static final int HANDLE_WIDTH = 8;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_TEXT_DISABLED = 0xFFA0A0A0;

        /** The normalized value 0..1. */
        protected double value;

        /** True while the mouse is held down on this slider. */
        private boolean dragging;

        protected AbstractSliderButton(int x, int y, int width, int height, double value) {
                super(x, y, width, height);
                this.value = value;
        }

        /** The formatted label, e.g. {@code "FOV: Normal"} (vanilla getMessage). */
        protected abstract String displayString();

        /** Called on every value change so subclasses persist the option. */
        protected abstract void applyValue();

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                if (!visible) return;
                this.hovered = active && isInside(mouseX, mouseY);

                // Vanilla WidgetSprites: track sprite swaps on hover
                String track = hovered ? "widget/slider_highlighted" : "widget/slider";
                if (!EaglerGuiImpl.drawSprite9(track, x, y, width, height, TRACK_BORDER)) {
                        EaglerGuiImpl.fillRect(x, y, width, height, 0xFF000000);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                                        hovered ? 0xFF8291B8 : 0xFF6F6F6F);
                }

                // Handle at x + value * (width - 8), vanilla getHandleX
                int handleX = x + (int) (value * (double) (width - HANDLE_WIDTH));
                String handle = hovered ? "widget/slider_handle_highlighted"
                                : "widget/slider_handle";
                // .png.mcmeta: border {left:2, top:2, right:2, bottom:3}
                if (!EaglerGuiImpl.drawSprite9Slices(handle, handleX, y, HANDLE_WIDTH, height,
                                2, 2, 2, 3)) {
                        EaglerGuiImpl.fillRect(handleX, y, HANDLE_WIDTH, height, 0xFF000000);
                        EaglerGuiImpl.fillRect(handleX + 1, y + 1, HANDLE_WIDTH - 2, height - 2,
                                        0xFFFFFFFF);
                }

                // Centered label, white while active / gray while disabled
                String label = displayString();
                int textWidth = EaglerGuiImpl.widthOf(label);
                float tx = x + (width - textWidth) / 2.0f;
                float ty = y + (height - EaglerFont.GLYPH_HEIGHT) / 2.0f;
                EaglerGuiImpl.drawText(label, tx, ty,
                                active ? COLOR_TEXT : COLOR_TEXT_DISABLED, true);
        }

        /** Vanilla setValueFromMouseX: (mx - x - 4) / (width - 8), clamped. */
        private void setValueFromMouse(double mouseX) {
                double v = (mouseX - (double) (x + 4)) / (double) (width - 8);
                setValue(v);
        }

        /** Vanilla setValue: clamps, stores, refreshes the label, applies. */
        protected void setValue(double value) {
                double clamped = value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
                if (clamped != this.value) {
                        this.value = clamped;
                        this.applyValue();
                }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!visible || !active || button != 0) {
                        return false;
                }
                if (isInside(mouseX, mouseY)) {
                        this.dragging = true;
                        this.setValueFromMouse(mouseX);
                        return true;
                }
                return false;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
                this.dragging = false;
                return visible && active && button == 0 && isInside(mouseX, mouseY);
        }

        @Override
        public void mouseMoved(double mouseX, double mouseY) {
                super.mouseMoved(mouseX, mouseY);
                if (this.dragging) {
                        this.setValueFromMouse(mouseX);
                }
        }

        /**
         * Stable id for the e2e telemetry bridge, like Button's.
         */
        @Override
        public String getTelemetryId() {
                return null;
        }
}
