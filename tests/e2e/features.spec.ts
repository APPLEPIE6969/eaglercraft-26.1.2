import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  attachSessionSummary,
  enterWorld,
  gameplayOf,
  getButtonCssCoords,
  getGuiCssCoords,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
  type Gameplay,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

type Telemetry = NonNullable<Awaited<ReturnType<typeof readGameTelemetry>>>;


/**
 * Clicks a bridge button by its telemetry id.
 *
 * The lookup polls instead of reading once: right after a navigation the
 * telemetry bridge still reports the PREVIOUS screen for up to a tick, so a
 * single-shot read either misses the button or - for ids that exist on
 * several screens ("done") - resolves the stale screen's button and clicks
 * its coordinates on the new one. When `expectedScreen` is given, the poll
 * waits until the bridge reports that screen AND carries the button, which
 * makes the click unambiguous. Class-name suffixes must be specific enough
 * not to substring-match ("OptionsScreen" IS a substring of
 * "SoundOptionsScreen" - use "screens.options.OptionsScreen").
 *
 * When `untilScreen` is given the click is verified by its EFFECT: the
 * helper polls for that screen and re-clicks (re-looking the button up)
 * up to three times, because a single click can physically drop on a
 * loaded runner and leave the screen unchanged.
 */
async function clickBridgeButton(
  page: Page,
  id: string,
  failMessage: string,
  expectedScreen?: string,
  untilScreen?: string,
): Promise<void> {
  const attempts = untilScreen ? 3 : 1;
  for (let attempt = 0; attempt < attempts; attempt++) {
    const findDeadline = Date.now() + 12_000;
    let coords: Awaited<ReturnType<typeof getButtonCssCoords>> = null;
    for (;;) {
      const telemetry = await readGameTelemetry(page);
      const screenOk =
        !expectedScreen || (telemetry?.screen ?? '').includes(expectedScreen);
      if (screenOk && telemetry?.buttons.some((b) => b.id === id)) {
        coords = await getButtonCssCoords(page, id);
        if (coords) break;
      }
      if (Date.now() > findDeadline) {
        expect(
          coords,
          `${failMessage} (screen: ${telemetry?.screen})`,
        ).not.toBeNull();
      }
      await page.waitForTimeout(250);
    }
    await page.mouse.move(coords!.cssX, coords!.cssY);
    await page.waitForTimeout(100);
    await page.mouse.click(coords!.cssX, coords!.cssY);
    if (!untilScreen) return;
    const untilDeadline = Date.now() + 8_000;
    for (;;) {
      const telemetry = await readGameTelemetry(page);
      if ((telemetry?.screen ?? '').includes(untilScreen)) return;
      if (Date.now() > untilDeadline) {
        break; // re-look the button up and click again
      }
      await page.waitForTimeout(250);
    }
  }
  expect(
    false,
    `${failMessage}: clicked "${id}" ${attempts}x without reaching ${untilScreen}`,
  ).toBe(true);
}





/** Reads a world block through the bridge's synchronous queryBlock. */
async function queryBlock(page: Page, x: number, y: number, z: number): Promise<number> {
  return page.evaluate(
    ({ x: bx, y: by, z: bz }) => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: { queryBlock?: (x: number, y: number, z: number) => number };
        }
      ).__eaglercraftGame;
      return game?.queryBlock ? game.queryBlock(bx, by, bz) : -2;
    },
    { x, y, z },
  );
}




test.describe('gameplay, options sub-screens and multiplayer gateway', () => {
  test('movement physics, jump and hotbar keys work in-world (capability-gated)', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry?.canEnterWorld) {
      await attachMilestoneScreenshot(page, testInfo, '01-gameplay-gate');
      test.skip(true, `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`);
      return;
    }
    if (!(await hasGameplayBridge(page))) {
      await attachMilestoneScreenshot(page, testInfo, '01-gameplay-gate');
      test.skip(true, 'the deployed bundle predates the gameplay bridge (queryBlock/setLook) — rebuild to activate this test');
      return;
    }

    // ── Enter the world and assert the vanilla spawn state ──
    const state = await enterWorld(page);
    const gp = gameplayOf(state);
    // Flat preset spawn: feet on the grass surface, centered
    expect(
      Math.abs(gp.player.x - 0.5) < 0.01 && Math.abs(gp.player.z - 0.5) < 0.01,
      `the player must spawn centered (got: ${JSON.stringify(gp.player)})`,
    ).toBe(true);
    expect(gp.player.onGround, 'the player must spawn on the ground').toBe(true);
    expect(gp.spawn, 'the spawn point must be the flat preset surface').toEqual([0.5, -60, 0.5]);
    expect(gp.hotbar.slots, 'the hotbar must have nine slots').toHaveLength(9);
    expect(gp.hotbar.selected, 'hotbar selection starts at slot 0').toBe(0);

    // ── The flat preset layers, verified through queryBlock ──
    expect(await queryBlock(page, 0, -64, 0), 'bedrock at the world floor').toBe(33);
    expect(await queryBlock(page, 0, -63, 0), 'dirt layer 1').toBe(10);
    expect(await queryBlock(page, 0, -62, 0), 'dirt layer 2').toBe(10);
    expect(await queryBlock(page, 0, -61, 0), 'grass on top').toBe(9);
    expect(await queryBlock(page, 0, -60, 0), 'air above the grass').toBe(0);

    // ── Walk forward (W): vanilla ground physics must move the player ──
    // Fixed wall-clock hold, then release and settle - the distance is
    // tick-rate dependent, so only the movement itself is asserted
    const startX = gp.player.x;
    const startZ = gp.player.z;
    await page.keyboard.down('KeyW');
    await page.waitForTimeout(800);
    await page.keyboard.up('KeyW');
    const walkState = await waitForTelemetry(page, 5_000, (t) => {
      const p = gameplayOf(t).player;
      return Math.hypot(p.x - startX, p.z - startZ) > 0.3;
    });
    const walked = gameplayOf(walkState).player;
    const walkDist = Math.hypot(walked.x - startX, walked.z - startZ);
    expect(
      walkDist,
      `holding W must move the player (walked: ${walkDist.toFixed(3)} blocks)`,
    ).toBeGreaterThan(0.3);
    expect(walkDist, 'a sub-second vanilla walk must stay bounded').toBeLessThan(15);
    expect(walked.onGround, 'the player stays on the ground walking').toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '02-walked-forward');

    // ── Jump (Space): vanilla 0.42 impulse must lift the player ──
    const groundY = walked.y;
    await page.keyboard.down('Space');
    const jumpState = await waitForTelemetry(page, 3_000, (t) => gameplayOf(t).player.y > groundY + 0.2);
    await page.keyboard.up('Space');
    expect(
      jumpState,
      `Space must lift the player above ${groundY.toFixed(2)} (last: ${JSON.stringify(jumpState?.gameplay?.player)})`,
    ).toBeTruthy();
    // Landing: gravity brings the player back onto the ground
    const landedState = await waitForTelemetry(page, 6_000, (t) => {
      const p = gameplayOf(t).player;
      return p.onGround && Math.abs(p.y - groundY) < 0.01;
    });
    expect(
      landedState,
      'gravity must return the player to the ground after the jump',
    ).toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '03-jumped');

    // ── Hotbar digits: vanilla Digit5 selects slot 4 ──
    await page.keyboard.press('Digit5');
    const slotState = await waitForTelemetry(page, 5_000, (t) => gameplayOf(t).hotbar.selected === 4);
    expect(slotState, 'Digit5 must select hotbar slot 4 (vanilla key.hotbar.5)').toBeTruthy();

    // ── Scroll changes the selection too (vanilla Inventory.swapPaint) ──
    // swapPaint only runs while the pointer is grabbed (vanilla gameplay
    // wheel), so the canvas must be clicked to grab first; a programmatic
    // lock grant is async, so wait for the pointer-lock state to settle
    // before trusting the wheel event.
    const wheelCanvas = await page.evaluate(() => {
      const c = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
      const hit = c ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
      if (!hit) return null;
      const rect = hit.getBoundingClientRect();
      return { x: rect.left + hit.clientWidth / 2, y: rect.top + hit.clientHeight / 2 };
    });
    expect(wheelCanvas, 'the game canvas must exist for the wheel grab').toBeTruthy();
    await page.mouse.move(wheelCanvas!.x, wheelCanvas!.y);
    await page.mouse.click(wheelCanvas!.x, wheelCanvas!.y);
    await page.waitForTimeout(600);
    await page.mouse.wheel(0, 120);
    const scrollState = await waitForTelemetry(page, 5_000, (t) => gameplayOf(t).hotbar.selected === 5);
    expect(scrollState, 'scroll down must move the selection to slot 5').toBeTruthy();

    expect(pageErrors, 'gameplay must not throw uncaught page errors').toEqual([]);
    await attachSessionSummary(testInfo, {
      gated: false,
      walkDist,
      jumpApex: gameplayOf(jumpState).player.y,
      gameplay: gameplayOf(walkState),
    });
    await context.close();
  });

  test('survival block breaking and placing work through the real mouse path (capability-gated)', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 150_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry?.canEnterWorld) {
      await attachMilestoneScreenshot(page, testInfo, '04-blocks-gate');
      test.skip(true, `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`);
      return;
    }
    if (!(await hasGameplayBridge(page))) {
      await attachMilestoneScreenshot(page, testInfo, '04-blocks-gate');
      test.skip(true, 'the deployed bundle predates the gameplay bridge (queryBlock/setLook) — rebuild to activate this test');
      return;
    }

    const state = await enterWorld(page);
    expect(gameplayOf(state).creative, 'the default fresh world is Survival (vanilla default)').toBe(false);

    // ── Aim straight down at the grass under the player ──
    await page.evaluate(() => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
        }
      ).__eaglercraftGame;
      game?.setLook?.(0, 90);
    });
    const aimState = await waitForTelemetry(page, 10_000, (t) => {
      const gp = t.gameplay as Gameplay | null | undefined;
      return Math.abs((gp?.player?.pitch ?? 0) - 90) < 1 && gp?.hit != null;
    });
    const hit = gameplayOf(aimState).hit!;
    expect(hit, `aiming down must hit the ground (last telemetry: ${JSON.stringify(aimState?.gameplay)})`).toBeTruthy();
    expect([hit.x, hit.y, hit.z], 'the ray must hit the grass block under spawn').toEqual([0, -61, 0]);
    expect(hit.face, 'the ray must enter through the top face').toBe('up');

    // ── Click the canvas to grab the pointer (vanilla click-to-grab) ──
    const canvas = await page.evaluate(() => {
      const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
      const c = byId ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
      if (!c) return null;
      const rect = c.getBoundingClientRect();
      return { x: rect.left + c.clientWidth / 2, y: rect.top + c.clientHeight / 2 };
    });
    expect(canvas, 'the game canvas must exist').toBeTruthy();
    await page.mouse.click(canvas!.x, canvas!.y);
    await page.waitForTimeout(300);

    // ── Hold left mouse: survival destroy progress must advance ──
    await page.mouse.down();
    let sawDestroyStage = false;
    let broke = false;
    const destroyDeadline = Date.now() + 20_000;
    while (Date.now() < destroyDeadline && !broke) {
      await page.waitForTimeout(150);
      const t = await readGameTelemetry(page);
      const g = t?.gameplay ? gameplayOf(t) : null;
      if (g?.destroy && g.destroy.stage >= 0) {
        sawDestroyStage = true;
      }
      broke = (await queryBlock(page, 0, -61, 0)) === 0;
    }
    await page.mouse.up();
    expect(
      broke,
      'holding the attack key on grass must break it (vanilla fist: ~0.9s)',
    ).toBe(true);
    expect(
      sawDestroyStage,
      'the survival destroy overlay must advance through the stages',
    ).toBe(true);
    expect(await queryBlock(page, 0, -61, 0), 'the grass block is gone').toBe(0);
    await attachMilestoneScreenshot(page, testInfo, '05-block-broken');

    // ── The mined block went to the hotbar (vanilla pickup) ──
    const pickupState = await readGameTelemetry(page);
    const pickedSlot = gameplayOf(pickupState).hotbar.slots.findIndex(
      (s) => s && s[0] === 9 && s[1] >= 1,
    );
    expect(pickedSlot, 'the broken grass must be picked up into the hotbar').toBeGreaterThanOrEqual(0);

    // ── Place it back: aim at the ground a few blocks south ──
    // Synthetic CDP pointer deltas drift the camera between ticks even
    // after the snap, so the aim is re-pinned every round until the
    // snapshot shows pitch ~30 with a valid top-face hit whose placement
    // cell is not the player's own occupied block (vanilla canSurvive
    // rejects that - as does this poll).
    let placeHit: Gameplay['hit'] = null;
    for (let i = 0; i < 25 && !placeHit; i++) {
      await page.evaluate(() => {
        const game = (
          self as unknown as {
            __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
          }
        ).__eaglercraftGame;
        // yaw 0 faces +Z, pitch 30 looks down-forward: from the 1-block
        // hole the ray clears the hole wall and lands ~1.5 blocks away
        game?.setLook?.(0, 30);
      });
      await page.waitForTimeout(200);
      const t = await readGameTelemetry(page);
      const gp = t?.gameplay ? gameplayOf(t) : null;
      if (!gp?.hit || gp.hit.face !== 'up') {
        continue;
      }
      if (Math.abs(gp.player.pitch - 30) >= 2) {
        continue;
      }
      const cellX = gp.hit.x;
      const cellY = gp.hit.y + 1;
      const cellZ = gp.hit.z;
      const feetX = Math.floor(gp.player.x);
      const feetY = Math.floor(gp.player.y);
      const feetZ = Math.floor(gp.player.z);
      if (cellX === feetX && cellY === feetY && cellZ === feetZ) {
        continue; // the player's own cell - placement would be rejected
      }
      placeHit = gp.hit;
    }
    expect(placeHit, 'the forward-down ray must find a placeable ground cell').toBeTruthy();
    expect(placeHit!.face, 'the placement surface must be the top face').toBe('up');
    const placeY = placeHit!.y + 1;
    const placeZ = placeHit!.z;
    const placeX = placeHit!.x;
    // Re-pin once more and click immediately - the pick refreshes per
    // frame, so the click races at most ~1 frame of drift
    await page.evaluate(() => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
        }
      ).__eaglercraftGame;
      game?.setLook?.(0, 30);
    });
    await page.waitForTimeout(80);
    const editsBefore = (await (async () => {
      const t = await readGameTelemetry(page);
      return t?.gameplay ? gameplayOf(t).edits : -1;
    })());
    await page.mouse.down({ button: 'right' });
    await page.waitForTimeout(120);
    await page.mouse.up({ button: 'right' });

    // Survival placement consumes the held stack and adds exactly one
    // edit - position-independent success signals (the camera can drift
    // a cell between the aim read and the click, so the position check
    // scans the neighborhood instead of pinning one cell)
    let placed = false;
    let placedAt: string | null = null;
    const placeDeadline = Date.now() + 8_000;
    while (Date.now() < placeDeadline && !placed) {
      await page.waitForTimeout(250);
      const t = await readGameTelemetry(page);
      const gp = t?.gameplay ? gameplayOf(t) : null;
      if (!gp) continue;
      const sel = gp.hotbar.slots[gp.hotbar.selected];
      const consumed = !sel || sel[1] === 0;
      if (gp.edits >= editsBefore + 1 && consumed) {
        placed = true;
        for (let dx = -2; dx <= 2 && !placedAt; dx++) {
          for (let dz = -2; dz <= 2 && !placedAt; dz++) {
            for (let dy = -1; dy <= 1 && !placedAt; dy++) {
              const id = await queryBlock(page, placeX + dx, placeY + dy, placeZ + dz);
              if (id === 9) {
                placedAt = `(${placeX + dx}, ${placeY + dy}, ${placeZ + dz})`;
              }
            }
          }
        }
      }
    }
    expect(
      placed,
      `right-clicking the ground must place the held grass (expected near ${placeX},${placeY},${placeZ}; editsBefore=${editsBefore})`,
    ).toBe(true);
    expect(placedAt, 'the placed grass block must sit near the aimed cell').toBeTruthy();
    expect(pageErrors, 'block editing must not throw uncaught page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '06-block-placed');
    await attachSessionSummary(testInfo, {
      gated: false,
      brokeAt: [0, -61, 0],
      placedAt: [0, placeY, placeZ],
    });
    await context.close();
  });

  test('Options sub-screens (Controls, Key Binds, Sound) and the multiplayer gateway screens work (capability-gated)', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 150_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry) {
      test.skip(true, 'the telemetry bridge is missing from the deployed bundle');
      return;
    }
    if (!(await hasGameplayBridge(page))) {
      await attachMilestoneScreenshot(page, testInfo, '07-screens-gate');
      test.skip(true, 'the deployed bundle predates the screens build (no gameplay bridge) — rebuild to activate this test');
      return;
    }

    // ── Options grid: Controls and Sound Settings must be live ──
    await clickBridgeButton(page, 'options', 'the title screen must expose the Options button');
    const optionsState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('OptionsScreen'));
    expect(optionsState?.screen.includes('OptionsScreen'), 'Options must open').toBe(true);
    const optionIds = (optionsState?.buttons ?? []).map((b) => b.id);
    expect(optionIds, 'the Options grid must carry Controls (got: ' + JSON.stringify(optionIds) + ')').toContain('controls');
    expect(optionIds, 'the Options grid must carry Sound Settings').toContain('soundsettings');
    const optionsById = (id: string) => (optionsState?.buttons ?? []).find((b) => b.id === id);
    expect(optionsById('controls')?.active, 'Controls must be active now').toBe(true);
    expect(optionsById('soundsettings')?.active, 'Sound Settings must be active now').toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '07-options-grid');

    // ── Controls: toggles + sprint window slider ──
    await clickBridgeButton(page, 'controls', 'the Controls button must be clickable');
    const controlsState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('ControlsScreen'));
    expect(controlsState?.screen.includes('ControlsScreen'), 'Controls must open').toBe(true);
    const controlIds = (controlsState?.buttons ?? []).map((b) => b.id);
    // CycleButton ids include the current value ("Toggle Crouch: Hold" ->
    // "togglecrouchhold"), so value-bearing rows match by prefix
    for (const expectedId of [
      'mousesettings',
      'keybinds',
      'togglecrouch',
      'togglesprint',
      'toggleattack',
      'toggleuse',
      'autojump',
      'sprintwindow',
      'operatoritemstab',
      'done',
    ]) {
      expect(
        controlIds.some((id) => id.startsWith(expectedId)),
        `Controls must carry "${expectedId}" (got: ${JSON.stringify(controlIds)})`,
      ).toBe(true);
    }
    await attachMilestoneScreenshot(page, testInfo, '08-controls');

    // ── Key Binds: the vanilla list with Reset All + Done ──
    await clickBridgeButton(page, 'keybinds', 'the Key Binds button must be clickable');
    const keybindsState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('KeyBindsScreen'));
    expect(keybindsState?.screen.includes('KeyBindsScreen'), 'Key Binds must open').toBe(true);
    const keybindIds = (keybindsState?.buttons ?? []).map((b) => b.id);
    expect(keybindIds, 'Key Binds must carry the Reset All button').toContain('resetall');
    expect(keybindIds, 'Key Binds must carry the Done button').toContain('done');
    await attachMilestoneScreenshot(page, testInfo, '09-keybinds');

    // ── Rebinding: click the "Walk Forwards" row's key button, press K ──
    // Row geometry mirrors KeyBindsScreen: listTop = 32, rows are 20px;
    // row 0 is the Movement header, row 1 is Walk Forwards. The key
    // button sits at the right edge of the row band.
    const keyButtonCenter = await page.evaluate(() => {
      const bridge = (
        window as unknown as { __eaglercraftGame?: { guiScale?: number } }
      ).__eaglercraftGame;
      const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
      const canvas = byId ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
      if (!canvas || canvas.width === 0 || canvas.clientWidth === 0) return null;
      const guiScale =
        typeof bridge?.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1;
      const backingToCss = canvas.clientWidth / canvas.width;
      const rect = canvas.getBoundingClientRect();
      const guiWidth = Math.round(canvas.clientWidth / (guiScale * backingToCss));
      const rowX = Math.max(10, guiWidth / 2 - 245);
      const rowW = Math.min(480, guiWidth - 20);
      const keyButtonX = rowX + rowW - 52 + 25; // +25 = button center
      const row1Y = 32 + 20 + 10; // header row + half the bind row
      const cssX = rect.left + keyButtonX * guiScale * backingToCss;
      const cssY = rect.top + row1Y * guiScale * backingToCss;
      if (!Number.isFinite(cssX) || !Number.isFinite(cssY)) return null;
      return { cssX, cssY };
    });
    expect(keyButtonCenter, 'the key-binds list geometry must be computable').toBeTruthy();
    // The row is listening; the vanilla contract: the next key press
    // rebinds (KeyBindsScreen.keyPressed persists immediately). K is scan
    // code 37 (0x25). Click/press delivery can drop on a loaded runner, so
    // arm-and-press is retried until the persisted table shows up; every
    // attempt re-arms the same row (idempotent) and bails out if the screen
    // navigated away for any other reason.
    let savedBinds: string | null = null;
    for (let attempt = 0; attempt < 4 && !savedBinds; attempt++) {
      const screenNow = await readGameTelemetry(page);
      if (!screenNow || !screenNow.screen.includes('KeyBindsScreen')) {
        throw new Error(
          `Key Binds screen closed during rebind attempt ${attempt + 1} (screen: ${screenNow?.screen})`,
        );
      }
      await page.mouse.click(keyButtonCenter!.cssX, keyButtonCenter!.cssY);
      await page.waitForTimeout(200);
      await page.keyboard.press('KeyK');
      await page.waitForTimeout(400);
      savedBinds = await page.evaluate(() =>
        localStorage.getItem('_eaglercraftX26.options.keybinds'),
      );
    }
    expect(savedBinds, 'a rebind must persist the keybinds table').toBeTruthy();
    expect(savedBinds!.includes('key.forward=37'), `Walk Forwards must now be bound to K (scan 37), got: ${savedBinds}`).toBe(true);

    // ── Sound Settings: master + category sliders ──
    await clickBridgeButton(page, 'done', 'the Key Binds Done button must work', 'screens.options.KeyBindsScreen', 'screens.options.ControlsScreen');
    await clickBridgeButton(page, 'done', 'the Controls Done button must work', 'screens.options.ControlsScreen', 'screens.options.OptionsScreen');
    await clickBridgeButton(page, 'soundsettings', 'the Sound Settings button must work', 'screens.options.OptionsScreen', 'screens.options.SoundOptionsScreen');
    const soundState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('SoundOptionsScreen'));
    expect(soundState?.screen.includes('SoundOptionsScreen'), 'Sound Settings must open').toBe(true);
    const soundIds = (soundState?.buttons ?? []).map((b) => b.id);
    for (const expectedId of [
      'mastervolume',
      'musicvolume',
      'recordvolume',
      'weathervolume',
      'blockvolume',
      'hostilevolume',
      'neutralvolume',
      'playervolume',
      'ambientvolume',
      'voicevolume',
      'subtitles',
      'directionalaudio',
      'done',
    ]) {
      expect(
        soundIds.some((id) => id.startsWith(expectedId)),
        `Sound Settings must carry "${expectedId}" (got: ${JSON.stringify(soundIds)})`,
      ).toBe(true);
    }
    await attachMilestoneScreenshot(page, testInfo, '10-sound-settings');

    // ── Master volume slider click-to-set (vanilla slider semantics) ──
    const master = (soundState?.buttons ?? []).find((b) => b.id === 'mastervolume')!;
    // Click at 25% of the slider width -> master volume ≈ 0.25
    const masterClick = await getGuiCssCoords(
      page,
      master.x + Math.round(master.w * 0.25),
      master.y + master.h / 2,
    );
    // Click-to-set at 25%, retried at a couple of positions - the value
    // must land in the persisted profile (vanilla OptionInstance.save)
    let savedMaster: string | null = null;
    for (const frac of [0.25, 0.6, 0.4]) {
      const c = await getGuiCssCoords(
        page,
        master.x + Math.round(master.w * frac),
        master.y + master.h / 2,
      );
      await page.mouse.move(c.cssX, c.cssY);
      await page.waitForTimeout(120);
      await page.mouse.click(c.cssX, c.cssY);
      await page.waitForTimeout(600);
      savedMaster = await page.evaluate(() => localStorage.getItem('_eaglercraftX26.profile.masterVol'));
      if (savedMaster !== null) {
        break;
      }
    }
    expect(
      savedMaster !== null,
      `master volume must persist after a slider click (got: ${savedMaster})`,
    ).toBe(true);

    // Done -> Options, Done -> Title
    await clickBridgeButton(page, 'done', 'the Sound Settings Done button must work', 'screens.options.SoundOptionsScreen', 'screens.options.OptionsScreen');
    await clickBridgeButton(page, 'done', 'the Options Done button must work', 'screens.options.OptionsScreen', 'TitleScreen');
    const titleAgain = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('TitleScreen'));
    expect(titleAgain?.screen.includes('TitleScreen'), 'Done must return to the title screen').toBe(true);

    await clickBridgeButton(page, 'multiplayer', 'the title screen must expose the Multiplayer button');
    const mpState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('JoinMultiplayerScreen'));
    expect(mpState?.screen.includes('JoinMultiplayerScreen'), 'Multiplayer must open the server browser').toBe(true);
    const mpIds = (mpState?.buttons ?? []).map((b) => b.id);
    for (const expectedId of ['joinserver', 'directconnection', 'addserver', 'edit', 'delete', 'refresh', 'cancel']) {
      expect(mpIds, `the server browser must carry "${expectedId}" (got: ${JSON.stringify(mpIds)})`).toContain(expectedId);
    }
    const mpById = (id: string) => (mpState?.buttons ?? []).find((b) => b.id === id);
    expect(mpById('edit')?.active, 'Edit must be disabled with no selection').toBe(false);
    expect(mpById('delete')?.active, 'Delete must be disabled with no selection').toBe(false);
    await attachMilestoneScreenshot(page, testInfo, '11-server-browser');

    // ── Add Server: type name + address, Done ──
    await clickBridgeButton(page, 'addserver', 'the Add Server button must be clickable');
    const addState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('EditServerScreen'));
    expect(addState?.screen.includes('EditServerScreen'), 'Add Server must open the entry dialog').toBe(true);
    // Keys can drop or mis-route under runner load (one CI run saved the
    // mangled name "teway\u0001localhost:1"), and a blind retype APPENDS to
    // whatever landed, so each field is cleared with Backspaces before
    // typing and the whole entry is retried until the persisted list holds
    // the exact name+address pair.
    async function clearAndType(boxId: string, text: string): Promise<void> {
      await clickBridgeButton(page, boxId, `the ${boxId} box must be exposed`);
      await page.waitForTimeout(150);
      for (let i = 0; i < 40; i++) {
        await page.keyboard.press('Backspace');
      }
      await page.waitForTimeout(100);
      await page.keyboard.type(text, { delay: 90 });
      await page.waitForTimeout(200);
    }
    async function entrySaved(): Promise<boolean> {
      const raw = await page.evaluate(() =>
        localStorage.getItem('_eaglercraftX26.serverlist'),
      );
      if (!raw) return false;
      try {
        const list = JSON.parse(raw) as Array<{ name?: string; ip?: string }>;
        return (
          Array.isArray(list) &&
          list.some((e) => e && e.name === 'Gateway' && e.ip === 'localhost:1')
        );
      } catch {
        return raw.includes('Gateway') && raw.includes('localhost:1');
      }
    }
    let entryOk = false;
    for (let attempt = 0; attempt < 3 && !entryOk; attempt++) {
      if (attempt > 0) {
        // Back out of whatever state the failed attempt left and start over
        const cancelVisible = (await readGameTelemetry(page))?.buttons.some(
          (b) => b.id === 'cancel' && b.active !== false,
        );
        if (cancelVisible) {
          await clickBridgeButton(page, 'cancel', 'Cancel must be clickable to redo the entry');
        }
        await clickBridgeButton(page, 'addserver', 'the Add Server button must be clickable');
        await waitForTelemetry(page, 15_000, (t) => t.screen.includes('EditServerScreen'));
      }
      await clearAndType('servername', 'Gateway');
      await clearAndType('serveraddress', 'localhost:1');
      const enabled = (await readGameTelemetry(page))?.buttons.find((b) => b.id === 'done')?.active;
      if (enabled !== true) continue;
      await clickBridgeButton(page, 'done', 'Done must be clickable');
      await page.waitForTimeout(400);
      entryOk = await entrySaved();
    }
    expect(
      entryOk,
      'the new entry must be saved with the exact typed name and address',
    ).toBe(true);

    // ── Select the row and join: ConnectScreen -> DisconnectedScreen ──
    // First row: listTop 32, row height 36 -> click at GUI (245, 50).
    // The row highlight is computed at render time, so the pointer must
    // rest on the row for a frame before the click lands.
    const rowClick = await getGuiCssCoords(page, 245, 50);
    await page.mouse.move(rowClick.cssX, rowClick.cssY);
    await page.waitForTimeout(250);
    await page.mouse.click(rowClick.cssX, rowClick.cssY);
    await page.waitForTimeout(300);
    await clickBridgeButton(page, 'joinserver', 'Join Server must be clickable after selecting a row');
    const connectState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('ConnectScreen'));
    expect(connectState?.screen.includes('ConnectScreen'), 'joining must show the ConnectScreen').toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '12-connecting');

    // localhost:1 refuses fast -> the honest DisconnectedScreen
    const disconnected = await waitForTelemetry(page, 45_000, (t) => t.screen.includes('DisconnectedScreen'));
    expect(
      disconnected?.screen.includes('DisconnectedScreen'),
      `a refused connection must land on the DisconnectedScreen (last: ${JSON.stringify(disconnected?.screen)})`,
    ).toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '13-disconnected');

    expect(pageErrors, 'the options/multiplayer flows must not throw uncaught page errors').toEqual([]);
    await attachSessionSummary(testInfo, {
      gated: false,
      serverList: await page.evaluate(() =>
        localStorage.getItem('_eaglercraftX26.serverlist'),
      ),
      keybinds: savedBinds,
    });
    await context.close();
  });
});
