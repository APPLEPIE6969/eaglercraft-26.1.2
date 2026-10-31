import net.minecraft.client.renderer.FaceTables;

/**
 * JVM unit test for the vanilla face geometry (no browser, no WebGL,
 * ~0.3s). Locks the invariants that the production probe had to
 * rediscover the hard way:
 *
 *  1. CORNERS: every face's 4 corners equal the 26.1.2 decompile's
 *     net.minecraft.client.renderer.FaceInfo table (independent copy below).
 *  2. PLANE: each face lies on its vanilla plane - UP on y+1 (the y+2
 *     floating-canopy regression emitted it a block too high, leaving
 *     1-block void bands at every terrain edge: "block sides invisible").
 *  3. WINDING: every triangle's right-hand normal points outward from the
 *     block center (front-facing for frontFace CCW / cullFace BACK).
 *  4. UVs: (0,0)(0,1)(1,1)(1,0) per quad in emission order (vanilla's
 *     per-vertex assignment; v=0 is the texture's top row).
 *  5. PACKING: 7 floats per vertex - x,y,z,u,v,layer,color; color packs
 *     the directional shade into RGB bytes with alpha 255.
 *
 * Exit code 0 = all invariants hold; 1 = a violation (message on stdout).
 */
public class FaceTablesTest {

        static int failures = 0;

        static void check(boolean ok, String what) {
                if (!ok) {
                        System.out.println("FAIL: " + what);
                        failures++;
                }
        }

        // Independent copy of the decompiled FaceInfo tables
        // (Extent.MIN_*=0, MAX_*=1) - [face][corner][x,y,z]
        static final int[][][] FACE_INFO = {
                { {0,1,0},{0,1,1},{1,1,1},{1,1,0} }, // UP
                { {0,0,1},{0,0,0},{1,0,0},{1,0,1} }, // DOWN
                { {1,1,0},{1,0,0},{0,0,0},{0,1,0} }, // NORTH
                { {0,1,1},{0,0,1},{1,0,1},{1,1,1} }, // SOUTH
                { {0,1,0},{0,0,0},{0,0,1},{0,1,1} }, // WEST
                { {1,1,1},{1,0,1},{1,0,0},{1,1,0} }, // EAST
        };
        static final String[] NAMES = { "UP", "DOWN", "NORTH", "SOUTH", "WEST", "EAST" };
        // Vanilla plane per face: [face] -> {axis(0=x,1=y,2=z), offset}
        static final int[][] PLANE = { {1,1},{1,0},{2,0},{2,1},{0,0},{0,1} };

        public static void main(String[] args) {
                int bx = 101, by = -52, bz = 37; // arbitrary origin incl. negatives
                float shade = 0.8f;
                int layer = 13;
                float[] out = new float[6 * 6 * 7];

                for (int face = 0; face < 6; face++) {
                        java.util.Arrays.fill(out, 0f);
                        int vi = FaceTables.emitFace(out, 0, bx, by, bz, face, layer, shade);
                        check(vi == 6 * 7, NAMES[face] + ": emits 6x7 floats (got " + vi + ")");

                        // Quad corners from the expanded triangles: buffer order is
                        // (v0,v1,v2, v2,v3,v0) -> corner k from vertex k, except corner
                        // 3 (v3) which sits at vertex index 4.
                        float[][] quad = new float[4][3];
                        for (int k = 0; k < 4; k++) {
                                int src = (k == 3) ? 4 : k;
                                quad[k][0] = out[src * 7];
                                quad[k][1] = out[src * 7 + 1];
                                quad[k][2] = out[src * 7 + 2];
                        }

                        // 1. CORNERS equal the decompiled FaceInfo table
                        for (int c = 0; c < 4; c++) {
                                int ex = FACE_INFO[face][c][0], ey = FACE_INFO[face][c][1], ez = FACE_INFO[face][c][2];
                                boolean ok = quad[c][0] == bx + ex && quad[c][1] == by + ey && quad[c][2] == bz + ez;
                                check(ok, NAMES[face] + " corner " + c + " matches FaceInfo (got "
                                        + quad[c][0] + "," + quad[c][1] + "," + quad[c][2] + " want "
                                        + (bx + ex) + "," + (by + ey) + "," + (bz + ez) + ")");
                        }

                        // 2. PLANE at the vanilla coordinate
                        int axis = PLANE[face][0];
                        float wantPlane = (axis == 0 ? bx : axis == 1 ? by : bz) + PLANE[face][1];
                        for (int c = 0; c < 4; c++) {
                                float got = axis == 0 ? quad[c][0] : axis == 1 ? quad[c][1] : quad[c][2];
                                check(got == wantPlane, NAMES[face] + " lies on plane "
                                        + "xyz".charAt(axis) + "=" + wantPlane + " (got " + got + ")");
                        }

                        // 3. WINDING: both triangles' right-hand normals point outward
                        float cx = bx + 0.5f, cy = by + 0.5f, cz = bz + 0.5f;
                        for (int t = 0; t < 2; t++) {
                                float[] a = { out[(t * 3) * 7], out[(t * 3) * 7 + 1], out[(t * 3) * 7 + 2] };
                                float[] b = { out[(t * 3 + 1) * 7], out[(t * 3 + 1) * 7 + 1], out[(t * 3 + 1) * 7 + 2] };
                                float[] c = { out[(t * 3 + 2) * 7], out[(t * 3 + 2) * 7 + 1], out[(t * 3 + 2) * 7 + 2] };
                                float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                                float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
                                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                                float mag = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                                check(mag > 1e-6f, NAMES[face] + " tri " + t + " is non-degenerate");
                                float tcx = (a[0] + b[0] + c[0]) / 3f - cx;
                                float tcy = (a[1] + b[1] + c[1]) / 3f - cy;
                                float tcz = (a[2] + b[2] + c[2]) / 3f - cz;
                                float dot = nx * tcx + ny * tcy + nz * tcz;
                                check(dot > 0, NAMES[face] + " tri " + t + " normal points outward (dot="
                                        + dot + ")");
                        }

                        // 4. UVs: (0,0)(0,1)(1,1)(1,0) per quad in emission order
                        float[][] wantUV = { {0,0},{0,1},{1,1},{1,0} };
                        for (int c = 0; c < 4; c++) {
                                int src = (c == 3) ? 4 : c;
                                check(out[src * 7 + 3] == wantUV[c][0] && out[src * 7 + 4] == wantUV[c][1],
                                        NAMES[face] + " uv " + c + " is (" + wantUV[c][0] + "," + wantUV[c][1]
                                        + ") (got " + out[src * 7 + 3] + "," + out[src * 7 + 4] + ")");
                        }

                        // 5. PACKING: layer in slot 5; slot 6 carries the RGBA bytes as a
                        // float (a 4-byte carrier - the value is a NaN by design for any
                        // alpha-255 color; the GPU vertex attribute reads the bytes as
                        // UNSIGNED_BYTE[4] RGBA in little-endian order).
                        int shadeB = (int) (shade * 255f);
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        for (int v = 0; v < 6; v++) {
                                check(out[v * 7 + 5] == (float) layer,
                                        NAMES[face] + " vert " + v + " layer slot (got " + out[v * 7 + 5] + ")");
                                bb.putFloat(0, out[v * 7 + 6]);
                                boolean rgbaOk = (bb.get(0) & 0xff) == shadeB && (bb.get(1) & 0xff) == shadeB
                                        && (bb.get(2) & 0xff) == shadeB && (bb.get(3) & 0xff) == 255;
                                check(rgbaOk, NAMES[face] + " vert " + v + " color bytes != RGBA(" + shadeB
                                        + "," + shadeB + "," + shadeB + ",255)");
                        }
                }

                // Guard rails: overflow and unknown face return the offset unchanged.
                int r = FaceTables.emitFace(new float[6 * 7 - 1], 0, 0, 0, 0, 0, 1, 1f);
                check(r == 0, "overflow guard returns unchanged offset (got " + r + ")");
                r = FaceTables.emitFace(new float[6 * 6 * 7], 0, 0, 0, 0, 99, 1, 1f);
                check(r == 0, "unknown face returns unchanged offset (got " + r + ")");

                if (failures > 0) {
                        System.out.println("FaceTablesTest: " + failures + " failure(s)");
                        System.exit(1);
                }
                System.out.println("FaceTablesTest: all invariants hold (6 faces x corners/plane/winding/uv/packing)");
        }
}
