import { expect, test } from '@playwright/test';

interface EaglerCraftXOptions {
  container: string;
  assetsURI: string;
  localesURI: string;
  servers: unknown[];
  relays: unknown[];
}

test.describe('static asset contract', () => {
  test('serves the site metadata files', async ({ request }) => {
    for (const path of ['/index.html', '/robots.txt', '/logo.svg']) {
      const response = await request.get(path);
      expect(response.status(), `${path} should be served`).toBe(200);
    }
  });

  test('uses the expected content types', async ({ request }) => {
    const script = await request.get('/classes.js');
    expect(script.headers()['content-type']).toContain('javascript');

    const logo = await request.get('/logo.svg');
    expect(logo.headers()['content-type']).toContain('image/svg+xml');
  });

  test('returns 404 for unknown paths', async ({ request }) => {
    const response = await request.get('/definitely-not-here-9d2f4b.txt');
    expect(response.status()).toBe(404);
  });

  test('exposes a well formed client configuration', async ({ page }) => {
    await page.goto('/index.html');

    const options = await page.evaluate(
      () => (window as unknown as { eaglercraftXOpts?: EaglerCraftXOptions }).eaglercraftXOpts,
    );

    expect(options, 'window.eaglercraftXOpts must be defined').toBeTruthy();
    expect(options!.container).toBe('game_frame');
    expect(typeof options!.assetsURI).toBe('string');
    expect(typeof options!.localesURI).toBe('string');
    expect(Array.isArray(options!.servers)).toBe(true);
    expect(Array.isArray(options!.relays)).toBe(true);

    await expect(page.locator(`#${options!.container}`)).toHaveCount(1);
  });
});
