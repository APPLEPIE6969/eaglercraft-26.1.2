import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  formatErrors,
  isGameRuntimeError,
  readMissingShellElements,
  waitForClientBootstrap,
} from './support';

const BOOT_TIMEOUT = 180_000;

interface EaglerCraftXOptions {
  container: string;
  assetsURI: string;
  localesURI: string;
  servers: unknown[];
  relays: unknown[];
}

test.describe('client shell', () => {
  // The client page is shared by the whole worker, so the expensive TeaVM
  // bootstrap happens once instead of once per test.
  test.beforeAll(async ({ clientPage }) => {
    const booted = await waitForClientBootstrap(clientPage, BOOT_TIMEOUT);
    expect(booted, 'the TeaVM bundle must export self.main').toBe(true);
  });

  test('loads the EaglerCraftX shell', async ({ clientPage }) => {
    const title = await clientPage.title();
    expect(title, 'the document title must identify the client').toMatch(/EaglerCraftX/i);

    const missing = await readMissingShellElements(clientPage);
    expect(missing, 'the shell must provide its required elements').toEqual([]);
  });

  test('loads the real TeaVM bundle instead of a stub', async ({ request }) => {
    const response = await request.get('/classes.js');
    expect(response.status(), 'classes.js must be served').toBe(200);

    const body = await response.text();
    expect(body.length, 'classes.js should be a full TeaVM build, not a stub').toBeGreaterThan(
      50_000,
    );
    expect(body, 'classes.js should carry the TeaVM export wrapper').toContain('$rt_exports');
    expect(body, 'classes.js should export the TeaVM entry point').toContain('$rt_exports.main');
  });

  test('exports the TeaVM entry point on self.main', async ({ clientPage }) => {
    const mainType = await clientPage.evaluate(
      () => typeof (self as unknown as { main?: unknown }).main,
    );
    expect(mainType).toBe('function');
  });

  test('exposes a well formed client configuration', async ({ clientPage }) => {
    const options = await clientPage.evaluate(
      () => (window as unknown as { eaglercraftXOpts?: EaglerCraftXOptions }).eaglercraftXOpts,
    );

    expect(options, 'window.eaglercraftXOpts must be defined').toBeTruthy();
    expect(options!.container).toBe('game_frame');
    expect(typeof options!.assetsURI).toBe('string');
    expect(typeof options!.localesURI).toBe('string');
    expect(Array.isArray(options!.servers)).toBe(true);
    expect(Array.isArray(options!.relays)).toBe(true);

    const containers = await clientPage.evaluate(
      (id) => document.querySelectorAll(`#${id}`).length,
      options!.container,
    );
    expect(containers, 'the configured container must exist exactly once').toBe(1);
  });

  test('does not report a failed bundle download', async ({ clientPage }) => {
    const overlayText = await clientPage
      .locator('#error-msg')
      .textContent()
      .catch(() => null);
    expect(
      overlayText ?? '',
      'the client must not hit the "failed to load classes.js" path',
    ).not.toContain('Failed to load classes.js');
  });

  test('boots without uncaught shell errors', async ({ clientErrors }) => {
    const errors = await clientErrors();
    const shellErrors = errors.filter((error) => !isGameRuntimeError(error));
    expect(shellErrors, `unexpected shell errors:\n${formatErrors(shellErrors)}`).toEqual([]);
  });

  test('captures a screenshot of the running client', async ({ clientPage }, testInfo) => {
    await attachMilestoneScreenshot(clientPage, testInfo, 'shell-client');
  });
});
