package net.minecraft.client.gui.screens;

import com.mojang.blaze3d.vertex.PoseStack;

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
 * <p>The list itself is minimal but honest: one world ("New World",
 * the milestone world) rendered as a real widget with the vanilla
 * geometry, vanilla unknown-world icon, click to select and a second
 * click (or the Play button) to join. Create/Edit/Re-Create/Delete are
 * disabled until world persistence exists.</p>
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
        private WorldEntryWidget worldEntry;

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

                // Create New World spans columns 3-4 (150px). Disabled until
                // world creation/persistence exists.
                Button create = new Button(gridX + 158, row1Y,
                                Button.DEFAULT_WIDTH, Button.HEIGHT, "Create New World", button -> { });
                create.setActive(false);
                this.addRenderableWidget(create);

                // Row 2: Edit / Delete / Re-Create / Back, 71px each
                Button edit = new Button(gridX, row2Y, 71, Button.HEIGHT, "Edit", button -> { });
                edit.setActive(false);
                this.addRenderableWidget(edit);

                Button delete = new Button(gridX + 79, row2Y, 71, Button.HEIGHT, "Delete", button -> { });
                delete.setActive(false);
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

                // ===== The world entry (vanilla WorldSelectionList row) =====
                // Rows are 270px wide, centered in the list; first row at the
                // top of the list area.
                int rowLeft = centerX - ROW_WIDTH / 2;
                this.worldEntry = new WorldEntryWidget(rowLeft, HEADER_HEIGHT,
                                ROW_WIDTH, ENTRY_HEIGHT);
                this.addRenderableWidget(this.worldEntry);

                this.updateButtonStatus();
        }

        /**
         * Vanilla updateButtonStatus: the Play button enables exactly when
         * a world is selected.
         */
        private void updateButtonStatus() {
                if (this.playWorldButton != null) {
                        this.playWorldButton.setActive(this.worldEntry != null && this.worldEntry.isSelected());
                }
        }

        /**
         * The vanilla joinWorld flow: opens the world through the same
         * {@code Minecraft.doWorldLoad} path the Singleplayer milestone
         * uses.
         */
        private void joinSelectedWorld() {
                if (this.minecraft == null) return;
                ClientMain.log("[SelectWorldScreen] Play pressed -> loading world '" + WORLD_NAME + "'");
                this.minecraft.doWorldLoad(WORLD_NAME);
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                // Vanilla Screen.extractBackground (level == null): the rotating
                // panorama cubemap is already drawn behind the GUI by
                // GameRenderer; this screen tiles the alpha-64 menu_background
                // over it, exactly like vanilla extractMenuBackground.
                boolean bgDrawn = EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                        0, 0, this.width, this.height, 0, 0);

                int listTop = HEADER_HEIGHT;
                int listBottom = this.height - FOOTER_HEIGHT;

                if (bgDrawn) {
                        // Vanilla extractListBackground: menu_list_background
                        // tiles the list area, pattern anchored to the screen
                        EaglerGuiImpl.drawSpriteTiled("gui/menu_list_background",
                                0, listTop, this.width, listBottom - listTop,
                                this.width, this.height - listBottom);
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
         * click routing and the telemetry bridge (id "newworld").
         * Render order matches vanilla WorldListEntry.extractContent:
         * selection outline, icon, then the three text lines.
         */
        public class WorldEntryWidget extends AbstractWidget {

                private boolean selected = false;

                public WorldEntryWidget(int x, int y, int width, int height) {
                        super(x, y, width, height);
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
                        EaglerGuiImpl.drawText(WORLD_NAME, textX, y + 2 + 1, 0xFFFFFFFF, true);
                        EaglerGuiImpl.drawText(WORLD_NAME + " (10/23/26)", textX, y + 2 + 12,
                                COLOR_SECONDARY_TEXT, true);
                        EaglerGuiImpl.drawText("Version: 26.1.2", textX, y + 2 + 21,
                                COLOR_SECONDARY_TEXT, true);
                }

                @Override
                protected void onClick() {
                        if (this.selected) {
                                // Second click on the selected row: vanilla
                                // double-click joins the world
                                joinSelectedWorld();
                        } else {
                                this.selected = true;
                                ClientMain.log("[SelectWorldScreen] world entry selected");
                        }
                        updateButtonStatus();
                }

                /**
                 * Stable id for the e2e telemetry bridge, so automation can
                 * click the world row exactly like a button.
                 */
                @Override
                public String getTelemetryId() {
                        return "newworld";
                }
        }
}
