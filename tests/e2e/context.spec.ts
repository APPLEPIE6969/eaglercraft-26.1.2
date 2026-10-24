import { test, expect } from './fixtures';

/**
 * Browser-integration regression spec.
 *
 * Guards two user-facing bugs from the flicker report:
 *
 * 1. The WebGL2 context used to ship `desynchronized: true`. Where Chrome
 *    honors the hint (real-GPU compositors, e.g. Windows DirectComposition)
 *    the low-latency present path is a documented flicker source: parts of
 *    the frame - typically the UI quads redrawn last - show stale or partial
 *    content. Headless and software GL never honor the hint, which is why
 *    the flicker only reproduced on the user's machine and not in CI. The
 *    attribute must stay off.
 *
 * 2. The document-level key listeners used to call preventDefault() on EVERY
 *    key, which swallowed the browser's own shortcuts: Ctrl+R / Ctrl+Shift+R
 *    (reload) and F12 / Ctrl+Shift+I|J|C (devtools) did nothing on the site.
 *    Those combos must reach the browser; ordinary gameplay keys must still
 *    be captured.
 */
test.describe('browser integration', () => {
  test('webgl2 context uses the flicker-safe attribute set', async ({
    clientPage,
  }) => {
    const attrsHandle = await clientPage.waitForFunction(() => {
      const canvas = document.querySelector(
        '#EaglerCraftX_26_1_2_Canvas',
      ) as HTMLCanvasElement | null;
      if (!canvas) {
        return null;
      }
      // getContext with the same type returns the ALREADY created context;
      // getContextAttributes reports what it was created with.
      const gl = canvas.getContext('webgl2') as WebGL2RenderingContext | null;
      return gl ? gl.getContextAttributes() : null;
    }, null, { timeout: 180_000 });
    // waitForFunction hands back a JSHandle; unwrap to the plain object.
    const attrs = await attrsHandle.jsonValue<{ desynchronized?: boolean; alpha?: boolean }>();
    expect(attrs, 'game canvas never produced a WebGL2 context').not.toBeNull();
    // The flicker fix: the low-latency present hint must stay off.
    expect(attrs!.desynchronized).toBe(false);
    // The page background must never bleed through the game framebuffer.
    expect(attrs!.alpha).toBe(false);
  });

  test('browser-reserved shortcuts reach the browser, gameplay keys do not', async ({
    clientPage,
  }) => {
    // The keyboard listeners register during client boot; the telemetry
    // bridge only exists once initialization completed, so waiting for it
    // guarantees the listeners under test are installed.
    await clientPage.waitForFunction(
      () => !!(window as { __eaglercraftGame?: unknown }).__eaglercraftGame,
      null,
      { timeout: 180_000 },
    );

    const results = await clientPage.evaluate(async () => {
      // diagnostics: what bundle is this page actually running?
      const bundle = await fetch('/classes.js').then((r) => r.text());
      const diag = {
        bundleBytes: bundle.length,
        desynchronized: /desynchronized\s*:\s*(true|false)/.exec(bundle)?.[1] ?? 'absent',
        hasF12Literal: bundle.includes('"F12"'),
      };
      const log: string[] = [];
      const orig = KeyboardEvent.prototype.preventDefault;
      KeyboardEvent.prototype.preventDefault = function () {
        log.push(`prevented: ${this.code}`);
        return orig.call(this);
      };
      const dispatch = (init: KeyboardEventInit): boolean => {
        const event = new KeyboardEvent('keydown', {
          cancelable: true,
          bubbles: true,
          ...init,
        });
        document.dispatchEvent(event);
        // NOTE: dispatchEvent()'s return value is `!defaultPrevented` -
        // read the event's own flag, not the dispatch result.
        return event.defaultPrevented;
      };
      const out = {
        diag,
        log,
        f12: dispatch({ code: 'F12', key: 'F12' }),
        ctrlR: dispatch({ code: 'KeyR', key: 'r', ctrlKey: true }),
        ctrlShiftR: dispatch({ code: 'KeyR', key: 'R', ctrlKey: true, shiftKey: true }),
        devtoolsPane: dispatch({ code: 'KeyI', key: 'I', ctrlKey: true, shiftKey: true }),
        plainKey: dispatch({ code: 'KeyA', key: 'a' }),
        f5: dispatch({ code: 'F5', key: 'F5' }),
      };
      KeyboardEvent.prototype.preventDefault = orig;
      return out;
    });

    // defaultPrevented === false -> the browser keeps its default action.
    expect(results.f12, 'F12 (devtools) was swallowed').toBe(false);
    expect(results.ctrlR, 'Ctrl+R (reload) was swallowed').toBe(false);
    expect(results.ctrlShiftR, 'Ctrl+Shift+R (hard reload) was swallowed').toBe(false);
    expect(results.devtoolsPane, 'Ctrl+Shift+I (devtools pane) was swallowed').toBe(false);
    // defaultPrevented === true -> the game captured the key as before.
    expect(results.plainKey, 'plain gameplay key is no longer captured').toBe(true);
    expect(results.f5, 'F5 (in-game use) is no longer captured').toBe(true);
  });
});
