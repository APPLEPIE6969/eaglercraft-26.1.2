package net.minecraft.client.gui.screens.multiplayer;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code DisconnectedScreen} - shown when a connection attempt or
 * session ends.
 *
 * <p>Vanilla layout: the "Disconnected" title (or the caller's title),
 * the reason text word-wrapped and centered, and a single 200px
 * {@code [Back]} footer button. {@code toMenu} (joining from the
 * server list) returns there; {@code toTitle} returns to the title
 * screen.</p>
 */
public class DisconnectedScreen extends Screen {

        private static final int TITLE_Y = 30;

        private static final int COLOR_TITLE = 0xFFFFFFFF;
        private static final int COLOR_REASON = 0xFFD0D0D0;

        protected final Screen lastScreen;
        protected final String title;
        protected final String reason;

        public DisconnectedScreen(String title, String reason, Screen lastScreen) {
                this.title = title != null ? title : "Disconnected";
                this.reason = reason != null ? reason : "";
                this.lastScreen = lastScreen;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                // Vanilla footer: one 200px Back button (toMenu / toTitle)
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - 60, 200, Button.HEIGHT,
                                "Back", b -> this.onClose()));
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf(this.title);
                EaglerGuiImpl.drawText(this.title,
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TITLE, true);

                int reasonY = TITLE_Y + 24;
                List<String> lines = EaglerGuiImpl.wrapText(this.reason,
                                this.width - 80);
                for (int i = 0; i < lines.size() && reasonY < this.height - 80; i++) {
                        String line = lines.get(i);
                        int lineWidth = EaglerGuiImpl.widthOf(line);
                        EaglerGuiImpl.drawText(line,
                                        this.width / 2 - lineWidth / 2.0f, reasonY,
                                        COLOR_REASON, true);
                        reasonY += 10;
                }

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

        /** Vanilla DisconnectedScreen.onClose: toMenu falls back toTitle. */
        @Override
        public void onClose() {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(this.lastScreen != null
                                        ? this.lastScreen : new TitleScreen());
                }
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
