import { expect, test } from '@playwright/test';

test.describe('published bundle', () => {
  test('serves the shell and the assets it references', async ({ request }) => {
    const index = await request.get('/index.html');
    expect(index.status(), 'index.html must be served').toBe(200);

    const html = await index.text();
    expect(html).toContain('classes.js');
    expect(html).toContain('game_frame');

    const logo = await request.get('/logo.svg');
    expect(logo.status(), 'logo.svg must be served').toBe(200);
    expect(await logo.text()).toContain('<svg');

    const robots = await request.get('/robots.txt');
    expect(robots.status(), 'robots.txt must be served').toBe(200);
  });

  test('serves the TeaVM bundle over HTTP', async ({ request }) => {
    const bundle = await request.get('/classes.js');
    expect(bundle.status(), 'classes.js must be served').toBe(200);

    const body = await bundle.text();
    expect(body.length, 'classes.js must be a full build, not a stub').toBeGreaterThan(50_000);
    expect(body, 'classes.js must be a TeaVM bundle').toContain('$rt_exports');
  });

  test('does not serve files outside the client directory', async ({ request }) => {
    const traversal = await request.get('/../package.json');
    expect(traversal.status()).toBe(404);
  });
});
