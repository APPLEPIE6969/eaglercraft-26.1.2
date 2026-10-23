package net.minecraft.client.multiplayer;

/**
 * EaglerCraft 26.1.2 browser override for ClientLevel, the client-side
 * representation of a loaded world.
 *
 * <p>This is the minimal world state the client needs to be "in game":
 * an identity (name + dimension), a running game clock, and a spawn
 * point the camera is placed at. There is no chunk/terrain data yet -
 * the world renders as the sky backdrop while the real block renderer
 * is being integrated - but the lifecycle is real: constructed by the
 * Singleplayer path, ticked once per game tick at 20 TPS, and exposed
 * to the HUD and the telemetry bridge.</p>
 */
public class ClientLevel {

        private final String levelName;
        private final String dimensionId;

        private long gameTime;
        private long dayTime;

        /**
         * The vanilla world difficulty: 0=peaceful, 1=easy, 2=normal
         * (the fresh-world default), 3=hard. Edited from the in-world
         * Options header difficulty CycleButton.
         */
        private int difficulty = 2;

        private final double spawnX;
        private final double spawnY;
        private final double spawnZ;

        public ClientLevel(String levelName, String dimensionId) {
                this.levelName = levelName != null ? levelName : "New World";
                this.dimensionId = dimensionId != null ? dimensionId : "minecraft:overworld";
                this.gameTime = 0L;
                this.dayTime = 1000L; // dawn, like a fresh vanilla world
                this.spawnX = 0.5D;
                this.spawnY = 64.0D;
                this.spawnZ = 0.5D;
        }

        /**
         * Advances the world clock one tick (1/20 s). Called from
         * {@code Minecraft.tick()}.
         */
        public void tick() {
                this.gameTime++;
                this.dayTime++;
                if (this.dayTime >= 24000L) {
                        this.dayTime -= 24000L;
                }
        }

        // ========== Accessors ==========

        public String getLevelName() {
                return levelName;
        }

        public String getDimensionId() {
                return dimensionId;
        }

        public long getGameTime() {
                return gameTime;
        }

        public long getDayTime() {
                return dayTime;
        }

        /** Vanilla world difficulty: 0=peaceful, 1=easy, 2=normal, 3=hard. */
        public int getDifficulty() {
                return difficulty;
        }

        public void setDifficulty(int difficulty) {
                this.difficulty = difficulty & 3;
        }

        public double getSpawnX() {
                return spawnX;
        }

        public double getSpawnY() {
                return spawnY;
        }

        public double getSpawnZ() {
                return spawnZ;
        }

        @Override
        public String toString() {
                return "ClientLevel[" + levelName + " in " + dimensionId
                        + ", gameTime=" + gameTime + "]";
        }
}
