package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.gui.components.Button;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code ConfirmScreen}
 * - the vanilla yes/no confirmation dialog (the Multiplayer delete
 * flow opens it; vanilla also uses it for link warnings).
 *
 * <p>Vanilla layout: the wrapped title line(s) centered in the upper
 * third, the wrapped body below it, then the {@code [Yes][No]} pair
 * (150px each, 10px gap) in the footer band.</p>
 */
public class ConfirmScreen extends Screen {

        private static final int TITLE_Y = 30;

        private static final int COLOR_TITLE = 0xFFFFFFFF;
        private static final int COLOR_BODY = 0xFFD0D0D0;

        protected final Screen lastScreen;
        protected final String title;
        protected final String body;
        private final ConfirmHandler handler;

        /** Vanilla ConfirmScreen's onConfirm(answer) callback. */
        public interface ConfirmHandler {
                void onConfirm(boolean accepted);
        }

        public ConfirmScreen(Screen lastScreen, String title, String body,
                        ConfirmHandler handler) {
                this.lastScreen = lastScreen;
                this.title = title;
                this.body = body;
                this.handler = handler;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int buttonY = this.height - 60;
                this.addRenderableWidget(new Button(centerX - 155, buttonY, 150,
                                Button.HEIGHT, "Yes", b -> this.answer(true)));
                this.addRenderableWidget(new Button(centerX + 5, buttonY, 150,
                                Button.HEIGHT, "No", b -> this.answer(false)));
        }

        private void answer(boolean accepted) {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(this.lastScreen);
                }
                if (this.handler != null) {
                        this.handler.onConfirm(accepted);
                }
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf(this.title);
                EaglerGuiImpl.drawText(this.title,
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TITLE, true);

                int bodyY = TITLE_Y + 20;
                for (String line : EaglerGuiImpl.wrapText(this.body, this.width - 80)) {
                        int lineWidth = EaglerGuiImpl.widthOf(line);
                        EaglerGuiImpl.drawText(line,
                                        this.width / 2 - lineWidth / 2.0f, bodyY, COLOR_BODY, true);
                        bodyY += 10;
                }

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == 1) {
                        this.answer(false);
                        return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean shouldCloseOnEsc() {
                return false; // ESC routes through answer(false) above
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
