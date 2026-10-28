import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import net from 'node:net';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  gameplayOf,
  getButtonCssCoords,
  installConsoleRecorder,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  waitForReadyState,
  waitForTelemetry,
  type Telemetry,
} from './support';

/**
 * The real gateway protocol (docs/PROTOCOL.md, MC 26.1.2 protocol 775):
 *
 *   1. protocol conformance - the standalone wire-level walk
 *      (scripts/gw_real_smoke.mjs) against the live PlaceholderServer:
 *      status ping/MOTD, login, the configuration sequence, the play
 *      phase with an 81-column chunk stream, digging a tree log
 *      (server-authoritative Block Update + Set Container Slot),
 *      placing it back, chat, and the invalid-username kick.
 *   2. the browser join - the client's own Multiplayer UI drives the
 *      same protocol: Direct Connection -> 127.0.0.1:PORT -> in-world
 *      on the server's spawn, then hard-refresh spirit: punch the
 *      tree, watch the server break the block, mine the log into the
 *      hotbar, place it back, and disconnect from the pause menu.
 *   3. a dead address lands on the vanilla DisconnectedScreen.
 *
 * The gateway runs the production shadowJar; the smoke mutates the
 * world and restores what it breaks, so the browser test can share it.
 */

const GW_PORT = 28341;
const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const PLACEHOLDER_JAR = join(
  REPO_ROOT, 'gateway', 'PlaceholderServer', 'build', 'libs', 'PlaceholderServer-1.0.0.jar');
const SMOKE_SCRIPT = join(REPO_ROOT, 'scripts', 'gw_real_smoke.mjs');

const jarsBuilt = existsSync(PLACEHOLDER_JAR);

let gateway: ChildProcess | undefined;
const gatewayLog: string[] = [];

function gatewayTail(): string {
  return gatewayLog.join('').slice(-2_000);
}

async function waitForPort(port: number, timeoutMs: number, label: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ok = await new Promise<boolean>((resolve) => {
      const socket = net.connect(port, '127.0.0.1');
      socket.once('connect', () => {
        socket.destroy();
        resolve(true);
      });
      socket.once('error', () => resolve(false));
      setTimeout(() => {
        socket.destroy();
        resolve(false);
      }, 1000);
    });
    if (ok) {
      return;
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`${label} did not open port ${port} within ${timeoutMs}ms\n${gatewayTail()}`);
}

test.describe('real gateway protocol', () => {
  test.skip(!jarsBuilt, 'gateway jars are not built (run ./gradlew :PlaceholderServer:shadowJar first)');

  test.beforeAll(async () => {
    // A previous run's server must never hold the port (workers:1 makes
    // this process-group ours)
    try {
      const { execSync } = await import('node:child_process');
      execSync('pkill -9 -f PlaceholderServer-1.0.0.jar || true', { shell: '/bin/bash' });
      await new Promise((r) => setTimeout(r, 1500));
    } catch { /* no process to kill */ }
    gateway = spawn('java', [
      '-jar', PLACEHOLDER_JAR,
      '--port', String(GW_PORT),
      '--motd', 'e2e gateway - punch a tree',
      '--verbose',
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    gateway.stdout?.on('data', (d: Buffer) => gatewayLog.push(d.toString()));
    gateway.stderr?.on('data', (d: Buffer) => gatewayLog.push(d.toString()));
    gateway.on('exit', (code, signal) => gatewayLog.push(`\n[gateway] exited ${code} ${signal}\n`));
    await waitForPort(GW_PORT, 30_000, 'PlaceholderServer');
  });

  test.afterAll(async () => {
    if (gateway && gateway.exitCode === null && !gateway.killed) {
      gateway.kill('SIGTERM');
      await new Promise((r) => setTimeout(r, 500));
      if (gateway.exitCode === null && !gateway.killed) {
        gateway.kill('SIGKILL');
      }
    }
  });

  test('walks the whole protocol at the wire level', async () => {
    const child = spawn('node', [SMOKE_SCRIPT, String(GW_PORT)], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    child.stdout?.on('data', (d: Buffer) => { out += d.toString(); });
    child.stderr?.on('data', (d: Buffer) => { out += d.toString(); });
    const code = await new Promise<number>((resolve) => child.on('exit', (c) => resolve(c ?? 1)));
    if (code !== 0) {
      console.log(`gateway tail:\n${gatewayTail()}`);
    }
    expect(code, `the wire-level protocol walk must pass:\n${out.slice(-3_000)}`).toBe(0);
    expect(out, 'every protocol check must pass').toContain('42 passed, 0 failed');
  });

  test('joins through the UI and punches a tree', async ({ }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 180_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(String(error?.message ?? error)));
    const consoleTail = installConsoleRecorder(page);

    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must boot').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);
    await waitForFpsReading(page, 15_000);

    // ── Multiplayer -> Direct Connection ──
    await clickBridgeButton(page, 'multiplayer', 'the title screen must expose the Multiplayer button');
    await waitForTelemetry(page, 8_000, (t) => t.screen.includes('JoinMultiplayerScreen'));
    await clickBridgeButton(page, 'directconnection', 'the server browser must expose Direct Connection');
    await waitForTelemetry(page, 8_000, (t) => t.screen.includes('DirectConnectScreen'));

    // ── Type the gateway address (the EditBox is focused; retry until
    // the Join button enables - EditBox input can drop under load) ──
    const address = `127.0.0.1:${GW_PORT}`;
    let joined = false;
    for (let attempt = 0; attempt < 3 && !joined; attempt++) {
      await page.keyboard.type(address, { delay: 35 });
      joined = !!(await waitForTelemetry(page, 3_000, (t) =>
        t.screen.includes('DirectConnectScreen')
        && (t.buttons ?? []).some((b) => b.id === 'joinserver' && b.active === true)));
      if (!joined) {
        await page.keyboard.press('ControlOrMeta+a');
        await page.keyboard.press('Backspace');
      }
    }
    expect(joined, 'typing the address must enable Join Server').toBeTruthy();

    // ── Join: the real handshake - login - configuration - play ──
    await clickBridgeButton(page, 'joinserver', 'Join Server must be clickable');
    const inWorld = await waitForTelemetry(page, 25_000, (t) =>
      t.screen === 'in-game' && t.inWorld && gameplayOf(t) !== null);
    expect(inWorld, 'the play Login packet must enter the world').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '01-multiplayer-in-world');

    // The server's spawn, not the local flat preset's
    const gp = gameplayOf(inWorld)!;
    expect(gp.spawn, 'the gateway spawn must arrive with the play Login').toEqual([0.5, -60, 0.5]);
    expect(gp.creative, 'the gateway joins in survival').toBe(false);

    // ── Aim at the first tree's bottom log (3,-60,1): trunk at (3,1),
    // eye (0.5,-58.38,0.5) -> yaw atan2(-3,1)=-71.6, pitch atan(1.12/3.16)=19.5 ──
    // The snap retries: a SwiftShader frame stall can swallow one look
    // application, so the aim re-snaps up to three times.
    let aimed: Telemetry | null = null;
    for (let attempt = 0; attempt < 5 && !aimed; attempt++) {
      await setLook(page, -71.6, 19.5);
      aimed = await waitForTelemetry(page, 8_000, (t) => {
        const g = gameplayOf(t);
        const hit = g?.hit;
        return !!hit && hit.x === 3 && hit.y === -60 && hit.z === 1;
      });
    }
    if (!aimed) {
      console.log(`AIM DEBUG console tail:\n${consoleTail.text().slice(-3_000)}`);
    }
    expect(aimed, 'the crosshair must target the tree log within vanilla reach').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '02-aiming-at-the-tree');

    // ── Grab the mouse, then punch and hold ──
    // Pointer-lock discipline (the crafting spec's rule): a mouse move
    // while grabbed is a look delta, so every interaction moves to the
    // canvas center FIRST (absorbing the drag), snaps the camera, and
    // only acts once the crosshair verifies the target.
    const canvas = await canvasCenter(page);
    expect(canvas, 'the game canvas must exist').toBeTruthy();
    // Vanilla click-to-grab: verify the pointer actually locked (headless
    // Chrome enforces a ~1.25s re-lock cooldown, so a rejected request
    // needs a retry - the held dig requires the grab).
    const grabLocked = async (): Promise<boolean> => {
      for (let attempt = 0; attempt < 4; attempt++) {
        await page.mouse.move(canvas!.x, canvas!.y);
        await page.mouse.click(canvas!.x, canvas!.y);
        await page.waitForTimeout(700);
        if (await page.evaluate(() => !!document.pointerLockElement)) {
          return true;
        }
        await page.waitForTimeout(900); // re-lock cooldown
      }
      return false;
    };
    expect(await grabLocked(), 'the canvas must grab the pointer').toBeTruthy();
    await page.mouse.move(canvas!.x, canvas!.y); // absorb the lock delta
    let reAimed: Telemetry | null = null;
    for (let attempt = 0; attempt < 5 && !reAimed; attempt++) {
      await setLook(page, -71.6, 19.5);
      reAimed = await waitForTelemetry(page, 8_000, (t) => {
        const g = gameplayOf(t);
        const hit = g?.hit;
        return !!hit && hit.x === 3 && hit.y === -60 && hit.z === 1;
      });
    }
    expect(reAimed, 'the crosshair must stay on the log after the grab').toBeTruthy();
    // Hold left through the survival break (~60 game ticks). A dropped
    // pointer lock or a swallowed press restarts the hold; the break is
    // the server's (Block Update + Set Container Slot come back).
    let mined: Telemetry | null = null;
    for (let attempt = 0; attempt < 4 && !mined; attempt++) {
      await page.mouse.down(); // left: START_DIGGING, hold through the progress
      mined = await waitForTelemetry(page, 25_000, (t) => {
        const g = gameplayOf(t);
        return !!g && g.hotbar.slots[0]?.[0] === 351 && g.hotbar.slots[0]?.[1] === 1;
      });
      await page.mouse.up();
      if (!mined) {
        expect(await grabLocked(), 'the re-grab must lock the pointer').toBeTruthy();
        await page.mouse.move(canvas!.x, canvas!.y);
        await setLook(page, -71.6, 19.5);
        await waitForTelemetry(page, 6_000, (t) => {
          const g = gameplayOf(t);
          const hit = g?.hit;
          return !!hit && hit.x === 3 && hit.y === -60 && hit.z === 1;
        });
      }
    }
    if (!mined) {
      const last = await readGameTelemetry(page).catch(() => null);
      console.log(`MINE DEBUG gameplay=${JSON.stringify(last ? gameplayOf(last) : null).slice(0, 500)}`);
      console.log(`MINE DEBUG console tail:\n${consoleTail.text().slice(-2_000)}`);
    }
    expect(mined, 'punching the tree must mine the oak_log into the hotbar').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '03-log-in-the-hotbar');

    const blockGone = await pollQueryBlock(page, 3, -60, 1, 0, 10_000);
    expect(blockGone, 'the server must break the block (Block Update), not the client').toBeTruthy();

    // ── Place the log back on the ground ahead ──
    await setLook(page, 0, 58);
    await waitForTelemetry(page, 10_000, (t) => {
      const g = gameplayOf(t);
      const hit = g?.hit;
      return !!hit && hit.x === 0 && hit.y === -61 && hit.z === 1;
    });
    await page.mouse.click(canvas!.x, canvas!.y, { button: 'right' }); // Use Item On
    const placed = await pollQueryBlock(page, 0, -60, 1, 351, 10_000);
    expect(placed, 'the placed log must come back as a server Block Update').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '04-log-placed-back');
    const afterPlace = await waitForTelemetry(page, 8_000, (t) => {
      const g = gameplayOf(t);
      return !!g && g.hotbar.slots[0] === null;
    });
    expect(afterPlace, 'the server must consume the placed stock').toBeTruthy();

    // ── Disconnect from the pause menu ──
    await page.keyboard.press('Escape');
    await waitForTelemetry(page, 8_000, (t) => t.screen.includes('PauseScreen'));
    const pause = (await readGameTelemetry(page))!;
    const disconnectButton = (pause.buttons ?? []).find((b) => b.id === 'disconnect');
    expect(disconnectButton, 'a multiplayer pause menu must show Disconnect (not Save and Quit)').toBeTruthy();
    await clickBridgeButton(page, 'disconnect', 'the Disconnect button must be clickable');
    const backAtTitle = await waitForTelemetry(page, 8_000, (t) => t.screen.includes('TitleScreen'));
    expect(backAtTitle, 'Disconnect must return to the title screen').toBeTruthy();

    expect(pageErrors, 'the multiplayer flow must not throw page errors').toEqual([]);
    await context.close();
    await browser.close();
  });

  test('a dead address lands on the vanilla disconnect screen', async ({ }, testInfo) => {
    test.setTimeout(BOOT_TIMEOUT + READY_TIMEOUT + 60_000);
    const browser = await freshBrowser();
    const context = await browser.newContext();
    const page = await context.newPage();
    installConsoleRecorder(page);

    await page.goto('/index.html', { waitUntil: 'commit' });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT), 'the TeaVM bundle must boot').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT), 'the client must reach the title screen').toBe(true);

    await clickBridgeButton(page, 'multiplayer', 'the title screen must expose the Multiplayer button');
    await waitForTelemetry(page, 8_000, (t) => t.screen.includes('JoinMultiplayerScreen'));
    await clickBridgeButton(page, 'directconnection', 'the server browser must expose Direct Connection');
    await waitForTelemetry(page, 8_000, (t) => t.screen.includes('DirectConnectScreen'));

    let ready = false;
    for (let attempt = 0; attempt < 3 && !ready; attempt++) {
      await page.keyboard.type('127.0.0.1:9', { delay: 35 });
      ready = !!(await waitForTelemetry(page, 3_000, (t) =>
        t.screen.includes('DirectConnectScreen')
        && (t.buttons ?? []).some((b) => b.id === 'joinserver' && b.active === true)));
      if (!ready) {
        await page.keyboard.press('ControlOrMeta+a');
        await page.keyboard.press('Backspace');
      }
    }
    expect(ready, 'typing the dead address must enable Join Server').toBeTruthy();
    await clickBridgeButton(page, 'joinserver', 'Join Server must be clickable');
    const disconnected = await waitForTelemetry(page, 20_000, (t) =>
      t.screen.includes('DisconnectedScreen'));
    expect(disconnected, 'the refused connection must open the disconnect screen').toBeTruthy();
    await attachMilestoneScreenshot(page, testInfo, '05-connection-refused');
    await context.close();
    await browser.close();
  });
});

// ==================== local helpers (file-local, like the other specs) ====================

/** Headless Chromium launch flags shared by the gameplay specs (they keep
 * the browser from competing with the TeaVM bootstrap for CPU). */
const DEDICATED_ARGS = [
  '--no-sandbox',
  '--disable-dev-shm-usage',
  '--ignore-gpu-blocklist',
  '--enable-unsafe-swiftshader',
  '--use-gl=angle',
  '--use-angle=swiftshader',
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-sync',
  '--no-first-run',
  '--no-default-browser-check',
];

/** Launches a dedicated Chromium for one test (see DEDICATED_ARGS). */
async function freshBrowser() {
  const { chromium } = await import('@playwright/test');
  return chromium.launch({ headless: true, args: DEDICATED_ARGS });
}

async function clickBridgeButton(page: Page, id: string, failMessage: string): Promise<void> {
  const coords = await getButtonCssCoords(page, id);
  expect(coords, failMessage).not.toBeNull();
  await page.mouse.move(coords!.cssX, coords!.cssY);
  await page.waitForTimeout(100);
  await page.mouse.click(coords!.cssX, coords!.cssY);
}

async function setLook(page: Page, yaw: number, pitch: number): Promise<void> {
  await page.evaluate(({ yaw: y, pitch: p }) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { setLook?: (yaw: number, pitch: number) => void };
      }
    ).__eaglercraftGame;
    game?.setLook?.(y, p);
  }, { yaw, pitch });
}

/** Reads a world block through the bridge's synchronous queryBlock. */
async function queryBlock(page: Page, x: number, y: number, z: number): Promise<number | null> {
  return page.evaluate(({ bx, by, bz }) => {
    const game = (
      self as unknown as {
        __eaglercraftGame?: { queryBlock?: (x: number, y: number, z: number) => number | null };
      }
    ).__eaglercraftGame;
    return game?.queryBlock ? game.queryBlock(bx, by, bz) : null;
  }, { bx: x, by: y, bz: z });
}

async function pollQueryBlock(
  page: Page,
  x: number,
  y: number,
  z: number,
  expected: number,
  timeoutMs: number,
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if ((await queryBlock(page, x, y, z)) === expected) {
      return true;
    }
    await page.waitForTimeout(250);
  }
  return (await queryBlock(page, x, y, z)) === expected;
}

async function canvasCenter(page: Page): Promise<{ x: number; y: number } | null> {
  return page.evaluate(() => {
    const byId = document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null;
    const c = byId ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[])
      .find((k) => k.clientWidth > 0);
    if (!c) return null;
    const rect = c.getBoundingClientRect();
    return { x: rect.left + c.clientWidth / 2, y: rect.top + c.clientHeight / 2 };
  });
}
