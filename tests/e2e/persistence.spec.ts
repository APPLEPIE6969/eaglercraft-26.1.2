// World persistence: the localStorage stand-in for vanilla's region files.
//
//   1. Enter the default world, place a marker block through the real
//      setBlockState path, set the day time.
//   2. Save and Quit to Title through the real pause-menu button
//      ("saveandquittotitle") - the only path that flushes the edit
//      overlay and the day time (ClientLevel.saveEdits/saveDayTime).
//   3. RELOAD the whole page (a full TeaVM reboot, not a screen swap) and
//      enter the world again.
//   4. The marker block must still be there, and the persisted day time
//      must sit in localStorage under the world's time key.
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
  waitForEditApplied,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
} from './support';
import { getButtonCssCoords } from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

test('world edits survive a full save-quit + reload round trip', async ({ browser }, testInfo) => {
  test.setTimeout((BOOT_TIMEOUT + READY_TIMEOUT) * 2 + 300_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
  installConsoleRecorder(page);

  // ── session 1: build the marker ──
  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT)).toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT)).toBe(true);
  await waitForFpsReading(page, 30_000);
  const telemetry = await readGameTelemetry(page);
  if (!telemetry?.canEnterWorld || !(await hasGameplayBridge(page))) {
    await attachMilestoneScreenshot(page, testInfo, '01-persistence-gate');
    test.skip(true, 'the deployed bundle cannot run the persistence round trip yet');
    return;
  }
  const state = await enterWorld(page);
  const p = gameplayOf(state).player;
  const marker = await findCleanAirCell(page, [Math.floor(p.x), Math.floor(p.y), Math.floor(p.z)], 6, 3);
  expect(marker, 'clean air near spawn must exist for the marker').toBeTruthy();
  await setSceneBlocks(page, [[marker![0], marker![1], marker![2], 4]]);
  await waitForEditApplied(page, marker!, 4);
  // 18000 = midnight-ish day time (vanilla Data.day); the write happens
  // on save, the read on the next load
  await setDayTime(page, 18_000);

  // ── save and quit through the real pause menu ──
  await page.keyboard.press('Escape');
  const paused = await waitForTelemetry(page, 10_000, (t) => t.screen.includes('PauseScreen'));
  expect(paused, 'Escape must open the pause menu').toBeTruthy();
  const findDeadline = Date.now() + 15_000;
  let coords: Awaited<ReturnType<typeof getButtonCssCoords>> = null;
  for (;;) {
    coords = await getButtonCssCoords(page, 'saveandquittotitle');
    if (coords) break;
    if (Date.now() > findDeadline) {
      expect(coords, 'the pause menu must expose Save and Quit to Title').not.toBeNull();
    }
    await page.waitForTimeout(250);
  }
  await page.mouse.click(coords!.cssX, coords!.cssY);
  const quit = await waitForTelemetry(page, 20_000, (t) => !t.inWorld);
  expect(quit, 'Save and Quit must drop back to the title screen').toBeTruthy();

  // ── session 2: a full reboot in the same browser context ──
  await page.reload({ waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the client must reboot').toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen again').toBe(true);
  await waitForFpsReading(page, 30_000);
  const state2 = await enterWorld(page);

  // ── the world state must have survived ──
  expect(
    await readBlockAt(page, marker![0], marker![1], marker![2]),
    'the marker block must survive the save-quit + reload round trip',
  ).toBe(4);
  // The persisted day time sits under a namespaced key (PlatformRuntime
  // wraps every key in its config namespace) AND it keeps ticking between
  // the set and the save, so the stored value is 18000 plus the ticks
  // elapsed while the pause menu was driven. Assert the range: the day
  // time must have been SAVED (not reset to the fresh-world 1000) and
  // must have advanced monotonically from 18000.
  const savedTime = await page.evaluate(() => {
    const hits: { key: string; value: number }[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key || !key.includes('world.time')) continue;
      const raw = localStorage.getItem(key);
      const n = raw === null ? NaN : Number(raw);
      if (!Number.isNaN(n)) hits.push({ key, value: n });
    }
    return hits;
  });
  const daySaved = savedTime.some((h) => h.value >= 18_000 && h.value < 100_000);
  expect(
    daySaved,
    `the persisted day time must be a saved value >= 18000 (time keys: ${JSON.stringify(savedTime)})`,
  ).toBe(true);
  expect(gameplayOf(state2).edits, 'the edit counter must reflect the restored overlay').toBeGreaterThanOrEqual(1);

  expect(pageErrors, 'the persistence round trip must not throw uncaught page errors').toEqual([]);
  await attachMilestoneScreenshot(page, testInfo, '99-persistence-final');
  await context.close();
});
