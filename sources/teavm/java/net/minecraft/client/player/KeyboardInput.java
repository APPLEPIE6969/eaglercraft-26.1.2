package net.minecraft.client.player;

import net.minecraft.client.KeyMapping;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.player.ClientInput
 * + net.minecraft.client.player.KeyboardInput (merged - the decompiled
 * KeyboardInput is the only implementation the client uses).
 *
 * <p>Vanilla semantics, verbatim from the decompile: per-axis impulse
 * {@code calculateImpulse(positive, negative)} = +1 / -1 / 0, packed as
 * {@code Vec2(leftImpulse, forwardImpulse).normalized()}; the booleans
 * come straight from the Options key binds (up/down/left/right/jump/
 * shift/sprint).</p>
 *
 * <p>Key states come from the port's {@link net.minecraft.client.KeyMapping}
 * bind table (scan codes: W=17, S=31, A=30, D=32, Space=57, Shift=42,
 * Ctrl=29) polled through the KeyboardHandler - the browser stand-in
 * for vanilla's GLFW key polling through Options.keyUp.isDown() etc.
 * Rebinding a key in the Key Binds screen takes effect immediately.</p>
 */
public class KeyboardInput {

        /** Vanilla net.minecraft.world.entity.player.Input, unfolded. */
        public static final class Input {
                public final boolean forward;
                public final boolean backward;
                public final boolean left;
                public final boolean right;
                public final boolean jump;
                public final boolean shift;
                public final boolean sprint;

                Input(boolean forward, boolean backward, boolean left, boolean right,
                                boolean jump, boolean shift, boolean sprint) {
                        this.forward = forward;
                        this.backward = backward;
                        this.left = left;
                        this.right = right;
                        this.jump = jump;
                        this.shift = shift;
                        this.sprint = sprint;
                }

                public boolean hasForwardImpulse() {
                        return forward;
                }
        }

        private final net.minecraft.client.Minecraft minecraft;

        public Input keyPresses = new Input(false, false, false, false, false, false, false);
        /** Vanilla Vec2(leftImpulse, forwardImpulse).normalized(). */
        public float leftImpulse;
        public float forwardImpulse;

        /**
         * Port scan codes (PlatformInput.mapKeyCode table) - kept as the
         * KeyMapping defaults; the live input reads the bind table.
         */
        public static final int KEY_W = KeyMapping.SCAN_W;
        public static final int KEY_A = KeyMapping.SCAN_A;
        public static final int KEY_S = KeyMapping.SCAN_S;
        public static final int KEY_D = KeyMapping.SCAN_D;
        public static final int KEY_SPACE = KeyMapping.SCAN_SPACE;
        public static final int KEY_SHIFT = KeyMapping.SCAN_LSHIFT;
        public static final int KEY_CTRL = KeyMapping.SCAN_LCTRL;

        public KeyboardInput(net.minecraft.client.Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        private static float calculateImpulse(boolean positive, boolean negative) {
                if (positive == negative) {
                        return 0.0F;
                }
                return positive ? 1.0F : -1.0F;
        }

        public void tick() {
                // Vanilla KeyboardInput.tick: the booleans come straight
                // from the Options key binds (up/down/left/right/jump/
                // shift/sprint) - here through the KeyMapping table
                boolean up = KeyMapping.FORWARD.isDown();
                boolean down = KeyMapping.BACK.isDown();
                boolean left = KeyMapping.LEFT.isDown();
                boolean right = KeyMapping.RIGHT.isDown();
                this.keyPresses = new Input(
                        up, down, left, right,
                        KeyMapping.JUMP.isDown(),
                        KeyMapping.SNEAK.isDown(),
                        KeyMapping.SPRINT.isDown());
                float forwardImpulse = calculateImpulse(this.keyPresses.forward, this.keyPresses.backward);
                float leftImpulse = calculateImpulse(this.keyPresses.left, this.keyPresses.right);
                // Vanilla: Vec2(leftImpulse, forwardImpulse).normalized()
                float lengthSq = leftImpulse * leftImpulse + forwardImpulse * forwardImpulse;
                if (lengthSq > 0.0F) {
                        float inv = 1.0F / (float) Math.sqrt(lengthSq);
                        this.leftImpulse = leftImpulse * inv;
                        this.forwardImpulse = forwardImpulse * inv;
                } else {
                        this.leftImpulse = 0.0F;
                        this.forwardImpulse = 0.0F;
                }
        }
}
