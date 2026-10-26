import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import {
  enterWorld,
  gameplayOf,
  waitForClientBootstrap,
  waitForReadyState,
  waitForTelemetry,
  type Telemetry,
} from './support';

const BOOT_TIMEOUT = 150_000;
const READY_TIMEOUT = 90_000;

/**
 * Feature 9 — the real input contract: WASD moves along the physics look
 * vector, mouse look turns the right way, and the rendered view agrees with
 * the pick/physics yaw convention.
 *
 * These three locked a real production bug: KeyboardInput/xxa-zza were
 * vanilla-exact and the pick ray was vanilla-exact, but
 * (a) LocalPlayer.turn fed the VERTICAL delta into yaw and the HORIZONTAL
 *     delta into pitch with an inverted sign, and
 * (b) the view matrix was Rx(pitch)*Ry(yaw) instead of vanilla's
 *     Rx(pitch)*Ry(yaw + 180) - so the camera faced 180 degrees off the
 *     physics yaw at yaw 0 and 90 degrees off at yaw 45. Nothing in the
 *     older specs caught either: they aim through the setLook bridge (which
 *     snaps yaw/pitch directly) and assert gameplay, never screen direction.
 * The view test below renders a known block straight down the physics look
 * vector and requires it on screen center - the 180-degree flip renders it
 * behind the camera.
 */

// A fresh Chromium per test (see crafting.spec.ts - SwiftShader shader
// caches from sibling tests stretch frames enough to swallow input).
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

async function freshBrowser() {
  const { chromium } = await import('@playwright/test');
  return chromium.launch({ headless: true, args: DEDICATED_ARGS });
}

async function canvasCenter(page: Page): Promise<{ x: number; y: number }> {
  const box = await page.evaluate(() => {
    const c = document.getElementById('EaglerCraftX_26_1_2_Canvas')
      ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[])
        .find((x) => x.width > 0)
      ?? null;
    if (!c) return null;
    const r = c.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  });
  expect(box, 'the game canvas must exist').toBeTruthy();
  return { x: box!.x + box!.w / 2, y: box!.y + box!.h / 2 };
}

interface SceneApi {
  setLook?: (yaw: number, pitch: number) => void;
  setScene?: (o: {
    blocks?: [number, number, number, number][];
    fov?: number;
    hideHud?: boolean;
  }) => void;
}

async function setLook(page: Page, yaw: number, pitch: number): Promise<void> {
  await page.evaluate(({ yaw: y, pitch: p }) => {
    const game = (self as unknown as { __eaglercraftGame?: SceneApi })
      .__eaglercraftGame;
    game?.setLook?.(y, p);
  }, { yaw, pitch });
}

async function setScene(
  page: Page,
  scene: { blocks?: [number, number, number, number][]; fov?: number; hideHud?: boolean },
): Promise<void> {
  await page.evaluate((s) => {
    const game = (self as unknown as { __eaglercraftGame?: SceneApi })
      .__eaglercraftGame;
    game?.setScene?.(s);
  }, scene);
}

type PosPredicate = (t: Telemetry) => boolean;

/** Polls telemetry until pos/rot settles at the predicate or times out. */
async function waitForPlayer(
  page: Page,
  timeout: number,
  predicate: PosPredicate,
): Promise<Telemetry | null> {
  return waitForTelemetry(page, timeout, (t) => {
    const gp = gameplayOf(t);
    return !!gp && predicate(t);
  });
}

test.beforeEach(() => {
  test.setTimeout(240_000);
});

test('WASD walks along the physics look vector', async () => {
  const browser = await freshBrowser();
  const context = await browser.newContext({ viewport: { width: 960, height: 600 } });
  const page = await context.newPage();
  try {
    await page.goto('/index.html', { waitUntil: 'commit', timeout: BOOT_TIMEOUT });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen').toBe(true);
    await enterWorld(page);
    await page.waitForTimeout(1_200); // settle on the ground

    // Face south (yaw 0 = +Z): W must increase z, x ~unchanged.
    await setLook(page, 0, 0);
    await page.waitForTimeout(300);
    const southStart = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      return Math.abs(gp.player.yaw % 360) < 0.5;
    });
    expect(southStart, 'setLook(0) must settle yaw').toBeTruthy();
    const sx = gameplayOf(southStart!)!.player.x;
    const sz = gameplayOf(southStart!)!.player.z;

    await page.keyboard.down('KeyW');
    await page.waitForTimeout(700);
    await page.keyboard.up('KeyW');
    const southEnd = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      return gp.player.z > sz + 0.8;
    });
    expect(southEnd, 'W at yaw 0 must move south (+Z)').toBeTruthy();
    const southDx = Math.abs(gameplayOf(southEnd!)!.player.x - sx);
    expect(southDx, `W at yaw 0 must not strafe (dx=${southDx})`).toBeLessThan(0.5);

    // Face west (yaw 90 = -X): W must decrease x, z ~unchanged.
    await setLook(page, 90, 0);
    const westStart = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      return Math.abs(gp.player.yaw - 90) < 0.5;
    });
    expect(westStart, 'setLook(90) must settle yaw').toBeTruthy();
    const wx = gameplayOf(westStart!)!.player.x;
    const wz = gameplayOf(westStart!)!.player.z;

    await page.keyboard.down('KeyW');
    await page.waitForTimeout(700);
    await page.keyboard.up('KeyW');
    const westEnd = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      return gp.player.x < wx - 0.8;
    });
    expect(westEnd, 'W at yaw 90 must move west (-X)').toBeTruthy();
    const westDz = Math.abs(gameplayOf(westEnd!)!.player.z - wz);
    expect(westDz, `W at yaw 90 must not strafe (dz=${westDz})`).toBeLessThan(0.5);
  } finally {
    await browser.close();
  }
});

test('pointer-lock drag turns yaw right and pitch down', async () => {
  const browser = await freshBrowser();
  const context = await browser.newContext({ viewport: { width: 960, height: 600 } });
  const page = await context.newPage();
  try {
    await page.goto('/index.html', { waitUntil: 'commit', timeout: BOOT_TIMEOUT });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen').toBe(true);
    await enterWorld(page);
    await page.waitForTimeout(1_200);

    const canvas = await canvasCenter(page);

    // Grab with verification (headless re-lock cooldown ~1.25s).
    let grabbed = false;
    for (let attempt = 0; attempt < 4 && !grabbed; attempt++) {
      await page.mouse.move(canvas.x, canvas.y);
      await page.mouse.click(canvas.x, canvas.y);
      await page.waitForTimeout(700);
      grabbed = await page.evaluate(() => !!document.pointerLockElement);
      if (!grabbed) await page.waitForTimeout(900);
    }
    expect(grabbed, 'the pointer must lock for look input').toBeTruthy();

    await setLook(page, 0, 0);
    await page.waitForTimeout(300);
    const before = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      return Math.abs(gp.player.yaw % 360) < 0.5 && Math.abs(gp.player.pitch) < 0.5;
    });
    expect(before, 'the camera must settle at the snapped orientation').toBeTruthy();

    // Drag right: vanilla turn() adds dx*0.15*sens to yaw -> yaw INCREASES.
    // Synthetic events drive the SAME canvas mousemove listener real
    // pointer-lock deltas flow through (headless CDP synthesizes no
    // movementX from raw mouse moves).
    const drag = (dx: number, dy: number) => page.evaluate(({ ddx, ddy }) => {
      const c = document.getElementById('EaglerCraftX_26_1_2_Canvas')
        ?? (Array.from(document.querySelectorAll('canvas')) as HTMLCanvasElement[])
          .find((x) => x.width > 0);
      for (let i = 1; i <= 4; i++) {
        c?.dispatchEvent(new MouseEvent('mousemove', {
          movementX: ddx / 4, movementY: ddy / 4, bubbles: true,
        }));
      }
    }, { ddx: dx, ddy: dy });
    await drag(48, 0);
    const right = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      const dyaw = gp.player.yaw - gameplayOf(before!)!.player.yaw;
      return dyaw > 1.0;
    });
    expect(right, 'a rightward drag must increase yaw (turn right)').toBeTruthy();

    // Drag down: vanilla turn() adds dy*0.15*sens to pitch -> pitch INCREASES
    // (positive pitch looks down).
    await drag(0, 36);
    const down = await waitForPlayer(page, 10_000, (t) => {
      const gp = gameplayOf(t)!;
      const dpitch = gp.player.pitch - gameplayOf(right!)!.player.pitch;
      return dpitch > 1.0;
    });
    expect(down, 'a downward drag must increase pitch (look down)').toBeTruthy();
  } finally {
    await browser.close();
  }
});

test('the rendered view agrees with the physics look vector', async () => {
  const browser = await freshBrowser();
  const context = await browser.newContext({ viewport: { width: 768, height: 768 } });
  const page = await context.newPage();
  try {
    await page.goto('/index.html', { waitUntil: 'commit', timeout: BOOT_TIMEOUT });
    expect(await waitForClientBootstrap(page, BOOT_TIMEOUT),
      'the TeaVM bundle must export self.main').toBe(true);
    expect(await waitForReadyState(page, READY_TIMEOUT),
      'the client must reach the title screen').toBe(true);
    await enterWorld(page);
    await page.waitForTimeout(1_200);

    // A cobblestone marker 6 blocks ahead of spawn (yaw 0 = +Z): outside the
    // 4.5-block reach so no outline draws, well inside the 90-degree view.
    // Noon locks the light so the gray threshold is deterministic (a fresh
    // world boots at dawn, where the 0.65 world dimming shifts every mean).
    await setScene(page, { blocks: [[0, -59, 6, 4]] });
    await page.evaluate(() => {
      const game = (self as unknown as { __eaglercraftGame?: SceneApi }).__eaglercraftGame;
      game?.setScene?.({ fov: 70 });
    });
    await page.evaluate(() => self.__eaglercraftGame.setDayTime(6000));
    await setLook(page, 0, 0);
    await page.waitForTimeout(1_500); // frames must pass with the block visible

    const buf = await page.screenshot();
    const dataUrl = 'data:image/png;base64,' + buf.toString('base64');
    const scan = await page.evaluate(async (url) => {
      const img = new Image();
      await new Promise((res, rej) => {
        img.onload = res;
        img.onerror = rej;
        img.src = url;
      });
      const c = document.createElement('canvas');
      c.width = img.width;
      c.height = img.height;
      const ctx = c.getContext('2d')!;
      ctx.drawImage(img, 0, 0);
      // Center third of the frame: the block must sit near screen center.
      const x0 = Math.floor(img.width / 3);
      const x1 = Math.ceil((img.width * 2) / 3);
      const y0 = Math.floor(img.height / 3);
      const y1 = Math.ceil((img.height * 2) / 3);
      const d = ctx.getImageData(x0, y0, x1 - x0, y1 - y0).data;
      let gray = 0;
      for (let i = 0; i < d.length; i += 4) {
        const r = d[i];
        const g = d[i + 1];
        const b = d[i + 2];
        const spread = Math.max(Math.abs(r - g), Math.abs(g - b), Math.abs(r - b));
        const mean = (r + g + b) / 3;
        // Cobblestone: desaturated mid-gray with a slight blue cast from the
        // sky-light's channels (measured mean ~(71,71,100), spread ~29).
        // Excludes grass (green-dominant, spread ~57), the sky (B=255), and
        // the white crosshair (mean > 200).
        if (spread < 45 && mean >= 50 && mean <= 150 && b < 180) {
          gray++;
        }
      }
      return { gray, w: x1 - x0, h: y1 - y0 };
    }, dataUrl);
    expect(
      scan.gray,
      `the cobblestone ahead must render at screen center (gray px=${scan.gray} in ${scan.w}x${scan.h}) - a 180-degree view flip puts it behind the camera`,
    ).toBeGreaterThan(300);
  } finally {
    await browser.close();
  }
});
