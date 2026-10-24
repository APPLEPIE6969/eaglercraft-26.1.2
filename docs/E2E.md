# End-to-end browser tests

The web client in `public/` is the EaglerCraftX shell plus the TeaVM compiled
`classes.js`. This suite drives that client in a real Chromium browser so a
broken bundle, a broken shell or a missing graphics backend fails the build
before the client is published.

## What runs where

| Workflow | File | Trigger |
| --- | --- | --- |
| `E2E Browser Tests` | `.github/workflows/e2e.yml` | `workflow_call` and `workflow_dispatch` |
| `Build & Deploy` | `.github/workflows/ci.yml` | calls the reusable workflow after the client is built, before the Pages deploy |
| `PR Preview` | `.github/workflows/pr-preview.yml` | calls the reusable workflow for pull requests |

The reusable workflow checks the repository out, sets up Node.js, downloads the
client artifact that the build job produced, installs Playwright Chromium with
its system dependencies, runs the suite and always uploads the HTML report,
screenshots and traces as artifacts.

Because the deploy job depends on the browser tests, a client that no longer
boots is never published.

## Test files

| File | Covers |
| --- | --- |
| `tests/e2e/bundle.spec.ts` | The published bundle is served over HTTP, is a full TeaVM build instead of a stub, and the server does not leak files outside the client directory. |
| `tests/e2e/assets.spec.ts` | Static asset contract: metadata files, content types, 404 behaviour. |
| `tests/e2e/shell.spec.ts` | The shell exposes its elements, `self.main` is exported, the client configuration is well formed, no failed bundle download is reported, no uncaught shell errors, screenshot of the running client. |
| `tests/e2e/webgl.spec.ts` | Chromium can create a WebGL2 context and the game canvas is backed by one. |
| `tests/e2e/game.spec.ts` | The client reaches the title screen, the EPK archive is served, and no uncaught game runtime errors occur. |
| `tests/e2e/ingame.spec.ts` | The full "go in game" journey on a fresh page: loading screen → bundle boot → title screen → engine milestones (WebGL2 context, `EaglerCraft.initialize()` complete, Minecraft instance constructed, EPK parsed) → the game loop keeps rendering. Takes a milestone screenshot at every stage and attaches a session summary. Also runs the two capability-gated world-entry stages described below (bridge `enterWorld()` and the real Singleplayer click). |
| `tests/e2e/multiplayer.spec.ts` | Multiplayer gateway smoke test: a real browser performs the Eagler WS handshake (0x01), login (0x03 → 0x04 status 0), and a chat round-trip (0x05) through `GatewaySmokeMain` → `McBridge` → `McProtocolStub`, plus a negative login case. Skipped automatically when the gateway jars have not been built. |

### Blocking versus informational assertions

The MC 26.1.2 port is still in progress, so the game level assertions are
informational by default and only become hard failures when
`E2E_STRICT_GAME=1` is set:

- the client reaching the title screen
- `assets.epk` being served
- the absence of uncaught errors from the TeaVM bundle

Everything else (shell loads, the bundle is a real TeaVM build, `self.main` is
exported, WebGL2 is obtainable, no uncaught shell errors) always fails the run.

The `ingame.spec.ts` assertions are always blocking: the engine milestones it
checks (Minecraft instance constructed, EPK parsed, the loop rendering at a
positive FPS) are the client's own boot log lines, so a regression there means
the client really did stop "going in game".

Set `E2E_STRICT_GAME` to `true` on the reusable workflow input to turn the
informational assertions into hard failures.

### World-entry stages (capability-gated)

The final stage of the journey — Singleplayer → world → HUD — is
implemented in the client: `TitleScreen` carries a real `Button` widget
with hit-testing, a press loads a minimal `ClientLevel` through
`Minecraft.doWorldLoad`, and the HUD (crosshair + hotbar) renders through
the batched 2D pipeline. `ingame.spec.ts` runs two gated tests over that
path, both reading the `window.__eaglercraftGame` telemetry bridge that
`EaglerCraft.java` pushes at the end of initialization, once per game
logic tick (20 TPS) and immediately on crash:

```json
{
  "canEnterWorld": true,
  "gameState": 2,
  "screen": "net.minecraft.client.gui.screens.TitleScreen",
  "inWorld": false,
  "ticks": 42,
  "guiScale": 3,
  "buttons": [{ "id": "singleplayer", "label": "singleplayer", "active": true, "x": 113, "y": 108, "w": 200, "h": 20 }]
}
```

The `buttons` array carries every widget with a telemetry id: menu buttons
(id derived from the message, e.g. `playselectedworld`) and custom widgets
like the world-list row of the Select World screen (`newworld`). `active`
mirrors the vanilla widget state — a disabled button reports `false` and
ignores clicks.

1. **Bridge path** (`enters a world and reaches the HUD`): when
   `canEnterWorld` is `true`, the bridge must also expose an
   `enterWorld()` function; calling it queues a request that the next
   game tick feeds through the same `doWorldLoad` path the button drives.
   The test then requires `inWorld` to flip within 30 seconds, no uncaught
   page errors, and captures a `06-in-world-hud` screenshot.

2. **Real menu path** (`clicks the real Singleplayer button and reaches
   the HUD`): the bridge is used only to *locate* widgets — the test
   converts their GUI-pixel bounds to viewport coordinates via `guiScale`
   and the canvas' backing/CSS ratio, then performs genuine trusted
   `page.mouse.click` calls, no synthetic events. The journey is the
   vanilla one: click `singleplayer` → the Select World screen must open
   (`06-select-world-screen` screenshot) → click the `newworld` row (which
   must enable `playselectedworld`) → click **Play Selected World**.
   `inWorld` must flip, the screen must close (`screen: "in-game"`), and a
   `07-in-world-hud-click` screenshot is captured.

Both tests **skip with evidence** while `canEnterWorld` is `false` (or the
bundle predates the bridge): a `05-world-entry-gate` /
`05-singleplayer-menu` screenshot, a `game-telemetry.json` capture and a
`session-summary.json` explaining the gate reason land in the report and
the `test-results/` artifact. The client-side flag lives in
`EaglerCraft.canEnterWorld()`.

## Gameplay, options and multiplayer (`features.spec.ts`)

The gameplay milestone adds an in-world snapshot to every telemetry push —
`gameplay` (null outside a world):

```json
{
  "creative": false,
  "edits": 2,
  "spawn": [0.5, -60, 0.5],
  "player": { "x": 0.5, "y": -60, "z": 0.5, "yaw": 0, "pitch": 0,
               "onGround": true, "flying": false, "crouching": false },
  "hotbar": { "selected": 0, "slots": [[9, 1], null, null, null, null, null, null, null, null] },
  "hit": { "x": 0, "y": -61, "z": 0, "face": "up" },
  "destroy": { "stage": 3, "x": 0, "y": -61, "z": 0 }
}
```

The bridge also gains functions: `queryBlock(x, y, z)` (synchronous block
id, `-1` outside a world), `setHotbarSlot(slot, id, count)` /
`selectSlot(slot)` (hotbar requests consumed on the next tick) and
`setLook(yaw, pitch)` (absolute camera orientation — the automation
stand-in for a mouse look). The three `features.spec.ts` tests gate on the
presence of `queryBlock` + `setLook` and skip with a screenshot on older
bundles:

1. **Movement physics, jump and hotbar keys**: `enterWorld()` → assert the
   vanilla flat-preset spawn (player feet at `y=-60` on grass, bedrock /
   dirt / dirt / grass layers via `queryBlock`) → hold `W` (real keyboard
   events) and require the walk distance from vanilla ground physics →
   `Space` jump apex + gravity landing → `Digit5` / scroll wheel hotbar
   selection.
2. **Survival block breaking and placing**: aim straight down with
   `setLook(0, 90)` → click the canvas (vanilla click-to-grab pointer
   lock) → hold the left button and require both the destroy overlay
   stages (`destroy.stage`) and the block actually breaking → the mined
   grass must appear in the hotbar → `setLook(0, 30)` + right-click must
   place it back on the ground within reach.
3. **Options sub-screens and the multiplayer gateway**: the title Options
   button opens the grid with **Controls** and **Sound Settings** live →
   Controls carries the toggle rows + sprint-window slider and opens **Key
   Binds** → the rebind journey clicks the "Walk Forwards" row's key
   button, presses `K` and verifies `options.keybinds` persisted the scan
   code → Sound Settings carries the Master + category sliders and the
   master volume persists → the **Multiplayer** browser carries the
   vanilla footer buttons, the Add Server dialog types name + address
   into the EditBoxes and persists `serverlist`, and joining
   `localhost:1` walks ConnectScreen → the honest DisconnectedScreen.

## Screenshots

`ingame.spec.ts` captures the journey as milestone screenshots
(`01-loading-screen`, `02-bundle-booted`, `03-title-screen`,
`04-running-in-game`, plus `05-world-entry-gate` / `06-in-world-hud` from
the bridge test and `05-singleplayer-menu` / `06-select-world-screen` /
`07-in-world-hud-click` from the Singleplayer click test), and
`shell.spec.ts` captures the shared client
page as `shell-client`. Each screenshot is attached to the HTML report under its
milestone name and also written to the `screenshots/` directory (gitignored
locally, uploaded as a CI artifact next to the Playwright report). When a run
fails, these files show exactly how far the client got before it died.

`ingame.spec.ts` also attaches a `session-summary.json` with the relative
timestamps of the boot milestones, the parsed EPK size/entry count, the FPS
samples, the `__eaglercraftGame` telemetry snapshot and any uncaught page
errors. It is attached to the report *and* written into the test's output
directory under `test-results/`, because folder-layout HTML reports do not
persist text attachments — the copy in `test-results/` is what survives in
the CI artifact.

## Why the client page is shared

Booting the client evaluates the whole TeaVM bundle and blocks the browser main
thread while it does so. That takes about a minute per navigation, so the
suite boots the client once per worker and shares that page between the tests
(`tests/e2e/fixtures.ts`). Assertions that only need the HTTP surface use the
`request` fixture and never open a page at all.

The bootstrap wait is driven from Node instead of `page.waitForFunction`
(`waitForClientBootstrap` in `tests/e2e/support.ts`). A page side predicate
cannot be evaluated while the main thread is blocked, so a page side timeout
would expire during the very block it is waiting for.

## Running the suite locally

```bash
npm ci
npx playwright install chromium
npm run e2e
```

The suite serves `public/` with the dependency free static server in
`tests/e2e/server.mjs` and drives it through Playwright. Point it at a build
output with `E2E_PUBLIC_DIR`:

```bash
E2E_PUBLIC_DIR=build/out npm run e2e
```

Useful environment variables:

| Variable | Meaning |
| --- | --- |
| `E2E_PUBLIC_DIR` | Directory that holds the client. Defaults to `public`. |
| `E2E_STRICT_GAME` | `1` turns the informational game assertions into hard failures. |
| `E2E_PORT` / `E2E_HOST` | Address of the static server. Defaults to `127.0.0.1:4173`. |
| `E2E_BROWSER_CHANNEL` | Playwright browser channel, for example `chrome` to use a system browser instead of the bundled Chromium. |

The report is written to `playwright-report/` and can be opened with:

```bash
npm run e2e:report
```

## Troubleshooting

A run that fails while the client is still booting usually means the bundle
changed shape, not that the browser is at fault. The Playwright report
contains the trace, the console output and the screenshot of the failing
state. Upload it from the `Playwright browser tests` job and open the HTML
report locally.
