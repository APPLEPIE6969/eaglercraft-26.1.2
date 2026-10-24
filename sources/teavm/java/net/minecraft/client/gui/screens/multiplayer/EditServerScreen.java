package net.minecraft.client.gui.screens.multiplayer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerList;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code EditServerScreen} (vanilla's Add/Edit Server dialog).
 *
 * <p>Vanilla layout: "Edit Server Info" / "Add Server" title, the
 * "Server Name" and "Server Address" EditBox rows (310px, label above
 * the box), then the {@code [Done][Cancel]} footer pair. Done applies
 * the vanilla validation (name defaults to "Minecraft Server" when
 * blank; an empty address disables Done) and persists through
 * {@link ServerList#add}/{@link ServerList#replace}.</p>
 */
public class EditServerScreen extends Screen {

        private static final int TITLE_Y = 30;

        /** Vanilla EditServerScreen box width (240 GUI px). */
        private static final int BOX_WIDTH = 240;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_LABEL = 0xFFA0A0A0;

        protected final Screen lastScreen;
        /** The entry being edited (null = add mode). */
        private final ServerList.ServerData editing;
        private final int editIndex;

        private EditBox nameBox;
        private EditBox addressBox;
        private Button doneButton;

        public EditServerScreen(Screen lastScreen, ServerList.ServerData editing,
                        int editIndex) {
                this.lastScreen = lastScreen;
                this.editing = editing;
                this.editIndex = editIndex;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int boxX = centerX - BOX_WIDTH / 2;

                String name = this.editing != null ? this.editing.name : "";
                String address = this.editing != null ? this.editing.ip : "";

                this.nameBox = new EditBox(boxX, TITLE_Y + 34, BOX_WIDTH, 18, name) {
                        @Override
                        public String getTelemetryId() {
                                return "servername";
                        }
                };
                this.nameBox.setValue(name);
                this.addressBox = new EditBox(boxX, TITLE_Y + 74, BOX_WIDTH, 18, address) {
                        @Override
                        public String getTelemetryId() {
                                return "serveraddress";
                        }
                };
                this.addressBox.setValue(address);
                this.addRenderableWidget(this.nameBox);
                this.addRenderableWidget(this.addressBox);

                int footerY = this.height - 60;
                this.doneButton = new Button(centerX - 102, footerY, 100, Button.HEIGHT,
                                this.editing != null ? "Done" : "Done",
                                b -> this.apply());
                this.addRenderableWidget(this.doneButton);
                this.addRenderableWidget(new Button(centerX + 2, footerY, 100,
                                Button.HEIGHT, "Cancel", b -> this.onClose()));

                this.syncDoneEnabled();
        }

        /** Vanilla: Done is disabled while the address is blank. */
        private void syncDoneEnabled() {
                if (this.doneButton != null && this.addressBox != null) {
                        this.doneButton.setActive(!this.addressBox.getValue().trim().isEmpty());
                }
        }

        private void apply() {
                String name = this.nameBox.getValue().trim();
                if (name.isEmpty()) {
                        // Vanilla EditServerScreen default name
                        name = "Minecraft Server";
                }
                String address = this.addressBox.getValue().trim();
                ServerList list = ServerList.getOrLoad();
                if (this.editIndex >= 0) {
                        list.replace(this.editIndex, new ServerList.ServerData(name, address));
                } else {
                        list.add(new ServerList.ServerData(name, address));
                }
                this.onClose();
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                String title = this.editing != null ? "Edit Server Info" : "Add Server";
                int titleWidth = EaglerGuiImpl.widthOf(title);
                EaglerGuiImpl.drawText(title,
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                int boxX = this.width / 2 - BOX_WIDTH / 2;
                EaglerGuiImpl.drawText("Server Name", boxX, TITLE_Y + 22,
                                COLOR_LABEL, true);
                EaglerGuiImpl.drawText("Server Address", boxX, TITLE_Y + 62,
                                COLOR_LABEL, true);

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.nameBox != null) {
                        this.nameBox.setFocused(
                                        this.nameBox.isInside(mouseX, mouseY));
                }
                if (this.addressBox != null) {
                        this.addressBox.setFocused(
                                        this.addressBox.isInside(mouseX, mouseY));
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
                boolean handled = false;
                if (this.nameBox != null && this.nameBox.isFocused()
                                && this.nameBox.charTyped(codePoint)) {
                        handled = true;
                }
                if (this.addressBox != null && this.addressBox.isFocused()
                                && this.addressBox.charTyped(codePoint)) {
                        handled = true;
                }
                this.syncDoneEnabled();
                return handled || super.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == 1) {
                        this.onClose();
                        return true;
                }
                // Tab flips the focus like vanilla's changeFocus
                if (keyCode == 15 && this.nameBox != null && this.addressBox != null) {
                        boolean next = !this.nameBox.isFocused();
                        this.nameBox.setFocused(next);
                        this.addressBox.setFocused(!next);
                        return true;
                }
                EditBox focused = this.nameBox != null && this.nameBox.isFocused()
                                ? this.nameBox
                                : this.addressBox != null && this.addressBox.isFocused()
                                                ? this.addressBox : null;
                if (focused != null && focused.keyPressed(keyCode)) {
                        this.syncDoneEnabled();
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
