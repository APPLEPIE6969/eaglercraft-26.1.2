# Implementation Coverage — EaglerCraft 26.1.2 vs the real client

Straight answers to three questions: what was decompiled, what is
implemented, and what is tested. Numbers are generated from the trees,
not estimated.

## 1. Decompilation status: COMPLETE

The official 26.1.2 client jar is unobfuscated (Mojang ships original
class/member names), so Vineflower 1.10.1 needs no remapping step. The
**full** jar has been decompiled and is committed as ground truth:

- `docs/decompiled-26.1.2-full-src.zip` — every class, 6,882 Java files
  (~50 MB loose, 9 MB zipped): `net/minecraft/world` 2,559,
  `net/minecraft/client` 1,813, `net/minecraft/util` 713,
  `net/minecraft/server` 418, `net/minecraft/network` 411,
  `net/minecraft/data` 165, `com/mojang/blaze3d` 149, realms 127, and
  the rest.
- `docs/decompiled-26.1.2-reference/` — the hand-picked files used as
  implementation ground truth so far (FaceInfo, FaceBakery,
  ModelBlockRenderer, SectionRenderDispatcher/Compiler, Direction,
  LivingEntity, LocalPlayer, KeyboardInput, Entity, Minecraft).
- Regeneration: any jar/Vineflower (see `client_version`); the zip was
  produced from the SHA-verified Mojang client jar.

## 2. Implementation status: a substantial subset, deliberately honest

The port is **411 Java files** against 6,882 decompiled - roughly 6% of
the vanilla class count, but the classes chosen are the ones a browser
single-player/multiplayer client needs first. Per system:

| System | Status | Where |
| --- | --- | --- |
| Window/shell, GL4ES-style WebGL2 context | done | `internal/teavm/*`, `PlatformOpenGL` |
| Title screen, options, world select/create, multiplayer screens | done | `client/gui/screens/*` |
| Panorama (26.x face-array renderer, 2.0 deg/s vanilla spin) | done | `adapter/PanoramaRenderer` |
| World storage: level blobs, DataFixerUpper upgrades | done | `world/level/storage`, `util/datafix` |
| World gen (single-dimension overworld terrain, trees) | done (subset) | `ClientLevel` generator |
| Chunk meshing (vanilla FaceInfo tables, face culling, section rebuild) | done | `LevelRenderer`, `FaceTables` |
| Movement physics (fixed 20 TPS, 0.216 b/tick walk invariant) | done | `LocalPlayer`, `Entity` |
| Block breaking/placing, hit outline, destroy stages | done | `LevelRenderer`, `MultiPlayerGameMode` |
| Survival inventory + creative picker screens | done | `Inventory`, `CreativeModeInventoryScreen` |
| Crafting (2x2 inventory + 3x3 table + recipes) | done | `world/item/crafting` |
| Sky: day/night timeline, sun/moon, sky colors | done | `SkyTimeline`, `SkyRenderer` |
| Sound | partial | `client/sounds`, `SoundManager` |
| Multiplayer: connect flow + gateway protocol | done | `network`, `Connection`, `ClientPacketListener` |
| Mob entities (zombies, animals, item drops, arrows) | **missing** | only `world/entity/player` exists |
| Redstone, hoppers, other block entities | **missing** | no block-entity system yet |
| Nether/End dimensions, portals | **missing** | single-dimension world |
| Weather, maps, enchanting/brewing, villages/structures | **missing** | - |

The table is the roadmap order too: entities are the next large system
(the renderer/gameplay already loop per-tick; mobs slot into the
existing entity walk).

## 3. Test status: three layers, fast first

| Layer | Runtime | What it locks | Files |
| --- | --- | --- | --- |
| JVM unit (CI job `geometry-unit`) | ~20 s incl. checkout | face geometry invariants vs the decompile: corners, planes (UP=y+1), outward winding, UV order, RGBA byte packing | `tests/geometry/FaceTablesTest.java` |
| Playwright e2e (17 specs, 48 tests) | the deploy gate | boot, title/worlds/options screens, world entry, HUD, movement tick clock, dig/place mechanics, inventory + creative picker, crafting recipes, sky cycle, multiplayer navigation, gateway protocol, WebGL context, VBO plane invariants incl. hole walls | `tests/e2e/*.spec.ts` |
| Production probes (manual, after deploys) | minutes | live-site panorama spin, walk b/tick vs vanilla, mesh x-rays via `debugMesh`, screenshots with cull on/off | `scripts/probe_*.mjs` |

The y+2 canopy bug ("block sides invisible", round 3) is the case study
for why the layers are ordered this way: it passed screenshots because
face **displacement** changes neither normals nor counts - only the JVM
test's plane assertion and the e2e VBO position check catch it, and both
now run on every push, the JVM one ~20 s into the pipeline.

## 4. Why not Playwright for everything

A browser test pays a full TeaVM bootstrap (about a minute) per
navigation and a software-GL frame loop of 1-4 fps on CI runners; pure
logic invariants (geometry, physics constants, recipe matching, protocol
framing) run on the JVM in seconds with exact assertions instead of
pixel proxies. Playwright stays where a browser is the thing under test:
bundle evaluation, screens, input plumbing, WebGL, deploy artifact.
Rule of thumb: if the assertion can be expressed as Java data in/out,
it belongs in the JVM layer; if it needs a GPU, a DOM or a real
page, it belongs in Playwright.

## 5. Probe tooling (the x-ray)

`window.__eaglercraftGame` exposes `debugMesh(x,y,z)` (per-block mesher
decision + the actual uploaded VBO tuples), `queryBlock`, `setScene`
(world sculpting through the real `setBlockState` path), `setLook`
(camera snap), `setDayTime`. The probes under `scripts/` build on these
to turn "looks wrong on screen" into "this quad is at y+2" in one pass.
