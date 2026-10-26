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
 * Feature 8 — the crafting system: the vanilla recipe data baked from the
 * jar's data/minecraft files, the live 2x2 grid in the survival inventory
 * screen, the crafting-table block (3x3 CraftingTableScreen opened by
 * right-clicking the placed block) and the vanilla survival tool ladder
 * planks -> sticks -> wooden pickaxe.
 *
 * Slot coordinates are the panel-relative origins measured from the real
 * 26.1.2 container textures (see the screen javadocs), converted to canvas
 * CSS pixels through the shared helper. Item ids: blocks below 512
 * (oak_log 351, oak_planks 15, cobblestone 4, crafting_table 375), items
 * at 4096+ (stick 4096, wooden_pickaxe 4097, wooden_axe 4098,
 * stone_pickaxe 4102).
 */

// A fresh Chromium per test: the whole suite shares one browser process
// per worker, and the SwiftShader shader caches / memory earlier tests
// accumulate stretch later frames long enough for the game's per-frame
// input latch to swallow panel clicks (every failure signature in this
// file reproduces only when tests run after siblings). The args mirror
// playwright.config.ts's launchOptions.
const DEDICATED_ARGS = [
  '--no-sandbox',
  '--disable-dev-shm-usage',
  '--ignore-gpu-blocklist',
  '--enable-unsafe-swiftshader',
  '--use-gl=angle',
  '--use-angle=swiftshader',
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-sync',
  '--no-first-run',
  '--no-default-browser-check',
];

/** Launches a dedicated Chromium for one test (see DEDICATED_ARGS). */
async function freshBrowser() {
  const { chromium } = await import('@playwright/test');
  return chromium.launch({ headless: true, args: DEDICATED_ARGS });
}

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

/** Clicks a panel-relative GUI coordinate (left or right button).
 *
 * The game's input path is a per-frame 1-element latch per button
 * (PlatformInput.mousePressedLatch -> MouseHandler.cursorPressed), so two
 * clicks inside one headless frame coalesce into one (and immediate reads
 * after a click can be stale). Every click therefore waits for the game
 * logic tick counter to advance - proof that at least one frame pump ran
 * and consumed the click - before returning.
 */
async function readTicks(page: Page): Promise<number> {
  return page.evaluate(() => {
    const g = (self as unknown as { __eaglercraftGame?: { ticks?: number } }).__eaglercraftGame;
    return typeof g?.ticks === 'number' ? g.ticks : -1;
  });
}

async function waitForTickAdvance(page: Page, from: number, deadlineMs = 3000): Promise<void> {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    if ((await readTicks(page)) > from) return;
    await page.waitForTimeout(50);
  }
}

/**
 * Waits for two requestAnimationFrame callbacks - the game's frame pump is
 * a rAF loop, so two rAFs prove the frame that consumed the click's input
 * latch has completed. (Tick advances are NOT sufficient: ticks catch up in
 * batches inside one heavy frame, so a tick can pass while the click's
 * latch is still pending and the next same-button click would overwrite
 * it.) Races a 5s watchdog so a stalled loop cannot hang the test.
 */
async function waitForFramePump(page: Page): Promise<void> {
  await Promise.race([
    page.evaluate(() => new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    })),
    page.waitForTimeout(5000),
  ]);
}

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
  await waitForFramePump(page);
  await page.waitForTimeout(80);
}

/** Selects a hotbar slot through the bridge's selectSlot queue. */
async function selectHotbarSlot(page: Page, slot: number): Promise<void> {
  await page.evaluate((s) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { selectSlot?: (slot: number) => void };
      }
    ).__eaglercraftGame;
    game?.selectSlot?.(s);
  }, slot);
}

/** Snaps the camera to an absolute yaw/pitch through the bridge. */
async function setLook(page: Page, yaw: number, pitch: number): Promise<void> {
  await page.evaluate(({ yaw: y, pitch: p }) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
      }
    ).__eaglercraftGame;
    game?.setLook?.(y, p);
  }, { yaw, pitch });
}

/** Reads a world block through the bridge's synchronous queryBlock. */
async function queryBlock(page: Page, x: number, y: number, z: number): Promise<number> {
  return page.evaluate(({ bx, by, bz }) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { queryBlock?: (x: number, y: number, z: number) => number };
      }
    ).__eaglercraftGame;
    return game?.queryBlock ? game.queryBlock(bx, by, bz) : -2;
  }, { bx: x, by: y, bz: z });
}

/** The survival container panel size (InventoryScreen). */
const SURVIVAL_PANEL = { w: 176, h: 166 };
/** The crafting-table container panel size (CraftingTableScreen). */
const TABLE_PANEL = { w: 176, h: 166 };

// Survival inventory panel slot centers (panel-relative).
const SURV_HOTBAR = (col: number) => ({ x: 7 + col * 18 + 9, y: 141 + 9 });
const SURV_MAIN = (col: number, row: number) => ({ x: 7 + col * 18 + 9, y: 83 + row * 18 + 9 });
/** The 2x2 craft grid cells, row-major (vanilla InventoryMenu layout). */
const SURV_CRAFT = (index: number) => ({
  x: 97 + (index % 2) * 18 + 9,
  y: 17 + Math.floor(index / 2) * 18 + 9,
});
const SURV_RESULT = { x: 153 + 9, y: 27 + 9 };

// Crafting-table panel slot centers (vanilla CraftingMenu layout).
const TABLE_GRID = (index: number) => ({
  x: 30 + (index % 3) * 18 + 9,
  y: 17 + Math.floor(index / 3) * 18 + 9,
});
const TABLE_RESULT = { x: 124 + 9, y: 35 + 9 };
/** The player hotbar row on the crafting-table panel (vanilla (8,84) inventory block). */
const TABLE_HOTBAR = (col: number) => ({ x: 8 + col * 18 + 9, y: 142 + 9 });
/** The player main inventory rows on the crafting-table panel. */
const TABLE_MAIN = (col: number, row: number) => ({ x: 8 + col * 18 + 9, y: 84 + row * 18 + 9 });

const OAK_LOG = 351;
const OAK_PLANKS = 15;
const COBBLESTONE = 4;
const CRAFTING_TABLE = 375;
const STICK = 4096;
const WOODEN_PICKAXE = 4097;
const WOODEN_AXE = 4098;
const STONE_PICKAXE = 4102;

interface BootOptions {
  /** Hotbar seed applied right after entering the world. */
  hotbar?: [number, number, number][];
}

/** Boots the client, enters the survival world and applies the hotbar seed. */
async function bootSurvival(
  page: Page,
  testInfo: import('@playwright/test').TestInfo,
  options: BootOptions = {},
): Promise<{ gui: { w: number; h: number } }> {
  await page.goto('/index.html', { waitUntil: 'commit' });
  expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
  expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
  await waitForFpsReading(page, 15_000);

  const telemetry = await readGameTelemetry(page);
  if (!telemetry?.canEnterWorld) {
    await attachMilestoneScreenshot(page, testInfo, '00-craft-gate');
    test.skip(true, 'the client cannot enter a world yet');
    return { gui: { w: 0, h: 0 } };
  }
  if (!(await hasGameplayBridge(page))) {
    test.skip(true, 'the deployed bundle predates the gameplay bridge — rebuild to activate');
    return { gui: { w: 0, h: 0 } };
  }

  const state = await enterWorld(page);
  if (gameplayOf(state).creative) {
    test.skip(true, 'the default world is creative on this bundle — crafting needs a survival world');
    return { gui: { w: 0, h: 0 } };
  }

  for (const [slot, id, count] of options.hotbar ?? []) {
    await setHotbarSlot(page, slot, id, count);
  }
  await waitForTelemetry(page, 5_000, (t) => {
    const gp = gameplayOf(t);
    return (options.hotbar ?? []).every(([slot, id, count]) => {
      const stack = gp.hotbar.slots[slot];
      return !!stack && stack[0] === id && stack[1] === count;
    });
  });

  // The canvas settles (DPR/gui-scale re-init) within the first seconds
  // of a session; a boot with a slower TeaVM evaluation shifts the settle
  // window, so require two consecutive equal scale readings before the
  // panel coordinates are cached - otherwise clicks drift by a cell.
  let previous: number | null = null;
  for (let i = 0; i < 20; i++) {
    const { w, h } = await readGuiSize(page);
    if (previous !== null && previous === w && w > 0 && h > 0) {
      return { gui: { w, h } };
    }
    previous = w;
    await page.waitForTimeout(500);
  }
  return { gui: await readGuiSize(page) };
}

test.describe('crafting (Feature 8)', () => {
  test('2x2 grid crafts planks and sticks with vanilla slot semantics', async ({}, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    const { gui } = await bootSurvival(page, testInfo, {
      hotbar: [[0, OAK_LOG, 8]],
    });
    await page.keyboard.press('KeyE');
    const opened = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('InventoryScreen'),
    );
    expect(opened, 'E must open the InventoryScreen').toBeTruthy();

    // ── Pick up the 8 logs and place the whole stack in craft cell 0 ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(0).x, SURV_CRAFT(0).y);
    const seeded = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.craft?.grid?.[0]?.[0] === OAK_LOG
        && gp.craft.grid[0]?.[1] === 8
        && gp.craft.result?.[0] === OAK_PLANKS
        && gp.craft.result?.[1] === 4;
    });
    expect(
      seeded,
      `one oak log must craft 4 oak planks (telemetry: ${JSON.stringify(opened?.gameplay)})`,
    ).toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '01-planks-result');

    // ── Take the result onto the cursor (vanilla ResultSlot) ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_RESULT.x, SURV_RESULT.y);
    const took = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.carried?.[0] === OAK_PLANKS && gp.carried?.[1] === 4
        && gp.craft?.grid?.[0]?.[1] === 7;
    });
    expect(took, 'taking the result must consume one log and leave the recipe live').toBeTruthy();

    // ── Park the planks, pick the logs back out of the grid ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_MAIN(0, 0).x, SURV_MAIN(0, 0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(0).x, SURV_CRAFT(0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_MAIN(1, 0).x, SURV_MAIN(1, 0).y);
    const parked = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.main?.[0]?.[0] === OAK_PLANKS
        && gp.main?.[1]?.[0] === OAK_LOG
        && gp.craft?.grid?.every((c) => c === null);
    });
    expect(parked, 'the grid must empty back into the inventory').toBeTruthy();

    // ── Sticks: one plank in each cell of a grid column (vanilla 1x2) ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_MAIN(0, 0).x, SURV_MAIN(0, 0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(0).x, SURV_CRAFT(0).y, 'right');
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(2).x, SURV_CRAFT(2).y, 'right');
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_MAIN(2, 0).x, SURV_MAIN(2, 0).y);
    const sticked = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      return gp.craft?.result?.[0] === STICK && gp.craft.result?.[1] === 4;
    });
    expect(sticked, 'a vertical plank pair must craft 4 sticks').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '02-sticks-result');

    // ── Take the sticks and close: the carried stack returns to the hotbar ──
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_RESULT.x, SURV_RESULT.y);
    await page.keyboard.press('KeyE');
    const closed = await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    expect(closed, 'E must close the inventory screen').toBeTruthy();
    const final = await readGameTelemetry(page);
    const gp = gameplayOf(final!);
    expect(gp.craft, 'the crafting telemetry must vanish with the screen').toBeUndefined();
    expect(gp.hotbar.slots[0]?.[0], 'the returned sticks must land in the hotbar').toBe(STICK);
    // 4 planks were picked from main 0: 1+1 went into the grid (consumed by
    // the stick craft), the 2 leftovers were parked in main 2
    expect(gp.main?.[2]?.[0], 'the leftover planks must stay in the main inventory').toBe(OAK_PLANKS);
    expect(gp.main?.[2]?.[1], 'the leftover plank count must be exact').toBe(2);

    expect(pageErrors, 'the crafting flow must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '03-crafted-and-closed');
    await context.close();
  });

  test('shift-clicking the result crafts the whole ingredient stack', async ({}, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    const { gui } = await bootSurvival(page, testInfo, {
      hotbar: [[0, OAK_LOG, 4]],
    });
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('InventoryScreen'));

    // 4 logs in one cell; craft-all must loop 4 planks x4 into the inventory
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(0).x, SURV_CRAFT(0).y);
    await page.keyboard.down('ShiftLeft');
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_RESULT.x, SURV_RESULT.y);
    await page.keyboard.up('ShiftLeft');
    const crafted = await waitForTelemetry(page, 5_000, (t) => {
      const gp = gameplayOf(t);
      const mainCount = (gp.main ?? []).reduce(
        (sum, s) => sum + (s && s[0] === OAK_PLANKS ? s[1] : 0), 0);
      const hotbarCount = (gp.hotbar.slots).reduce(
        (sum, s) => sum + (s && s[0] === OAK_PLANKS ? s[1] : 0), 0);
      return mainCount + hotbarCount === 16;
    });
    expect(crafted, 'shift-click must craft the whole 4-log stack into 16 planks').toBeTruthy();
    const after = gameplayOf((await readGameTelemetry(page))!);
    expect(after.craft?.grid?.[0], 'the grid must be empty after craft-all').toBeNull();
    expect(after.carried ?? null, 'craft-all must leave the cursor empty').toBeNull();

    expect(pageErrors, 'craft-all must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '04-craft-all');
    await context.close();
  });

  test('the crafting-table block opens the 3x3 screen and crafts the wooden pickaxe', async ({}, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 150_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    installConsoleRecorder(page);

    const { gui } = await bootSurvival(page, testInfo, {
      hotbar: [[0, OAK_PLANKS, 64], [5, STICK, 8]],
    });

    // ── Craft the table in the 2x2 grid (four planks) ──
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('InventoryScreen'));
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    for (const cell of [0, 1, 2, 3]) {
      await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(cell).x, SURV_CRAFT(cell).y, 'right');
    }
    // park the 60 leftover planks so the cursor is free for the result take
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    const tableResult = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('InventoryScreen')
      && gameplayOf(t).craft?.result?.[0] === CRAFTING_TABLE);
    expect(tableResult, 'four planks must craft the crafting table').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '05-table-result');
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_RESULT.x, SURV_RESULT.y);
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    // the carried table returns to the first free hotbar slot (1; 5 holds sticks)
    const parked = await waitForTelemetry(page, 5_000, (t) =>
      gameplayOf(t).hotbar.slots[1]?.[0] === CRAFTING_TABLE);
    expect(parked, 'the crafted table must return into the hotbar').toBeTruthy();

    // ── Aim at the grass one block ahead (before grabbing — setLook after
    // the grab would be dragged by the move-to-center delta) ──
    // pitch 58° from eye height hits the ground block at (0,-61,1); its
    // neighbor (0,-60,1) is free of the player box, unlike straight down
    const canvas = await page.evaluate(() => {
      const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
      const c = byId ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
      if (!c) return null;
      const rect = c.getBoundingClientRect();
      return { x: rect.left + c.clientWidth / 2, y: rect.top + c.clientHeight / 2 };
    });
    expect(canvas, 'the game canvas must exist').toBeTruthy();
    // Pointer-lock discipline: a mouse move while grabbed is a look delta,
    // so every canvas interaction moves to center FIRST (absorbing the
    // drag), then snaps the camera (setLook also clears accumulated
    // deltas), then clicks from a stationary pointer.
    await page.mouse.move(canvas!.x, canvas!.y);
    await page.mouse.click(canvas!.x, canvas!.y); // vanilla click-to-grab
    await page.waitForTimeout(400);
    await setLook(page, 0, 58);
    await waitForTelemetry(page, 10_000, (t) => {
      const gp = t.gameplay as Gameplay | null | undefined;
      return Math.abs((gp?.player?.pitch ?? 0) - 58) < 1 && gp?.hit != null;
    });
    await selectHotbarSlot(page, 1);
    // the selection request is consumed on the next game tick - under a
    // SwiftShader stall that can outlast any fixed wait, so poll for it
    await waitForTelemetry(page, 8_000, (t) => gameplayOf(t).hotbar.selected === 1);
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    expect(await waitForCellBlock(page, 10_000, 0, -60, 1, CRAFTING_TABLE),
      `right-click must place the table at (0,-60,1) (state: ${
        JSON.stringify(gameplayOf((await readGameTelemetry(page))!))})`).toBeTruthy();

    // ── Re-aim at the table's top face (eye->(0.5,-59,1.5) ≈ 32° down) ──
    await setLook(page, 0, 32);
    await waitForTelemetry(page, 10_000, (t) => {
      const gp = t.gameplay as Gameplay | null | undefined;
      return Math.abs((gp?.player?.pitch ?? 0) - 32) < 1;
    });
    // ── Right-click the table: the 3x3 screen opens ──
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    const tableOpen = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('CraftingTableScreen'));
    expect(tableOpen, 'right-clicking the table must open the 3x3 screen').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '06-table-screen');

    // ── Wooden pickaxe: XXX / ' # ' / ' # ' — planks over sticks ──
    // pick the plank stack from the hotbar, place one into each head cell,
    // park the remainder, then the same for the stick shaft cells
    await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(0).x, TABLE_HOTBAR(0).y);
    for (const cell of [0, 1, 2]) {
      await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_GRID(cell).x, TABLE_GRID(cell).y, 'right');
    }
    await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(0).x, TABLE_HOTBAR(0).y);
    await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(5).x, TABLE_HOTBAR(5).y);
    for (const cell of [4, 7]) {
      await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_GRID(cell).x, TABLE_GRID(cell).y, 'right');
    }
    await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(5).x, TABLE_HOTBAR(5).y);
    const pickaxe = await waitForTelemetry(page, 5_000, (t) =>
      t.screen.includes('CraftingTableScreen')
      && gameplayOf(t).craft?.result?.[0] === WOODEN_PICKAXE);
    expect(pickaxe, 'the pickaxe pattern must resolve in the 3x3 grid').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '07-pickaxe-result');
    await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_RESULT.x, TABLE_RESULT.y);
    const pickaxeTaken = await waitForTelemetry(page, 5_000, (t) =>
      gameplayOf(t).carried?.[0] === WOODEN_PICKAXE);
    expect(pickaxeTaken, 'the pickaxe must land on the cursor').toBeTruthy();

    // ── E closes; grid leftovers and the carried pickaxe return ──
    await page.keyboard.press('KeyE');
    const closed = await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    expect(closed, 'E must close the crafting table screen').toBeTruthy();
    const done = gameplayOf((await readGameTelemetry(page))!);
    const pickaxeStack = done.hotbar.slots.find((s) => s?.[0] === WOODEN_PICKAXE);
    expect(pickaxeStack, 'the returned pickaxe must reach the inventory').toBeTruthy();
    expect(pickaxeStack![1], 'tools must not stack (vanilla max stack 1)').toBe(1);

    expect(pageErrors, 'the table flow must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '08-pickaxe-crafted');
    await context.close();
  });

  test('mirrored axe placement and the stone tool tag both resolve', async ({}, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 180_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    const consoleRecorder = installConsoleRecorder(page);

    const { gui } = await bootSurvival(page, testInfo, {
      hotbar: [[0, OAK_PLANKS, 64], [5, STICK, 8], [6, COBBLESTONE, 16]],
    });

    // ── Table craft + place + open (condensed; geometry proven in test 3) ──
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('InventoryScreen'));
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    for (const cell of [0, 1, 2, 3]) {
      await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_CRAFT(cell).x, SURV_CRAFT(cell).y, 'right');
    }
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_HOTBAR(0).x, SURV_HOTBAR(0).y);
    await clickPanel(page, gui, SURVIVAL_PANEL.w, SURVIVAL_PANEL.h, SURV_RESULT.x, SURV_RESULT.y);
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');
    const parkedT = await waitForTelemetry(page, 5_000, (t) => gameplayOf(t).hotbar.slots[1]?.[0] === CRAFTING_TABLE);
    if (process.env.CRAFT_DEBUG) {
      console.log('CRAFT-DEBUG parked:', JSON.stringify({
        parked: !!parkedT,
        gp: gameplayOf((await readGameTelemetry(page))!),
        console: consoleRecorder.text().split('\n').filter((l) => l.includes('Screen]') || l.includes('[Inventory]')).join(' | ').slice(-900),
      }));
    }
    expect(parkedT, `the condensed table park must work (state: ${
      JSON.stringify(gameplayOf((await readGameTelemetry(page))!))}; console: ${
      consoleRecorder.text().split('\n').filter((l) => l.includes('Screen]') || l.includes('[Inventory]')).join(' | ').slice(-1500)})`).toBeTruthy();

    await setLook(page, 0, 58);
    await waitForTelemetry(page, 10_000, (t) => {
      const gp = t.gameplay as Gameplay | null | undefined;
      return Math.abs((gp?.player?.pitch ?? 0) - 58) < 1 && gp?.hit != null;
    });
    const canvas = await page.evaluate(() => {
      const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
      const c = byId ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[]).find((k) => k.clientWidth > 0);
      if (!c) return null;
      const rect = c.getBoundingClientRect();
      return { x: rect.left + c.clientWidth / 2, y: rect.top + c.clientHeight / 2 };
    });
    // pointer-lock discipline: absorb the move drag, grab, re-snap
    await page.mouse.move(canvas!.x, canvas!.y);
    await page.mouse.click(canvas!.x, canvas!.y);
    await page.waitForTimeout(400);
    await setLook(page, 0, 58);
    await waitForTelemetry(page, 10_000, (t) =>
      Math.abs((gameplayOf(t).player?.pitch ?? 0) - 58) < 1);
    await selectHotbarSlot(page, 1);
    await waitForTelemetry(page, 8_000, (t) => gameplayOf(t).hotbar.selected === 1);
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    expect(await waitForCellBlock(page, 10_000, 0, -60, 1, CRAFTING_TABLE),
      `the condensed table placement must work at (0,-60,1) (state: ${
        JSON.stringify(gameplayOf((await readGameTelemetry(page))!))})`).toBeTruthy();
    await setLook(page, 0, 32);
    await waitForTelemetry(page, 10_000, (t) =>
      Math.abs((gameplayOf(t).player?.pitch ?? 0) - 32) < 1);
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('CraftingTableScreen'));

    /**
     * Picks a whole hotbar stack, right-places one item into each cell,
     * parks the rest. Headless input delivery occasionally no-ops a click
     * (per-frame input latch), so the grid is verified and the placement
     * retried once before the caller's assertion sees it.
     */
    const placeMaterial = async (hotbarCol: number, cells: number[]) => {
      for (let attempt = 0; attempt < 2; attempt++) {
        await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(hotbarCol).x, TABLE_HOTBAR(hotbarCol).y);
        for (const cell of cells) {
          await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_GRID(cell).x, TABLE_GRID(cell).y, 'right');
        }
        await clickPanel(page, gui, TABLE_PANEL.w, TABLE_PANEL.h, TABLE_HOTBAR(hotbarCol).x, TABLE_HOTBAR(hotbarCol).y);
        const gp = gameplayOf((await readGameTelemetry(page))!);
        if (cells.every((c) => (gp.craft?.grid?.[c]?.[0] ?? 0) !== 0)) {
          return;
        }
      }
    };

    // ── Axe, vanilla orientation: jar pattern ["XX","X#"," #"] — sticks at
    // relative (1,1),(1,2) = cells 4,7; planks at 0,1,3 ──
    await placeMaterial(0, [0, 1, 3]);
    await placeMaterial(5, [4, 7]);
    const axe = await waitForTelemetry(page, 5_000, (t) =>
      gameplayOf(t).craft?.result?.[0] === WOODEN_AXE);
    expect(axe, `the left-aligned axe pattern must craft (state: ${
      JSON.stringify(gameplayOf((await readGameTelemetry(page))!))}; console: ${
      consoleRecorder.text().split('\n').filter((x) => x.includes('Screen]') || x.includes('[Inventory]')).join(' | ').slice(-1500)})`).toBeTruthy();

    // closing returns the untouched grid stacks (vanilla removed())
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');

    // ── Re-open: mirrored axe orientation must also craft ──
    await page.mouse.move(canvas!.x, canvas!.y); // absorb the move drag
    await setLook(page, 0, 32);
    await waitForTelemetry(page, 5_000, (t) =>
      Math.abs((gameplayOf(t).player?.pitch ?? 0) - 32) < 1);
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('CraftingTableScreen'));
    // ── Re-open: mirrored axe orientation (horizontally flipped pattern
    // ["XX","#X","#"]) = planks cells 0,1,4 + stick cells 3,6 ──
    await placeMaterial(0, [0, 1, 4]);
    await placeMaterial(5, [3, 6]);
    const axeMirrored = await waitForTelemetry(page, 5_000, (t) =>
      gameplayOf(t).craft?.result?.[0] === WOODEN_AXE);
    expect(axeMirrored, 'the mirrored axe orientation must also craft (vanilla mirror)').toBeTruthy();
    await page.keyboard.press('KeyE');
    await waitForTelemetry(page, 5_000, (t) => t.screen === 'in-game');

    // ── Stone pickaxe: the stone_tool_materials tag accepts cobblestone ──
    await page.mouse.move(canvas!.x, canvas!.y); // absorb the move drag
    await setLook(page, 0, 32);
    await waitForTelemetry(page, 5_000, (t) =>
      Math.abs((gameplayOf(t).player?.pitch ?? 0) - 32) < 1);
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' });
    await waitForTelemetry(page, 5_000, (t) => t.screen.includes('CraftingTableScreen'));
    await placeMaterial(6, [0, 1, 2]);
    await placeMaterial(5, [4, 7]);
    const stonePick = await waitForTelemetry(page, 5_000, (t) =>
      gameplayOf(t).craft?.result?.[0] === STONE_PICKAXE);
    expect(stonePick, 'cobblestone must satisfy the stone tool material tag').toBeTruthy();

    expect(pageErrors, 'the tag/mirror flow must not throw page errors').toEqual([]);
    await attachMilestoneScreenshot(page, testInfo, '10-tags-and-mirror');
    await context.close();
  });
});

/** Reads the block id at a world position through the bridge. */
async function queryHitBlock(
  page: Page,
  hit: { x: number; y: number; z: number },
): Promise<number> {
  return page.evaluate(({ bx, by, bz }) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { queryBlock?: (x: number, y: number, z: number) => number };
      }
    ).__eaglercraftGame;
    return game?.queryBlock ? game.queryBlock(bx, by, bz) : -2;
  }, { bx: hit.x, by: hit.y, bz: hit.z });
}

/**
 * Polls the world block at a fixed cell through the bridge until it equals
 * the given id. (Crosshair-based polling is fragile: a late pointer-lock
 * grant can drag the aim after the placement click; the placed cell is
 * known exactly, so query it directly.)
 */
async function waitForCellBlock(
  page: Page,
  deadlineMs: number,
  x: number,
  y: number,
  z: number,
  blockId: number,
): Promise<boolean> {
  const deadline = Date.now() + deadlineMs;
  while (Date.now() < deadline) {
    if ((await queryHitBlock(page, { x, y, z })) === blockId) {
      return true;
    }
    await page.waitForTimeout(150);
  }
  return false;
}
