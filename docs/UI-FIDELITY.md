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
| Singleplayer opens `SelectWorldScreen` | `Minecraft.doWorldLoad("New World")` directly | world-select UI not ported yet; the direct load is the milestone's entry path |
| `minecraft.stop()` quits the process | page reload (`window.location.reload()`) | browser-honest equivalent |
| Panorama + Mojang logo + splash | Eagler gradient background | Eaglercraft branding is deliberate |
| Sprite icon buttons with globe/accessibility icons | label-less 20x20 squares | icon sprites not loadable (no PNG pipeline yet) |
| `title.credits` copyright line | omitted | Eaglercraft should not carry Mojang's copyright line |

## Button (`net.minecraft.client.gui.components.Button`)

Decompiled constants (26.1.2): `SMALL_WIDTH = 120`, `DEFAULT_WIDTH = 150`,
`BIG_WIDTH = 200`, `DEFAULT_HEIGHT = 20`, `DEFAULT_SPACING = 8`.

Render state machine (three `WidgetSprites`):
`widget/button` (normal), `widget/button_highlighted` (hovered or focused),
`widget/button_disabled`. Our beveled-quad approximation maps them as:

| Vanilla state | Ours |
|---|---|
| normal sprite | body `0xFF6F6F6F`, bevel light `0xFFA8A8A8` / dark `0xFF565656` |
| highlighted sprite | body `0xFF8291B8` |
| disabled sprite | body `0xFF4A4A4A`, dark bevels, no hover |

Label: **white `0xFFFFFFFF` when active, `0xFFA0A0A0` when disabled**
(`AbstractWidget` decompile: `Style.EMPTY.withColor(-6250336)` for the
inactive message; active label renders white). Modern vanilla does **not**
tint the label yellow on hover — that was the 1.8 look; hover is expressed
by the highlighted sprite only, which our body color reproduces.

## HUD (`net.minecraft.client.gui.Gui`, extract pipeline)

26.1.2 renders the HUD through sprite blits (`hud/hotbar`,
`hud/hotbar_selection`, `hud/crosshair`); the layout constants from the
decompile:

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

We cannot blit the real sprites yet (no PNG decode in the TeaVM port), so
the HUD is drawn with the same geometry out of batched solid quads.

## Conformance status

| Element | Geometry | Behavior |
|---|---|---|
| TitleScreen button grid | exact (topPos, pitch, sizes, x-offsets) | Singleplayer live; Quit = reload; Multiplayer/Realms/Options + icon buttons disabled until their screens exist |
| Button widget | exact constants | press-to-activate on left click (vanilla fires on press); keyboard activation (Enter/Space) not yet ported |
| Hotbar | exact (182x22 @ (w/2-91, h-22), 24x23 selection @ (-1,-1), 20px pitch) | selection follows `selectedHotbarSlot` |
| Crosshair | exact (15x15 @ ((w-15)/2, (h-15)/2)) | drawn as solid quads (vanilla XOR-blends the sprite) |
