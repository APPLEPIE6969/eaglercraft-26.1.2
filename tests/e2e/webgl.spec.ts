import { expect, test } from '@playwright/test';

test.describe('graphics', () => {
  test('Chromium can create a WebGL2 context', async ({ page }) => {
    await page.goto('/index.html');

    const gl = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 64;
      canvas.height = 64;
      const context = canvas.getContext('webgl2');
      if (!context) {
        return { webgl2: false, version: '', renderer: '' };
      }
      return {
        webgl2: true,
        version: String(context.getParameter(context.VERSION)),
        renderer: String(context.getParameter(context.RENDERER)),
      };
    });

    expect(gl.webgl2, 'WebGL2 must be available for the EaglerCraftX renderer').toBe(true);
    console.log(`[e2e] WebGL2 ${gl.version} — ${gl.renderer}`);
  });

  test('the game canvas accepts a graphics context', async ({ page }) => {
    await page.goto('/index.html');
    await page.waitForTimeout(2_000);

    const info = await page.evaluate(() => {
      const canvas = document.getElementById('game_frame') as HTMLCanvasElement | null;
      if (!canvas) {
        return { present: false, webgl2: false, webgl: false };
      }
      const webgl2 = Boolean(canvas.getContext('webgl2'));
      const webgl = webgl2 ? false : Boolean(canvas.getContext('webgl'));
      return { present: true, webgl2, webgl };
    });

    expect(info.present, '#game_frame must exist').toBe(true);
    expect(
      info.webgl2 || info.webgl,
      'the game canvas must be backed by a WebGL context',
    ).toBe(true);
  });
});
