import { test, expect } from './fixtures';
import {
  attachMilestoneScreenshot,
  installErrorCollector,
  readCapturedErrors,
  readGameTelemetry,
  waitForClientBootstrap,
  waitForFpsReading,
  type GameTelemetry,
} from './support';

/**
 * HiDPI / device-pixel-ratio regression spec.
 *
 * The user-facing bug this guards against: on displays (or browser zoom
 * levels) with devicePixelRatio != 1 the drawing buffer used to stay at
 * CSS-pixel resolution while the Window cache, GUI scale and mouse mapping
 * used the true ratio. The GL viewport was sized once at boot from the same
 * stale ratio, so the title screen rendered oversized and clipped (truncated
 * buttons at the window edges) with the whole frame upscaled and pixelated -
 * and nothing ever re-aligned itself until the user manually resized the
 * window, which was the only event that re-triggered a buffer resize.
 *
 * Every other spec runs at the default deviceScaleFactor 1, where all three
 * sizes coincide and the bug is invisible. This spec boots a dedicated 2x
 * context and asserts the full consistency triangle:
 *
 *   1. buffer == CSS size x devicePixelRatio (the stale-buffer regression)
 *   2. telemetry guiScale == vanilla auto-scale rule for the CSS viewport
 *   3. every title button lands fully inside the visible canvas (the
 *      truncated-buttons / degenerate-scale regression)
 *   4. a mid-session viewport resize re-syncs the buffer (the old bug's
 *      workaround - resizing the window - must not be required to see a
 *      correct frame, but when it happens the pipeline must follow)
 */

/** The real game canvas ClientMain overlays on the viewport. */
const GAME_CANVAS_SELECTOR = '#EaglerCraftX_26_1_2_Canvas';

interface CanvasGeometry {
  clientWidth: number;
  clientHeight: number;
  bufferWidth: number;
  bufferHeight: number;
  devicePixelRatio: number;
}

async function readCanvasGeometry(page: import('@playwright/test').Page): Promise<CanvasGeometry> {
  return page.evaluate((selector) => {
    const canvas = document.querySelector(selector) as HTMLCanvasElement | null;
    if (!canvas) {
      return {
        clientWidth: 0,
        clientHeight: 0,
        bufferWidth: 0,
        bufferHeight: 0,
        devicePixelRatio: 0,
      };
    }
    return {
      clientWidth: canvas.clientWidth,
      clientHeight: canvas.clientHeight,
      bufferWidth: canvas.width,
      bufferHeight: canvas.height,
      devicePixelRatio: window.devicePixelRatio || 1,
    };
  }, GAME_CANVAS_SELECTOR);
}

/** Vanilla auto-scale rule (Window.calculateScale with preferredScale=MAX). */
function expectedGuiScale(cssWidth: number, cssHeight: number): number {
  let scale = 1;
  while (
    cssWidth / (scale + 1) >= 320 &&
    cssHeight / (scale + 1) >= 240
  ) {
    scale++;
  }
  return scale;
}

test.describe('HiDPI (deviceScaleFactor 2)', () => {
  test('drawing buffer tracks CSS x DPR and the title menu lays out unclipped', async ({
    browser,
  }, testInfo) => {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    await installErrorCollector(page);
    // "commit" (like the shared clientPage fixture): the TeaVM bootstrap
    // blocks the main thread, so waiting for load would deadlock.
    await page.goto('/index.html', { waitUntil: 'commit' });

    // 1. The drawing buffer must equal CSS x DPR once the runtime has run
    //    its first frame. Polled, because the buffer is applied by the game
    //    loop after the bundle finishes evaluating.
    await expect
      .poll(
        async () => {
          const geo = await readCanvasGeometry(page);
          return {
            bufferWidth: geo.bufferWidth,
            bufferHeight: geo.bufferHeight,
            wantWidth: Math.round(geo.clientWidth * geo.devicePixelRatio),
            wantHeight: Math.round(geo.clientHeight * geo.devicePixelRatio),
          };
        },
        {
          timeout: 180_000,
          message: 'drawing buffer never reached CSS x devicePixelRatio',
        },
      )
      .toEqual({
        bufferWidth: 2560, // 1280 CSS x 2 DPR
        bufferHeight: 1600, // 800 CSS x 2 DPR
        wantWidth: 2560,
        wantHeight: 1600,
      });

    const geo = await readCanvasGeometry(page);
    expect(geo.clientWidth).toBe(1280);
    expect(geo.clientHeight).toBe(800);
    expect(geo.devicePixelRatio).toBe(2);

    // 2. The client must reach the running title screen at the 2x buffer.
    await waitForClientBootstrap(page);
    const { fps } = await waitForFpsReading(page, 30_000);
    expect(fps).toBeGreaterThan(0);

    // 3. Telemetry GUI scale must follow the vanilla auto rule for the CSS
    //    viewport (1280x800 -> 3), not degenerate to 1 (the boot state of
    //    the stale-size bug) and not blow up with the 2x buffer.
    let telemetry: GameTelemetry | null = null;
    await expect
      .poll(async () => {
        telemetry = await readGameTelemetry(page);
        return telemetry ? telemetry.guiScale : -1;
      }, { timeout: 60_000 })
      .toBe(expectedGuiScale(1280, 800));
    expect(telemetry!.screen).toContain('TitleScreen');

    // 4. Every reported button must land fully inside the visible canvas.
    //    GUI pixels scale by guiScale backing pixels each; the canvas shows
    //    its backing at clientWidth/width CSS pixels per backing pixel.
    const buttons = telemetry!.buttons;
    expect(buttons.length).toBeGreaterThan(0);
    const backingToCss = geo.clientWidth / geo.bufferWidth;
    for (const button of buttons) {
      const cssRight = (button.x + button.w) * telemetry!.guiScale * backingToCss;
      const cssBottom = (button.y + button.h) * telemetry!.guiScale * backingToCss;
      expect(
        cssRight,
        `button "${button.id}" (${button.label}) right edge clipped`,
      ).toBeLessThanOrEqual(geo.clientWidth + 1);
      expect(
        cssBottom,
        `button "${button.id}" (${button.label}) bottom edge clipped`,
      ).toBeLessThanOrEqual(geo.clientHeight + 1);
      expect(
        button.x,
        `button "${button.id}" (${button.label}) left edge clipped`,
      ).toBeGreaterThanOrEqual(-1);
    }

    await attachMilestoneScreenshot(page, testInfo, 'hidpi-title');

    // 5. A mid-session resize must re-sync the buffer within a frame or two
    //    (the game loop re-applies CSS x DPR every frame and the per-tick
    //    canvas sync re-initializes the screens).
    await page.setViewportSize({ width: 1000, height: 700 });
    await expect
      .poll(async () => {
        const resized = await readCanvasGeometry(page);
        return [
          resized.bufferWidth,
          resized.bufferHeight,
          Math.round(resized.clientWidth * resized.devicePixelRatio),
          Math.round(resized.clientHeight * resized.devicePixelRatio),
        ];
      }, { timeout: 30_000 })
      .toEqual([2000, 1400, 2000, 1400]);

    // 6. No game runtime errors along the way.
    const errors = await readCapturedErrors(page);
    const gameErrors = errors.filter((error) => error.source.includes('classes.js'));
    expect(gameErrors, JSON.stringify(gameErrors, null, 2)).toEqual([]);

    await context.close();
  });
});
