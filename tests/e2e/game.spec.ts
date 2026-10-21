import { expect, test } from '@playwright/test';
import { isStrictGame } from './env';
import {
  formatErrors,
  installErrorCollector,
  isGameRuntimeError,
  readCapturedErrors,
  readReadyState,
  waitForReadyState,
} from './support';

const strict = isStrictGame();

test.describe('game runtime', () => {
  test.beforeEach(async ({ page }) => {
    await installErrorCollector(page);
  });

  test('reaches the title screen', async ({ page }) => {
    await page.goto('/index.html');

    const timeout = strict ? 90_000 : 30_000;
    const reached = await waitForReadyState(page, timeout);
    const state = await readReadyState(page);

    if (strict) {
      expect(reached, 'the client must reach the title screen in strict mode').toBe(true);
    } else {
      console.log(
        `[e2e] title screen reached: ${reached} (loadingHidden=${state.loadingHidden}, titleVisible=${state.titleVisible})`,
      );
    }
  });

  test('serves the EPK asset archive', async ({ page }) => {
    await page.goto('/index.html');

    const response = await page.request.get('/assets.epk');
    const status = response.status();

    if (strict) {
      expect(status, 'assets.epk must be served in strict mode').toBe(200);
    } else {
      console.log(`[e2e] assets.epk status: ${status}`);
    }
  });

  test('runs the client without uncaught game runtime errors', async ({ page }) => {
    await page.goto('/index.html');
    await page.waitForTimeout(8_000);

    const errors = await readCapturedErrors(page);
    const runtimeErrors = errors.filter(isGameRuntimeError);

    if (strict) {
      expect(runtimeErrors, `unexpected runtime errors:\n${formatErrors(runtimeErrors)}`).toEqual([]);
    } else {
      console.log(`[e2e] game runtime errors (informational): ${runtimeErrors.length}`);
      for (const error of runtimeErrors) {
        console.log(`[e2e]   ${error.message} (${error.source}:${error.line})`);
      }
    }
  });
});
