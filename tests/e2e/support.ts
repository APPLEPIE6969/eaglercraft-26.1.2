import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { Page, TestInfo } from '@playwright/test';

export interface CapturedError {
  message: string;
  source: string;
  line: number;
  column: number;
}

// ========== Console recorder ==========

export interface ConsoleEntry {
  /** Epoch millis at which the message was emitted. */
  at: number;
  /** Console message severity: log, info, warning, error, ... */
  type: string;
  text: string;
}

export interface ConsoleRecorder {
  /** Snapshot of every console message captured so far. */
  entries(): ConsoleEntry[];
  /** Every captured message rendered as "[type] text" lines. */
  text(): string;
}

/**
 * Records every console message the page emits. The recorder must be
 * installed before the page navigates, because the client logs its boot
 * milestones (platform init, Minecraft construction, EPK parsing) while
 * the bundle evaluates — long before any test could ask for them.
 */
export function installConsoleRecorder(page: Page): ConsoleRecorder {
  const entries: ConsoleEntry[] = [];
  page.on('console', (message) => {
    entries.push({ at: Date.now(), type: message.type(), text: message.text() });
  });
  return {
    entries: () => [...entries],
    text: () => entries.map((entry) => `[${entry.type}] ${entry.text}`).join('\n'),
  };
}

/** Any uncaught error raised from the TeaVM bundle is a game runtime error. */
export const GAME_BUNDLE_MARKER = 'classes.js';

/**
 * Captures uncaught errors and unhandled promise rejections before any page
 * script runs. Resource load failures (a missing favicon, for example) do not
 * bubble, so they are deliberately not collected here.
 */
export async function installErrorCollector(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const w = window as unknown as { __e2eErrors?: unknown[] };
    w.__e2eErrors = [];
    const push = (entry: unknown) => {
      if (Array.isArray(w.__e2eErrors)) {
        w.__e2eErrors.push(entry);
      }
    };
    window.addEventListener('error', (event) => {
      const err = event as ErrorEvent;
      push({
        message: err.message || String(err.error ?? ''),
        source: err.filename || '',
        line: err.lineno || 0,
        column: err.colno || 0,
      });
    });
    window.addEventListener('unhandledrejection', (event) => {
      const reason = (event as PromiseRejectionEvent).reason;
      push({
        message: 'unhandledrejection: ' + (reason instanceof Error ? reason.message : String(reason)),
        source: '',
        line: 0,
        column: 0,
      });
    });
  });
}

export async function readCapturedErrors(page: Page): Promise<CapturedError[]> {
  const errors = await page.evaluate(() => {
    const w = window as unknown as { __e2eErrors?: CapturedError[] };
    return w.__e2eErrors ?? [];
  });
  return Array.isArray(errors) ? errors : [];
}

/**
 * Errors that originate from the TeaVM bundle are game runtime errors. The
 * MC 26.1.2 port is still in progress, so those are reported but only fail
 * the run in strict mode.
 */
export function isGameRuntimeError(error: CapturedError): boolean {
  return error.source.includes(GAME_BUNDLE_MARKER);
}

export function formatErrors(errors: CapturedError[]): string {
  return JSON.stringify(errors, null, 2);
}

/**
 * Waits until the TeaVM bundle has been evaluated and exported its entry
 * point.
 *
 * The check is driven from Node rather than with page.waitForFunction on
 * purpose. The bootstrap blocks the browser main thread, so a page side
 * predicate cannot even be evaluated until the block clears, and a page side
 * timeout would expire while it is still blocked. Node side polling keeps the
 * deadline honest.
 */
export async function waitForClientBootstrap(page: Page, timeoutMs = 180_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ready = await page
      .evaluate(() => typeof (self as unknown as { main?: unknown }).main === 'function')
      .catch(() => false);
    if (ready) {
      return true;
    }
    await page.waitForTimeout(1_000);
  }
  return false;
}

export interface ReadyState {
  loadingHidden: boolean;
  titleVisible: boolean;
  ready: boolean;
}

export async function readReadyState(page: Page): Promise<ReadyState> {
  return page.evaluate(() => {
    const loading = document.getElementById('loading-screen');
    const title = document.getElementById('title-overlay');
    const loadingHidden =
      Boolean(loading) &&
      (loading.classList.contains('hidden') || getComputedStyle(loading).display === 'none');
    const titleVisible = Boolean(title) && getComputedStyle(title).display !== 'none';
    return { loadingHidden, titleVisible, ready: loadingHidden && titleVisible };
  });
}

/** Polls the ready state instead of relying on a single waitForFunction timeout. */
export async function waitForReadyState(page: Page, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const state = await readReadyState(page);
    if (state.ready) {
      return true;
    }
    await page.waitForTimeout(500);
  }
  return false;
}

/** Ids that the shell must provide for the client to be able to boot. */
export const SHELL_ELEMENT_IDS = [
  'game_frame',
  'loading-screen',
  'progress-bar',
  'title-overlay',
  'error-overlay',
] as const;

/** Returns the shell element ids that are missing from the document. */
export async function readMissingShellElements(page: Page): Promise<string[]> {
  return page.evaluate((ids) => ids.filter((id) => !document.getElementById(id)), [
    ...SHELL_ELEMENT_IDS,
  ]);
}

// ========== Game session state ==========

export interface GameSessionState {
  /** The TeaVM bundle has been evaluated and exported its entry point. */
  mainExported: boolean;
  /** The loading screen is still shown (bootstrap in progress). */
  loadingVisible: boolean;
  /** The title overlay is shown (the client is interactive). */
  titleVisible: boolean;
  /** The shell error overlay (window.onerror path) is showing. */
  crashOverlayVisible: boolean;
  /** ClientMain.showCrashScreen replaced the page body with a crash view. */
  crashScreenShown: boolean;
  /** Raw text of the FPS counter, e.g. "60 FPS" (null before the title screen). */
  fpsText: string | null;
}

/**
 * Reads the full observable state of a game session from the DOM. Unlike
 * readReadyState this also covers the crash paths and the FPS counter, so a
 * test can tell "booting", "in game" and "crashed" apart.
 */
export async function readGameSessionState(page: Page): Promise<GameSessionState> {
  return page.evaluate(() => {
    const loading = document.getElementById('loading-screen');
    const title = document.getElementById('title-overlay');
    const errorOverlay = document.getElementById('error-overlay');
    const fps = document.getElementById('title-fps');
    const loadingVisible =
      loading !== null &&
      !loading.classList.contains('hidden') &&
      getComputedStyle(loading).display !== 'none';
    const titleVisible = title !== null && getComputedStyle(title).display !== 'none';
    const crashOverlayVisible =
      errorOverlay !== null && errorOverlay.classList.contains('visible');
    const crashScreenShown = Boolean(document.querySelector('h2'));
    return {
      mainExported: typeof (self as unknown as { main?: unknown }).main === 'function',
      loadingVisible,
      titleVisible,
      crashOverlayVisible,
      crashScreenShown,
      fpsText: fps && fps.textContent ? fps.textContent : null,
    };
  });
}

/**
 * Extracts the frames-per-second number from the FPS counter text
 * ("60 FPS" -> 60). Returns 0 when the counter has no reading yet, so the
 * caller can fail with a plain toBeGreaterThan(0) assertion.
 */
export function parseFps(fpsText: string | null): number {
  if (!fpsText) {
    return 0;
  }
  const match = fpsText.match(/(\d+(?:\.\d+)?)\s*FPS/i);
  return match ? Number(match[1]) : 0;
}

/**
 * Waits until the FPS counter publishes its first reading. The counter is
 * driven by requestAnimationFrame and only starts reporting roughly a second
 * after the title screen appears, so it cannot be asserted at the moment the
 * overlay becomes visible. Returns the latest session state together with
 * the parsed reading.
 */
export async function waitForFpsReading(
  page: Page,
  timeoutMs = 15_000,
): Promise<{ state: GameSessionState; fps: number }> {
  const deadline = Date.now() + timeoutMs;
  let state = await readGameSessionState(page);
  let fps = parseFps(state.fpsText);
  while (fps <= 0 && Date.now() < deadline) {
    await page.waitForTimeout(500);
    state = await readGameSessionState(page);
    fps = parseFps(state.fpsText);
  }
  return { state, fps };
}

// ========== Milestone screenshots ==========

/** Directory that receives the milestone screenshots (relative to the repo root). */
export const SCREENSHOTS_DIR = 'screenshots';

/**
 * Takes a screenshot, saves it to the screenshots/ directory and attaches it
 * to the Playwright report under the given milestone name. Playwright
 * captures through the browser compositor, so this also works while the
 * client bootstrap blocks the page main thread.
 */
export async function attachMilestoneScreenshot(
  page: Page,
  testInfo: TestInfo,
  name: string,
): Promise<Buffer> {
  const shot = await page.screenshot({ fullPage: false });
  const dir = resolve(process.cwd(), SCREENSHOTS_DIR);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `${name}.png`), shot);
  await testInfo.attach(name, { body: shot, contentType: 'image/png' });
  return shot;
}

// ========== Game telemetry bridge ==========

/** A button on the current screen, with its bounds in GUI-scaled pixels. */
export interface GameTelemetryButton {
  id: string;
  label: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface GameTelemetry {
  /** The client reports a working Singleplayer → world → HUD path. */
  canEnterWorld: boolean;
  /** EaglerCraft STATE_* constant: 2 = running, 3 = paused, 5 = crashed. */
  gameState: number;
  /** Current screen class name, "in-game" when no screen, "none" without an engine. */
  screen: string;
  /** True once a level is loaded (the HUD stage). */
  inWorld: boolean;
  /** Game logic ticks processed since boot (20 TPS). */
  ticks: number;
  /** The GUI scale factor menus and the HUD are laid out with. */
  guiScale: number;
  /** Buttons on the current screen with bounds in GUI pixels. */
  buttons: GameTelemetryButton[];
}

/**
 * Reads the `window.__eaglercraftGame` telemetry object that the TeaVM
 * adapter pushes at the end of initialization, once per game logic tick and
 * immediately on crash. Returns null when the bundle predates the bridge
 * (for example a stale `public/` that has not been rebuilt yet), which lets
 * callers distinguish "bridge missing" from "capability absent". The bridge
 * object additionally carries an `enterWorld()` function (see the world-entry
 * contract in docs/E2E.md) which is not part of this data snapshot.
 */
export async function readGameTelemetry(page: Page): Promise<GameTelemetry | null> {
  return page.evaluate(() => {
    const bridge = (
      window as unknown as { __eaglercraftGame?: Record<string, unknown> }
    ).__eaglercraftGame;
    if (!bridge || typeof bridge !== 'object') {
      return null;
    }
    const rawButtons = Array.isArray(bridge.buttons) ? bridge.buttons : [];
    return {
      canEnterWorld: bridge.canEnterWorld === true,
      gameState: typeof bridge.gameState === 'number' ? bridge.gameState : -1,
      screen: typeof bridge.screen === 'string' ? bridge.screen : '',
      inWorld: bridge.inWorld === true,
      ticks: typeof bridge.ticks === 'number' ? bridge.ticks : -1,
      guiScale: typeof bridge.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1,
      buttons: rawButtons
        .filter(
          (b): b is Record<string, unknown> =>
            Boolean(b) && typeof b === 'object' && typeof (b as { id?: unknown }).id === 'string',
        )
        .map((b) => {
          const rec = b as Record<string, unknown>;
          return {
            id: String(rec.id),
            label: typeof rec.label === 'string' ? rec.label : String(rec.id),
            x: typeof rec.x === 'number' ? rec.x : 0,
            y: typeof rec.y === 'number' ? rec.y : 0,
            w: typeof rec.w === 'number' ? rec.w : 0,
            h: typeof rec.h === 'number' ? rec.h : 0,
          };
        }),
    };
  });
}

export interface ButtonCssCoords {
  button: GameTelemetryButton;
  guiScale: number;
  /** Viewport CSS x of the button center (what page.mouse.click expects). */
  cssX: number;
  /** Viewport CSS y of the button center. */
  cssY: number;
}

/**
 * Computes the viewport click position of a button reported on the telemetry
 * bridge, so tests can drive the REAL menu path with trusted mouse events
 * instead of dispatching synthetic DOM events.
 *
 * Units: button bounds are in GUI pixels; one GUI pixel is `guiScale` canvas
 * backing pixels; the viewport sees the canvas at
 * `canvas.clientWidth / canvas.width` of its backing size (1/dpr).
 */
export async function getButtonCssCoords(
  page: Page,
  buttonId: string,
): Promise<ButtonCssCoords | null> {
  return page.evaluate((id) => {
    const bridge = (
      window as unknown as {
        __eaglercraftGame?: { guiScale?: number; buttons?: GameTelemetryButton[] };
      }
    ).__eaglercraftGame;
    const button = (bridge?.buttons ?? []).find((b) => b && b.id === id);
    if (!button) {
      return null;
    }
    // The shell hosts TWO canvases: its own empty #game_frame container
    // and the real game canvas ClientMain appends inside it. The game
    // canvas is the event target and the size reference — querying the
    // first canvas in the document would pick the container with its
    // default 300x150 backing attributes and wreck the ratio.
    const canvas =
      (document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null) ??
      document.querySelector('canvas');
    if (!canvas || canvas.width === 0) {
      return null;
    }
    const guiScale =
      typeof bridge?.guiScale === 'number' && bridge.guiScale > 0 ? bridge.guiScale : 1;
    const backingToCss = canvas.clientWidth / canvas.width;
    const rect = canvas.getBoundingClientRect();
    return {
      button,
      guiScale,
      cssX: rect.left + (button.x + button.w / 2) * guiScale * backingToCss,
      cssY: rect.top + (button.y + button.h / 2) * guiScale * backingToCss,
    };
  }, buttonId);
}

// ========== Session summary ==========

/**
 * Writes a text artifact into the test's output directory (under
 * test-results/) and attaches it to the Playwright report. The write
 * matters: folder-layout HTML reports do not persist text attachments, but
 * everything under test-results/ is uploaded as a CI artifact, so the file
 * survives there. The output directory is created on demand because
 * Playwright only materializes it lazily.
 */
export async function writeTestArtifact(
  testInfo: TestInfo,
  name: string,
  body: string,
  contentType = 'application/json',
): Promise<void> {
  mkdirSync(testInfo.outputDir, { recursive: true });
  writeFileSync(join(testInfo.outputDir, name), body);
  await testInfo.attach(name, { body, contentType });
}

/**
 * Attaches a JSON session summary to the Playwright report and writes it to
 * the test's output directory. See writeTestArtifact for why both happen.
 */
export async function attachSessionSummary(
  testInfo: TestInfo,
  summary: Record<string, unknown>,
): Promise<void> {
  await writeTestArtifact(testInfo, 'session-summary.json', JSON.stringify(summary, null, 2));
}
