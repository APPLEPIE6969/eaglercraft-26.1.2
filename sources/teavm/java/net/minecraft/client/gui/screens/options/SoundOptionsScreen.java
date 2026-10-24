package net.minecraft.client.gui.screens.options;

import com.mojang.blaze3d.vertex.PoseStack;

import net.lax1dude.eaglercraft.v2_6.adapter.EaglerGuiImpl;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;

/**
 * EaglerCraft 26.1.2 browser override for the MC
 * {@code SoundOptionsScreen} - the "Sound Settings" page.
 *
 * <p>Layout from the decompiled 26.1.2 screen: the full-width Master
 * slider, then the two-column grid of per-{@code SoundSource} category
 * sliders (Music, Records, Weather, Blocks, Hostile, Neutral, Players,
 * Ambient, Voice), the sound-device row, the Subtitles / Directional
 * Audio toggles, the music-frequency and music-toast rows, and the
 * Done footer:</p>
 *
 * <pre>
 *              Sound Settings
 *           [Master: 100%      ]
 *      [Music: 100%  ][Records: 100%]
 *      [Weather: 100%][Blocks: 100% ]
 *      [Hostile: 100%][Neutral: 100%]
 *      [Players: 100%][Ambient: 100%]
 *      [Voice: 100%  ][Sound Device...]
 *      [Subtitles: OFF][Directional Audio: OFF]
 *      [Music Frequency...][Music Toast...]
 *                [    Done    ]
 * </pre>
 *
 * <p>Master and the UI-affecting categories feed the live audio stack
 * (EaglerProfile volumes); the world-audio categories persist for
 * parity until the port gains world sound playback. The sound-device
 * row lists the browser's one output device and renders disabled,
 * vanilla-style.</p>
 */
public class SoundOptionsScreen extends Screen {

        private static final int TITLE_Y = 8;

        /** Vanilla option widget width (OptionInstance buttons are 150). */
        private static final int WIDGET_WIDTH = 150;

        /** Footer height (vanilla HeaderAndFooterLayout footer, 33px). */
        private static final int FOOTER_HEIGHT = 33;

        private static final int COLOR_TEXT = 0xFFFFFFFF;

        protected final Options options;
        protected final Screen lastScreen;

        public SoundOptionsScreen(Screen lastScreen, Options options) {
                this.lastScreen = lastScreen;
                this.options = options;
        }

        @Override
        protected void init() {
                int centerX = this.width / 2;
                int gridX = centerX - WIDGET_WIDTH - 4;
                int rightX = centerX + 4;
                int rowStep = Button.HEIGHT + 4;

                // ===== Master: full-width slider =====
                int rowY = TITLE_Y + 9 + 17;
                this.addRenderableWidget(new VolumeSlider(
                                centerX - WIDGET_WIDTH - 4, rowY, WIDGET_WIDTH * 2 + 8,
                                Button.HEIGHT, "Master", this.options.masterVolume,
                                v -> {
                                        this.options.setMasterVolume(v);
                                        this.options.save();
                                }, "mastervolume"));

                // ===== Category grid (2 columns, vanilla order) =====
                int row1Y = rowY + Button.HEIGHT + 8;
                this.addRenderableWidget(this.volumeSlider(gridX, row1Y, "Music",
                                this.options.musicVolume,
                                v -> {
                                        this.options.musicVolume = v;
                                        this.options.save();
                                }, "musicvolume"));
                this.addRenderableWidget(this.volumeSlider(rightX, row1Y, "Records",
                                this.options.recordVolume,
                                v -> {
                                        this.options.recordVolume = v;
                                        this.options.save();
                                }, "recordvolume"));

                int row2Y = row1Y + rowStep;
                this.addRenderableWidget(this.volumeSlider(gridX, row2Y, "Weather",
                                this.options.weatherVolume,
                                v -> {
                                        this.options.weatherVolume = v;
                                        this.options.save();
                                }, "weathervolume"));
                this.addRenderableWidget(this.volumeSlider(rightX, row2Y, "Blocks",
                                this.options.blockVolume,
                                v -> {
                                        this.options.blockVolume = v;
                                        this.options.save();
                                }, "blockvolume"));

                int row3Y = row2Y + rowStep;
                this.addRenderableWidget(this.volumeSlider(gridX, row3Y, "Hostile",
                                this.options.hostileVolume,
                                v -> {
                                        this.options.hostileVolume = v;
                                        this.options.save();
                                }, "hostilevolume"));
                this.addRenderableWidget(this.volumeSlider(rightX, row3Y, "Neutral",
                                this.options.neutralVolume,
                                v -> {
                                        this.options.neutralVolume = v;
                                        this.options.save();
                                }, "neutralvolume"));

                int row4Y = row3Y + rowStep;
                this.addRenderableWidget(this.volumeSlider(gridX, row4Y, "Players",
                                this.options.playerVolume,
                                v -> {
                                        this.options.playerVolume = v;
                                        this.options.save();
                                }, "playervolume"));
                this.addRenderableWidget(this.volumeSlider(rightX, row4Y, "Ambient",
                                this.options.ambientVolume,
                                v -> {
                                        this.options.ambientVolume = v;
                                        this.options.save();
                                }, "ambientvolume"));

                int row5Y = row4Y + rowStep;
                this.addRenderableWidget(this.volumeSlider(gridX, row5Y, "Voice",
                                this.options.voiceVolume,
                                v -> {
                                        this.options.voiceVolume = v;
                                        this.options.save();
                                }, "voicevolume"));

                // The browser has exactly one output device - disabled row
                Button device = new Button(rightX, row5Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Sound Device...", b -> { });
                device.setActive(false);
                this.addRenderableWidget(device);

                // ===== Subtitles / Directional Audio =====
                int row6Y = row5Y + rowStep;
                this.addRenderableWidget(new CycleButton(gridX, row6Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Subtitles",
                                new String[] { "OFF", "ON" },
                                this.options.showSubtitles ? 1 : 0,
                                (b, i) -> {
                                        this.options.showSubtitles = i == 1;
                                        this.options.save();
                                }));
                this.addRenderableWidget(new CycleButton(rightX, row6Y, WIDGET_WIDTH,
                                Button.HEIGHT, "Directional Audio",
                                new String[] { "OFF", "ON" },
                                this.options.directionalAudio ? 1 : 0,
                                (b, i) -> {
                                        this.options.directionalAudio = i == 1;
                                        this.options.save();
                                }));

                // ===== Music Frequency / Music Toast (disabled) =====
                int row7Y = row6Y + rowStep;
                this.addRenderableWidget(this.disabledButton(gridX, row7Y,
                                "Music Frequency..."));
                this.addRenderableWidget(this.disabledButton(rightX, row7Y,
                                "Music Toast..."));

                // ===== Footer: Done =====
                this.addRenderableWidget(new Button(centerX - 100,
                                this.height - FOOTER_HEIGHT + 6, 200, Button.HEIGHT,
                                "Done", button -> this.onClose()));
        }

        private VolumeSlider volumeSlider(int x, int y, String name, float initial,
                        VolumeSink sink, String telemetryId) {
                return new VolumeSlider(x, y, WIDGET_WIDTH, Button.HEIGHT, name,
                                initial, sink, telemetryId);
        }

        private Button disabledButton(int x, int y, String label) {
                Button button = new Button(x, y, WIDGET_WIDTH, Button.HEIGHT,
                                label, b -> { });
                button.setActive(false);
                return button;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
                EaglerGuiImpl.drawSpriteTiled("gui/menu_background",
                                0, 0, this.width, this.height, 0, 0, 32);

                int titleWidth = EaglerGuiImpl.widthOf("Sound Settings");
                EaglerGuiImpl.drawText("Sound Settings",
                                this.width / 2 - titleWidth / 2.0f, TITLE_Y, COLOR_TEXT, true);

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

        /** A volume row's write-through target. */
        interface VolumeSink {
                void accept(float value);
        }

        /**
         * A vanilla volume slider: 0..1 rendered as "Name: N%" with
         * vanilla's OFF at zero (OptionInstance.volume label contract).
         */
        static class VolumeSlider extends AbstractSliderButton {

                private final String name;
                private final VolumeSink sink;
                private final String telemetryId;

                VolumeSlider(int x, int y, int width, int height, String name,
                                float initial, VolumeSink sink, String telemetryId) {
                        super(x, y, width, height, initial);
                        this.name = name;
                        this.sink = sink;
                        this.telemetryId = telemetryId;
                }

                private static String label(String name, double value) {
                        if (value <= 0.0D) {
                                return name + ": OFF";
                        }
                        return name + ": " + (int) Math.round(value * 100.0D) + "%";
                }

                @Override
                protected String displayString() {
                        return label(this.name, this.value);
                }

                @Override
                protected void applyValue() {
                        this.sink.accept((float) this.value);
                }

                @Override
                public String getTelemetryId() {
                        return this.telemetryId;
                }
        }
}
