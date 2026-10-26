// Renders the title-screen panorama cubemap faces with the port's own
// world renderer, replacing the soft 256x256 1.8.9 faces with a crisp
// 768x768 scene of the flat world.
//
// Face convention (docs/UI-FIDELITY.md, from vanilla CubeMapTexture):
//   GL faces [+X,-X,+Y,-Y,+Z,-Z] <- panorama_[1,3,5,4,0,2].png
// so we render:
//   panorama_0 = +Z (yaw 0)   panorama_1 = +X (yaw -90)
//   panorama_2 = -Z (yaw 180) panorama_3 = -X (yaw 90)
//   panorama_4 = -Y (pitch +90, straight down)
//   panorama_5 = +Y (pitch -90, straight up)
// Side faces need the exact 90-degree cube-face projection: square canvas,
// fov 90, perspective -> horizontal FOV = vertical FOV = 90.
//
// Scene: the flat world's grass plain with a ring of oak trees around
// spawn, morning light. The procedural base only meshes DIRTIED sections,
// so the script no-op-edits one grass block per section in the view radius
// (same-id setBlockState) to make the mesher build the ground out to the
// fog line.
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
const VIEW_RADIUS = 6; // sections (-6..6) = 13x13 ground to the fog line

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

// Enter the flat world
await page.evaluate(() => self.__eaglercraftGame.enterWorld());
await page.waitForFunction(() => {
  const t = self.__eaglercraftGame;
  return t && t.inWorld && t.gameplay && t.gameplay.player && t.gameplay.player.onGround;
}, undefined, { timeout: 45_000 });
console.log('in world');

// Probe the grass layer: topmost solid under the player.
const grassY = await page.evaluate(() => {
  const q = self.__eaglercraftGame.queryBlock;
  for (let y = -58; y >= -64; y--) {
    if (q(0, y, 0)) return y;
  }
  return null;
});
if (grassY === null) throw new Error('no solid ground under spawn');
const GRASS_ID = await page.evaluate(y => self.__eaglercraftGame.queryBlock(0, y, 0), grassY);
console.log(`grass layer y=${grassY} id=${GRASS_ID}`);

// Sculpt: ring of oak trees + no-op grass edits to dirty every ground
// section in the view radius. Oak log 351, leaves 359 (the 26.1.2 palette).
const TREES = [];
const treeAt = (tx, tz, h) => {
  const base = grassY + 1;
  for (let i = 0; i < h; i++) TREES.push([tx, base + i, tz, 351]);
  const top = base + h - 1;
  for (let ly = top - 2; ly <= top - 1; ly++) {
    for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) {
      if (Math.abs(dx) === 2 && Math.abs(dz) === 2) continue; // vanilla corner trim
      if (dx === 0 && dz === 0) continue; // trunk
      TREES.push([tx + dx, ly, tz + dz, 359]);
    }
  }
  for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
    if (Math.abs(dx) + Math.abs(dz) > 1) continue; // plus cap
    TREES.push([tx + dx, top, tz + dz, 359]);
    TREES.push([tx + dx, top + 1, tz + dz, 359]);
  }
};
// 8 trees, irregular ring 7-15 blocks out, spaced from each other.
treeAt(7, 3, 4); treeAt(-9, 5, 5); treeAt(4, -11, 4); treeAt(-6, -8, 6);
treeAt(13, -4, 5); treeAt(-14, -2, 4); treeAt(2, 14, 5); treeAt(-3, 16, 4);

// No-op same-id edits: one per ground section -> marks it dirty so the
// mesher builds the procedural base out to the fog line.
const dirtyEdits = [];
for (let sx = -VIEW_RADIUS; sx <= VIEW_RADIUS; sx++) {
  for (let sz = -VIEW_RADIUS; sz <= VIEW_RADIUS; sz++) {
    dirtyEdits.push([sx * 16 + 8, grassY, sz * 16 + 8, GRASS_ID]);
  }
}

await page.evaluate(({ trees, dirty, t }) => {
  if (t !== null) self.__eaglercraftGame.setDayTime(t);
  self.__eaglercraftGame.setScene({ blocks: [...trees, ...dirty], fov: 90, hideHud: true });
}, { trees: TREES, dirty: dirtyEdits, t: DAY_TIME });
console.log(`scene set: ${TREES.length} tree blocks + ${dirtyEdits.length} section dirties`);

// Let the budgeted mesh rebuilds chew through ~200 sections, then settle
await page.waitForTimeout(9_000);

// Sanity: a tree block must exist away from spawn
const probe = await page.evaluate(() => self.__eaglercraftGame.queryBlock(7, -58, 3));
console.log('tree probe (7,-58,3) =', probe, '(expect 351)');

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
