package net.minecraft.client.renderer;

/**
 * The vanilla face geometry tables, extracted verbatim from
 * {@link LevelRenderer}'s mesher so they can be unit-tested on the JVM
 * (see scripts/geometry_test.java) without a browser or WebGL context.
 *
 * <p>Corner tables are the 26.1.2 decompile's
 * {@code net.minecraft.client.renderer.FaceInfo} (per-direction corner
 * order), with FaceBakery's per-vertex UV assignment: in FaceInfo's
 * corner order every face's uv pattern is exactly
 * {@code (0,0)(0,1)(1,1)(1,0)} - the per-direction differences live
 * entirely in the corner table. Vanilla texture convention: v=0 is the
 * texture's TOP row, so side faces map v to (1 - y).
 *
 * <p>The quad is emitted as six expanded vertices (two triangles:
 * {@code (0,1,2)(2,3,0)} - vanilla's diagonal split), interleaved as
 * 7 floats: x, y, z, u, v, atlas layer, packed RGBA color (float bits).
 * Cross-product checking all six tables shows every emitted triangle's
 * geometric normal points outward, which is GL-CCW front for
 * CULL_FACE BACK (the pipeline's vanilla state: frontFace CCW,
 * cullFace BACK).
 *
 * <p>CRITICAL invariant (the y+2 regression): callers pass the BLOCK's
 * own integer origin. FaceInfo.UP contributes MAX_Y (=y+1) itself, so
 * the up face lands on the block's top plane y+1 - never y+2. The unit
 * test locks every face's plane to its vanilla value.
 */
public final class FaceTables {

        private FaceTables() {
        }

        public static final int FACE_UP = 0;
        public static final int FACE_DOWN = 1;
        public static final int FACE_NORTH = 2;
        public static final int FACE_SOUTH = 3;
        public static final int FACE_WEST = 4;
        public static final int FACE_EAST = 5;

        /** Vanilla's quad index diagonal split. */
        public static final int[] QUAD_INDEX_ORDER = { 0, 1, 2, 2, 3, 0 };

        private static final float[] FACE_QUAD = new float[20];

        /**
         * Emits one textured quad (6 vertices x 7 floats) for the face of
         * the unit cube at block origin (x,y,z). {@code face} is one of
         * the FACE_* constants; {@code layer} the texture atlas layer;
         * {@code shade} the vanilla directional shade (U=1.0, D=0.5,
         * N/S=0.8, W/E=0.6). Returns the new write offset, or {@code vi}
         * unchanged when the scratch cannot fit the quad.
         */
        public static int emitFace(float[] out, int vi, int x, int y, int z, int face,
                int layer, float shade) {
                if (layer < 0) {
                        return vi; // texture not in the atlas (defensive)
                }
                if (vi + 6 * 7 > out.length) {
                        return vi; // scratch overflow (defensive)
                }
                float x0 = x, x1 = x + 1;
                float y0 = y, y1 = y + 1;
                float z0 = z, z1 = z + 1;
                float[] q = FACE_QUAD;
                switch (face) {
                        case FACE_UP:
                                // FaceInfo.UP: (x0,y1,z0)(x0,y1,z1)(x1,y1,z1)(x1,y1,z0)
                                q[0] = x0; q[1] = y1; q[2] = z0; q[3] = 0f; q[4] = 0f;
                                q[5] = x0; q[6] = y1; q[7] = z1; q[8] = 0f; q[9] = 1f;
                                q[10] = x1; q[11] = y1; q[12] = z1; q[13] = 1f; q[14] = 1f;
                                q[15] = x1; q[16] = y1; q[17] = z0; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_DOWN:
                                // FaceInfo.DOWN: (x0,y0,z1)(x0,y0,z0)(x1,y0,z0)(x1,y0,z1)
                                q[0] = x0; q[1] = y0; q[2] = z1; q[3] = 0f; q[4] = 0f;
                                q[5] = x0; q[6] = y0; q[7] = z0; q[8] = 0f; q[9] = 1f;
                                q[10] = x1; q[11] = y0; q[12] = z0; q[13] = 1f; q[14] = 1f;
                                q[15] = x1; q[16] = y0; q[17] = z1; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_NORTH:
                                // FaceInfo.NORTH (-Z): (x1,y1,z0)(x1,y0,z0)(x0,y0,z0)(x0,y1,z0)
                                q[0] = x1; q[1] = y1; q[2] = z0; q[3] = 0f; q[4] = 0f;
                                q[5] = x1; q[6] = y0; q[7] = z0; q[8] = 0f; q[9] = 1f;
                                q[10] = x0; q[11] = y0; q[12] = z0; q[13] = 1f; q[14] = 1f;
                                q[15] = x0; q[16] = y1; q[17] = z0; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_SOUTH:
                                // FaceInfo.SOUTH (+Z): (x0,y1,z1)(x0,y0,z1)(x1,y0,z1)(x1,y1,z1)
                                q[0] = x0; q[1] = y1; q[2] = z1; q[3] = 0f; q[4] = 0f;
                                q[5] = x0; q[6] = y0; q[7] = z1; q[8] = 0f; q[9] = 1f;
                                q[10] = x1; q[11] = y0; q[12] = z1; q[13] = 1f; q[14] = 1f;
                                q[15] = x1; q[16] = y1; q[17] = z1; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_WEST:
                                // FaceInfo.WEST (-X): (x0,y1,z0)(x0,y0,z0)(x0,y0,z1)(x0,y1,z1)
                                q[0] = x0; q[1] = y1; q[2] = z0; q[3] = 0f; q[4] = 0f;
                                q[5] = x0; q[6] = y0; q[7] = z0; q[8] = 0f; q[9] = 1f;
                                q[10] = x0; q[11] = y0; q[12] = z1; q[13] = 1f; q[14] = 1f;
                                q[15] = x0; q[16] = y1; q[17] = z1; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_EAST:
                                // FaceInfo.EAST (+X): (x1,y1,z1)(x1,y0,z1)(x1,y0,z0)(x1,y1,z0)
                                q[0] = x1; q[1] = y1; q[2] = z1; q[3] = 0f; q[4] = 0f;
                                q[5] = x1; q[6] = y0; q[7] = z1; q[8] = 0f; q[9] = 1f;
                                q[10] = x1; q[11] = y0; q[12] = z0; q[13] = 1f; q[14] = 1f;
                                q[15] = x1; q[16] = y1; q[17] = z0; q[18] = 1f; q[19] = 0f;
                                break;
                        default:
                                return vi;
                }
                int shadeB = (int) (shade * 255.0f);
                int colorPacked = shadeB | (shadeB << 8) | (shadeB << 16) | (255 << 24);
                float colorF = Float.intBitsToFloat(colorPacked);
                for (int k = 0; k < QUAD_INDEX_ORDER.length; k++) {
                        int idx = QUAD_INDEX_ORDER[k];
                        int b = idx * 5;
                        out[vi++] = q[b];
                        out[vi++] = q[b + 1];
                        out[vi++] = q[b + 2];
                        out[vi++] = q[b + 3];
                        out[vi++] = q[b + 4];
                        out[vi++] = (float) layer;
                        out[vi++] = colorF;
                }
                return vi;
        }
}
