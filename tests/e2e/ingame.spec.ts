import type { ConsoleRecorder, ConsoleEntry } from './support';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  attachSessionSummary,
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
      telemetry: worldState,
      uncaughtPageErrors: pageErrors,
    });
    await context.close();
  });
});
