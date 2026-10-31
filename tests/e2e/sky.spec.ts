import type { Page } from '@playwright/test';
import type { Telemetry } from './support';
import { expect, test } from './fixtures';
import {
  attachMilestoneScreenshot,
  enterWorld,
  getButtonCssCoords,
  hasGameplayBridge,
  waitForTelemetry,
} from './support';

/** Clicks a bridge button by its telemetry id (move + settle + click). */
async function clickBridgeButton(
  page: Page,
  id: string,
  failMessage: string,
): Promise<void> {
  const coords = await getButtonCssCoords(page, id);
  expect(coords, failMessage).not.toBeNull();
  await page.mouse.move(coords!.cssX, coords!.cssY);
  await page.waitForTimeout(100);
  await page.mouse.click(coords!.cssX, coords!.cssY);
}

/**
 * The sky snapshot from buildGameplayJson (mirrors the "sky" object the
 * client pushes every tick): the exact timeline sample the renderer used
 * for its last frame.
 */
interface SkySnapshot {
  dayTime: number;
  skyColor: number;
  fogColor: number;
  sunAngle: number;
  starBrightness: number;
  moonPhase: number;
  skyLight: number;
}

function skyOf(state: Telemetry | null | undefined): SkySnapshot | null {
  const gp = state?.gameplay as { sky?: SkySnapshot } | null | undefined;
  return gp?.sky ?? null;
}

/**
 * Waits for the client bootstrap to expose the bridge. The clientPage
 * fixture resolves at navigation "commit" - long before the bundle has
 * finished evaluating - so the capability gate must poll, not probe once.
 */
async function waitForBridge(page: Page, timeoutMs = 90_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await hasGameplayBridge(page)) {
      return true;
    }
    await page.waitForTimeout(500);
  }
  return false;
}

/** Queues a setDayTime through the bridge (consumed on the next tick). */
async function setDayTime(page: Page, t: number): Promise<void> {
  await page.evaluate((v) => {
    const game = (
      self as unknown as { __eaglercraftGame?: { setDayTime?: (t: number) => void } }
    ).__eaglercraftGame;
    game?.setDayTime?.(v);
  }, t);
}

/**
 * Waits for the telemetry to report the requested day time. The request
 * is consumed on the next game tick and the clock keeps running, so the
 * read value drifts forward a few ticks - anything within +200 is the
 * requested sample.
 */
async function readSkyAt(page: Page, t: number, label: string): Promise<SkySnapshot> {
  const state = await waitForTelemetry(page, 15_000, (s) => {
    const sky = skyOf(s);
    return !!sky && sky.dayTime >= t && sky.dayTime < t + 200;
  });
  const sky = skyOf(state);
  expect(sky, `${label}: the sky snapshot must exist in world`).toBeTruthy();
  return sky!;
}

test.describe('day/night sky timeline', () => {
  // The vanilla numbers asserted here are baked from
  // data/minecraft/timeline/day.json + dimension_type/overworld.json
  // (see SkyTimeline.java / scripts/gen_sky_timeline.py for provenance).
  test('noon, dusk, midnight and dawn follow the vanilla day timeline (capability-gated)', async ({
    clientPage: page,
  }, testInfo) => {
    // Four setDayTime cycles plus five in-world screenshots: on the
    // software-GL CI runners the captures alone can outlive the old
    // global 300s budget (the "60s screenshot stall"), so the sky
    // tests carry their own tick-clock-sized budget.
    test.setTimeout(600_000);
    test.skip(!(await waitForBridge(page)), 'requires the gameplay + setDayTime bridge');
    await enterWorld(page);

    // ── Noon (t=6000): base sky/fog colors, sun at the zenith, no stars ──
    await setDayTime(page, 6000);
    let sky = await readSkyAt(page, 6000, 'noon');
    expect(sky.skyColor, 'noon sky = #78a7ff x #ffffff').toBe(0x78a7ff);
    expect(sky.fogColor, 'noon fog = #c0d8ff x #ffffff').toBe(0xc0d8ff);
    expect(
      Math.abs(sky.sunAngle),
      'noon sun angle = 0 (zenith) after the cubic_bezier ease',
    ).toBeLessThanOrEqual(2);
    expect(sky.starBrightness, 'no stars at noon').toBe(0);
    expect(sky.skyLight, 'full world brightness at noon').toBe(0xffffff);
    expect(sky.moonPhase, 'day 0 = full moon').toBe(0);
    await attachMilestoneScreenshot(page, testInfo, '01-sky-noon');

    // ── Dusk (t=12000): sun past the horizon, sky color dimming ──
    // The sky multiplier lerps white->black between t=11867 and 13670;
    // at 12000 it is (236,236,236) -> sky (111,155,236).
    await setDayTime(page, 12000);
    sky = await readSkyAt(page, 12000, 'dusk');
    expect(sky.sunAngle, 'dusk sun angle ~77.6 deg (below the horizon)').toBeGreaterThan(70);
    expect(sky.sunAngle).toBeLessThan(86);
    expect(sky.skyColor, 'dusk sky = #78a7ff x the white->black ramp (the +200-tick\n    // drift window maps to r 98..111, g 136..155, b 209..236)').toBeGreaterThanOrEqual(0x6288d1);
    expect(sky.skyColor).toBeLessThanOrEqual(0x6f9bec);
    // channel sanity for the drift window: b stays the largest channel
    const duskR = (sky.skyColor >> 16) & 0xff;
    const duskG = (sky.skyColor >> 8) & 0xff;
    const duskB = sky.skyColor & 0xff;
    expect(duskB, 'blue dominates the dusk sky ramp').toBeGreaterThan(duskG);
    expect(duskG).toBeGreaterThan(duskR);
    // Vanilla's star track starts fading stars in around t=11700, while
    // the sky ramp is still brightening down - at the +200-tick drift
    // window's end the brightness is ~0.1, far from the night plateau 0.5
    expect(sky.starBrightness, 'stars only barely starting at dusk').toBeLessThan(0.15);
    await attachMilestoneScreenshot(page, testInfo, '02-sky-dusk');

    // ── Midnight (t=18000): black sky, dark blue fog, stars, moonlight ──
    await setDayTime(page, 18000);
    sky = await readSkyAt(page, 18000, 'midnight');
    expect(sky.skyColor, 'midnight sky = #78a7ff x #000000').toBe(0x000000);
    expect(sky.fogColor, 'midnight fog = #c0d8ff x #0f0f16 (Java int-division truncation)').toBe(0x0b0c16);
    expect(
      Math.abs(sky.sunAngle - 180),
      'midnight sun angle = 180 (nadir)',
    ).toBeLessThanOrEqual(2);
    expect(sky.starBrightness, 'stars at full night brightness').toBeCloseTo(0.5, 1);
    expect(sky.skyLight, 'night world dimming = #7a7aff x 0.24').toBe(0x1d1d3d);
    expect(sky.moonPhase, 'still day 0 at t=18000').toBe(0);
    await attachMilestoneScreenshot(page, testInfo, '03-sky-midnight');

    // ── Dawn (t=23000): stars fading, sun climbing back up ──
    await setDayTime(page, 23000);
    sky = await readSkyAt(page, 23000, 'dawn');
    expect(sky.sunAngle, 'dawn sun angle ~266 deg').toBeGreaterThan(255);
    expect(sky.sunAngle).toBeLessThan(280);
    expect(sky.starBrightness, 'stars fading at dawn (track ~0.381)').toBeGreaterThan(0.3);
    expect(sky.starBrightness).toBeLessThan(0.45);
    await attachMilestoneScreenshot(page, testInfo, '04-sky-dawn');

    // ── The day count rides the free-running counter (moon phase) ──
    await setDayTime(page, 30000);
    sky = await readSkyAt(page, 30000, 'day-2 noon');
    expect(sky.moonPhase, 'day 1 = waning gibbous').toBe(1);
    expect(sky.skyColor, 'day 2 noon is a normal day again').toBe(0x78a7ff);
  });

  test('day time persists across save-and-quit (capability-gated)', async ({
    clientPage: page,
  }) => {
    test.setTimeout(600_000);
    test.skip(!(await waitForBridge(page)), 'requires the gameplay + setDayTime bridge');
    await enterWorld(page);

    const SAVE_TIME = 1234567;
    await setDayTime(page, SAVE_TIME);
    await readSkyAt(page, SAVE_TIME, 'pinned time');

    // Save and Quit to Title through the pause menu (vanilla flow)
    await page.keyboard.press('Escape');
    const pauseState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('PauseScreen'));
    expect(pauseState?.screen.includes('PauseScreen'), 'ESC must pause').toBe(true);
    await clickBridgeButton(page, 'saveandquittotitle', 'the pause menu must expose Save and Quit');
    const titleState = await waitForTelemetry(page, 15_000, (t) => t.screen.includes('TitleScreen'));
    expect(titleState?.screen.includes('TitleScreen'), 'Save and Quit must return to the title').toBe(true);

    // Re-enter the same world: the clock must resume from the saved value
    await enterWorld(page);
    const state = await waitForTelemetry(page, 15_000, (s) => {
      const sky = skyOf(s);
      return !!sky && sky.dayTime >= SAVE_TIME;
    });
    const sky = skyOf(state);
    expect(sky, 'the sky snapshot must exist after re-entry').toBeTruthy();
    expect(
      sky!.dayTime,
      `the restored day time must be the saved value plus tick drift (saved ${SAVE_TIME})`,
    ).toBeLessThan(SAVE_TIME + 600);
  });
});
