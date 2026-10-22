package net.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.gui.Font.
 * Minimal font renderer stub that provides approximate text width calculations.
 * Actual text rendering is handled by EaglerShaderImpl's text rendering pipeline.
 */
public class Font {

        /** Approximate character width in pixels for the default font. */
        private static final int CHAR_WIDTH = 6;

        /** Line height in pixels. */
        private static final int LINE_HEIGHT = 9;

        public Font() {
                // no initialization needed
        }

        /**
         * Draws text with a shadow. Returns the x position after drawing.
         */
        public int drawShadow(PoseStack poseStack, String text, float x, float y, int color) {
                // TODO: Delegate to EaglerShaderImpl text rendering
                return (int) (x + width(text));
        }

        /**
         * Draws text without shadow. Returns the x position after drawing.
         */
        public int draw(PoseStack poseStack, String text, float x, float y, int color) {
                // TODO: Delegate to EaglerShaderImpl text rendering
                return (int) (x + width(text));
        }

        /**
         * Draws text with shadow using raw string.
         */
        public int drawShadow(String text, float x, float y, int color) {
                return (int) (x + width(text));
        }

        /**
         * Draws text without shadow using raw string.
         */
        public int draw(String text, float x, float y, int color) {
                return (int) (x + width(text));
        }

        /**
         * Returns the width of the text in pixels.
         * Uses an approximation of 6 pixels per character.
         */
        public int width(String text) {
                if (text == null) return 0;
                int w = 0;
                for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c == '\u00a7') {
                                // Skip formatting codes
                                i++;
                                continue;
                        }
                        w += getCharWidth(c);
                }
                return w;
        }

        /**
         * Returns the width of a single character.
         */
        public int getCharWidth(char c) {
                if (c == ' ') return 4;
                return CHAR_WIDTH;
        }

        /**
         * Returns the line height.
         */
        public int lineHeight() {
                return LINE_HEIGHT;
        }

        /**
         * Returns the line height (alias).
         */
        public int getLineHeight() {
                return LINE_HEIGHT;
        }

        /**
         * Splits text to fit within the given width.
         */
        public String[] split(String text, int maxWidth) {
                if (text == null) return new String[0];
                if (width(text) <= maxWidth) return new String[] { text };

                // Simple word-wrap
                java.util.List<String> lines = new java.util.ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (String word : text.split(" ")) {
                        if (current.length() > 0 && width(current + " " + word) > maxWidth) {
                                lines.add(current.toString());
                                current = new StringBuilder(word);
                        } else {
                                if (current.length() > 0) current.append(" ");
                                current.append(word);
                        }
                }
                if (current.length() > 0) lines.add(current.toString());

                return lines.toArray(new String[0]);
        }

        /**
         * Draws text centered at the given x position.
         */
        public int drawCenteredShadow(PoseStack poseStack, String text, float x, float y, int color) {
                float textWidth = width(text);
                return drawShadow(poseStack, text, x - textWidth / 2.0f, y, color);
        }

        /**
         * Trims text to fit within the given width.
         */
        public String substrByWidth(String text, int maxWidth) {
                if (text == null) return "";
                int totalWidth = 0;
                for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c == '\u00a7') {
                                i++;
                                continue;
                        }
                        totalWidth += getCharWidth(c);
                        if (totalWidth > maxWidth) {
                                return text.substring(0, i);
                        }
                }
                return text;
        }

        /**
         * Returns the word-wrap height for the given text and max width.
         */
        public int wordWrapHeight(String text, int maxWidth) {
                return split(text, maxWidth).length * LINE_HEIGHT;
        }
}
