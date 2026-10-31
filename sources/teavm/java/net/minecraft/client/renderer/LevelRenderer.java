package net.minecraft.client.renderer;

import java.util.HashMap;
import java.util.Map;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;

import net.lax1dude.eaglercraft.v2_6.adapter.WorldGlRenderer;
import net.lax1dude.eaglercraft.v2_6.adapter.WorldTextures;
import net.lax1dude.eaglercraft.v2_6.internal.PlatformOpenGL;
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
 * form. A face is emitted when the neighbor block does not occlude it
 * (air, leaves, glass - vanilla's isOpaqueFullCube face culling; the
 * air-only rule first used here culled leaf-leaf faces and turned every
 * cutout texture hole into a see-through gap). Each vertex packs:</p>
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

        /** Mesh rebuild budget per frame - 64 keeps the initial view-volume
         * churn of the hilly base (a 13x13x3 sweep is ~500 sections) under
         * ~10s even on SwiftShader's software GL (~1fps there), and is a
         * few milliseconds of work on any real GPU. */
        private static final int REBUILD_BUDGET = 64;

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
         * Rebuilds one section's opaque mesh. Faces are emitted against
         * non-occluding neighbors with vanilla's directional shading.
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
                                        // +Y (up face, shade 1.0) - grass uses its tinted top texture.
                                        // emitFace takes the BLOCK's own origin and its FaceInfo.UP
                                        // table adds MAX_Y (=+1) itself: pass plain y. The old call
                                        // passed the plane (y+1), so every top face was emitted at
                                        // y+2 - a canopy floating one block above the side quads,
                                        // leaving a 1-block void band at every terrain edge and
                                        // black voids around every dug hole ("sides invisible").
                                        if (!occludes(level.getBlockState(x, y + 1, z))) {
                                                vi = emitFace(out, vi, x, y, z, FACE_UP,
                                                        WorldTextures.layerTop(block), SHADE_UP);
                                        }
                                        // -Y (down face, shade 0.5)
                                        if (!occludes(level.getBlockState(x, y - 1, z))) {
                                                vi = emitFace(out, vi, x, y, z, FACE_DOWN,
                                                        WorldTextures.layerBottom(block), SHADE_DOWN);
                                        }
                                        // -Z (north, shade 0.8)
                                        if (!occludes(level.getBlockState(x, y, z - 1))) {
                                                vi = emitFace(out, vi, x, y, z, FACE_NORTH,
                                                        WorldTextures.layerSide(block), SHADE_NORTH_SOUTH);
                                        }
                                        // +Z (south, shade 0.8)
                                        if (!occludes(level.getBlockState(x, y, z + 1))) {
                                                vi = emitFace(out, vi, x, y, z, FACE_SOUTH,
                                                        WorldTextures.layerSide(block), SHADE_NORTH_SOUTH);
                                        }
                                        // -X (west, shade 0.6)
                                        if (!occludes(level.getBlockState(x - 1, y, z))) {
                                                vi = emitFace(out, vi, x, y, z, FACE_WEST,
                                                        WorldTextures.layerSide(block), SHADE_WEST_EAST);
                                        }
                                        // +X (east, shade 0.6)
                                        if (!occludes(level.getBlockState(x + 1, y, z))) {
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

        // ========== Dev mesh inspection (telemetry debugMesh bridge) ==========

        /**
         * Dumps the mesh state around one block as JSON: the section key,
         * the section mesh's vertex count, the mesher's own face decision
         * for the block (neighbors + atlas layers), and the ACTUAL vertex
         * tuples uploaded in the section VBO whose position falls within
         * [x-1..x+2, y-1..y+2, z-1..z+2] (7 floats each: x,y,z,u,v,layer,
         * color). Reading the VBO back is what turns "the trunk is in the
         * level but not on screen" from a mystery into a fact - either the
         * quads are missing here (mesher bug) or present (draw/depth bug).
         */
        public String debugMeshJson(int x, int y, int z) {
                StringBuilder sb = new StringBuilder(4096);
                if (!(minecraft.getLevel() instanceof ClientLevel level)) {
                        return "{}";
                }
                long secKey = ClientLevel.sectionKey(x >> 4, ClientLevel.sectionIndex(y), z >> 4);
                Mesh mesh = meshes.get(Long.valueOf(secKey));
                sb.append("{\"section\":").append(secKey);
                sb.append(",\"meshVerts\":").append(mesh != null ? mesh.vertices : -1);
                sb.append(",\"meshCount\":").append(meshes.size());
                sb.append(",\"dirtyCount\":").append(level.dirtyCount());
                int id = level.getBlockState(x, y, z);
                sb.append(",\"id\":").append(id);
                sb.append(",\"neighbors\":[");
                int[] nb = new int[] {
                                level.getBlockState(x, y + 1, z), level.getBlockState(x, y - 1, z),
                                level.getBlockState(x, y, z - 1), level.getBlockState(x, y, z + 1),
                                level.getBlockState(x - 1, y, z), level.getBlockState(x + 1, y, z) };
                for (int i = 0; i < nb.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(nb[i]);
                }
                sb.append(']');
                sb.append(",\"occludes\":[");
                for (int i = 0; i < nb.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(occludes(nb[i]));
                }
                sb.append(']');
                Blocks.Block block = Blocks.byId(id);
                sb.append(",\"layers\":").append("{\"top\":").append(WorldTextures.layerTop(block))
                                .append(",\"side\":").append(WorldTextures.layerSide(block))
                                .append(",\"bottom\":").append(WorldTextures.layerBottom(block)).append('}');
                sb.append(",\"localVerts\":[");
                int kept = 0;
                if (mesh != null && mesh.vbo != null && mesh.vertices > 0) {
                        WebGL2RenderingContext gl = ClientMain.getWebGL2();
                        if (gl != null) {
                                int floats = mesh.vertices * WorldGlRenderer.STRIDE_FLOATS;
                                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, mesh.vbo);
                                Float32Array all = readBufferSubData(gl,
                                                WebGL2RenderingContext.ARRAY_BUFFER, 0,
                                                Float32Array.create(floats));
                                PlatformOpenGL._wglBindBuffer(WebGL2RenderingContext.ARRAY_BUFFER, null);
                                if (all != null) {
                                        float xLo = x - 0.5F, xHi = x + 1.5F;
                                        float yLo = y - 0.5F, yHi = y + 1.5F;
                                        float zLo = z - 0.5F, zHi = z + 1.5F;
                                        for (int v = 0; v < mesh.vertices && kept < 400; v++) {
                                                int o = v * WorldGlRenderer.STRIDE_FLOATS;
                                                float px = all.get(o), py = all.get(o + 1), pz = all.get(o + 2);
                                                if (px < xLo || px > xHi || py < yLo || py > yHi
                                                                || pz < zLo || pz > zHi) {
                                                        continue;
                                                }
                                                if (kept > 0) {
                                                        sb.append(',');
                                                }
                                                kept++;
                                                for (int k = 0; k < WorldGlRenderer.STRIDE_FLOATS; k++) {
                                                        if (k > 0) {
                                                                sb.append(',');
                                                        }
                                                        if (k == 6) {
                                                                // The packed RGBA byte blob travels as
                                                                // a float - full-bright shades pack to
                                                                // 0xFFFFFFFF which IS a float NaN; emit
                                                                // the raw int bits so the JSON stays valid
                                                                sb.append(Float.floatToIntBits(all.get(o + k)));
                                                        } else {
                                                                sb.append(all.get(o + k));
                                                        }
                                                }
                                        }
                                }
                        }
                }
                sb.append(']');
                sb.append('}');
                return sb.toString();
        }

        /**
         * Reads a VBO's data store back (WebGL2 getBufferSubData) - the
         * dev mesh dump's raw float source.
         */
        @JSBody(params = { "gl", "target", "srcOffset", "dst" }, script =
                "gl.getBufferSubData(target, srcOffset, dst); return dst;")
        private static native Float32Array readBufferSubData(WebGL2RenderingContext gl,
                        int target, int srcOffset, Float32Array dst);


        /**
         * Vanilla's face-cull test (ChunkRenderBuilder): a face is drawn
         * when the neighbor block does not occlude it - air and non-opaque
         * renders (leaves, glass) leave the face visible, full opaque
         * cubes hide it. The air-only rule used before culled leaf-leaf
         * faces, so every hole in the cutout leaves texture looked
         * straight through the canopy to the sky (the "torn scraps"
         * title-panorama trees) - vanilla fancy leaves draw their inner
         * faces so texture holes reveal leaves behind them.
         */
        private static boolean occludes(int id) {
                return id != Blocks.AIR && Blocks.byId(id).opaque;
        }

        /**
         * Emits one textured quad (6 vertices x 7 floats) for the face of
         * the block at (x,y,z) - the geometry lives in {@link FaceTables}
         * so the JVM unit test can lock the vanilla invariants (outward
         * winding, per-face plane, UV order) without a browser.
         * Returns the new write offset.
         */
        private int emitFace(float[] out, int vi, int x, int y, int z, int face,
                int layer, float shade) {
                if (vi + 6 * 7 > out.length) {
                        ClientMain.warn("[LevelRenderer] mesh scratch overflow");
                        return vi;
                }
                return FaceTables.emitFace(out, vi, x, y, z, face, layer, shade);
        }

        // Face-geometry tables live in FaceTables (shared with the JVM
        // unit test). These aliases keep the mesher call sites readable.
        private static final int FACE_UP = FaceTables.FACE_UP;
        private static final int FACE_DOWN = FaceTables.FACE_DOWN;
        private static final int FACE_NORTH = FaceTables.FACE_NORTH;
        private static final int FACE_SOUTH = FaceTables.FACE_SOUTH;
        private static final int FACE_WEST = FaceTables.FACE_WEST;
        private static final int FACE_EAST = FaceTables.FACE_EAST;

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
                // (0,2,1) (0,3,2) - the REVERSE of vanilla's (0,1,2)(2,3,0):
                // these overlay corner sets are the original hand-authored
                // ones (wound CW from outside), so the reversed order makes
                // them front-facing (self-consistent, cross-product checked)
                int[] order = { 0, 2, 1, 0, 3, 2 };
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
