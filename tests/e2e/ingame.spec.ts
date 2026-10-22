import type { ConsoleRecorder, ConsoleEntry } from './support';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  installConsoleRecorder,
  parseFps,
  readGameSessionState,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
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
    await testInfo.attach('session-summary.json', {
      body: JSON.stringify(
        {
          titleScreenMs,
          milestones: milestoneEvents,
          assetsEpk: epk,
          fps: { firstSample: first.fps, secondSample: fpsAfter },
          uncaughtPageErrors: pageErrors,
          consoleMessageCount: entries.length,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    // ── The session must not have thrown anything uncaught ──
    expect(
      pageErrors,
      `the session must run without uncaught page errors:\n${pageErrors.join('\n')}`,
    ).toEqual([]);

    await context.close();
  });
});
