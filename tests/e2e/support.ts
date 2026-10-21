import type { Page } from '@playwright/test';

export interface CapturedError {
  message: string;
  source: string;
  line: number;
  column: number;
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
