package net.minecraft.client.gui.components;

/**
 * EaglerCraft 26.1.2 browser override for the MC {@code CycleButton}:
 * a button whose label reads "{@code Prefix: Value}" and steps through
 * its value list on each left click, like vanilla
 * {@code CommonComponents.optionNameValue}.
 *
 * <p>Used for the CreateWorldScreen selectors (Game Mode / Difficulty /
 * Allow Cheats / World Type) and the SwitchGrid ON/OFF toggles (whose
 * value labels are just "ON"/"OFF" with an empty prefix).</p>
 */
public class CycleButton extends Button {

        /** Vanilla CommonComponents.OPTION_ON. */
        public static final String VALUE_ON = "ON";

        /** Vanilla CommonComponents.OPTION_OFF. */
        public static final String VALUE_OFF = "OFF";

        /** Callback mirroring vanilla CycleButton.OnValueChange. */
        public interface OnValueChange {
                void onChange(CycleButton button, int newIndex);
        }

        private final String prefix;
        private final String[] values;
        private int index;
        private OnValueChange onValueChange;

        /**
         * @param prefix the "{@code Game Mode}" part, empty for pure-value
         *               labels like the SwitchGrid ON/OFF buttons
         */
        public CycleButton(int x, int y, int width, int height, String prefix,
                        String[] values, int initialIndex, OnValueChange onValueChange) {
                super(x, y, width, height, compose(prefix, values, initialIndex), b -> { });
                this.prefix = prefix != null ? prefix : "";
                this.values = values != null && values.length > 0 ? values : new String[] { "" };
                this.index = Math.max(0, Math.min(initialIndex, this.values.length - 1));
                this.onValueChange = onValueChange;
        }

        private static String compose(String prefix, String[] values, int index) {
                String value = values != null && values.length > 0
                                ? values[Math.max(0, Math.min(index, values.length - 1))]
                                : "";
                return prefix == null || prefix.isEmpty() ? value : prefix + ": " + value;
        }

        /** The current value index. */
        public int getIndex() {
                return index;
        }

        /** The current value's display name. */
        public String getValue() {
                return values[index];
        }

        /**
         * Sets the value by index without firing the change callback (the
         * way vanilla setValue keeps external state in sync).
         */
        public void setIndex(int newIndex) {
                this.index = Math.max(0, Math.min(newIndex, values.length - 1));
                this.message = compose(prefix, values, index);
        }

        @Override
        protected void onClick() {
                // Vanilla: left click steps forward through the value list
                this.index = (this.index + 1) % values.length;
                this.message = compose(prefix, values, index);
                if (onValueChange != null) {
                        onValueChange.onChange(this, index);
                }
        }
}
