# NTM-Space Flower Fix

Client-side fix for **NTM-Space**: the **Wild Strawberries** and **Wild Mint** flowers rendered purple/black because
the add-on rebuilt their textures too late for HBM's code-based model baking. This re-stitches the sprites and
re-bakes the two flowers at the right moment. Fixes upstream NTM-Space issue #80. Minecraft 1.12.2.

See **[Releases](../../releases)**. MIT licensed.


## Compile-only jars

The build compiles against these jars in `libs/` (other authors' mods / APIs). They are not included in this repo — get them from their official pages and drop them in `libs/` before building:

- `ntmce.jar`
- `ntmspace.jar`
