package net.minecraft.client;

import net.lax1dude.eaglercraft.v2_6.EaglerProfile;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.Options.
 * Game settings/options stored via localStorage through EaglerProfile.
 * Many MC options don't apply in the browser (e.g., fullscreen, vsync is browser-managed).
 */
public class Options {

        // ========== Rendering Options ==========
        public int renderDistance = EaglerProfile.getRenderDistance();
        public int fov = EaglerProfile.getFOV();
        public double gamma = 0.5;
        public boolean fancyGraphics = EaglerProfile.isFancyGraphics();
        public boolean smoothLighting = EaglerProfile.isSmoothLighting();
        public int mipmapLevels = EaglerProfile.getMipmapLevels();
        public boolean ambientOcclusion = EaglerProfile.isSmoothLighting();
        public int cloudsOption = 0; // 0=off, 1=fast, 2=fancy
        public boolean particles = true;
        public boolean entityShadows = true;
        public int graphicsMode = 0; // 0=fast, 1=fancy, 2=fabulous

        // ========== Sound Options ==========
        public float masterVolume = EaglerProfile.getMasterVolume();
        public float musicVolume = EaglerProfile.getMusicVolume();
        public float soundVolume = EaglerProfile.getSFXVolume();
        public float ambientVolume = EaglerProfile.getAmbientVolume();

        // ========== Chat Options ==========
        public float chatScale = 1.0f;
        public float chatWidth = 1.0f;
        public float chatHeightUnfocused = 0.5f;
        public float chatHeightFocused = 1.0f;
        public float chatOpacity = 1.0f;
        public boolean chatColors = true;
        public boolean chatLinks = true;
        public boolean chatLinksPrompt = true;
        public boolean reducedDebugInfo = false;
        public boolean showSubtitles = false;

        // ========== Skin/Customization ==========
        public boolean showCape = true;
        public int mainHand = 1; // 0=left, 1=right

        // ========== Language ==========
        public String languageCode = EaglerProfile.getLang();

        // ========== Browser-irrelevant Options ==========
        public boolean fullscreen = false;
        public boolean enableVsync = EaglerProfile.isVsync();
        public boolean rawMouseInput = true;
        public int framerateLimit = 260;
        public boolean hudHidden = false;
        public boolean heldItemTooltips = EaglerProfile.isHeldItemTooltips();
        public boolean showFPS = EaglerProfile.isShowFPS();
        public boolean autosave = true;

        // ========== Key Bindings (stub) ==========
        public int keyForward = 87;       // W
        public int keyLeft = 65;          // A
        public int keyBack = 83;          // S
        public int keyRight = 68;         // D
        public int keyJump = 32;          // Space
        public int keySneak = 340;        // Left Shift
        public int keySprint = 341;       // Left Ctrl
        public int keyInventory = 69;     // E
        public int keyDrop = 81;          // Q
        public int keyUseItem = 345;      // Right Ctrl
        public int keyAttack = 348;       // Mouse 0 (handled separately)
        public int keyPickItem = 261;     // Middle click
        public int keyChat = 84;          // T
        public int keyCommand = 191;      // /
        public int keyTogglePerspective = 294; // F5
        public int keyScreenshot = 294;   // F2
        public int keyFullscreen = 300;   // F11
        public int keySmoothCamera = 295; // F4
        public int keyPlayerList = 258;   // Tab

        /** Whether options have been modified since last save. */
        private boolean dirty = false;

        /** The Minecraft instance that owns these options. */
        private final Minecraft minecraft;

        public Options(Minecraft minecraft, java.io.File file) {
                this.minecraft = minecraft;
                load();
        }

        /**
         * Loads options from localStorage via EaglerProfile.
         */
        public void load() {
                try {
                        // Sync from EaglerProfile (which handles localStorage)
                        this.renderDistance = EaglerProfile.getRenderDistance();
                        this.fov = EaglerProfile.getFOV();
                        this.fancyGraphics = EaglerProfile.isFancyGraphics();
                        this.smoothLighting = EaglerProfile.isSmoothLighting();
                        this.mipmapLevels = EaglerProfile.getMipmapLevels();
                        this.masterVolume = EaglerProfile.getMasterVolume();
                        this.musicVolume = EaglerProfile.getMusicVolume();
                        this.soundVolume = EaglerProfile.getSFXVolume();
                        this.ambientVolume = EaglerProfile.getAmbientVolume();
                        this.languageCode = EaglerProfile.getLang();
                        this.enableVsync = EaglerProfile.isVsync();
                        this.heldItemTooltips = EaglerProfile.isHeldItemTooltips();
                        this.showFPS = EaglerProfile.isShowFPS();

                        // Load key bindings from localStorage if available
                        String val;
                        val = PlatformRuntime.localStorageGet("options.gamma");
                        if (val != null) this.gamma = Double.parseDouble(val);

                        val = PlatformRuntime.localStorageGet("options.clouds");
                        if (val != null) this.cloudsOption = Integer.parseInt(val);

                        val = PlatformRuntime.localStorageGet("options.chatScale");
                        if (val != null) this.chatScale = Float.parseFloat(val);

                        val = PlatformRuntime.localStorageGet("options.chatWidth");
                        if (val != null) this.chatWidth = Float.parseFloat(val);

                        val = PlatformRuntime.localStorageGet("options.mainHand");
                        if (val != null) this.mainHand = Integer.parseInt(val);

                        ClientMain.log("[Options] Loaded from localStorage");
                } catch (Exception e) {
                        ClientMain.warn("[Options] Failed to load: " + e.getMessage());
                }
        }

        /**
         * Saves options to localStorage via EaglerProfile.
         */
        public void save() {
                try {
                        // Sync to EaglerProfile (which persists to localStorage)
                        EaglerProfile.setRenderDistance(this.renderDistance);
                        EaglerProfile.setFOV(this.fov);
                        EaglerProfile.setFancyGraphics(this.fancyGraphics);
                        EaglerProfile.setSmoothLighting(this.smoothLighting);
                        EaglerProfile.setMipmapLevels(this.mipmapLevels);
                        EaglerProfile.setMasterVolume(this.masterVolume);
                        EaglerProfile.setMusicVolume(this.musicVolume);
                        EaglerProfile.setSFXVolume(this.soundVolume);
                        EaglerProfile.setAmbientVolume(this.ambientVolume);
                        EaglerProfile.setLang(this.languageCode);
                        EaglerProfile.setVsync(this.enableVsync);
                        EaglerProfile.setHeldItemTooltips(this.heldItemTooltips);
                        EaglerProfile.setShowFPS(this.showFPS);

                        // Save additional options
                        PlatformRuntime.localStorageSet("options.gamma", String.valueOf(this.gamma));
                        PlatformRuntime.localStorageSet("options.clouds", String.valueOf(this.cloudsOption));
                        PlatformRuntime.localStorageSet("options.chatScale", String.valueOf(this.chatScale));
                        PlatformRuntime.localStorageSet("options.chatWidth", String.valueOf(this.chatWidth));
                        PlatformRuntime.localStorageSet("options.mainHand", String.valueOf(this.mainHand));

                        EaglerProfile.save();
                        dirty = false;

                        ClientMain.log("[Options] Saved to localStorage");
                } catch (Exception e) {
                        ClientMain.warn("[Options] Failed to save: " + e.getMessage());
                }
        }

        // ========== Getters/Setters ==========

        public int getRenderDistance() {
                return renderDistance;
        }

        public void setRenderDistance(int renderDistance) {
                this.renderDistance = Math.max(2, Math.min(16, renderDistance));
                this.dirty = true;
        }

        public int getFov() {
                return fov;
        }

        public void setFov(int fov) {
                this.fov = Math.max(30, Math.min(110, fov));
                this.dirty = true;
        }

        public double getGamma() {
                return gamma;
        }

        public void setGamma(double gamma) {
                this.gamma = Math.max(0.0, Math.min(1.0, gamma));
                this.dirty = true;
        }

        public float getMasterVolume() {
                return masterVolume;
        }

        public void setMasterVolume(float volume) {
                this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
                this.dirty = true;
        }

        public float getMusicVolume() {
                return musicVolume;
        }

        public void setMusicVolume(float volume) {
                this.musicVolume = Math.max(0.0f, Math.min(1.0f, volume));
                this.dirty = true;
        }

        public float getSoundVolume() {
                return soundVolume;
        }

        public void setSoundVolume(float volume) {
                this.soundVolume = Math.max(0.0f, Math.min(1.0f, volume));
                this.dirty = true;
        }

        public String getLanguageCode() {
                return languageCode;
        }

        public void setLanguageCode(String code) {
                this.languageCode = code;
                this.dirty = true;
        }

        public boolean isFancyGraphics() {
                return fancyGraphics;
        }

        public void setFancyGraphics(boolean fancy) {
                this.fancyGraphics = fancy;
                this.dirty = true;
        }

        public boolean isSmoothLighting() {
                return smoothLighting;
        }

        public void setSmoothLighting(boolean smooth) {
                this.smoothLighting = smooth;
                this.dirty = true;
        }

        /**
         * Returns the key code for a given key binding name.
         */
        public int getKey(String keyName) {
                switch (keyName) {
                        case "key.forward": return keyForward;
                        case "key.left": return keyLeft;
                        case "key.back": return keyBack;
                        case "key.right": return keyRight;
                        case "key.jump": return keyJump;
                        case "key.sneak": return keySneak;
                        case "key.sprint": return keySprint;
                        case "key.inventory": return keyInventory;
                        case "key.drop": return keyDrop;
                        case "key.use": return keyUseItem;
                        case "key.attack": return keyAttack;
                        case "key.pickItem": return keyPickItem;
                        case "key.chat": return keyChat;
                        case "key.command": return keyCommand;
                        default: return -1;
                }
        }

        /**
         * Sets a key binding.
         */
        public void setKey(String keyName, int keyCode) {
                switch (keyName) {
                        case "key.forward": keyForward = keyCode; break;
                        case "key.left": keyLeft = keyCode; break;
                        case "key.back": keyBack = keyCode; break;
                        case "key.right": keyRight = keyCode; break;
                        case "key.jump": keyJump = keyCode; break;
                        case "key.sneak": keySneak = keyCode; break;
                        case "key.sprint": keySprint = keyCode; break;
                        case "key.inventory": keyInventory = keyCode; break;
                        case "key.drop": keyDrop = keyCode; break;
                        case "key.use": keyUseItem = keyCode; break;
                        case "key.attack": keyAttack = keyCode; break;
                        case "key.pickItem": keyPickItem = keyCode; break;
                        case "key.chat": keyChat = keyCode; break;
                        case "key.command": keyCommand = keyCode; break;
                }
                dirty = true;
        }

        public boolean isDirty() {
                return dirty;
        }

        @Override
        public String toString() {
                return "Options{renderDist=" + renderDistance + ", fov=" + fov
                        + ", fancyGfx=" + fancyGraphics + ", lang=" + languageCode + "}";
        }

}
