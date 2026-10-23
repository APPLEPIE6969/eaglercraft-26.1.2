package net.minecraft.client.gui.components;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerFont;
import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code EditBox} text
 * field (the world-name and seed inputs on CreateWorldScreen).
 *
 * <p>Visual behavior mirrors the decompiled 26.1.2 widget: the
 * {@code widget/text_field} sprite ({@code border: 1} nine-slice per its
 * .png.mcmeta; the {@code _highlighted} variant while focused), text in
 * vanilla's {@code 0xFFE0E0E0} drawn 4px inside the left edge and
 * vertically centered, the gray {@code 0xFF7F7F7F} hint while empty and
 * unfocused, and a blinking 1px cursor ({@code 0xFFD0D0D0}, 500ms half
 * period) at the insertion point. Text wider than the field scrolls
 * horizontally so the cursor stays visible, exactly like vanilla's
 * {@code displayPos}.</p>
 *
 * <p>Input: printable characters from {@link #charTyped} insert at the
 * cursor; Backspace (14), Delete (211), Left/Right (203/205) and
 * Home/End (199/207) edit per the scan-code table the browser port
 * uses. Clicking the field focuses it; the owning screen unfocuses it
 * when a click lands elsewhere.</p>
 */
public class EditBox extends AbstractWidget {

        /** Vanilla EditBox.textColor (26.1.2: 0xE0E0E0, no shadow). */
        private static final int COLOR_TEXT = 0xFFE0E0E0;

        /** Vanilla EditBox hint color (-8421505 = 0xFF7F7F7F). */
        private static final int COLOR_HINT = 0xFF7F7F7F;

        /** Vanilla cursor color 0xFFD0D0D0. */
        private static final int COLOR_CURSOR = 0xFFD0D0D0;

        /** Vanilla blink half-period (isBlinked: millis / 500 % 2). */
        private static final long BLINK_HALF_PERIOD_MS = 500;

        /** Nine-slice border of the text_field sprites (.png.mcmeta: 1). */
        private static final int SPRITE_BORDER = 1;

        /** Horizontal padding vanilla puts inside the sprite (4px). */
        private static final int H_PADDING = 4;

        /** Internal scan codes (browser port mapKeyCode table). */
        private static final int KEY_BACKSPACE = 14;
        private static final int KEY_ENTER = 28;
        private static final int KEY_HOME = 199;
        private static final int KEY_END = 207;
        private static final int KEY_LEFT = 203;
        private static final int KEY_RIGHT = 205;
        private static final int KEY_DELETE = 211;

        private String value = "";
        private String hint = "";
        private boolean focused = false;
        private int cursorPos = 0;
        private int displayPos = 0;

        public EditBox(int x, int y, int width, int height, String hint) {
                super(x, y, width, height);
                this.hint = hint != null ? hint : "";
        }

        // ===== Value =====

        public String getValue() {
                return value;
        }

        public void setValue(String newValue) {
                value = newValue != null ? newValue : "";
                if (cursorPos > value.length()) {
                        cursorPos = value.length();
                }
                this.displayPos = scrollToShowCursor();
        }

        public void setHint(String hint) {
                this.hint = hint != null ? hint : "";
        }

        public void setFocused(boolean focused) {
                this.focused = focused;
                if (focused) {
                        this.cursorPos = value.length();
                        this.displayPos = scrollToShowCursor();
                }
        }

        public boolean isFocused() {
                return focused;
        }

        // ===== Input =====

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (!visible || !active || button != 0) return false;
                if (isInside(mouseX, mouseY)) {
                        this.focused = true;
                        // Cursor to the end of the text (vanilla positions it
                        // at the click point; the approximation is invisible
                        // for the short world names/seed this screen holds)
                        this.cursorPos = value.length();
                        this.displayPos = scrollToShowCursor();
                        onClick();
                        return true;
                }
                return false;
        }

        @Override
        protected void onClick() {
                // focus handled in mouseClicked; nothing else to do
        }

        /**
         * Inserts a printable character at the cursor. Returns true when
         * consumed (this field is focused).
         */
        public boolean charTyped(char c) {
                if (!focused) return false;
                if (c >= 32 && c != 127) {
                        value = value.substring(0, cursorPos) + c
                                        + value.substring(cursorPos);
                        cursorPos++;
                        displayPos = scrollToShowCursor();
                        return true;
                }
                return false;
        }

        /**
         * Control-key editing. Returns true when the key was consumed
         * (this field is focused). Codes are the browser port's
         * scan-code-style table.
         */
        public boolean keyPressed(int keyCode) {
                if (!focused) return false;
                switch (keyCode) {
                        case KEY_BACKSPACE:
                                if (cursorPos > 0) {
                                        value = value.substring(0, cursorPos - 1)
                                                        + value.substring(cursorPos);
                                        cursorPos--;
                                        displayPos = scrollToShowCursor();
                                }
                                return true;
                        case KEY_DELETE:
                                if (cursorPos < value.length()) {
                                        value = value.substring(0, cursorPos)
                                                        + value.substring(cursorPos + 1);
                                        displayPos = scrollToShowCursor();
                                }
                                return true;
                        case KEY_LEFT:
                                if (cursorPos > 0) cursorPos--;
                                displayPos = scrollToShowCursor();
                                return true;
                        case KEY_RIGHT:
                                if (cursorPos < value.length()) cursorPos++;
                                displayPos = scrollToShowCursor();
                                return true;
                        case KEY_HOME:
                                cursorPos = 0;
                                displayPos = scrollToShowCursor();
                                return true;
                        case KEY_END:
                                cursorPos = value.length();
                                displayPos = scrollToShowCursor();
                                return true;
                        case KEY_ENTER:
                                return true; // consumed, no-op
                        default:
                                return false;
                }
        }

        // ===== Rendering =====

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                if (!visible) return;
                this.hovered = active && isInside(mouseX, mouseY);

                // Vanilla EditBox: the highlighted sprite while focused
                String sprite = focused ? "widget/text_field_highlighted"
                                : "widget/text_field";
                if (!EaglerGuiImpl.drawSprite9Slices(sprite, x, y, width, height,
                                SPRITE_BORDER, SPRITE_BORDER, SPRITE_BORDER, SPRITE_BORDER)) {
                        // Pre-sprite fallback: dark panel + gray border
                        EaglerGuiImpl.fillRect(x, y, width, height, 0xFF000000);
                        EaglerGuiImpl.fillRect(x + 1, y + 1, width - 2, height - 2,
                                        focused ? 0xFF000000 : 0xFF8B8B8B);
                }

                int textY = y + (height - EaglerFont.GLYPH_HEIGHT) / 2;

                if (value.isEmpty() && !focused && !hint.isEmpty()) {
                        EaglerGuiImpl.drawText(hint, x + H_PADDING, textY, COLOR_HINT, false);
                        return;
                }

                // Horizontal scroll: keep the cursor inside the visible box
                int visibleWidth = width - H_PADDING * 2;
                String visible = visibleSubstring();
                EaglerGuiImpl.drawText(visible, x + H_PADDING, textY, COLOR_TEXT, false);

                // Blinking cursor at the insertion point
                if (focused && isBlinked()) {
                        int beforeCursor = visibleWidthBeforeCursor();
                        int cursorX = x + H_PADDING + beforeCursor;
                        if (cursorX >= x + H_PADDING && cursorX <= x + width - H_PADDING) {
                                EaglerGuiImpl.fillRect(cursorX, textY,
                                                1, EaglerFont.GLYPH_HEIGHT, COLOR_CURSOR);
                        }
                }
        }

        /** Vanilla EditBox.isBlinked: (millis - focus change) / 500 % 2 == 0. */
        private boolean isBlinked() {
                return (System.currentTimeMillis() / BLINK_HALF_PERIOD_MS) % 2 == 0;
        }

        /** The substring that fits between the paddings at the current scroll. */
        private String visibleSubstring() {
                if (displayPos >= value.length()) return "";
                int startChar = charAtOffset(displayPos);
                StringBuilder sb = new StringBuilder();
                int used = 0;
                for (int i = startChar; i < value.length(); i++) {
                        int charW = EaglerGuiImpl.widthOf(String.valueOf(value.charAt(i)));
                        if (used + charW > visibleWidth()) break;
                        sb.append(value.charAt(i));
                        used += charW;
                }
                return sb.toString();
        }

        /** Pixel offset from the visible text start to the cursor. */
        private int visibleWidthBeforeCursor() {
                int startChar = charAtOffset(displayPos);
                int used = 0;
                for (int i = startChar; i < cursorPos && i < value.length(); i++) {
                        used += EaglerGuiImpl.widthOf(String.valueOf(value.charAt(i)));
                }
                return used;
        }

        private int visibleWidth() {
                return width - H_PADDING * 2;
        }

        /** Index of the character at the given pixel offset into the value. */
        private int charAtOffset(int offsetPx) {
                if (offsetPx <= 0) return 0;
                int used = 0;
                for (int i = 0; i < value.length(); i++) {
                        if (used >= offsetPx) return i;
                        used += EaglerGuiImpl.widthOf(String.valueOf(value.charAt(i)));
                }
                return value.length();
        }

        /**
         * Vanilla displayPos scrolling: scroll right so the cursor is
         * visible, scroll left when the cursor would sit before the
         * visible window.
         */
        private int scrollToShowCursor() {
                int cursorOffset = 0;
                for (int i = 0; i < cursorPos && i < value.length(); i++) {
                        cursorOffset += EaglerGuiImpl.widthOf(String.valueOf(value.charAt(i)));
                }
                if (cursorOffset - displayPos > visibleWidth()) {
                        displayPos = cursorOffset - visibleWidth();
                }
                if (cursorOffset - displayPos < 0) {
                        displayPos = cursorOffset;
                }
                int totalWidth = EaglerGuiImpl.widthOf(value);
                if (totalWidth - displayPos < visibleWidth()) {
                        displayPos = Math.max(0, totalWidth - visibleWidth());
                }
                return displayPos;
        }
}
