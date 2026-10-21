import { expect, test } from '@playwright/test';
import {
  formatErrors,
  installErrorCollector,
  isGameRuntimeError,
  readCapturedErrors,
  waitForClientBootstrap,
} from './support';

test.describe('client shell', () => {
  test.beforeEach(async ({ page }) => {
    await installErrorCollector(page);
  });

  test('loads the EaglerCraftX shell', async ({ page }) => {
    const response = await page.goto('/index.html');
    expect(response, 'index.html should be reachable').not.toBeNull();
    expect(response!.status()).toBe(200);

    await expect(page).toHaveTitle(/EaglerCraftX/i);
    await expect(page.locator('#game_frame')).toBeAttached();
    await expect(page.locator('#loading-screen')).toBeAttached();
    await expect(page.locator('#progress-bar')).toBeAttached();
    await expect(page.locator('#title-overlay')).toBeAttached();
    await expect(page.locator('#error-overlay')).toBeAttached();
  });

  test('loads the real TeaVM bundle instead of a stub', async ({ page }) => {
    await page.goto('/index.html');
    await waitForClientBootstrap(page);

    const bundle = await page.evaluate(async () => {
      const response = await fetch('classes.js');
      const text = await response.text();
      return {
        status: response.status,
        length: text.length,
        head: text.slice(0, 400),
        tail: text.slice(-400),
      };
    });

    expect(bundle.status).toBe(200);
    expect(bundle.length, 'classes.js should be a full TeaVM build, not a stub').toBeGreaterThan(
      50_000,
    );
    expect(bundle.head, 'classes.js should carry the TeaVM export wrapper').toContain('$rt_exports');
    expect(bundle.tail, 'classes.js should export the TeaVM entry point').toContain(
      '$rt_exports.main',
    );
  });

  test('exports the TeaVM entry point on self.main', async ({ page }) => {
    await page.goto('/index.html');
    await waitForClientBootstrap(page);

    const mainType = await page.evaluate(() => typeof (self as unknown as { main?: unknown }).main);
    expect(mainType).toBe('function');
  });

  test('does not report a failed bundle download', async ({ page }) => {
    await page.goto('/index.html');
    await waitForClientBootstrap(page);
    await page.waitForTimeout(1_500);

    const overlayText = await page.locator('#error-msg').textContent();
    expect(overlayText ?? '', 'the client must not hit the "failed to load classes.js" path').not.toContain(
      'Failed to load classes.js',
    );
  });

  test('boots without uncaught shell errors', async ({ page }) => {
    await page.goto('/index.html');
    await waitForClientBootstrap(page);
    await page.waitForTimeout(1_500);

    const errors = await readCapturedErrors(page);
    const shellErrors = errors.filter((error) => !isGameRuntimeError(error));
    expect(shellErrors, `unexpected shell errors:\n${formatErrors(shellErrors)}`).toEqual([]);
  });

  test('captures a screenshot of the running client', async ({ page }, testInfo) => {
    await page.goto('/index.html');
    await waitForClientBootstrap(page);
    await page.waitForTimeout(5_000);

    await testInfo.attach('eaglercraft-client', {
      body: await page.screenshot({ fullPage: false }),
      contentType: 'image/png',
    });
  });
});
