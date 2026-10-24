package net.minecraft.client.gui.screens.options;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code KeyBindsScreen}
 * - the "Key Binds" page opened from Controls.
 *
 * <p>Vanilla layout: a scrollable list grouped by
 * {@link net.minecraft.client.KeyMapping.Category} - a shaded header
 * row per category, then 20px rows with the bind's display name on the
 * left and its {@code > key <} button on the right. Clicking a row
 * enters vanilla's listening state (the button renders
 * {@code > ??? <} in yellow until any key is pressed); rebinds resolve
 * conflicts by unbinding the previous owner ("???"). The footer holds
 * the vanilla {@code [Reset All...][Done]} pair.</p>
 *
 * <p>The list carries every registered keyboard bind plus the mouse
 * operations (attack/use/pick) as disabled rows - the browser gameplay
 * layer reads those straight from the physical buttons, so their
 * "Button N" binding is not editable here (vanilla allows rebinding
 * them; the port's mouse-capture path is fixed).</p>
 */
public class KeyBindsScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla KeyBindsList row height (20px). */
        private static final int ROW_HEIGHT = 20;

        /** The bind button width (vanilla's 50px key widget). */
        private static final int KEY_BUTTON_WIDTH = 50;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;
        private static final int COLOR_CATEGORY = 0xFFFFFF00;
        private static final int COLOR_LISTENING = 0xFFFFFF00;
        private static final int COLOR_ROW = 0xFFB0B0B0;

        protected final Options options;
        protected final Screen lastScreen;

        /** One list row: a category header or a bind entry. */
        private static final class Row {
                final KeyMapping.Category category; // non-null = header
                final KeyMapping binding;           // null for headers
                final String label;
                final boolean mouseBind;

                Row(KeyMapping.Category category, String label) {
                        this.category = category;
                        this.binding = null;
                        this.label = label;
                        this.mouseBind = false;
                }

                Row(KeyMapping binding, String label, boolean mouseBind) {
                        this.category = null;
                        this.binding = binding;
                        this.label = label;
                        this.mouseBind = mouseBind;
                }
        }

        /** The flattened rows (headers + entries), rebuilt per init. */
        private final List<Row> rows = new ArrayList<Row>();

        /** Vanilla listening state: the row waiting for a key press. */
        private KeyMapping listening;

        /** Scroll offset in pixels (vanilla KeyBindsList.scrollAmount). */
        private double scrollAmount;

        private int listTop;
        private int listBottom;

        public KeyBindsScreen(Screen lastScreen, Options options) {
                this.lastScreen = lastScreen;
                this.options = options;
        }

        @Override
        protected void init() {
                this.listTop = TITLE_Y + 9 + 15;
                this.listBottom = this.height - FOOTER_HEIGHT;
                this.rebuildRows();
                this.scrollAmount = 0;
                this.listening = null;

                int centerX = this.width / 2;
                // Vanilla footer: [Reset All...][Done], 150px each
                this.addRenderableWidget(new Button(centerX - 155,
                                this.height - FOOTER_HEIGHT + 6, 150, Button.HEIGHT,
                                "Reset All...", b -> {
                                        KeyMapping.resetAll();
                                        KeyMapping.saveBinds();
                                }));
                this.addRenderableWidget(new Button(centerX + 5,
                                this.height - FOOTER_HEIGHT + 6, 150, Button.HEIGHT,
                                "Done", b -> this.onClose()));
        }

        /**
         * Flattens the bind table into rows: per category (vanilla
         * declaration order) a header, then that category's binds, then
         * the disabled mouse-operation rows under Gameplay.
         */
        private void rebuildRows() {
                this.rows.clear();
                for (KeyMapping.Category category : KeyMapping.Category.SORTED) {
                        boolean any = false;
                        for (KeyMapping mapping : KeyMapping.all()) {
                                if (mapping.category == category) {
                                        if (!any) {
                                                this.rows.add(new Row(category,
                                                                categoryName(category)));
                                                any = true;
                                        }
                                        this.rows.add(new Row(mapping,
                                                        bindName(mapping.name), false));
                                        if (category == KeyMapping.Category.GAMEPLAY) {
                                                this.addMouseRows(category);
                                        }
                                }
                        }
                        if (category == KeyMapping.Category.GAMEPLAY && !any) {
                                this.rows.add(new Row(category, categoryName(category)));
                                this.addMouseRows(category);
                        }
                }
        }

        /** The disabled mouse rows (attack/use/pick). */
        private void addMouseRows(KeyMapping.Category category) {
                this.rows.add(new Row(null, "Attack", true));
                this.rows.add(new Row(null, "Use", true));
                this.rows.add(new Row(null, "Pick Block", true));
        }

        /** Vanilla Category display names. */
        private static String categoryName(KeyMapping.Category category) {
                switch (category) {
                        case MOVEMENT: return "Movement";
                        case GAMEPLAY: return "Gameplay";
                        case INVENTORY: return "Inventory";
                        case INTERFACE: return "Interface";
                        case CREATIVE: return "Creative Mode";
                        case MULTIPLAYER: return "Multiplayer";
                        case MISC: return "Miscellaneous";
                        default: return category.name();
                }
        }

        /** Vanilla bind display names (key.categories / key.bind.*). */
        private static String bindName(String name) {
                switch (name) {
                        case "key.forward": return "Walk Forwards";
                        case "key.left": return "Strafe Left";
                        case "key.back": return "Walk Backwards";
                        case "key.right": return "Strafe Right";
                        case "key.jump": return "Jump";
                        case "key.sneak": return "Sneak";
                        case "key.sprint": return "Sprint";
                        case "key.inventory": return "Open/Close Inventory";
                        case "key.drop": return "Drop Selected Item";
                        case "key.hotbar.1": return "Hotbar Slot 1";
                        case "key.hotbar.2": return "Hotbar Slot 2";
                        case "key.hotbar.3": return "Hotbar Slot 3";
                        case "key.hotbar.4": return "Hotbar Slot 4";
                        case "key.hotbar.5": return "Hotbar Slot 5";
                        case "key.hotbar.6": return "Hotbar Slot 6";
                        case "key.hotbar.7": return "Hotbar Slot 7";
                        case "key.hotbar.8": return "Hotbar Slot 8";
                        case "key.hotbar.9": return "Hotbar Slot 9";
                        case "key.chat": return "Chat";
                        case "key.playerlist": return "Player List";
                        case "key.screenshot": return "Take Screenshot";
                        case "key.togglePerspective": return "Toggle Perspective";
                        case "key.fullscreen": return "Toggle Fullscreen";
                        default: return name;
                }
        }

        /** The "Button N" label of the disabled mouse rows. */
        private static String mouseBindLabel(String label) {
                switch (label) {
                        case "Attack": return "Button 1";
                        case "Use": return "Button 2";
                        case "Pick Block": return "Button 3";
                        default: return label;
                }
        }

        private double maxScroll() {
                int contentHeight = this.rows.size() * ROW_HEIGHT;
                int listHeight = this.listBottom - this.listTop;
                return Math.max(0, contentHeight - listHeight);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Key Binds");
                EaglerGuiImpl.drawText("Key Binds",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

                int rowX = Math.max(10, this.width / 2 - 245);
                int rowW = Math.min(480, this.width - 20);
                int keyButtonX = rowX + rowW - KEY_BUTTON_WIDTH - 2;

                for (int i = 0; i < this.rows.size(); i++) {
                        int screenY = this.listTop + i * ROW_HEIGHT
                                        - (int) this.scrollAmount;
                        if (screenY + ROW_HEIGHT < this.listTop
                                        || screenY > this.listBottom) {
                                continue;
                        }
                        Row row = this.rows.get(i);
                        if (row.category != null) {
                                // Vanilla category header: full-width label
                                EaglerGuiImpl.drawText(row.label, rowX,
                                                screenY + 6, COLOR_CATEGORY, true);
                        } else if (row.mouseBind) {
                                // Disabled mouse row: label + fixed binding
                                EaglerGuiImpl.drawText(row.label, rowX,
                                                screenY + 6, 0xFF707070, true);
                                String key = mouseBindLabel(row.label);
                                EaglerGuiImpl.fillRect(keyButtonX, screenY,
                                                KEY_BUTTON_WIDTH, ROW_HEIGHT - 2,
                                                0xFF3A3A3A);
                                int keyWidth = EaglerGuiImpl.widthOf(key);
                                EaglerGuiImpl.drawText(key,
                                                keyButtonX + (KEY_BUTTON_WIDTH - keyWidth) / 2.0f,
                                                screenY + 6, 0xFF606060, true);
                        } else {
                                KeyMapping mapping = row.binding;
                                boolean isListening = mapping == this.listening;
                                EaglerGuiImpl.drawText(row.label, rowX,
                                                screenY + 6, COLOR_ROW, true);
                                String key = isListening ? "> ??? <" : mapping.keyName();
                                int keyWidth = EaglerGuiImpl.widthOf(key);
                                boolean hovered = mouseX >= keyButtonX
                                                && mouseX < keyButtonX + KEY_BUTTON_WIDTH
                                                && mouseY >= screenY
                                                && mouseY < screenY + ROW_HEIGHT - 2;
                                EaglerGuiImpl.fillRect(keyButtonX, screenY,
                                                KEY_BUTTON_WIDTH, ROW_HEIGHT - 2,
                                                isListening ? 0xFF6A5A1E
                                                                : hovered ? 0xFF8291B8 : 0xFF6F6F6F);
                                EaglerGuiImpl.drawText(key,
                                                keyButtonX + (KEY_BUTTON_WIDTH - keyWidth) / 2.0f,
                                                screenY + 6,
                                                isListening ? COLOR_LISTENING : COLOR_TEXT, true);
                        }
                }

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && this.listening != null) {
                        // Clicking elsewhere cancels the listening state
                        this.listening = null;
                        return true;
                }
                int rowX = Math.max(10, this.width / 2 - 245);
                int rowW = Math.min(480, this.width - 20);
                int keyButtonX = rowX + rowW - KEY_BUTTON_WIDTH - 2;
                for (int i = 0; i < this.rows.size(); i++) {
                        int screenY = this.listTop + i * ROW_HEIGHT
                                        - (int) this.scrollAmount;
                        if (mouseY >= screenY && mouseY < screenY + ROW_HEIGHT - 2
                                        && mouseX >= keyButtonX
                                        && mouseX < keyButtonX + KEY_BUTTON_WIDTH) {
                                Row row = this.rows.get(i);
                                if (row.binding != null && !row.mouseBind) {
                                        this.listening = row.binding;
                                        return true;
                                }
                                return false;
                        }
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
                // Positive delta = DOM scroll down = show later rows
                double newScroll = this.scrollAmount + delta * ROW_HEIGHT;
                this.scrollAmount = Math.max(0.0D, Math.min(this.maxScroll(), newScroll));
                return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (this.listening != null) {
                        if (keyCode == KeyMapping.SCAN_ESCAPE) {
                                // Vanilla: ESC cancels the listening state
                                this.listening = null;
                                return true;
                        }
                        this.listening.setKey(keyCode);
                        KeyMapping.saveBinds();
                        this.listening = null;
                        return true;
                }
                if (keyCode == 1 && this.shouldCloseOnEsc()) {
                        this.onClose();
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
