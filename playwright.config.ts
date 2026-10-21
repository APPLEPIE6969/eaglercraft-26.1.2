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

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: './test-results',
  // The TeaVM client is CPU heavy; a single worker keeps the run deterministic
  // and avoids several Chromium instances fighting over the same CPU budget.
  fullyParallel: false,
  workers: 1,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  timeout: 90_000,
  expect: {
    timeout: 20_000,
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
    viewport: { width: 1280, height: 800 },
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
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
