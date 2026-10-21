import { test as base, expect, type Page } from '@playwright/test';
import { installErrorCollector, readCapturedErrors, type CapturedError } from './support';

/**
 * The TeaVM bootstrap blocks the browser main thread for roughly a minute
 * while it evaluates the bundle. Every navigation therefore costs about that
 * much, so the suite boots the client once per worker and shares the page
 * between the tests instead of reloading it for every assertion.
 */
export interface ClientFixtures {
  /** The already booted EaglerCraftX client. Shared by every test in a worker. */
  clientPage: Page;
  /** Number of errors the client had raised when the current test started. */
  errorBaseline: number;
  /** Errors the client raised since the current test started. */
  clientErrors: () => Promise<CapturedError[]>;
}

async function currentErrorCount(page: Page): Promise<number> {
  return page
    .evaluate(() => {
      const w = window as unknown as { __e2eErrors?: unknown[] };
      return Array.isArray(w.__e2eErrors) ? w.__e2eErrors.length : 0;
    })
    .catch(() => 0);
}

export const test = base.extend<ClientFixtures>({
  clientPage: [
    async ({ browser }, use) => {
      const context = await browser.newContext();
      const page = await context.newPage();
      await installErrorCollector(page);
      // "commit" resolves as soon as the response is accepted. Waiting for the
      // load event would mean waiting for the TeaVM bootstrap to finish, and
      // that blocks the main thread the whole time.
      await page.goto('/index.html', { waitUntil: 'commit' });
      await use(page);
      await context.close();
    },
    { scope: 'worker' },
  ],

  errorBaseline: async ({ clientPage }, use) => {
    await use(await currentErrorCount(clientPage));
  },

  clientErrors: async ({ clientPage, errorBaseline }, use) => {
    await use(async () => (await readCapturedErrors(clientPage)).slice(errorBaseline));
  },
});

export { expect };
