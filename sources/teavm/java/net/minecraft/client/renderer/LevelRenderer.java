package net.minecraft.client.renderer;

import java.util.HashMap;
import java.util.Map;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v2_6.adapter.WorldGlRenderer;
import net.lax1dude.eaglercraft.v2_6.adapter.WorldTextures;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v2_6.internal.teavm.WebGL2RenderingContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * EaglerCraft 26.1.2 browser override for net.minecraft.client.renderer.LevelRenderer.
 *
 * <h3>Meshing</h3>
 *
 * <p>One opaque mesh per 16x16x16 section, rebuilt only when dirty -
 * vanilla's SectionRenderDispatcher reduced to its synchronous browser
 * form. A face is emitted when the neighbor cube is air (vanilla
 * solid-render face culling for a full-cube world). Each vertex packs:</p>
 *
 * <ul>
 *   <li>position (3f) - world-space block corners</li>
 *   <li>uv (2f) - vanilla's 0..1 per-face texture range</li>
 *   <li>layer (1f) - the atlas array's layer index for this face's
 *       texture (the sampler2DArray slice; vanilla's atlas UV reduction
 *       in browser form)</li>
 *   <li>color (4ub) - vanilla's directional face shade (down 0.5, up
 *       1.0, north/south 0.8, west/east 0.6 - the getShade constants);
 *       the grass top tint is baked into the texture at upload</li>
 * </ul>
 *
 * <p>Smooth-lighting AO is not computed (flat world + full cubes make
 * the directional shade the dominant term); documented in
 * docs/UI-FIDELITY.md.</p>
 *
 * <h3>Frame</h3>
 *
 * <p>{@link #renderLevel} consumes dirty sections (budgeted per frame),
 * then draws every built mesh with the camera view, then the destroy
 * stage overlay and the hit outline. Texture loading is polled every
 * frame so late-arriving PNGs light the world up without a reload.</p>
 */
public class LevelRenderer {

        private final Minecraft minecraft;

        /** Section key -> mesh handle + vertex count. */
        private static final class Mesh {
                JSObject vbo;
                int vertices;
        }

        private final Map<Long, Mesh> meshes = new HashMap<Long, Mesh>();

        /** Vanilla getShade constants (Direction-based face brightness). */
        private static final float SHADE_DOWN = 0.5F;
        private static final float SHADE_UP = 1.0F;
        private static final float SHADE_NORTH_SOUTH = 0.8F;
        private static final float SHADE_WEST_EAST = 0.6F;

        /** Mesh rebuild budget per frame (sections are tiny in a flat world). */
        private static final int REBUILD_BUDGET = 8;

        public LevelRenderer(Minecraft minecraft) {
                this.minecraft = minecraft;
        }

        // ========== LevelRenderer API ==========

        /** Called when the level changes. */
        public void setLevel(Object level) {
                for (Mesh m : meshes.values()) {
                        WorldGlRenderer.deleteMesh(m.vbo);
                }
                meshes.clear();
        }

        public void resize(int width, int height) {
                // no-op - the MVP is rebuilt every frame
        }

        public void tick() {
                // no-op - rebuilds are consumed in renderLevel
        }

        // ========== Frame ==========

        /**
         * Renders the world: consumes dirty-section rebuilds, then draws
         * the opaque layer with the camera view.
         */
        public void renderLevel(WebGL2RenderingContext gl, int width, int height,
                        double eyeX, double eyeY, double eyeZ, float yaw, float pitch, float fov) {
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return;
                }
                WorldTextures.requestAll();
                WorldTextures.pollPending();
                if (!WorldTextures.isReady()) {
                        return; // atlas still decoding; nothing to draw yet
                }

                // Budgeted rebuilds (vanilla SectionRenderDispatcher.compile)
                int budget = REBUILD_BUDGET;
                while (budget-- > 0) {
                        Long dirty = level.pollDirtySection();
                        if (dirty == null) {
                                break;
                        }
                        rebuildSection(gl, level, dirty.longValue());
                }

                WorldGlRenderer.beginFrame(gl, eyeX, eyeY, eyeZ, yaw, pitch, fov, width, height);
                WorldGlRenderer.bindState(gl);

                JSObject atlas = WorldTextures.texture();
                for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
                        Mesh mesh = e.getValue();
                        if (mesh == null || mesh.vbo == null || mesh.vertices <= 0) {
                                continue;
                        }
                        WorldGlRenderer.drawSections(gl, mesh.vbo, mesh.vertices, atlas);
                }
        }

        /**
         * Renders the destroy-stage crack overlay on the block being mined
         * (vanilla renderDestroyProgress: inflated 1.002 cube, blended).
         */
        public void renderDestroyProgress(WebGL2RenderingContext gl, int x, int y, int z, int stage) {
                if (stage < 0 || stage > 9 || !WorldTextures.isReady()) {
                        return;
                }
                int layer = WorldTextures.LAYER_DESTROY_0 + stage;
                float[] verts = buildInflatedCube(x, y, z, layer);
                Float32Array packed = WorldGlRenderer.pack(verts);
                WorldGlRenderer.drawDestroyStage(gl, packed, verts.length / WorldGlRenderer.STRIDE_FLOATS,
                        WorldTextures.texture());
        }

        /**
         * Renders the hit outline (vanilla renderHitOutline: black edges
         * around the targeted block's unit cube, slightly inflated).
         * WebGL caps lineWidth at 1, so each edge is drawn as two quads
         * (top/side pairs) of ~0.01 thickness - vanilla's ~2.5px weight.
         */
        public void renderHitOutline(WebGL2RenderingContext gl, int x, int y, int z) {
                if (!WorldTextures.isReady()) {
                        return;
                }
                float[] verts = buildOutlineQuads(x, y, z);
                Float32Array packed = WorldGlRenderer.pack(verts);
                WorldGlRenderer.drawOutline(gl, packed, verts.length / WorldGlRenderer.STRIDE_FLOATS);
        }

        // ========== Meshing ==========

        /**
         * Rebuilds one section's opaque mesh. Faces are emitted against air
         * neighbors with vanilla's directional shading.
         */
        private void rebuildSection(WebGL2RenderingContext gl, ClientLevel level, long secKey) {
                int secX = BlockPos.getX(secKey);
                int secY = BlockPos.getY(secKey);
                int secZ = BlockPos.getZ(secKey);
                int baseX = secX << 4;
                int baseY = secY << 4;
                int baseZ = secZ << 4;

                float[] out = scratch;
                int vi = 0;
                for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                                for (int lx = 0; lx < 16; lx++) {
                                        int x = baseX + lx;
                                        int y = baseY + ly;
                                        int z = baseZ + lz;
                                        int id = level.getBlockState(x, y, z);
                                        if (id == Blocks.AIR) {
                                                continue;
                                        }
                                        Blocks.Block block = Blocks.byId(id);
                                        // +Y (up face, shade 1.0) - grass uses its tinted top texture
                                        if (level.getBlockState(x, y + 1, z) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y + 1, z, FACE_UP,
                                                        WorldTextures.layerTop(block), SHADE_UP);
                                        }
                                        // -Y (down face, shade 0.5)
                                        if (level.getBlockState(x, y - 1, z) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y, z, FACE_DOWN,
                                                        WorldTextures.layerBottom(block), SHADE_DOWN);
                                        }
                                        // -Z (north, shade 0.8)
                                        if (level.getBlockState(x, y, z - 1) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y, z, FACE_NORTH,
                                                        WorldTextures.layerSide(block), SHADE_NORTH_SOUTH);
                                        }
                                        // +Z (south, shade 0.8)
                                        if (level.getBlockState(x, y, z + 1) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y, z, FACE_SOUTH,
                                                        WorldTextures.layerSide(block), SHADE_NORTH_SOUTH);
                                        }
                                        // -X (west, shade 0.6)
                                        if (level.getBlockState(x - 1, y, z) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y, z, FACE_WEST,
                                                        WorldTextures.layerSide(block), SHADE_WEST_EAST);
                                        }
                                        // +X (east, shade 0.6)
                                        if (level.getBlockState(x + 1, y, z) == Blocks.AIR) {
                                                vi = emitFace(out, vi, x, y, z, FACE_EAST,
                                                        WorldTextures.layerSide(block), SHADE_WEST_EAST);
                                        }
                                }
                        }
                }

                if (vi == 0) {
                        Mesh empty = meshes.get(Long.valueOf(secKey));
                        if (empty != null) {
                                WorldGlRenderer.deleteMesh(empty.vbo);
                                meshes.remove(Long.valueOf(secKey));
                        }
                        return;
                }

                Float32Array packed = packTrim(out, vi);
                int vertices = vi / WorldGlRenderer.STRIDE_FLOATS;
                JSObject vbo = WorldGlRenderer.uploadMesh(gl, packed);
                Mesh mesh = meshes.get(Long.valueOf(secKey));
                if (mesh == null) {
                        mesh = new Mesh();
                        meshes.put(Long.valueOf(secKey), mesh);
                } else {
                        WorldGlRenderer.deleteMesh(mesh.vbo);
                }
                mesh.vbo = vbo;
                mesh.vertices = vertices;
        }

        /** Meshing scratch: grows on demand (a section's visible faces are
         * typically a few hundred; 128k floats = comfortable headroom). */
        private float[] scratch = new float[128 * 1024];

        /**
         * Emits one textured quad (6 vertices x 6 floats + 1 packed color
         * float each... interleaved: 5 floats + 1 color float per vertex).
         * Returns the new write offset.
         */
        private int emitFace(float[] out, int vi, int x, int y, int z, int face,
                int layer, float shade) {
                if (layer < 0) {
                        return vi; // texture not in the atlas (defensive)
                }
                if (vi + 6 * 7 > out.length) {
                        ClientMain.warn("[LevelRenderer] mesh scratch overflow");
                        return vi;
                }
                // The quad's 4 corners (axis-aligned unit cube face)
                float x0 = x, x1 = x + 1;
                float y0 = y, y1 = y + 1;
                float z0 = z, z1 = z + 1;
                float[] q = FACE_QUAD;
                switch (face) {
                        case FACE_UP:
                                // CCW seen from above
                                q[0] = x0; q[1] = y1; q[2] = z1; q[3] = 0f; q[4] = 1f;
                                q[5] = x0; q[6] = y1; q[7] = z0; q[8] = 0f; q[9] = 0f;
                                q[10] = x1; q[11] = y1; q[12] = z0; q[13] = 1f; q[14] = 0f;
                                q[15] = x1; q[16] = y1; q[17] = z1; q[18] = 1f; q[19] = 1f;
                                break;
                        case FACE_DOWN:
                                // CCW seen from below
                                q[0] = x0; q[1] = y0; q[2] = z0; q[3] = 0f; q[4] = 0f;
                                q[5] = x0; q[6] = y0; q[7] = z1; q[8] = 0f; q[9] = 1f;
                                q[10] = x1; q[11] = y0; q[12] = z1; q[13] = 1f; q[14] = 1f;
                                q[15] = x1; q[16] = y0; q[17] = z0; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_NORTH:
                                // -Z face, CCW seen from -Z
                                q[0] = x1; q[1] = y0; q[2] = z0; q[3] = 1f; q[4] = 1f;
                                q[5] = x0; q[6] = y0; q[7] = z0; q[8] = 0f; q[9] = 1f;
                                q[10] = x0; q[11] = y1; q[12] = z0; q[13] = 0f; q[14] = 0f;
                                q[15] = x1; q[16] = y1; q[17] = z0; q[18] = 1f; q[19] = 0f;
                                break;
                        case FACE_SOUTH:
                                // +Z face, CCW seen from +Z
                                q[0] = x0; q[1] = y0; q[2] = z1; q[3] = 0f; q[4] = 1f;
                                q[5] = x1; q[6] = y0; q[7] = z1; q[8] = 1f; q[9] = 1f;
                                q[10] = x1; q[11] = y1; q[12] = z1; q[13] = 1f; q[14] = 0f;
                                q[15] = x0; q[16] = y1; q[17] = z1; q[18] = 0f; q[19] = 0f;
                                break;
                        case FACE_WEST:
                                // -X face, CCW seen from -X
                                q[0] = x0; q[1] = y0; q[2] = z1; q[3] = 1f; q[4] = 1f;
                                q[5] = x0; q[6] = y0; q[7] = z0; q[8] = 0f; q[9] = 1f;
                                q[10] = x0; q[11] = y1; q[12] = z0; q[13] = 0f; q[14] = 0f;
                                q[15] = x0; q[16] = y1; q[17] = z1; q[18] = 1f; q[19] = 0f;
                                break;
                        default: // FACE_EAST
                                // +X face, CCW seen from +X
                                q[0] = x0; q[1] = y0; q[2] = z0; q[3] = 0f; q[4] = 1f;
                                q[5] = x1; q[6] = y0; q[7] = z0; q[8] = 1f; q[9] = 1f;
                                q[10] = x1; q[11] = y1; q[12] = z0; q[13] = 1f; q[14] = 0f;
                                q[15] = x0; q[16] = y1; q[17] = z0; q[18] = 0f; q[19] = 0f;
                                break;
                }
                int shadeB = (int) (shade * 255.0f);
                int colorPacked = shadeB | (shadeB << 8) | (shadeB << 16) | (255 << 24);
                float colorF = Float.intBitsToFloat(colorPacked);
                // Two triangles: (0,1,2) (2,3,0) - vanilla's quad index
                // diagonal split, wound CCW for BACK culling. Vertex slots:
                // x, y, z, u, v, layer, color
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

        // (0,2,1)(0,3,2): each triangle is the REVERSE of the previous
        // (0,1,2)(2,3,0) order. The face corner arrays wind their
        // triangles CW as seen from outside (the FACE_UP quad's
        // cross product points (0,-1,0)), so the reversed order is
        // what makes them GL-CCW front faces for CULL_FACE BACK -
        // the unreversed order culled every top face and rendered
        // the world inside-out (the dark ground = bedrock bottoms
        // seen through the culled grass).
        private static final int[] QUAD_INDEX_ORDER = { 0, 2, 1, 0, 3, 2 };
        private static final float[] FACE_QUAD = new float[20];

        private static final int FACE_UP = 0;
        private static final int FACE_DOWN = 1;
        private static final int FACE_NORTH = 2;
        private static final int FACE_SOUTH = 3;
        private static final int FACE_WEST = 4;
        private static final int FACE_EAST = 5;

        // ========== Overlay geometry ==========
        // Corner-array quad writer shared by both overlays: 4 corners with
        // x,y,z,u,v, a layer, and a shade (shade < 0 = outline black a=0.4).

        /** Emits one quad from 4 corners [x,y,z,u,v] each. */
        private static int pushQuadCorners(float[] out, int vi, float[][] c,
                float layer, float shade) {
                int colorPacked;
                if (shade < 0.0f) {
                        // vanilla renderHitOutline color: black, alpha 0.4
                        colorPacked = (102 << 24);
                } else {
                        int s = (int) (shade * 255.0f);
                        colorPacked = s | (s << 8) | (s << 16) | (255 << 24);
                }
                float colorF = Float.intBitsToFloat(colorPacked);
                // (0,1,2) (2,3,0) - vanilla's quad index diagonal split
                int[] order = { 0, 2, 1, 0, 3, 2 }; // reversed: see QUAD_INDEX_ORDER
                for (int k = 0; k < order.length; k++) {
                        float[] v = c[order[k]];
                        out[vi++] = v[0];
                        out[vi++] = v[1];
                        out[vi++] = v[2];
                        out[vi++] = v[3];
                        out[vi++] = v[4];
                        out[vi++] = layer;
                        out[vi++] = colorF;
                }
                return vi;
        }

        /** Quad corner scratch: [corner][x,y,z,u,v]. */
        private static final float[][] QC = new float[4][5];
        private static float[] trim(float[] data, int count) {
                float[] out = new float[count];
                System.arraycopy(data, 0, out, 0, count);
                return out;
        }

        /** Packs scratch[0..count) into a Float32Array. */
        private static Float32Array packTrim(float[] data, int count) {
                Float32Array out = Float32Array.create(count);
                for (int i = 0; i < count; i++) {
                        out.set(i, data[i]);
                }
                return out;
        }


        private static void qc(int i, float x, float y, float z, float u, float v) {
                QC[i][0] = x;
                QC[i][1] = y;
                QC[i][2] = z;
                QC[i][3] = u;
                QC[i][4] = v;
        }

        /**
         * Builds the destroy-stage cube: all 6 faces of the block inflated
         * by 0.002 on each side (vanilla's progress cube sizing), each
         * face textured 0..1 on the given atlas layer.
         */
        private static float[] buildInflatedCube(int bx, int by, int bz, int layer) {
                float e = 0.002f;
                float x0 = bx - e, x1 = bx + 1 + e;
                float y0 = by - e, y1 = by + 1 + e;
                float z0 = bz - e, z1 = bz + 1 + e;
                float[] out = new float[6 * 6 * 7];
                int vi = 0;
                // +Y
                qc(0, x0, y1, z1, 0f, 1f); qc(1, x0, y1, z0, 0f, 0f);
                qc(2, x1, y1, z0, 1f, 0f); qc(3, x1, y1, z1, 1f, 1f);
                vi = pushQuadCorners(out, vi, QC, layer, 1.0f);
                // -Y
                qc(0, x0, y0, z0, 0f, 0f); qc(1, x0, y0, z1, 0f, 1f);
                qc(2, x1, y0, z1, 1f, 1f); qc(3, x1, y0, z0, 1f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, 0.5f);
                // -Z
                qc(0, x1, y0, z0, 1f, 1f); qc(1, x0, y0, z0, 0f, 1f);
                qc(2, x0, y1, z0, 0f, 0f); qc(3, x1, y1, z0, 1f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, 0.8f);
                // +Z
                qc(0, x0, y0, z1, 0f, 1f); qc(1, x1, y0, z1, 1f, 1f);
                qc(2, x1, y1, z1, 1f, 0f); qc(3, x0, y1, z1, 0f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, 0.8f);
                // -X
                qc(0, x0, y0, z1, 1f, 1f); qc(1, x0, y0, z0, 0f, 1f);
                qc(2, x0, y1, z0, 0f, 0f); qc(3, x0, y1, z1, 1f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, 0.6f);
                // +X
                qc(0, x0, y0, z0, 0f, 1f); qc(1, x1, y0, z0, 1f, 1f);
                qc(2, x1, y1, z0, 1f, 0f); qc(3, x0, y1, z0, 0f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, 0.6f);
                return trim(out, vi);
        }

        /**
         * Builds the hit-outline geometry: the 12 cube edges as thin
         * cross-quads (a vertical + horizontal quad per edge), black at
         * alpha 0.4 - vanilla's line look at WebGL's 1px line-width cap.
         */
        private static float[] buildOutlineQuads(int bx, int by, int bz) {
                float e = 0.002f;
                float x0 = bx - e, x1 = bx + 1 + e;
                float y0 = by - e, y1 = by + 1 + e;
                float z0 = bz - e, z1 = bz + 1 + e;
                float t = 0.006f; // ~2.5 vanilla px at depth
                float[] out = new float[12 * 2 * 6 * 7];
                int vi = 0;
                float shade = -1.0f;
                int layer = 0;
                // Bottom rectangle (4 edges): horizontal cross-quads
                vi = pushCrossX(out, vi, x0, x1, y0, z0, t, z1, layer, shade);
                vi = pushCrossX(out, vi, x0, x1, y0, z1, t, z0, layer, shade);
                vi = pushCrossZ(out, vi, z0, z1, y0, x0, t, x1, layer, shade);
                vi = pushCrossZ(out, vi, z0, z1, y0, x1, t, x0, layer, shade);
                // Top rectangle
                vi = pushCrossX(out, vi, x0, x1, y1, z0, t, z1, layer, shade);
                vi = pushCrossX(out, vi, x0, x1, y1, z1, t, z0, layer, shade);
                vi = pushCrossZ(out, vi, z0, z1, y1, x0, t, x1, layer, shade);
                vi = pushCrossZ(out, vi, z0, z1, y1, x1, t, x0, layer, shade);
                // Verticals (4): two thin quads each (X-facing + Z-facing)
                vi = pushCrossY(out, vi, y0, y1, x0, z0, t, x1, layer, shade);
                vi = pushCrossY(out, vi, y0, y1, x1, z0, t, x0, layer, shade);
                vi = pushCrossY(out, vi, y0, y1, x0, z1, t, x1, layer, shade);
                vi = pushCrossY(out, vi, y0, y1, x1, z1, t, x0, layer, shade);
                return trim(out, vi);
        }

        /** X-aligned edge at height y, depth z, with a horizontal cross-quad. */
        private static int pushCrossX(float[] out, int vi, float xa, float xb, float y,
                float z, float t, float zFar, int layer, float shade) {
                qc(0, xa, y - t, z, 0f, 0f);
                qc(1, xb, y - t, z, 1f, 0f);
                qc(2, xb, y + t, z, 1f, 1f);
                qc(3, xa, y + t, z, 0f, 1f);
                vi = pushQuadCorners(out, vi, QC, layer, shade);
                qc(0, xa, y, z - t, 0f, 0f);
                qc(1, xb, y, z - t, 1f, 0f);
                qc(2, xb, y, z + t, 1f, 1f);
                qc(3, xa, y, z + t, 0f, 1f);
                return pushQuadCorners(out, vi, QC, layer, shade);
        }

        /** Z-aligned edge at height y, column x, horizontal cross-quad. */
        private static int pushCrossZ(float[] out, int vi, float za, float zb, float y,
                float x, float t, float xFar, int layer, float shade) {
                qc(0, x, y - t, za, 0f, 0f);
                qc(1, x, y - t, zb, 1f, 0f);
                qc(2, x, y + t, zb, 1f, 1f);
                qc(3, x, y + t, za, 0f, 1f);
                vi = pushQuadCorners(out, vi, QC, layer, shade);
                qc(0, x - t, y, za, 0f, 0f);
                qc(1, x - t, y, zb, 1f, 0f);
                qc(2, x + t, y, zb, 1f, 1f);
                qc(3, x + t, y, za, 0f, 1f);
                return pushQuadCorners(out, vi, QC, layer, shade);
        }

        /** Y-aligned edge at column (x,z), vertical cross-quad pair. */
        private static int pushCrossY(float[] out, int vi, float ya, float yb, float x,
                float z, float t, float xFar, int layer, float shade) {
                qc(0, x - t, ya, z, 0f, 0f);
                qc(1, x - t, yb, z, 0f, 1f);
                qc(2, x + t, yb, z, 1f, 1f);
                qc(3, x + t, ya, z, 1f, 0f);
                vi = pushQuadCorners(out, vi, QC, layer, shade);
                qc(0, x, ya, z - t, 0f, 0f);
                qc(1, x, yb, z - t, 0f, 1f);
                qc(2, x, yb, z + t, 1f, 1f);
                qc(3, x, ya, z + t, 1f, 0f);
                return pushQuadCorners(out, vi, QC, layer, shade);
        }
}
