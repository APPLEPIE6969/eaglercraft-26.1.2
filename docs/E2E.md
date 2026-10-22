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
| `tests/e2e/ingame.spec.ts` | The full "go in game" journey on a fresh page: loading screen → bundle boot → title screen → engine milestones (WebGL2 context, `EaglerCraft.initialize()` complete, Minecraft instance constructed, EPK parsed) → the game loop keeps rendering. Takes a milestone screenshot at every stage and attaches a session summary. Also runs the capability-gated world-entry stage described below. |
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

### World-entry stage (capability-gated)

The final stage of the journey — Singleplayer → world → HUD — cannot be
automated until the client actually renders that path (today `TitleScreen` has
no menu wiring, `WorldLoader` is a stub and the HUD is a no-op). Instead of
waiting, `ingame.spec.ts` carries a capability-gated test that is already
wired for the day the path lands:

1. The test boots the client to the running title screen, then reads the
   `window.__eaglercraftGame` telemetry bridge that `EaglerCraft.java` pushes
   at the end of initialization, once per game logic tick (20 TPS) and
   immediately on crash:

   ```json
   {
     "canEnterWorld": false,
     "gameState": 2,
     "screen": "net.minecraft.client.gui.screens.TitleScreen",
     "inWorld": false,
     "ticks": 42
   }
   ```

2. If the bridge is missing (a bundle older than the bridge) or
   `canEnterWorld` is `false`, the test **skips with evidence**: a
   `05-world-entry-gate` screenshot, a `game-telemetry.json` capture and a
   `session-summary.json` explaining the gate reason land in the report and
   the `test-results/` artifact.

3. The moment the client reports `canEnterWorld: true`, the test activates
   and enforces the world-entry **contract**: the bridge must also expose
   `enterWorld()` (resolving once the level is loaded and the HUD is up),
   the bridge must flip `inWorld` to `true` within 30 seconds, the
   transition must not throw uncaught page errors, and a `06-in-world-hud`
   screenshot is captured. The client-side flip lives in
   `EaglerCraft.canEnterWorld()`.

So the test is pre-armed: no redesign is needed when the menu wiring lands —
the client flip alone turns the skip into a real end-to-end assertion.

## Screenshots

`ingame.spec.ts` captures the journey as milestone screenshots
(`01-loading-screen`, `02-bundle-booted`, `03-title-screen`,
`04-running-in-game`, and `05-world-entry-gate` from the capability-gated
world-entry test), and `shell.spec.ts` captures the shared client page as
`shell-client`. Each screenshot is attached to the HTML report under its
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
