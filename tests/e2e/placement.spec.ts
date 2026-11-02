// Placement matrix: everything "placing a block" touches.
//
//   Scene A - the six-face matrix: a lone stone probe gets a cobblestone
//     neighbor attached to each of its 6 faces in turn. After every
//     attachment BOTH sides of the shared interface must cull (vanilla
//     occlusion: a full-cube neighbor hides the facing quad on both
//     blocks), a non-shared quad of the neighbor must render, and after
//     every removal the probe's quad must re-emerge. This is the
//     visibility rule the "invisible block sides" reports were about,
//     driven in all six directions.
//   Scene B - the section border: the world is meshed in 16x16x16
//     sections; a block placed on a x=16k boundary must remesh BOTH
//     sections - the terrain block across the border must cull its
//     facing quad exactly like a same-section neighbor.
//   Scene C - the real right-click path: hotbar item + aim + a genuine
//     right-button press must run vanilla use-item placement (hit face
//     offset rule), not the dev shortcut.
//
// All block edits (Scene A/B) ride setScene, which is the real
// Level.setBlockState path - the mesher, the culling and the section
// dirty queue all behave exactly as with a player edit.
import { expect, test } from './fixtures';
import type { Page } from '@playwright/test';
import {
  attachMilestoneScreenshot,
  enterWorld,
  findCleanAirCell,
  gameplayOf,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  readBlockAt,
  setHotbarSlot,
  setSceneBlocks,
  waitForClientBootstrap,
  waitForEditApplied,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

type MeshInfo = { localVerts: number[]; dirtyCount: number; id: number } | null;

async function readMesh(page: Page, spot: [number, number, number]): Promise<MeshInfo> {
  return page.evaluate(([x, y, z]) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: {
          debugMesh?: (x: number, y: number, z: number) => MeshInfo;
        };
      }
    ).__eaglercraftGame;
    return game?.debugMesh ? game.debugMesh(x, y, z) : null;
  }, spot);
}

/** Counts outward triangles whose normal is within eps of a face axis. */
function outwardTris(verts: number[], dir: 'UP' | 'DOWN' | 'NORTH' | 'SOUTH' | 'WEST' | 'EAST'): number {
  const n: Record<string, [number, number, number]> = {
    UP: [0, 1, 0], DOWN: [0, -1, 0], NORTH: [0, 0, -1],
    SOUTH: [0, 0, 1], WEST: [-1, 0, 0], EAST: [1, 0, 0],
  };
  const target = n[dir];
  let count = 0;
  for (let t = 0; t + 2 < verts.length / 7; t += 3) {
    const at = (k: number) => verts.slice(k * 7, k * 7 + 3);
    const a = at(t), b = at(t + 1), c = at(t + 2);
    const u = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const v = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    const cross = [
      u[1] * v[2] - u[2] * v[1],
      u[2] * v[0] - u[0] * v[2],
      u[0] * v[1] - u[1] * v[0],
    ];
    const mag = Math.hypot(...cross);
    if (mag < 1e-6) continue;
    const dot = (cross[0] * target[0] + cross[1] * target[1] + cross[2] * target[2]) / mag;
    if (dot > 0.999) count++;
  }
  return count;
}

test('block placement: six-face culling matrix, section borders, real right-click', async ({ browser }, testInfo) => {
  test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 600_000);
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
  if (!telemetry?.canEnterWorld || !(await hasGameplayBridge(page))) {
    await attachMilestoneScreenshot(page, testInfo, '01-placement-gate');
    test.skip(true, 'the deployed bundle cannot run the placement matrix yet');
    return;
  }

  const state = await enterWorld(page);
  const p = gameplayOf(state).player;
  const origin: [number, number, number] = [Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)];

  // ── Scene A: the six-face culling matrix ──
  const probe = await findCleanAirCell(page, origin, 6, 3);
  expect(probe, 'clean air for the floating probe must exist near spawn').toBeTruthy();
  await setSceneBlocks(page, [[probe![0], probe![1], probe![2], 1]]); // stone
  await waitForEditApplied(page, probe!, 1);
  const dirs: Array<{ name: 'UP' | 'DOWN' | 'NORTH' | 'SOUTH' | 'WEST' | 'EAST'; d: [number, number, number]; opp: 'UP' | 'DOWN' | 'NORTH' | 'SOUTH' | 'WEST' | 'EAST' }> = [
    { name: 'UP', d: [0, 1, 0], opp: 'DOWN' },
    { name: 'DOWN', d: [0, -1, 0], opp: 'UP' },
    { name: 'NORTH', d: [0, 0, -1], opp: 'SOUTH' },
    { name: 'SOUTH', d: [0, 0, 1], opp: 'NORTH' },
    { name: 'WEST', d: [-1, 0, 0], opp: 'EAST' },
    { name: 'EAST', d: [1, 0, 0], opp: 'WEST' },
  ];
  for (const dir of dirs) {
    const neighbor: [number, number, number] = [probe![0] + dir.d[0], probe![1] + dir.d[1], probe![2] + dir.d[2]];
    // attach: the probe's quad toward the neighbor must cull
    await setSceneBlocks(page, [[neighbor[0], neighbor[1], neighbor[2], 4]]); // cobblestone
    await waitForEditApplied(page, neighbor, 4);
    const probeMeshAttached = await readMesh(page, probe!);
    expect(
      outwardTris(probeMeshAttached?.localVerts ?? [], dir.name),
      `probe ${dir.name} quad must cull while a full cube sits there`,
    ).toBe(0);
    // Vanilla occlusion hides the shared interface from BOTH sides: the
    // neighbor's face toward the probe (a full cube) must cull too - the
    // same rule geometry.spec asserts as "the placed block's bottom is
    // culled against the ground". A non-shared quad proves the neighbor
    // itself actually remeshed.
    const neighborMesh = await readMesh(page, neighbor);
    expect(
      outwardTris(neighborMesh?.localVerts ?? [], dir.opp),
      `neighbor ${dir.opp} quad must cull against the probe (a full cube)`,
    ).toBe(0);
    const exposed: 'UP' | 'NORTH' = dir.opp === 'UP' ? 'NORTH' : 'UP';
    expect(
      outwardTris(neighborMesh?.localVerts ?? [], exposed),
      `neighbor ${exposed} quad must render (the neighbor itself must mesh)`,
    ).toBe(2);
    // detach: the probe's quad must re-emerge (neighbor visibility updates)
    await setSceneBlocks(page, [[neighbor[0], neighbor[1], neighbor[2], 0]]);
    await waitForEditApplied(page, neighbor, 0);
    const probeMeshDetached = await readMesh(page, probe!);
    expect(
      outwardTris(probeMeshDetached?.localVerts ?? [], dir.name),
      `probe ${dir.name} quad must re-appear after the neighbor is removed`,
    ).toBe(2);
  }

  // ── Scene B: the section border remeshes both sections ──
  // Sections are 16x16x16; an edit that changes a block's neighbor across
  // a section line must re-mesh BOTH sections. On the flat spawn pad
  // (radius 24, grass at y=-50) every horizontal quad is legitimately
  // culled - the pad's own solidity hides them - so the honest cross-
  // section scenario is DIG-driven: remove the pad block in section 1 at
  // (16,-50,z) and the pad block in section 0 at (15,-50,z) must RE-
  // EXPOSE its EAST quad; restore the block and the quad must re-cull.
  // Both remesh directions, across the boundary, no terrain assumptions.
  const borderZ: number | null = await page.evaluate((oz) => {
    const q = (
      self as unknown as {
        __eaglercraftGame?: { queryBlock?: (x: number, y: number, z: number) => number };
      }
    ).__eaglercraftGame?.queryBlock;
    if (!q) return null;
    for (let z = oz - 8; z <= oz + 8; z++) {
      // both sides of the x=15|16 section line: pad grass with open air above
      if (q(15, -50, z) !== 9) continue;
      if (q(16, -50, z) !== 9) continue;
      if (q(15, -49, z) !== 0 || q(16, -49, z) !== 0) continue; // tree-free columns
      if (q(15, -48, z) !== 0 || q(16, -48, z) !== 0) continue;
      return z;
    }
    return null;
  }, origin[2]);
  expect(borderZ, 'a tree-free pad column pair across the x=15|16 section line must exist').toBeTruthy();
  const west: [number, number, number] = [15, -50, borderZ!];
  const east: [number, number, number] = [16, -50, borderZ!];
  // before: the west block's EAST quad is culled by the solid pad neighbor
  const westBefore = await readMesh(page, west);
  expect(
    outwardTris(westBefore?.localVerts ?? [], 'EAST'),
    'the pad block must have its EAST quad culled while the neighbor is solid',
  ).toBe(0);
  // dig across the section line: the west block must RE-EXPOSE its EAST quad
  await setSceneBlocks(page, [[east[0], east[1], east[2], 0]]);
  await waitForEditApplied(page, east, 0);
  const westAfterDig = await readMesh(page, west);
  expect(
    outwardTris(westAfterDig?.localVerts ?? [], 'EAST'),
    'digging the neighbor across the section line must re-expose the WEST block EAST quad',
  ).toBe(2);
  // restore the block: the quad must re-cull (both remesh directions)
  await setSceneBlocks(page, [[east[0], east[1], east[2], 9]]);
  await waitForEditApplied(page, east, 9);
  const westAfterRestore = await readMesh(page, west);
  expect(
    outwardTris(westAfterRestore?.localVerts ?? [], 'EAST'),
    'restoring the neighbor across the section line must re-cull the WEST block EAST quad',
  ).toBe(0);
  expect(await readBlockAt(page, east[0], east[1], east[2]), 'the pad is restored to grass').toBe(9);

  // ── Scene C: the real right-click use path ──
  // Hotbar slot 0 (selected on spawn) is filled with cobblestone through
  // the bridge. Vanilla canSurvive rejects placing into the player's own
  // cell - aiming straight down from the pad makes the target the feet
  // cell and every placement is (correctly) rejected - so the scene uses
  // the features.spec-proven geometry: a 1-deep hole (dev-set via the
  // real setBlockState path), the player drops in, setLook(0, 30) aims
  // down-forward so the ray clears the hole wall onto a top face ~1.5
  // blocks away, and every retry re-pins the camera, verifies the crosshair
  // (face 'up', aim settled, target != feet cell), then right-clicks with
  // a press window wider than one slow CI frame. Success is verified by
  // the edits counter + held-stack consumption (the camera can drift a
  // cell between the aim read and the click), then the placed block is
  // located near the aimed cell.
  await setHotbarSlot(page, 0, 4, 64);
  await setSceneBlocks(page, [[0, -50, 0, 0]]); // dev-dig the hole under the player
  await waitForTelemetry(page, 20_000, (t) => {
    const gp = t.gameplay as ReturnType<typeof gameplayOf> | null | undefined;
    return gp?.player.onGround === true && Math.floor(gp.player.y) === -50;
  });
  const canvas = await page.evaluate(() => {
    const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
    const c =
      byId ??
      (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
    if (!c) return null;
    const rect = c.getBoundingClientRect();
    return { x: rect.left + c.clientWidth / 2, y: rect.top + c.clientHeight / 2 };
  });
  expect(canvas, 'the game canvas must exist').toBeTruthy();
  await page.mouse.click(canvas!.x, canvas!.y); // vanilla click-to-grab
  await page.waitForTimeout(700);
  const editsBefore = await (async () => {
    const t = await readGameTelemetry(page);
    return t?.gameplay ? gameplayOf(t).edits : -1;
  })();
  let placedReal = false;
  let placeX = -1;
  let placeY = -1;
  let placeZ = -1;
  const placeDeadline = Date.now() + 60_000;
  for (let i = 0; i < 25 && !placedReal && Date.now() < placeDeadline; i++) {
    await page.evaluate(() => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
        }
      ).__eaglercraftGame;
      // yaw 0 faces +Z, pitch 30 down-forward: the ray clears the hole
      // wall and lands ~1.5 blocks away on a placeable top face
      game?.setLook?.(0, 30);
    });
    await page.waitForTimeout(250);
    const t = await readGameTelemetry(page);
    const gp = t?.gameplay ? gameplayOf(t) : null;
    if (!gp?.hit || gp.hit.face !== 'up') continue;
    if (Math.abs(gp.player.pitch - 30) >= 2) continue;
    const cellX = gp.hit.x;
    const cellY = gp.hit.y + 1;
    const cellZ = gp.hit.z;
    if (cellX === Math.floor(gp.player.x) && cellY === Math.floor(gp.player.y) && cellZ === Math.floor(gp.player.z)) {
      continue; // the player's own cell - vanilla rejects that placement
    }
    placeX = cellX;
    placeY = cellY;
    placeZ = cellZ;
    await page.mouse.down({ button: 'right' });
    await page.waitForTimeout(300);
    await page.mouse.up({ button: 'right' });
    // survival placement consumes the held stack and adds exactly one edit
    const ok = await waitForTelemetry(page, 5_000, (t2) => {
      const g2 = t2.gameplay ? gameplayOf(t2) : null;
      if (!g2) return false;
      const sel = g2.hotbar.slots[g2.hotbar.selected];
      const consumed = !sel || sel[1] === 0 || sel[1] < 64;
      return g2.edits >= editsBefore + 1 && consumed;
    });
    placedReal = !!ok;
  }
  expect(
    placedReal,
    `the real right-click path must place the hotbar cobblestone (aimed near ${placeX},${placeY},${placeZ}; editsBefore=${editsBefore})`,
  ).toBe(true);
  // locate the placed cobblestone near the aimed cell (the camera can
  // drift a cell between the aim read and the click)
  let placedAt: string | null = null;
  for (let dx = -2; dx <= 2 && !placedAt; dx++) {
    for (let dz = -2; dz <= 2 && !placedAt; dz++) {
      for (let dy = -1; dy <= 1 && !placedAt; dy++) {
        if (await readBlockAt(page, placeX + dx, placeY + dy, placeZ + dz) === 4) {
          placedAt = `${placeX + dx},${placeY + dy},${placeZ + dz}`;
        }
      }
    }
  }
  expect(placedAt, 'the placed cobblestone must sit near the aimed cell').toBeTruthy();

  expect(pageErrors, 'the placement matrix must not throw uncaught page errors').toEqual([]);
  await attachMilestoneScreenshot(page, testInfo, '99-placement-final');
  await context.close();
});
