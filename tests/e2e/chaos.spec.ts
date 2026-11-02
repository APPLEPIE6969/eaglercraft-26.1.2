// Chaos fuzz: a seeded storm of real inputs and bridge commands, with the
// vanilla invariants checked after every round. This is the "anything a
// player can mash" test - the goal is not a scripted outcome but that the
// game NEVER crashes, NEVER produces a non-finite player state, NEVER
// throws, and stays responsive after arbitrary abuse.
//
//   Rounds are deterministic (seed 0x5EED) so a failure reproduces; the
//   actions cover movement keys, look snaps, jumps, attack/use presses,
//   hotbar digits and scrolls, the inventory toggle, the pause toggle,
//   day-time jumps and scene edits. After every round:
//     - the player position must be finite and bounded (no NaN/Infinity,
//       no clipping to |y| > 2000),
//     - the game state must never be 5 (EaglerCraft STATE_CRASHED),
//     - the tick clock must keep advancing (no deadlock),
//     - no new uncaught page errors.
//   A final probe asserts the world is still editable and attaches a
//   screenshot artifact.
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  enterWorld,
  findCleanAirCell,
  gameplayOf,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  readBlockAt,
  setDayTime,
  setSceneBlocks,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;
const ROUNDS = 24;

/** Deterministic LCG so a red run reproduces the exact same storm. */
function lcg(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

test('seeded input chaos never crashes, wedges or desyncs the game', async ({ browser }, testInfo) => {
  test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 420_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
  installConsoleRecorder(page);

  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT)).toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT)).toBe(true);
  await waitForFpsReading(page, 30_000);
  const telemetry = await readGameTelemetry(page);
  if (!telemetry?.canEnterWorld || !(await hasGameplayBridge(page))) {
    await attachMilestoneScreenshot(page, testInfo, '01-chaos-gate');
    test.skip(true, 'the deployed bundle cannot run the chaos fuzz yet');
    return;
  }
  await enterWorld(page);

  // grab the pointer once - real mouse buttons then act on the world
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
  await page.mouse.click(canvas!.x, canvas!.y);
  await page.waitForTimeout(600);

  const rng = lcg(0x5eed);
  const MOVE_KEYS = ['KeyW', 'KeyA', 'KeyS', 'KeyD'];
  const heldKeys: string[] = [];
  let lastTicks = -1;

  for (let round = 0; round < ROUNDS; round++) {
    const roll = rng();
    if (roll < 0.22) {
      // tap or release a movement key
      const key = MOVE_KEYS[Math.floor(rng() * MOVE_KEYS.length)];
      if (heldKeys.includes(key)) {
        await page.keyboard.up(key);
        heldKeys.splice(heldKeys.indexOf(key), 1);
      } else {
        await page.keyboard.down(key);
        heldKeys.push(key);
      }
    } else if (roll < 0.34) {
      // look snap to a random direction (deterministic)
      const yaw = Math.floor(rng() * 720) - 360;
      const pitch = Math.floor(rng() * 180) - 90;
      await page.evaluate(([yawV, pitchV]) => {
        const game = (
          self as unknown as {
            __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
          }
        ).__eaglercraftGame;
        game?.setLook?.(yawV, pitchV);
      }, [yaw, pitch]);
    } else if (roll < 0.44) {
      // jump / fly-toggle taps
      await page.keyboard.down('Space');
      await page.waitForTimeout(70);
      await page.keyboard.up('Space');
    } else if (roll < 0.52) {
      // attack press
      await page.mouse.down({ button: 'left' });
      await page.waitForTimeout(120 + Math.floor(rng() * 200));
      await page.mouse.up({ button: 'left' });
    } else if (roll < 0.6) {
      // use press (placement attempts)
      await page.mouse.down({ button: 'right' });
      await page.waitForTimeout(150);
      await page.mouse.up({ button: 'right' });
    } else if (roll < 0.68) {
      // hotbar digit
      await page.keyboard.press(`Digit${1 + Math.floor(rng() * 9)}`);
    } else if (roll < 0.74) {
      // hotbar scroll
      await page.mouse.wheel(0, rng() > 0.5 ? 120 : -120);
    } else if (roll < 0.8) {
      // inventory toggle
      await page.keyboard.press('KeyE');
      await page.waitForTimeout(200);
      await page.keyboard.press('Escape');
    } else if (roll < 0.86) {
      // day-time jump (vanilla /time set)
      await setDayTime(page, Math.floor(rng() * 24000));
    } else if (roll < 0.93) {
      // a scene edit near the player through the real setBlockState path
      const spot = await findCleanAirCell(page, [Math.floor(rng() * 4) - 2, Math.floor(rng() * 4) - 2, Math.floor(rng() * 4) - 2], 3, 1);
      if (spot) {
        await setSceneBlocks(page, [[spot[0], spot[1], spot[2], rng() > 0.5 ? 4 : 0]]);
      }
    } else {
      // pause toggle
      await page.keyboard.press('Escape');
      await page.waitForTimeout(200);
      await page.keyboard.press('Escape');
    }
    await page.waitForTimeout(250);

    // ── invariants after every round ──
    const snap = await readGameTelemetry(page);
    const gp = snap?.gameplay ? (snap.gameplay as ReturnType<typeof gameplayOf>) : null;
    expect(snap, `round ${round}: the telemetry bridge must still publish`).toBeTruthy();
    expect(snap!.gameState, `round ${round}: the game must never crash (STATE_CRASHED=5)`).not.toBe(5);
    expect(gp, `round ${round}: the gameplay snapshot must survive the storm`).toBeTruthy();
    const pos = gp!.player;
    expect(
      Number.isFinite(pos.x) && Number.isFinite(pos.y) && Number.isFinite(pos.z),
      `round ${round}: the player position must stay finite (got ${JSON.stringify(pos)})`,
    ).toBe(true);
    expect(
      Math.abs(pos.y) < 2000,
      `round ${round}: the player must stay inside the world (y=${pos.y})`,
    ).toBe(true);
    expect(snap!.ticks, `round ${round}: the tick clock must keep advancing`).toBeGreaterThan(lastTicks);
    lastTicks = snap!.ticks;
    expect(pageErrors, `round ${round}: no uncaught page errors during the storm`).toEqual([]);
  }

  // release everything and settle
  for (const key of heldKeys) await page.keyboard.up(key);

  // ── final responsiveness probe: the world is still editable ──
  const spot = await findCleanAirCell(page, [0, 0, 0], 4, 1);
  if (spot) {
    await setSceneBlocks(page, [[spot[0], spot[1], spot[2], 4]]);
    await page.waitForTimeout(1_500);
    expect(await readBlockAt(page, spot[0], spot[1], spot[2]), 'the world must still accept edits after the storm').toBe(4);
  }
  expect(pageErrors, 'no uncaught page errors across the whole storm').toEqual([]);
  await attachMilestoneScreenshot(page, testInfo, '99-chaos-final');
  await context.close();
});
