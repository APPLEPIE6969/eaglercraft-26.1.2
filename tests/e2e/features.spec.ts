import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  attachSessionSummary,
  getButtonCssCoords,
  installConsoleRecorder,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

type Telemetry = NonNullable<Awaited<ReturnType<typeof readGameTelemetry>>>;

/** Polls the telemetry until the predicate holds or the deadline expires. */
async function waitForTelemetry(
  page: Page,
  deadlineMs: number,
  predicate: (telemetry: Telemetry) => boolean,
): Promise<Telemetry> {
  const deadline = Date.now() + deadlineMs;
  let state = await readGameTelemetry(page);
  while (Date.now() < deadline && !(state && predicate(state))) {
    await page.waitForTimeout(200);
    state = await readGameTelemetry(page);
  }
  return state;
}

/** Clicks a bridge button by its telemetry id (move + settle + click). */
async function clickBridgeButton(page: Page, id: string, failMessage: string): Promise<void> {
  const coords = await getButtonCssCoords(page, id);
  expect(coords, failMessage).not.toBeNull();
  await page.mouse.move(coords!.cssX, coords!.cssY);
  await page.waitForTimeout(100);
  await page.mouse.click(coords!.cssX, coords!.cssY);
}

/** Converts arbitrary GUI-pixel coordinates to canvas CSS coordinates. */
async function getGuiCssCoords(
  page: Page,
  guiX: number,
  guiY: number,
): Promise<{ cssX: number; cssY: number }> {
  return page.evaluate(({ guiX: gx, guiY: gy }) => {
    const bridge = (
      window as unknown as { __eaglercraftGame?: { guiScale?: number } }
    ).__eaglercraftGame;
    const canvas = document.querySelector('canvas');
    if (!canvas || canvas.width === 0) {
      throw new Error('no game canvas for coordinate conversion');
    }
    const guiScale =
      typeof bridge?.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1;
    const backingToCss = canvas.clientWidth / canvas.width;
    const rect = canvas.getBoundingClientRect();
    return {
      cssX: rect.left + gx * guiScale * backingToCss,
      cssY: rect.top + gy * guiScale * backingToCss,
    };
  }, { guiX, guiY });
}

/** Enters the default world through the bridge and waits for the HUD. */
async function enterWorld(page: Page): Promise<Telemetry> {
  await page.evaluate(() => {
    const game = (
      self as unknown as { __eaglercraftGame?: { enterWorld?: () => void } }
    ).__eaglercraftGame;
    game?.enterWorld?.();
  });
  const state = await waitForTelemetry(page, 30_000, (t) => t.inWorld);
  expect(state?.inWorld, 'the client must enter the world').toBe(true);
  expect(state?.gameplay, 'the gameplay snapshot must exist in world').toBeTruthy();
  return state!;
}

/** The gameplay snapshot type (mirrors buildGameplayJson in EaglerCraft.java). */
interface Gameplay {
  creative: boolean;
  edits: number;
  spawn: [number, number, number];
  player: {
    x: number;
    y: number;
    z: number;
    yaw: number;
    pitch: number;
    onGround: boolean;
    flying: boolean;
    crouching: boolean;
  };
  hotbar: { selected: number; slots: ([number, number] | null)[] };
  hit: { x: number; y: number; z: number; face?: string } | null;
  destroy: { stage: number; x: number; y: number; z: number } | null;
}

function gameplayOf(state: Telemetry): Gameplay {
  return state.gameplay as unknown as Gameplay;
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

/** Whether the deployed bundle carries the gameplay bridge (capability gate). */
async function hasGameplayBridge(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { queryBlock?: unknown; setLook?: unknown; gameplay?: unknown };
      }
    ).__eaglercraftGame;
    return typeof game?.queryBlock === 'function' && typeof game?.setLook === 'function';
  });
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
    const startX = gp.player.x;
    const startZ = gp.player.z;
    await page.keyboard.down('KeyW');
    await page.waitForTimeout(900);
    const walkState = await waitForTelemetry(page, 5_000, (t) => {
      const p = gameplayOf(t).player;
      return Math.hypot(p.x - startX, p.z - startZ) > 0.5;
    });
    await page.keyboard.up('KeyW');
    const walked = gameplayOf(walkState).player;
    const walkDist = Math.hypot(walked.x - startX, walked.z - startZ);
    expect(
      walkDist,
      `holding W for 0.9s must move the player (walked: ${walkDist.toFixed(3)} blocks)`,
    ).toBeGreaterThan(0.5);
    expect(walkDist, 'a 0.9s vanilla walk is slower than sprinting a whole second').toBeLessThan(6);
    expect(gameplayOf(walkState).player.onGround, 'the player stays on the ground walking').toBe(true);
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
    const aimState = await waitForTelemetry(page, 5_000, (t) => gameplayOf(t).hit !== null);
    const hit = gameplayOf(aimState).hit!;
    expect(hit, `aiming down must hit the ground (last telemetry: ${JSON.stringify(aimState?.gameplay)})`).toBeTruthy();
    expect([hit.x, hit.y, hit.z], 'the ray must hit the grass block under spawn').toEqual([0, -61, 0]);
    expect(hit.face, 'the ray must enter through the top face').toBe('up');

    // ── Click the canvas to grab the pointer (vanilla click-to-grab) ──
    const canvas = await page.evaluate(() => {
      const c = document.querySelector('canvas');
      return c ? { x: c.getBoundingClientRect().left + c.clientWidth / 2, y: c.getBoundingClientRect().top + c.clientHeight / 2 } : null;
    });
    expect(canvas, 'the game canvas must exist').toBeTruthy();
    await page.mouse.click(canvas!.x, canvas!.y);
    await page.waitForTimeout(300);

    // ── Hold left mouse: survival destroy progress must advance ──
    await page.mouse.down();
    let sawDestroyStage = false;
    let broke = false;
    const destroyDeadline = Date.now() + 12_000;
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
    await page.evaluate(() => {
      const game = (
        self as unknown as {
          __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
        }
      ).__eaglercraftGame;
      // yaw 0 faces +Z, pitch 30 looks down-forward: hits the ground
      // ~2.8 blocks away (within the 4.5 reach)
      game?.setLook?.(0, 30);
    });
    await page.waitForTimeout(400);
    const placeAimState = await readGameTelemetry(page);
    const placeHit = gameplayOf(placeAimState).hit;
    expect(placeHit, 'the forward-down ray must hit the ground').toBeTruthy();
    expect(placeHit!.face, 'the placement surface must be the top face').toBe('up');
    const placeY = placeHit!.y + 1;
    const placeZ = placeHit!.z;

    await page.mouse.down({ button: 'right' });
    await page.waitForTimeout(150);
    await page.mouse.up({ button: 'right' });
    await page.waitForTimeout(400);
    expect(
      await queryBlock(page, 0, placeY, placeZ),
      `right-clicking the ground must place the grass at (0, ${placeY}, ${placeZ})`,
    ).toBe(9);
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
      const canvas = document.querySelector('canvas');
      if (!canvas || canvas.width === 0) return null;
      const guiScale =
        typeof bridge?.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1;
      const backingToCss = canvas.clientWidth / canvas.width;
      const rect = canvas.getBoundingClientRect();
      const guiWidth = Math.round(canvas.clientWidth / (guiScale * backingToCss));
      const rowX = Math.max(10, guiWidth / 2 - 245);
      const rowW = Math.min(480, guiWidth - 20);
      const keyButtonX = rowX + rowW - 52 + 25; // +25 = button center
      const row1Y = 32 + 20 + 10; // header row + half the bind row
      return {
        cssX: rect.left + keyButtonX * guiScale * backingToCss,
        cssY: rect.top + row1Y * guiScale * backingToCss,
      };
    });
    expect(keyButtonCenter, 'the key-binds list geometry must be computable').toBeTruthy();
    await page.mouse.click(keyButtonCenter!.cssX, keyButtonCenter!.cssY);
    await page.waitForTimeout(200);
    // The row is listening; the vanilla contract: the next key press
    // rebinds. K is scan code 37 (0x25).
    await page.keyboard.press('KeyK');
    await page.waitForTimeout(400);
    const savedBinds = await page.evaluate(() => localStorage.getItem('_eaglercraftX26.options.keybinds'));
    expect(savedBinds, 'a rebind must persist the keybinds table').toBeTruthy();
    expect(savedBinds!.includes('key.forward=37'), `Walk Forwards must now be bound to K (scan 37), got: ${savedBinds}`).toBe(true);

    // ── Sound Settings: master + category sliders ──
    await clickBridgeButton(page, 'done', 'the Key Binds Done button must be clickable');
    await clickBridgeButton(page, 'done', 'the Controls Done button must be clickable');
    await clickBridgeButton(page, 'soundsettings', 'the Sound Settings button must be clickable');
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
    await page.mouse.click(masterClick.cssX, masterClick.cssY);
    await page.waitForTimeout(300);
    const savedMaster = await page.evaluate(
      () => localStorage.getItem('_eaglercraftX26.p.masterVol'),
    );
    expect(
      savedMaster !== null,
      `master volume must persist (got: ${savedMaster})`,
    ).toBe(true);

    // Done -> Options, Done -> Title
    await clickBridgeButton(page, 'done', 'the Sound Settings Done button must be clickable');
    await clickBridgeButton(page, 'done', 'the Options Done button must be clickable');
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
    await clickBridgeButton(page, 'servername', 'the Server Name box must be exposed');
    await page.keyboard.type('Gateway');
    await clickBridgeButton(page, 'serveraddress', 'the Server Address box must be exposed');
    await page.keyboard.type('localhost:1');
    const doneEnabled = (await readGameTelemetry(page))?.buttons.find((b) => b.id === 'done')?.active;
    expect(doneEnabled, 'Done must enable once the address is non-empty').toBe(true);
    await clickBridgeButton(page, 'done', 'Done must be clickable');
    await page.waitForTimeout(400);

    // ── The entry persisted (vanilla servers.dat equivalent) ──
    const savedList = await page.evaluate(() => localStorage.getItem('_eaglercraftX26.serverlist'));
    expect(savedList, 'the server list must persist to localStorage').toBeTruthy();
    expect(savedList!.includes('Gateway'), `the new entry must be saved (got: ${JSON.stringify(savedList)})`).toBe(true);

    // ── Select the row and join: ConnectScreen -> DisconnectedScreen ──
    // First row: listTop 32, row height 36 -> click at GUI (245, 50)
    const rowClick = await getGuiCssCoords(page, 245, 50);
    await page.mouse.click(rowClick.cssX, rowClick.cssY);
    await page.waitForTimeout(200);
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
      serverList: savedList,
      keybinds: savedBinds,
    });
    await context.close();
  });
});
