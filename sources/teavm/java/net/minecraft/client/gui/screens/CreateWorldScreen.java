package net.minecraft.client.gui.screens;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;

/**
 * EaglerCraft 26.1.2 browser override for
 * net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.
 *
 * <p>Every geometry constant below is straight from the decompiled
 * 26.1.2 sources (see docs/UI-FIDELITY.md):</p>
 *
 * <ul>
 *   <li>TabNavigationBar: height 24, {@code tabsWidth = min(400, w) - 28},
 *       {@code tabWidth = roundToward(tabsWidth / 3, 2)}, bar centered at
 *       {@code roundToward((w - tabsWidth) / 2, 2)}; the
 *       {@code gui/header_separator} texture runs 2px tall at y=22 on
 *       both sides of the tab strip</li>
 *   <li>Tab sprites {@code widget/tab} / {@code widget/tab_selected}
 *       (plus the _highlighted hover variants), nine-slice borders
 *       left/top/right 2, bottom 0 straight from their .png.mcmeta</li>
 *   <li>Tab content starts at {@code y = 24 + (h - 57 - contentH) / 6}
 *       (TabManager tab area + FrameLayout alignY 1/6), centered
 *       horizontally; rows 8px apart, the labeled field is a 9px label
 *       4px above the 20px edit</li>
 *   <li>GameTab: "World Name" label over a 208x20 EditBox (vanilla
 *       default value "New World"), then 210x20 cycle buttons
 *       "Game Mode: Survival|Hardcore|Creative" (Hardcore locks
 *       Difficulty to Hard and disables Allow Cheats),
 *       "Difficulty: Peaceful|Easy|Normal|Hard",
 *       "Allow Cheats: OFF|ON"</li>
 *   <li>WorldTab: "World Type: Default|Superflat|Large Biomes|AMPLIFIED"
 *       150x20 + "Customize" 150x20 (disabled, as for non-editable
 *       presets), "Seed for the world generator" label over a 308x20
 *       EditBox (hint "Leave blank for a random seed"), and the two
 *       SwitchGrid switches "Generate Structures" / "Bonus Chest" as
 *       44x20 ON/OFF toggles (SwitchGrid row spacing 4, button width 44)</li>
 *   <li>MoreTab: "Game Rules" / "Experiments" / "Data Packs" 210x20
 *       buttons (no sub-screens in this build yet)</li>
 *   <li>Footer: 33px strip, buttons vertically centered
 *       ({@code y = h - 33 + 6}): "Create New World" and "Cancel",
 *       150x20 each, 8px apart, centered</li>
 * </ul>
 *
 * <p>Create joins a world the same way SelectWorldScreen does (the
 * milestone {@code Minecraft.doWorldLoad} path), using the typed world
 * name; persistence and per-world settings come with the world-storage
 * milestone. Cancel / ESC return to the Select World screen.</p>
 */
public class CreateWorldScreen extends Screen {

        /** Vanilla TabNavigationBar.HEIGHT. */
        private static final int TAB_BAR_HEIGHT = 24;

        /** Vanilla TabNavigationBar.MAX_WIDTH + layout margins. */
        private static final int TAB_BAR_MAX_WIDTH = 400;
        private static final int TAB_BAR_MARGIN = 28;

        /** Vanilla HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT. */
        private static final int FOOTER_HEIGHT = 33;

        /** Vanilla GridLayout row spacing on the Create tabs. */
        private static final int ROW_SPACING = 8;

        /** Vanilla SwitchGrid defaults. */
        private static final int SWITCH_BUTTON_WIDTH = 44;
        private static final int SWITCH_ROW_SPACING = 4;

        private static final int COLOR_TAB_TEXT = 0xFFFFFFFF;
        private static final int COLOR_TAB_TEXT_INACTIVE = 0xFFA0A0A0;

        private static final String[] TAB_LABELS = { "Game", "World", "More" };

        private static final String[] GAME_MODES = { "Survival", "Hardcore", "Creative" };
        private static final String[] DIFFICULTIES = { "Peaceful", "Easy", "Normal", "Hard" };
        private static final String[] WORLD_TYPES = { "Default", "Superflat", "Large Biomes", "AMPLIFIED" };

        private final Screen lastScreen;

        private final List<TabButtonWidget> tabButtons = new ArrayList<TabButtonWidget>();
        private int selectedTab = 0;

        // Game tab
        private EditBox nameEdit;
        private CycleButton gameModeButton;
        private CycleButton difficultyButton;
        private CycleButton allowCommandsButton;

        // World tab
        private CycleButton worldTypeButton;
        private Button customizeTypeButton;
        private EditBox seedEdit;
        private CycleButton generateStructuresButton;
        private CycleButton bonusChestButton;

        // More tab
        private final List<Button> moreButtons = new ArrayList<Button>();

        // Tab content widget groups (visibility toggled on tab switch)
        private final List<AbstractWidgetWrapper> tabWidgets = new ArrayList<AbstractWidgetWrapper>();

        /** Mutable ui state (the vanilla WorldCreationUiState subset). */
        private int gameModeIndex = 0;
        private int difficultyIndex = 2; // Normal
        private boolean allowCommands = false;
        private boolean generateStructures = true;
        private boolean bonusChest = false;

        public CreateWorldScreen(Screen lastScreen) {
                super();
                this.lastScreen = lastScreen;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                tabButtons.clear();
                tabWidgets.clear();
                moreButtons.clear();

                // ===== Tab bar (vanilla TabNavigationBar.arrangeElements) =====
                int tabsWidth = Math.min(TAB_BAR_MAX_WIDTH, this.width) - TAB_BAR_MARGIN;
                int tabWidth = tabsWidth / 3;
                if ((tabWidth & 1) != 0) tabWidth += 1; // roundToward(,2)
                int barX = (this.width - tabsWidth) / 2;
                if ((barX & 1) != 0) barX += 1;         // roundToward(,2)

                for (int i = 0; i < TAB_LABELS.length; i++) {
                        TabButtonWidget tab = new TabButtonWidget(barX + i * tabWidth, 0,
                                        tabWidth, TAB_BAR_HEIGHT, TAB_LABELS[i], i == selectedTab, i);
                        tabButtons.add(tab);
                        this.addRenderableWidget(tab);
                }

                // ===== Tab contents (laid out at the vanilla content Y) =====
                layoutGameTab(centerX);
                layoutWorldTab(centerX);
                layoutMoreTab(centerX);
                for (int i = 0; i < tabWidgets.size(); i++) {
                        this.addRenderableWidget(tabWidgets.get(i).widget);
                }
                applyTabVisibility();
                applyGameModeLocks();

                // ===== Footer (HeaderAndFooterLayout footer, children centered) =====
                int footerY = this.height - FOOTER_HEIGHT
                                + (FOOTER_HEIGHT - Button.HEIGHT) / 2;
                Button create = new Button(centerX - 154, footerY, Button.DEFAULT_WIDTH,
                                Button.HEIGHT, "Create New World", button -> this.onCreate());
                this.addRenderableWidget(create);
                Button cancel = new Button(centerX + 4, footerY, Button.DEFAULT_WIDTH,
                                Button.HEIGHT, "Cancel", button -> this.popScreen());
                this.addRenderableWidget(cancel);

                // Focus starts on the name field (vanilla setInitialFocus)
                if (nameEdit != null && selectedTab == 0) {
                        nameEdit.setFocused(true);
                }
        }

        /** Vanilla content Y: tab area top + FrameLayout alignY(1/6). */
        private int contentY(int contentHeight) {
                int areaTop = TAB_BAR_HEIGHT;
                int areaHeight = this.height - FOOTER_HEIGHT - TAB_BAR_HEIGHT;
                return areaTop + (areaHeight - contentHeight) / 6;
        }

        private void layoutGameTab(int centerX) {
                // label(9) + 4 + edit(20) + 8 + 20 + 8 + 20 + 8 + 20 = 109
                int y0 = contentY(109);

                nameEdit = new EditBox(centerX - 104, y0 + 13, 208, 20, "World Name");
                nameEdit.setValue("New World");
                registerTabWidget(0, nameEdit);

                gameModeButton = new CycleButton(centerX - 105, y0 + 41, 210, 20,
                                "Game Mode", GAME_MODES, gameModeIndex,
                                (button, newIndex) -> {
                                        gameModeIndex = newIndex;
                                        applyGameModeLocks();
                                });
                registerTabWidget(0, gameModeButton);

                difficultyButton = new CycleButton(centerX - 105, y0 + 69, 210, 20,
                                "Difficulty", DIFFICULTIES, difficultyIndex,
                                (button, newIndex) -> difficultyIndex = newIndex);
                registerTabWidget(0, difficultyButton);

                allowCommandsButton = new CycleButton(centerX - 105, y0 + 97, 210, 20,
                                "Allow Cheats", new String[] { CycleButton.VALUE_OFF, CycleButton.VALUE_ON },
                                allowCommands ? 1 : 0,
                                (button, newIndex) -> allowCommands = newIndex == 1);
                registerTabWidget(0, allowCommandsButton);
        }

        private void layoutWorldTab(int centerX) {
                // row1(20) + 8 + label(9)+4+seed(20) + 8 + switch rows (20+4+20) = 113
                int y0 = contentY(113);
                int gridLeft = centerX - 155; // 150 + 10 + 150 = 310 wide

                worldTypeButton = new CycleButton(gridLeft, y0, 150, 20,
                                "World Type", WORLD_TYPES, 0, null);
                registerTabWidget(1, worldTypeButton);

                customizeTypeButton = new Button(gridLeft + 160, y0, 150, 20,
                                "Customize", button -> { /* preset editors: later milestone */ });
                customizeTypeButton.setActive(false);
                registerTabWidget(1, customizeTypeButton);

                int seedLabelY = y0 + 28;
                seedEdit = new EditBox(centerX - 154, seedLabelY + 13, 308, 20,
                                "Leave blank for a random seed");
                seedEdit.setValue("");
                registerTabWidget(1, seedEdit);

                int switchLeft = centerX - 155; // SwitchGrid width 310
                generateStructuresButton = new CycleButton(
                                switchLeft + 310 - SWITCH_BUTTON_WIDTH, y0 + 69,
                                SWITCH_BUTTON_WIDTH, 20, "",
                                new String[] { CycleButton.VALUE_ON, CycleButton.VALUE_OFF },
                                generateStructures ? 0 : 1,
                                (button, newIndex) -> generateStructures = newIndex == 0);
                registerTabWidget(1, generateStructuresButton);

                bonusChestButton = new CycleButton(
                                switchLeft + 310 - SWITCH_BUTTON_WIDTH,
                                y0 + 69 + 20 + SWITCH_ROW_SPACING,
                                SWITCH_BUTTON_WIDTH, 20, "",
                                new String[] { CycleButton.VALUE_ON, CycleButton.VALUE_OFF },
                                bonusChest ? 0 : 1,
                                (button, newIndex) -> bonusChest = newIndex == 0);
                registerTabWidget(1, bonusChestButton);
        }

        private void layoutMoreTab(int centerX) {
                // 3 x 20 + 2 x 8 = 76
                int y0 = contentY(76);
                String[] labels = { "Game Rules", "Experiments", "Data Packs" };
                for (int i = 0; i < labels.length; i++) {
                        Button button = new Button(centerX - 105, y0 + i * (Button.HEIGHT + ROW_SPACING),
                                        210, 20, labels[i], b -> { /* sub-screens: later milestone */ });
                        button.setActive(false);
                        moreButtons.add(button);
                        registerTabWidget(2, button);
                }
        }

        private void registerTabWidget(int tab, AbstractWidget widget) {
                tabWidgets.add(new AbstractWidgetWrapper(tab, widget));
        }

        private void applyTabVisibility() {
                for (int i = 0; i < tabWidgets.size(); i++) {
                        AbstractWidgetWrapper wrapper = tabWidgets.get(i);
                        wrapper.widget.setVisible(wrapper.tab == selectedTab);
                }
                for (int i = 0; i < tabButtons.size(); i++) {
                        tabButtons.get(i).setSelected(i == selectedTab);
                }
        }

        /**
         * Vanilla uiState listener effects: Hardcore forces the difficulty
         * to Hard and greys out both the Difficulty and Allow Cheats
         * selectors.
         */
        private void applyGameModeLocks() {
                boolean hardcore = gameModeIndex == 1;
                if (hardcore) {
                        difficultyIndex = 3; // Hard, forced
                }
                if (difficultyButton != null) {
                        difficultyButton.setIndex(difficultyIndex);
                        difficultyButton.setActive(!hardcore);
                }
                if (allowCommandsButton != null) {
                        allowCommandsButton.setActive(!hardcore);
                }
        }

        /** Vanilla onCreate: persist the world, then join it. */
        private void onCreate() {
                if (this.minecraft == null) return;
                String name = nameEdit != null && !nameEdit.getValue().trim().isEmpty()
                                ? nameEdit.getValue().trim()
                                : "New World";
                String seed = seedEdit != null ? seedEdit.getValue() : "";
                ClientMain.log("[CreateWorldScreen] Create pressed -> world '" + name
                                + "' (mode=" + GAME_MODES[gameModeIndex]
                                + ", difficulty=" + DIFFICULTIES[difficultyIndex]
                                + ", cheats=" + allowCommands + ", type=" + WORLD_TYPES[worldTypeButton.getIndex()]
                                + ", seed=" + seed + ")");

                // Vanilla createFreshLevel writes the level to disk first:
                // persist the settings to localStorage so the Select World
                // list shows (and can rejoin) this world afterwards
                net.lax1dude.eaglercraft.v2_6.WorldStorage.WorldSummary summary =
                                new net.lax1dude.eaglercraft.v2_6.WorldStorage.WorldSummary();
                summary.name = name;
                summary.seed = seed;
                summary.gameMode = GAME_MODES[gameModeIndex];
                summary.allowCommands = allowCommands;
                summary.generateStructures = generateStructures;
                summary.bonusChest = bonusChest;
                // Real epoch - PlatformRuntime.getCurrentTimeMillis is the
                // monotonic page-uptime clock, not a wall clock
                summary.lastPlayed = System.currentTimeMillis();
                net.lax1dude.eaglercraft.v2_6.WorldStorage.saveWorld(summary);

                this.minecraft.doWorldLoad(name);
        }

        /** Vanilla popScreen: back to the screen that opened this one. */
        private void popScreen() {
                if (this.minecraft != null) {
                        this.minecraft.setScreen(this.lastScreen);
                }
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // Background: the blurred panorama was already presented to
                // the screen by GameRenderer (vanilla extractBackground);
                // like every non-title screen this one tiles the alpha-64
                // menu_background over the blur (vanilla extractMenuBackground,
                // 16px PNG repeating every 32 GUI px).
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                        0, 0, this.width, this.height, 0, 0, 32);

                // Tab bar chrome: the gui/header_separator texture runs 2px
                // tall at y = HEIGHT - 2 on both sides of the tab strip
                // (vanilla TabNavigationBar.extractRenderState)
                int tabsWidth = Math.min(TAB_BAR_MAX_WIDTH, this.width) - TAB_BAR_MARGIN;
                int tabWidth = tabsWidth / 3;
                if ((tabWidth & 1) != 0) tabWidth += 1;
                int barX = (this.width - tabsWidth) / 2;
                if ((barX & 1) != 0) barX += 1;

                int separatorY = TAB_BAR_HEIGHT - 2;
                boolean separatorOk = EaglerGuiImpl.drawSprite("gui/header_separator",
                                0, separatorY, barX, 2);
                EaglerGuiImpl.drawSprite("gui/header_separator",
                                barX + tabWidth * TAB_LABELS.length, separatorY,
                                this.width - (barX + tabWidth * TAB_LABELS.length), 2);
                if (!separatorOk) {
                        EaglerGuiImpl.fillRect(0, separatorY, this.width, 2, 0xFF000000);
                }

                // Switch labels ("Generate Structures" / "Bonus Chest") live
                // outside their buttons, SwitchGrid style
                if (selectedTab == 1 && generateStructuresButton != null) {
                        int labelY = generateStructuresButton.getY()
                                        + (Button.HEIGHT - EaglerFont.GLYPH_HEIGHT) / 2;
                        EaglerGuiImpl.drawText("Generate Structures", centerX() - 155,
                                        labelY, COLOR_TAB_TEXT, false);
                        int bonusY = bonusChestButton.getY()
                                        + (Button.HEIGHT - EaglerFont.GLYPH_HEIGHT) / 2;
                        EaglerGuiImpl.drawText("Bonus Chest", centerX() - 155,
                                        bonusY, COLOR_TAB_TEXT, false);
                }

                // The "World Name" / "Seed for the world generator" labels
                // above their fields (CommonLayouts.labeledElement)
                if (nameEdit != null && selectedTab == 0) {
                        drawCenteredLabel("World Name", centerX(),
                                        nameEdit.getY() - EaglerFont.GLYPH_HEIGHT - 4);
                }
                if (seedEdit != null && selectedTab == 1) {
                        drawCenteredLabel("Seed for the world generator", centerX(),
                                        seedEdit.getY() - EaglerFont.GLYPH_HEIGHT - 4);
                }

                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        private void drawCenteredLabel(String text, int centerX, int y) {
                int textWidth = EaglerGuiImpl.widthOf(text);
                EaglerGuiImpl.drawText(text, centerX - textWidth / 2.0f, y, COLOR_TAB_TEXT, false);
        }

        private int centerX() {
                return this.width / 2;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean consumed = super.mouseClicked(mouseX, mouseY, button);
                // Tab switching
                for (int i = 0; i < tabButtons.size(); i++) {
                        TabButtonWidget tab = tabButtons.get(i);
                        if (tab.consumedClick()) {
                                tab.clearConsumed();
                                if (selectedTab != tab.tabIndex) {
                                        selectedTab = tab.tabIndex;
                                        applyTabVisibility();
                                        // vanilla setInitialFocus: name field on the Game tab
                                        if (nameEdit != null) nameEdit.setFocused(false);
                                        if (seedEdit != null) seedEdit.setFocused(false);
                                        if (selectedTab == 0 && nameEdit != null) {
                                                nameEdit.setFocused(true);
                                        }
                                }
                                return true;
                        }
                }
                // Unfocus edit boxes when the click lands elsewhere
                if (nameEdit != null && nameEdit.isFocused() && !nameEdit.isInside(mouseX, mouseY)) {
                        nameEdit.setFocused(false);
                }
                if (seedEdit != null && seedEdit.isFocused() && !seedEdit.isInside(mouseX, mouseY)) {
                        seedEdit.setFocused(false);
                }
                return consumed;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                // Vanilla: ESC pops back to the previous screen
                if (keyCode == 1) { // Escape (mapKeyCode "Escape")
                        popScreen();
                        return true;
                }
                if (nameEdit != null && nameEdit.keyPressed(keyCode)) return true;
                if (seedEdit != null && seedEdit.keyPressed(keyCode)) return true;
                return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
                if (nameEdit != null && nameEdit.charTyped(codePoint)) return true;
                if (seedEdit != null && seedEdit.charTyped(codePoint)) return true;
                return super.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }

        /**
         * One header tab: the vanilla TabButton with the
         * {@code widget/tab*} sprite family (nine-slice left/top/right 2,
         * bottom 0 - the tab sits flush on the header separator).
         */
        public class TabButtonWidget extends AbstractWidget {

                private final String label;
                private final int tabIndex;
                private boolean selected;
                private boolean clickConsumed = false;

                public TabButtonWidget(int x, int y, int width, int height,
                                String label, boolean selected, int tabIndex) {
                        super(x, y, width, height);
                        this.label = label;
                        this.selected = selected;
                        this.tabIndex = tabIndex;
                }

                public void setSelected(boolean selected) {
                        this.selected = selected;
                }

                public boolean consumedClick() {
                        return clickConsumed;
                }

                public void clearConsumed() {
                        this.clickConsumed = false;
                }

                @Override
                public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                        if (!visible) return;
                        this.hovered = active && isInside(mouseX, mouseY);

                        // Vanilla WidgetSprites.get(active, hovered) selection
                        String sprite;
                        if (selected) {
                                sprite = hovered ? "widget/tab_selected_highlighted"
                                                : "widget/tab_selected";
                        } else {
                                sprite = hovered ? "widget/tab_highlighted" : "widget/tab";
                        }
                        if (!EaglerGuiImpl.drawSprite9Slices(sprite, x, y, width, height,
                                        2, 2, 2, 0)) {
                                // Pre-sprite fallback: flat tab panel
                                EaglerGuiImpl.fillRect(x, y, width, height,
                                                selected ? 0xFF000000 : 0xFF585858);
                                EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                                                selected ? 0xFF3C3C3C : 0xFF414141);
                        }

                        // Centered label; white when selected, gray otherwise
                        int textWidth = EaglerGuiImpl.widthOf(label);
                        float tx = x + (width - textWidth) / 2.0f;
                        float ty = y + (height - EaglerFont.GLYPH_HEIGHT) / 2.0f;
                        EaglerGuiImpl.drawText(label, tx, ty,
                                        selected ? COLOR_TAB_TEXT : COLOR_TAB_TEXT_INACTIVE,
                                        selected);
                }

                @Override
                public boolean mouseClicked(double mouseX, double mouseY, int button) {
                        if (!visible || !active || button != 0) return false;
                        if (isInside(mouseX, mouseY)) {
                                this.clickConsumed = true;
                                return true;
                        }
                        return false;
                }

                /** Bridge id: "tab_game" / "tab_world" / "tab_more". */
                @Override
                public String getTelemetryId() {
                        return "tab_" + label.toLowerCase();
                }
        }

        /** Pairs a widget with the tab it belongs to (visibility group). */
        private static class AbstractWidgetWrapper {
                final int tab;
                final AbstractWidget widget;

                AbstractWidgetWrapper(int tab, AbstractWidget widget) {
                        this.tab = tab;
                        this.widget = widget;
                }
        }
}
