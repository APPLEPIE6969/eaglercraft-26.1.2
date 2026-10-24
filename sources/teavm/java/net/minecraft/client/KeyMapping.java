package net.minecraft.client;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v2_6.internal.PlatformInput;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.KeyMapping.
 *
 * <p>Vanilla semantics: every game action owns one KeyMapping with a
 * stable name ("key.forward"), a {@link Category} and a bound key; the
 * bind table is static (vanilla constructs them once in Minecraft and
 * everything polls the same instances). The browser port binds
 * <b>scan codes</b> - the PlatformInput key table (SET-1 style:
 * W=17, A=30, S=31, D=32, Space=57, LShift=42, LCtrl=29, E=18, Q=16,
 * digits 1..9 = 2..10, Escape=1) - not GLFW virtual keycodes.</p>
 *
 * <p>{@link #isDown} polls the live keyboard state each call, exactly
 * like vanilla's InputConstants polling (vanilla caches isDown per
 * KeyMapping and refreshes in {@code Minecraft.setKeyBindings}; the
 * poll is equivalent because the port's KeyboardHandler state is
 * authoritative between events). {@link #clickCount} mirrors vanilla's
 * click counter for the toggle-style binds.</p>
 *
 * <p>Rebinding resolves conflicts the vanilla way: assigning a key
 * that another KeyMapping already holds sets the other one unbound
 * (rendered as "???"). {@link #resetAll} restores every default;
 * {@link #saveBinds}/{@link #loadBinds} persist the table to
 * localStorage as {@code options.keybinds} ("name=code" entries).</p>
 *
 * <p>Mouse operations (attack/use/pickItem) stay wired to the physical
 * mouse buttons in MouseHandler - the browser gameplay layer does not
 * consume those KeyMappings, so the Key Binds screen lists them as
 * disabled rows (their real binding, "Button N", is not editable
 * without a mouse-capture path).</p>
 */
public class KeyMapping {

        /** Vanilla KeyMapping.Category - the Key Binds list groups rows by these. */
        public enum Category {
                MOVEMENT, GAMEPLAY, INVENTORY, INTERFACE, CREATIVE,
                MULTIPLAYER, MISC;

                /** Vanilla sorts categories in declaration order. */
                public static final Category[] SORTED = values();
        }

        /** All registered binds in registration order (vanilla ALL list). */
        private static final List<KeyMapping> ALL = new ArrayList<KeyMapping>();

        // ===== Port scan codes (PlatformInput SET-1 style table) =====
        public static final int SCAN_ESCAPE = 1;
        public static final int SCAN_1 = 2;
        public static final int SCAN_9 = 10;
        public static final int SCAN_Q = 16;
        public static final int SCAN_W = 17;
        public static final int SCAN_E = 18;
        public static final int SCAN_T = 20;
        public static final int SCAN_A = 30;
        public static final int SCAN_S = 31;
        public static final int SCAN_D = 32;
        public static final int SCAN_SLASH = 43;       // backslash/ISO (26.1.2 key.command alt)
        public static final int SCAN_TAB = 15;
        public static final int SCAN_LCTRL = 29;
        public static final int SCAN_ENTER = 28;
        public static final int SCAN_LSHIFT = 42;
        public static final int SCAN_SPACE = 57;
        public static final int SCAN_F2 = 60;          // F2 (SET-1: F1=59, F2=60)
        public static final int SCAN_F5 = 63;
        public static final int SCAN_F11 = 87;

        /** The registered binds, by vanilla name. */
        public static final KeyMapping FORWARD = register("key.forward", Category.MOVEMENT, SCAN_W);
        public static final KeyMapping LEFT = register("key.left", Category.MOVEMENT, SCAN_A);
        public static final KeyMapping BACK = register("key.back", Category.MOVEMENT, SCAN_S);
        public static final KeyMapping RIGHT = register("key.right", Category.MOVEMENT, SCAN_D);
        public static final KeyMapping JUMP = register("key.jump", Category.MOVEMENT, SCAN_SPACE);
        public static final KeyMapping SNEAK = register("key.sneak", Category.MOVEMENT, SCAN_LSHIFT);
        public static final KeyMapping SPRINT = register("key.sprint", Category.MOVEMENT, SCAN_LCTRL);

        public static final KeyMapping INVENTORY = register("key.inventory", Category.INVENTORY, SCAN_E);
        public static final KeyMapping DROP = register("key.drop", Category.INVENTORY, SCAN_Q);
        public static final KeyMapping HOTBAR_1 = register("key.hotbar.1", Category.INVENTORY, SCAN_1);
        public static final KeyMapping HOTBAR_2 = register("key.hotbar.2", Category.INVENTORY, 3);
        public static final KeyMapping HOTBAR_3 = register("key.hotbar.3", Category.INVENTORY, 4);
        public static final KeyMapping HOTBAR_4 = register("key.hotbar.4", Category.INVENTORY, 5);
        public static final KeyMapping HOTBAR_5 = register("key.hotbar.5", Category.INVENTORY, 6);
        public static final KeyMapping HOTBAR_6 = register("key.hotbar.6", Category.INVENTORY, 7);
        public static final KeyMapping HOTBAR_7 = register("key.hotbar.7", Category.INVENTORY, 8);
        public static final KeyMapping HOTBAR_8 = register("key.hotbar.8", Category.INVENTORY, 9);
        public static final KeyMapping HOTBAR_9 = register("key.hotbar.9", Category.INVENTORY, SCAN_9);

        public static final KeyMapping CHAT = register("key.chat", Category.INTERFACE, SCAN_T);
        public static final KeyMapping PLAYERLIST = register("key.playerlist", Category.INTERFACE, SCAN_TAB);
        public static final KeyMapping SCREENSHOT = register("key.screenshot", Category.INTERFACE, SCAN_F2);
        public static final KeyMapping TOGGLE_PERSPECTIVE =
                        register("key.togglePerspective", Category.INTERFACE, SCAN_F5);
        public static final KeyMapping FULLSCREEN = register("key.fullscreen", Category.INTERFACE, SCAN_F11);

        /** The bind this KeyMapping listens on (scan code, 0 = unbound). */
        private int key;
        /** Vanilla clickCount - bumped on each press edge while bound. */
        private int clickCount;
        /** Vanilla isDown cache, refreshed by poll(). */
        private boolean down;

        /** The default scan code (used by Reset All). */
        private final int defaultKey;

        private KeyMapping(String name, Category category, int defaultKey) {
                this.name = name;
                this.category = category;
                this.defaultKey = defaultKey;
                this.key = defaultKey;
        }

        private static KeyMapping register(String name, Category category, int defaultKey) {
                KeyMapping mapping = new KeyMapping(name, category, defaultKey);
                ALL.add(mapping);
                return mapping;
        }

        /** The vanilla bind name ("key.forward"). */
        public final String name;
        /** The category this bind groups under in the Key Binds list. */
        public final Category category;

        public int getKey() {
                return this.key;
        }

        /** The default bind (Reset All / conflict display). */
        public int getDefaultKey() {
                return this.defaultKey;
        }

        /**
         * Rebinds this mapping, resolving conflicts vanilla-style: any
         * other mapping holding the same key becomes unbound ("???").
         */
        public void setKey(int key) {
                if (key == this.key) {
                        return;
                }
                if (key != 0) {
                        for (int i = 0; i < ALL.size(); i++) {
                                KeyMapping other = ALL.get(i);
                                if (other != this && other.key == key) {
                                        other.key = 0;
                                        other.down = false;
                                }
                        }
                }
                this.key = key;
                this.down = false;
        }

        /** Restores the default bind (vanilla reset). */
        public void reset() {
                setKey(this.defaultKey);
        }

        /** Restores every mapping to its default (vanilla Reset Keys). */
        public static void resetAll() {
                for (int i = 0; i < ALL.size(); i++) {
                        ALL.get(i).reset();
                }
        }

        /** All registered binds in registration order. */
        public static List<KeyMapping> all() {
                return ALL;
        }

        /** Looks a mapping up by vanilla name (Options compat). */
        public static KeyMapping byName(String name) {
                for (int i = 0; i < ALL.size(); i++) {
                        if (ALL.get(i).name.equals(name)) {
                                return ALL.get(i);
                        }
                }
                return null;
        }

        /**
         * Whether the bound key is currently held (or was just pressed,
         * for toggles the gameplay layer consumes the click counter).
         * Unbound mappings are never down.
         */
        public boolean isDown() {
                if (this.key == 0) {
                        return false;
                }
                return PlatformInput.isKeyDown(this.key);
        }

        /**
         * Per-tick state refresh (vanilla Minecraft.setKeyBindings /
         * KeyMapping.click dance): detects press edges and bumps the
         * click counter. Call once per game tick for every mapping.
         */
        public void poll() {
                boolean nowDown = isDown();
                if (nowDown && !this.down) {
                        this.clickCount++;
                }
                this.down = nowDown;
        }

        /** Consumes one click (toggle binds use this). */
        public void consumeClick() {
                if (this.clickCount > 0) {
                        this.clickCount--;
                }
        }

        public int clickCount() {
                return this.clickCount;
        }

        /**
         * The first mapping currently holding clicks, vanilla
         * {@code KeyMapping.get("key.bind")} helper shape - the gameplay
         * layer uses it for toggle handling.
         */
        public static boolean isDown(String name) {
                KeyMapping mapping = byName(name);
                return mapping != null && mapping.isDown();
        }

        /**
         * Persists the whole bind table to localStorage as
         * {@code options.keybinds} ("name=code;name=code", unbound = 0).
         */
        public static void saveBinds() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ALL.size(); i++) {
                        KeyMapping mapping = ALL.get(i);
                        if (sb.length() > 0) {
                                sb.append(';');
                        }
                        sb.append(mapping.name).append('=').append(mapping.key);
                }
                PlatformRuntime.localStorageSet("options.keybinds", sb.toString());
                ClientMain.log("[KeyMapping] Saved " + ALL.size() + " binds");
        }

        /**
         * Restores the persisted bind table (missing names keep their
         * defaults; unknown codes are dropped).
         */
        public static void loadBinds() {
                String raw = PlatformRuntime.localStorageGet("options.keybinds");
                if (raw == null || raw.isEmpty()) {
                        return;
                }
                int loaded = 0;
                for (String entry : raw.split(";")) {
                        int eq = entry.indexOf('=');
                        if (eq <= 0) {
                                continue;
                        }
                        KeyMapping mapping = byName(entry.substring(0, eq));
                        if (mapping == null) {
                                continue;
                        }
                        try {
                                int key = Integer.parseInt(entry.substring(eq + 1));
                                if (key >= 0) {
                                        mapping.key = key;
                                        loaded++;
                                }
                        } catch (NumberFormatException ignored) {
                                // keep the default
                        }
                }
                ClientMain.log("[KeyMapping] Loaded " + loaded + " binds");
        }

        /**
         * The display label for a scan code, the port's SET-1 table
         * (vanilla resolves through GLFW's key names). Unbound renders
         * as "???" like vanilla's INPUT_INVALID.
         */
        public static String keyName(int key) {
                if (key == 0) {
                        return "???";
                }
                switch (key) {
                        case SCAN_ESCAPE: return "Esc";
                        case SCAN_TAB: return "Tab";
                        case SCAN_Q: return "Q";
                        case SCAN_W: return "W";
                        case SCAN_E: return "E";
                        case SCAN_T: return "T";
                        case SCAN_A: return "A";
                        case SCAN_S: return "S";
                        case SCAN_D: return "D";
                        case SCAN_SLASH: return "\\";
                        case SCAN_LCTRL: return "Left Ctrl";
                        case SCAN_LSHIFT: return "Left Shift";
                        case SCAN_SPACE: return "Space";
                        case SCAN_F2: return "F2";
                        case SCAN_F5: return "F5";
                        case SCAN_F11: return "F11";
                        default:
                                if (key >= SCAN_1 && key <= SCAN_9) {
                                        return String.valueOf(key - SCAN_1 + 1);
                                }
                                return "Key #" + key;
                }
        }

        /** This mapping's display key ("W", "Space", "???"). */
        public String keyName() {
                return keyName(this.key);
        }

        /**
         * The display label for the mouse buttons the gameplay layer
         * reads directly (disabled Key Binds rows).
         */
        public static String mouseButtonName(int button) {
                return "Button " + (button + 1);
        }
}
