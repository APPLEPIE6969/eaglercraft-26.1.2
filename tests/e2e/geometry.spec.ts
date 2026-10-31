// Geometry regression: the y+2 floating-canopy bug (top faces emitted one
// block above their side quads) shipped past every screenshot check because
// displacement changes neither normals nor counts. This spec locks the FULL
// pipeline - mesher call sites included - by reading the uploaded VBO back
// through the debugMesh bridge and asserting POSITIONS:
//   - a lone floating block has all 6 faces, each on its vanilla plane
//     (UP on y+1 catches the canopy bug, DOWN on y, sides on x/z planes)
//   - digging a hole re-emits the 4 wall faces and the floor's top face
//   - placing a block against terrain emits its 4 side faces + top
// Uses setScene (the real setBlockState path) and queryBlock (synchronous).
import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  enterWorld,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

/** Waits until the mesher's dirty-section queue drains (all edits meshed). */
async function waitForMeshDrain(page: Page, timeoutMs = 45_000): Promise<void> {
  const ok = await page
    .waitForFunction(() => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: {
            gameplay?: { player?: { x: number; y: number; z: number } };
            debugMesh?: (x: number, y: number, z: number) => { dirtyCount: number } | null;
          };
        }
      ).__eaglercraftGame;
      const p = game?.gameplay?.player;
      const d = game?.debugMesh;
      if (!p || !d) return false;
      const m = d(Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
      return !!m && m.dirtyCount === 0;
    }, { timeout: timeoutMs })
    .then(() => true)
    .catch(() => false);
  expect(ok, 'the mesh queue must drain after edits').toBe(true);
}

interface FaceAnalysis {
  counts: Record<string, number>;
  plane: Record<string, number>;
}

/**
 * Classifies a block's localVerts (7 floats/vertex, quads expanded as
 * v0,v1,v2,v2,v3,v0) into per-direction OUTWARD triangle counts plus the
 * plane coordinate of each direction. Runs in-page (evaluate) or in node -
 * pure math on plain arrays.
 */
function analyzeBlock(verts: number[], block: [number, number, number]): FaceAnalysis {
  const [bx, by, bz] = block;
  const cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
  type Tri = { dir: string; outward: boolean; vals: number[] };
  const tris: Tri[] = [];
  for (let t = 0; t + 2 < verts.length / 7; t += 3) {
    const a = verts.slice(t * 7, t * 7 + 3);
    const b = verts.slice((t + 1) * 7, (t + 1) * 7 + 3);
    const c = verts.slice((t + 2) * 7, (t + 2) * 7 + 3);
    const u = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const v = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    const n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
    const mag = Math.hypot(n[0], n[1], n[2]);
    if (mag < 1e-6) continue;
    const nn = n.map((k) => k / mag);
    const tc = [(a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3];
    const outward = nn[0] * (tc[0] - cx) + nn[1] * (tc[1] - cy) + nn[2] * (tc[2] - cz) > 0;
    const ax = Math.abs(nn[0]), ay = Math.abs(nn[1]), az = Math.abs(nn[2]);
    const dir = ax >= ay && ax >= az ? (nn[0] > 0 ? 'EAST' : 'WEST')
      : ay >= az ? (nn[1] > 0 ? 'UP' : 'DOWN') : (nn[2] > 0 ? 'SOUTH' : 'NORTH');
    const axis = dir === 'UP' || dir === 'DOWN' ? 1 : dir === 'NORTH' || dir === 'SOUTH' ? 2 : 0;
    tris.push({ dir, outward, vals: [a[axis], b[axis], c[axis]] });
  }
  const counts: Record<string, number> = {};
  const plane: Record<string, number> = {};
  for (const t of tris) {
    if (!t.outward) continue;
    counts[t.dir] = (counts[t.dir] || 0) + 1;
  }
  for (const dir of Object.keys(counts)) {
    const vals = tris.filter((t) => t.dir === dir && t.outward).flatMap((t) => t.vals);
    vals.sort((p, q) => p - q);
    plane[dir] = Math.round(vals[Math.floor(vals.length / 2)]);
  }
  return { counts, plane };
}

test('VBO plane invariants: floating block, dug hole, placed block', async ({ browser }, testInfo) => {
  test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 240_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
  installConsoleRecorder(page);

  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
  await waitForFpsReading(page, 30_000);

  const telemetry = await readGameTelemetry(page);
  if (!telemetry?.canEnterWorld) {
    await attachMilestoneScreenshot(page, testInfo, '01-geometry-gate');
    test.skip(true, `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`);
    return;
  }
  if (!(await hasGameplayBridge(page))) {
    await attachMilestoneScreenshot(page, testInfo, '01-geometry-gate');
    test.skip(true, 'the deployed bundle predates the gameplay bridge (queryBlock/setScene/debugMesh) — rebuild to activate this test');
    return;
  }

  const state = await enterWorld(page);
  const p = state.gameplay!.player;
  const fx = Math.floor(p.x), fy = Math.floor(p.y), fz = Math.floor(p.z);

  // ── Scene 1: lone floating stone block (all 6 faces, vanilla planes) ──
  // The spot needs a clean 3x3x3 air neighborhood so the debugMesh box
  // [x-0.5..x+1.5] catches exactly the block's own 36 verts (no leaks).
  const floatSpot: [number, number, number] | null = await page.evaluate(([fx, fy, fz]) => {
    const g = (self as unknown as { __eaglercraftGame?: GameBridge }).__eaglercraftGame!;
    for (let cand = 0; cand < 24; cand++) {
      const x = fx + 3 + (cand % 4), y = fy + 3 + Math.floor(cand / 4), z = fz + (cand % 2);
      let clean = true;
      for (let dy = -1; dy <= 1 && clean; dy++) {
        for (let dz = -1; dz <= 1 && clean; dz++) {
          for (let dx = -1; dx <= 1 && clean; dx++) {
            if (g.queryBlock!(x + dx, y + dy, z + dz) !== 0) clean = false;
          }
        }
      }
      if (clean) {
        g.setScene!({ blocks: [[x, y, z, 1]], hideHud: 1 });
        return [x, y, z];
      }
    }
    return null;
  }, [fx, fy, fz]);
  expect(floatSpot, 'air space must exist above spawn for the floating probe').toBeTruthy();
  await waitForMeshDrain(page);
  const floatVerts = await readLocalVerts(page, floatSpot!);
  expect(floatVerts.length / 7, 'a lone block emits exactly 6 quads = 36 verts').toBe(36);
  const floatFaces = analyzeBlock(floatVerts, floatSpot!);
  for (const dir of ['UP', 'DOWN', 'NORTH', 'SOUTH', 'WEST', 'EAST']) {
    expect(floatFaces.counts[dir] ?? 0, `${dir} face: exactly 2 outward triangles`).toBe(2);
  }
  expect(floatFaces.plane.UP, 'UP face on y+1 (the y+2 floating-canopy regression)').toBe(floatSpot![1] + 1);
  expect(floatFaces.plane.DOWN).toBe(floatSpot![1]);
  expect(floatFaces.plane.NORTH).toBe(floatSpot![2]);
  expect(floatFaces.plane.SOUTH).toBe(floatSpot![2] + 1);
  expect(floatFaces.plane.WEST).toBe(floatSpot![0]);
  expect(floatFaces.plane.EAST).toBe(floatSpot![0] + 1);

  // ── Scene 2: dig a hole (walls + floor re-emitted) ──
  const hole: [number, number, number] | null = await page.evaluate(([fx, fy, fz]) => {
    const g = (self as unknown as { __eaglercraftGame?: GameBridge }).__eaglercraftGame!;
    const x = fx + 3, z = fz + 3;
    for (let y = fy + 6; y >= fy - 8; y--) {
      if (g.queryBlock!(x, y, z) !== 0) {
        g.setScene!({ blocks: [[x, y, z, 0]], hideHud: 1 });
        return [x, y, z];
      }
    }
    return null;
  }, [fx, fy, fz]);
  expect(hole, 'a diggable surface block must exist near spawn').toBeTruthy();
  await waitForMeshDrain(page);
  const walls = await page.evaluate(([hx, hy, hz]) => {
    const g = (self as unknown as { __eaglercraftGame?: GameBridge }).__eaglercraftGame!;
    const out: Record<string, number> = {};
    for (const [dx, dz, name] of [[0, -1, 'NORTH'], [0, 1, 'SOUTH'], [-1, 0, 'WEST'], [1, 0, 'EAST']] as const) {
      const m = g.debugMesh!(hx + dx, hy, hz + dz);
      out[name] = m ? m.localVerts.length / 7 : -1;
    }
    const floor = g.debugMesh!(hx, hy - 1, hz);
    out.FLOOR = floor ? floor.localVerts.length / 7 : -1;
    return out;
  }, hole!);
  for (const name of ['NORTH', 'SOUTH', 'WEST', 'EAST']) {
    expect(walls[name], `${name} hole wall face emitted (>=6 verts)`).toBeGreaterThanOrEqual(6);
  }
  expect(walls.FLOOR, 'hole floor top face emitted (>=6 verts)').toBeGreaterThanOrEqual(6);

  // ── Scene 3: place a block against the terrain ──
  const placed: [number, number, number] | null = await page.evaluate(([fx, fy, fz]) => {
    const g = (self as unknown as { __eaglercraftGame?: GameBridge }).__eaglercraftGame!;
    const x = fx + 2, z = fz + 2;
    for (let y = fy + 6; y >= fy - 8; y--) {
      if (g.queryBlock!(x, y, z) !== 0) {
        g.setScene!({ blocks: [[x, y + 1, z, 4]], hideHud: 1 });
        return [x, y + 1, z];
      }
    }
    return null;
  }, [fx, fy, fz]);
  expect(placed, 'a surface to build on must exist near spawn').toBeTruthy();
  await waitForMeshDrain(page);
  const placedVerts = await readLocalVerts(page, placed!);
  const placedFaces = analyzeBlock(placedVerts, placed!);
  for (const dir of ['UP', 'NORTH', 'SOUTH', 'WEST', 'EAST']) {
    expect(placedFaces.counts[dir] ?? 0, `placed block ${dir} face: 2 outward triangles`).toBe(2);
  }
  expect(placedFaces.plane.UP, 'placed block UP face on y+1').toBe(placed![1] + 1);
  // The bottom is culled against the ground (vanilla face-cull rule): the
  // block's box may only contain neighbor-plane leak verts, never 2 clean
  // outward DOWN triangles.
  expect(placedFaces.counts.DOWN ?? 0, 'bottom face culled against the ground').toBeLessThanOrEqual(1);

  expect(pageErrors, 'no page errors during geometry scenes').toEqual([]);
  await context.close();

  type GameBridge = {
    queryBlock?: (x: number, y: number, z: number) => number;
    debugMesh?: (x: number, y: number, z: number) => { localVerts: number[]; dirtyCount: number; id: number } | null;
    setScene?: (o: { blocks: [number, number, number, number][]; hideHud?: number }) => void;
  };

  async function readLocalVerts(page: Page, spot: [number, number, number]): Promise<number[]> {
    return page.evaluate(([x, y, z]) => {
      const g = (self as unknown as { __eaglercraftGame?: GameBridge }).__eaglercraftGame!;
      const m = g.debugMesh!(x, y, z);
      return m ? m.localVerts : [];
    }, spot);
  }
});
