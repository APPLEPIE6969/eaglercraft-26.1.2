# UI Fidelity: extracted vanilla MC 26.1.2 spec

Ground truth for every number in this document: the **official Minecraft
26.1.2 client jar** (`client.jar`, 38,113,927 bytes, SHA-verified against
`piston-meta.mojang.com/v1/packages/58e6c8191.../26.1.2.json`), decompiled
with Vineflower 1.10.1. 26.1.2 ships unobfuscated, so class/member names in
the decompile are Mojang's own. Tooling note: REA (`rea-agents` CLI) was
evaluated first per project convention; its free workflows target
native/JS/APK evidence and route Java archives through the commercial JEB
provider, so the decompiler path above is the equivalent evidence-based RE
for a JVM artifact.

## Sprite pipeline (`GuiSprites` + `EaglerGuiImpl`)

Since the fidelity-sprite milestone, every 2D element below renders with
**Mojang's own pixels**: the PNGs listed here were extracted verbatim from
the 26.1.2 client jar into `public/assets/gui/sprites/` and are uploaded as
NEAREST-filtered WebGL2 textures at boot (`GuiSprites`, async with a
procedural fallback for the first frames). `EaglerGuiImpl` batches them as
textured quads in the same VBO as solid fills (solid fills sample a 1x1
white texture, so one shader covers both).

| Sprite key | Source in the jar | Drawn at |
|---|---|---|
| `widget/button` / `_highlighted` / `_disabled` | `gui/sprites/widget/button*.png`, 200x20 | nine-slice, **border 3** (from the sprite's `.png.mcmeta`: `nine_slice, width 200, height 20, border 3`) |
| `widget/text_field` | 200x20 | 1:1 search box |
| `hud/crosshair` | 15x15 | 1:1 @ ((w-15)/2, (h-15)/2) |
| `hud/hotbar` | 182x22 | 1:1 @ (w/2-91, h-22) |
| `hud/hotbar_selection` | 24x23 | 1:1 @ (-1,-1) of the selected slot |
| `misc/unknown_server` | 128x128 | 32x32 world-list icon (a fresh world has no icon.png) |
| `icon/language`, `icon/accessibility` | 15x15 | centered in the 20x20 icon buttons |
| `gui/menu_background` | 16x16 tiled | fullscreen menu backdrop |
| `gui/menu_list_background` | 16x16 tiled | the world-list area |
| `gui/header_separator` / `gui/footer_separator` | 32x2 | 2px lines above/below the list |

## TitleScreen (`net.minecraft.client.gui.screens.TitleScreen`)

Vanilla `init()` (decompiled, 26.1.2):

```java
int spacing = 24;
int topPos = this.height / 4 + 48;
// row 1: Singleplayer   200x20 @ (width/2 - 100, topPos)      -> SelectWorldScreen
// row 2: Multiplayer    200x20 @ (width/2 - 100, topPos + 24)  (disabled when banned)
// row 3: Realms         200x20 @ (width/2 - 100, topPos + 48)
topPos += 36;                                                   // -> height/4 + 84
// language      20x20 @ (width/2 - 124, topPos)
// Options       98x20 @ (width/2 - 100, topPos)
// Quit Game     98x20 @ (width/2 +   2, topPos)                -> minecraft.stop()
// accessibility 20x20 @ (width/2 + 104, topPos)
// copyright PlainTextButton @ (width - textWidth - 2, height - 10)
```

Known intentional deviations in our port:

| Vanilla | Ours | Why |
|---|---|---|
| `minecraft.stop()` quits the process | page reload (`window.location.reload()`) | browser-honest equivalent |
| Mojang logo + splash text | DOM overlay branding (Eaglercraft / Minecraft 26.1.2) | Eaglercraft branding is deliberate; the panorama behind it is real |
| `title.credits` copyright line | omitted | Eaglercraft should not carry Mojang's copyright line |

## Panorama (CubeMap, `net.minecraft.client.renderer.CubeMap` + `Panorama`)

Vanilla 26.1.2 renders the title/menu background as a perspective-projected
unit cube textured with a `GL_TEXTURE_CUBE_MAP` (decompiled, 26.1.2):

```java
// CubeMap.render(10.0F, spin)   (called from GuiRenderer while the
// PanoramaRenderState is set - i.e. every screen while level == null)
projection.setupPerspective(0.05F, 10.0F, 85.0F, window.width, window.height);
modelViewStack.rotationX(PI);            // vanilla's Y-flip
modelViewStack.rotateX(10 deg);
modelViewStack.rotateY(-spin);
// 24-vertex unit cube (positions ARE the sample directions), 36 quad indices,
// core/panorama shader: texCoord0 = Position; fragColor = texture(Sampler0, texCoord0)
// no depth test: the camera sits inside a convex cube, every ray exits one face

// Panorama.extractRenderState:
spin = Mth.wrapDegrees(spin + realtimeDeltaTicks * panoramaSpeed * 0.1F);   // speed default 1.0
renderState = new PanoramaRenderState(-spin);

// CubeMapTexture: GL faces [+X,-X,+Y,-Y,+Z,-Z] <- panorama_[1,3,5,4,0,2].png,
// stacked with copyRect(swapX=false, swapY=true) -> rows flip before upload
```

Asset note: the 26.1.2 jar ships **1x1 stub PNGs** in a uniform slate color
RGB(98,111,113) and a fully transparent `panorama_overlay.png` - with the
default assets the vanilla 26.1.2 title background is exactly that flat color
(any texture pack can drop real panorama faces in). Our port serves the
**official 1.8.9 panorama faces** (256x256, extracted verbatim from the
piston-meta client jar) at
`public/assets/gui/sprites/title/background/panorama_{0..5}.png`, so the
title screen shows the classic slowly rotating scene through the faithful
cubemap pipeline. Until the faces decode, `PanoramaRenderer` shows the flat
stub color - bit-identical to vanilla-without-a-pack.

The `options.accessibility.panorama_speed` slider (default 1.0) is honored
as a constant; the options UI does not exist yet.

## Day/night sky (`SkyRenderer` + `SkyTimeline`, from `SkyRenderer.class` + `timeline/day.json`)

Vanilla 26.1.2 renders the overworld sky through
`net.minecraft.client.renderer.SkyRenderer` driven by data-driven
environment attributes; the day cycle lives in
`data/minecraft/timeline/day.json` (period 24000, clock minecraft:overworld).
Our port mirrors it 1:1:

- `SkyTimeline.java` is GENERATED by `scripts/gen_sky_timeline.py` from the
  jar (edit the script, not the file): the sky/fog base colors from
  `dimension_type/overworld.json` (`#78a7ff` / `#c0d8ff`), the WRAPPED
  multiply tracks (day x`#ffffff`, night x`#000000` sky / x`#0f0f16` fog,
  linear ramps 11867-13670 and 22330-24133), the merged sky-light dimming
  (day 1,1,1; night `#7a7aff` x 0.24), the GATED star-brightness track
  (0 at day, 0.5 plateau at night) and the 32-keyframe
  sunrise_sunset_color track.
- Sun/moon/star angles: `360 x cubic_bezier(0.362,0.241,0.638,0.759)`
  over the period from t=6000 (the two same-tick keyframes are the wrap
  seam); the moon rides 180 deg ahead. Vanilla moved off the old
  sinusoidal celestialAngle to this eased curve - we follow the data.
- Geometry (verbatim vanilla): top disc fan (center y=+16, 17 rim verts at
  radius 512), bottom disc for the dark disc (rendered black, translated
  +12, when the eye is below the horizon height 63), 16-segment sunrise
  fan (center y=100, ring 120/120/-40, z-scaled by the color alpha -
  vanilla's fade trick), sun quad scale 30 at distance 100, moon quad
  scale 20 with the per-phase textures, 1500-iteration star field seeded
  with 10842L (java.util.Random = vanilla LegacyRandom sequence).
- Shaders: core/sky fog semantics (max of spherical linear and cylindrical
  step, fog start 0, FogSkyEnd = SKY_FOG_END_DISTANCE default 512 = the
  disc radius), position_color for the sunrise fan (TRANSLUCENT blend),
  position_tex for the sun/moon (OVERLAY blend), stars (OVERLAY). All sky
  draws run with depth test/write off and the camera ROTATION as the
  model-view; the world pass draws over the sky afterwards.
- Moon phase: classic `floor(dayTime / 24000) % 8` over the MoonPhase enum
  order - timeline/moon.json's visual/moon_phase float track (period
  192000) maps 1:1 onto that sequence.
- The day counter runs FREE (never wraps, like vanilla's level.dat
  Data.day); it persists to localStorage (`eaglercraft.world.time.<name>`)
  on save-and-quit and restores on load. New worlds start at 1000 (dawn).
- World dimming: `WorldGlRenderer` takes a `u_skyLight` uniform =
  SKY_LIGHT_COLOR x SKY_LIGHT_FACTOR from the same timeline (the
  honest stand-in for vanilla's per-vertex sky/block light split, which
  the flat-preset renderer does not model yet).
- The clear color is the timeline fog color (vanilla clears to the fog
  color); menus keep the classic constant behind the panorama.
- Telemetry: the `gameplay.sky` snapshot (dayTime, skyColor, fogColor,
  sunAngle, starBrightness, moonPhase, skyLight) plus the bridge
  `setDayTime(t)` request (the /time set equivalent) drive the
  `sky.spec.ts` regression tests.

Asset note: `textures/environment/celestial/sun.png` and the eight
`celestial/moon/<phase>.png` files are extracted verbatim from the jar to
`public/assets/world/environment/` and load asynchronously (while missing,
the disc + stars still render).

## Menu background blur (`post_effect/blur.json` + `box_blur.fsh`)

Vanilla 26.1.2 blurs whatever a screen drew behind its GUI
(`GameRenderer.processBlurEffect` runs the post effect once per frame,
from `GuiRenderer` at the blur stratum marker):

```java
// Screen.extractBackground (out of world):
//   extractPanorama (level == null)
//   extractBlurredBackground: if (menuBackgroundBlurriness >= 1) blurBeforeThisStratum()
//   extractMenuBackground: blit menu_background (alpha 64) over the blur
// post_effect/blur.json: SIX box_blur passes (H,V,H,V,H,V), each rendered
//   with core/screenquad, input sampled bilinear ("bilinear": true)
// box_blur.fsh (verbatim math):
//   actualRadius = Radius >= 0.5 ? round(Radius) : float(MenuBlurRadius)
//   for (a = -actualRadius + 0.5; a <= actualRadius; a += 2.0)
//       blurred += texture(In, texCoord + step * a);      // step = oneTexel * BlurDir
//   blurred += texture(In, texCoord + step * actualRadius) / 2.0;
//   fragColor = blurred / (actualRadius + 0.5)
```

- Option: `options.accessibility.menu_background_blurriness`, range 0..10,
  **default 5** (`Options.BLURRINESS_DEFAULT_VALUE = 5`).
- Vanilla `TitleScreen.extractBackground` is **empty** - the title screen
  shows the SHARP panorama with no tiles; every other out-of-world screen
  blurs and then tiles `menu_background` over it. Our port mirrors this
  with `Screen.shouldBlurBackground()` (TitleScreen overrides false).
- Our `MenuBlurRenderer` mirrors the pipeline 1:1: the panorama cube draws
  into an offscreen RGBA8 target, six ping-pong blur passes, then a blit
  to the screen before the GUI draws.

## Menu background tiling (`extractMenuBackgroundTexture`)

Vanilla draws the menu tiles with `graphics.blit(GUI_TEXTURED,
menu_background, x, y, 0, 0, width, height, 32, 32)` - the last two
arguments are the declared texture dimensions, so the 16x16
`menu_background` / `menu_list_background` PNGs repeat every **32 GUI
pixels** (2x zoom), anchored at the screen origin (u=v=0). Our
`drawSpriteTiled(..., destTileSize=32)` reproduces exactly this.

## CreateWorldScreen (`...screens.worldselection.CreateWorldScreen`)

Ported from the decompile of `CreateWorldScreen`, its three tabs
(`GameTab` / `WorldTab` / `MoreTab`), `TabNavigationBar`, `TabButton`,
`HeaderAndFooterLayout`, `CommonLayouts` and `SwitchGrid`:

```java
// TabNavigationBar: HEIGHT=24, tabsWidth = min(400, w) - 28,
//   tabWidth = roundToward(tabsWidth / 3, 2), bar x = roundToward((w - tabsWidth)/2, 2)
//   header_separator 2px at y=22 left of the first tab and right of the last
//   tab sprites widget/tab(_selected)(_highlighted), nine-slice borders 2/2/2/0
// content y = 24 + (h - 57 - contentH) / 6  (tab area + FrameLayout alignY 1/6)
// GameTab (rows 8 apart): "World Name" label + EditBox 208x20 (value "New World",
//   hint "World Name"), then 210x20 CycleButtons:
//   "Game Mode: Survival|Hardcore|Creative" (Hardcore locks Difficulty to Hard
//    and disables Allow Cheats), "Difficulty: Peaceful|Easy|Normal|Hard",
//   "Allow Cheats: OFF|ON"
// WorldTab (2 cols, col spacing 10): "World Type: Default|Superflat|Large
//   Biomes|AMPLIFIED" 150x20 + "Customize" 150x20, "Seed for the world
//   generator" label + EditBox 308x20 (hint "Leave blank for a random seed"),
//   SwitchGrid 310 wide: "Generate Structures" / "Bonus Chest", 44x20 ON/OFF
//   buttons (row spacing 4)
// MoreTab: "Game Rules" / "Experiments" / "Data Packs" 210x20
// Footer (33px, children centered): "Create New World" + "Cancel", 150x20,
//   8px apart, centered -> y = h - 33 + 6
```

EditBox rendering: `widget/text_field` / `_highlighted` sprite (nine-slice
border 1), text `0xFFE0E0E0` (no shadow) 4px inside the left edge,
vertically centered; hint `0xFF7F7F7F` while empty and unfocused; cursor
`0xFFD0D0D0` blinking on a 500ms half period; vanilla `displayPos`
scrolling when the text overflows.

Keyboard input is wired through a new `PlatformInput.KeyEventObserver`:
DOM keydown -> printable `event.key` chars become `Screen.charTyped`
(control codes < 30 excluded), everything else routes
`keyPress -> Screen.keyPressed`; ESC pops back to Select World.

Known deviations: Create joins the milestone world via the same
`Minecraft.doWorldLoad` path as Play (persistence arrives with the
world-storage milestone), so seed / game mode / difficulty / world type
are honest UI state that does not feed the generator yet; "Customize",
"Game Rules", "Experiments" and "Data Packs" are disabled (their
sub-screens are later milestones); no tooltips yet.

## Font (`EaglerFont`, from `textures/font/ascii.png` + `font/include/default.json`)

The GUI font is the real vanilla bitmap font, regenerated byte-exact from the
official client jar by `scripts/gen_font.py` (the table in `EaglerFont.java`
is GENERATED - edit the script, not the file):

- 8x8 glyph cells from `ascii.png` (128x128, 16x16 grid, code = row*16+col),
  ascent 7, line height 8 (so widget text centers at vanilla's
  `(height - 8) / 2`).
- Widths follow vanilla `BitmapProvider$Definition.getActualGlyphWidth`:
  scan the cell right-to-left, first column with any non-transparent pixel
  gives width = col+1; advance = width + 1, except space which
  `include/space.json` pins to 4.
- String width = sum of advances including the trailing one (so a single 'A'
  is 6 wide, two spaces 8 - vanilla `Font.width` semantics).
- Known TeaVM quirk (cost us three CI rounds): its incremental string-concat
  emit mis-assigns `StringBuilder.append` slots for chains mixing operand
  types or carrying ternaries - keep hot concat paths pure-String or use
  explicit appends (see `WorldStorage.serialize`, `formatDate`).

## PauseScreen (`...screens.PauseScreen`)

ESC in world opens the game menu. Layout from the decompiled 26.1.2 screen:
"Game Menu" title at y=40, 2-column grid (`BUTTON_WIDTH_FULL=204`,
`BUTTON_WIDTH_HALF=98`, 4px cell padding, 4px row gaps) aligned at the 25%
height mark (`FrameLayout.alignInRectangle(..., 0.5F, 0.25F)`):

```
[       Back to Game        ]
[ Advancements ][ Statistics ]
[ Send Feedback][ Report Bugs]
[ Options...   ][ Open to LAN]
[   Save and Quit to Title   ]
```

Behavior: the world + HUD behind the menu run through the menu blur
(`MenuBlurRenderer.blurScreenContent` - the default framebuffer is captured
with `copyTexSubImage2D`, no y-flip since both GL spaces share the bottom-up
origin, then the same six box_blur passes run); ESC returns to the game;
Send Feedback / Report Bugs open `feedback.minecraft.net` / `bugs.mojang.com`
(vanilla's openUri); Advancements, Statistics and Open to LAN render in
vanilla's disabled style until their systems exist; Save and Quit drops the
level back to the title screen.

## OptionsScreen + AccessibilityOptionsScreen (`...screens.options.*`)

OptionsScreen: "Options" title, header row = FOV slider (30..110, "Normal"
at 70, "Quake Pro" at 110) + the in-world difficulty CycleButton (vanilla
`createWorldOptionsButtonOrDifficultyButton`), the 2-column sub-screen grid
(Skin Customization / Sound Settings / Video Settings / Controls / Language
/ Chat Settings / Resource Packs / Accessibility / Telemetry / Credits &
Attribution), Done footer (200px). Accessibility is the one live sub-screen
today; the rest render disabled until their systems exist.

AccessibilityOptionsScreen owns the vanilla `menuBackgroundBlurriness`
slider (0..10, default 5, persisted to localStorage under the namespaced
`options.menuBackgroundBlurriness` key). Sliders are the vanilla widget:
`widget/slider` track (nine-slice border 1) + `widget/slider_handle` (8x20,
border 2/2/2/3), click-to-set and drag semantics from
`AbstractSliderButton`.

## World persistence (`WorldStorage`)

The LevelStorageSource stand-in: created worlds persist to localStorage
(one namespaced `eaglercraft.worlds` record per list, fields
name/seed/gameMode/cheats/structures/bonusChest/lastPlayed/version).
SelectWorldScreen renders the real list (exclusive selection, vanilla
MM/DD/YY date line, Delete removes records, double-click or Play joins the
selected world); CreateWorldScreen.onCreate persists before
`doWorldLoad`; `touchWorld` refreshes lastPlayed on every load. A fresh
profile is seeded with the milestone "New World".

## Button (`net.minecraft.client.gui.components.Button`)

Decompiled constants (26.1.2): `SMALL_WIDTH = 120`, `DEFAULT_WIDTH = 150`,
`BIG_WIDTH = 200`, `DEFAULT_HEIGHT = 20`, `DEFAULT_SPACING = 8`.

Render state machine (three `WidgetSprites`, now the real sprites):

| Vanilla state | Sprite | Fallback (before PNGs load) |
|---|---|---|
| normal | `widget/button` | body `0xFF6F6F6F`, bevel light `0xFFA8A8A8` / dark `0xFF565656` |
| hovered or focused | `widget/button_highlighted` | body `0xFF8291B8` |
| disabled | `widget/button_disabled` | body `0xFF4A4A4A`, dark bevels, no hover |

Label: **white `0xFFFFFFFF` when active, `0xFFA0A0A0` when disabled**
(`AbstractWidget` decompile: `Style.EMPTY.withColor(-6250336)` for the
inactive message; active label renders white). Modern vanilla does **not**
tint the label yellow on hover — that was the 1.8 look; hover is expressed
by the highlighted sprite only.

## SelectWorldScreen (`...screens.worldselection.SelectWorldScreen`)

Ported from the decompile of `SelectWorldScreen`, `WorldSelectionList`,
`AbstractSelectionList` and `AbstractScrollArea`:

```java
// HeaderAndFooterLayout(this, 49, 60)
// title "Select World" centered, y = 8
// search EditBox 200x20 @ (width/2 - 100, 22), hint "Search..." (gray+italic)
// list: y = 49 .. height-60; getRowWidth() = 270 (fixed), rows centered
// entry height 36 (WorldSelectionList ctor: super(..., 0, 36))
//   icon 32x32 @ (rowLeft+2, entryY+2); text @ rowLeft+37:
//   name @ contentY+1, id/date @ contentY+12, info @ contentY+21, gray -8355712
//   selection: fill(entry, -8355712 outline) + fill(+1..-1, black)
// footer GridLayout 4 cols x 71 + 8 spacing (=308, centered):
//   row 1 @ h-52: Play Selected World (span 2 = 150), Create New World (span 2)
//   row 2 @ h-28: Edit / Delete / Re-Create / Back (71 each)
// updateButtonStatus(null): Play + Edit + Re-Create + Delete start disabled
// scrollbar: none when the list does not overflow
//   (default ScrollbarSettings has a null disabled sprite -> nothing draws)
```

Known deviations: the search box is visual-only (text input not ported);
Edit/Re-Create/Delete are disabled until world persistence exists; one
world ("New World", the milestone world) instead of a save-file scan;
the second line shows the world id and a fixed date, the third the version
string. The single player journey is vanilla-exact: click a row to select
(second click on the selected row joins, like vanilla double-click), or
click the row then **Play Selected World**. **Create New World** now opens
the tabbed CreateWorldScreen (see its section).

## HUD (`net.minecraft.client.gui.Gui`, extract pipeline)

26.1.2 renders the HUD through sprite blits; the layout constants from the
decompile (now drawn with the real sprites):

```java
// crosshair: 15x15 sprite, centered by box origin
blitSprite(CROSSHAIR_SPRITE, (guiWidth - 15) / 2, (guiHeight - 15) / 2, 15, 15);
// hotbar: 182x22
blitSprite(HOTBAR_SPRITE, screenCenter - 91, guiHeight - 22, 182, 22);
// selection: 24x23, one pixel up/left of the hotbar, 20px slot pitch
blitSprite(HOTBAR_SELECTION_SPRITE, screenCenter - 91 - 1 + selectedSlot * 20,
           guiHeight - 22 - 1, 24, 23);
// slot content origin (items, when we render them):
//   x = screenCenter - 90 + i * 20 + 2, y = guiHeight - 19
```

The procedural solid-quad path remains as the fallback for the frames
before the PNGs finish downloading (and as documentation of the colors).

## Conformance status

| Element | Geometry | Behavior |
|---|---|---|
| TitleScreen button grid | exact (topPos, pitch, sizes, x-offsets) | Singleplayer -> SelectWorldScreen; Quit = reload; Multiplayer/Realms/Options disabled until their screens exist |
| Button widget | exact constants + real sprites (nine-slice border 3) | press-to-activate on left click (vanilla fires on press); keyboard activation (Enter/Space) not yet ported |
| Menu blur | exact (6x box_blur, bilinear half-step taps, radius = option, default 5) | title screen sharp; menus blurred under the tiles out of world; world+HUD blurred under the pause/options screens in world |
| Menu background tiles | exact (16px PNG repeating every 32 GUI px, anchored at screen origin) | alpha-64 tiles over the blurred panorama |
| CreateWorldScreen | exact (tab bar 24px, content alignY 1/6, footer h-33+6, widget sizes from the decompile) | tabs switch, CycleButtons cycle, EditBoxes accept text (keyboard), Create joins a world; Customize/Game Rules/Experiments/Data Packs disabled; no tooltips |
| SelectWorldScreen | exact (header 49 / footer 60, 270px rows, 36px entries, footer grid) | real localStorage world list: select-then-play, Delete, vanilla date line; search/edit/re-create disabled until their features exist |
| Hotbar | exact + real sprite | selection follows `selectedHotbarSlot` |
| Crosshair | exact + real sprite | sprite blit (vanilla XOR-blends when hostile indicators exist; ours is the plain sprite) |
| Font | exact glyphs + widths from the jar's ascii.png (ascent 7, space advance 4) | vanilla variable-width rendering; shadow = +1px copy at 25% color |

## Feature 4: block palette, 36-slot inventory and the container screens

### Registry id policy (no decompile available)

The original `mc-client-26.1.2.jar` (the local decompile reference) was
lost with a sandbox reset and its only provenance was Mojang's piston
manifest, so new-block numeric ids could no longer be read from the
decompile. Policy:

- `1/4/9/10/15/33` (stone, cobblestone, grass_block, dirt, oak_planks,
  bedrock) are verified from the decompile and never change.
- `5-8` (stone-brick family), `11-14` (dirt family incl. mud), `16-23`
  (planks family) and `34-49` (the ore ladder) follow the registry runs
  those anchors imply - high confidence from modern-vanilla ordering.
- Every other entry uses a port-assigned id `>= 256`
  (scripts/gen_block_palette.py assigns them deterministically in
  definition order). Save format stores ids verbatim; they are stable
  for the life of the port.

### Blocks (158 entries)

- Textures are the real jar PNGs (static frame 0; `.png.mcmeta`
  animation metadata is not ported) copied to
  `assets/world/textures/` (3D array layers) and
  `assets/gui/sprites/block/` (GUI icons) by
  scripts/gen_block_palette.py.
- destroySpeed / requiresCorrectTool follow the vanilla
  Properties.strength table (stone 1.5, obsidian 50, ancient debris 30,
  leaves 0.2, ...); all full cubes use friction 0.6.
- Biome tint bake at texture upload: grass top 0x91BD59 (plains grass),
  leaves 0x77AB2F (plains foliage), birch 0x80A755, spruce 0x619961 -
  the same bake vanilla's biome colormaps perform. The world shader's
  alpha-0.5 discard gives leaves/glass cutout for free.

### Inventory (vanilla 36-slot model)

- `Inventory.items`: 0-8 hotbar, 9-35 main. `addItem` = vanilla
  getSlotWithRemainingSpace-then-getFreeSlot (hotbar-first).
- Slot clicks implement vanilla AbstractContainerMenu.doClick: left
  pickup/place/merge/swap, right pickup-ceil-half / place-one, shift
  quick-move between the main inventory and the hotbar. The carried
  (cursor) stack is `AbstractContainerMenu.carried`.

### Survival InventoryScreen

- Panel geometry measured from the real container texture's own inset
  borders (176x166): armor x=7 y=7+18r, craft 2x2 (97,17)+(115,35),
  result (153,27), main (7,83) 9x3, hotbar (7,141).
- Working: the 27 main + 9 hotbar slots (click/shift-click), carried
  stack, tooltips, E/ESC close.
- Simplifications (no backing systems yet): armor and the 2x2
  crafting grid render as inert vanilla slots; clicking outside the
  panel does not drop the carried stack (no item entities).

### CreativeModeInventoryScreen

- Panel 195x136 from tab_items.png (grid (8,17) 9x5, hotbar row y=111,
  destroy slot (174,111), 12x15 scroller on a 90px track at x=174),
  all measured from the texture.
- Behavior is vanilla's item-picker tab: left = full stack, right =
  one, click-with-carried on the grid = void, destroy slot = void,
  wheel pages the grid, E/ESC close.
- Simplifications vs vanilla: a single tab row with the three
  populated categories (Building / Colored / Natural) - the search
  tab (no localized search index) and the player-inventory tab are
  not ported yet. Tabs nine-slice-stretch the 26x32
  tab_top_selected/unselected sprites to the label width.

### Telemetry bridge additions

`gameplay.main` (27 slots), `gameplay.carried` and CycleButton ids of
the form `gamemode:survival` (prefix + current value) for automation.

## Crafting (Feature 8)

The vanilla recipe data drives the grids, baked from the jar's
data-driven files by `scripts/gen_crafting_recipes.py` into
`CraftingRecipes.java` (25 recipes: planks x8, stick, crafting_table,
wooden/stone/iron tool ladders; tags recursively expanded to port ids
at generation time; skips logged in the generated header).

- Item ids: port-assigned space at 4096+ (`Items`), blocks stay below
  512; tools stack to 1, ingredients to 64 (vanilla limits).
- Matched like vanilla `ShapedRecipe`/`ShapelessRecipe`: shaped = the
  grid's non-empty bounding box compared against the pattern (both
  horizontal mirrors), so 3-wide tool patterns are uncraftable in the
  2x2; shapeless = ingredient multiset with backtracking.
- Result slot: left takes one craft onto an empty/compatible cursor,
  shift crafts repeatedly into the inventory; every take consumes one
  item per non-empty grid cell (no container leftovers in the set).
- 2x2 grid + result live in the survival InventoryScreen
  ((97,17)/(115,17)/(97,35)/(115,35), result (153,27) - measured from
  the texture insets; the modern result cell draws 26x26 centered on
  the item origin, same math as the jar's menu code).
- crafting_table block (port-assigned id 375, `TAB_NONE`, hardness 2.5)
  is craftable from four planks (2x2), placeable, and right-click
  opens the 3x3 `CraftingTableScreen` (vanilla `CraftingMenu`:
  grid (30,17), result (124,35), inventory block at (8,84)).
- Both screens return the grid contents AND the carried stack to the
  inventory on close (vanilla `AbstractContainerMenu.removed`); overflow
  is lost (the documented item-entity-free simplification).
- Known deviations: no recipe book button on the crafting table screen;
  iron tools ship as recipe data but iron_ingot has no in-world source
  until the furnace milestone; items are not in the creative tabs yet.

## Engine fixes surfaced by the crafting e2e

- `Inventory.setCarried` clamped the stale carried count instead of the
  requested one (every pickup produced an empty cursor stack; the old
  soft telemetry assertions hid it).
- The telemetry bridge's `waitForTelemetry` returned the last snapshot
  on timeout and every caller asserted truthiness - a vacuous pass that
  masked the above; it now returns null on timeout.
- `Items` clinit order: the registry collections must precede the
  register calls (a clinit-order slip silently killed ALL sprite
  downloads - the panorama read false at the title screen).
- `MouseHandler.grabMouse` is optimistic (the browser grant is async; a
  synchronous check always read false and swallowed the next click),
  `tick()` drops the phantom look delta of a late lock grant, and the
  wheel routes through vanilla's `Inventory.swapPaint` whenever no
  screen is open (the port's old path updated a cosmetic Gui field).
- The bridge's `setHotbarSlot` no longer selects the slot it fills, and
  PlatformInput's per-button input latch counts presses instead of
  overwriting (rapid clicks queue instead of vanishing on slow frames).

## 15. Multiplayer / the real gateway protocol (26.1.2)

| Element | Fidelity | Notes |
|---------|----------|-------|
| Connection state machine | exact (Handshake -> Login -> Configuration -> Play per docs/PROTOCOL.md, protocol 775) | WebSocket binary frames; permessage-deflate optional (the browser falls back when the gateway doesn't negotiate it) |
| ConnectScreen join flow | exact (vanilla status line at height/2-50, 10s join guard) | real handshake + Login Hello; "Logging in..." during configuration; the play Login packet enters the world |
| DirectConnectScreen / JoinMultiplayerScreen | exact layouts (previous milestone) | Direct Connection feeds the real protocol now - no more "does not support multiplayer yet" |
| Play phase | server-authoritative | Level Chunk streaming (section-palette form, 4-chunk view / 6-chunk unload radius), Block Update dig/place, Set Container Slot drops, Player Position teleports (confirmed), keep-alives every 15s (0x2C S->C / 0x11 C->S) |
| MultiPlayerGameMode | vanilla dig lifecycle over the wire | START/ABORT/FINISH Player Actions drive the server; the client keeps the crack-overlay state machine; Use Item On for placement; the local edit path stays singleplayer-only |
| Remote world storage | vanilla-equivalent reads | base chunks natively in section bytes; high-id palettes (oak logs/leaves) in the edit overlay; physics held until the first chunk lands (vanilla's "Loading terrain" hold) |
| PauseScreen quit | exact (vanilla disconnectButtonLabel) | "Disconnect" for multiplayer worlds (connection closes, nothing persists), "Save and Quit to Title" for local ones |
| Known gaps | - | no entity spawn/rendering (no other players visible), no chat HUD (chat logs to console), no server-list pinging (the STATUS state exists and is smoke-tested), Bungee/Velocity still on the session-registry protocol |

### Multiplayer implementation notes

- TeaVM interop: `java.util.UUID` loses its methods across call
  boundaries (the handshake rides two longs); JSFunctor callbacks must
  be invoked as plain JS functions (`callback(e.data)`, not
  `callback.call(...)`); the Connection's pre-open send burst queues
  until onopen; the 0x02 id doubles as Login Success AND Configuration
  Finish - only the state discriminates them.
- The patcher (`scripts/patch_default_methods.py`) is now idempotent:
  re-running it on an already-patched bundle used to corrupt the Long
  helpers (a BigInt "Division by zero" at boot).
