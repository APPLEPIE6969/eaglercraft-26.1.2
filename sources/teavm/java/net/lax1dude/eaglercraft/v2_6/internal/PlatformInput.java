package net.lax1dude.eaglercraft.v2_6.internal;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.TouchEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;

/**
 * Platform input implementation for EaglerCraft 26.1.2 on the TeaVM/browser platform.
 * Handles keyboard, mouse, touch, gamepad, and pointer lock input.
 *
 * <p>Key 26.1.2 improvements:</p>
 * <ul>
 *   <li>Pointer Lock API 2.0 with unadjustedMovement</li>
 *   <li>Full keyboard layout independence using event.code property</li>
 *   <li>IME composition event support (critical for CJK input)</li>
 *   <li>Gamepad API polling with standard mapping</li>
 *   <li>Touch events with pressure sensitivity</li>
 *   <li>Mouse wheel with deltaMode handling (pixel/line/page)</li>
 *   <li>Async Clipboard API (navigator.clipboard)</li>
 *   <li>Context menu suppression</li>
 * </ul>
 */
public class PlatformInput {

        private static final Window window = Window.current();
        private static HTMLCanvasElement canvas;

        // ========== Keyboard State ==========
        private static final int MAX_KEYCODE = 256;
        private static final boolean[] keyDown = new boolean[MAX_KEYCODE];
        private static final boolean[] keyDownOnce = new boolean[MAX_KEYCODE];

        /** Current keyboard event key string. */
        private static String currentKeyStr = null;

        /** Current keyboard event code string (layout-independent). */
        private static String currentKeyCode = null;

        /** Whether an IME composition is in progress. */
        private static boolean imeComposing = false;

        /** The composition text from IME. */
        private static String compositionText = "";

        // ========== Mouse State ==========
        private static final int MAX_MOUSEBUTTON = 8;
        private static final boolean[] mouseDown = new boolean[MAX_MOUSEBUTTON];

        /**
         * Press/release events latched by the DOM listeners. The game polls
         * input once per frame, and a fast click can complete (down + up)
         * between two frames - a pure state poll would never see it. The
         * listeners latch every transition here; EaglerCraft consumes the
         * latches once per frame via {@link #consumeMousePress} /
         * {@link #consumeMouseRelease}, preserving press-before-release
         * order for clicks that happen within a single frame.
         */
        private static final int[] mousePressedCount = new int[MAX_MOUSEBUTTON];
        private static final int[] mouseReleasedCount = new int[MAX_MOUSEBUTTON];

        /** Current mouse position in canvas coordinates. */
        private static int mouseX = 0;
        private static int mouseY = 0;

        /** Mouse delta from pointer lock movement. */
        private static int dx = 0;
        private static int dy = 0;

        /** Accumulated mouse wheel delta. */
        private static int dwheel = 0;

        /** Whether the pointer is currently locked. */
        private static boolean pointerLocked = false;

        // ========== Touch State ==========
        /** Maximum number of tracked touch points. */
        private static final int MAX_TOUCH_POINTS = 8;

        /** Touch point coordinates [pointIndex][0=x, 1=y, 2=pressure]. */
        private static final float[][] touchPoints = new float[MAX_TOUCH_POINTS][3];

        /** Whether each touch point is currently active. */
        private static final boolean[] touchActive = new boolean[MAX_TOUCH_POINTS];

        /** Number of active touch points. */
        private static int touchCount = 0;

        // ========== Gamepad State ==========
        /** Gamepad state: axes values. */
        private static final float[] gamepadAxes = new float[8];

        /** Gamepad state: button values. */
        private static final boolean[] gamepadButtons = new boolean[24];

        /** Whether a gamepad is connected. */
        private static boolean gamepadConnected = false;

        /** Index of the active gamepad. */
        private static int activeGamepadIndex = -1;

        // ========== Clipboard State ==========
        /** Clipboard text to paste. */
        private static String clipboardText = null;

        /** Whether a clipboard paste event has occurred. */
        private static boolean clipboardPasted = false;

        // ========== Event Types ==========
        private static final int EVENT_KEYBOARD = 1;
        private static final int EVENT_MOUSE = 2;
        private static final int EVENT_WHEEL = 3;
        private static final int EVENT_TOUCH = 4;
        private static final int EVENT_POINTER_LOCK = 5;

        /** Event queue for buffering input events. */
        private static final int MAX_EVENTS = 256;
        private static final int[] eventTypes = new int[MAX_EVENTS];
        private static final int[] eventKeys = new int[MAX_EVENTS];
        private static final String[] eventKeyStrs = new String[MAX_EVENTS];
        private static final int[] eventMouseButtons = new int[MAX_EVENTS];
        private static final int[] eventCoordsX = new int[MAX_EVENTS];
        private static final int[] eventCoordsY = new int[MAX_EVENTS];
        private static float[] eventWheels = new float[MAX_EVENTS];
        private static int eventCount = 0;

        /**
         * Initializes the input system. Called by ClientMain during startup.
         * Registers all DOM event listeners on the canvas element.
         */
        public static void _init() {
                canvas = ClientMain.getCanvas();
                if (canvas == null) {
                        throw new RuntimeException("Canvas is null during PlatformInput init!");
                }

                // Keyboard events
                registerKeyListeners();

                // Mouse events
                registerMouseListeners();

                // Touch events
                registerTouchListeners();

                // Wheel events
                registerWheelListener();

                // Pointer lock change
                registerPointerLockListener();

                // Gamepad events
                registerGamepadListeners();

                // Context menu suppression
                suppressContextMenu();

                // Prevent default for drag events
                suppressDragDrop();

                ClientMain.log("[PlatformInput] Input system initialized");
        }

        // ========== Event Listener Registration ==========

        /**
         * Observer for raw keyboard events, invoked synchronously from the
         * DOM keydown/keyup handlers. {@code keyCode} is the internal
         * scan-code-style code ({@code mapKeyCode}, -1 for unmapped keys)
         * and {@code keyStr} is the DOM {@code event.key} string - for
         * printable keys that is the actual character ("a", "A", "7"), for
         * control keys its name ("Backspace", "ArrowLeft", ...).
         */
        public interface KeyEventObserver {
                void onKeyEvent(int keyCode, String keyStr, boolean down);
        }

        private static KeyEventObserver keyEventObserver = null;

        /**
         * Browser-reserved shortcuts that must reach the browser: devtools
         * (F12, Ctrl/Cmd+Shift+I/J/C) and page reload (Ctrl/Cmd+R,
         * Ctrl/Cmd+Shift+R). The blanket {@code preventDefault()} used to
         * swallow these too, which made Ctrl+R and F12 dead keys on the
         * site - the game owns gameplay keys, but these combos belong to
         * the browser on every desktop platform. The events are still
         * delivered to the game below; only the browser-default
         * suppression is skipped. F5 stays captured (in-game use).
         */
        private static boolean isBrowserShortcut(KeyboardEvent event) {
                String code = event.getCode();
                if ("F12".equals(code)) {
                        return true;
                }
                boolean ctrlOrCmd = event.isCtrlKey() || event.isMetaKey();
                if (ctrlOrCmd && "KeyR".equals(code)) {
                        return true;
                }
                if (event.isCtrlKey() && event.isShiftKey()) {
                        return "KeyI".equals(code) || "KeyJ".equals(code)
                                        || "KeyC".equals(code);
                }
                return false;
        }

        /**
         * Installs the keyboard observer (the Minecraft instance routes
         * events into its KeyboardHandler / current screen). Safe to call
         * again; the latest observer wins.
         */
        public static void setKeyEventObserver(KeyEventObserver observer) {
                keyEventObserver = observer;
        }

        private static void registerKeyListeners() {
                // Keyboard is bound at DOCUMENT level, not the canvas: a
                // canvas element is never focusable, so with canvas-level
                // listeners the keydown target would be <body> and the game
                // would never see any key. Document-level listeners receive
                // every key regardless of focus (Eaglercraft 1.8 does the
                // same). Mouse listeners stay on the canvas.
                HTMLDocument document = window.getDocument();
                document.addEventListener("keydown", (EventListener<KeyboardEvent>) event -> {
                        if (imeComposing) return;
                        if (!isBrowserShortcut(event)) {
                                event.preventDefault();
                        }

                        String code = event.getCode();
                        int keyCode = mapKeyCode(code);

                        if (keyCode >= 0 && keyCode < MAX_KEYCODE) {
                                if (!keyDown[keyCode]) {
                                        keyDownOnce[keyCode] = true;
                                }
                                keyDown[keyCode] = true;
                        }

                        currentKeyStr = event.getKey();
                        currentKeyCode = code;

                        enqueueKeyEvent(keyCode, event.getKey(), code);
                        if (keyEventObserver != null) {
                                try {
                                        keyEventObserver.onKeyEvent(keyCode, event.getKey(), true);
                                } catch (Throwable t) {
                                        // input must never break the event loop
                                }
                        }
                });

                document.addEventListener("keyup", (EventListener<KeyboardEvent>) event -> {
                        if (imeComposing) return;
                        if (!isBrowserShortcut(event)) {
                                event.preventDefault();
                        }

                        String code = event.getCode();
                        int keyCode = mapKeyCode(code);

                        if (keyCode >= 0 && keyCode < MAX_KEYCODE) {
                                keyDown[keyCode] = false;
                        }

                        currentKeyStr = event.getKey();
                        currentKeyCode = code;
                        if (keyEventObserver != null) {
                                try {
                                        keyEventObserver.onKeyEvent(keyCode, event.getKey(), false);
                                } catch (Throwable t) {
                                        // input must never break the event loop
                                }
                        }
                });

                // IME composition events
                canvas.addEventListener("compositionstart", (EventListener<Event>) event -> {
                        imeComposing = true;
                        compositionText = "";
                });

                canvas.addEventListener("compositionupdate", (EventListener<Event>) event -> {
                        compositionText = getCompositionData(event);
                });

                canvas.addEventListener("compositionend", (EventListener<Event>) event -> {
                        imeComposing = false;
                        compositionText = getCompositionData(event);
                        // Insert the final composition text as if it were typed
                        currentKeyStr = compositionText;
                        currentKeyCode = "";
                });
        }

        private static void registerMouseListeners() {
                // Button events are recorded in BOTH modes: unlocked clicks
                // drive the GUI screens, pointer-locked clicks drive gameplay
                // (attack/use - vanilla MouseHandler.onPress while grabbed).
                // The latches funnel through EaglerCraft's poll loop into
                // MouseHandler.cursorPressed either way.
                canvas.addEventListener("mousedown", (EventListener<MouseEvent>) event -> {
                        event.preventDefault();

                        int button = event.getButton();
                        if (button >= 0 && button < MAX_MOUSEBUTTON) {
                                mouseDown[button] = true;
                                // count, don't overwrite: rapid clicks must
                                // queue for the following frames (a boolean
                                // latch drops them on slow machines)
                                if (mousePressedCount[button] < 8) mousePressedCount[button]++;
                        }
                });

                canvas.addEventListener("mouseup", (EventListener<MouseEvent>) event -> {
                        event.preventDefault();

                        int button = event.getButton();
                        if (button >= 0 && button < MAX_MOUSEBUTTON) {
                                mouseDown[button] = false;
                                if (mouseReleasedCount[button] < 8) mouseReleasedCount[button]++;
                        }
                });

                canvas.addEventListener("mousemove", (EventListener<MouseEvent>) event -> {
                        if (pointerLocked) {
                                // In pointer lock mode, use movementX/Y for delta
                                dx += getMovementX(event);
                                dy += getMovementY(event);
                        } else {
                                // Normal mode: track absolute position relative to canvas
                                int[] coords = getCanvasRelativeCoords(event);
                                mouseX = coords[0];
                                mouseY = coords[1];
                        }
                });
        }

        private static void registerTouchListeners() {
                canvas.addEventListener("touchstart", (EventListener<TouchEvent>) event -> {
                        event.preventDefault();
                        updateTouchState(event);
                });

                canvas.addEventListener("touchmove", (EventListener<TouchEvent>) event -> {
                        event.preventDefault();
                        updateTouchState(event);
                });

                canvas.addEventListener("touchend", (EventListener<TouchEvent>) event -> {
                        event.preventDefault();
                        updateTouchState(event);
                });

                canvas.addEventListener("touchcancel", (EventListener<TouchEvent>) event -> {
                        event.preventDefault();
                        clearTouchState();
                });
        }

        private static void registerWheelListener() {
                // Use native JS to register with { passive: false } so preventDefault() works
                registerWheelListener0(normalized -> __onWheel(normalized));
        }

        /**
         * Native method to register wheel listener with passive: false option.
         * Required for Chrome which makes wheel events passive by default.
         */
        @JSFunctor
        private interface WheelCallback extends JSObject {
                void call(double normalizedDelta);
        }

        @JSBody(params = { "callback" }, script = ""
                        + "var c = document.getElementById('EaglerCraftX_26_1_2_Canvas');"
                        + "if (c) {"
                        + "  c.addEventListener('wheel', function(e) {"
                        + "    e.preventDefault();"
                        + "    var mode = e.deltaMode || 0;"
                        + "    var raw = e.deltaY;"
                        + "    var normalized;"
                        + "    if (mode === 0) normalized = raw;"
                        + "    else if (mode === 1) normalized = raw * 40;"
                        + "    else normalized = raw * 800;"
                        + "    callback(normalized);"
                        + "  }, { passive: false });"
                        + "}")
        private static native void registerWheelListener0(WheelCallback callback);

        /** Callback from JavaScript when a wheel event occurs. */
        private static void __onWheel(double normalizedDelta) {
                dwheel += (int) normalizedDelta;
        }

        private static void registerPointerLockListener() {
                documentAddPointerLockChangeListener((event) -> {
                        if (isPointerLocked0()) {
                                pointerLocked = true;
                        } else {
                                pointerLocked = false;
                                dx = 0;
                                dy = 0;
                        }
                });
        }

        private static void registerGamepadListeners() {
                if (!ClientMain.gamepadSupported) return;

                windowAddGamepadConnectedListener((event) -> {
                        int index = getGamepadEventIndex(event);
                        if (activeGamepadIndex < 0) {
                                activeGamepadIndex = index;
                        }
                        gamepadConnected = true;
                        ClientMain.log("[PlatformInput] Gamepad connected: index=" + index);
                });

                windowAddGamepadDisconnectedListener((event) -> {
                        int index = getGamepadEventIndex(event);
                        if (index == activeGamepadIndex) {
                                activeGamepadIndex = -1;
                                gamepadConnected = false;
                                // Clear state
                                for (int i = 0; i < gamepadAxes.length; i++) gamepadAxes[i] = 0;
                                for (int i = 0; i < gamepadButtons.length; i++) gamepadButtons[i] = false;
                        }
                        ClientMain.log("[PlatformInput] Gamepad disconnected: index=" + index);
                });
        }

        private static void suppressContextMenu() {
                canvas.addEventListener("contextmenu", (EventListener<Event>) event -> {
                        event.preventDefault();
                });
        }

        private static void suppressDragDrop() {
                canvas.addEventListener("dragover", (EventListener<Event>) event -> {
                        event.preventDefault();
                });
                canvas.addEventListener("drop", (EventListener<Event>) event -> {
                        event.preventDefault();
                });
        }

        // ========== Gamepad Polling ==========

        /**
         * Polls the gamepad state. Should be called once per frame.
         */
        public static void pollGamepad() {
                if (!gamepadConnected || activeGamepadIndex < 0) return;
                pollGamepad0(activeGamepadIndex,
                        (index, value) -> __updateGamepadAxis(index, value),
                        (index, pressed) -> __updateGamepadButton(index, pressed));
        }

        // ========== Pointer Lock ==========

        /**
         * Requests pointer lock on the canvas element.
         */
        public static void requestPointerLock() {
                if (ClientMain.pointerLockSupported) {
                        requestPointerLock0();
                }
        }

        /**
         * Exits pointer lock mode.
         */
        public static void exitPointerLock() {
                exitPointerLock0();
        }

        /**
         * Checks if the pointer is currently locked.
         */
        public static boolean isPointerLocked() {
                return pointerLocked;
        }

        // ========== Clipboard ==========

        /**
         * Copies text to the clipboard using the async Clipboard API.
         */
        public static void setClipboardText(String text) {
                if (ClientMain.clipboardSupported) {
                        setClipboardText0(text);
                } else {
                        // Fallback using deprecated document.execCommand
                        setClipboardTextFallback(text);
                }
        }

        /**
         * Gets the clipboard text. Returns null if no text is available.
         */
        public static String getClipboardText() {
                if (ClientMain.clipboardSupported) {
                        getClipboardText0(text -> __onClipboardRead(text));
                }
                return clipboardText;
        }

        // ========== State Query Methods ==========

        /**
         * Checks if a key is currently held down.
         */
        public static boolean isKeyDown(int keyCode) {
                if (keyCode < 0 || keyCode >= MAX_KEYCODE) return false;
                return keyDown[keyCode];
        }

        /**
         * Checks if a key was pressed this frame (edge-triggered).
         * Resets after checking.
         */
        public static boolean isKeyDownOnce(int keyCode) {
                if (keyCode < 0 || keyCode >= MAX_KEYCODE) return false;
                boolean result = keyDownOnce[keyCode];
                keyDownOnce[keyCode] = false;
                return result;
        }

        /**
         * Gets the current mouse X position (canvas-relative).
         */
        public static int getMouseX() {
                return mouseX;
        }

        /**
         * Gets the current mouse Y position (canvas-relative).
         */
        public static int getMouseY() {
                return mouseY;
        }

        /**
         * Gets and clears the accumulated mouse DX from pointer lock.
         */
        public static int getMouseDX() {
                int result = dx;
                dx = 0;
                return result;
        }

        /**
         * Gets and clears the accumulated mouse DY from pointer lock.
         */
        public static int getMouseDY() {
                int result = dy;
                dy = 0;
                return result;
        }

        /**
         * Discards any accumulated pointer-lock deltas without applying
         * them (input hygiene: stale synthetic deltas must not fight an
         * explicit camera snap - vanilla look deltas always come from
         * real mouse motion).
         */
        public static void clearMouseDeltas() {
                dx = 0;
                dy = 0;
        }

        /**
         * Gets and clears the accumulated mouse wheel delta.
         */
        public static int getMouseDWheel() {
                int result = dwheel;
                dwheel = 0;
                return result;
        }

        /**
         * Checks if a mouse button is currently held down.
         */
        public static boolean isMouseDown(int button) {
                if (button < 0 || button >= MAX_MOUSEBUTTON) return false;
                return mouseDown[button];
        }

        /**
         * Consumes a latched button press: returns (and clears) whether a
         * mousedown transition for this button happened since the last
         * consumption. This survives clicks that complete entirely between
         * two game frames, which a plain isMouseDown poll would miss.
         *
         * <p>The latch is a per-button COUNT, not a boolean: a boolean
         * silently drops every click that arrives while the previous press
         * is still pending (slow machines / heavy frames coalesce rapid
         * clicks into one - the crafting e2e caught this), a counter queues
         * them for the following frames like GLFW's event queue.</p>
         */
        public static boolean consumeMousePress(int button) {
                if (button < 0 || button >= MAX_MOUSEBUTTON) return false;
                if (mousePressedCount[button] > 0) {
                        mousePressedCount[button]--;
                        return true;
                }
                return false;
        }

        /**
         * Consumes a latched button release (the same count semantics as
         * {@link #consumeMousePress}).
         */
        public static boolean consumeMouseRelease(int button) {
                if (button < 0 || button >= MAX_MOUSEBUTTON) return false;
                if (mouseReleasedCount[button] > 0) {
                        mouseReleasedCount[button]--;
                        return true;
                }
                return false;
        }

        /**
         * Gets the current keyboard event key string.
         */
        public static String getCurrentKeyStr() {
                return currentKeyStr;
        }

        /**
         * Gets the current keyboard event code string (layout-independent).
         */
        public static String getCurrentKeyCode() {
                return currentKeyCode;
        }

        /**
         * Checks if an IME composition is in progress.
         */
        public static boolean isIMEComposing() {
                return imeComposing;
        }

        /**
         * Gets the current IME composition text.
         */
        public static String getCompositionText() {
                return compositionText;
        }

        /**
         * Gets the number of active touch points.
         */
        public static int getTouchCount() {
                return touchCount;
        }

        /**
         * Gets the X coordinate of a touch point.
         */
        public static float getTouchX(int index) {
                if (index < 0 || index >= MAX_TOUCH_POINTS) return 0;
                return touchPoints[index][0];
        }

        /**
         * Gets the Y coordinate of a touch point.
         */
        public static float getTouchY(int index) {
                if (index < 0 || index >= MAX_TOUCH_POINTS) return 0;
                return touchPoints[index][1];
        }

        /**
         * Gets the pressure of a touch point.
         */
        public static float getTouchPressure(int index) {
                if (index < 0 || index >= MAX_TOUCH_POINTS) return 0;
                return touchPoints[index][2];
        }

        /**
         * Checks if a touch point is active.
         */
        public static boolean isTouchActive(int index) {
                if (index < 0 || index >= MAX_TOUCH_POINTS) return false;
                return touchActive[index];
        }

        /**
         * Gets a gamepad axis value.
         */
        public static float getGamepadAxis(int index) {
                if (index < 0 || index >= gamepadAxes.length) return 0;
                return gamepadAxes[index];
        }

        /**
         * Checks if a gamepad button is pressed.
         */
        public static boolean isGamepadButton(int index) {
                if (index < 0 || index >= gamepadButtons.length) return false;
                return gamepadButtons[index];
        }

        /**
         * Checks if a gamepad is connected.
         */
        public static boolean isGamepadConnected() {
                return gamepadConnected;
        }

        // ========== Frame Reset ==========

        /**
         * Resets per-frame input state. Called at the start of each game tick.
         */
        public static void frameReset() {
                eventCount = 0;
        }

        // ========== Event Queue ==========

        private static void enqueueKeyEvent(int keyCode, String keyStr, String code) {
                if (eventCount < MAX_EVENTS) {
                        eventTypes[eventCount] = EVENT_KEYBOARD;
                        eventKeys[eventCount] = keyCode;
                        eventKeyStrs[eventCount] = keyStr;
                        eventCount++;
                }
        }

        // ========== Native JS Methods ==========

        /**
         * Maps a keyboard event code string to an internal key code.
         * Uses the code property for layout independence.
         */
        @JSBody(params = { "code" }, script = ""
                        + "var map = {"
                        + "  'KeyA':30,'KeyB':48,'KeyC':46,'KeyD':32,'KeyE':18,'KeyF':33,'KeyG':34,'KeyH':35,"
                        + "  'KeyI':23,'KeyJ':36,'KeyK':37,'KeyL':38,'KeyM':50,'KeyN':49,'KeyO':24,'KeyP':25,"
                        + "  'KeyQ':16,'KeyR':19,'KeyS':31,'KeyT':20,'KeyU':22,'KeyV':47,'KeyW':17,'KeyX':45,"
                        + "  'KeyY':21,'KeyZ':44,"
                        + "  'Digit0':11,'Digit1':2,'Digit2':3,'Digit3':4,'Digit4':5,'Digit5':6,"
                        + "  'Digit6':7,'Digit7':8,'Digit8':9,'Digit9':10,"
                        + "  'Escape':1,'F1':59,'F2':60,'F3':61,'F4':62,'F5':63,'F6':64,'F7':65,'F8':66,"
                        + "  'F9':67,'F10':68,'F11':87,'F12':88,"
                        + "  'Backquote':41,'Minus':12,'Equal':13,'Backspace':14,'Tab':15,"
                        + "  'BracketLeft':26,'BracketRight':27,'Backslash':43,'Semicolon':39,'Quote':40,"
                        + "  'Enter':28,'CapsLock':58,"
                        + "  'ShiftLeft':42,'ShiftRight':54,'ControlLeft':29,'ControlRight':157,"
                        + "  'AltLeft':56,'AltRight':184,'MetaLeft':221,'MetaRight':220,"
                        + "  'Space':57,'ContextMenu':58,"
                        + "  'ArrowLeft':203,'ArrowUp':200,'ArrowRight':205,'ArrowDown':208,"
                        + "  'Insert':210,'Home':199,'PageUp':201,'Delete':211,'End':207,'PageDown':209,"
                        + "  'NumLock':69,'ScrollLock':70,'Pause':197,"
                        + "  'NumpadDivide':181,'NumpadMultiply':55,'NumpadSubtract':74,'NumpadAdd':78,"
                        + "  'NumpadEnter':156,'NumpadDecimal':83,"
                        + "  'Numpad0':82,'Numpad1':79,'Numpad2':80,'Numpad3':81,'Numpad4':75,"
                        + "  'Numpad5':76,'Numpad6':77,'Numpad7':71,'Numpad8':72,'Numpad9':73"
                        + "};"
                        + "return map[code] || -1;")
        private static native int mapKeyCode(String code);

        /**
         * Gets canvas-relative coordinates from a mouse event.
         */
        @JSBody(params = { "event" }, script = ""
                        + "var rect = event.target.getBoundingClientRect();"
                        + "var x = event.clientX - rect.left;"
                        + "var y = event.clientY - rect.top;"
                        + "var scaleX = event.target.clientWidth / rect.width;"
                        + "var scaleY = event.target.clientHeight / rect.height;"
                        + "return [Math.round(x * scaleX), Math.round(y * scaleY)];")
        private static native int[] getCanvasRelativeCoords(MouseEvent event);

        /**
         * Opens a URL in a new browser tab (vanilla {@code Util.getPlatform()
         * .openUri} / {@code Screen.openScreen} semantics for the pause
         * menu's "Send Feedback" and "Report Bugs" buttons).
         */
        public static void openURL(String url, String target) {
                openURLJS(url, target != null ? target : "_blank");
        }

        @JSBody(params = { "url", "target" }, script = "window.open(url, target);")
        private static native void openURLJS(String url, String target);

        /**
         * Gets movementX from a mouse event (pointer lock delta).
         */
        @JSBody(params = { "event" }, script = "return event.movementX || 0;")
        private static native int getMovementX(MouseEvent event);

        /**
         * Gets movementY from a mouse event (pointer lock delta).
         */
        @JSBody(params = { "event" }, script = "return event.movementY || 0;")
        private static native int getMovementY(MouseEvent event);

        /**
         * Gets the composition data from a composition event.
         */
        @JSBody(params = { "event" }, script = "return event.data || '';")
        private static native String getCompositionData(Event event);

        /**
         * Requests pointer lock on the canvas.
         */
        @JSBody(params = {}, script = ""
                        + "var c = document.getElementById('EaglerCraftX_26_1_2_Canvas');"
                        + "if (c && c.requestPointerLock) {"
                        + "  var r;"
                        + "  if (c.requestPointerLock.length > 0) {"
                        + "    r = c.requestPointerLock({ unadjustedMovement: true });"
                        + "  } else {"
                        + "    r = c.requestPointerLock();"
                        + "  }"
                        // Chrome 111+ returns a promise that REJECTS when the
                        // document lacks transient activation (e.g. the request
                        // comes from the game loop rather than the click event
                        // itself). Swallow the rejection - the pointer stays
                        // unlocked and the next real canvas click retries.
                        + "  if (r && typeof r.catch === 'function') r.catch(function() {});"
                        + "}")
        private static native void requestPointerLock0();

        /**
         * Exits pointer lock.
         */
        @JSBody(params = {}, script = ""
                        + "if (document.exitPointerLock) document.exitPointerLock();"
                        + "else if (document.mozExitPointerLock) document.mozExitPointerLock();")
        private static native void exitPointerLock0();

        /**
         * Checks if the pointer is currently locked.
         */
        @JSBody(params = {}, script = ""
                        + "return !!(document.pointerLockElement || document.mozPointerLockElement);")
        private static native boolean isPointerLocked0();

        /**
         * Adds a pointer lock change event listener to the document.
         */
        @JSBody(params = { "callback" }, script = ""
                        + "document.addEventListener('pointerlockchange', callback);"
                        + "document.addEventListener('mozpointerlockchange', callback);")
        private static native void documentAddPointerLockChangeListener(EventListener<Event> callback);

        /**
         * Sets the clipboard text using the async Clipboard API.
         */
        @JSBody(params = { "text" }, script = ""
                        + "if (navigator.clipboard && navigator.clipboard.writeText) {"
                        + "  navigator.clipboard.writeText(text).catch(function(e) {"
                        + "    console.warn('Clipboard write failed:', e);"
                        + "  });"
                        + "}")
        private static native void setClipboardText0(String text);

        /**
         * Sets the clipboard text using a fallback method.
         */
        @JSBody(params = { "text" }, script = ""
                        + "var ta = document.createElement('textarea');"
                        + "ta.value = text;"
                        + "ta.style.position = 'fixed';"
                        + "ta.style.left = '-9999px';"
                        + "document.body.appendChild(ta);"
                        + "ta.select();"
                        + "document.execCommand('copy');"
                        + "document.body.removeChild(ta);")
        private static native void setClipboardTextFallback(String text);

        /**
         * Reads clipboard text using the async Clipboard API.
         */
        @JSFunctor
        private interface ClipboardReadCallback extends JSObject {
                void call(String text);
        }

        @JSBody(params = { "callback" }, script = ""
                        + "if (navigator.clipboard && navigator.clipboard.readText) {"
                        + "  navigator.clipboard.readText().then(function(text) {"
                        + "    callback(text);"
                        + "  }).catch(function(e) {"
                        + "    console.warn('Clipboard read failed:', e);"
                        + "  });"
                        + "}")
        private static native void getClipboardText0(ClipboardReadCallback callback);

        /**
         * Callback from JavaScript when clipboard text is read.
         */
        private static void __onClipboardRead(String text) {
                clipboardText = text;
                clipboardPasted = true;
        }

        /**
         * Updates touch state from a TouchEvent.
         */
        @JSBody(params = { "event" }, script = ""
                        + "var touches = event.changedTouches;"
                        + "var result = [];"
                        + "for (var i = 0; i < touches.length; i++) {"
                        + "  var t = touches[i];"
                        + "  var rect = t.target.getBoundingClientRect();"
                        + "  var x = t.clientX - rect.left;"
                        + "  var y = t.clientY - rect.top;"
                        + "  var p = t.force || 0;"
                        + "  result.push({ id: t.identifier, x: x, y: y, pressure: p });"
                        + "}"
                        + "return result;")
        private static native JSObject extractTouchData(TouchEvent event);

        private static void updateTouchState(TouchEvent event) {
                // Reset touch state
                for (int i = 0; i < MAX_TOUCH_POINTS; i++) {
                        touchActive[i] = false;
                }
                touchCount = 0;
                updateTouchState0(event, (index, x, y, pressure) -> __updateTouchPoint(index, x, y, pressure));
        }

        @JSFunctor
        private interface TouchUpdateCallback extends JSObject {
                void call(int index, float x, float y, float pressure);
        }

        @JSBody(params = { "event", "callback" }, script = ""
                        + "var touches = event.touches;"
                        + "for (var i = 0; i < Math.min(touches.length, 8); i++) {"
                        + "  var t = touches[i];"
                        + "  var rect = t.target.getBoundingClientRect();"
                        + "  var scaleX = t.target.clientWidth / rect.width;"
                        + "  var scaleY = t.target.clientHeight / rect.height;"
                        + "  callback("
                        + "    i,"
                        + "    (t.clientX - rect.left) * scaleX,"
                        + "    (t.clientY - rect.top) * scaleY,"
                        + "    t.force || 1.0"
                        + "  );"
                        + "}")
        private static native void updateTouchState0(TouchEvent event, TouchUpdateCallback callback);

        private static void __updateTouchPoint(int index, float x, float y, float pressure) {
                if (index >= 0 && index < MAX_TOUCH_POINTS) {
                        touchPoints[index][0] = x;
                        touchPoints[index][1] = y;
                        touchPoints[index][2] = pressure;
                        touchActive[index] = true;
                        touchCount = Math.max(touchCount, index + 1);
                }
        }

        private static void clearTouchState() {
                for (int i = 0; i < MAX_TOUCH_POINTS; i++) {
                        touchActive[i] = false;
                        touchPoints[i][0] = 0;
                        touchPoints[i][1] = 0;
                        touchPoints[i][2] = 0;
                }
                touchCount = 0;
        }

        /**
         * Polls the gamepad state from JavaScript.
         */
        @JSFunctor
        private interface GamepadAxisCallback extends JSObject {
                void call(int index, float value);
        }

        @JSFunctor
        private interface GamepadButtonCallback extends JSObject {
                void call(int index, boolean pressed);
        }

        @JSBody(params = { "index", "axisCallback", "buttonCallback" }, script = ""
                        + "var gamepads = navigator.getGamepads();"
                        + "if (!gamepads || !gamepads[index]) return;"
                        + "var gp = gamepads[index];"
                        + "for (var i = 0; i < Math.min(gp.axes.length, 8); i++) {"
                        + "  axisCallback(i, gp.axes[i]);"
                        + "}"
                        + "for (var i = 0; i < Math.min(gp.buttons.length, 24); i++) {"
                        + "  buttonCallback(i, gp.buttons[i].pressed);"
                        + "}")
        private static native void pollGamepad0(int index, GamepadAxisCallback axisCallback, GamepadButtonCallback buttonCallback);

        private static void __updateGamepadAxis(int index, float value) {
                if (index >= 0 && index < gamepadAxes.length) {
                        gamepadAxes[index] = value;
                }
        }

        private static void __updateGamepadButton(int index, boolean pressed) {
                if (index >= 0 && index < gamepadButtons.length) {
                        gamepadButtons[index] = pressed;
                }
        }

        /**
         * Gets the gamepad index from a GamepadEvent.
         */
        @JSBody(params = { "event" }, script = "return event.gamepad.index;")
        private static native int getGamepadEventIndex(Event event);

        /**
         * Adds a gamepadconnected event listener.
         */
        @JSBody(params = { "callback" }, script = "window.addEventListener('gamepadconnected', callback);")
        private static native void windowAddGamepadConnectedListener(EventListener<Event> callback);

        /**
         * Adds a gamepaddisconnected event listener.
         */
        @JSBody(params = { "callback" }, script = "window.addEventListener('gamepaddisconnected', callback);")
        private static native void windowAddGamepadDisconnectedListener(EventListener<Event> callback);
}
