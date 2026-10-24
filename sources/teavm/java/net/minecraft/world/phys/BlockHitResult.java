package net.minecraft.world.phys;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.world.phys.HitResult
 * + net.minecraft.world.phys.BlockHitResult (merged - the port only needs
 * block hits, never entity hits).
 *
 * <p>Vanilla semantics: the exact ray/shape intersection point
 * ({@link #location}), the hit block position, and the face
 * {@link Direction} the ray entered through. A miss is represented by
 * {@code type == MISS} with vanilla's "miss" contract (location = ray
 * end, blockPos = containing block of the ray end).</p>
 */
public class BlockHitResult {

        public enum Type {
                MISS,
                BLOCK
        }

        public final Type type;
        /** Exact intersection point (vanilla HitResult.location). */
        public final double x;
        public final double y;
        public final double z;
        /** Hit block position (vanilla BlockHitResult.blockPos). */
        public final int blockX;
        public final int blockY;
        public final int blockZ;
        /** Face the ray entered through (null on MISS). */
        public final Direction direction;
        /** True when the ray started inside the block (vanilla isInside). */
        public final boolean isInside;

        private BlockHitResult(Type type, double x, double y, double z,
                        int blockX, int blockY, int blockZ, Direction direction, boolean isInside) {
                this.type = type;
                this.x = x;
                this.y = y;
                this.z = z;
                this.blockX = blockX;
                this.blockY = blockY;
                this.blockZ = blockZ;
                this.direction = direction;
                this.isInside = isInside;
        }

        public static BlockHitResult miss(double x, double y, double z, int blockX, int blockY, int blockZ) {
                return new BlockHitResult(Type.MISS, x, y, z, blockX, blockY, blockZ, null, false);
        }

        public static BlockHitResult block(double x, double y, double z,
                        int blockX, int blockY, int blockZ, Direction direction, boolean isInside) {
                return new BlockHitResult(Type.BLOCK, x, y, z, blockX, blockY, blockZ, direction, isInside);
        }

        /** The hit block as a packed position (for storage keys). */
        public long blockPos() {
                return BlockPos.asLong(blockX, blockY, blockZ);
        }

        @Override
        public String toString() {
                if (type == Type.MISS) {
                        return "BlockHitResult[MISS]";
                }
                return "BlockHitResult[BLOCK " + blockX + " " + blockY + " " + blockZ
                        + " face=" + direction + "]";
        }
}
