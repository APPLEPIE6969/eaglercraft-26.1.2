package net.minecraft.core;

/**
 * EaglerCraft 26.1.2 browser override for the BlockPos packing helpers
 * (the port stores positions as packed longs in level maps, exactly like
 * vanilla's {@code BlockPos.asLong}).
 *
 * <p>Bit layout (vanilla): 26 bits X, 12 bits Y, 26 bits Z, signed
 * offset so the range covers the vanilla world bounds.</p>
 */
public final class BlockPos {

        private static final int PACKED_X_LENGTH = 26;
        private static final int PACKED_Y_LENGTH = 12;
        private static final int PACKED_Z_LENGTH = 26;
        private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L;
        private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
        private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L;
        private static final int Y_OFFSET = PACKED_Z_LENGTH;
        private static final int X_OFFSET = PACKED_Y_LENGTH + PACKED_Z_LENGTH;

        private BlockPos() {
        }

        public static long asLong(int x, int y, int z) {
                long l = 0L;
                l |= ((long) x & PACKED_X_MASK) << X_OFFSET;
                l |= ((long) y & PACKED_Y_MASK) << Y_OFFSET;
                l |= ((long) z & PACKED_Z_MASK);
                return l;
        }

        public static int getX(long packed) {
                return (int) ((packed << (64 - X_OFFSET - PACKED_X_LENGTH))
                        >> (64 - PACKED_X_LENGTH));
        }

        public static int getY(long packed) {
                return (int) ((packed << (64 - Y_OFFSET - PACKED_Y_LENGTH))
                        >> (64 - PACKED_Y_LENGTH));
        }

        public static int getZ(long packed) {
                return (int) ((packed << (64 - PACKED_Z_LENGTH))
                        >> (64 - PACKED_Z_LENGTH));
        }
}
