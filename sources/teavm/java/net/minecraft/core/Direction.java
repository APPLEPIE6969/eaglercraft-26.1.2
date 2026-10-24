package net.minecraft.core;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.core.Direction
 * (the six face directions used by raycast results, placement offsets
 * and the mesh builder's face shading).
 */
public final class Direction {

        public static final Direction DOWN = new Direction(0, 1, -1, 0, 0);
        public static final Direction UP = new Direction(1, 0, 0, 1, 0);
        public static final Direction NORTH = new Direction(2, 3, 0, 0, -1);
        public static final Direction SOUTH = new Direction(3, 2, 0, 0, 1);
        public static final Direction WEST = new Direction(4, 5, -1, 0, 0);
        public static final Direction EAST = new Direction(5, 4, 1, 0, 0);

        /** Vanilla's Direction values in declaration order. */
        public static final Direction[] VALUES = { DOWN, UP, NORTH, SOUTH, WEST, EAST };

        public final int ordinal;
        /** Vanilla's opposite().ordinal(). */
        public final int oppositeOrdinal;
        public final int stepX;
        public final int stepY;
        public final int stepZ;

        private Direction(int ordinal, int oppositeOrdinal, int stepX, int stepY, int stepZ) {
                this.ordinal = ordinal;
                this.oppositeOrdinal = oppositeOrdinal;
                this.stepX = stepX;
                this.stepY = stepY;
                this.stepZ = stepZ;
        }

        /** Vanilla Direction.from3DDataValue (0=down .. 5=east). */
        public static Direction from3DDataValue(int index) {
                return VALUES[index % VALUES.length];
        }

        public Direction getOpposite() {
                return VALUES[this.oppositeOrdinal];
        }

        public String getName() {
                switch (this.ordinal) {
                        case 0: return "down";
                        case 1: return "up";
                        case 2: return "north";
                        case 3: return "south";
                        case 4: return "west";
                        default: return "east";
                }
        }

        @Override
        public String toString() {
                return "Direction[" + getName() + "]";
        }
}
