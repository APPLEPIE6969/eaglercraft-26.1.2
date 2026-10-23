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
| Menu blur | exact (6x box_blur, bilinear half-step taps, radius = option, default 5) | title screen sharp (vanilla empty extractBackground), other menus blurred under the tiles |
| Menu background tiles | exact (16px PNG repeating every 32 GUI px, anchored at screen origin) | alpha-64 tiles over the blurred panorama |
| CreateWorldScreen | exact (tab bar 24px, content alignY 1/6, footer h-33+6, widget sizes from the decompile) | tabs switch, CycleButtons cycle, EditBoxes accept text (keyboard), Create joins a world; Customize/Game Rules/Experiments/Data Packs disabled; no tooltips |
| SelectWorldScreen | exact (header 49 / footer 60, 270px rows, 36px entries, footer grid) | select-then-play flow live; search/edit/delete/re-create disabled until their features exist |
| Hotbar | exact + real sprite | selection follows `selectedHotbarSlot` |
| Crosshair | exact + real sprite | sprite blit (vanilla XOR-blends when hostile indicators exist; ours is the plain sprite) |
