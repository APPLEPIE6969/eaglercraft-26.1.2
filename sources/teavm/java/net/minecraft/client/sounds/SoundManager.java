package net.minecraft.client.sounds;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformAudio;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.sounds.SoundManager.
 * Audio manager stub that delegates to PlatformAudio for Web Audio API playback.
 * Most methods are no-ops until the full MC sound system is integrated.
 */
public class SoundManager {

        public SoundManager() {
                ClientMain.log("[SoundManager] Initialized (Web Audio API stub)");
        }

        /**
         * Plays a sound. Delegates to PlatformAudio.
         */
        public void play(Object sound) {
                // TODO: Map MC sound events to PlatformAudio play calls
        }

        /**
         * Stops a sound.
         */
        public void stop(Object sound) {
                // no-op
        }

        /**
         * Stops all sounds.
         */
        public void stop() {
                PlatformAudio.stopAllSounds();
        }

        /**
         * Called every tick.
         */
        public void tick() {
                // Tick audio engine - PlatformAudio doesn't have a tick method yet
        }

        /**
         * Returns whether a sound is currently playing.
         */
        public boolean isCurrentlyPlaying(Object sound) {
                return false;
        }

        /**
         * Sets the master volume.
         */
        public void setMasterVolume(float volume) {
                PlatformAudio.setMasterVolume(volume);
        }

        /**
         * Sets the category volume.
         */
        public void setCategoryVolume(String category, float volume) {
                switch (category) {
                        case "master":
                                PlatformAudio.setMasterVolume(volume);
                                break;
                        case "music":
                                PlatformAudio.setMusicVolume(volume);
                                break;
                        case "ambient":
                                // No ambient volume channel in PlatformAudio yet, use SFX
                                PlatformAudio.setSFXVolume(volume);
                                break;
                        default:
                                PlatformAudio.setSFXVolume(volume);
                                break;
                }
        }

        /**
         * Returns the master volume.
         */
        public float getMasterVolume() {
                return PlatformAudio.getMasterVolume();
        }

        /**
         * Updates the audio engine.
         */
        public void update() {
                // Audio engine update - no-op for now
        }

        /**
         * Called when the sound system should reload.
         */
        public void reload() {
                // no-op - sounds are loaded on demand via PlatformAudio
        }

        /**
         * Returns whether the sound system is available.
         */
        public boolean isAvailable() {
                return PlatformAudio.isAvailable();
        }

        /**
         * Called when the game is shutting down.
         */
        public void close() {
                PlatformAudio.stopAllSounds();
        }
}
