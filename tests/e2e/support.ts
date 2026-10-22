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
