package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v2_6.WorldStorage;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.screens.worldselection.SelectWorldScreen.
 *
 * <p>The screen the vanilla Singleplayer button opens: a world list with
 * a search field above and the Play / Create / Edit / Delete / Re-Create
 * / Back button grid below. Every geometry constant here is straight
 * from the decompiled 26.1.2 sources (see docs/UI-FIDELITY.md):</p>
 *
 * <ul>
 *   <li>Title centered at y=8; search field 200x20 at (w/2-100, 22)</li>
 *   <li>Header/footer layout heights 49/60, so the list spans
 *       y=49..h-60 and rows are 270px wide centered (w/2-135)</li>
 *   <li>List entries are 36px tall: 32x32 icon at (rowLeft+2, y+2),
 *       world name at (rowLeft+37, y+3), id/date and info lines in gray
 *       {@code 0xFF808080} 9px apart below it; selection = 1px
 *       {@code 0xFF808080} outline + black fill</li>
 *   <li>Footer grid: 4 columns of 71px with 8px spacing (total 308,
 *       centered); Play and Create span two columns (150px), row 2 has
 *       Edit / Delete / Re-Create / Back at 71px</li>
 *   <li>Play starts disabled and enables when a world is selected
 *       ({@code updateButtonStatus(null)} at init)</li>
 *   <li>No scrollbar: with a list that doesn't overflow, vanilla
 *       renders none (the default ScrollbarSettings has a null
 *       disabled sprite)</li>
 * </ul>
 *
 * <p>The list is the real localStorage world list (vanilla
 * LevelStorageSource stand-in, see {@link WorldStorage}): created worlds
 * are persisted with their settings, the milestone "New World" seeds a
 * fresh profile, and Play/Delete operate on the selected entry.
 * Edit/Re-Create stay disabled until world editing exists.</p>
 */
public class SelectWorldScreen extends Screen {

        /** Vanilla HeaderAndFooterLayout heights for this screen. */
        private static final int HEADER_HEIGHT = 49;
        private static final int FOOTER_HEIGHT = 60;

        /** Vanilla WorldSelectionList.getRowWidth(). */
        private static final int ROW_WIDTH = 270;

        /** Vanilla ObjectSelectionList entry height. */
        private static final int ENTRY_HEIGHT = 36;

        private static final int COLOR_SECONDARY_TEXT = 0xFF808080;
        private static final int COLOR_SELECTION_OUTLINE = 0xFF808080;
        private static final int COLOR_SELECTION_FILL = 0xFF000000;
        private static final int COLOR_HINT_TEXT = 0xFFA0A0A0;

        /** The one world this build ships (the milestone world). */
        static final String WORLD_NAME = TitleScreen.DEFAULT_WORLD_NAME;

        private Button playWorldButton;
        private Button deleteButton;
        private final List<WorldEntryWidget> worldEntries = new ArrayList<WorldEntryWidget>();
        private WorldEntryWidget selectedEntry;

        public SelectWorldScreen(Screen lastScreen) {
                super();
                this.lastScreen = lastScreen;
        }

        private final Screen lastScreen;

        @Override
        protected void init() {
                int centerX = this.width / 2;

                // ===== Footer button grid (vanilla createFooterButtons) =====
                // 4 columns x 71px + 8px spacing = 308, horizontally centered.
                int gridX = centerX - 154;
                int row1Y = this.height - FOOTER_HEIGHT + 8;
                int row2Y = row1Y + Button.HEIGHT + 4;

                // Play Selected World spans columns 1-2 (150px). Starts
                // disabled - vanilla updateButtonStatus(null) at init.
                this.playWorldButton = new Button(gridX, row1Y,
                                Button.DEFAULT_WIDTH, Button.HEIGHT, "Play Selected World",
                                button -> this.joinSelectedWorld());
                this.playWorldButton.setActive(false);
                this.addRenderableWidget(this.playWorldButton);

                // Create New World spans columns 3-4 (150px). Opens the
                // vanilla CreateWorldScreen (tabbed world creation form).
                Button create = new Button(gridX + 158, row1Y,
                                Button.DEFAULT_WIDTH, Button.HEIGHT, "Create New World",
                                button -> {
                                        ClientMain.log("[SelectWorldScreen] Create New World pressed");
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(new CreateWorldScreen(this));
                                        }
                                });
                this.addRenderableWidget(create);

                // Row 2: Edit / Delete / Re-Create / Back, 71px each
                Button edit = new Button(gridX, row2Y, 71, Button.HEIGHT, "Edit", button -> { });
                edit.setActive(false);
                this.addRenderableWidget(edit);

                Button delete = new Button(gridX + 79, row2Y, 71, Button.HEIGHT, "Delete",
                                button -> this.deleteSelectedWorld());
                delete.setActive(false);
                this.deleteButton = delete;
                this.addRenderableWidget(delete);

                Button recreate = new Button(gridX + 158, row2Y, 71, Button.HEIGHT, "Re-Create", button -> { });
                recreate.setActive(false);
                this.addRenderableWidget(recreate);

                this.addRenderableWidget(new Button(gridX + 237, row2Y, 71, Button.HEIGHT, "Back",
                                button -> {
                                        ClientMain.log("[SelectWorldScreen] Back pressed");
                                        if (this.minecraft != null) {
                                                this.minecraft.setScreen(this.lastScreen);
                                        }
                                }));

                // ===== The world entries (vanilla WorldSelectionList rows) =====
                // Rows are 270px wide, centered in the list, 36px tall,
                // stacked from the top of the list area. The list content is
                // the localStorage world list (WorldStorage.listWorlds).
                int rowLeft = centerX - ROW_WIDTH / 2;
                this.worldEntries.clear();
                this.selectedEntry = null;
                WorldStorage.WorldSummary[] summaries = WorldStorage.listWorlds();
                for (int i = 0; i < summaries.length; i++) {
                        WorldEntryWidget entry = new WorldEntryWidget(rowLeft,
                                        HEADER_HEIGHT + i * (ENTRY_HEIGHT + 2),
                                        ROW_WIDTH, ENTRY_HEIGHT, summaries[i]);
                        this.worldEntries.add(entry);
                        this.addRenderableWidget(entry);
                }

                this.updateButtonStatus();
        }

        /**
         * Vanilla updateButtonStatus: the Play button enables exactly when
         * a world is selected; Delete follows the same rule.
         */
        private void updateButtonStatus() {
                boolean hasSelection = this.selectedEntry != null;
                if (this.playWorldButton != null) {
                        this.playWorldButton.setActive(hasSelection);
                }
                if (this.deleteButton != null) {
                        this.deleteButton.setActive(hasSelection);
                }
        }

        /** Vanilla deleteButton flow: remove the selected world's record. */
        private void deleteSelectedWorld() {
                if (this.minecraft == null || this.selectedEntry == null) return;
                String name = this.selectedEntry.summary.name;
                ClientMain.log("[SelectWorldScreen] Delete pressed -> world '" + name + "'");
                WorldStorage.deleteWorld(name);
                // Rebuild the whole screen (vanilla refreshes the list)
                this.init(this.minecraft, this.width, this.height);
        }

        /**
         * The vanilla joinWorld flow: opens the world through the same
         * {@code Minecraft.doWorldLoad} path the Singleplayer milestone
         * uses.
         */
        private void joinSelectedWorld() {
                if (this.minecraft == null) return;
                String name = this.selectedEntry != null
                                ? this.selectedEntry.summary.name
                                : WORLD_NAME;
                ClientMain.log("[SelectWorldScreen] Play pressed -> loading world '" + name + "'");
                this.minecraft.doWorldLoad(name);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // Vanilla Screen.extractBackground (level == null): the blurred
                // panorama cubemap is already presented to the screen by
                // GameRenderer (blur radius = menuBackgroundBlurriness); this
                // screen tiles the alpha-64 menu_background over it, exactly
                // like vanilla extractMenuBackground - whose blit declares
                // textureWidth 32, so the 16px PNG repeats every 32 GUI px.
                boolean bgDrawn = EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                        0, 0, this.width, this.height, 0, 0, 32);

                int listTop = HEADER_HEIGHT;
                int listBottom = this.height - FOOTER_HEIGHT;

                if (bgDrawn) {
                        // Vanilla extractListBackground: menu_list_background
                        // tiles the list area, pattern anchored to the screen
                        // (uv offsets 0,0 like vanilla), same 32px repeat
                        EaglerGuiImpl.drawSpriteTiled("gui/menu_list_background",
                                0, listTop, this.width, listBottom - listTop, 0, 0, 32);
                        // Vanilla extractListSeparators: 2px lines above and below
                        EaglerGuiImpl.drawSprite("gui/header_separator", 0, listTop - 2, this.width, 2);
                        EaglerGuiImpl.drawSprite("gui/footer_separator", 0, listBottom, this.width, 2);
                } else {
                        // Pre-sprite fallback: keep the panorama visible and
                        // just darken the list band for text readability
                        EaglerGuiImpl.fillRect(0, listTop, this.width, listBottom - listTop, 0x90000000);
                }

                // Title (vanilla: StringWidget centered in the header)
                int titleWidth = EaglerGuiImpl.widthOf("Select World");
                EaglerGuiImpl.drawText("Select World", centerX() - titleWidth / 2.0f, 8, 0xFFFFFFFF, true);

                // Search field (vanilla EditBox at w/2-100, 22, 200x20) -
                // visual only until text input exists; renders the widget
                // sprite with the vanilla "Search..." hint.
                int searchX = centerX() - 100;
                if (!EaglerGuiImpl.drawSprite("widget/text_field", searchX, 22, 200, 20)) {
                        EaglerGuiImpl.fillRect(searchX, 22, 200, 20, 0xFF000000);
                        EaglerGuiImpl.fillRect(searchX + 1, 23, 198, 18, 0xFF8B8B8B);
                }
                EaglerGuiImpl.drawText("Search...", searchX + 4, 22 + 6, COLOR_HINT_TEXT, false);

                // Widgets (world entry + footer buttons) on top
                super.render(poseStack, mouseX, mouseY, partialTick);
        }

        private int centerX() {
                return this.width / 2;
        }

        @Override
        public boolean isPauseScreen() {
                return true;
        }

        /**
         * One row of the world list, a real widget so it takes part in
         * click routing and the telemetry bridge. Render order matches
         * vanilla WorldListEntry.extractContent: selection outline, icon,
         * then the three text lines (name, name + last-played date,
         * version line - the date formatted like vanilla's MM/DD/YY).
         */
        public class WorldEntryWidget extends AbstractWidget {

                final WorldStorage.WorldSummary summary;
                private boolean selected = false;

                WorldEntryWidget(int x, int y, int width, int height,
                                WorldStorage.WorldSummary summary) {
                        super(x, y, width, height);
                        this.summary = summary;
                }

                public boolean isSelected() {
                        return this.selected;
                }

                @Override
                public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                        // Vanilla extractSelection: 1px gray outline, black fill
                        if (this.selected) {
                                EaglerGuiImpl.fillRect(x, y, width, height, COLOR_SELECTION_OUTLINE);
                                EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2, COLOR_SELECTION_FILL);
                        }

                        // Icon: 32x32 at (contentX, contentY); a fresh world has
                        // no icon.png, so vanilla shows the unknown-world art
                        int iconX = x + 2;
                        int iconY = y + 2;
                        if (!EaglerGuiImpl.drawSprite("misc/unknown_server", iconX, iconY, 32, 32)) {
                                EaglerGuiImpl.fillRect(iconX, iconY, 32, 32, 0xFF000000);
                                EaglerGuiImpl.fillRect(iconX + 1, iconY + 1, 30, 30, 0xFF3F3F3F);
                        }

                        // Text stack at contentX + 32 + 3, rows at +1/+12/+21
                        // relative to the content box (contentY = y + 2)
                        int textX = x + 2 + 32 + 3;
                        EaglerGuiImpl.drawText(summary.name, textX, y + 2 + 1, 0xFFFFFFFF, true);
                        EaglerGuiImpl.drawText(summary.name + " (" + formatDate(summary.lastPlayed) + ")",
                                textX, y + 2 + 12, COLOR_SECONDARY_TEXT, true);
                        EaglerGuiImpl.drawText("Version: " + summary.version, textX, y + 2 + 21,
                                COLOR_SECONDARY_TEXT, true);
                }

                @Override
                protected void onClick() {
                        // Vanilla: clicking a row selects it exclusively; a
                        // second click on the selected row joins the world
                        if (!this.selected) {
                                for (int i = 0; i < worldEntries.size(); i++) {
                                        worldEntries.get(i).selected = false;
                                }
                                this.selected = true;
                                selectedEntry = this;
                                ClientMain.log("[SelectWorldScreen] world entry selected: "
                                                + this.summary.name);
                        } else {
                                joinSelectedWorld();
                        }
                        updateButtonStatus();
                }

                /**
                 * Stable id for the e2e telemetry bridge, so automation can
                 * click the world row exactly like a button (the milestone
                 * "New World" keeps the historical "newworld" id).
                 */
                @Override
                public String getTelemetryId() {
                        return summary.name.toLowerCase().replaceAll("[^a-z0-9]+", "");
                }
        }

        /** Vanilla's MM/DD/YY world-list date format. */
        private static String formatDate(long millis) {
                if (millis <= 0L) return "";
                java.util.Date d = new java.util.Date(millis);
                int month = d.getMonth() + 1;
                int day = d.getDate();
                int year = (d.getYear() + 1900) % 100;
                return month + "/" + day + "/" + (year < 10 ? "0" : "") + year;
        }
}
