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
| Panorama + Mojang logo + splash | Eagler gradient background | Eaglercraft branding is deliberate |
| `title.credits` copyright line | omitted | Eaglercraft should not carry Mojang's copyright line |

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
Create/Edit/Re-Create/Delete are disabled until world persistence exists;
one world ("New World", the milestone world) instead of a save-file scan;
the second line shows the world id and a fixed date, the third the version
string. The single player journey is vanilla-exact: click a row to select
(second click on the selected row joins, like vanilla double-click), or
click the row then **Play Selected World**.

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
| SelectWorldScreen | exact (header 49 / footer 60, 270px rows, 36px entries, footer grid) | select-then-play flow live; search/create/edit/delete/re-create disabled until their features exist |
| Hotbar | exact + real sprite | selection follows `selectedHotbarSlot` |
| Crosshair | exact + real sprite | sprite blit (vanilla XOR-blends when hostile indicators exist; ours is the plain sprite) |
