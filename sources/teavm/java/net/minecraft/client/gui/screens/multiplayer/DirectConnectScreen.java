package net.minecraft.client.gui.screens.multiplayer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code DirectConnectScreen} - the "Direct Connection" dialog.
 *
 * <p>Vanilla layout: the "Direct Connection" title, the
 * "Server Address" EditBox (310px) centered, and the
 * {@code [Join Server][Cancel]} footer pair; Join is disabled while
 * the address is blank. Joining hands the address to
 * {@link ConnectScreen} as an ad-hoc ServerData ("Minecraft Server").</p>
 */
public class DirectConnectScreen extends Screen {

        private static final int TITLE_Y = 30;

        /** Vanilla DirectConnectScreen box width (310 GUI px). */
        private static final int BOX_WIDTH = 310;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_LABEL = 0xFFA0A0A0;

        protected final Screen lastScreen;

        private EditBox addressBox;
        private Button joinButton;

        public DirectConnectScreen(Screen lastScreen) {
                this.lastScreen = lastScreen;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int boxX = centerX - BOX_WIDTH / 2;

                this.addressBox = new EditBox(boxX, TITLE_Y + 34, BOX_WIDTH, 18, "") {
                        @Override
                        public String getTelemetryId() {
                                return "directaddress";
                        }
                };
                this.addressBox.setFocused(true);
                this.addRenderableWidget(this.addressBox);

                int footerY = this.height - 60;
                this.joinButton = new Button(centerX - 102, footerY, 100, Button.HEIGHT,
                                "Join Server", b -> this.join());
                this.addRenderableWidget(this.joinButton);
                this.addRenderableWidget(new Button(centerX + 2, footerY, 100,
                                Button.HEIGHT, "Cancel", b -> this.onClose()));
                this.syncJoinEnabled();
        }

        private void syncJoinEnabled() {
                if (this.joinButton != null && this.addressBox != null) {
                        this.joinButton.setActive(!this.addressBox.getValue().trim().isEmpty());
                }
        }

        private void join() {
                String address = this.addressBox.getValue().trim();
                if (address.isEmpty() || this.minecraft == null) {
                        return;
                }
                this.minecraft.setScreen(new ConnectScreen(this.lastScreen,
                                new net.minecraft.client.multiplayer.ServerList.ServerData(
                                                "Minecraft Server", address)));
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Direct Connection");
                EaglerGuiImpl.drawText("Direct Connection",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                int boxX = this.width / 2 - BOX_WIDTH / 2;
                EaglerGuiImpl.drawText("Server Address", boxX, TITLE_Y + 22,
                                COLOR_LABEL, true);

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.addressBox != null) {
                        this.addressBox.setFocused(this.addressBox.isInside(mouseX, mouseY));
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
                if (this.addressBox != null && this.addressBox.isFocused()
                                && this.addressBox.charTyped(codePoint)) {
                        return true;
                }
                return super.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == 1) {
                        this.onClose();
                        return true;
                }
                if (keyCode == net.minecraft.client.KeyMapping.SCAN_ENTER
                                && this.joinButton != null && this.joinButton.isActive()) {
                        // Enter joins, like vanilla (scan-code routing: 28)
                        this.join();
                        return true;
                }
                if (this.addressBox != null && this.addressBox.isFocused()
                                && this.addressBox.keyPressed(keyCode)) {
                        this.syncJoinEnabled();
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
}
