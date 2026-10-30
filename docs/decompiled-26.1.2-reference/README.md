# Decompiled 26.1.2 reference

Ground-truth excerpts from the **official Minecraft 26.1.2 client jar**
(SHA-1 `4e618f09a0c649dde3fdf829df443ce0b8831e65`, verified against
`piston-meta.mojang.com/v1/packages/978e6c0dd077d0f8d5d53887454928f1d3687ba8/26.1.2.json`).

26.1.2 ships unobfuscated, so class and member names are Mojang's own.
Decompiled with Vineflower 1.10.1 (`--silent -lib=client.jar`); the two
"Unable to simplify switch on enum" notes in FaceBakery are Vineflower
cosmetics, not errors.

These files are committed as the evidence base for browser-port fixes -
cite them in commit messages the way the face-winding and movement-tick
fixes do:

| File | Used for |
|---|---|
| `FaceInfo.java` | the canonical per-direction cube-face corner table |
| `FaceBakery.java` | `defaultFaceUV` per-direction uv projection + winding recalculation |
| `CuboidFace.java` | per-vertex uv assignment (minU/minV/maxU/maxV by corner index) |
| `SectionCompiler.java` | the section rebuild pipeline (tesselation, layers, vis graph) |
| `LivingEntity.java` | `travelInAir` / `handleRelativeFrictionAndCalculateMovement` movement physics |
| `KeyboardInput.java` | impulse extraction from key state |

Copyright Mojang AB - reference use within this fork's porting work only.
