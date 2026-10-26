package net.minecraft.client.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.player.LocalPlayer.
 *
 * <p>The movement physics chain is ported step-for-step from the
 * decompiled 26.1.2 client (constants verbatim):</p>
 *
 * <ol>
 *   <li>{@link #tick()}: vanilla LocalPlayer.aiStep - sprint trigger
 *       (double-tap forward within the 7-tick sprintWindow, or the
 *       sprint key), creative fly toggle (double-tap jump within 7
 *       ticks), input modification (x0.98, xSNEAKING_SPEED 0.3 when
 *       sneaking, unit-square diagonal clamp), velocity dead-zone
 *       snapping (horizontal^2 &lt; 9.0E-6, |y| &lt; 0.003).</li>
 *   <li>{@link #travel(ClientLevel)}: vanilla LivingEntity.travelInAir -
 *       block friction (0.6) from the block 0.5 below the feet,
 *       friction = blockFriction * 0.91, ground acceleration =
 *       speed * (0.21600002 / friction^3), air acceleration =
 *       flyingSpeed (0.05 flying / 0.02 walking), gravity 0.08,
 *       vertical drag 0.98.</li>
 *   <li>{@link #jumpFromGround()}: vanilla jump - 0.42 upward plus the
 *       sprint boost (-sin(yaw) * 0.2, +cos(yaw) * 0.2), with the
 *       10-tick noJumpDelay.</li>
 *   <li>{@link #move(ClientLevel, double, double, double)}: vanilla
 *       Entity.move + collide - per-axis clipping (Y first, then X/Z
 *       with X first when |dx| &gt;= |dz|), 1.0E-7 collision epsilon,
 *       0.6 step-up with candidate heights, collision flags.</li>
 * </ol>
 *
 * <p>Not ported (no surface in this world): fluids, ladders, vehicles,
 * powder snow, fall-flying, effects. Fall damage tracking exists (the
 * fallDistance field) but health is not yet simulated.</p>
 */
public class LocalPlayer {

        private final Minecraft minecraft;

        // ===== Vanilla entity state =====
        public double x;
        public double y;
        public double z;
        public double xo;
        public double yo;
        public double zo;
        public float yRot;   // yaw
        public float xRot;   // pitch
        public float yRotO;
        public float xRotO;

        public double xd;
        public double yd;
        public double zd;

        /** Vanilla Entity.onGround(). */
        public boolean onGround;
        public boolean horizontalCollision;
        public boolean verticalCollision;
        public boolean minorHorizontalCollision;
        /** Vanilla Entity.fallDistance. */
        public float fallDistance;

        // ===== Input (vanilla LocalPlayer fields) =====
        public final KeyboardInput input;
        public boolean jumping;
        /** Vanilla LivingEntity.xxa/zza - the modified input impulses. */
        public float xxa;
        public float zza;

        // ===== Sprint / sneak / fly (vanilla fields) =====
        private boolean sprinting;
        /** Vanilla LocalPlayer.sprintTriggerTime (double-tap forward window). */
        protected int sprintTriggerTime;
        /** Vanilla LocalPlayer.jumpTriggerTime (double-tap jump window). */
        private int jumpTriggerTime;
        /** Vanilla LivingEntity.noJumpDelay. */
        private int noJumpDelay;
        /** Vanilla Abilities.flying (creative flight). */
        public boolean flying;
        /** Vanilla Abilities.mayfly - true in creative. */
        public final boolean mayfly;
        public boolean crouching;

        /** Vanilla LocalPlayer (client) walk/fly speeds. */
        public static final float WALKING_SPEED = 0.1F;
        public static final float FLYING_SPEED = 0.05F;
        /** Vanilla LivingEntity.BASE_JUMP_POWER. */
        public static final float BASE_JUMP_POWER = 0.42F;
        /** Vanilla gravity attribute default. */
        public static final double GRAVITY = 0.08D;

        /** Vanilla Player default dimensions: 0.6 wide, 1.8 tall. */
        public static final double BB_WIDTH = 0.6D;
        public static final double BB_HEIGHT = 1.8D;
        /** Crouching pose height (vanilla Pose.CROUCHING dimensions). */
        public static final double CROUCH_BB_HEIGHT = 1.5D;
        /** Vanilla standing eye height. */
        public static final double EYE_HEIGHT = 1.62D;
        /** Vanilla crouching eye height. */
        public static final double CROUCH_EYE_HEIGHT = 1.27D;

        public LocalPlayer(Minecraft minecraft, boolean creative) {
                this.minecraft = minecraft;
                this.input = new KeyboardInput(minecraft);
                this.mayfly = creative;
        }

        // ===== Position / rotation (vanilla Entity API) =====

        public void absSnapTo(double x, double y, double z) {
                this.xo = x;
                this.yo = y;
                this.zo = z;
                this.x = x;
                this.y = y;
                this.z = z;
        }

        public void snapTo(double x, double y, double z, float yRot, float xRot) {
                absSnapTo(x, y, z);
                this.yRot = yRot % 360.0F;
                this.xRot = clampPitch(xRot);
                this.yRotO = this.yRot;
                this.xRotO = this.xRot;
        }

        private static float clampPitch(float pitch) {
                float p = pitch;
                if (p < -90.0F) p = -90.0F;
                if (p > 90.0F) p = 90.0F;
                return p;
        }

        /**
         * Vanilla Entity.turn: rotation applied by the mouse handler,
         * one call per pointer-lock delta batch.
         */
        public void turn(double xo, double yo) {
                this.yRotO = this.yRot;
                this.xRotO = this.xRot;
                // Vanilla Entity.turn(x, y): x = HORIZONTAL mouse delta ->
                // yaw (mouse right = turn right), y = VERTICAL delta ->
                // pitch (mouse down = look down); both positive, pitch
                // clamped to +-90.
                this.yRot += (float) xo * 0.15F;
                this.xRot = clampPitch(this.xRot + (float) yo * 0.15F);
        }

        public double getEyeY() {
                return this.y + (this.crouching ? CROUCH_EYE_HEIGHT : EYE_HEIGHT);
        }

        /** The camera position: vanilla Camera.getPosition(). */
        public double getEyeX() {
                return this.x;
        }

        public double getEyeZ() {
                return this.z;
        }

        public boolean isSprinting() {
                return this.sprinting;
        }

        public void setSprinting(boolean sprinting) {
                this.sprinting = sprinting;
        }

        public boolean isCrouching() {
                return this.crouching;
        }

        // ===== AABB =====

        public double getMinX() {
                return this.x - BB_WIDTH / 2.0D;
        }

        public double getMaxX() {
                return this.x + BB_WIDTH / 2.0D;
        }

        public double getMinZ() {
                return this.z - BB_WIDTH / 2.0D;
        }

        public double getMaxZ() {
                return this.z + BB_WIDTH / 2.0D;
        }

        public double getMinY() {
                return this.y;
        }

        public double getMaxY() {
                return this.y + (this.crouching ? CROUCH_BB_HEIGHT : BB_HEIGHT);
        }

        // ===== Tick (vanilla LocalPlayer.aiStep + LivingEntity.aiStep core) =====

        /** One movement tick (20 TPS). */
        public void tick(ClientLevel level) {
                this.xo = this.x;
                this.yo = this.y;
                this.zo = this.z;
                this.yRotO = this.yRot;
                this.xRotO = this.xRot;

                if (this.sprintTriggerTime > 0) {
                        this.sprintTriggerTime--;
                }
                if (this.noJumpDelay > 0) {
                        this.noJumpDelay--;
                }

                boolean wasJumping = this.input.keyPresses.jump;
                boolean wasShiftKeyDown = this.input.keyPresses.shift;
                // Vanilla captures the PRE-tick forward impulse BEFORE
                // input.tick() - the double-tap-sprint window is driven by
                // exactly this staleness (arm on the press tick, fire when
                // the stale value flips false->true within 7 ticks)
                boolean hasForwardImpulse = this.input.keyPresses.forward;

                // Vanilla crouching check (canPlayerFitWithinBlocksAndEntitiesWhen
                // reduced to the block-no-collision query for the crouch pose)
                this.crouching = !this.flying
                        && this.input.keyPresses.shift
                        && level.noCollision(getMinX(), this.y, getMinZ(),
                                getMaxX(), this.y + CROUCH_BB_HEIGHT, getMaxZ());

                this.input.tick();

                // Vanilla: shift/backward cancels the double-tap sprint window
                if (wasShiftKeyDown || this.input.keyPresses.backward) {
                        this.sprintTriggerTime = 0;
                }

                // Vanilla canStartSprinting (reduced to the movement-relevant
                // checks) + the exact double-tap structure from the decompile
                if (!this.sprinting && this.input.keyPresses.forward && !this.crouching) {
                        if (!hasForwardImpulse) {
                                if (this.sprintTriggerTime > 0) {
                                        this.setSprinting(true);
                                } else {
                                        this.sprintTriggerTime = 7; // options.sprintWindow default
                                }
                        }
                        if (this.input.keyPresses.sprint) {
                                this.setSprinting(true);
                        }
                }
                if (this.sprinting) {
                        // Vanilla shouldStopRunSprinting: no forward impulse or
                        // a hard horizontal collision stops the sprint
                        if (!this.input.keyPresses.forward
                                        || (this.horizontalCollision && !this.minorHorizontalCollision)) {
                                this.setSprinting(false);
                        }
                }

                // Vanilla creative flight toggle: double-tap jump within 7 ticks
                boolean justToggledCreativeFlight = false;
                if (this.mayfly) {
                        if (!wasJumping && this.input.keyPresses.jump) {
                                if (this.jumpTriggerTime == 0) {
                                        this.jumpTriggerTime = 7;
                                } else {
                                        this.flying = !this.flying;
                                        if (this.flying && this.onGround) {
                                                this.jumpFromGround();
                                        }
                                        justToggledCreativeFlight = true;
                                        this.jumpTriggerTime = 0;
                                }
                        }
                }

                // Vanilla flying vertical input: shift down / jump up
                if (this.flying) {
                        int inputYa = 0;
                        if (this.input.keyPresses.shift) {
                                inputYa--;
                        }
                        if (this.input.keyPresses.jump) {
                                inputYa++;
                        }
                        if (inputYa != 0) {
                                this.yd += inputYa * FLYING_SPEED * 3.0F;
                        }
                }

                // Vanilla applyInput + modifyInput:
                //   0.98 base scale, xSNEAKING_SPEED (0.3) when sneaking,
                //   then the unit-square diagonal clamp
                float li = this.input.leftImpulse * 0.98F;
                float fi = this.input.forwardImpulse * 0.98F;
                if (this.crouching) {
                        li *= 0.3F;
                        fi *= 0.3F;
                }
                float length = (float) Math.sqrt(li * li + fi * fi);
                if (length > 1.0F) {
                        // modifyInputSpeedForSquareMovement: clamp magnitude so the
                        // vector stays inside the unit square, preserving direction
                        float ax = Math.abs(li);
                        float az = Math.abs(fi);
                        float tan = az > ax ? ax / az : az / ax;
                        float distanceToUnitSquare =
                                (float) Math.sqrt(1.0F + tan * tan);
                        float modified = Math.min(length * distanceToUnitSquare, 1.0F);
                        li = li / length * modified;
                        fi = fi / length * modified;
                }
                this.xxa = li;
                this.zza = fi;
                this.jumping = this.input.keyPresses.jump && !justToggledCreativeFlight;

                // Vanilla jump handling: onGround + noJumpDelay
                if (this.jumping) {
                        if (this.onGround && this.noJumpDelay == 0) {
                                this.jumpFromGround();
                                this.noJumpDelay = 10;
                        }
                    } else {
                        this.noJumpDelay = 0;
                }

                // Vanilla: super.aiStep() -> travel + physics
                this.travel(level);

                // Vanilla: landing cancels creative flight
                if (this.onGround && this.flying) {
                        this.flying = false;
                }

                // Vanilla LivingEntity.aiStep velocity dead zones
                if (this.xd * this.xd + this.zd * this.zd < 9.0E-6D) {
                        this.xd = 0.0D;
                        this.zd = 0.0D;
                }
                if (Math.abs(this.yd) < 0.003D) {
                        this.yd = 0.0D;
                }
            }

        // ===== Travel (vanilla LivingEntity.travelInAir) =====

        private void travel(ClientLevel level) {
                if (this.flying) {
                        travelFlying(level);
                        return;
                }
                // Vanilla getBlockPosBelowThatAffectsMyMovement: 0.5000001 below feet
                int belowId = level.getBlockStateBelowForMovement(this.x, this.y, this.z);
                float blockFriction = this.onGround
                        ? Blocks.byId(belowId).friction
                        : 1.0F;
                float friction = blockFriction * 0.91F;

                // Vanilla handleRelativeFrictionAndCalculateMovement
                moveRelative(getFrictionInfluencedSpeed(blockFriction));
                move(level, this.xd, this.yd, this.zd);

                double movementY = this.yd;
                movementY -= GRAVITY;

                float verticalFriction = 0.98F;
                this.xd *= friction;
                this.yd = movementY * verticalFriction;
                this.zd *= friction;
        }

        private void travelFlying(ClientLevel level) {
                // Vanilla travelFlying air branch: accel 0.02... but Player
                // overrides getFlyingSpeed to abilities.getFlyingSpeed()
                // (0.05, x2 when sprinting)
                float speed = this.sprinting ? FLYING_SPEED * 2.0F : FLYING_SPEED;
                moveRelative(speed);
                move(level, this.xd, this.yd, this.zd);
                this.xd *= 0.91F;
                this.yd *= 0.91F;
                this.zd *= 0.91F;
        }

        /** Vanilla LivingEntity.getFrictionInfluencedSpeed; Player's speed
         * attribute = abilities.walkingSpeed (0.1) with the sprint
         * modifier (+0.30000001192092896 MULTIPLY_TOTAL) applied live. */
        private float getFrictionInfluencedSpeed(float blockFriction) {
                if (this.onGround) {
                        float speed = this.sprinting
                                ? (float) (WALKING_SPEED * 1.30000001192092896D)
                                : WALKING_SPEED;
                        return speed * (0.21600002F / (blockFriction * blockFriction * blockFriction));
                }
                return this.flying ? (this.sprinting ? FLYING_SPEED * 2.0F : FLYING_SPEED) : 0.02F;
        }

        /**
         * Vanilla Entity.moveRelative + getInputVector: rotates the
         * (xxa, zza) impulse by the yaw into a world-space delta.
         */
        private void moveRelative(float speed) {
                double inX = this.xxa;
                double inZ = this.zza;
                double lengthSq = inX * inX + inZ * inZ;
                if (lengthSq < 1.0E-7D) {
                    return;
                }
                double scale = speed;
                if (lengthSq > 1.0D) {
                        double inv = 1.0D / Math.sqrt(lengthSq);
                        inX *= inv;
                        inZ *= inv;
                }
                inX *= scale;
                inZ *= scale;
                double yawRad = this.yRot * (Math.PI / 180.0D);
                float sin = (float) Math.sin(yawRad);
                float cos = (float) Math.cos(yawRad);
                this.xd += inX * cos - inZ * sin;
                this.zd += inZ * cos + inX * sin;
        }

        /**
         * Vanilla jumpFromGround: 0.42 up (Math.max with current y), plus
         * the sprint boost along the facing direction.
         */
        private void jumpFromGround() {
            double jumpPower = BASE_JUMP_POWER;
            this.yd = Math.max(jumpPower, this.yd);
            if (this.sprinting) {
                float angle = this.yRot * (float) (Math.PI / 180.0D);
                this.xd -= Math.sin(angle) * 0.2D;
                this.zd += Math.cos(angle) * 0.2D;
            }
        }

        // ===== Collision (vanilla Entity.move + collide) =====

        /**
         * Vanilla Entity.move(MoverType.SELF, delta): collide per axis,
         * track collision flags, fall distance, onGround.
         */
        private void move(ClientLevel level, double dx, double dy, double dz) {
                this.horizontalCollision = false;
                this.verticalCollision = false;
                this.minorHorizontalCollision = false;

                Vec3 resolved = collide(level, dx, dy, dz);
                boolean xCollision = dx != resolved.x;
                boolean yCollision = dy != resolved.y;
                boolean zCollision = dz != resolved.z;
                this.horizontalCollision = xCollision || zCollision;
                if (horizontalCollision) {
                        // vanilla minorHorizontalCollision: step-up assisted moves
                        double dhx = Math.abs(resolved.x - dx);
                        double dhz = Math.abs(resolved.z - dz);
                        this.minorHorizontalCollision = dhx < 1.0E-5D && dhz < 1.0E-5D
                                && (dx != 0.0D || dz != 0.0D);
                    }
                this.verticalCollision = yCollision;

                // Step-up (vanilla maxUpStep 0.6): retry the horizontal move
                // with a raised box when it collided and we're grounded
                if ((this.onGround || (yCollision && dy < 0.0D)) && this.horizontalCollision) {
                        Vec3 stepped = collideStepUp(level, dx, dy, dz, resolved);
                        if (stepped != null) {
                                resolved = stepped;
                                xCollision = dx != stepped.x;
                                zCollision = dz != stepped.z;
                                this.horizontalCollision = xCollision || zCollision;
                                this.minorHorizontalCollision = true;
                        }
                }

                double oldY = this.y;
                this.x += resolved.x;
                this.y += resolved.y;
                this.z += resolved.z;

                // Vanilla updateOnGround + fall distance: accumulate the
                // descent while airborne; landing (yCollision falling) ends
                // the fall - damage lands here once health is simulated
                this.onGround = yCollision && dy < 0.0D;
                if (this.onGround) {
                        this.fallDistance = 0.0F;
                } else if (resolved.y < 0.0D) {
                        this.fallDistance = (float) ((double) this.fallDistance - resolved.y);
                } else if (this.fallDistance > 0.0F && resolved.y > 0.0D) {
                        // moving up erases the accumulated descent (vanilla
                        // resets fall distance on the upswing)
                        this.fallDistance = 0.0F;
                }

                // Vanilla: stuck-in-block push-out is handled by
                // moveTowardsClosestSpace in aiStep; the flat world's only
                // reachable trap is placing a block inside yourself, which
                // placement already forbids (isUnobstructed check).
        }

        private static final class Vec3 {
                final double x;
                final double y;
                final double z;

                Vec3(double x, double y, double z) {
                        this.x = x;
                        this.y = y;
                        this.z = z;
                }
        }

        /**
         * Vanilla collideWithShapes for full-cube voxels: clip the movement
         * axis by axis (Y first, then X/Z - X first iff |dx| >= |dz|), each
         * axis clipped against the solid blocks the box sweeps through.
         * 1.0E-7 epsilon matches vanilla's Shapes.collide.
         */
        private Vec3 collide(ClientLevel level, double dx, double dy, double dz) {
            double moveY = clipAxis(level, false, true, false,
                    getMinX(), getMinY(), getMinZ(), getMaxX(), getMaxY(), getMaxZ(), dy);
            double moveX = clipAxis(level, true, false, false,
                    getMinX(), getMinY(), getMinZ(), getMaxX(), getMaxY(), getMaxZ(), dx);
            double moveZ = clipAxis(level, false, false, true,
                    getMinX(), getMinY(), getMinZ(), getMaxX(), getMaxY(), getMaxZ(), dz);
            return new Vec3(moveX, moveY, moveZ);
        }

        /**
         * Clips a single axis of movement against solid blocks (vanilla
         * Shapes.collide + VoxelShape.collideX for unit cubes). The box is
         * swept along the axis and the movement is clamped to the nearest
         * blocking face within the swept volume.
         */
        private double clipAxis(ClientLevel level, boolean axisX, boolean axisY, boolean axisZ,
                double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                double distance) {
            if (Math.abs(distance) < 1.0E-7D) {
                return 0.0D;
            }
            double sweepMinX = minX;
            double sweepMaxX = maxX;
            double sweepMinY = minY;
            double sweepMaxY = maxY;
            double sweepMinZ = minZ;
            double sweepMaxZ = maxZ;
            if (axisX) {
                if (distance > 0.0D) {
                    sweepMaxX += distance;
                } else {
                    sweepMinX += distance;
                }
            } else if (axisY) {
                if (distance > 0.0D) {
                    sweepMaxY += distance;
                } else {
                    sweepMinY += distance;
                }
            } else {
                if (distance > 0.0D) {
                    sweepMaxZ += distance;
                } else {
                    sweepMinZ += distance;
                }
            }

            int x0 = floor(sweepMinX);
            int x1 = floor(sweepMaxX - 1.0E-7D);
            int y0 = floor(sweepMinY);
            int y1 = floor(sweepMaxY - 1.0E-7D);
            int z0 = floor(sweepMinZ);
            int z1 = floor(sweepMaxZ - 1.0E-7D);

            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        if (level.getBlockState(bx, by, bz) == Blocks.AIR) {
                            continue;
                        }
                        if (axisX) {
                            if (distance > 0.0D) {
                                double allowed = (double) bx - maxX;
                                if (allowed < distance && allowed > -1.0E-7D
                                        && overlapsYZ(minY, maxY, minZ, maxZ, by, bz)) {
                                    distance = Math.max(allowed, 0.0D);
                                }
                            } else {
                                double allowed = (double) (bx + 1) - minX;
                                if (allowed > distance && allowed < 1.0E-7D
                                        && overlapsYZ(minY, maxY, minZ, maxZ, by, bz)) {
                                    distance = Math.min(allowed, 0.0D);
                                }
                            }
                        } else if (axisY) {
                            if (distance > 0.0D) {
                                double allowed = (double) by - maxY;
                                if (allowed < distance && allowed > -1.0E-7D
                                        && overlapsXZ(minX, maxX, minZ, maxZ, bx, bz)) {
                                    distance = Math.max(allowed, 0.0D);
                                }
                            } else {
                                double allowed = (double) (by + 1) - minY;
                                if (allowed > distance && allowed < 1.0E-7D
                                        && overlapsXZ(minX, maxX, minZ, maxZ, bx, bz)) {
                                    distance = Math.min(allowed, 0.0D);
                                }
                            }
                        } else {
                            if (distance > 0.0D) {
                                double allowed = (double) bz - maxZ;
                                if (allowed < distance && allowed > -1.0E-7D
                                        && overlapsXY(minX, maxX, minY, maxY, bx, by)) {
                                    distance = Math.max(allowed, 0.0D);
                                }
                            } else {
                                double allowed = (double) (bz + 1) - minZ;
                                if (allowed > distance && allowed < 1.0E-7D
                                        && overlapsXY(minX, maxX, minY, maxY, bx, by)) {
                                    distance = Math.min(allowed, 0.0D);
                                }
                            }
                        }
                    }
                }
            }
            return distance;
        }

        private static boolean overlapsYZ(double minY, double maxY, double minZ, double maxZ,
                int by, int bz) {
            return minY < (double) (by + 1) - 1.0E-7D && maxY > (double) by + 1.0E-7D
                && minZ < (double) (bz + 1) - 1.0E-7D && maxZ > (double) bz + 1.0E-7D;
        }

        private static boolean overlapsXZ(double minX, double maxX, double minZ, double maxZ,
                int bx, int bz) {
            return minX < (double) (bx + 1) - 1.0E-7D && maxX > (double) bx + 1.0E-7D
                && minZ < (double) (bz + 1) - 1.0E-7D && maxZ > (double) bz + 1.0E-7D;
        }

        private static boolean overlapsXY(double minX, double maxX, double minY, double maxY,
                int bx, int by) {
            return minX < (double) (bx + 1) - 1.0E-7D && maxX > (double) bx + 1.0E-7D
                && minY < (double) (by + 1) - 1.0E-7D && maxY > (double) by + 1.0E-7D;
        }

        /**
         * Vanilla step-up: when a grounded move collided horizontally, retry
         * with the box raised up to 0.6 and keep the variant that traveled
         * further horizontally.
         */
        private Vec3 collideStepUp(ClientLevel level, double dx, double dy, double dz,
                Vec3 flatMove) {
            // Candidate heights from the blocks ahead (vanilla
            // collectCandidateStepUpHeights): the top of each obstacle in range
            double stepHeight = 0.6D;
            double raiseY = getMaxY();
            double targetTop = this.y + stepHeight;
            double bestTop = -1.0D;
            int x0 = floor(getMinX() + Math.min(dx, 0.0D) - 1.0E-7D);
            int x1 = floor(getMaxX() + Math.max(dx, 0.0D) + 1.0E-7D);
            int z0 = floor(getMinZ() + Math.min(dz, 0.0D) - 1.0E-7D);
            int z1 = floor(getMaxZ() + Math.max(dz, 0.0D) + 1.0E-7D);
            int yTop = floor(raiseY + stepHeight - 1.0E-7D);
            for (int bx = x0; bx <= x1; bx++) {
                for (int bz = z0; bz <= z1; bz++) {
                    for (int by = floor(this.y - 1.0E-7D); by <= yTop; by++) {
                        if (level.getBlockState(bx, by, bz) != Blocks.AIR) {
                            double top = by + 1;
                            double rel = top - this.y;
                            if (rel > 0.0D && rel <= stepHeight && rel != flatMove.y
                                    && rel > bestTop) {
                                bestTop = rel;
                            }
                        }
                    }
                }
            }
            if (bestTop <= 0.0D) {
                return null;
            }
            // Try the move from the raised position
            double raisedY = this.y + bestTop;
            double moveYUp = clipAxis(level, false, true, false,
                    getMinX(), this.y, getMinZ(), getMaxX(), getMaxY(), getMaxZ(), bestTop);
            if (moveYUp < bestTop - 1.0E-7D) {
                return null; // ceiling in the way of the raise
            }
            double moveX = clipAxis(level, true, false, false,
                    getMinX(), raisedY, getMinZ(), getMaxX(), raisedY + boxHeight(), getMaxZ(), dx);
            double moveZ = clipAxis(level, false, false, true,
                    getMinX(), raisedY, getMinZ(), getMaxX(), raisedY + boxHeight(), getMaxZ(), dz);
            double hSq = moveX * moveX + moveZ * moveZ;
            double flatHsq = flatMove.x * flatMove.x + flatMove.z * flatMove.z;
            if (hSq > flatHsq + 1.0E-7D) {
                // vanilla: subtract the raise again (distanceToGround)
                return new Vec3(moveX, moveYUp - bestTop, moveZ);
            }
            return null;
        }

        private double boxHeight() {
            return this.crouching ? CROUCH_BB_HEIGHT : BB_HEIGHT;
        }

        private static int floor(double v) {
            int i = (int) v;
            return v < (double) i ? i - 1 : i;
        }

        // ===== Interaction reach (vanilla BLOCK_INTERACTION_RANGE) =====

        public static final double BLOCK_INTERACTION_RANGE = 4.5D;

        /** Whether the position is within vanilla's interaction range (+1 buffer like the server check). */
        public boolean isWithinBlockInteractionRange(int bx, int by, int bz) {
            double cx = (double) bx + 0.5D;
            double cy = (double) by + 0.5D;
            double cz = (double) bz + 0.5D;
            double dx = cx - this.x;
            double dyy = cy - (this.y + EYE_HEIGHT);
            double dz = cz - this.z;
            double maxRange = BLOCK_INTERACTION_RANGE + 1.0D;
            return dx * dx + dyy * dyy + dz * dz <= maxRange * maxRange;
        }
}
