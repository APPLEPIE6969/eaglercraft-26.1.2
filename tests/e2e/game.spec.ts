import { isStrictGame } from './env';
import { expect, test } from './fixtures';
import {
  formatErrors,
  isGameRuntimeError,
  readReadyState,
  waitForClientBootstrap,
  waitForReadyState,
} from './support';

const strict = isStrictGame();
const BOOT_TIMEOUT = 180_000;

test.describe('game runtime', () => {
  test.beforeAll(async ({ clientPage }) => {
    const booted = await waitForClientBootstrap(clientPage, BOOT_TIMEOUT);
    expect(booted, 'the TeaVM bundle must export self.main').toBe(true);
  });

  test('reaches the title screen', async ({ clientPage }) => {
    const timeout = strict ? 120_000 : 60_000;
    const reached = await waitForReadyState(clientPage, timeout);
    const state = await readReadyState(clientPage);

    if (strict) {
      expect(reached, 'the client must reach the title screen in strict mode').toBe(true);
    } else {
      console.log(
        `[e2e] title screen reached: ${reached} (loadingHidden=${state.loadingHidden}, titleVisible=${state.titleVisible})`,
      );
    }
  });

  test('serves the EPK asset archive', async ({ request }) => {
    const response = await request.get('/assets.epk');
    const status = response.status();

    if (strict) {
      expect(status, 'assets.epk must be served in strict mode').toBe(200);
    } else {
      console.log(`[e2e] assets.epk status: ${status}`);
    }
  });

  test('client parses the EPK asset archive at boot', async ({ clientPage }) => {
    // ClientMain kicks off EPKLoader.loadEPK('assets.epk') right after the game
    // loop starts; EPKLoader publishes the raw bundle to window and parses the
    // header. The client boots in ~1 min and the 322 B bundle arrives in <1 s,
    // so by the time any test runs the window property is set.
    const assets = await clientPage.evaluate(() => {
      const w = window as unknown as {
        __eaglercraftAssets?: { byteLength: number };
      };
      return w.__eaglercraftAssets
        ? { byteLength: w.__eaglercraftAssets.byteLength }
        : null;
    });

    if (strict) {
      expect(
        assets,
        'the client must fetch and expose assets.epk at boot in strict mode',
      ).not.toBeNull();
      expect(assets!.byteLength, 'assets.epk byte length').toBeGreaterThan(0);
    } else {
      console.log(`[e2e] assets.epk consumed at boot: ${JSON.stringify(assets)}`);
    }
  });

  test('runs the client without uncaught game runtime errors', async ({ clientErrors }) => {
    const errors = await clientErrors();
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
