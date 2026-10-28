// Renders the title-screen panorama cubemap faces with the port's own
// world renderer - now straight from the GENERATED world: rolling grass
// hills with oak groves, no hand-planted trees.
//
// Face convention (docs/UI-FIDELITY.md, from vanilla CubeMapTexture):
//   GL faces [+X,-X,+Y,-Y,+Z,-Z] <- panorama_[1,3,5,4,0,2].png
// so we render:
//   panorama_0 = +Z (yaw 0)   panorama_1 = +X (yaw -90)
//   panorama_2 = -Z (yaw 180) panorama_3 = -X (yaw 90)
//   panorama_4 = -Y (pitch +90, straight down)
//   panorama_5 = +Y (pitch -90, straight up)
// Side faces use the exact 90-degree cube-face projection: square canvas,
// fov 90, perspective -> horizontal FOV = vertical FOV = 90.
//
// The bridge's setScene({dirtyRadius}) sweeps the whole view volume of the
// hilly base dirty so the budgeted mesh rebuilds mesh the ground out to
// the fog line (a no-op-edit trick cannot work when every column has its
// own surface height).
//
// One-shot: server + browser in this process (the sandbox reaps background
// processes between tool calls).
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { join, extname } from 'node:path';

const ROOT = '/home/z/my-project/eaglercraft-26.1.2/public';
const OUT_DIR = process.env.PANO_OUT || join(ROOT, 'assets/gui/sprites/title/background');
const PORT = 4322;
const FACE = 768;
const DAY_TIME = process.env.PANO_TIME ? Number(process.env.PANO_TIME) : null; // null = world default
const VIEW_RADIUS = 10; // sections (-10..10) = 21x21 ground toward the fog line

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.epk': 'application/octet-stream', '.lang': 'text/plain',
  '.json': 'application/json', '.map': 'application/json' };

const server = createServer(async (req, res) => {
  try {
    let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (p.endsWith('/')) p += 'index.html';
    const data = await readFile(join(ROOT, p));
    res.writeHead(200, { 'Content-Type': MIME[extname(p)] || 'application/octet-stream' });
    res.end(data);
  } catch {
    res.writeHead(404); res.end('nf');
  }
});
await new Promise(r => server.listen(PORT, r));

const browser = await chromium.launch({ headless: true, args: [
  '--no-sandbox', '--disable-dev-shm-usage', '--ignore-gpu-blocklist',
  '--enable-unsafe-swiftshader', '--use-gl=angle', '--use-angle=swiftshader',
  '--disable-background-networking', '--no-first-run', '--no-default-browser-check',
] });
const context = await browser.newContext({
  viewport: { width: FACE, height: FACE },
  deviceScaleFactor: 1,
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', e => errors.push('pageerror: ' + e.message));

await page.goto(`http://127.0.0.1:${PORT}/index.html`, { waitUntil: 'domcontentloaded' });

// Boot to the title screen (the shell's DOM gate: loading hidden + title
// overlay visible - the same ready check tests/e2e/support.ts uses)
const bootDeadline = Date.now() + 150_000;
let booted = false;
while (Date.now() < bootDeadline) {
  booted = await page.evaluate(() => {
    const loading = document.getElementById('loading-screen');
    const title = document.getElementById('title-overlay');
    const lh = !!loading &&
      (loading.classList.contains('hidden') || getComputedStyle(loading).display === 'none');
    const tv = !!title && getComputedStyle(title).display !== 'none';
    return lh && tv;
  });
  if (booted) break;
  await page.waitForTimeout(500);
}
if (!booted) throw new Error('boot timeout: title screen never reached');
console.log('booted, entering world...');

// Enter the generated world
await page.evaluate(() => self.__eaglercraftGame.enterWorld());
await page.waitForFunction(() => {
  const t = self.__eaglercraftGame;
  return t && t.inWorld && t.gameplay && t.gameplay.player && t.gameplay.player.onGround;
}, undefined, { timeout: 45_000 });
console.log('in world');

const player = await page.evaluate(() => {
  const p = self.__eaglercraftGame.gameplay.player;
  return { x: p.x, y: p.y, z: p.z };
});
console.log(`player at ${JSON.stringify(player)}`);
if (Math.abs(player.y + 49) > 0.01) {
  throw new Error(`spawn must be on the pad at y=-49 (got ${player.y})`);
}

// Mesh the whole view volume of the hills, then wait until the budgeted
// rebuilds have actually drained the dirty queue (deterministic - the
// software-GL probe rig runs at ~1fps, a real GPU drains in well under a
// second)
await page.evaluate(({ r, t }) => {
  if (t !== null) self.__eaglercraftGame.setDayTime(t);
  self.__eaglercraftGame.setScene({ dirtyRadius: r, fov: 90, hideHud: true });
}, { r: VIEW_RADIUS, t: DAY_TIME });
console.log(`scene set: dirtyRadius=${VIEW_RADIUS}`);

const drainDeadline = Date.now() + 120_000;
let dirty = -1;
while (Date.now() < drainDeadline) {
  await page.waitForTimeout(2_000);
  dirty = await page.evaluate(() => {
    const d = self.__eaglercraftGame.debugMesh(0, -50, 0);
    return d ? d.dirtyCount : -1;
  });
  console.log(`  dirty sections left: ${dirty}`);
  if (dirty === 0) break;
}
if (dirty !== 0) throw new Error(`mesh queue never drained (stuck at ${dirty})`);

// Sanity probes: the ground right under the camera and a far column
const probes = await page.evaluate(() => {
  const q = self.__eaglercraftGame.queryBlock;
  return {
    underFeet: q(0, -50, 0),
    farGround: q(80, q(80, -50, 80) === 0 ? -52 : -50, 80),
  };
});
console.log('probes:', JSON.stringify(probes));
if (probes.underFeet !== 9) throw new Error('spawn pad grass missing: ' + probes.underFeet);

const FACES = [
  { name: 'panorama_0', yaw: 0, pitch: 0 },     // +Z south
  { name: 'panorama_1', yaw: -90, pitch: 0 },   // +X east (morning sun)
  { name: 'panorama_2', yaw: 180, pitch: 0 },   // -Z north
  { name: 'panorama_3', yaw: 90, pitch: 0 },    // -X west
  { name: 'panorama_4', yaw: 0, pitch: 90 },    // -Y straight down
  { name: 'panorama_5', yaw: 0, pitch: -90 },   // +Y straight up
];

for (const f of FACES) {
  await page.evaluate(({ y, p }) => {
    self.__eaglercraftGame.setLook(y, p);
  }, { y: f.yaw, p: f.pitch });
  await page.waitForTimeout(1_400); // frames with the new orientation
  const shot = await page.screenshot(); // the game canvas fills the viewport
  const out = join(OUT_DIR, `${f.name}.png`);
  await writeFile(out, shot);
  console.log(`${f.name}: ${shot.length} bytes`);
}

console.log('page errors:', errors.length ? errors.slice(0, 6) : 'none');
await browser.close();
server.close();
process.exit(0);
