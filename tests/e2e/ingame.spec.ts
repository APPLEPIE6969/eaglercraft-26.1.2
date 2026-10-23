import type { ConsoleRecorder, ConsoleEntry } from './support';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  attachSessionSummary,
  getButtonCssCoords,
  installConsoleRecorder,
  parseFps,
  readGameSessionState,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
  writeTestArtifact,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

/**
 * The client's boot milestones as logged by the TeaVM bundle. Each marker is
 * emitted by a specific stage of the engine startup sequence
 * (ClientMain -> EaglerCraft -> Minecraft -> EPKLoader), so their presence
 * tells us exactly how far a session got before it failed.
 */
const ENGINE_MILESTONES = [
  { marker: 'WebGL2: true', meaning: 'the WebGL2 context was created' },
  {
    marker: '=== Initialization complete ===',
    meaning: 'EaglerCraft.initialize() finished without crashing',
  },
  {
    marker: 'Minecraft instance created!',
    meaning: 'the Minecraft game engine instance was constructed',
  },
] as const;

/**
 * Parses the EPK loader line ("Asset bundle parsed: 7470553 bytes,
 * 10464 entries") into its numbers, or null when the client never parsed
 * the asset archive.
 */
function parseEpkMilestone(entries: ConsoleEntry[]): { bytes: number; entries: number } | null {
  for (const entry of entries) {
    const match = entry.text.match(/Asset bundle parsed: (\d+) bytes, (\d+) entries/);
    if (match) {
      return { bytes: Number(match[1]), entries: Number(match[2]) };
    }
  }
  return null;
}

test.describe('go in game', () => {
  // This spec intentionally boots its own page instead of reusing the shared
  // clientPage fixture: it verifies the full lifecycle from the first request
  // to the running game, and the milestone screenshots must not depend on
  // which other spec file happened to boot the shared page first.
  test('boots the client and reaches the running game', async ({ browser }, testInfo) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    // Installed before navigation so every boot milestone is captured.
    const consoleRecorder: ConsoleRecorder = installConsoleRecorder(page);
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    const startedAt = Date.now();

    // ── Milestone 1: the shell serves and shows its loading screen ──
    await page.goto('/index.html', { waitUntil: 'commit' });
    await page.locator('#loading-screen').waitFor({ state: 'attached' });
    const shellState = await readGameSessionState(page);
    expect(
      shellState.loadingVisible || shellState.titleVisible,
      'the client must show either the loading screen or the game right after the shell loads',
    ).toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '01-loading-screen');

    // ── Milestone 2: the TeaVM bundle evaluates and exports its entry ──
    const booted = await waitForClientBootstrap(page, BOOT_TIMEOUT);
    expect(booted, 'the TeaVM bundle must export self.main').toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '02-bundle-booted');

    // ── Milestone 3: the title screen — the game is interactive ──
    const reached = await waitForReadyState(page, READY_TIMEOUT);
    const titleState = await readGameSessionState(page);
    const titleScreenMs = Date.now() - startedAt;
    expect(
      reached,
      `the client must reach the title screen (state: ${JSON.stringify(titleState)})`,
    ).toBe(true);
    expect(
      titleState.crashOverlayVisible || titleState.crashScreenShown,
      'the client must not show a crash screen at the title screen',
    ).toBe(false);
    await attachMilestoneScreenshot(page, testInfo, '03-title-screen');

    // ── Milestone 3b: the vanilla panorama cubemap is live and spinning ──
    // GameRenderer draws the 26.1.2 CubeMap panorama behind every menu
    // screen; the telemetry bridge exposes its build state and spin angle.
    // The title overlay can become visible a beat before the first MC frame
    // uploads the face textures, so poll instead of asserting instantly.
    const PANORAMA_TIMEOUT = 15_000;
    const panoramaDeadline = Date.now() + PANORAMA_TIMEOUT;
    let panorama: { ready: boolean | null; spin: number | null } = {
      ready: null,
      spin: null,
    };
    while (Date.now() < panoramaDeadline) {
      panorama = await page.evaluate(() => {
        const game = (
          self as unknown as {
            __eaglercraftGame?: { panoramaReady?: boolean; panoramaSpin?: number };
          }
        ).__eaglercraftGame;
        return { ready: game?.panoramaReady ?? null, spin: game?.panoramaSpin ?? null };
      });
      if (panorama.ready === true) break;
      await page.waitForTimeout(250);
    }
    expect(
      panorama.ready,
      `the panorama cubemap must be built from the six face PNGs at the title screen (last state: ${JSON.stringify(panorama)})`,
    ).toBe(true);
    expect(
      typeof panorama.spin,
      'the panorama spin angle must be a number once the cubemap is live',
    ).toBe('number');
    await page.waitForTimeout(1_500);
    const panoramaLater = await page.evaluate(() => {
      const game = (
        self as unknown as { __eaglercraftGame?: { panoramaSpin?: number } }
      ).__eaglercraftGame;
      return game?.panoramaSpin ?? null;
    });
    expect(
      panoramaLater,
      'the panorama spin must advance over time (vanilla Panorama tick)',
    ).not.toBe(panorama.spin);

    // ── Milestone 4: the engine milestones must all be in the console ──
    const entries = consoleRecorder.entries();
    const text = consoleRecorder.text();
    for (const { marker, meaning } of ENGINE_MILESTONES) {
      expect(
        text.includes(marker),
        `the console must record "${marker}" (${meaning}). Full console:\n${text}`,
      ).toBe(true);
    }

    const epk = parseEpkMilestone(entries);
    expect(epk, 'the client must parse assets.epk at boot').not.toBeNull();
    expect(
      epk!.bytes,
      'assets.epk must carry a non-empty payload',
    ).toBeGreaterThan(0);
    expect(
      epk!.entries,
      'assets.epk must contain the vanilla asset entries',
    ).toBeGreaterThan(0);

    // ── Milestone 5: the game loop is alive and rendering frames ──
    // The FPS counter only publishes its first reading about a second after
    // the title screen appears, so poll for it instead of asserting at the
    // moment the overlay becomes visible.
    const first = await waitForFpsReading(page, 15_000);
    expect(
      first.fps,
      'the game loop must start producing frames (FPS counter)',
    ).toBeGreaterThan(0);

    // Give the loop a couple of seconds, then confirm it is still running.
    await page.waitForTimeout(2_500);
    const runningState = await readGameSessionState(page);
    const fpsAfter = parseFps(runningState.fpsText);
    expect(fpsAfter, `the FPS counter must keep updating, got ${runningState.fpsText}`).toBeGreaterThan(0);
    expect(
      runningState.crashOverlayVisible || runningState.crashScreenShown,
      'the client must not crash while the game loop runs',
    ).toBe(false);
    await attachMilestoneScreenshot(page, testInfo, '04-running-in-game');

    // ── Evidence: a structured session summary for the report ──
    const milestoneEvents = entries
      .filter((entry) =>
        ENGINE_MILESTONES.some(({ marker }) => entry.text.includes(marker)) ||
        /Asset bundle parsed|First frame|Starting game loop/.test(entry.text),
      )
      .map((entry) => ({ at: entry.at - startedAt, text: entry.text }));
    const endTelemetry = await readGameTelemetry(page);
    await attachSessionSummary(testInfo, {
      titleScreenMs,
      milestones: milestoneEvents,
      assetsEpk: epk,
      fps: { firstSample: first.fps, secondSample: fpsAfter },
      gameTelemetry: endTelemetry,
      uncaughtPageErrors: pageErrors,
      consoleMessageCount: entries.length,
    });

    // ── The session must not have thrown anything uncaught ──
    expect(
      pageErrors,
      `the session must run without uncaught page errors:\n${pageErrors.join('\n')}`,
    ).toEqual([]);

    await context.close();
  });

  /**
   * Stage 6 of the "go in game" journey: Singleplayer → world → HUD.
   *
   * This test is capability-gated: it fully boots the client, then reads the
   * `window.__eaglercraftGame` telemetry bridge that the adapter pushes. When
   * the client does not yet report `canEnterWorld` (or the bundle predates
   * the bridge), the test skips with that evidence attached — a screenshot of
   * the gate state plus a `game-telemetry.json` capture. The moment the
   * client flips `canEnterWorld` to true, this test activates and enforces
   * the world-entry contract documented in docs/E2E.md.
   */
  test('enters a world and reaches the HUD (capability-gated)', async ({ browser }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 60_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));

    // Boot to the running title screen — the same milestones the journey test
    // asserts in detail; here they are the preconditions for the gate read.
    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(
      await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main',
    ).toBe(true);
    expect(
      await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen before the world-entry gate',
    ).toBe(true);
    await waitForFpsReading(page, 15_000);

    // ── The capability gate ──
    const telemetry = await readGameTelemetry(page);
    // Folder-layout reports do not persist text attachments, so the copy in
    // test-results/ is what survives in the CI artifact.
    await writeTestArtifact(testInfo, 'game-telemetry.json', JSON.stringify(telemetry, null, 2));
    await attachMilestoneScreenshot(page, testInfo, '05-world-entry-gate');

    if (!telemetry) {
      await attachSessionSummary(testInfo, {
        gated: true,
        reason: 'telemetry-bridge-missing',
        uncaughtPageErrors: pageErrors,
      });
      test.skip(
        true,
        'the deployed bundle predates the __eaglercraftGame telemetry bridge — rebuild the client to activate the world-entry stage',
      );
      return;
    }
    if (!telemetry.canEnterWorld) {
      await attachSessionSummary(testInfo, {
        gated: true,
        reason: 'world-entry-not-implemented',
        telemetry,
        uncaughtPageErrors: pageErrors,
      });
      test.skip(
        true,
        `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)}) — this test activates automatically once canEnterWorld flips to true`,
      );
      return;
    }

    // ── Stage 6: drive the Singleplayer → world → HUD path ──
    // Contract (docs/E2E.md): a client that reports canEnterWorld=true must
    // also expose enterWorld() on the bridge, resolving once the HUD is up.
    const hasEnterWorld = await page.evaluate(() => {
      const bridge = (window as unknown as { __eaglercraftGame?: { enterWorld?: unknown } })
        .__eaglercraftGame;
      return typeof bridge?.enterWorld === 'function';
    });
    expect(
      hasEnterWorld,
      'canEnterWorld=true requires enterWorld() on the __eaglercraftGame bridge (docs/E2E.md, world-entry contract)',
    ).toBe(true);
    await page.evaluate(() => {
      const bridge = (
        window as unknown as { __eaglercraftGame: { enterWorld: () => Promise<void> | void } }
      ).__eaglercraftGame;
      return Promise.resolve(bridge.enterWorld());
    });

    // The bridge must flip inWorld once the level is loaded and the HUD is up.
    const deadline = Date.now() + 30_000;
    let worldState = await readGameTelemetry(page);
    while (!worldState?.inWorld && Date.now() < deadline) {
      await page.waitForTimeout(250);
      worldState = await readGameTelemetry(page);
    }
    expect(
      worldState?.inWorld,
      `the client must flip inWorld=true after enterWorld() (last telemetry: ${JSON.stringify(worldState)})`,
    ).toBe(true);
    expect(
      pageErrors,
      `entering a world must not throw uncaught page errors:\n${pageErrors.join('\n')}`,
    ).toEqual([]);

    await attachMilestoneScreenshot(page, testInfo, '06-in-world-hud');
    await attachSessionSummary(testInfo, {
      gated: false,
      path: 'bridge-enterWorld',
      telemetry: worldState,
      uncaughtPageErrors: pageErrors,
    });
    await context.close();
  });

  /**
   * Stage 6 through the real menu: clicks the actual Singleplayer button
   * with trusted mouse events (no bridge shortcuts) and requires the client
   * to land in the world with the HUD up.
   *
   * This is the human journey exactly as a player runs it: move the mouse
   * over the button, press left button, the button's hit-testing fires the
   * world-load path. The telemetry bridge is used only to LOCATE the button
   * (its bounds in GUI pixels) and to OBSERVE the result (inWorld) — never
   * to trigger the transition.
   */
  test('clicks the real Singleplayer button and reaches the HUD (capability-gated)', async ({ browser }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 60_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));

    // Boot to the running title screen — same preconditions as the bridge test
    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(
      await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main',
    ).toBe(true);
    expect(
      await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen before the Singleplayer click',
    ).toBe(true);
    await waitForFpsReading(page, 15_000);

    // ── The capability gate ──
    const telemetry = await readGameTelemetry(page);
    await writeTestArtifact(testInfo, 'game-telemetry.json', JSON.stringify(telemetry, null, 2));
    await attachMilestoneScreenshot(page, testInfo, '05-singleplayer-menu');

    if (!telemetry) {
      await attachSessionSummary(testInfo, {
        gated: true,
        reason: 'telemetry-bridge-missing',
        uncaughtPageErrors: pageErrors,
      });
      test.skip(
        true,
        'the deployed bundle predates the __eaglercraftGame telemetry bridge — rebuild the client to activate the Singleplayer click stage',
      );
      return;
    }
    if (!telemetry.canEnterWorld) {
      await attachSessionSummary(testInfo, {
        gated: true,
        reason: 'world-entry-not-implemented',
        telemetry,
        uncaughtPageErrors: pageErrors,
      });
      test.skip(
        true,
        `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)}) — this test activates automatically once canEnterWorld flips to true`,
      );
      return;
    }

    // ── Contract: the title screen must carry the real Singleplayer button ──
    const coords = await getButtonCssCoords(page, 'singleplayer');
    expect(
      coords,
      `canEnterWorld=true requires a "singleplayer" button in the bridge's buttons array (got: ${JSON.stringify(telemetry.buttons)})`,
    ).not.toBeNull();
    expect(
      coords!.button.w,
      'the Singleplayer button must have a non-empty hit box',
    ).toBeGreaterThan(0);

    // ── The human action: press left mouse button on the button ──
    await page.mouse.move(coords!.cssX, coords!.cssY);
    await page.waitForTimeout(100); // let a hover frame render
    await page.mouse.click(coords!.cssX, coords!.cssY);

    // ── Vanilla 26.1.2 opens the Select World screen, not the world ──
    const selectDeadline = Date.now() + 15_000;
    let selectState = await readGameTelemetry(page);
    while (
      Date.now() < selectDeadline &&
      !(selectState?.screen ?? '').includes('SelectWorldScreen')
    ) {
      await page.waitForTimeout(250);
      selectState = await readGameTelemetry(page);
    }
    expect(
      (selectState?.screen ?? '').includes('SelectWorldScreen'),
      `clicking Singleplayer must open the Select World screen (last telemetry: ${JSON.stringify(selectState)})`,
    ).toBe(true);
    await attachMilestoneScreenshot(page, testInfo, '06-select-world-screen');

    // ── Select the world row (vanilla: click to select, Play enables) ──
    const entry = await getButtonCssCoords(page, 'newworld');
    expect(
      entry,
      `the Select World screen must expose the "newworld" row in the bridge's buttons array (got: ${JSON.stringify(selectState?.buttons)})`,
    ).not.toBeNull();
    await page.mouse.click(entry!.cssX, entry!.cssY);
    await page.waitForTimeout(150);

    // ── Play Selected World (enabled by the selection) ──
    const play = await getButtonCssCoords(page, 'playselectedworld');
    expect(
      play,
      'selecting a world must enable the "playselectedworld" button',
    ).not.toBeNull();
    expect(
      play!.button.active,
      'the Play Selected World button must be active once a world is selected',
    ).toBe(true);
    await page.mouse.click(play!.cssX, play!.cssY);

    // ── The world must load and the HUD must take over ──
    const deadline = Date.now() + 30_000;
    let worldState = await readGameTelemetry(page);
    while (!worldState?.inWorld && Date.now() < deadline) {
      await page.waitForTimeout(250);
      worldState = await readGameTelemetry(page);
    }
    expect(
      worldState?.inWorld,
      `the client must flip inWorld=true after clicking Play Selected World (last telemetry: ${JSON.stringify(worldState)})`,
    ).toBe(true);
    expect(
      worldState?.screen,
      'the title screen must close once the world is loaded',
    ).toBe('in-game');
    expect(
      pageErrors,
      `entering a world via the menu must not throw uncaught page errors:\n${pageErrors.join('\n')}`,
    ).toEqual([]);

    await attachMilestoneScreenshot(page, testInfo, '07-in-world-hud-click');
    await attachSessionSummary(testInfo, {
      gated: false,
      path: 'singleplayer-click',
      clickedButton: coords!.button,
      worldEntry: entry!.button,
      playButton: play!.button,
      guiScale: coords!.guiScale,
      telemetry: worldState,
      uncaughtPageErrors: pageErrors,
    });
    await context.close();
  });

  /**
   * The Create New World form: Singleplayer -> Select World -> Create New
   * World -> the vanilla 26.1.2 tabbed screen (Game / World / More), then
   * typing a world name into the EditBox and pressing Create joins the
   * world through the same doWorldLoad path as Play.
   *
   * Also asserts the menu-background blur contract: the title screen
   * (empty vanilla extractBackground) reports panoramaBlur=0 while the
   * Select World screen blurs with the default option value 5.
   */
  test('opens the Create World form, types a name, and creates the world (capability-gated)', async ({ browser }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 90_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));

    // Boot to the title screen
    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(
      await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main',
    ).toBe(true);
    expect(
      await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen',
    ).toBe(true);
    await waitForFpsReading(page, 15_000);

    const telemetry = await readGameTelemetry(page);
    if (!telemetry?.canEnterWorld) {
      await attachMilestoneScreenshot(page, testInfo, '05-create-world-gate');
      test.skip(
        true,
        `the client cannot enter a world yet (telemetry: ${JSON.stringify(telemetry)})`,
      );
      return;
    }

    // ── Blur contract at the title screen: sharp panorama (radius 0) ──
    const titleBlur = await page.evaluate(() => {
      const game = (
        self as unknown as { __eaglercraftGame?: { panoramaBlur?: number } }
      ).__eaglercraftGame;
      return game?.panoramaBlur ?? null;
    });
    expect(
      titleBlur,
      'the title screen must report panoramaBlur=0 (vanilla TitleScreen has an empty extractBackground - no menu blur)',
    ).toBe(0);

    // ── Singleplayer -> Select World: now the menu blur must be on ──
    const singleplayer = await getButtonCssCoords(page, 'singleplayer');
    expect(singleplayer, 'the title screen must expose the singleplayer button').not.toBeNull();
    await page.mouse.move(singleplayer!.cssX, singleplayer!.cssY);
    await page.waitForTimeout(100);
    await page.mouse.click(singleplayer!.cssX, singleplayer!.cssY);
    const selectDeadline = Date.now() + 15_000;
    let selectState = await readGameTelemetry(page);
    while (
      Date.now() < selectDeadline &&
      !(selectState?.screen ?? '').includes('SelectWorldScreen')
    ) {
      await page.waitForTimeout(250);
      selectState = await readGameTelemetry(page);
    }
    expect(
      (selectState?.screen ?? '').includes('SelectWorldScreen'),
      `clicking Singleplayer must open the Select World screen (last telemetry: ${JSON.stringify(selectState)})`,
    ).toBe(true);
    expect(
      selectState?.panoramaBlur,
      'the Select World screen must blur its panorama with the vanilla default menuBackgroundBlurriness=5',
    ).toBe(5);
    await attachMilestoneScreenshot(page, testInfo, '06-select-world-blurred');

    // ── Create New World -> the tabbed CreateWorldScreen ──
    const create = await getButtonCssCoords(page, 'createnewworld');
    expect(
      create,
      `the Select World screen must expose the "createnewworld" button (got: ${JSON.stringify(selectState?.buttons)})`,
    ).not.toBeNull();
    expect(create!.button.active, 'the Create New World button must be active').toBe(true);
    await page.mouse.move(create!.cssX, create!.cssY);
    await page.waitForTimeout(100);
    await page.mouse.click(create!.cssX, create!.cssY);

    const formDeadline = Date.now() + 15_000;
    let formState = await readGameTelemetry(page);
    while (
      Date.now() < formDeadline &&
      !(formState?.screen ?? '').includes('CreateWorldScreen')
    ) {
      await page.waitForTimeout(250);
      formState = await readGameTelemetry(page);
    }
    expect(
      (formState?.screen ?? '').includes('CreateWorldScreen'),
      `clicking Create New World must open the CreateWorldScreen (last telemetry: ${JSON.stringify(formState)})`,
    ).toBe(true);
    expect(
      formState?.panoramaBlur,
      'the Create World screen must also blur its panorama (radius 5)',
    ).toBe(5);

    // ── The three vanilla tabs must be present ──
    for (const tabId of ['tab_game', 'tab_world', 'tab_more']) {
      const tab = (formState?.buttons ?? []).find((button) => button.id === tabId);
      expect(
        tab,
        `the Create World screen must carry the "${tabId}" tab button (buttons: ${JSON.stringify(formState?.buttons)})`,
      ).toBeTruthy();
    }
    await attachMilestoneScreenshot(page, testInfo, '07-create-world-form');

    // ── Type a world name into the focused name EditBox ──
    await page.keyboard.type('E2E World', { delay: 40 });

    // ── Create (the Game tab is open, its Create button is in the footer) ──
    const createConfirm = await getButtonCssCoords(page, 'createnewworld');
    expect(
      createConfirm,
      'the Create World screen must expose the footer "createnewworld" button',
    ).not.toBeNull();
    await page.mouse.move(createConfirm!.cssX, createConfirm!.cssY);
    await page.waitForTimeout(100);
    await page.mouse.click(createConfirm!.cssX, createConfirm!.cssY);

    // ── The world must load and the HUD must take over ──
    const deadline = Date.now() + 30_000;
    let worldState = await readGameTelemetry(page);
    while (!worldState?.inWorld && Date.now() < deadline) {
      await page.waitForTimeout(250);
      worldState = await readGameTelemetry(page);
    }
    expect(
      worldState?.inWorld,
      `the client must flip inWorld=true after Create (last telemetry: ${JSON.stringify(worldState)})`,
    ).toBe(true);
    expect(
      pageErrors,
      `creating a world must not throw uncaught page errors:\n${pageErrors.join('\n')}`,
    ).toEqual([]);

    await attachMilestoneScreenshot(page, testInfo, '08-created-world-hud');
    await attachSessionSummary(testInfo, {
      gated: false,
      path: 'create-world-form',
      titleBlur,
      selectBlur: selectState?.panoramaBlur,
      formBlur: formState?.panoramaBlur,
      typedName: 'E2E World',
      telemetry: worldState,
      uncaughtPageErrors: pageErrors,
    });
    await context.close();
  });
});
