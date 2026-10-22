import { expect, test } from './fixtures';
import { waitForClientBootstrap } from './support';

test.describe('graphics', () => {
  test('Chromium can create a WebGL2 context', async ({ page }) => {
    // A blank document is enough for the capability probe and keeps the test
    // independent from the TeaVM bootstrap.
    await page.setContent('<canvas id="probe" width="64" height="64"></canvas>');

    const gl = await page.evaluate(() => {
      const canvas = document.getElementById('probe') as HTMLCanvasElement;
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

  test('the game canvas accepts a graphics context', async ({ clientPage }) => {
    const booted = await waitForClientBootstrap(clientPage, 180_000);
    expect(booted, 'the TeaVM bundle must export self.main').toBe(true);

    const info = await clientPage.evaluate(() => {
      // The real game canvas is the one ClientMain creates (the shell's
      // #game_frame is only a placeholder container; the game canvas is a
      // viewport overlay). Fall back to the placeholder for bundles that
      // still nest inside it.
      const canvas =
        (document.getElementById('EaglerCraftX_26_1_2_Canvas') as HTMLCanvasElement | null) ??
        (document.getElementById('game_frame') as HTMLCanvasElement | null);
      if (!canvas) {
        return { present: false, webgl2: false, webgl: false };
      }
      const webgl2 = Boolean(canvas.getContext('webgl2'));
      const webgl = webgl2 ? false : Boolean(canvas.getContext('webgl'));
      return { present: true, webgl2, webgl };
    });

    expect(info.present, 'the game canvas must exist').toBe(true);
    expect(
      info.webgl2 || info.webgl,
      'the game canvas must be backed by a WebGL context',
    ).toBe(true);
  });
});
