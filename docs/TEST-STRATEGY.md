# Test Strategy — what is tested, how, and why

EaglerCraft 26.1.2 is tested on three layers, ordered by speed: JVM unit
tests lock pure-logic parity in seconds, Playwright e2e locks everything a
browser is needed for, and production probes verify the deployed bytes
after each deploy. The rule of thumb: **if the assertion can be expressed
as Java data in/out, it belongs to the JVM layer; if it needs a GPU, a DOM
or a real page, it belongs to Playwright.**

## Layer 1 — JVM unit tests (`geometry-unit` CI job, ~25 s incl. checkout)

Plain `javac` + `main()` (no JUnit), run from the repo root so tests can
read the committed decompile zip and the port sources:

| Test | Locks | Pattern |
| --- | --- | --- |
| `FaceTablesTest` | face corners, planes (UP=y+1), outward winding, UV order, RGBA packing | independent copy of the decompiled FaceInfo table |
| `RegistryParityTest` | every port block exists in vanilla 26.1.2; ids unique; every committed world texture is referenced; destroy_stage_0..9 exist | **decompile-canary**: parses `docs/decompiled-26.1.2-full-src.zip` at runtime |
| `PhysicsParityTest` | gravity 0.08, jump 0.42 (`BASE_JUMP_POWER` — same constant name as vanilla), friction 0.98 (`INPUT_FRICTION`), ground accel 0.216…, fly 0.05, walk 0.1, 20 TPS | **twin canaries**: each constant must appear in BOTH the port source and the named vanilla decompile file — if Mojang moves a constant, the vanilla side goes red and forces a re-audit instead of silent drift |
| `RecipeParityTest` | the generated recipe table is structurally sound, references only real registry ids, and still carries vanilla truths (planks ×4, sticks ×4, 2×2 table, pickaxe `XXX/_S_/_S_`) | parses the port registries, walks `CraftingRecipes.ALL` |
| `ProtocolFuzzTest` | VarInt/VarLong/string codecs: 12k+ seeded round-trips, byte-exact differential vs an independent reference encoder, overlong/truncated/hostile-length inputs rejected without hangs or bogus allocations | seeded fuzz (seed 0x5EED — reproducible) |

## Layer 2 — Playwright e2e (the deploy gate, 21 specs / 53 tests, sharded 3×)

Each spec boots the real TeaVM bundle in Chromium (SwiftShader WebGL2),
drives the REAL input paths (mouse events, keyboard, the pause menu, the
world-creation UI) and asserts through the `window.__eaglercraftGame`
telemetry bridge (`queryBlock`, `setScene` — the real `setBlockState`
path —, `debugMesh`, `setLook`, `setDayTime`, gameplay JSON):

| Spec family | Locks |
| --- | --- |
| shell, bundle, assets, hidpi, webgl, context | boot pipeline, EPK assets, shell ids, WebGL2 context |
| features, input, ingame | spawn state, movement/jump/hotbar/scroll, survival dig (in-page tick-accurate destroy stages) and place |
| **placement** | the six-face culling matrix (neighbor attach → probe quad culls → detach → quad re-emerges, all 6 directions, vanilla planes), the 16k section-border remesh (both sections), the real right-click use path |
| **creative** | world creation through the real UI with the `gamemode:survival → hardcore → creative` CycleButton transitions, creative flag, double-tap flight (vanilla 7-tick window), Space/Shift vertical control, landing cancels flight, single-click instant break, picker open/close; the default world stays Survival |
| **persistence** | marker block + day time survive a full Save-and-Quit → page reload → re-enter round trip (the localStorage stand-in for region files) |
| **chaos** | 24 seeded rounds of real-input storms (movement, look snaps, attack/use, hotbar, inventory, pause, day-time jumps, scene edits) with per-round invariants: finite bounded player position, no STATE_CRASHED, tick clock advancing, zero uncaught errors, world still editable afterwards |
| geometry, sky, inventory, crafting, multiplayer, gateway-protocol, pause-options | VBO plane invariants incl. hole walls, day timeline, carried stacks, recipes, gateway flow |

Screenshots are best-effort artifacts, never assertions — the assertions
ride the telemetry bridge (a capture that starves on the software-GL
runner costs seconds, never a red run).

## Layer 3 — production probes (after each deploy)

`scripts/prod_verify.sh` mirrors the full `public/` tree, runs the
geometry spec against the LIVE bytes, and checks the walk invariant /
panorama spin / mesh x-rays through the same bridge.

## Speed: where the minutes go and what keeps them down

- The TeaVM bundle evaluates for ~1 minute per boot and the CI runner
  renders at 1–4 fps — that dominates everything. Countermeasures, in
  order of impact: **3-way CI sharding** (each shard = a fresh runner,
  own client, own world, no cross-talk), **worker-scoped boots** (one
  boot shared by all tests in a worker), **telemetry assertions instead
  of pixel waits**, and pushing everything pure-logic down to the JVM
  layer (seconds instead of browser minutes).
- e2e budgets are sized to the vanilla 20 TPS clock (e.g. the crafting
  spec gets 840 s) — shrinking budgets below the game's own clock
  produces flakes, not speed.

## The Playwright verdict

**Playwright stays.** Everything the browser layer asserts — WebGL2
geometry, real input event plumbing, DOM screens, the actual deployed
bundle — needs a real browser, and no faster alternative produces the
same output: raw CDP or puppeteer would save seconds per boot but forfeit
sharding, retries, traces, reports and the fixture model that makes the
suite maintainable. The honest speed wins came from (1) moving
pure-logic parity to the JVM layer (FaceTables-style tests caught the y+2
bug ~20 s into the pipeline), (2) sharding the browser suite 3×, and
(3) telemetry-driven assertions instead of screenshots. That combination
keeps full-coverage CI at roughly the same wall clock as the old 48-test
serial suite despite the new specs.
