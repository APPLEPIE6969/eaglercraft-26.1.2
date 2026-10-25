import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  enterWorld,
  gameplayOf,
  getGuiCssCoords,
  hasGameplayBridge,
  installConsoleRecorder,
  readGameTelemetry,
  setHotbarSlot,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
  type Gameplay,
  type Telemetry,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

/**
 * Feature 4 — the inventory system: the vanilla 36-slot inventory model,
 * the survival InventoryScreen (E key) and the creative item picker.
 *
 * Slot coordinates are the panel-relative origins measured from the real
 * 26.1.2 container textures (see the screen javadocs), converted to canvas
 * CSS pixels through the shared helper. The panel is centered, so its
 * screen origin comes from the reported gui size (canvas backing / guiScale).
 */

async function readGuiSize(page: Page): Promise<{ w: number; h: number }> {
  return page.evaluate(() => {
    const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas');
    const canvas =
      (byId as HTMLCanvasElement | null) ??
      (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).filter(
        (c) => c.width > 0,
      )[0] ??
      null;
    const bridge = (
      window as unknown as { __eaglercraftGame?: { guiScale?: number } }
    ).__eaglercraftGame;
    const guiScale =
      typeof bridge?.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1;
    if (!canvas || canvas.width === 0) {
      return { w: 0, h: 0 };
    }
    return { w: Math.round(canvas.width / guiScale), h: Math.round(canvas.height / guiScale) };
  });
}

/** Clicks a panel-relative GUI coordinate (left or right button). */
/**
 * Clicks a panel-relative GUI coordinate. The game's input path is a
 * per-frame 1-element latch per button, so the click waits for two rAF
 * callbacks (proof the consuming frame completed) before returning -
 * otherwise the next same-button click can overwrite the pending latch.
 */
async function clickPanel(
  page: Page,
  gui: { w: number; h: number },
  panelW: number,
  panelH: number,
  px: number,
  py: number,
  button: 'left' | 'right' = 'left',
): Promise<void> {
  const coords = await getGuiCssCoords(
    page,
    Math.round(gui.w / 2 - panelW / 2) + px,
    Math.round(gui.h / 2 - panelH / 2) + py,
  );
  await page.mouse.move(coords.cssX, coords.cssY);
  await page.waitForTimeout(120);
  await page.mouse.click(coords.cssX, coords.cssY, { button });
  await Promise.race([
    page.evaluate(
      () => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))),
    ),
    page.waitForTimeout(5000),
  ]);
  await page.waitForTimeout(80);
}

/** The survival container panel size (InventoryScreen). */
const SURVIVAL_PANEL = { w: 176, h: 166 };
/** The creative picker panel size (CreativeModeInventoryScreen). */
const CREATIVE_PANEL = { w: 195, h: 136 };

test.describe('inventory screens (Feature 4)', () => {
  test('survival inventory screen moves items between hotbar and main (capability-gated)', async ({
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
      await attachMilestoneScreenshot(page, testInfo, '01-inv-gate');
      test.skip(true, 'the client cannot enter a world yet');
      return;
    }
    if (!(await hasGameplayBridge(page))) {
      test.skip(true, 'the deployed bundle predates the gameplay bridge — rebuild to activate');
      return;
    }

    const state = await enterWorld(page);
    if (gameplayOf(state).creative) {
      test.skip(true, 'the default world is creative on this bundle — survival inventory needs a survival world');
      return;
    }

    // Seed hotbar slot 0 with a 64-stack of oak planks through the bridge
    await setHotbarSlot(page, 0, 15, 64);
    const seeded = await waitForTelemetry(page, 5_000, (t) => {
      const slots = gameplayOf(t).hotbar.slots;
      return !!slots[0] && slots[0]![0] === 15 && slots[0]![1] === 64;
    });
    expect(seeded, 'the bridge must seed hotbar slot 0 with 64x oak planks').toBeTruthy();

    const gui = await readGuiSize(page);
    expect(gui.w, 'the gui size must be readable for slot math').toBeGreaterThan(0);

    // ── E opens the survival InventoryScreen ──
    await page.keyboard.press('KeyE');
    const opened = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('InventoryScreen'),
    );
    expect(
      opened,
      `E must open the InventoryScreen (screen: ${opened?.screen})`,
    ).toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '02-survival-inventory-open');

    // ── Left click hotbar slot 0 picks the stack up (vanilla pickup) ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, 7 + 9, 141 + 9);
    const picked = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return !!gp.carried && gp.carried[0] === 15 && gp.carried[1] === 64 && gp.hotbar.slots[0] === null;
    });
    expect(picked, 'left click must lift the whole stack onto the cursor').toBeTruthy();

    // ── Left click main slot 0 places it (vanilla place) ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, 7 + 9, 83 + 9);
    const placed = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.carried === null && !!gp.main && gp.main[0]?.[0] === 15;
    });
    expect(placed, 'the carried stack must land in main slot 0').toBeTruthy();

    // ── Shift-click quick-moves it back to the hotbar ──
    await page.keyboard.down('ShiftLeft');
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, 7 + 9, 83 + 9);
    await page.keyboard.up('ShiftLeft');
    const moved = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return !!gp.hotbar.slots[0] && gp.hotbar.slots[0]![0] === 15 && (!gp.main || gp.main[0] === null);
    });
    expect(
      moved,
      'shift-click must quick-move the stack from main back to the hotbar',
    ).toBeTruthy();

    // ── E closes back into the game ──
    await page.keyboard.press('KeyE');
    const closed = await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    expect(closed, 'E must close the inventory screen').toBeTruthy();

    expect(pageErrors, 'the inventory screen must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '03-survival-inventory-closed');
    await context.close();
  });

  test('creative item picker grabs stacks, fills the hotbar and voids them (capability-gated)', async ({
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
      await attachMilestoneScreenshot(page, testInfo, '04-creative-gate');
      test.skip(true, 'the client cannot enter a world yet');
      return;
    }
    if (!(await hasGameplayBridge(page))) {
      test.skip(true, 'the deployed bundle predates the gameplay bridge — rebuild to activate');
      return;
    }

    // The default world is survival: a creative world needs the Create
    // World flow with the Game Mode cycle button. Drive the real screens:
    // TitleScreen -> Singleplayer -> Select World -> Create New World.
    const singleplayer = await waitForTelemetry(
      page,
      5_000,
      (t) => t.screen.includes('TitleScreen') && t.buttons.some((b) => b.id === 'singleplayer'),
    );
    if (!singleplayer) {
      test.skip(true, 'the title screen is not reachable through the bridge');
      return;
    }
    const spCoords = await import('./support').then((m) => m.getButtonCssCoords(page, 'singleplayer'));
    await page.mouse.click(spCoords!.cssX, spCoords!.cssY);
    const selectWorld = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('SelectWorldScreen'),
    );
    expect(selectWorld, 'Singleplayer must open Select World').toBeTruthy();
    const createBtn = selectWorld!.buttons.find((b) => b.id.includes('create'));
    if (!createBtn) {
      test.skip(true, 'Select World has no Create button on this bundle');
      return;
    }
    const createCoords = await import('./support').then((m) => m.getButtonCssCoords(page, createBtn.id));
    await page.mouse.click(createCoords!.cssX, createCoords!.cssY);
    const createWorld = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('CreateWorldScreen'),
    );
    expect(createWorld, 'Create New World must open CreateWorldScreen').toBeTruthy();

    // Cycle the Game Mode button to Creative (Survival -> Hardcore -> Creative)
    const modeButton = () =>
      readGameTelemetry(page).then((t) => t!.buttons.find((b) => b.id.startsWith('gamemode:')));
    let mode = await modeButton();
    for (let i = 0; i < 4 && mode && !mode.id.startsWith('gamemode:creative'); i++) {
      const c = await import('./support').then((m) => m.getButtonCssCoords(page, mode!.id));
      await page.mouse.click(c!.cssX, c!.cssY);
      await page.waitForTimeout(300);
      mode = await modeButton();
    }
    if (!mode || !mode.id.startsWith('gamemode:creative')) {
      test.skip(true, 'the Game Mode button did not reach Creative on this bundle');
      return;
    }

    // Create the world (leave the default name)
    const createNow = createWorld!.buttons.find((b) => b.id === 'createnewworld');
    if (!createNow) {
      test.skip(true, 'CreateWorldScreen has no create button on this bundle');
      return;
    }
    const nowCoords = await import('./support').then((m) => m.getButtonCssCoords(page, createNow.id));
    await page.mouse.click(nowCoords!.cssX, nowCoords!.cssY);
    const world = await waitForTelemetry(page, 60_000, (t) => {
      const gp = t.gameplay as Gameplay | null | undefined;
      return t.inWorld && !!gp?.player?.onGround;
    });
    expect(world, 'the creative world must boot and settle the player').toBeTruthy();
    expect(gameplayOf(world!).creative, 'the created world must be creative').toBe(true);

    const gui = await readGuiSize(page);

    // ── E opens the creative picker ──
    await page.keyboard.press('KeyE');
    const opened = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('CreativeModeInventoryScreen'),
    );
    expect(opened, `E must open the creative picker (screen: ${opened?.screen})`).toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '05-creative-open');

    // ── Left click grid cell 0 grabs a full stack (stone, registry first) ──
    await clickPanel(page, gui, CREATIVE_PANEL.w, CREATIVE_PANEL.h, 8 + 9, 17 + 9);
    const picked = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return !!gp.carried && gp.carried[0] === 1 && gp.carried[1] === 64;
    });
    expect(picked, 'left click must grab a full stone stack from the grid').toBeTruthy();

    // ── Click hotbar slot 0 places the stack there ──
    await clickPanel(page, gui, CREATIVE_PANEL.w, CREATIVE_PANEL.h, 8 + 9, 111 + 9);
    const placed = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.carried === null && !!gp.hotbar.slots[0] && gp.hotbar.slots[0]![0] === 1;
    });
    expect(placed, 'the stone stack must land in hotbar slot 0').toBeTruthy();

    // ── Click the destroy slot with another stack voids it ──
    await clickPanel(page, gui, CREATIVE_PANEL.w, CREATIVE_PANEL.h, 8 + 9, 17 + 9);
    await waitForTelemetry(page, 5_000, (t) => !!gameplayOf(t).carried);
    await clickPanel(page, gui, CREATIVE_PANEL.w, CREATIVE_PANEL.h, 174 + 9, 111 + 9);
    const voided = await waitForTelemetry(page, 5_000, (t) => gameplayOf(t).carried === null);
    expect(voided, 'the destroy slot must void the carried stack').toBeTruthy();

    // ── E closes back into the game ──
    await page.keyboard.press('KeyE');
    const closed = await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    expect(closed, 'E must close the creative picker').toBeTruthy();

    expect(pageErrors, 'the creative screen must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '06-creative-closed');
    await context.close();
  });
});
