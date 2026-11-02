// Creative mode, end to end: world CREATION through the real UI, the
// vanilla creative flight rules, and instant breaking.
//
//   1. Title -> Singleplayer -> Create New World -> cycle Game Mode
//      survival -> hardcore -> creative (the CycleButton telemetry ids
//      document this exact transition: "gamemode:survival" -> click ->
//      "gamemode:hardcore" -> click -> "gamemode:creative").
//   2. Create: the world must load with gameplay.creative === true
//      (vanilla LocalPlayer(minecraft, creative) abilities wiring).
//   3. Flight: double-tap Space inside the vanilla 7-tick window toggles
//      flying; Space ascends, Shift descends; touching the ground cancels
//      flight (vanilla landing rule).
//   4. Instant break: a single left click removes the targeted block with
//      no destroy-stage progress (creative attack rule).
//   5. The creative picker opens on E and closes on Escape.
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  enterWorld,
  gameplayOf,
  getButtonCssCoords,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  readBlockAt,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
} from './support';
import type { Page } from '@playwright/test';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

/** Clicks a bridge button by telemetry id and waits for a screen transition. */
async function clickButton(page: Page, id: string, failMessage: string): Promise<void> {
  const findDeadline = Date.now() + 15_000;
  let coords: Awaited<ReturnType<typeof getButtonCssCoords>> = null;
  for (;;) {
    coords = await getButtonCssCoords(page, id);
    if (coords) break;
    if (Date.now() > findDeadline) {
      const telemetry = await readGameTelemetry(page);
      expect(coords, `${failMessage} (screen: ${telemetry?.screen}, buttons: ${telemetry?.buttons.map((b) => b.id).join(',')})`).not.toBeNull();
    }
    await page.waitForTimeout(250);
  }
  await page.mouse.move(coords!.cssX, coords!.cssY);
  await page.waitForTimeout(100);
  await page.mouse.click(coords!.cssX, coords!.cssY);
}

test('creative world: UI creation, flight rules, instant break, picker', async ({ browser }, testInfo) => {
  test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 420_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
  installConsoleRecorder(page);

  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
  await waitForFpsReading(page, 30_000);
  if (!(await hasGameplayBridge(page))) {
    await attachMilestoneScreenshot(page, testInfo, '01-creative-gate');
    test.skip(true, 'the deployed bundle predates the gameplay bridge');
    return;
  }

  // ── 1. the real world-creation UI ──
  await clickButton(page, 'singleplayer', 'the title screen must expose Singleplayer');
  await waitForTelemetry(page, 10_000, (t) => t.screen.includes('SelectWorldScreen'));
  await clickButton(page, 'createnewworld', 'the world list must expose Create New World');
  await waitForTelemetry(page, 10_000, (t) => t.screen.includes('CreateWorldScreen'));

  const modeId = async () => {
    const t = await readGameTelemetry(page);
    return t?.buttons.find((b) => b.id.startsWith('gamemode:'))?.id ?? null;
  };
  const waitForMode = async (want: string, timeoutMs: number): Promise<boolean> => {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      if ((await modeId()) === want) return true;
      if (Date.now() > deadline) return false;
      await page.waitForTimeout(250);
    }
  };
  expect(await modeId(), 'the game mode cycle must start on survival').toBe('gamemode:survival');
  await clickButton(page, 'gamemode:survival', 'the game mode cycle button must be clickable');
  expect(await waitForMode('gamemode:hardcore', 8_000), 'clicking survival must cycle to hardcore').toBe(true);
  await clickButton(page, 'gamemode:hardcore', 'the game mode cycle must pass through hardcore');
  expect(await waitForMode('gamemode:creative', 8_000), 'clicking hardcore must cycle to creative').toBe(true);

  // ── 2. create: the world must load straight into creative ──
  await clickButton(page, 'createnewworld', 'the create button must be clickable');
  const inWorld = await waitForTelemetry(page, 45_000, (t) => t.inWorld);
  expect(inWorld, 'creating the world must enter it').toBeTruthy();
  expect(gameplayOf(inWorld!).creative, 'the created world must be Creative').toBe(true);

  // ── 3. flight ──
  const groundY = gameplayOf(inWorld!).player.y;
  // double-tap Space inside the 7-tick window
  await page.keyboard.down('Space');
  await page.waitForTimeout(80);
  await page.keyboard.up('Space');
  await page.waitForTimeout(90);
  await page.keyboard.down('Space');
  await page.waitForTimeout(60);
  await page.keyboard.up('Space');
  const flying = await waitForTelemetry(page, 10_000, (t) => gameplayOf(t).player.flying === true);
  expect(flying, 'a double-tap jump must toggle creative flight').toBeTruthy();
  // Space ascends
  const yBeforeUp = gameplayOf(flying!).player.y;
  await page.keyboard.down('Space');
  const rose = await waitForTelemetry(page, 8_000, (t) => gameplayOf(t).player.y > yBeforeUp + 0.4);
  await page.keyboard.up('Space');
  expect(rose, 'holding Space must ascend while flying').toBeTruthy();
  // Shift descends
  const yBeforeDown = gameplayOf(rose!).player.y;
  await page.keyboard.down('ShiftLeft');
  const fell = await waitForTelemetry(page, 8_000, (t) => gameplayOf(t).player.y < yBeforeDown - 0.4);
  expect(fell, 'holding Shift must descend while flying').toBeTruthy();
  // keep descending until landing - vanilla cancels flight on the ground
  const landed = await waitForTelemetry(page, 25_000, (t) => {
    const gp = gameplayOf(t).player;
    return gp.onGround && !gp.flying;
  });
  await page.keyboard.up('ShiftLeft');
  expect(
    landed,
    `descending onto the ground must cancel flight (last: ${JSON.stringify(landed?.gameplay?.player)})`,
  ).toBeTruthy();
  expect(Math.abs(gameplayOf(landed!).player.y - groundY), 'the flight loop must return to the pad surface').toBeLessThan(1.5);

  // ── 4. instant break ──
  await page.evaluate(() => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
      }
    ).__eaglercraftGame;
    game?.setLook?.(0, 90);
  });
  await waitForTelemetry(page, 10_000, (t) => {
    const gp = t.gameplay as ReturnType<typeof gameplayOf> | null | undefined;
    return !!gp?.hit && gp.hit.y === -50;
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
  await page.mouse.click(canvas!.x, canvas!.y); // click-to-grab; in creative this single click IS the attack
  const broken = await waitForTelemetry(page, 10_000, (t) => {
    const gp = t.gameplay as ReturnType<typeof gameplayOf> | null | undefined;
    return gp?.hit == null || gp.hit.y !== -50;
  });
  expect(await readBlockAt(page, 0, -50, 0), 'a single creative click must break the targeted block').toBe(0);
  expect(broken, 'the aim must be off the broken cell afterwards').toBeTruthy();

  // ── 5. the creative picker ──
  await page.keyboard.press('KeyE');
  const pickerOpen = await waitForTelemetry(page, 8_000, (t) => t.screen.includes('CreativeModeInventoryScreen'));
  expect(pickerOpen, 'E must open the creative picker').toBeTruthy();
  await page.keyboard.press('Escape');
  const pickerClosed = await waitForTelemetry(page, 8_000, (t) => !t.screen.includes('CreativeModeInventoryScreen'));
  expect(pickerClosed, 'Escape must close the creative picker').toBeTruthy();

  expect(pageErrors, 'the creative flow must not throw uncaught page errors').toEqual([]);
  await attachMilestoneScreenshot(page, testInfo, '99-creative-final');
  await context.close();
});

// The default survival world sanity-check stays available for contrast:
// if creative regressed into "always survival" the assertion above fails,
// and this guard documents which default this suite expects.
test('the default world stays Survival', async ({ browser }, testInfo) => {
  test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  installConsoleRecorder(page);
  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT)).toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT)).toBe(true);
  const telemetry = await readGameTelemetry(page);
  if (!telemetry?.canEnterWorld || !(await hasGameplayBridge(page))) {
    test.skip(true, 'the deployed bundle cannot enter a world yet');
    return;
  }
  const state = await enterWorld(page);
  expect(gameplayOf(state).creative, 'the seeded default world must remain Survival').toBe(false);
  await context.close();
});
