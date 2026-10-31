# Worklog — eaglercraft-26.1.2

> NOTE: the sandbox was wiped between sessions; this file was recreated from the
> conversation summary. The repo was re-cloned from origin (token-authenticated).

---
Task ID: 1 (recovered)
Agent: main
Task: assets.epk pipeline (EPKCompiler + build script + deployment)

Work Log:
- Reverse-engineered the EPK format (magic "EAGPKG!"+NUL 8 bytes, u16 version=2,
  u16 flags, u32 fileCount, u32 reserved; entries: u16 nameLen + utf8 + u32 len + data, LE)
- Implemented EPKCompiler (deterministic: entries sorted by path, CRC32, no timestamps)
  with `--verify file.epk [sourceDir]` round-trip check
- scripts/build_assets_epk.sh stages sources/resources -> manifest -> compile -> verify
- Deployed public/assets.epk (322 B, 2 entries) + download/assets.epk

Stage Summary:
- assets.epk pipeline complete; e2e green at the time (19/19)

---
Task ID: 2 (recovered)
Agent: main
Task: push milestone (ci.yml trigger, gateway skeletons, EPKLoader wiring)

Work Log:
- Proved the ci.yml "corruption" (branches: ain]) was a display artifact of the
  session tool output (the literal sequence "[m" is eaten in output). GitHub API
  blob f900195... matches local HEAD; git log -S finds no commit ever containing
  the broken string. NO fix was needed.
- Recovered Bungee/Velocity/PlaceholderServer gateway sources from the worktree;
  fixed .gitignore (bare src/ rule + scripts/ rule); fixed two Gradle 9 shadow
  validation errors (startScripts/shadowJar input declaration, classifier-less
  jar/shadowJar path collision -> slim classifier)
- Wired EPKLoader into ClientMain boot path (beginAssetBundleLoad -> loadEPK)
- Pushed 4 commits: 2ef92ce (title screen), 34814c1 (gateway), d0d8028 (teavm
  local build), a979dbd (EPK boot wiring). main = a979dbd, API-verified.

Stage Summary:
- main pushed with token account; token configured in git remote URL (not committed)

---
Task ID: 3
Agent: main
Task: (b) gateway milestone #2 - forward Eagler players into proxy server registry

Work Log:
- Re-cloned repo after sandbox wipe; verified main = a979dbd
- Design: extend wire protocol with 0x03 login / 0x04 login-result / 0x05 chat;
  shared pure-JDK MC-protocol TCP bridge (mc/ package in PlaceholderServer module);
  Bungee: EaglerPlayerSession implements ProxiedPlayer + EaglerSessionRegistry +
  reflection registration into proxy player set + PostLoginEvent; Velocity: wire
  parity with its own registry; GatewaySmokeMain standalone harness for e2e

Stage Summary:
- in progress

---
---
Task ID: 3 (completed)
Agent: main
Task: gateway milestone #2 + multiplayer e2e smoke + complete assets.epk

Work Log:
- Reviewed the in-progress milestone #2 worktree (12 new files, ~800 insertions):
  EaglerPlayerSession (full ProxiedPlayer), EaglerSessionRegistry,
  EaglerProxyAccess, GatewaySmokeMain, VelocityEaglerSession/Registry,
  mc/ package (McBridge/McProtocolStub/McVarInt/McNbt/McPacketIds)
- Fixed the login/chat race: 0x04 status 0 is now sent only after the backend
  login completes (unreachable backend -> status 3 + teardown); chat arriving
  while the bridge connects is buffered (64 frames) and flushed on CONNECTED.
  Applied to BOTH Bungee and Velocity for parity
- Added setReuseAddr(true) to both WS listeners (restarts re-bind through
  TIME_WAIT; was breaking e2e reruns)
- Built all 3 gateway jars (shadow, JDK26 @ tools/jdk26); standalone smoke
  client (scripts/gw_smoke_client.mjs) verified: handshake, login, chat echo
  through bridge, ping/pong, invalid username -> status 1, dead backend -> 3
- multiplayer.spec.ts (task c): browser-driven smoke via GatewaySmokeMain +
  McProtocolStub; fixed two spec bugs (echo consumed by the wait loop ->
  return it from the evaluate; rejection test resolved on the handshake
  accept instead of the login result). Full suite 21/21 green
- Task (a): MC 26.1.2 client jar downloaded from piston-meta (38 MB, real
  version exists); all 10,462 vanilla asset entries staged + fork lang stub
  + CRC32 manifest -> EPKCompiler -> public/assets.epk = 7,470,553 B,
  10,464 entries, round-trip verified. Sounds (436 MB asset index) deferred
  to on-demand loading. Full TeaVM compile stays on the CI runner (12 GB
  heap; sandbox has 3 GB)
- Committed 8444121 (gateway milestone 2) + 4de3e80 (complete assets.epk),
  pushed main a979dbd..4de3e80, API-verified 200

Stage Summary:
- main = 4de3e80; e2e 21/21; EPK now carries the complete vanilla asset set;
  TeaVM full build + deploy happen on the next CI run of the pushed commit

---
Task ID: 4
Agent: main
Task: improve the Playwright e2e suite — milestone screenshots + "go in game" verification

Work Log:
- Probed the deployed client empirically first (scripts/probe_client.mjs): boot ~2s,
  MC instance constructs ("Minecraft instance created!"), EPK parsed (7,470,553 B,
  10,464 entries), title overlay + FPS counter live at ~60 FPS, canvas black
  (renderFrame only clears when mc.tick succeeds; no GUI render yet) — so the honest
  "in game" gate for the current build is the engine-running milestone chain
- tests/e2e/support.ts: added installConsoleRecorder (page console capture),
  readGameSessionState (loading/title/crash/fps DOM state), parseFps +
  waitForFpsReading (rAF counter publishes ~1s after title screen — poll, don't
  assert instantly), attachMilestoneScreenshot (saves screenshots/<name>.png +
  attaches to the HTML report)
- tests/e2e/ingame.spec.ts (new): fresh page, full journey with hard asserts —
  loading screen -> self.main exported -> title screen (no crash overlay) ->
  console milestones (WebGL2: true, === Initialization complete ===,
  Minecraft instance created!, EPK bytes/entries > 0) -> FPS > 0 twice 2.5s apart
  -> zero uncaught page errors; attaches 01..04 milestone PNGs + session-summary.json
- shell.spec.ts screenshot routed through the helper; .gitignore + e2e.yml upload
  gained screenshots/; docs/E2E.md documents the new spec + screenshots section
- Local restore niggles: sandbox had Chromium 1200 vs Playwright wanting 1248
  (npx playwright install chromium fixed it); 49 mode-bit flips restored to 644
- Suite locally: 22/22 green (was 21 tests, now +1), ingame spec stable 3/3 runs
- Rebased on origin/main (previous session's 580dcb0 + 2b36a6d were missing from
  the restored worktree), pushed 7d359a5

Stage Summary:
- main = 7d359a5; e2e 22/22 local; CI run in flight for the new spec
- Next obvious upgrade when the client gains a real GUI/world path: extend
  ingame.spec.ts with a click-Singleplayer -> world-load -> HUD milestone (the
  spec's fresh-page structure already supports adding stage 6), and/or add a
  window.__eaglercraftGame telemetry bridge in EaglerCraft.java for direct
  state asserts instead of console markers

---
Task ID: 4 (completed)
Agent: main
Task: CI verification for the improved e2e suite

Work Log:
- CI run 39768371107 for 7d359a5: build ✓ (TeaVM recompiled, "compilation
  succeeded"), e2e ✓ 20 passed in 9.5s — ingame.spec.ts ran and passed on CI
  (multiplayer pair auto-skipped there: gateway jars are local-only), deploy ✓
- playwright-report artifact (798 KB) carries the embedded milestone screenshots
  + session-summary.json; live site re-verified serving classes.js + assets.epk

Stage Summary:
- main = 7d359a5, CI fully green, site live
- Suite now proves the client "goes in game" to the current build's ceiling:
  boot -> title screen -> Minecraft instance constructed -> EPK parsed ->
  render loop at positive FPS, with screenshots at every stage

---
Task ID: 5 (completed)
Agent: main
Task: Open the playwright-report artifact from CI run 39768371107

Work Log:
- Downloaded artifact 12420499892 (playwright-report-39768371107-1, 798 KB) via
  GitHub API (gh CLI unavailable; token from git remote URL, never echoed)
- Extracted: screenshots/ (5 PNGs), test-results/junit.xml, playwright-report/
  folder layout (hash-named PNG attachments, no session-summary.json persisted)
- junit.xml: 20 passed / 2 skipped (multiplayer, by design) — ingame.spec.ts
  "boots the client and reaches the running game" passed in 5.125s
- Visually verified all four milestone screenshots (loading bar advancing,
  title screen, running state with 61 FPS overlay)
- Copied 5 screenshots + junit.xml to
  /home/z/my-project/download/e2e-screenshots-run-39768371107/

Stage Summary:
- Artifact evidence confirms the new e2e suite works end-to-end on CI
- Note: text attachments (session-summary.json) don't persist in folder-layout
  HTML reports uploaded as artifacts — if we want it in the artifact, write it
  via testInfo.attach with a PNG-style binary path or save to test-results/

---
Task ID: 6 (completed)
Agent: main
Task: Push the pending drawSprite fix (token provided), verify CI, and finish
the sprite-pipeline + SelectWorldScreen milestone visuals

Work Log:
- Sandbox had reset again: HEAD back at 7d359a5, all local commits gone.
  Remote had everything except the unpushed one-liner. Stashed mode-bit
  noise, set core.fileMode=false, updated remote token, fetched, ff to 83e6355
- Recreated the drawSprite corner-coords fix as 8cd7beb, pushed, CI green
  (run 39875639566). Verified bundle + junit 24/0 + screenshots: crosshair/
  hotbar/title buttons now render real vanilla sprites correctly
- CI screenshots exposed one remaining artifact: a 48x48 blue square at the
  top-left of Select World + near-black screen elsewhere
- 12-round browser GL probe (custom Playwright harness, GL wrappers +
  readPixels-after-each-draw): program GUI ✓, geometry sane ✓, texture content
  uniform (0,0,0,64) ✓, blend SRC_ALPHA/ONE_MINUS_SRC_ALPHA ✓, 1 clear/rAF ✓,
  no FBO ✓ — yet x>=48 ended ~20 blend layers dark
- Log-sampled drawArrays bisect on a fresh VAO showed layers accumulating
  ~1 per few quads -> giant overlapping rects
- ROOT CAUSE: drawSpriteTiled fed the raw destination offset (tx - x) into
  the source UV phase without wrapping: su0 grew +1 per tile column, wrap
  branch's right edge (tx + (su1-1)*twF) grew unbounded, stretching every
  later tile across the rest of the screen. Only tile 0 rendered correctly
  (= the blue square)
- FIX (7dfa5a5): vanilla GuiSpriteScaling.Tiled semantics — source phase
  floorMod'ed into one sprite period, dest = clipped tile rect, u/v windows
  split at the texture seam (up to 2x2 sub-quads)
- Also scoped the DOM title banner to the title screen via the bridge's
  screen class (was only hidden in-world; it floated over Select World)
- CI on 7dfa5a5 green; screenshot showed correct layout but a light-blue
  backdrop (alpha-64 tiles over the sky clear color; vanilla blends them
  over the blurred panorama we don't render yet)
- FIX (1e2d9e7): opaque dark blue-gray base under the Select World backdrop
- CI green; refreshed public/classes.js from the 1e2d9e7 build (3e34461)
- Final run (3e34461): all three jobs green, deployed to Pages

Stage Summary:
- main = 3e34461, CI fully green (build + e2e 24/0 + deploy)
- Pixel-verified: title buttons 0x6F6F6F active body, sky 160/206/255,
  white crosshair, dark vanilla Select World (header 31,34,42 / list 17,19,24)
- Files: EaglerGuiImpl.java (drawSprite corners + drawSpriteTiled phase),
  SelectWorldScreen.java (dark base), public/index.html (overlay scoping),
  public/classes.js (refreshed)
- Probe harness kept at /home/z/my-project/scripts/probe_bluesquare.js for
  future GL debugging; token rotated by user mid-session (old one 401'd)

---
Task ID: 8 (completed)
Agent: main
Task: User reported "background still blurry" + "movement/camera half
inverted (W goes D)" — both traced to the never-deployed fix commit;
unblock its CI and ship it

Work Log:
- Sandbox had reset again (local back at 3e34461). Remote main carried a new
  commit 8d99dd0 (an earlier continuation's fix for exactly these complaints:
  LocalPlayer.turn axis swap, camera yaw+180, own-render 768x768 panorama,
  quad winding, tint scoping, atlas mipmap fix) — but its CI run
  40319355595 FAILED in the e2e job, so Deploy was skipped and production
  still served the pre-fix bundle. The user's "still" complaints were the
  undeployed state, not a bad fix.
- CI failure triage: input.spec.ts 3/3 GREEN (the inversion fix verified);
  sky + crafting failed once and passed on retry (flakes); the real blocker
  was features.spec.ts:467 'joining must show the ConnectScreen' failing 3x.
- Reproduced locally (had to npx playwright install chromium — reset wiped
  the headless shell; public/ bundle is committed so no toolchain rebuild
  was needed). Wrote scripts/probe_joinflow.mjs: seeds the serverlist
  (storage format is name\\u0001ip lines, NOT JSON — ServerList.java),
  walks Multiplayer -> row -> join with an 8ms in-page screen sampler.
- Ground truth: the client DOES pass through ConnectScreen
  (JoinMultiplayerScreen -> ConnectScreen ~1.4s -> DisconnectedScreen
  'the server closed the connection'). The spec's 200ms poll missed it
  under runner load because the loopback refusal can land between two
  telemetry pushes. Worse: the poll had been passing on the OLD bundle
  because a telemetry throw froze the rAF loop and kept the screen up —
  the assert was passing on a bug.
- FIX (219f3b3): the dead-address join now samples screen transitions
  in-page at 8ms around the click and asserts the recorded transitions
  contain ConnectScreen after the terminal DisconnectedScreen lands.
  Validated locally (4.5m, passed), then input.spec re-run (2 passed +
  1 retry-absorbed flake, same as CI).
- CI on 219f3b3 (run 40680642241): build green, full e2e suite green,
  Deploy green. Live bundle = CI's fresh TeaVM build (byte diff vs local
  is TeaVM parameter-name nondeterminism only).
- Production verified: booted https://applepie6969.github.io/eaglercraft-26.1.2
  headless — title screen with the crisp self-rendered panorama (trees,
  water), zero page errors (probe-out/live-title.png).

Stage Summary:
- main = 219f3b3, CI fully green, Pages deployed
- Both user complaints fixed in production: camera/movement conventions
  (W walks the look vector; mouse right turns right) and the crisp panorama
- Lesson: a passing poll assert can be masking a frozen-loop bug; transient
  screen states need in-page transition sampling, not 200ms polling
- scripts/probe_joinflow.mjs + probe_live_title.mjs kept locally (uncommitted)
- sandbox reset recovery note: npx playwright install chromium needed after
  resets; public/ bundle is committed so e2e runs without a gradle rebuild

## Task 9 - restore the real 26.x title panorama (2026-10-30)
- User: "we're working on Minecraft version 26.3 not 1.8 fix that". The
  panorama had been rewritten to the 1.8 method; the fork-original port
  (561ce4f~1) was already the modern 26.x method (CubeMap.render, fixed
  10 deg pitch, fov-85 raymarch over the six face PNGs, spin += delta *
  2.0 deg/s) with only an angle-unit bug.
- Fix: restored the original 345-line PanoramaRenderer + Math.toRadians
  at the single Java/GLSL boundary + rAF clock clamp (MAX_FRAME_DELTA_TICKS
  = 10, rejects negative/NaN background-tab deltas).
- Local probe: spin +2.003/+2.026 deg/s (vanilla 2.0), horizon row 323->323
  (zero bob), zero console errors. Bundle markers: panorama_faces +
  u_tanHalfFov in, u_f1/1.7320508 gone.
- Commit ed9395a (88f3fd8 source + bundle amend). CI run 41035247365:
  build green, e2e green (the two heavy specs passed on fresh runners,
  confirming the local-timeout judgment), Deploy green. Live site serves
  the new bundle (marker check).
- Lesson: sandbox reset ate 87f3b88 AND the worklog entry - commit source
  first, build second, amend, push immediately.

## Task 10 - vanilla face geometry + fixed 20 TPS from the 26.1.2 decompile (2026-10-30)
- User: "when you look at the side of blocks theyre invisible (remember
  its minecraft version 26.x) get the actual logic of the real minecraft,
  decompile it, also the walking is way too fast, get the real
  decompilation".
- Ground truth: 26.1.2 client jar (SHA-verified from the Mojang manifest,
  same jar the build script uses) decompiled with Vineflower 1.10.1 -
  26.1.2 ships UNOBFUFCATED so the decompile has Mojang's own names.
  Excerpts committed under docs/decompiled-26.1.2-reference/ (FaceInfo,
  FaceBakery, CuboidFace, ModelBlockRenderer, SectionCompiler,
  LivingEntity, LocalPlayer, KeyboardInput...).
- Bug 1 (invisible sides): the mesher's hand-authored corner tables were
  double-negated with the reversed QUAD_INDEX_ORDER (0,2,1)(0,3,2) -
  cross-product shows only UP/DOWN/WEST survived, NORTH/SOUTH computed
  inward (culled) and EAST was a copy of the NORTH quad on the wrong
  plane (+X face never existed). Fix: vanilla FaceInfo corners + (0,1,2)
  (2,3,0) order + vanilla UV projection - every face emits outward-CCW.
  The cull rule already matched Block.shouldRenderFace and is unchanged.
- Bug 2 (walking too fast): USE_FIXED_TIMESTEP=false ran one full MC tick
  per rAF frame (60-144 TPS vs vanilla 20). The decompiled travelInAir
  math (friction 0.6*0.91, accel 0.21600002/blockFriction^3, 0.98 input
  scale) was already in the port; enabling the fixed-20-TPS accumulator
  path restores 4.317 b/s walk / 5.612 b/s sprint. Camera got the vanilla
  partialTick lerp (xo..x), GUI/panorama got the right clocks.
- Commit 05dae78 (source 19d5a2b + bundle amend). Local probe: walk
  9.06 blocks / 42 ticks = 0.2159 b/tick (vanilla), four horizons at
  0.6-0.8% black (voids gone), east view shows full dirt cross-sections.
- CI run 41130361406: build green, e2e RED x4 - all harness-vs-clock
  (see Task 11), Deploy skipped.

## Task 11 - e2e harness vs the 20 TPS clock (2026-10-31)
- This round: sandbox reset rolled the repo back to 219f3b3 AND ate the
  two unpushed test commits (a2fc9f1, 3c7e0be). Recovered 05dae78 from
  origin; the test commits were re-derived from the failure analysis.
- The four e2e failures on 05dae78 were all test harness, not product:
  budgets sized at the accidental 60 TPS (crafting last test ran 6.8 min
  vs a 7.0 min cap), the 60s screenshot default (sky), the 120ms
  right-click window coalescing on loaded runners (features place), and
  node-side polling blind to per-tick destroy stages (features dig).
- Key insight: the bundle REPLACES window.__eaglercraftGame once per
  tick, so an in-page setter trap observes every tick's destroy stage
  even when a slow frame bursts a dozen ticks between polls; the dig
  release is an in-page canvas mouseup the moment queryBlock returns air
  (the node round-trip loses to vanilla's 5-tick continue-dig delay).
  Place = aim->right-click->verify retry loop, 300ms press window. Join
  tail = select->join->observe retry with per-attempt screen sampler.
- Also checked the "second block breaks instantly" observation: NOT a
  product bug - the port aborts on target change and restarts after
  vanilla's 5-tick delay with fresh progress; the old spec's 30s hold
  simply dug on (vanilla hold-to-continue).
- Commit 7b95dcd. Local: sky 1.6m, features 6.4m (all three), mouse-path
  55s where it flailed for 8 minutes.
- Lesson: poll-based observation is wall-time-bounded; tick-accurate
  assertions need in-page traps on the per-tick bridge replacement.
- CI run 41202981498 on 7b95dcd: build green, E2E green (the three
  reworked suites passed on fresh runners), Deploy green.
- Production verified (probe_live_final.mjs vs applepie6969.github.io):
  panorama 2.021 deg/s, steady-state walk 0.2159 b/tick = the vanilla
  invariant (4.317 b/s / 20 TPS), zero console errors, screenshot shows
  full dirt cross-sections on every slope - the void signature gone.

## Task 11 — round 3 of "block sides invisible": root cause found by x-ray, not screenshots (2026-10-31)

User report: sides STILL invisible after the FaceInfo fix. Built
scripts/probe_face_repro.mjs: GL-state recorder (init-script wrap of
enable/disable/cullFace/frontFace), world sculpting via setScene,
per-triangle normal x-ray of the uploaded VBOs via debugMesh, cull
on/off screenshots. Findings: hole walls + terrain sides present and
outward-wound in the VBOs; GL state vanilla (frontFace CCW, cullFace
BACK); screenshots showed black void bands at every terrain edge and
around holes, filling in with CULL_FACE force-disabled. Only a
POSITIONAL defect fits: emitFace was called with the already-offset
plane (y+1) for FACE_UP while FaceInfo.UP adds MAX_Y itself -> every
top quad at y+2, a floating canopy 1 block above the sides. Fix: pass
plain y (1c165c4). Lesson: displacement bugs need position assertions -
added FaceTables.java extraction + JVM FaceTablesTest (~0.3s, CI
geometry-unit job) + tests/e2e/geometry.spec.ts (VBO plane invariants:
36 verts, all 6 faces, UP=y+1, hole walls/floor re-emitted, bottom
culled). Full-jar decompile completed and archived
(docs/decompiled-26.1.2-full-src.zip, 6,882 files); audit doc
docs/IMPLEMENTATION-COVERAGE.md; worklog moved into the repo.
