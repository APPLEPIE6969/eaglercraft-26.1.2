package net.minecraft.client.multiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.multiplayer.MultiPlayerGameMode
 * (singleplayer: the client runs the integrated server's game-mode logic
 * synchronously, exactly vanilla's singleplayer relationship).
 *
 * <p>The destroy state machine is the decompiled ServerPlayerGameMode
 * flow verbatim, evaluated on the game tick:</p>
 *
 * <ul>
 *   <li>{@code startDestroyBlock}: creative (instabuild) breaks
 *       immediately; survival records the start tick and reports the
 *       first destroy stage {@code (int)(progress * 10)}.</li>
 *   <li>{@code continueDestroyBlock}: progress accumulates one
 *       {@code getDestroyProgress} per tick - the vanilla formula
 *       {@code destroySpeed(player) / hardness / (correctTool ? 30 : 100)}
 *       - with hardness -1 (bedrock) unbreakable.</li>
 *   <li>{@code stopDestroyBlock}: vanilla's release rule - the total
 *       progress {@code progress * (ticksSpent + 1)} breaking at the
 *       0.7 threshold.</li>
 *   <li>{@code useItemOn}: vanilla BlockItem.place - target position is
 *       the hit face's neighbor, guarded by the placement AABB
 *       obstruction check against the player box.</li>
 * </ul>
 *
 * <p>Drops go straight to the hotbar (no item entities yet) - documented
 * in docs/UI-FIDELITY.md.</p>
 */
public class MultiPlayerGameMode {

        private final Minecraft minecraft;

        // Vanilla ServerPlayerGameMode destroy state
        private boolean isDestroyingBlock;
        private int destroyPos;
        private int destroyPosY;
        private int destroyPosZ;
        private int destroyProgressStart;
        private int gameTicks;

        public MultiPlayerGameMode(Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        /** Advances the game tick counter (called once per game tick). */
        public void tick() {
                this.gameTicks++;
        }

        public boolean isDestroying() {
                return isDestroyingBlock;
        }

        /** The destroy overlay position (-1 when none). */
        public int getDestroyX() {
                return destroyPos;
        }

        public int getDestroyY() {
                return destroyPosY;
        }

        public int getDestroyZ() {
                return destroyPosZ;
        }

        /** The destroy stage 0..9 for the crack overlay (-1 = none). */
        public int getDestroyStage() {
                if (!isDestroyingBlock) {
                        return -1;
                }
                return destroyStage;
        }

        private int destroyStage = -1;

        /**
         * Vanilla START_DESTROY_BLOCK. Returns true when the block broke
         * immediately (creative / insta-mine).
         */
        public boolean startDestroyBlock(int x, int y, int z) {
                LocalPlayer player = minecraft.getPlayer();
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return false;
                }
                if (player == null || level == null) {
                        return false;
                }
                if (!player.isWithinBlockInteractionRange(x, y, z)) {
                        return false; // vanilla "too far"
                }
                int state = level.getBlockState(x, y, z);
                if (state == Blocks.AIR) {
                        return false;
                }
                if (level.isCreative()) {
                        // vanilla creative destroy
                        destroyBlock(x, y, z);
                        return true;
                }
                Blocks.Block block = Blocks.byId(state);
                if (block.destroySpeed < 0.0F) {
                        return false; // unbreakable (bedrock)
                }
                // vanilla survival start: record the start tick + stage 0
                this.destroyProgressStart = this.gameTicks;
                this.isDestroyingBlock = true;
                this.destroyPos = x;
                this.destroyPosY = y;
                this.destroyPosZ = z;
                float progress = getDestroyProgress(level, block);
                this.destroyStage = (int) (progress * 10.0F);
                return false;
        }

        /**
         * Vanilla's per-tick continue (ServerPlayerGameMode.tick): the
         * destroy overlay advances while the attack key stays held on the
         * same block. Returns the current stage (updated in place).
         */
        public void continueDestroyBlock(int x, int y, int z) {
                if (!isDestroyingBlock) {
                        return;
                }
                if (x != destroyPos || y != destroyPosY || z != destroyPosZ) {
                        // vanilla ABORT on target change
                        abortDestroyBlock();
                        return;
                }
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return;
                }
                if (level == null || level.getBlockState(x, y, z) == Blocks.AIR) {
                        abortDestroyBlock();
                        return;
                }
                Blocks.Block block = Blocks.byId(level.getBlockState(x, y, z));
                if (block.destroySpeed < 0.0F) {
                        return;
                }
                float progress = getDestroyProgress(level, block)
                        * (this.gameTicks - this.destroyProgressStart + 1);
                this.destroyStage = (int) (progress * 10.0F);
                if (progress >= 1.0F) {
                        // vanilla delayed-destroy completion
                        destroyBlock(x, y, z);
                        this.isDestroyingBlock = false;
                        this.destroyStage = -1;
                }
        }

        /**
         * Vanilla STOP_DESTROY_BLOCK: the release rule breaks the block
         * when total progress passed the 0.7 threshold.
         */
        public void stopDestroyBlock(int x, int y, int z) {
                if (!isDestroyingBlock) {
                        return;
                }
                if (x == destroyPos && y == destroyPosY && z == destroyPosZ) {
                        int ticksSpentDestroying = this.gameTicks - this.destroyProgressStart;
                        if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return;
                }
                        if (level != null && level.getBlockState(x, y, z) != Blocks.AIR) {
                                Blocks.Block block = Blocks.byId(level.getBlockState(x, y, z));
                                float destroyProgress = getDestroyProgress(level, block)
                                        * (ticksSpentDestroying + 1);
                                if (destroyProgress >= 0.7F) {
                                        destroyBlock(x, y, z);
                                }
                        }
                }
                this.isDestroyingBlock = false;
                this.destroyStage = -1;
        }

        /** Vanilla ABORT_DESTROY_BLOCK. */
        public void abortDestroyBlock() {
                this.isDestroyingBlock = false;
                this.destroyStage = -1;
        }

        /** The per-tick survival progress: destroySpeed/hardness/modifier. */
        private float getDestroyProgress(ClientLevel level, Blocks.Block block) {
                if (block.destroySpeed == -1.0F) {
                        return 0.0F;
                }
                // No tools in this build: the fist is the "wrong tool" for
                // requiresCorrectTool blocks (stone family) -> 100x divisor
                int modifier = block.requiresCorrectTool ? 100 : 30;
                return 1.0F / block.destroySpeed / modifier;
        }

        /** Breaks the block and picks it up (vanilla destroyBlock + drop). */
        private void destroyBlock(int x, int y, int z) {
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return;
                }
                if (level == null) {
                        return;
                }
                int state = level.getBlockState(x, y, z);
                if (state == Blocks.AIR) {
                        return;
                }
                level.setBlockState(x, y, z, Blocks.AIR);
                if (!level.isCreative()) {
                        minecraft.getInventory().addItem(state);
                }
        }

        /**
         * Vanilla BlockItem.place via useItemOn: the hit face's neighbor
         * cube receives the selected block when the player's box does not
         * overlap it (canPlace's isUnobstructed check) and the slot has
         * stock (survival) or the mode is creative.
         */
        public boolean useItemOn(BlockHitResult hit, int blockId) {
                if (hit == null || hit.type != BlockHitResult.Type.BLOCK || blockId == Blocks.AIR) {
                        return false;
                }
                LocalPlayer player = minecraft.getPlayer();
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return false;
                }
                if (player == null || level == null) {
                        return false;
                }
                int nx = hit.blockX + hit.direction.stepX;
                int ny = hit.blockY + hit.direction.stepY;
                int nz = hit.blockZ + hit.direction.stepZ;
                if (ny < ClientLevel.MIN_Y || ny > ClientLevel.MAX_Y) {
                        return false;
                }
                if (level.getBlockState(nx, ny, nz) != Blocks.AIR) {
                        return false;
                }
                if (!player.isWithinBlockInteractionRange(nx, ny, nz)) {
                        return false;
                }
                // Vanilla isUnobstructed: the placement cube must not overlap
                // the player AABB
                boolean overlapsPlayer =
                        player.getMinX() < nx + 1.0D && player.getMaxX() > (double) nx
                        && player.getMinY() < ny + 1.0D && player.getMaxY() > (double) ny
                        && player.getMinZ() < nz + 1.0D && player.getMaxZ() > (double) nz;
                if (overlapsPlayer) {
                        return false;
                }
                level.setBlockState(nx, ny, nz, blockId);
                if (!level.isCreative()) {
                        minecraft.getInventory().consumeSelected();
                }
                return true;
        }
}
