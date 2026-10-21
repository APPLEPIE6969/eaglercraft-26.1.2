import { defineConfig } from '@playwright/test';

type ProcLike = { env?: Record<string, string | undefined> };

function readEnv(name: string): string | undefined {
  const proc = (globalThis as { process?: ProcLike }).process;
  const value = proc?.env?.[name];
  return value === undefined || value === '' ? undefined : value;
}

const isCI = readEnv('CI') !== undefined;
const port = Number(readEnv('E2E_PORT') ?? '4173');
const host = readEnv('E2E_HOST') ?? '127.0.0.1';
const baseURL = `http://${host}:${port}`;
const publicDir = readEnv('E2E_PUBLIC_DIR') ?? 'public';
const browserChannel = readEnv('E2E_BROWSER_CHANNEL');

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results',
  // The TeaVM client is CPU heavy; a single worker keeps the run deterministic
  // and avoids several Chromium instances fighting over the same CPU budget.
  fullyParallel: false,
  workers: 1,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  // The client bootstrap blocks the browser main thread for about a minute, so
  // a single test can legitimately need several minutes.
  timeout: 300_000,
  expect: {
    timeout: 60_000,
  },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  use: {
    baseURL,
    headless: true,
    browserName: 'chromium',
    ...(browserChannel ? { channel: browserChannel } : {}),
    viewport: { width: 1280, height: 800 },
    actionTimeout: 60_000,
    navigationTimeout: 180_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    launchOptions: {
      args: [
        '--no-sandbox',
        '--disable-dev-shm-usage',
        '--ignore-gpu-blocklist',
        // Headless Chromium has no GPU, so WebGL2 has to go through SwiftShader.
        // Newer Chromium builds refuse SwiftShader for WebGL unless the
        // "unsafe" switch is present.
        '--enable-unsafe-swiftshader',
        '--use-gl=angle',
        '--use-angle=swiftshader',
        // Keep the browser from doing anything in the background that would
        // compete with the TeaVM bootstrap for CPU.
        '--disable-background-networking',
        '--disable-component-update',
        '--disable-sync',
        '--no-first-run',
        '--no-default-browser-check',
      ],
    },
  },
  webServer: {
    command: 'node tests/e2e/server.mjs',
    url: `${baseURL}/index.html`,
    reuseExistingServer: !isCI,
    timeout: 30_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      E2E_PUBLIC_DIR: publicDir,
      E2E_PORT: String(port),
      E2E_HOST: host,
    },
  },
});
