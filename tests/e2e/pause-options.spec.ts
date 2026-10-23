import type { ConsoleEntry } from './support';
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

/** Polls the telemetry until the predicate holds or the deadline expires. */
async function waitForTelemetry(
  page: import('@playwright/test').Page,
  deadlineMs: number,
  predicate: (telemetry: NonNullable<Awaited<ReturnType<typeof readGameTelemetry>>>) => boolean,
): Promise<Awaited<ReturnType<typeof readGameTelemetry>>> {
  const deadline = Date.now() + deadlineMs;
  let state = await readGameTelemetry(page);
  while (Date.now() < deadline && !(state && predicate(state))) {
    await page.waitForTimeout(250);
    state = await readGameTelemetry(page);
  }
  return state;
}

/** Clicks a bridge button by its telemetry id (move + settle + click). */
async function clickBridgeButton(
  page: import('@playwright/test').Page,
  id: string,
  failMessage: string,
): Promise<void> {
  const coords = await getButtonCssCoords(page, id);
  expect(coords, failMessage).not.toBeNull();
  await page.mouse.move(coords!.cssX, coords!.cssY);
  await page.waitForTimeout(100);
  await page.mouse.click(coords!.cssX, coords!.cssY);
}

test.describe('pause menu, options and world persistence', () => {
  test('ESC opens the vanilla pause menu with the in-world blur, Options live-edits the blur slider (capability-gated)', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    const consoleRecorder = installConsoleRecorder(page);

    // Boot to the title screen
    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry?.canEnterWorld) {
      await attachMilestoneScreenshot(page, testInfo, '01-pause-gate');
      test.skip(true, `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`);
      return;
    }

    // ── Enter a world through the bridge (the Singleplayer contract) ──
    await page.evaluate(() => {
      const game = (self as unknown as { __eaglercraftGame?: { enterWorld?: () => void } })
        .__eaglercraftGame;
      game?.enterWorld?.();
    });
    const worldState = await waitForTelemetry(page, 30_000, (t) => t.inWorld);
    expect(worldState?.inWorld, 'the client must be in a world before the pause menu test').toBe(true);
    expect(worldState?.screen, 'in world with no screen open the bridge reports "in-game"').toBe('in-game');

    // ── ESC opens the vanilla pause menu over the blurred world ──
    await page.keyboard.press('Escape');
    const pauseState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('PauseScreen'));
    expect(
      pauseState?.screen.includes('PauseScreen'),
      `ESC in world must open the PauseScreen (last telemetry: ${JSON.stringify(pauseState)})`,
    ).toBe(true);
    expect(
      pauseState?.panoramaBlur,
      'the pause menu must blur the world behind it with the default radius 5',
    ).toBe(5);

    // ── The full vanilla pause layout, with the right disabled set ──
    const pauseButtons = pauseState?.buttons ?? [];
    const pauseIds = pauseButtons.map((b) => b.id);
    for (const expectedId of [
      'backtogame',
      'advancements',
      'statistics',
      'sendfeedback',
      'reportbugs',
      'options',
      'opentolan',
      'saveandquittotitle',
    ]) {
      expect(
        pauseIds,
        `the pause menu must carry the "${expectedId}" button (got: ${JSON.stringify(pauseIds)})`,
      ).toContain(expectedId);
    }
    const byId = (id: string) => pauseButtons.find((b) => b.id === id);
    expect(byId('backtogame')?.active, 'Back to Game must be active').toBe(true);
    expect(byId('advancements')?.active, 'Advancements must be disabled (no system yet)').toBe(false);
    expect(byId('statistics')?.active, 'Statistics must be disabled (no system yet)').toBe(false);
    expect(byId('opentolan')?.active, 'Open to LAN must be disabled (no networking)').toBe(false);
    expect(byId('saveandquittotitle')?.active, 'Save and Quit to Title must be active').toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '02-pause-menu');

    // ── Options... opens the vanilla grid with the FOV slider + difficulty ──
    await clickBridgeButton(page, 'options', 'the pause menu must expose a clickable Options button');
    const optionsState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('OptionsScreen'));
    expect(
      optionsState?.screen.includes('OptionsScreen'),
      `clicking Options must open the OptionsScreen (last telemetry: ${JSON.stringify(optionsState)})`,
    ).toBe(true);
    const optionIds = (optionsState?.buttons ?? []).map((b) => b.id);
    expect(optionIds, 'the in-world Options header must carry the difficulty button').toContain('difficultycycle');
    expect(optionIds, 'the Options header must carry the FOV slider').toContain('fovslider');
    expect(optionIds, 'the Options grid must carry the Accessibility sub-screen button').toContain('accessibility');
    expect(optionIds, 'the Options footer must carry Done').toContain('done');
    await attachMilestoneScreenshot(page, testInfo, '03-options-screen');

    // ── Accessibility... -> the menuBackgroundBlurriness slider ──
    await clickBridgeButton(page, 'accessibility', 'the Options grid must expose a clickable Accessibility button');
    const accessState = await waitForTelemetry(page, 15_000, (t) =>
      t.screen.includes('AccessibilityOptionsScreen'));
    expect(
      accessState?.screen.includes('AccessibilityOptionsScreen'),
      `clicking Accessibility must open the AccessibilityOptionsScreen (last: ${JSON.stringify(accessState)})`,
    ).toBe(true);
    expect(
      accessState?.menuBackgroundBlurriness,
      'the blur option must start at the vanilla default 5',
    ).toBe(5);

    // ── Click the slider track at the 8/10 position (vanilla click-to-set) ──
    const slider = await getButtonCssCoords(page, 'menubackgroundblurriness');
    expect(slider, 'the Accessibility screen must expose the blur slider').not.toBeNull();
    const guiOffset = 4 + 0.8 * (slider!.button.w - 8); // vanilla setValueFromMouseX
    const targetCssX = slider!.cssX - (slider!.button.w / 2) * slider!.guiScale + guiOffset * slider!.guiScale;
    await page.mouse.move(targetCssX, slider!.cssY);
    await page.waitForTimeout(100);
    await page.mouse.click(targetCssX, slider!.cssY);
    const sliderState = await waitForTelemetry(page, 10_000, (t) => t.menuBackgroundBlurriness === 8);
    expect(
      sliderState?.menuBackgroundBlurriness,
      'clicking the slider at 8/10 must set menuBackgroundBlurriness to 8',
    ).toBe(8);
    expect(
      sliderState?.panoramaBlur,
      'the menu behind the Accessibility screen must now blur with radius 8',
    ).toBe(8);
    expect(
      await page.evaluate(() => {
        // PlatformRuntime namespaces every localStorage key (config-driven
        // prefix, default "eaglercraft"), so match on the key suffix.
        const hit = Object.keys(window.localStorage).find((key) =>
          key.endsWith('.options.menuBackgroundBlurriness'),
        );
        return hit !== undefined ? window.localStorage.getItem(hit) : null;
      }),
      'the new blur value must persist to localStorage',
    ).toBe('8');
    await attachMilestoneScreenshot(page, testInfo, '04-blur-slider-8');

    // ── Done -> Done -> pause; ESC back to the game; ESC pauses again ──
    await clickBridgeButton(page, 'done', 'the Accessibility screen must expose Done');
    await waitForTelemetry(page, 15_000, (t) => t.screen.includes('OptionsScreen'));
    await clickBridgeButton(page, 'done', 'the Options screen must expose Done');
    const backAtPause = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('PauseScreen'));
    expect(backAtPause?.screen.includes('PauseScreen'), 'Done must return to the pause menu').toBe(true);

    await page.keyboard.press('Escape');
    const backInGame = await waitForTelemetry(page, 15_000, (t) => t.screen === 'in-game');
    expect(backInGame?.screen, 'ESC on the pause menu must return to the game').toBe('in-game');
    expect(backInGame?.panoramaBlur, 'no screen open: no menu blur').toBe(0);

    await page.keyboard.press('Escape');
    const pausedAgain = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('PauseScreen'));
    expect(pausedAgain?.screen.includes('PauseScreen'), 'ESC again must pause again').toBe(true);

    expect(pageErrors, `no uncaught page errors:\n${pageErrors.join('\n')}`).toEqual([]);
    await attachSessionSummary(testInfo, {
      gated: false,
      path: 'pause-options-blur',
      pauseBlur: pauseState?.panoramaBlur,
      blurAfterSlider: sliderState?.menuBackgroundBlurriness,
      uncaughtPageErrors: pageErrors,
      consoleTail: consoleRecorder.text().slice(-1_000),
    });
    await context.close();
  });

  test('created worlds persist into the Select World list and rejoin (capability-gated)', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 120_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    const consoleRecorder = installConsoleRecorder(page);

    // Boot to the title screen
    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry?.canEnterWorld) {
      await attachMilestoneScreenshot(page, testInfo, '11-persist-gate');
      test.skip(true, `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`);
      return;
    }

    // ── The milestone world seeds a fresh profile's list ──
    await clickBridgeButton(page, 'singleplayer', 'the title screen must expose the singleplayer button');
    const selectState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('SelectWorldScreen'));
    expect(selectState?.screen.includes('SelectWorldScreen'), 'Singleplayer must open the Select World screen').toBe(true);
    const listIds = (selectState?.buttons ?? []).map((b) => b.id);
    expect(listIds, `the fresh list must contain the seeded milestone world (got: ${JSON.stringify(listIds)})`).toContain('newworld');

    // ── Create a distinctly-named world through the vanilla form ──
    await clickBridgeButton(page, 'createnewworld', 'the Select World screen must expose Create New World');
    const formState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('CreateWorldScreen'));
    expect(formState?.screen.includes('CreateWorldScreen'), 'Create New World must open the form').toBe(true);
    await page.keyboard.type('E2E Persist', { delay: 40 });
    await clickBridgeButton(page, 'createnewworld', 'the form footer must expose Create New World');

    const worldState = await waitForTelemetry(page, 30_000, (t) => t.inWorld);
    expect(worldState?.inWorld, 'the created world must load').toBe(true);

    // ── Save and Quit to Title through the pause menu ──
    await page.keyboard.press('Escape');
    const pauseState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('PauseScreen'));
    expect(pauseState?.screen.includes('PauseScreen'), 'ESC must pause').toBe(true);
    await clickBridgeButton(page, 'saveandquittotitle', 'the pause menu must expose Save and Quit');
    const titleState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('TitleScreen'));
    expect(titleState?.screen.includes('TitleScreen'), 'Save and Quit must return to the title screen').toBe(true);
    expect(titleState?.inWorld, 'the level must be dropped after quitting').toBe(false);

    // ── The persisted world appears in the Select World list ──
    await clickBridgeButton(page, 'singleplayer', 'the title screen must expose the singleplayer button again');
    const selectAgain = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('SelectWorldScreen'));
    const listAgain = (selectAgain?.buttons ?? []).map((b) => b.id);
    expect(listAgain, `the created world must persist into the list (got: ${JSON.stringify(listAgain)})`).toContain('newworlde2epersist');
    await attachMilestoneScreenshot(page, testInfo, '12-persisted-world-list');

    // ── Selecting it enables Play; playing joins THAT world ──
    await clickBridgeButton(page, 'newworlde2epersist', 'the persisted world row must be clickable');
    const playState = await waitForTelemetry(page, 10_000, (t) =>
      (t.buttons ?? []).some((b) => b.id === 'playselectedworld' && b.active === true));
    expect(
      playState?.buttons.some((b) => b.id === 'playselectedworld' && b.active === true),
      'selecting the persisted world must enable Play Selected World',
    ).toBe(true);
    await clickBridgeButton(page, 'playselectedworld', 'Play Selected World must be clickable');
    const rejoined = await waitForTelemetry(page, 30_000, (t) => t.inWorld);
    expect(rejoined?.inWorld, 'the persisted world must rejoin').toBe(true);
    expect(
      consoleRecorder.text().includes("Loading world: New WorldE2E Persist"),
      `joining must load the persisted world's name (console tail:\n${consoleRecorder.text().slice(-1_500)})`,
    ).toBe(true);

    expect(pageErrors, `no uncaught page errors:\n${pageErrors.join('\n')}`).toEqual([]);
    await attachSessionSummary(testInfo, {
      gated: false,
      path: 'world-persistence',
      listBefore: listIds,
      listAfter: listAgain,
      uncaughtPageErrors: pageErrors,
      consoleTail: consoleRecorder.text().slice(-1_000),
    });
    await context.close();
  });
});
