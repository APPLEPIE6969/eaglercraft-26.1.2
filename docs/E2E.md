# End-to-End Tests

The EaglerCraftX 26.1.2 client is a TeaVM-compiled JavaScript bundle that boots
inside a browser canvas. A Gradle build can succeed and still ship a client
that throws on the first frame, fails to create a WebGL2 context, or silently
falls back to a stub bundle. The Playwright suite in `tests/e2e/` closes that
gap by loading the real client in headless Chromium and asserting on what
actually happens in the page.

## Layout

```
tests/e2e/
  server.mjs             Dependency-free static file server (Node built-ins only)
  resolve-client-dir.sh  Finds index.html inside a downloaded CI artifact
  env.ts                 Environment helpers (E2E_STRICT_GAME)
  support.ts             Error collector, ready-state probe, helpers
  assets.spec.ts         Static asset contract (content types, 404, client config)
  bundle.spec.ts         Blocking checks on the published classes.js bundle
  shell.spec.ts          Blocking checks on the page shell
  webgl.spec.ts          Blocking checks on WebGL2 availability
  game.spec.ts           Informational checks on the running game
playwright.config.ts     Playwright configuration
```

## What is asserted

### Blocking (always fail the build)

| Check | Where | Why it matters |
|-------|-------|----------------|
| `classes.js` is a real TeaVM bundle, not a stub | `bundle.spec.ts` | TeaVM writes a tiny placeholder before a failed compile. A stub that reaches `public/` produces a client that cannot start. |
| `classes.js` is served over HTTP | `bundle.spec.ts` | The published client must be reachable at the path `index.html` references. |
| The server does not serve files outside the client directory | `bundle.spec.ts` | Path traversal has to fail closed. |
| `robots.txt` and `logo.svg` are served with the right content type | `assets.spec.ts` | Part of the published site contract. |
| Unknown paths return 404 | `assets.spec.ts` | A missing asset must not be masked by a catch-all route. |
| `window.eaglercraftXOpts` is well formed | `assets.spec.ts` | The client reads its container, asset paths and server list from this object. |
| The bundle exports `$rt_exports.main` | `bundle.spec.ts` | Confirms the bundle was compiled with the expected entry point rather than truncated. |
| `self.main` is a function at runtime | `bundle.spec.ts` | The bundle must actually attach the entry point to the global scope. |
| The "Failed to load classes.js" overlay never appears | `bundle.spec.ts` | Catches a missing or mistyped `classes.js` reference in `index.html`. |
| No uncaught shell errors | `bundle.spec.ts` | Errors raised outside the game bundle are startup bugs. |
| Chromium can create a WebGL2 context | `webgl.spec.ts` | WebGL 2.0 is a hard requirement of the renderer. |
| `#game_frame` exists and has a WebGL context | `webgl.spec.ts` | The canvas must be backed by a graphics context, not a dead element. |

### Informational (only fail with `E2E_STRICT_GAME=1`)

| Check | Where |
|-------|-------|
| The client reaches the title screen | `game.spec.ts` |
| `assets.epk` is served | `game.spec.ts` |
| No uncaught errors from the game runtime | `game.spec.ts` |

The TeaVM port of Minecraft 26.1.2 is still in progress, so the game-level
behaviour is reported rather than enforced by default. The counts are printed
to the console so a trend is visible in the CI log, and a hard failure can be
turned on per run:

```yaml
with:
  strict_game: true
```

## Local usage

The suite needs Node 20 or newer and a Chromium build:

```bash
npm ci
npx playwright install --with-deps chromium
npm run e2e
```

Useful environment variables:

| Variable | Default | Meaning |
|----------|---------|---------|
| `E2E_PUBLIC_DIR` | `public` | Directory that holds the client to serve |
| `E2E_PORT` | `4173` | Port for the static server |
| `E2E_HOST` | `127.0.0.1` | Host for the static server |
| `E2E_STRICT_GAME` | `0` | Set to `1` to make the game assertions blocking |

To serve a different build:

```bash
E2E_PUBLIC_DIR=path/to/built/public npm run e2e
```

The HTML report is written to `playwright-report/` and can be opened with
`npm run e2e:report`.

## CI wiring

### `.github/workflows/e2e.yml`

A reusable workflow (`workflow_call`) plus a manual `workflow_dispatch` entry
point. It:

1. Checks out the repository.
2. Sets up Node.js (default 24).
3. Downloads the built client artifact when `artifact_name` is supplied.
4. Resolves the client directory with `tests/e2e/resolve-client-dir.sh`.
5. Installs the test toolchain and Playwright Chromium with system
   dependencies.
6. Runs `npx playwright test` against the client.
7. Always uploads the HTML report, screenshots and traces as artifacts, even
   when the suite fails.

### `ci.yml` (main branch)

The workflow is split into three jobs:

```
build  ──▶  e2e  ──▶  deploy
```

The `deploy` job depends on **both** `build` and `e2e`, so a browser
regression blocks the GitHub Pages publish. The client is never pushed to
production without having run in a real browser first.

### `pr-preview.yml` (pull requests)

Pull requests build a preview client and run the same reusable workflow
against it. The preview step also refuses to overwrite `public/classes.js`
with a TeaVM stub, so a broken compile cannot masquerade as a preview.

## Regenerating the lockfile

`package-lock.json` is committed so CI installs exact dependency versions with
`npm ci`. Regenerate it on a GitHub runner instead of locally:

1. Run the **Update package-lock.json** workflow (`update-lockfile.yml`).
2. Download the `package-lock` artifact.
3. Commit the new `package-lock.json` to `main`.

The workflow also runs automatically when `package.json` changes on `main`.

## Troubleshooting

**`classes.js` fails the size check.** The TeaVM compile step did not produce
a real bundle. Check the `teavm-log` artifact from the same run.

**WebGL2 is unavailable in CI.** Headless Chromium has no GPU, so the suite
runs with SwiftShader (`--enable-unsafe-swiftshader`,
`--use-angle=swiftshader`). If the flags regress, the `webgl.spec.ts` checks
are the first thing that fails.

**The static server returns 403.** The requested path tried to escape the
served root. `tests/e2e/server.mjs` rejects those requests on purpose.

**A test times out.** The default per-test timeout is 90 seconds and the
navigation timeout is 45 seconds. The TeaVM bundle is CPU heavy, so the suite
runs with a single worker to keep timings predictable.
