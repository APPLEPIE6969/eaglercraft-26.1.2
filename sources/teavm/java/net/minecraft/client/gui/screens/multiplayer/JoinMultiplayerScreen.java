package net.minecraft.client.gui.screens.multiplayer;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerList;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code JoinMultiplayerScreen} - the server browser.
 *
 * <p>Layout from the decompiled 26.1.2 screen: the "Play Multiplayer"
 * title, the server list (36px rows: name, address, selection
 * outline), and the vanilla two-stage footer - the primary row shows
 * {@code [Join Server][Direct Connection][Add Server]} (100px each)
 * and the secondary row {@code [Edit][Delete][Refresh][Cancel]}
 * (74px each). The empty list renders the vanilla "Add Server" hint
 * row. Delete routes through the vanilla {@code ConfirmScreen}.</p>
 *
 * <p>Joining hands the entry's address to {@link ConnectScreen}; the
 * list persists through {@link ServerList} (localStorage) with the
 * vanilla add/edit/delete/swap semantics.</p>
 */
public class JoinMultiplayerScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla server list row height (36px). */
        private static final int ROW_HEIGHT = 36;

        /** Vanilla footer button geometry. */
        private static final int FOOTER_SMALL = 74;
        private static final int FOOTER_WIDE = 100;

        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_ADDRESS = 0xFF808080;
        private static final int COLOR_HINT = 0xFFA0A0A0;
        private static final int COLOR_ROW = 0xFFC0C0C0;
        private static final int COLOR_SELECTED = 0xFFFFFFFF;
        private static final int COLOR_HOVER = 0x808080A0;
        private static final int COLOR_SELECTED_ROW = 0x80303060;

        protected final Screen lastScreen;

        /** The shared, persisted server list (vanilla ServerList). */
        private final ServerList serverList = ServerList.getOrLoad();

        /** Vanilla selected slot (-1 = none). */
        private int selected = -1;

        /** Hovered row for the highlight (recomputed per frame). */
        private int hoveredRow = -1;

        /** Scroll offset in pixels (vanilla ServerSelectionList.scrollAmount). */
        private int listScroll;

        private int listTop;
        private int listBottom;

        /** The pending delete target (consumed by the Confirm callback). */
        private int pendingDelete = -1;

        public JoinMultiplayerScreen(Screen lastScreen) {
                this.lastScreen = lastScreen;
        }

        @Override
        protected void init() {
                this.listTop = TITLE_Y + 9 + 15;
                this.listBottom = this.height - FOOTER_HEIGHT * 2 - 4;
                if (this.selected >= this.serverList.size()) {
                        this.selected = this.serverList.size() - 1;
                }

                int centerX = this.width / 2;
                int footer2Y = this.height - FOOTER_HEIGHT + 6;
                int footer1Y = footer2Y - Button.HEIGHT - 4;

                // ===== Primary footer: Join / Direct / Add (100px each) =====
                this.addRenderableWidget(new Button(centerX - FOOTER_WIDE * 3 / 2 - 10,
                                footer1Y, FOOTER_WIDE, Button.HEIGHT,
                                "Join Server", b -> this.joinSelected()));
                this.addRenderableWidget(new Button(centerX - FOOTER_WIDE / 2,
                                footer1Y, FOOTER_WIDE, Button.HEIGHT,
                                "Direct Connection", b -> {
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new DirectConnectScreen(this));
                                        }
                                }));
                this.addRenderableWidget(new Button(centerX + FOOTER_WIDE / 2 + 10,
                                footer1Y, FOOTER_WIDE, Button.HEIGHT,
                                "Add Server", b -> {
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new EditServerScreen(
                                                                this, null, -1));
                                        }
                                }));

                // ===== Secondary footer: Edit / Delete / Refresh / Cancel =====
                int row2X = centerX - (FOOTER_SMALL * 4 + 10 * 3) / 2;
                Button edit = new Button(row2X, footer2Y, FOOTER_SMALL, Button.HEIGHT,
                                "Edit", b -> {
                                        if (this.selected >= 0) {
                                                ServerList.ServerData data = this.serverList.get(this.selected);
                                                this.minecraft.setScreen(new EditServerScreen(
                                                                this, data, this.selected));
                                        }
                                });
                edit.setActive(this.selected >= 0);
                this.addRenderableWidget(edit);

                Button delete = new Button(row2X + FOOTER_SMALL + 10, footer2Y,
                                FOOTER_SMALL, Button.HEIGHT,
                                "Delete", b -> this.confirmDelete());
                delete.setActive(this.selected >= 0);
                this.addRenderableWidget(delete);

                this.addRenderableWidget(new Button(row2X + (FOOTER_SMALL + 10) * 2,
                                footer2Y, FOOTER_SMALL, Button.HEIGHT,
                                "Refresh", b -> {
                                        ServerList.reload();
                                        this.refreshFooterButtons();
                                }));

                this.addRenderableWidget(new Button(row2X + (FOOTER_SMALL + 10) * 3,
                                footer2Y, FOOTER_SMALL, Button.HEIGHT,
                                "Cancel", b -> this.onClose()));
        }

        private void joinSelected() {
                if (this.selected < 0 || this.minecraft == null) {
                        return;
                }
                ServerList.ServerData data = this.serverList.get(this.selected);
                if (data != null && !data.ip.isEmpty()) {
                        this.minecraft.setScreen(new ConnectScreen(this, data));
                }
        }

        private void confirmDelete() {
                if (this.selected < 0 || this.minecraft == null) {
                        return;
                }
                ServerList.ServerData data = this.serverList.get(this.selected);
                if (data == null) {
                        return;
                }
                this.pendingDelete = this.selected;
                this.minecraft.setScreen(new ConfirmScreen(this,
                                "Are you sure you want to delete this server?",
                                "\"" + data.name + "\" will be lost forever! (A long time!)",
                                accepted -> {
                                        if (accepted && this.pendingDelete >= 0) {
                                                this.serverList.remove(this.pendingDelete);
                                                this.selected = -1;
                                        }
                                        this.pendingDelete = -1;
                                }));
        }

        /** Re-syncs the Edit/Delete enabled state with the selection
         * (vanilla rebuilds the footer widgets on selection change). */
        private void refreshFooterButtons() {
                if (this.minecraft == null) {
                        return;
                }
                this.init(this.minecraft, this.width, this.height);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Play Multiplayer");
                EaglerGuiImpl.drawText("Play Multiplayer",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                int rowX = Math.max(10, this.width / 2 - 245);
                int rowW = Math.min(480, this.width - 20);

                this.hoveredRow = -1;
                if (this.serverList.size() == 0) {
                        // Vanilla empty-list hint row
                        String hint = "No servers added - use \"Add Server\" below";
                        int hintWidth = EaglerGuiImpl.widthOf(hint);
                        EaglerGuiImpl.drawText(hint,
                                        this.width / 2 - hintWidth / 2.0f,
                                        this.listTop + 14, COLOR_HINT, true);
                } else {
                        for (int i = 0; i < this.serverList.size(); i++) {
                                int screenY = this.listTop + i * ROW_HEIGHT - this.listScroll;
                                if (screenY + ROW_HEIGHT < this.listTop
                                                || screenY > this.listBottom) {
                                        continue;
                                }
                                boolean hovered = mouseX >= rowX && mouseX < rowX + rowW
                                                && mouseY >= screenY
                                                && mouseY < screenY + ROW_HEIGHT
                                                && mouseY < this.listBottom;
                                if (hovered) {
                                        this.hoveredRow = i;
                                }
                                boolean isSelected = i == this.selected;
                                if (isSelected) {
                                        EaglerGuiImpl.fillRect(rowX, screenY, rowW, ROW_HEIGHT,
                                                        COLOR_SELECTED_ROW);
                                } else if (hovered) {
                                        EaglerGuiImpl.fillRect(rowX, screenY, rowW, ROW_HEIGHT,
                                                        COLOR_HOVER);
                                }
                                ServerList.ServerData data = this.serverList.get(i);
                                EaglerGuiImpl.drawText(data.name, rowX + 4,
                                                screenY + 6,
                                                isSelected ? COLOR_SELECTED : COLOR_ROW, true);
                                EaglerGuiImpl.drawText(data.ip, rowX + 4,
                                                screenY + 6 + 10, COLOR_ADDRESS, true);
                                if (isSelected) {
                                        // Vanilla selection outline
                                        outline(rowX, screenY, rowW, ROW_HEIGHT);
                                }
                        }
                }

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        /** Vanilla's 1px selection outline. */
        private void outline(int x, int y, int w, int h) {
                EaglerGuiImpl.fillRect(x, y, w, 1, 0xFFFFFFFF);
                EaglerGuiImpl.fillRect(x, y + h - 1, w, 1, 0xFFFFFFFF);
                EaglerGuiImpl.fillRect(x, y, 1, h, 0xFFFFFFFF);
                EaglerGuiImpl.fillRect(x + w - 1, y, 1, h, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && this.hoveredRow >= 0) {
                        if (this.selected == this.hoveredRow) {
                                // Vanilla: clicking the selected row joins
                                this.joinSelected();
                        } else {
                                this.selected = this.hoveredRow;
                                this.refreshFooterButtons();
                        }
                        return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                int contentHeight = this.serverList.size() * ROW_HEIGHT;
                int listHeight = this.listBottom - this.listTop;
                if (contentHeight > listHeight) {
                        this.listScroll = Math.max(0, Math.min(contentHeight - listHeight,
                                        this.listScroll - (int) (delta * ROW_HEIGHT)));
                }
                return true;
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
                        this.minecraft.setScreen(this.lastScreen != null
                                        ? this.lastScreen : new TitleScreen());
                }
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }
}
